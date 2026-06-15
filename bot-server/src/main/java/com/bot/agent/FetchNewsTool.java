package com.bot.agent;

import com.bot.model.NewsItem;
import com.bot.service.NewsService;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Tool: fetch_news
 *
 * Fetches the latest news items, optionally filtered by keyword or category.
 */
public class FetchNewsTool implements Tool {

    private static final int DEFAULT_COUNT = 5;

    private final NewsService newsService;

    public FetchNewsTool(NewsService newsService) {
        this.newsService = newsService;
    }

    @Override
    public String name() {
        return "fetch_news";
    }

    @Override
    public String description() {
        return "拉取最新新闻列表。可按关键词和分类过滤。";
    }

    @Override
    public String parameters() {
        return """
                {
                  "keyword": "可选, 标题关键词过滤",
                  "category": "可选, 分类: tech_science|humanities_nature|entertainment|current_affairs",
                  "count": "可选, 返回条数, 默认5"
                }""";
    }

    @Override
    public ToolsResult execute(Map<String, Object> args) {
        int count = intArg(args, "count", DEFAULT_COUNT);
        String keyword = stringArg(args, "keyword", null);

        try {
            List<NewsItem> allNews = newsService.fetchAll();
            if (allNews == null || allNews.isEmpty()) {
                return ToolsResult.fail("当前 RSS 源无数据");
            }

            List<NewsItem> filtered = allNews.stream()
                    .filter(item -> keyword == null
                            || (item.getTitle() != null && item.getTitle().contains(keyword)))
                    .limit(count)
                    .collect(Collectors.toList());

            if (filtered.isEmpty()) {
                return ToolsResult.ok("未找到匹配的新闻条目。");
            }

            StringBuilder sb = new StringBuilder("已获取最新新闻:\n");
            for (int i = 0; i < filtered.size(); i++) {
                NewsItem item = filtered.get(i);
                sb.append(i + 1).append(". ").append(item.getTitle())
                        .append(" | ").append(item.getSource())
                        .append(" | ").append(item.getCategory())
                        .append("\n");
            }
            sb.append("\n你可以根据标题进一步分析（analyze_article）或查后续（track_follow_up）。");
            return ToolsResult.ok(sb.toString());
        } catch (Exception e) {
            return ToolsResult.fail("获取新闻失败: " + e.getMessage());
        }
    }

    private static String stringArg(Map<String, Object> args, String key, String fallback) {
        Object value = args.get(key);
        if (value instanceof String s && !s.isBlank()) {
            return s.trim();
        }
        return fallback;
    }

    private static int intArg(Map<String, Object> args, String key, int fallback) {
        Object value = args.get(key);
        if (value instanceof Number n) {
            return Math.max(1, n.intValue());
        }
        if (value instanceof String s) {
            try {
                return Math.max(1, Integer.parseInt(s.trim()));
            } catch (NumberFormatException ignored) {
            }
        }
        return fallback;
    }
}
