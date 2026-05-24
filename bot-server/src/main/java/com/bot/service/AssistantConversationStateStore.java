package com.bot.service;

import com.bot.config.AppConfig;
import com.bot.model.AssistantMessage;
import com.bot.model.NewsItem;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@Service
public class AssistantConversationStateStore {

    private static final String DEFAULT_STATE_DIR = "./storage/assistant";
    private static final String STATE_FILE_NAME = "assistant-state.json";

    private final ObjectMapper objectMapper;
    private final Path stateFile;

    public AssistantConversationStateStore(AppConfig.BotConfig botConfig, ObjectMapper objectMapper) {
        this.objectMapper = objectMapper.copy().findAndRegisterModules();
        this.stateFile = resolveStateFile(botConfig);
    }

    public synchronized StoredState load() {
        if (!Files.exists(stateFile)) {
            return StoredState.empty();
        }
        try {
            PersistedState persistedState = objectMapper.readValue(stateFile.toFile(), PersistedState.class);
            return new StoredState(
                    copyConversations(persistedState == null ? null : persistedState.conversations),
                    copyNewsSnapshots(persistedState == null ? null : persistedState.newsSnapshots)
            );
        } catch (Exception e) {
            log.warn("Failed to load assistant conversation state from {}: {}", stateFile, e.getMessage());
            return StoredState.empty();
        }
    }

    public synchronized void save(StoredState state) {
        try {
            Files.createDirectories(stateFile.getParent());
            PersistedState persistedState = new PersistedState();
            persistedState.conversations = copyConversations(state == null ? null : state.conversations());
            persistedState.newsSnapshots = copyNewsSnapshots(state == null ? null : state.newsSnapshots());

            Path tempFile = stateFile.resolveSibling(STATE_FILE_NAME + ".tmp");
            objectMapper.writerWithDefaultPrettyPrinter().writeValue(tempFile.toFile(), persistedState);
            moveIntoPlace(tempFile);
        } catch (Exception e) {
            log.warn("Failed to save assistant conversation state to {}: {}", stateFile, e.getMessage());
        }
    }

    private Path resolveStateFile(AppConfig.BotConfig botConfig) {
        String configuredDir = DEFAULT_STATE_DIR;
        if (botConfig != null
                && botConfig.getAssistant() != null
                && botConfig.getAssistant().getStateDir() != null
                && !botConfig.getAssistant().getStateDir().isBlank()) {
            configuredDir = botConfig.getAssistant().getStateDir().trim();
        }
        return Path.of(configuredDir).resolve(STATE_FILE_NAME).normalize();
    }

    private void moveIntoPlace(Path tempFile) throws Exception {
        try {
            Files.move(tempFile, stateFile, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException ignored) {
            Files.move(tempFile, stateFile, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private Map<String, List<AssistantMessage>> copyConversations(Map<String, List<AssistantMessage>> source) {
        Map<String, List<AssistantMessage>> copied = new LinkedHashMap<>();
        if (source == null || source.isEmpty()) {
            return copied;
        }
        source.forEach((conversationId, messages) -> {
            if (conversationId == null || conversationId.isBlank() || messages == null || messages.isEmpty()) {
                return;
            }
            copied.put(conversationId, List.copyOf(messages));
        });
        return copied;
    }

    private Map<String, StoredNewsSnapshot> copyNewsSnapshots(Map<String, StoredNewsSnapshot> source) {
        Map<String, StoredNewsSnapshot> copied = new LinkedHashMap<>();
        if (source == null || source.isEmpty()) {
            return copied;
        }
        source.forEach((conversationId, snapshot) -> {
            if (conversationId == null || conversationId.isBlank() || snapshot == null || snapshot.items() == null || snapshot.items().isEmpty()) {
                return;
            }
            copied.put(conversationId, new StoredNewsSnapshot(
                    snapshot.requestedLayer(),
                    snapshot.requestedCategory(),
                    List.copyOf(snapshot.items()),
                    snapshot.focusedNewsId(),
                    snapshot.storedAtEpochMillis()
            ));
        });
        return copied;
    }

    public record StoredState(Map<String, List<AssistantMessage>> conversations,
                              Map<String, StoredNewsSnapshot> newsSnapshots) {
        static StoredState empty() {
            return new StoredState(Map.of(), Map.of());
        }
    }

    public record StoredNewsSnapshot(String requestedLayer,
                                     String requestedCategory,
                                     List<NewsItem> items,
                                     String focusedNewsId,
                                     long storedAtEpochMillis) {
    }

    private static class PersistedState {
        public Map<String, List<AssistantMessage>> conversations = new LinkedHashMap<>();
        public Map<String, StoredNewsSnapshot> newsSnapshots = new LinkedHashMap<>();
    }
}