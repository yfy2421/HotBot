package com.bot.client;

import com.bot.config.AppConfig;
import com.bot.model.AssistantMessage;
import com.bot.model.FetchResult;
import com.bot.model.SystemAlert;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestTemplate;

import java.time.Duration;
import java.util.Collections;
import java.util.Map;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

import static com.bot.util.TextUtils.hasText;

@Slf4j
@Component
public class ResilientPythonMLClient extends PythonMLClient {

    private static final String DEFAULT_CHAT_FALLBACK_REPLY = "当前 AI 服务暂时不可用，请稍后再试。";
    private static final String CIRCUIT_OPEN_EXCEPTION_TYPE = "CircuitOpen";
    private static final String CIRCUIT_OPEN_MESSAGE = "circuit breaker open";
    private static final int CIRCUIT_BREAKER_WINDOW_SIZE = 4;
    private static final int CIRCUIT_BREAKER_MIN_CALLS = 4;
    private static final int CIRCUIT_BREAKER_HALF_OPEN_CALLS = 2;
    private static final float CIRCUIT_BREAKER_FAILURE_RATE_THRESHOLD = 100f;
    private static final Duration CIRCUIT_BREAKER_OPEN_WAIT = Duration.ofSeconds(30);

    private final CircuitBreakerRegistry circuitBreakerRegistry;
    private final Map<String, CircuitBreaker> capabilityCircuitBreakers = new ConcurrentHashMap<>();
    private final AppConfig.BotConfig.MlConfig mlConfig;

    public ResilientPythonMLClient(RestTemplate restTemplate, AppConfig.BotConfig config) {
        super(restTemplate, config);
        this.mlConfig = config.getMl();
        CircuitBreakerConfig breakerConfig = CircuitBreakerConfig.custom()
                .slidingWindowType(CircuitBreakerConfig.SlidingWindowType.COUNT_BASED)
                .slidingWindowSize(CIRCUIT_BREAKER_WINDOW_SIZE)
                .minimumNumberOfCalls(CIRCUIT_BREAKER_MIN_CALLS)
                .failureRateThreshold(CIRCUIT_BREAKER_FAILURE_RATE_THRESHOLD)
                .permittedNumberOfCallsInHalfOpenState(CIRCUIT_BREAKER_HALF_OPEN_CALLS)
                .waitDurationInOpenState(CIRCUIT_BREAKER_OPEN_WAIT)
                .recordException(this::shouldRecordFailure)
                .build();
        this.circuitBreakerRegistry = CircuitBreakerRegistry.of(breakerConfig);
    }

    @Override
    public List<List<Float>> embed(List<String> texts) {
        if (texts == null || texts.isEmpty()) {
            return emptyFloatVectors();
        }
        return executeDetailed("embed", "/api/embed", true, "empty-vectors",
                () -> defaultList(super.embed(texts)),
                emptyFloatVectors()).data();
    }

    @Override
    public FetchResult<List<List<Float>>> embedWithAlert(List<String> texts,
                                                         String alertSource,
                                                         String alertCode,
                                                         String alertSubject) {
        if (texts == null || texts.isEmpty()) {
            return FetchResult.of(emptyFloatVectors());
        }
        return toFetchResult(executeDetailed("embed", "/api/embed", true, "empty-vectors",
                () -> defaultList(super.embed(texts)),
                emptyFloatVectors()), alertSource, alertCode, alertSubject);
    }

    @Override
    public String commentary(String content) {
        return executeDetailed("commentary", "/api/commentary", true, "skip-commentary",
                () -> defaultText(super.commentary(content), ""),
                "").data();
    }

    @Override
    public FetchResult<String> commentaryWithAlert(String content,
                                                   String alertSource,
                                                   String alertCode,
                                                   String alertSubject) {
        return toFetchResult(executeDetailed("commentary", "/api/commentary", true, "skip-commentary",
                () -> defaultText(super.commentary(content), ""),
                ""), alertSource, alertCode, alertSubject);
    }

    @Override
    public String chat(String message, List<AssistantMessage> history, String systemPrompt) {
        String reply = executeDetailed("chat", "/api/chat", false, "fallback-reply",
                () -> super.chat(message, history, systemPrompt),
                chatFallbackReply()).data();
        return hasText(reply) ? reply.trim() : chatFallbackReply();
    }

