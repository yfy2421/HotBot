package com.bot.service;

import com.bot.model.AssistantMessage;
import com.bot.model.NewsItem;

import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

import static com.bot.util.TextUtils.defaultText;
import static com.bot.util.TextUtils.hasText;

class ConversationStateManager {

    private final AssistantConversationStateStore stateStore;
    private final int maxHistoryMessages;
    private final Duration newsSnapshotRetention;
    private final Function<List<NewsItem>, List<NewsItem>> snapshotItemCopier;

    private final Map<String, Deque<AssistantMessage>> conversations;
    private final Map<String, NewsSnapshotState> recentNewsSnapshots;

    ConversationStateManager(AssistantConversationStateStore stateStore,
                             int maxHistoryMessages,
                             Duration newsSnapshotRetention,
                             Function<List<NewsItem>, List<NewsItem>> snapshotItemCopier) {
        this.stateStore = stateStore;
        this.maxHistoryMessages = maxHistoryMessages;
        this.newsSnapshotRetention = newsSnapshotRetention;
        this.snapshotItemCopier = snapshotItemCopier;

        AssistantConversationStateStore.StoredState storedState = stateStore.load();
        this.conversations = restoreConversations(storedState.conversations());
        this.recentNewsSnapshots = restoreNewsSnapshots(storedState.newsSnapshots());
    }

    void clear(String conversationId) {
        conversations.remove(conversationId);
        recentNewsSnapshots.remove(conversationId);
        persistState();
    }

    void remember(String conversationId, String userText, String assistantText) {
        Deque<AssistantMessage> deque = conversations.computeIfAbsent(conversationId, key -> new ArrayDeque<>());
        synchronized (deque) {
            deque.addLast(copyMessage(AssistantMessage.builder().role("user").content(userText).build()));
            deque.addLast(copyMessage(AssistantMessage.builder().role("assistant").content(assistantText).build()));
            while (deque.size() > maxHistoryMessages) {
                deque.removeFirst();
            }
        }
        persistState();
    }

    List<AssistantMessage> snapshot(String conversationId) {
        Deque<AssistantMessage> deque = conversations.computeIfAbsent(conversationId, key -> new ArrayDeque<>());
        synchronized (deque) {
            return List.copyOf(deque);
        }
    }

    NewsSnapshotState newsSnapshot(String conversationId) {
        return recentNewsSnapshots.get(conversationId);
    }

    NewsSnapshotState reusableNewsSnapshot(String conversationId) {
        NewsSnapshotState snapshot = recentNewsSnapshots.get(conversationId);
        if (snapshot == null || snapshot.items() == null || snapshot.items().isEmpty()) {
            return null;
        }
        boolean reusable = System.currentTimeMillis() - snapshot.storedAtEpochMillis() <= newsSnapshotRetention.toMillis();
        if (!reusable && recentNewsSnapshots.remove(conversationId, snapshot)) {
            persistState();
            return null;
        }
        return snapshot;
    }

    void rememberNewsSnapshot(String conversationId,
                              String requestedLayer,
                              String requestedCategory,
                              List<NewsItem> items,
                              String focusedNewsId) {
        if (items == null || items.isEmpty()) {
            return;
        }
        recentNewsSnapshots.put(conversationId,
                new NewsSnapshotState(
                        defaultText(requestedLayer, "all"),
                        defaultText(requestedCategory, "all"),
                        copySnapshotItems(items),
                        focusedNewsId,
                        System.currentTimeMillis()));
        persistState();
    }

    void focusNewsItem(String conversationId, String newsId) {
        if (!hasText(newsId)) {
            return;
        }
        NewsSnapshotState snapshotState = recentNewsSnapshots.get(conversationId);
        if (snapshotState == null || !snapshotState.hasItems()) {
            return;
        }
        if (newsId.equals(snapshotState.focusedNewsId())) {
            return;
        }
        recentNewsSnapshots.put(conversationId, snapshotState.withFocusedNewsId(newsId));
        persistState();
    }

    void focusSingleNewsItem(String conversationId, List<NewsItem> items) {
        if (items == null || items.size() != 1) {
            return;
        }
        focusNewsItem(conversationId, items.get(0).getId());
    }

    private Map<String, Deque<AssistantMessage>> restoreConversations(Map<String, List<AssistantMessage>> storedConversations) {
        Map<String, Deque<AssistantMessage>> restored = new ConcurrentHashMap<>();
        if (storedConversations == null || storedConversations.isEmpty()) {
            return restored;
        }
        storedConversations.forEach((conversationId, messages) -> {
            if (!hasText(conversationId) || messages == null || messages.isEmpty()) {
                return;
            }
            ArrayDeque<AssistantMessage> deque = new ArrayDeque<>();
            messages.stream()
                    .filter(Objects::nonNull)
                    .map(this::copyMessage)
                    .filter(Objects::nonNull)
                    .forEach(deque::addLast);
            while (deque.size() > maxHistoryMessages) {
                deque.removeFirst();
            }
            if (!deque.isEmpty()) {
                restored.put(conversationId, deque);
            }
        });
        return restored;
    }

