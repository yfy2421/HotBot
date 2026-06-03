package com.bot.service;

import com.bot.client.PythonMLClient;
import lombok.extern.slf4j.Slf4j;

import java.util.List;

@Slf4j
class IntentRouter {

    private static final List<String> SUPPORTED_CITIES = List.of(
            "北京", "上海", "广州", "深圳", "杭州", "成都", "武汉", "南京",
            "西安", "重庆", "天津", "长沙", "苏州", "郑州", "青岛", "厦门"
    );

    private static final double L2_HIGH_CONFIDENCE = 0.75;
    private static final double L2_LOW_CONFIDENCE = 0.65;

    private final PythonMLClient mlClient;

    IntentRouter(PythonMLClient mlClient) {
        this.mlClient = mlClient;
    }

    ChatIntent route(String content) {
        String normalized = normalizeText(content);
        if (normalized.isEmpty()) {
            return ChatIntent.of(ChatIntentType.EMPTY, null, 1.0f);
        }

        // ── L1: Keyword fast path for deterministic commands ──
        if (isClearCommand(normalized)) {
            return ChatIntent.of(ChatIntentType.CLEAR, null, 1.0f);
        }
        if (isHelpCommand(normalized)) {
            return ChatIntent.of(ChatIntentType.HELP, null, 1.0f);
        }
        if (isHotNewsCommand(normalized)) {
            return ChatIntent.of(ChatIntentType.OVERVIEW, null, 1.0f);
        }
        if (isWeatherQuery(normalized)) {
            return ChatIntent.of(ChatIntentType.WEATHER, detectRequestedCity(normalized), 0.9f);
        }

        // ── L2: Embedding similarity classification ──
        PythonMLClient.IntentClassification l2 = mlClient.classifyIntent(normalized);
        String l2Intent = l2 != null ? l2.intent() : "default";
        double l2Confidence = l2 != null ? l2.confidence() : 0.0;

        if (l2Confidence >= L2_HIGH_CONFIDENCE) {
            // High confidence — use L2 result directly.
            ChatIntent result = mapL2Intent(l2Intent, normalized, (float) l2Confidence);
            if (result != null) {
                return result;
            }
        }

        if (l2Confidence >= L2_LOW_CONFIDENCE) {
            // Medium confidence — use L2 result, but note low confidence.
            ChatIntent result = mapL2Intent(l2Intent, normalized, (float) l2Confidence);
            if (result != null) {
                return result;
            }
        }

        // ── L3: Keyword fallback for now (LLM-based classification deferred) ──
        // When L2 is uncertain or returns "default", fall back to keyword-based
        // resolveNewsIntent to decide between OVERVIEW vs DEFAULT.
        // A future change will replace this with an LLM classification call
        // that also feeds L2/L3 disagreement data back into prototype iteration.
        if (!"default".equals(l2Intent) && l2Confidence > 0.0) {
            logL2L3Disagreement(normalized, l2Intent, l2Confidence);
        }

        NewsIntent newsIntent = resolveNewsIntent(normalized, null);
        if (newsIntent.overviewQuery()) {
            return ChatIntent.of(ChatIntentType.OVERVIEW, null, 0.5f);
        }
        return ChatIntent.of(ChatIntentType.DEFAULT, null, 0.3f);
    }

    /**
     * Map an L2 embedding intent label to a ChatIntent.
     * Returns null if the label maps to something that needs further resolution.
     */
    private ChatIntent mapL2Intent(String l2Label, String normalized, float confidence) {
        return switch (l2Label) {
            case "clear" -> ChatIntent.of(ChatIntentType.CLEAR, null, confidence);
            case "help" -> ChatIntent.of(ChatIntentType.HELP, null, confidence);
            case "weather" -> ChatIntent.of(ChatIntentType.WEATHER, detectRequestedCity(normalized), confidence);
            case "news_overview", "hotlist" -> ChatIntent.of(ChatIntentType.OVERVIEW, null, confidence);
            case "detail_followup" -> ChatIntent.of(ChatIntentType.DETAIL_FOLLOWUP, null, confidence);
            case "follow_up" -> ChatIntent.of(ChatIntentType.FOLLOW_UP, null, confidence);
            case "casual_chat" -> ChatIntent.of(ChatIntentType.CASUAL_CHAT, null, confidence);
            default -> null; // "default" — need further resolution
        };
    }

