package com.bot.service;

import com.bot.model.FetchResult;
import com.bot.model.NewsItem;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestTemplate;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class CommentSourceServiceTest {

    @Test
    void fetchesZhihuCommentsFromRealEndpointShape() {
        RestTemplate restTemplate = mock(RestTemplate.class);
        CommentSourceService service = new CommentSourceService(restTemplate);
        NewsItem newsItem = NewsItem.builder().id("zh-123456").source("知乎日报").title("知乎新闻").build();

        when(restTemplate.getForObject(contains("/long-comments"), eq(Map.class)))
                .thenReturn(Map.of("comments", List.of(Map.of("content", "长评论一"))));
        when(restTemplate.getForObject(contains("/short-comments"), eq(Map.class)))
                .thenReturn(Map.of("comments", List.of(Map.of("content", "短评论二"))));

        FetchResult<List<String>> result = service.fetchCommentsWithAlert(newsItem);

        assertTrue(result.alerts().isEmpty());
        assertEquals(List.of("长评论一", "短评论二"), result.data());
    }

    @Test
    void fetchesHackerNewsCommentsFromDiscussionUrl() {
        RestTemplate restTemplate = mock(RestTemplate.class);
        CommentSourceService service = new CommentSourceService(restTemplate);
        NewsItem newsItem = NewsItem.builder()
                .source("HackerNews")
                .title("HN 新闻")
                .discussionUrl("https://news.ycombinator.com/item?id=999")
                .build();

        when(restTemplate.getForObject(contains("item/999.json"), eq(Map.class)))
                .thenReturn(Map.of("kids", List.of(1001)));
        when(restTemplate.getForObject(contains("item/1001.json"), eq(Map.class)))
                .thenReturn(Map.of("text", "<p>Hello &amp; world</p>"));

        FetchResult<List<String>> result = service.fetchCommentsWithAlert(newsItem);

        assertTrue(result.alerts().isEmpty());
        assertEquals(List.of("Hello & world"), result.data());
    }
}