    @Override
    public List<SemanticRankMatch> rankCandidates(String query, List<String> candidates, int topK) {
        if (candidates == null || candidates.isEmpty() || !hasText(query) || topK <= 0) {
            return emptyRankMatches();
        }
        return executeDetailed("semantic-rank", "/api/semantic/rank", true, "empty-ranking",
                () -> defaultList(super.rankCandidates(query, candidates, topK)),
                emptyRankMatches()).data();
    }

    @Override
    public List<String> translateTexts(List<String> texts, String textType) {
        if (texts == null || texts.isEmpty()) {
            return List.of();
        }
        List<String> fallback = blankTranslations(texts.size());
        return executeDetailed("translate:" + defaultText(textType, "unknown"), "/api/translate", true, "empty-translation",
                () -> sanitizeTranslations(super.translateTexts(texts, textType), texts.size(), fallback),
                fallback).data();
    }

    @Override
    public Map<String, Object> sentiment(List<String> comments) {
        if (comments == null || comments.isEmpty()) {
            return emptyObjectMap();
        }
        return executeDetailed("sentiment", "/api/sentiment", true, "empty-sentiment",
                () -> defaultMap(super.sentiment(comments)),
                emptyObjectMap()).data();
    }

    @Override
    public FetchResult<Map<String, Object>> sentimentWithAlert(List<String> comments,
                                                               String alertSource,
                                                               String alertCode,
                                                               String alertSubject) {
        if (comments == null || comments.isEmpty()) {
            return FetchResult.of(emptyObjectMap());
        }
        return toFetchResult(executeDetailed("sentiment", "/api/sentiment", true, "empty-sentiment",
                () -> defaultMap(super.sentiment(comments)),
                emptyObjectMap()), alertSource, alertCode, alertSubject);
    }

    @Override
    public List<Map<String, Object>> ner(String text) {
        if (!hasText(text)) {
            return emptyObjectEntries();
        }
        return executeDetailed("ner", "/api/ner", true, "empty-entities",
                () -> defaultList(super.ner(text)),
                emptyObjectEntries()).data();
    }

    @Override
    public FetchResult<List<Map<String, Object>>> nerWithAlert(String text,
                                                               String alertSource,
                                                               String alertCode,
                                                               String alertSubject) {
        if (!hasText(text)) {
            return FetchResult.of(emptyObjectEntries());
        }
        return toFetchResult(executeDetailed("ner", "/api/ner", true, "empty-entities",
                () -> defaultList(super.ner(text)),
                emptyObjectEntries()), alertSource, alertCode, alertSubject);
    }

    @Override
    public void addNews(String id, String text, List<Float> vector, Map<String, Object> metadata) {
        if (!hasText(id) || !hasText(text) || vector == null || vector.isEmpty()) {
            return;
        }
        executeVoidDetailed("news-add", "/api/news/add", false, "skip-store-news",
                () -> super.addNews(id, text, vector, metadata));
    }

    @Override
    public FetchResult<Void> addNewsWithAlert(String id,
                                              String text,
                                              List<Float> vector,
                                              Map<String, Object> metadata,
                                              String alertSource,
                                              String alertCode,
                                              String alertSubject) {
        if (!hasText(id) || !hasText(text) || vector == null || vector.isEmpty()) {
            return FetchResult.of((Void) null);
        }
        return toFetchResult(executeVoidDetailed("news-add", "/api/news/add", false, "skip-store-news",
                () -> super.addNews(id, text, vector, metadata)), alertSource, alertCode, alertSubject);
    }

    @Override
    public List<Map<String, Object>> querySimilar(List<Float> vector, int days, double threshold) {
        if (vector == null || vector.isEmpty()) {
            return emptyObjectEntries();
        }
        return executeDetailed("news-similar", "/api/news/similar", true, "empty-similar-news",
                () -> defaultList(super.querySimilar(vector, days, threshold)),
                emptyObjectEntries()).data();
    }

    @Override
    public FetchResult<List<Map<String, Object>>> querySimilarWithAlert(List<Float> vector,
                                                                        int days,
                                                                        double threshold,
                                                                        String alertSource,
                                                                        String alertCode,
                                                                        String alertSubject) {
        if (vector == null || vector.isEmpty()) {
            return FetchResult.of(emptyObjectEntries());
        }
        return toFetchResult(executeDetailed("news-similar", "/api/news/similar", true, "empty-similar-news",
                () -> defaultList(super.querySimilar(vector, days, threshold)),
                emptyObjectEntries()), alertSource, alertCode, alertSubject);
    }