    private void logL2L3Disagreement(String text, String l2Intent, double l2Confidence) {
        log.info("L2_L3_DISAGREE: text=\"{}\" l2={}({}) l3=keyword_fallback",
                text, l2Intent, String.format("%.2f", l2Confidence));
    }

    NewsIntent resolveNewsIntent(String content, String forcedLayer) {
        String normalized = normalizeText(content);
        String lower = normalized.toLowerCase();
        boolean analysisRequested = isAnalysisQuery(lower);
        boolean followUpRequested = isFollowUpQuery(lower);
        boolean overviewQuery = isOverviewNewsQuery(normalized, lower, analysisRequested, followUpRequested);
        boolean requiresFreshNews = IntentKeywords.requiresFreshNews(lower);
        String requestedLayer = forcedLayer != null ? forcedLayer : resolveRequestedLayer(lower);
        String requestedCategory = resolveRequestedCategory(lower);
        return new NewsIntent(
                analysisRequested,
                followUpRequested,
                overviewQuery,
                requiresFreshNews,
                requestedLayer,
                requestedCategory
        );
    }

    private boolean isOverviewNewsQuery(String content, String lower, boolean analysisRequested, boolean followUpRequested) {
        if (analysisRequested || followUpRequested) {
            return false;
        }
        if (isHotNewsCommand(content)) {
            return true;
        }
        boolean hasNewsWord = IntentKeywords.containsOverviewNewsWord(lower);
        boolean hasOverviewVerb = IntentKeywords.containsOverviewVerb(lower);
        return hasNewsWord && (hasOverviewVerb
                || lower.endsWith("新闻")
                || lower.endsWith("热点")
                || lower.endsWith("热搜")
                || lower.endsWith("热榜"));
    }

    private boolean isFollowUpQuery(String lower) {
        return IntentKeywords.isFollowUpQuery(lower);
    }

    private String resolveRequestedLayer(String lower) {
        return IntentKeywords.resolveRequestedLayer(lower);
    }

    private String resolveRequestedCategory(String lower) {
        return IntentKeywords.resolveRequestedCategory(lower);
    }

    private boolean isWeatherQuery(String content) {
        return IntentKeywords.isWeatherQuery(content);
    }

    private String detectRequestedCity(String content) {
        return SUPPORTED_CITIES.stream()
                .filter(content::contains)
                .findFirst()
                .orElse(null);
    }

    private boolean isHotNewsCommand(String content) {
        return IntentKeywords.isHotNewsCommand(content);
    }

    private boolean isAnalysisQuery(String lower) {
        return IntentKeywords.isAnalysisQuery(lower);
    }

    private boolean isHelpCommand(String content) {
        return IntentKeywords.isHelpCommand(content);
    }

    private boolean isClearCommand(String content) {
        return IntentKeywords.isClearCommand(content);
    }

    private String normalizeText(String content) {
        return content == null ? "" : content.trim();
    }

    enum ChatIntentType {
        EMPTY,
        CLEAR,
        HELP,
        WEATHER,
        OVERVIEW,
        DETAIL_FOLLOWUP,
        FOLLOW_UP,
        CASUAL_CHAT,
        DEFAULT
    }

    record ChatIntent(ChatIntentType type, String requestedCity, float confidence) {
        static ChatIntent of(ChatIntentType type, String requestedCity, float confidence) {
            return new ChatIntent(type, requestedCity, confidence);
        }
    }

    record NewsIntent(boolean analysisRequested,
                      boolean followUpRequested,
                      boolean overviewQuery,
                      boolean requiresFreshNews,
                      String requestedLayer,
                      String requestedCategory) {
    }
}
