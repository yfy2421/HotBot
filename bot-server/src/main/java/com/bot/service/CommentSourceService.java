package com.bot.service;

import com.bot.model.FetchResult;
import com.bot.model.NewsItem;
import com.bot.model.SystemAlert;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Slf4j
@Service
@RequiredArgsConstructor
public class CommentSourceService {

    private static final String ZHIHU_SHORT_COMMENT_URL = "https://news-at.zhihu.com/api/4/story/%s/short-comments";
    private static final String ZHIHU_LONG_COMMENT_URL = "https://news-at.zhihu.com/api/4/story/%s/long-comments";
    private static final String HN_ITEM_URL = "https://hacker-news.firebaseio.com/v0/item/%s.json";
    private static final Pattern HN_ITEM_ID_PATTERN = Pattern.compile("(?:item\\?id=)(\\d+)");
    private static final int MAX_COMMENTS = 6;

    private final RestTemplate restTemplate;

    public List<String> fetchComments(NewsItem newsItem) {
        return fetchCommentsWithAlert(newsItem).data();
    }

    public FetchResult<List<String>> fetchCommentsWithAlert(NewsItem newsItem) {
        if (newsItem == null) {
            return FetchResult.of(List.of());
        }
        try {
            if ("知乎日报".equals(newsItem.getSource())) {
                return FetchResult.of(fetchZhihuComments(newsItem));
            }
            if ("HackerNews".equals(newsItem.getSource())) {
                return FetchResult.of(fetchHackerNewsComments(newsItem));
            }
            return FetchResult.of(List.of());
        } catch (Exception e) {
            log.warn("Comment source failed for '{}': {}", newsItem.getTitle(), e.getMessage());
            return FetchResult.of(
                    List.of(),
                    List.of(SystemAlert.warn("CommentSource", "COMMENT_SOURCE_FAILED",
                            summarizeException(newsItem, e))));
        }
    }

    @SuppressWarnings("unchecked")
    private List<String> fetchZhihuComments(NewsItem newsItem) {
        String storyId = extractZhihuStoryId(newsItem.getId());
        if (storyId == null) {
            return List.of();
        }

        List<String> comments = new ArrayList<>();
        comments.addAll(extractZhihuCommentTexts(
                restTemplate.getForObject(ZHIHU_LONG_COMMENT_URL.formatted(storyId), Map.class)));
        if (comments.size() < MAX_COMMENTS) {
            comments.addAll(extractZhihuCommentTexts(
                    restTemplate.getForObject(ZHIHU_SHORT_COMMENT_URL.formatted(storyId), Map.class)));
        }
        return comments.stream()
                .map(this::normalizeComment)
                .filter(text -> !text.isBlank())
                .distinct()
                .limit(MAX_COMMENTS)
                .toList();
    }

    @SuppressWarnings("unchecked")
    private List<String> extractZhihuCommentTexts(Map<String, Object> response) {
        if (response == null || response.get("comments") == null) {
            return List.of();
        }
        List<Map<String, Object>> comments = (List<Map<String, Object>>) response.get("comments");
        return comments.stream()
                .map(item -> String.valueOf(item.getOrDefault("content", "")))
                .toList();
    }

    @SuppressWarnings("unchecked")
    private List<String> fetchHackerNewsComments(NewsItem newsItem) {
        String itemId = extractHnItemId(newsItem.getDiscussionUrl(), newsItem.getUrl());
        if (itemId == null) {
            return List.of();
        }

        Map<String, Object> story = restTemplate.getForObject(HN_ITEM_URL.formatted(itemId), Map.class);
        if (story == null || story.get("kids") == null) {
            return List.of();
        }
        List<Object> kids = (List<Object>) story.get("kids");
        List<String> comments = new ArrayList<>();
        for (Object kid : kids.stream().limit(MAX_COMMENTS).toList()) {
            Map<String, Object> comment = restTemplate.getForObject(HN_ITEM_URL.formatted(String.valueOf(kid)), Map.class);
            if (comment == null) {
                continue;
            }
            if (Boolean.TRUE.equals(comment.get("deleted")) || Boolean.TRUE.equals(comment.get("dead"))) {
                continue;
            }
            String text = normalizeComment(String.valueOf(comment.getOrDefault("text", "")));
            if (!text.isBlank()) {
                comments.add(text);
            }
        }
        return comments;
    }

    private String extractZhihuStoryId(String newsId) {
        if (newsId == null || !newsId.startsWith("zh-")) {
            return null;
        }
        return newsId.substring(3);
    }

    private String extractHnItemId(String... candidates) {
        for (String candidate : candidates) {
            if (candidate == null || candidate.isBlank()) {
                continue;
            }
            Matcher matcher = HN_ITEM_ID_PATTERN.matcher(candidate);
            if (matcher.find()) {
                return matcher.group(1);
            }
        }
        return null;
    }

    private String normalizeComment(String raw) {
        if (raw == null || raw.isBlank()) {
            return "";
        }
        return raw
                .replaceAll("(?i)<p>", "\n")
                .replaceAll("<[^>]+>", " ")
                .replace("&quot;", "\"")
                .replace("&#x27;", "'")
                .replace("&#x2F;", "/")
                .replace("&amp;", "&")
                .replace("&lt;", "<")
                .replace("&gt;", ">")
                .replaceAll("\\s+", " ")
                .trim();
    }

    private String summarizeException(NewsItem newsItem, Exception e) {
        String message = e.getMessage();
        if (message == null || message.isBlank()) {
            message = "无详细错误信息";
        }
        return defaultTitle(newsItem) + " -> " + e.getClass().getSimpleName() + ": " + message;
    }

    private String defaultTitle(NewsItem newsItem) {
        if (newsItem == null || newsItem.getTitle() == null || newsItem.getTitle().isBlank()) {
            return "未命名新闻";
        }
        return newsItem.getTitle();
    }
}