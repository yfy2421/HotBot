package com.bot.service;

import com.bot.client.PythonMLClient;
import com.bot.config.AppConfig;
import com.bot.model.AssistantChatRequest;
import com.bot.model.AssistantChatResponse;
import com.bot.model.NewsItem;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AssistantConversationServiceTest {

    @Test
    void plainNewsRequestDoesNotTriggerChatFollowUpTracking() {
        NewsService newsService = mock(NewsService.class);
        WeatherService weatherService = mock(WeatherService.class);
        TrackingService trackingService = mock(TrackingService.class);
        PythonMLClient mlClient = mock(PythonMLClient.class);
        WeChatPusher pusher = mock(WeChatPusher.class);
        NewsCardRenderer renderer = renderer(null);

        when(newsService.fetchLayered(eq("news"), eq("all"))).thenReturn(List.of(news("n1", "测试新闻")));
        when(mlClient.chat(anyString(), anyList(), anyString())).thenReturn("普通新闻分析");

        AssistantConversationService service = new AssistantConversationService(newsService, weatherService, trackingService, mlClient, pusher, renderer);

        AssistantChatResponse response = service.chat(request("conv-plain", "最近有什么新闻"));

        verify(trackingService, never()).findRecentFollowUpsWithAlerts(anyList(), anyInt());
        assertFalse(response.getReply().contains("近 7 天后续追踪"));
    }

    @Test
    void followUpQuestionReusesSnapshotAndShowsTrackingSection() {
        NewsService newsService = mock(NewsService.class);
        WeatherService weatherService = mock(WeatherService.class);
        TrackingService trackingService = mock(TrackingService.class);
        PythonMLClient mlClient = mock(PythonMLClient.class);
        WeChatPusher pusher = mock(WeChatPusher.class);
        NewsCardRenderer renderer = renderer(null);

        when(newsService.fetchLayered(eq("news"), eq("all"))).thenReturn(List.of(news("n1", "测试新闻")));
        when(mlClient.chat(anyString(), anyList(), anyString())).thenReturn("这是同一主题的延续报道。");
        doAnswer(invocation -> {
            @SuppressWarnings("unchecked")
            List<NewsItem> items = invocation.getArgument(0, List.class);
            items.get(0).setFollowUpOf("old-1");
            items.get(0).setFollowUpTag("📌 后续: 更早的一条相关报道");
            return List.of();
        }).when(trackingService).findRecentFollowUpsWithAlerts(anyList(), eq(3));

        AssistantConversationService service = new AssistantConversationService(newsService, weatherService, trackingService, mlClient, pusher, renderer);

        service.chat(request("conv-follow", "最近有什么新闻"));
        AssistantChatResponse response = service.chat(request("conv-follow", "这些新闻有没有后续"));

        verify(trackingService).findRecentFollowUpsWithAlerts(anyList(), eq(3));
        assertTrue(response.getReply().contains("## 近 7 天后续追踪"));
        assertTrue(response.getReply().contains("📌 后续: 更早的一条相关报道"));
        assertNotNull(response.getNewsSnapshot());
        assertTrue(response.getNewsSnapshot().stream().anyMatch(item -> "📌 后续: 更早的一条相关报道".equals(item.getFollowUpTag())));
    }

    @Test
    void hotNewsCommandAttachesNewsCardMedia(@TempDir Path tempDir) throws Exception {
        NewsService newsService = mock(NewsService.class);
        WeatherService weatherService = mock(WeatherService.class);
        TrackingService trackingService = mock(TrackingService.class);
        PythonMLClient mlClient = mock(PythonMLClient.class);
        WeChatPusher pusher = mock(WeChatPusher.class);
        NewsCardRenderer renderer = renderer(tempDir);

        when(newsService.fetchLayered(eq("hotlist"), eq("all"))).thenReturn(List.of(news("n1", "测试热点一"), news("n2", "测试热点二")));

        AssistantConversationService service = new AssistantConversationService(newsService, weatherService, trackingService, mlClient, pusher, renderer);

        AssistantChatResponse response = service.chat(request("conv-card", "今日热点"));

        assertEquals("image", response.getMediaType());
        assertNotNull(response.getMediaPath());
        assertTrue(Path.of(response.getMediaPath()).isAbsolute());
        assertTrue(Files.exists(Path.of(response.getMediaPath())));
        assertTrue(response.getReply().contains("## 今日热点快照（热榜层）"));
    }

    private NewsCardRenderer renderer(Path outputDir) {
        AppConfig.BotConfig botConfig = new AppConfig.BotConfig();
        AppConfig.BotConfig.NewsConfig newsConfig = new AppConfig.BotConfig.NewsConfig();
        if (outputDir != null) {
            newsConfig.setCardOutputDir(outputDir.toString());
        }
        botConfig.setNews(newsConfig);
        return new NewsCardRenderer(botConfig);
    }

    private AssistantChatRequest request(String conversationId, String content) {
        AssistantChatRequest request = new AssistantChatRequest();
        request.setConversationId(conversationId);
        request.setContent(content);
        request.setSendReply(false);
        return request;
    }

    private NewsItem news(String id, String title) {
        return NewsItem.builder()
                .id(id)
                .title(title)
                .url("https://example.com/" + id)
                .source("测试源")
                .sourceType("news")
                .category("general")
                .trustLevel("aggregated")
                .publishTime("2026-05-21")
                .build();
    }
}