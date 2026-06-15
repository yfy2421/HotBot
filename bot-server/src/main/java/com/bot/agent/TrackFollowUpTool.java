package com.bot.agent;

import com.bot.model.NewsItem;
import com.bot.service.NewsService;
import com.bot.service.TrackingService;

import java.util.List;
import java.util.Map;

/**
 * Tool: track_follow_up
 *
 * Searches for follow-up reporting related to a given article title within the last 7 days.
 */
public class TrackFollowUpTool implements Tool {

    private final TrackingService trackingService;
    private final NewsService newsService;

    public TrackFollowUpTool(TrackingService trackingService, NewsService newsService) {
        this.trackingService = trackingService;
        this.newsService = newsService;
    }

    @Override
    public String name() {
        return "track_follow_up";
    }

    @Override
    public String description() {
        return "查询某条新闻在近 7 天内是否有后续报道或相关进展。需先调用 fetch_news 获取新闻列表。";
    }

    @Override
    public String parameters() {
        return """
                {
                  "title": "必填, 要查询后续的新闻标题（需与 fetch_news 返回的标题一致）"
                }""";
    }

    @Override
    public ToolsResult execute(Map<String, Object> args) {
        String title = stringArg(args, "title", null);

        if (title == null || title.isBlank()) {
            return ToolsResult.fail("缺少 title 参数");
        }

        try {
            List<NewsItem> allNews = newsService.fetchAll();
            if (allNews == null || allNews.isEmpty()) {
                return ToolsResult.fail("当前无新闻数据，无法追踪后续");
            }

            // Find the matching article
            List<NewsItem> matched = allNews.stream()
                    .filter(item -> item.getTitle() != null && item.getTitle().contains(title))
                    .limit(1)
                    .toList();

            if (matched.isEmpty()) {
                matched = allNews.stream()
                        .filter(item -> item.getTitle() != null
                                && item.getTitle().toLowerCase().contains(title.toLowerCase()))
                        .limit(1)
                        .toList();
            }

            if (matched.isEmpty()) {
                return ToolsResult.ok("未找到标题匹配《" + title + "》的新闻，请检查标题是否与 fetch_news 返回的一致。");
            }

            NewsItem target = matched.get(0);
            trackingService.shortTermTrackingWithAlerts(List.of(target));
            String followUpTag = target.getFollowUpTag();

            if (followUpTag == null || followUpTag.isBlank()) {
                return ToolsResult.ok("《" + target.getTitle() + "》近 7 天暂无后续报道。");
            }

            return ToolsResult.ok("《" + target.getTitle() + "》后续追踪:\n\n" + followUpTag);
        } catch (Exception e) {
            return ToolsResult.fail("后续追踪查询失败: " + e.getMessage());
        }
    }

    private static String stringArg(Map<String, Object> args, String key, String fallback) {
        Object value = args.get(key);
        if (value instanceof String s && !s.isBlank()) {
            return s.trim();
        }
        return fallback;
    }
}