    private Map<String, NewsSnapshotState> restoreNewsSnapshots(Map<String, AssistantConversationStateStore.StoredNewsSnapshot> storedSnapshots) {
        Map<String, NewsSnapshotState> restored = new ConcurrentHashMap<>();
        if (storedSnapshots == null || storedSnapshots.isEmpty()) {
            return restored;
        }
        long now = System.currentTimeMillis();
        long maxAgeMillis = newsSnapshotRetention.toMillis();
        storedSnapshots.forEach((conversationId, snapshot) -> {
            if (!hasText(conversationId) || snapshot == null || snapshot.items() == null || snapshot.items().isEmpty()) {
                return;
            }
            if (now - snapshot.storedAtEpochMillis() > maxAgeMillis) {
                return;
            }
            List<NewsItem> items = copySnapshotItems(snapshot.items());
            if (items.isEmpty()) {
                return;
            }
            restored.put(conversationId, new NewsSnapshotState(
                    defaultText(snapshot.requestedLayer(), "all"),
                    defaultText(snapshot.requestedCategory(), "all"),
                    items,
                    snapshot.focusedNewsId(),
                    snapshot.storedAtEpochMillis()));
        });
        return restored;
    }

    private AssistantMessage copyMessage(AssistantMessage message) {
        if (message == null || !hasText(message.getRole()) || !hasText(message.getContent())) {
            return null;
        }
        return AssistantMessage.builder()
                .role(limit(message.getRole(), 32))
                .content(limit(message.getContent(), 1000))
                .build();
    }

    private void persistState() {
        stateStore.save(new AssistantConversationStateStore.StoredState(
                snapshotConversationsForStore(),
                snapshotNewsSnapshotsForStore()
        ));
    }

    private Map<String, List<AssistantMessage>> snapshotConversationsForStore() {
        Map<String, List<AssistantMessage>> stored = new LinkedHashMap<>();
        conversations.forEach((conversationId, deque) -> {
            if (!hasText(conversationId) || deque == null) {
                return;
            }
            synchronized (deque) {
                if (deque.isEmpty()) {
                    return;
                }
                List<AssistantMessage> messages = deque.stream()
                        .map(this::copyMessage)
                        .filter(Objects::nonNull)
                        .toList();
                if (!messages.isEmpty()) {
                    stored.put(conversationId, messages);
                }
            }
        });
        return stored;
    }

    private Map<String, AssistantConversationStateStore.StoredNewsSnapshot> snapshotNewsSnapshotsForStore() {
        Map<String, AssistantConversationStateStore.StoredNewsSnapshot> stored = new LinkedHashMap<>();
        long now = System.currentTimeMillis();
        long maxAgeMillis = newsSnapshotRetention.toMillis();
        recentNewsSnapshots.forEach((conversationId, snapshot) -> {
            if (!hasText(conversationId) || snapshot == null || snapshot.items() == null || snapshot.items().isEmpty()) {
                return;
            }
            if (now - snapshot.storedAtEpochMillis() > maxAgeMillis) {
                return;
            }
            stored.put(conversationId, new AssistantConversationStateStore.StoredNewsSnapshot(
                    snapshot.requestedLayer(),
                    snapshot.requestedCategory(),
                    copySnapshotItems(snapshot.items()),
                    snapshot.focusedNewsId(),
                    snapshot.storedAtEpochMillis()
            ));
        });
        return stored;
    }

    private List<NewsItem> copySnapshotItems(List<NewsItem> items) {
        if (snapshotItemCopier == null || items == null || items.isEmpty()) {
            return List.of();
        }
        List<NewsItem> copied = snapshotItemCopier.apply(items);
        return copied == null ? List.of() : copied;
    }

    private String limit(String text, int maxLen) {
        if (text == null || text.length() <= maxLen) {
            return text;
        }
        return text.substring(0, maxLen) + "…";
    }

    record NewsSnapshotState(String requestedLayer, String requestedCategory, List<NewsItem> items, String focusedNewsId, long storedAtEpochMillis) {
        boolean hasItems() {
            return items != null && !items.isEmpty();
        }

        NewsSnapshotState withFocusedNewsId(String newsId) {
            return new NewsSnapshotState(requestedLayer, requestedCategory, items, newsId, storedAtEpochMillis);
        }
    }
}