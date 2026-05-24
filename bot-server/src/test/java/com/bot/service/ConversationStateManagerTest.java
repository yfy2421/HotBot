package com.bot.service;

import com.bot.model.NewsItem;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ConversationStateManagerTest {

    @Test
    void rememberedNewsSnapshotIsReusableWithinRetentionWindow() {
        AssistantConversationStateStore stateStore = mock(AssistantConversationStateStore.class);
        when(stateStore.load()).thenReturn(AssistantConversationStateStore.StoredState.empty());
        ConversationStateManager manager = manager(stateStore, Duration.ofMinutes(5));

        List<NewsItem> items = List.of(news("n1", "测试新闻甲"), news("n2", "测试新闻乙"));

        manager.rememberNewsSnapshot("conv-reuse", "news", "all", items, null);

        ConversationStateManager.NewsSnapshotState snapshot = manager.reusableNewsSnapshot("conv-reuse");

        assertNotNull(snapshot);
        assertEquals("news", snapshot.requestedLayer());
        assertEquals("all", snapshot.requestedCategory());
        assertEquals(2, snapshot.items().size());
        assertEquals("测试新闻甲", snapshot.items().get(0).getTitle());
        assertNull(snapshot.focusedNewsId());
        verify(stateStore, atLeastOnce()).save(any());
    }

    @Test
    void focusNewsItemSwitchesFocusedIdOnExistingSnapshot() {
        AssistantConversationStateStore stateStore = mock(AssistantConversationStateStore.class);
        when(stateStore.load()).thenReturn(AssistantConversationStateStore.StoredState.empty());
        ConversationStateManager manager = manager(stateStore, Duration.ofMinutes(5));

        manager.rememberNewsSnapshot("conv-focus", "hotlist", "all", List.of(
                news("n1", "测试热点甲"),
                news("n2", "测试热点乙")
        ), "n1");

        manager.focusNewsItem("conv-focus", "n2");

        ConversationStateManager.NewsSnapshotState snapshot = manager.newsSnapshot("conv-focus");

        assertNotNull(snapshot);
        assertEquals("n2", snapshot.focusedNewsId());
    }

    @Test
    void focusSingleNewsItemUsesOnlyItemId() {
        AssistantConversationStateStore stateStore = mock(AssistantConversationStateStore.class);
        when(stateStore.load()).thenReturn(AssistantConversationStateStore.StoredState.empty());
        ConversationStateManager manager = manager(stateStore, Duration.ofMinutes(5));

        List<NewsItem> items = List.of(news("n1", "测试新闻甲"));
        manager.rememberNewsSnapshot("conv-single", "news", "all", items, null);

        manager.focusSingleNewsItem("conv-single", items);

        ConversationStateManager.NewsSnapshotState snapshot = manager.newsSnapshot("conv-single");

        assertNotNull(snapshot);
        assertEquals("n1", snapshot.focusedNewsId());
    }

    private ConversationStateManager manager(AssistantConversationStateStore stateStore, Duration retention) {
        return new ConversationStateManager(
                stateStore,
                10,
                retention,
                items -> items == null ? List.of() : new ArrayList<>(items)
        );
    }

    private NewsItem news(String id, String title) {
        return NewsItem.builder()
                .id(id)
                .title(title)
                .summary(title + " 的摘要")
                .detailExcerpt(title + " 的详情摘录")
                .source("测试源")
                .sourceType("news")
                .category("current_affairs")
                .trustLevel("aggregated")
                .build();
    }
}