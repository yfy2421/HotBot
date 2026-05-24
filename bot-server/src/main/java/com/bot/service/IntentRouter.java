package com.bot.service;

import java.util.List;

class IntentRouter {

    private static final List<String> SUPPORTED_CITIES = List.of(
            "北京", "上海", "广州", "深圳", "杭州", "成都", "武汉", "南京",
            "西安", "重庆", "天津", "长沙", "苏州", "郑州", "青岛", "厦门"
    );

    ChatIntent route(String content) {
        String normalized = normalizeText(content);
        if (normalized.isEmpty()) {
            return ChatIntent.empty();
        }
        if (isClearCommand(normalized)) {
            return ChatIntent.of(ChatIntentType.CLEAR, null);
        }
        if (isHelpCommand(normalized)) {
            return ChatIntent.of(ChatIntentType.HELP, null);
        }
        if (isWeatherQuery(normalized)) {
            return ChatIntent.of(ChatIntentType.WEATHER, detectRequestedCity(normalized));
        }

        NewsIntent newsIntent = resolveNewsIntent(normalized, null);
        if (newsIntent.overviewQuery()) {
            return ChatIntent.of(ChatIntentType.OVERVIEW, null);
        }
        return ChatIntent.of(ChatIntentType.DEFAULT, null);
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
        DEFAULT
    }

    record ChatIntent(ChatIntentType type, String requestedCity) {
        static ChatIntent empty() {
            return new ChatIntent(ChatIntentType.EMPTY, null);
        }

        static ChatIntent of(ChatIntentType type, String requestedCity) {
            return new ChatIntent(type, requestedCity);
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