    @Override
    public void addEntity(String id, String name, String type, List<Float> vector, Map<String, Object> metadata) {
        if (!hasText(id) || !hasText(name) || !hasText(type) || vector == null || vector.isEmpty()) {
            return;
        }
        executeVoidDetailed("entity-add", "/api/entity/add", false, "skip-store-entity",
                () -> super.addEntity(id, name, type, vector, metadata));
    }

    @Override
    public FetchResult<Void> addEntityWithAlert(String id,
                                                String name,
                                                String type,
                                                List<Float> vector,
                                                Map<String, Object> metadata,
                                                String alertSource,
                                                String alertCode,
                                                String alertSubject) {
        if (!hasText(id) || !hasText(name) || !hasText(type) || vector == null || vector.isEmpty()) {
            return FetchResult.of((Void) null);
        }
        return toFetchResult(executeVoidDetailed("entity-add", "/api/entity/add", false, "skip-store-entity",
                () -> super.addEntity(id, name, type, vector, metadata)), alertSource, alertCode, alertSubject);
    }

    @Override
    public List<Map<String, Object>> queryEntityHistory(List<Float> vector, double threshold) {
        if (vector == null || vector.isEmpty()) {
            return emptyObjectEntries();
        }
        return executeDetailed("entity-history", "/api/entity/history", true, "empty-entity-history",
                () -> defaultList(super.queryEntityHistory(vector, threshold)),
                emptyObjectEntries()).data();
    }

    @Override
    public FetchResult<List<Map<String, Object>>> queryEntityHistoryWithAlert(List<Float> vector,
                                                                              double threshold,
                                                                              String alertSource,
                                                                              String alertCode,
                                                                              String alertSubject) {
        if (vector == null || vector.isEmpty()) {
            return FetchResult.of(emptyObjectEntries());
        }
        return toFetchResult(executeDetailed("entity-history", "/api/entity/history", true, "empty-entity-history",
                () -> defaultList(super.queryEntityHistory(vector, threshold)),
                emptyObjectEntries()), alertSource, alertCode, alertSubject);
    }

    private MlExecution<Void> executeVoidDetailed(String capability,
                                                  String endpoint,
                                                  boolean retryOnConnectionFailure,
                                                  String fallbackUsed,
                                                  Runnable action) {
        MlExecution<Boolean> execution = executeDetailed(capability, endpoint, retryOnConnectionFailure, fallbackUsed, () -> {
            action.run();
            return Boolean.TRUE;
        }, Boolean.FALSE);
        return new MlExecution<>(execution.capability(), null, execution.degraded(), execution.exceptionType(), execution.fallbackUsed(), execution.message());
    }

    private <T> MlExecution<T> executeDetailed(String capability,
                                               String endpoint,
                                               boolean retryOnConnectionFailure,
                                               String fallbackUsed,
                                               Supplier<T> action,
                                               T fallbackValue) {
        CircuitBreaker circuitBreaker = circuitBreaker(capability);
        long start = System.nanoTime();
        try {
            T result = circuitBreaker.executeSupplier(() -> executeWithRetry(capability, endpoint, retryOnConnectionFailure, action));
            if (result != null) {
                return new MlExecution<>(capability, result, false, null, null, null);
            }
            logFallback(capability, endpoint, elapsedMillis(start), "InvalidResponse", fallbackUsed, "returned null");
            return new MlExecution<>(capability, fallbackValue, true, "InvalidResponse", fallbackUsed, "returned null");
        } catch (CallNotPermittedException e) {
            logFallback(capability, endpoint, elapsedMillis(start), CIRCUIT_OPEN_EXCEPTION_TYPE, fallbackUsed, CIRCUIT_OPEN_MESSAGE);
            return new MlExecution<>(capability, fallbackValue, true, CIRCUIT_OPEN_EXCEPTION_TYPE, fallbackUsed, CIRCUIT_OPEN_MESSAGE);
        } catch (RuntimeException e) {
            String message = summarize(e);
            logFallback(capability, endpoint, elapsedMillis(start), e.getClass().getSimpleName(), fallbackUsed, message);
            return new MlExecution<>(capability, fallbackValue, true, e.getClass().getSimpleName(), fallbackUsed, message);
        }
    }

