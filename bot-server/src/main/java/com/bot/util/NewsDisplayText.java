package com.bot.util;

public final class NewsDisplayText {

    private NewsDisplayText() {
    }

    public static String displaySourceType(String sourceType) {
        return switch (TextUtils.defaultText(sourceType, "all")) {
            case "news" -> "新闻源";
            case "hotlist" -> "热榜源";
            case "overview" -> "按内容归类";
            case "all" -> "全部来源";
            default -> sourceType;
        };
    }

    public static String displayCategory(String category) {
        return switch (TextUtils.defaultText(category, "all")) {
            case "tech" -> "科技";
            case "general" -> "综合";
            case "current_affairs" -> "新闻时事";
            case "tech_science" -> "科技科普";
            case "humanities_nature" -> "人文自然";
            case "entertainment" -> "娱乐";
            case "all" -> "全部分类";
            default -> category;
        };
    }

    public static String displayTrustLevel(String trustLevel) {
        return switch (TextUtils.defaultText(trustLevel, "unknown")) {
            case "official_rss" -> "官方 RSS";
            case "aggregated" -> "聚合来源";
            case "community" -> "社区热榜";
            case "unknown" -> "unknown";
            default -> trustLevel;
        };
    }
}