    private <T> FetchResult<T> toFetchResult(MlExecution<T> execution,
                                             String alertSource,
                                             String alertCode,
                                             String alertSubject) {
        if (execution == null || !execution.degraded()) {
            return FetchResult.of(execution == null ? null : execution.data());
        }
        return FetchResult.of(execution.data(), List.of(SystemAlert.warn(
                alertSource,
                alertCode,
                formatAlertMessage(alertSubject, execution))));
    }

    private boolean shouldRetry(RuntimeException e) {
        return e instanceof ResourceAccessException;
    }

    private boolean shouldRecordFailure(Throwable throwable) {
        return throwable instanceof RuntimeException;
    }

    private CircuitBreaker circuitBreaker(String capability) {
        return capabilityCircuitBreakers.computeIfAbsent(
                capability,
                name -> circuitBreakerRegistry.circuitBreaker(name));
    }

    private <T> T executeWithRetry(String capability,
                                   String endpoint,
                                   boolean retryOnConnectionFailure,
                                   Supplier<T> action) {
        int maxAttempts = maxAttempts(retryOnConnectionFailure);
        RuntimeException lastFailure = null;
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            long start = System.nanoTime();
            try {
                return action.get();
            } catch (RuntimeException e) {
                lastFailure = e;
                if (attempt < maxAttempts && shouldRetry(e)) {
                    log.warn("ML call retrying: capability={} endpoint={} attempt={} latencyMs={} exception={} message={}",
                            capability,
                            endpoint,
                            attempt,
                            elapsedMillis(start),
                            e.getClass().getSimpleName(),
                            summarize(e));
                    continue;
                }
                throw e;
            }
        }
        throw lastFailure == null ? new IllegalStateException("未知错误") : lastFailure;
    }

    private int maxAttempts(boolean retryOnConnectionFailure) {
        if (!retryOnConnectionFailure) {
            return 1;
        }
        int configuredAttempts = mlConfig.getRetry().getConnectionFailureMaxAttempts();
        return Math.max(1, configuredAttempts);
    }

    private String chatFallbackReply() {
        return defaultText(mlConfig.getFallback().getChatReply(), DEFAULT_CHAT_FALLBACK_REPLY);
    }

    private long elapsedMillis(long start) {
        return (System.nanoTime() - start) / 1_000_000;
    }

    private void logFallback(String capability,
                             String endpoint,
                             long latencyMs,
                             String exceptionType,
                             String fallbackUsed,
                             String message) {
        log.warn("ML capability degraded: capability={} endpoint={} latencyMs={} exception={} fallback={} message={}",
                capability,
                endpoint,
                latencyMs,
                exceptionType,
                fallbackUsed,
                message);
    }

    private String formatAlertMessage(String subject, MlExecution<?> execution) {
        String prefix = hasText(subject) ? subject.trim() + " -> " : "";
        return prefix + execution.capability() + "/" + defaultText(execution.exceptionType(), "UnknownFailure")
                + ": " + defaultText(execution.message(), "无详细错误信息");
    }

    private String summarize(Exception e) {
        String message = e.getMessage();
        return hasText(message) ? message : "无详细错误信息";
    }

    private String defaultText(String value, String fallback) {
        return hasText(value) ? value : fallback;
    }

    private <T> List<T> defaultList(List<T> value) {
        return value == null ? List.of() : value;
    }

    private Map<String, Object> defaultMap(Map<String, Object> value) {
        return value == null ? Map.of() : value;
    }

    private List<String> sanitizeTranslations(List<String> translations, int expectedSize, List<String> fallback) {
        if (translations == null || translations.isEmpty() || translations.size() != expectedSize) {
            return fallback;
        }
        return translations.stream().map(item -> item == null ? "" : item).toList();
    }

    private List<String> blankTranslations(int size) {
        return size <= 0 ? List.of() : Collections.nCopies(size, "");
    }

    private List<List<Float>> emptyFloatVectors() {
        return List.of();
    }

    private List<SemanticRankMatch> emptyRankMatches() {
        return List.of();
    }

    private Map<String, Object> emptyObjectMap() {
        return Map.of();
    }

    private List<Map<String, Object>> emptyObjectEntries() {
        return List.of();
    }

    private record MlExecution<T>(String capability,
                                  T data,
                                  boolean degraded,
                                  String exceptionType,
                                  String fallbackUsed,
                                  String message) {
    }
}