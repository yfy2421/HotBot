package com.bot.service;

import com.bot.client.PythonMLClient;
import com.bot.config.AppConfig;
import com.bot.model.AssistantChatRequest;
import com.bot.model.AssistantChatResponse;
import com.bot.model.AssistantMessage;
import com.bot.model.FetchResult;
import com.bot.model.NewsItem;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Map;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AssistantConversationServiceTest {

    @Test
    void newsOverviewQueryReturnsImageWithoutAiAnalysis(@TempDir Path tempDir) {
        NewsService newsService = mock(NewsService.class);
        WeatherService weatherService = mock(WeatherService.class);
        TrackingService trackingService = mock(TrackingService.class);
        PythonMLClient mlClient = mock(PythonMLClient.class);
        WeChatPusher pusher = mock(WeChatPusher.class);

        when(newsService.fetchAll()).thenReturn(List.of(
                news("n1", "测试新闻甲", "news"),
                news("n2", "测试新闻乙", "news")
        ));

        AssistantConversationService service = service(newsService, weatherService, trackingService, mlClient, pusher, tempDir.resolve("state-1"), tempDir.resolve("cards-1"));

        AssistantChatResponse response = service.chat(request("conv-overview", "最近有什么新闻"));

        assertEquals("image", response.getMediaType());
        assertNotNull(response.getMediaPath());
        assertTrue(Files.exists(Path.of(response.getMediaPath())));
        assertTrue(response.getReply().contains("回复 1-2 或大致标题查看详情"));
        verify(mlClient, never()).chat(anyString(), anyList(), anyString());
        verify(trackingService, never()).findRecentFollowUpsWithAlerts(anyList(), anyInt());
    }

    @Test
    void detailSelectionByIndexReturnsSingleItemCard(@TempDir Path tempDir) {
        NewsService newsService = mock(NewsService.class);
        WeatherService weatherService = mock(WeatherService.class);
        TrackingService trackingService = mock(TrackingService.class);
        PythonMLClient mlClient = mock(PythonMLClient.class);
        WeChatPusher pusher = mock(WeChatPusher.class);

        when(newsService.fetchAll()).thenReturn(List.of(
                news("n1", "测试热点甲", "hotlist"),
                news("n2", "测试热点乙", "hotlist")
        ));

        AssistantConversationService service = service(newsService, weatherService, trackingService, mlClient, pusher, tempDir.resolve("state-2"), tempDir.resolve("cards-2"));

        service.chat(request("conv-detail", "今日热点"));
        AssistantChatResponse response = service.chat(request("conv-detail", "1"));

        assertEquals("image", response.getMediaType());
        assertNotNull(response.getMediaPath());
        assertEquals(1, response.getNewsSnapshot().size());
        assertEquals("测试热点甲", response.getNewsSnapshot().get(0).getTitle());
        assertTrue(response.getReply().contains("分析这条"));
    }

    @Test
    void fuzzyTitleSelectionReturnsSingleItemCard(@TempDir Path tempDir) {
        NewsService newsService = mock(NewsService.class);
        WeatherService weatherService = mock(WeatherService.class);
        TrackingService trackingService = mock(TrackingService.class);
        PythonMLClient mlClient = mock(PythonMLClient.class);
        WeChatPusher pusher = mock(WeChatPusher.class);

        when(newsService.fetchAll()).thenReturn(List.of(
                news("n1", "多流LLM 论文", "hotlist"),
                news("n2", "测试热点乙", "hotlist")
        ));

        AssistantConversationService service = service(newsService, weatherService, trackingService, mlClient, pusher, tempDir.resolve("state-3"), tempDir.resolve("cards-3"));

        service.chat(request("conv-fuzzy", "今日热点"));
        AssistantChatResponse response = service.chat(request("conv-fuzzy", "多流LLM"));

        assertEquals("image", response.getMediaType());
        assertEquals(1, response.getNewsSnapshot().size());
        assertEquals("多流LLM 论文", response.getNewsSnapshot().get(0).getTitle());
    }

    @Test
    void colloquialEnglishKeywordSelectionReturnsSingleItemCard(@TempDir Path tempDir) {
        NewsService newsService = mock(NewsService.class);
        WeatherService weatherService = mock(WeatherService.class);
        TrackingService trackingService = mock(TrackingService.class);
        PythonMLClient mlClient = mock(PythonMLClient.class);
        WeChatPusher pusher = mock(WeChatPusher.class);

        when(newsService.fetchAll()).thenReturn(List.of(
                news("n1", "古巴国家主席：古巴不会容忍对其历史的侮辱", "news"),
                news("n2", "谁将从SpaceX IPO中受益最大？ 主要是埃隆马斯克以及他内部圈子的少数人。", "news")
        ));

        AssistantConversationService service = service(newsService, weatherService, trackingService, mlClient, pusher, tempDir.resolve("state-3b"), tempDir.resolve("cards-3b"));

        service.chat(request("conv-colloquial", "今日新闻"));
        AssistantChatResponse response = service.chat(request("conv-colloquial", "space那个看看"));

        assertEquals("image", response.getMediaType());
        assertEquals(1, response.getNewsSnapshot().size());
        assertTrue(response.getNewsSnapshot().get(0).getTitle().contains("SpaceX"));
        assertTrue(response.getReply().contains("新闻详情"));
    }

    @Test
    void semanticRankingSelectsBestMatchingWaymoItem(@TempDir Path tempDir) {
        NewsService newsService = mock(NewsService.class);
        WeatherService weatherService = mock(WeatherService.class);
        TrackingService trackingService = mock(TrackingService.class);
        PythonMLClient mlClient = mock(PythonMLClient.class);
        WeChatPusher pusher = mock(WeChatPusher.class);

        when(newsService.fetchAll()).thenReturn(List.of(
                news("n1", "Waymo在无人驾驶出租车持续驶入洪水区域之后，将暂停扩展至四个城市", "news"),
                news("n2", "Waymo在无人驾驶出租车在施工区域行驶困难后暂停高速公路服务", "news"),
                news("n3", "苹果发布 CarPlay 新功能", "news")
        ));
        when(mlClient.rankCandidates(eq("waymo在无人驾驶出租车"), anyList(), eq(2))).thenReturn(List.of(
                new PythonMLClient.SemanticRankMatch(1, "candidate-2", 0.88d, 0.71d, 0.94d),
                new PythonMLClient.SemanticRankMatch(0, "candidate-1", 0.73d, 0.69d, 0.75d),
                new PythonMLClient.SemanticRankMatch(2, "candidate-3", 0.12d, 0.10d, 0.15d)
        ));

        AssistantConversationService service = service(newsService, weatherService, trackingService, mlClient, pusher, tempDir.resolve("state-semantic-1"), tempDir.resolve("cards-semantic-1"));

        service.chat(request("conv-semantic", "今日新闻"));
        AssistantChatResponse response = service.chat(request("conv-semantic", "Waymo在无人驾驶出租车那条"));

        assertEquals("image", response.getMediaType());
        assertEquals(1, response.getNewsSnapshot().size());
        assertEquals("Waymo在无人驾驶出租车在施工区域行驶困难后暂停高速公路服务", response.getNewsSnapshot().get(0).getTitle());
    }

    @Test
    void semanticRankingReturnsCandidateListWhenScoresAreTooClose(@TempDir Path tempDir) {
        NewsService newsService = mock(NewsService.class);
        WeatherService weatherService = mock(WeatherService.class);
        TrackingService trackingService = mock(TrackingService.class);
        PythonMLClient mlClient = mock(PythonMLClient.class);
        WeChatPusher pusher = mock(WeChatPusher.class);

        when(newsService.fetchAll()).thenReturn(List.of(
                news("n1", "Waymo在无人驾驶出租车持续驶入洪水区域之后，将暂停扩展至四个城市", "news"),
                news("n2", "Waymo在无人驾驶出租车在施工区域行驶困难后暂停高速公路服务", "news"),
                news("n3", "苹果发布 CarPlay 新功能", "news")
        ));
        when(mlClient.rankCandidates(eq("waymo"), anyList(), eq(2))).thenReturn(List.of(
                new PythonMLClient.SemanticRankMatch(1, "candidate-2", 0.69d, 0.70d, 0.68d),
                new PythonMLClient.SemanticRankMatch(0, "candidate-1", 0.66d, 0.68d, 0.65d),
                new PythonMLClient.SemanticRankMatch(2, "candidate-3", 0.18d, 0.17d, 0.20d)
        ));

        AssistantConversationService service = service(newsService, weatherService, trackingService, mlClient, pusher, tempDir.resolve("state-semantic-2"), tempDir.resolve("cards-semantic-2"));

        service.chat(request("conv-semantic-2", "今日新闻"));
        AssistantChatResponse response = service.chat(request("conv-semantic-2", "Waymo那条"));

        assertNull(response.getMediaType());
        assertTrue(response.getReply().contains("我更可能指这几条"));
        assertTrue(response.getReply().contains("1. Waymo在无人驾驶出租车持续驶入洪水区域之后，将暂停扩展至四个城市"));
        assertTrue(response.getReply().contains("2. Waymo在无人驾驶出租车在施工区域行驶困难后暂停高速公路服务"));
    }

    @Test
    void overviewQueryAggregatesAllSourcesIntoContentBuckets(@TempDir Path tempDir) {
        NewsService newsService = mock(NewsService.class);
        WeatherService weatherService = mock(WeatherService.class);
        TrackingService trackingService = mock(TrackingService.class);
        PythonMLClient mlClient = mock(PythonMLClient.class);
        WeChatPusher pusher = mock(WeChatPusher.class);

        List<NewsItem> allItems = new ArrayList<>();
        for (int index = 1; index <= 3; index++) {
            allItems.add(newsWithSource("ca-" + index, "国际时事 " + index, "news", "新华社新闻_新华网", "外交会议与社会新闻摘要"));
            allItems.add(newsWithSource("tech-" + index, "SpaceX 火箭技术 " + index, "news", "TechCrunch RSS", "科技与航天进展"));
            allItems.add(newsWithSource("hum-" + index, "各国国旗冷知识 " + index, "hotlist", "知乎日报", "文化与地理知识帖"));
            allItems.add(newsWithSource("ent-" + index, "电影票房观察 " + index, "news", "测试娱乐源", "明星电影娱乐资讯"));
        }
        when(newsService.fetchAll()).thenReturn(allItems);

        AssistantConversationService service = service(newsService, weatherService, trackingService, mlClient, pusher, tempDir.resolve("state-3c"), tempDir.resolve("cards-3c"));

        AssistantChatResponse response = service.chat(request("conv-fallback", "今日热点"));

        assertEquals("image", response.getMediaType());
        assertEquals(12, response.getNewsSnapshot().size());
        assertTrue(response.getReply().contains("回复 1-12 或大致标题查看详情"));
        assertTrue(response.getNewsSnapshot().stream().map(NewsItem::getCategory).anyMatch("current_affairs"::equals));
        assertTrue(response.getNewsSnapshot().stream().map(NewsItem::getCategory).anyMatch("tech_science"::equals));
        assertTrue(response.getNewsSnapshot().stream().map(NewsItem::getCategory).anyMatch("humanities_nature"::equals));
        assertTrue(response.getNewsSnapshot().stream().map(NewsItem::getCategory).anyMatch("entertainment"::equals));
        verify(newsService).fetchAll();
    }

    @Test
    void detailSelectionHydratesZhihuDetailContentBeforeRendering(@TempDir Path tempDir) {
        NewsService newsService = mock(NewsService.class);
        WeatherService weatherService = mock(WeatherService.class);
        TrackingService trackingService = mock(TrackingService.class);
        CommentSourceService commentSourceService = mock(CommentSourceService.class);
        PythonMLClient mlClient = mock(PythonMLClient.class);
        WeChatPusher pusher = mock(WeChatPusher.class);

        NewsItem item = news("zh-1", "有什么关于各国国歌、国旗、国徽的冷知识？", "hotlist");
        item.setSource("知乎日报");
        item.setSummary("我就是个画地图的 · 12 分钟阅读");
        when(newsService.fetchAll()).thenReturn(List.of(item));
        when(commentSourceService.fetchDetailContentWithAlert(any(NewsItem.class))).thenReturn(FetchResult.of("文章主要从各国国旗、国歌和国徽的设计来源切入，挑了几个最有代表性的冷知识来讲，比如特殊旗帜形状、图案象征和历史误读。"));

        AssistantConversationService service = service(newsService, weatherService, trackingService, commentSourceService, mlClient, pusher, renderer(tempDir.resolve("cards-3d")), tempDir.resolve("state-3d"));

        AssistantChatResponse response = service.chat(request("conv-zhihu-detail", "今日热点"));

        assertEquals("image", response.getMediaType());
        assertEquals(1, response.getNewsSnapshot().size());
        assertTrue(response.getNewsSnapshot().get(0).getFullBody().contains("文章主要从各国国旗"));
        assertTrue(response.getNewsSnapshot().get(0).getDetailExcerpt().contains("文章主要从各国国旗"));
        assertNull(response.getNewsSnapshot().get(0).getDetailContent());
    }

    @Test
    void legacyDetailContentStillBackfillsExcerptForRendering(@TempDir Path tempDir) {
        NewsService newsService = mock(NewsService.class);
        WeatherService weatherService = mock(WeatherService.class);
        TrackingService trackingService = mock(TrackingService.class);
        CommentSourceService commentSourceService = mock(CommentSourceService.class);
        PythonMLClient mlClient = mock(PythonMLClient.class);
        WeChatPusher pusher = mock(WeChatPusher.class);

        NewsItem item = news("legacy-1", "只带 legacy 详情字段的新闻", "news");
        item.setDetailContent("这是一段只存在于 legacy detailContent 的旧摘录。仍然应该能被详情渲染链路读取。");
        when(newsService.fetchAll()).thenReturn(List.of(item));

        AssistantConversationService service = service(newsService, weatherService, trackingService, commentSourceService, mlClient, pusher, renderer(tempDir.resolve("cards-legacy")), tempDir.resolve("state-legacy"));

        AssistantChatResponse response = service.chat(request("conv-legacy-detail", "今日新闻"));

        assertEquals("image", response.getMediaType());
        assertEquals(1, response.getNewsSnapshot().size());
        assertEquals("这是一段只存在于 legacy detailContent 的旧摘录。仍然应该能被详情渲染链路读取。", response.getNewsSnapshot().get(0).getDetailExcerpt());
        assertNull(response.getNewsSnapshot().get(0).getDetailContent());
        assertNull(response.getNewsSnapshot().get(0).getFullBody());
    }

    @Test
    void excerptOnlyDetailRendersWithoutFullBody(@TempDir Path tempDir) {
        NewsService newsService = mock(NewsService.class);
        WeatherService weatherService = mock(WeatherService.class);
        TrackingService trackingService = mock(TrackingService.class);
        CommentSourceService commentSourceService = mock(CommentSourceService.class);
        PythonMLClient mlClient = mock(PythonMLClient.class);
        WeChatPusher pusher = mock(WeChatPusher.class);

        NewsItem item = news("excerpt-1", "只带 excerpt 的新闻", "news");
        item.setDetailExcerpt("这是一段已经存在的详情摘录，应直接作为详情内容使用。 ");
        when(newsService.fetchAll()).thenReturn(List.of(item));
        when(commentSourceService.fetchDetailContentWithAlert(any(NewsItem.class))).thenReturn(FetchResult.of(""));

        AssistantConversationService service = service(newsService, weatherService, trackingService, commentSourceService, mlClient, pusher, renderer(tempDir.resolve("cards-excerpt")), tempDir.resolve("state-excerpt"));

        AssistantChatResponse response = service.chat(request("conv-excerpt-detail", "今日新闻"));

        assertEquals("image", response.getMediaType());
        assertEquals(1, response.getNewsSnapshot().size());
        assertEquals("这是一段已经存在的详情摘录，应直接作为详情内容使用。", response.getNewsSnapshot().get(0).getDetailExcerpt());
        assertNull(response.getNewsSnapshot().get(0).getDetailContent());
        assertNull(response.getNewsSnapshot().get(0).getFullBody());
    }

    @Test
    void englishFullBodyIsTranslatedLocallyBeforeRenderingDetailCard(@TempDir Path tempDir) {
        NewsService newsService = mock(NewsService.class);
        WeatherService weatherService = mock(WeatherService.class);
        TrackingService trackingService = mock(TrackingService.class);
        PythonMLClient mlClient = mock(PythonMLClient.class);
        WeChatPusher pusher = mock(WeChatPusher.class);
        NewsCardRenderer renderer = mock(NewsCardRenderer.class);

        NewsItem item = news("n5", "Waymo service update", "news");
        item.setDetailExcerpt("Waymo paused highway service after a construction-zone incident.");
        item.setFullBody("Waymo paused highway service after a construction-zone incident. The company said it will fix route safety issues before reopening the feature.");
        when(newsService.fetchAll()).thenReturn(List.of(item));
        when(mlClient.translateTexts(eq(List.of(item.getFullBody())), eq("body"))).thenReturn(List.of("Waymo 因施工区域事故暂停高速服务。公司表示会先修复路线安全问题，再恢复这项功能。"));
        when(renderer.renderDetailCard(anyString(), anyString(), any(NewsItem.class), anyMap(), anyMap(), anyString())).thenReturn(tempDir.resolve("translated-body.png").toString());

        AssistantConversationService service = service(newsService, weatherService, trackingService, mock(CommentSourceService.class), mlClient, pusher, renderer, tempDir.resolve("state-3body"));

        AssistantChatResponse response = service.chat(request("conv-body-translation", "今日新闻"));

        ArgumentCaptor<NewsItem> itemCaptor = ArgumentCaptor.forClass(NewsItem.class);
        verify(renderer).renderDetailCard(anyString(), anyString(), itemCaptor.capture(), anyMap(), anyMap(), anyString());
        assertEquals("image", response.getMediaType());
        assertEquals("Waymo 因施工区域事故暂停高速服务。公司表示会先修复路线安全问题，再恢复这项功能。", itemCaptor.getValue().getTranslatedFullBody());
        assertTrue(itemCaptor.getValue().getTranslatedDetailExcerpt().contains("Waymo 因施工区域事故暂停高速服务"));
        verify(mlClient).translateTexts(eq(List.of(item.getFullBody())), eq("body"));
    }

    @Test
    void englishSummaryIsTranslatedBeforeRenderingDetailCard(@TempDir Path tempDir) {
        NewsService newsService = mock(NewsService.class);
        WeatherService weatherService = mock(WeatherService.class);
        TrackingService trackingService = mock(TrackingService.class);
        PythonMLClient mlClient = mock(PythonMLClient.class);
        WeChatPusher pusher = mock(WeChatPusher.class);
        NewsCardRenderer renderer = mock(NewsCardRenderer.class);

        NewsItem item = news("n3", "SpaceX 股权结构", "news");
        item.setSummary("Elon Musk has the largest stake in SpaceX by billions of shares. The other biggest shareholders also have longstanding and deep ties to Musk.");
        when(newsService.fetchAll()).thenReturn(List.of(item));
    when(mlClient.translateTexts(eq(List.of(item.getSummary())), eq("summary"))).thenReturn(List.of("马斯克持有 SpaceX 最大股份，其他主要股东也与他关系紧密。"));
        when(renderer.renderDetailCard(anyString(), anyString(), any(NewsItem.class), anyMap(), anyMap(), anyString())).thenReturn(tempDir.resolve("translated-summary.png").toString());

        AssistantConversationService service = service(newsService, weatherService, trackingService, mock(CommentSourceService.class), mlClient, pusher, renderer, tempDir.resolve("state-3d"));

        AssistantChatResponse response = service.chat(request("conv-summary-translation", "今日新闻"));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, String>> summaryCaptor = ArgumentCaptor.forClass((Class<Map<String, String>>) (Class<?>) Map.class);
        ArgumentCaptor<NewsItem> itemCaptor = ArgumentCaptor.forClass(NewsItem.class);
        verify(renderer).renderDetailCard(anyString(), anyString(), itemCaptor.capture(), anyMap(), summaryCaptor.capture(), anyString());
        assertEquals("image", response.getMediaType());
        assertEquals("马斯克持有 SpaceX 最大股份，其他主要股东也与他关系紧密。", itemCaptor.getValue().getTranslatedSummaryPreview());
        assertEquals("马斯克持有 SpaceX 最大股份，其他主要股东也与他关系紧密。", summaryCaptor.getValue().get(item.summaryPreviewText()));
        verify(mlClient).translateTexts(eq(List.of(item.getSummary())), eq("summary"));
        verify(mlClient, never()).chat(anyString(), anyList(), anyString());
    }

    @Test
    void translatedSummaryPreviewSurvivesPersistedSnapshotAfterRestart(@TempDir Path tempDir) {
        NewsService newsService = mock(NewsService.class);
        WeatherService weatherService = mock(WeatherService.class);
        TrackingService trackingService = mock(TrackingService.class);
        WeChatPusher pusher = mock(WeChatPusher.class);
        Path stateDir = tempDir.resolve("assistant-state-summary");

        NewsItem item = news("n3", "SpaceX 股权结构", "news");
        item.setSummary("Elon Musk has the largest stake in SpaceX by billions of shares. The other biggest shareholders also have longstanding and deep ties to Musk.");
        when(newsService.fetchAll()).thenReturn(List.of(item));

        PythonMLClient firstMlClient = mock(PythonMLClient.class);
        when(firstMlClient.translateTexts(eq(List.of(item.summaryPreviewText())), eq("summary"))).thenReturn(List.of("马斯克持有 SpaceX 最大股份，其他主要股东也与他关系紧密。"));
        NewsCardRenderer firstRenderer = mock(NewsCardRenderer.class);
        when(firstRenderer.renderDetailCard(anyString(), anyString(), any(NewsItem.class), anyMap(), anyMap(), anyString())).thenReturn(tempDir.resolve("persisted-summary-1.png").toString());

        AssistantConversationService firstService = service(newsService, weatherService, trackingService, mock(CommentSourceService.class), firstMlClient, pusher, firstRenderer, stateDir);
        firstService.chat(request("conv-summary-restart", "今日新闻"));

        PythonMLClient secondMlClient = mock(PythonMLClient.class);
        NewsCardRenderer secondRenderer = mock(NewsCardRenderer.class);
        when(secondRenderer.renderDetailCard(anyString(), anyString(), any(NewsItem.class), anyMap(), anyMap(), anyString())).thenReturn(tempDir.resolve("persisted-summary-2.png").toString());

        AssistantConversationService secondService = service(newsService, weatherService, trackingService, mock(CommentSourceService.class), secondMlClient, pusher, secondRenderer, stateDir);
        AssistantChatResponse response = secondService.chat(request("conv-summary-restart", "1"));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, String>> summaryCaptor = ArgumentCaptor.forClass((Class<Map<String, String>>) (Class<?>) Map.class);
        ArgumentCaptor<NewsItem> itemCaptor = ArgumentCaptor.forClass(NewsItem.class);
        verify(secondRenderer).renderDetailCard(anyString(), anyString(), itemCaptor.capture(), anyMap(), summaryCaptor.capture(), anyString());
        verify(secondMlClient, never()).translateTexts(anyList(), eq("summary"));
        verify(newsService, times(1)).fetchAll();
        assertEquals("image", response.getMediaType());
        assertEquals("马斯克持有 SpaceX 最大股份，其他主要股东也与他关系紧密。", itemCaptor.getValue().getTranslatedSummaryPreview());
        assertEquals("马斯克持有 SpaceX 最大股份，其他主要股东也与他关系紧密。", summaryCaptor.getValue().get(item.summaryPreviewText()));
    }

    @Test
    void englishTitleIsTranslatedLocallyBeforeRenderingDetailCard(@TempDir Path tempDir) {
        NewsService newsService = mock(NewsService.class);
        WeatherService weatherService = mock(WeatherService.class);
        TrackingService trackingService = mock(TrackingService.class);
        PythonMLClient mlClient = mock(PythonMLClient.class);
        WeChatPusher pusher = mock(WeChatPusher.class);
        NewsCardRenderer renderer = mock(NewsCardRenderer.class);

        NewsItem item = news("n4", "Waymo pauses highway service after construction-zone trouble", "news");
        when(newsService.fetchAll()).thenReturn(List.of(item));
        when(mlClient.translateTexts(eq(List.of(item.getTitle())), eq("title"))).thenReturn(List.of("Waymo 因施工区域行驶问题暂停高速服务"));
        when(renderer.renderDetailCard(anyString(), anyString(), any(NewsItem.class), anyMap(), anyMap(), anyString())).thenReturn(tempDir.resolve("translated-title.png").toString());

        AssistantConversationService service = service(newsService, weatherService, trackingService, mock(CommentSourceService.class), mlClient, pusher, renderer, tempDir.resolve("state-3title"));

        AssistantChatResponse response = service.chat(request("conv-title-translation", "今日新闻"));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, String>> titleCaptor = ArgumentCaptor.forClass((Class<Map<String, String>>) (Class<?>) Map.class);
        verify(renderer).renderDetailCard(anyString(), anyString(), any(NewsItem.class), titleCaptor.capture(), anyMap(), anyString());
        assertEquals("image", response.getMediaType());
        assertEquals("Waymo 因施工区域行驶问题暂停高速服务", titleCaptor.getValue().get(item.getTitle()));
        verify(mlClient).translateTexts(eq(List.of(item.getTitle())), eq("title"));
        verify(mlClient, never()).chat(anyString(), anyList(), anyString());
    }

    @Test
    void analysisOnKnowledgeHotlistUsesCommunityTemplateAndDetailExcerpt(@TempDir Path tempDir) {
        NewsService newsService = mock(NewsService.class);
        WeatherService weatherService = mock(WeatherService.class);
        TrackingService trackingService = mock(TrackingService.class);
        CommentSourceService commentSourceService = mock(CommentSourceService.class);
        PythonMLClient mlClient = mock(PythonMLClient.class);
        WeChatPusher pusher = mock(WeChatPusher.class);

        NewsItem item = news("zh-2", "有什么关于各国国歌、国旗、国徽的冷知识？", "hotlist");
        item.setSource("知乎日报");
        item.setSummary("我就是个画地图的 · 12 分钟阅读");
        when(newsService.fetchAll()).thenReturn(List.of(item));
        when(commentSourceService.fetchDetailContentWithAlert(any(NewsItem.class))).thenReturn(FetchResult.of("正文摘录：这篇内容主要解释了不同国家旗帜和徽章背后的历史来源，以及几个常见误解。"));
        when(mlClient.chat(anyString(), anyList(), anyString())).thenReturn("## 判断\n\n这是篇知识帖。");

        AssistantConversationService service = service(newsService, weatherService, trackingService, commentSourceService, mlClient, pusher, renderer(tempDir.resolve("cards-3e")), tempDir.resolve("state-3e"));

        service.chat(request("conv-zhihu-analysis", "今日热点"));
        AssistantChatResponse response = service.chat(request("conv-zhihu-analysis", "分析这条"));

        ArgumentCaptor<String> promptCaptor = ArgumentCaptor.forClass(String.class);
        verify(mlClient).chat(eq("分析这条"), anyList(), promptCaptor.capture());
        assertNull(response.getMediaType());
        assertTrue(promptCaptor.getValue().contains("社区热榜里的知识帖/内容帖"));
        assertTrue(promptCaptor.getValue().contains("详情摘录: 正文摘录：这篇内容主要解释了不同国家旗帜和徽章背后的历史来源"));
    }

    @Test
    void analysisRequestOnFocusedItemReturnsTextWithoutMedia(@TempDir Path tempDir) {
        NewsService newsService = mock(NewsService.class);
        WeatherService weatherService = mock(WeatherService.class);
        TrackingService trackingService = mock(TrackingService.class);
        PythonMLClient mlClient = mock(PythonMLClient.class);
        WeChatPusher pusher = mock(WeChatPusher.class);

        when(newsService.fetchAll()).thenReturn(List.of(
                news("n1", "测试热点甲", "hotlist"),
                news("n2", "测试热点乙", "hotlist")
        ));
        when(mlClient.chat(anyString(), anyList(), anyString())).thenReturn("## 判断\n\n这条值得继续看。\n\n## 为什么值得看\n\n它说明方向有新变化。\n\n## 别高估什么\n\n现在证据还早。\n\n## 接下来观察什么\n\n先看是否有后续披露。");

        AssistantConversationService service = service(newsService, weatherService, trackingService, mlClient, pusher, tempDir.resolve("state-4"), tempDir.resolve("cards-4"));

        service.chat(request("conv-analysis", "今日热点"));
        service.chat(request("conv-analysis", "1"));
        AssistantChatResponse response = service.chat(request("conv-analysis", "分析这个热点"));

        assertNull(response.getMediaType());
        assertFalse(response.getReply().contains("## 本次回答依据的新闻快照"));
        assertTrue(response.getReply().contains("## 判断"));
        assertTrue(response.getReply().contains("## 别高估什么"));
        verify(mlClient).chat(eq("分析这个热点"), anyList(), anyString());
    }

    @Test
    void switchingFocusedItemReusesSnapshotForSubsequentAnalysis(@TempDir Path tempDir) {
        NewsService newsService = mock(NewsService.class);
        WeatherService weatherService = mock(WeatherService.class);
        TrackingService trackingService = mock(TrackingService.class);
        PythonMLClient mlClient = mock(PythonMLClient.class);
        WeChatPusher pusher = mock(WeChatPusher.class);

        when(newsService.fetchAll()).thenReturn(List.of(
                news("n1", "测试热点甲", "hotlist"),
                news("n2", "测试热点乙", "hotlist")
        ));
        when(mlClient.chat(anyString(), anyList(), anyString())).thenReturn("## 判断\n\n第二条值得继续看。\n\n## 为什么值得看\n\n新变量更多。\n\n## 别高估什么\n\n证据还不够。\n\n## 接下来观察什么\n\n继续盯后续。");

        AssistantConversationService service = service(newsService, weatherService, trackingService, mlClient, pusher, tempDir.resolve("state-focus"), tempDir.resolve("cards-focus"));

        service.chat(request("conv-focus-switch", "今日热点"));
        service.chat(request("conv-focus-switch", "2"));
        AssistantChatResponse response = service.chat(request("conv-focus-switch", "分析这条"));

        ArgumentCaptor<String> promptCaptor = ArgumentCaptor.forClass(String.class);
        verify(mlClient).chat(eq("分析这条"), anyList(), promptCaptor.capture());
        verify(newsService, times(1)).fetchAll();
        assertNull(response.getMediaType());
        assertTrue(promptCaptor.getValue().contains("测试热点乙"));
        assertFalse(promptCaptor.getValue().contains("测试热点甲"));
        assertTrue(response.getReply().contains("## 判断"));
    }

    @Test
    void analysisRequestWithoutFocusedItemAsksForClarification(@TempDir Path tempDir) {
        NewsService newsService = mock(NewsService.class);
        WeatherService weatherService = mock(WeatherService.class);
        TrackingService trackingService = mock(TrackingService.class);
        PythonMLClient mlClient = mock(PythonMLClient.class);
        WeChatPusher pusher = mock(WeChatPusher.class);

        when(newsService.fetchAll()).thenReturn(List.of(
                news("n1", "测试热点甲", "hotlist"),
                news("n2", "测试热点乙", "hotlist")
        ));

        AssistantConversationService service = service(newsService, weatherService, trackingService, mlClient, pusher, tempDir.resolve("state-5"), tempDir.resolve("cards-5"));

        service.chat(request("conv-ask", "今日热点"));
        AssistantChatResponse response = service.chat(request("conv-ask", "分析这个热点"));

        assertNull(response.getMediaType());
        assertTrue(response.getReply().contains("你可以回复 1-2"));
        verify(mlClient, never()).chat(eq("分析这个热点"), anyList(), anyString());
    }

    @Test
    void followUpQuestionReusesSnapshotAndShowsTrackingSection(@TempDir Path tempDir) {
        NewsService newsService = mock(NewsService.class);
        WeatherService weatherService = mock(WeatherService.class);
        TrackingService trackingService = mock(TrackingService.class);
        PythonMLClient mlClient = mock(PythonMLClient.class);
        WeChatPusher pusher = mock(WeChatPusher.class);

        when(newsService.fetchAll()).thenReturn(List.of(news("n1", "测试新闻", "news")));
        when(mlClient.chat(anyString(), anyList(), anyString())).thenReturn("## 判断\n\n这是同一主题的延续报道。");
        doAnswer(invocation -> {
            @SuppressWarnings("unchecked")
            List<NewsItem> items = invocation.getArgument(0, List.class);
            items.get(0).setFollowUpOf("old-1");
            items.get(0).setFollowUpTag("📌 后续: 更早的一条相关报道");
            return List.of();
        }).when(trackingService).findRecentFollowUpsWithAlerts(anyList(), eq(3));

        AssistantConversationService service = service(newsService, weatherService, trackingService, mlClient, pusher, tempDir.resolve("state-6"), tempDir.resolve("cards-6"));

        service.chat(request("conv-follow", "最近有什么新闻"));
        AssistantChatResponse response = service.chat(request("conv-follow", "这些新闻有没有后续"));

        verify(trackingService).findRecentFollowUpsWithAlerts(anyList(), eq(3));
        assertNull(response.getMediaType());
        assertTrue(response.getReply().contains("## 近 7 天后续追踪"));
        assertTrue(response.getReply().contains("📌 后续: 更早的一条相关报道"));
    }

    @Test
    void conversationHistorySurvivesServiceRestart(@TempDir Path tempDir) {
        NewsService newsService = mock(NewsService.class);
        WeatherService weatherService = mock(WeatherService.class);
        TrackingService trackingService = mock(TrackingService.class);
        WeChatPusher pusher = mock(WeChatPusher.class);
        Path stateDir = tempDir.resolve("assistant-state");

        PythonMLClient firstMlClient = mock(PythonMLClient.class);
        when(firstMlClient.chat(anyString(), anyList(), anyString())).thenReturn("第一次回答");
        AssistantConversationService firstService = service(newsService, weatherService, trackingService, firstMlClient, pusher, stateDir, tempDir.resolve("cards-7"));

        firstService.chat(request("conv-history", "你好"));

        PythonMLClient secondMlClient = mock(PythonMLClient.class);
        when(secondMlClient.chat(anyString(), anyList(), anyString())).thenReturn("第二次回答");
        AssistantConversationService secondService = service(newsService, weatherService, trackingService, secondMlClient, pusher, stateDir, tempDir.resolve("cards-8"));

        secondService.chat(request("conv-history", "继续说"));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<AssistantMessage>> historyCaptor = ArgumentCaptor.forClass((Class<List<AssistantMessage>>) (Class<?>) List.class);
        verify(secondMlClient).chat(eq("继续说"), historyCaptor.capture(), anyString());
        List<AssistantMessage> history = historyCaptor.getValue();
        assertEquals(2, history.size());
        assertEquals("user", history.get(0).getRole());
        assertEquals("你好", history.get(0).getContent());
        assertEquals("assistant", history.get(1).getRole());
        assertEquals("第一次回答", history.get(1).getContent());
    }

    @Test
    void followUpQuestionReusesPersistedSnapshotAfterRestart(@TempDir Path tempDir) {
        NewsService newsService = mock(NewsService.class);
        WeatherService weatherService = mock(WeatherService.class);
        TrackingService trackingService = mock(TrackingService.class);
        WeChatPusher pusher = mock(WeChatPusher.class);
        Path stateDir = tempDir.resolve("assistant-state-2");

        when(newsService.fetchAll()).thenReturn(List.of(news("n1", "测试新闻", "news")));

        PythonMLClient firstMlClient = mock(PythonMLClient.class);
        AssistantConversationService firstService = service(newsService, weatherService, trackingService, firstMlClient, pusher, stateDir, tempDir.resolve("cards-9"));
        firstService.chat(request("conv-restart", "最近有什么新闻"));

        PythonMLClient secondMlClient = mock(PythonMLClient.class);
        when(secondMlClient.chat(anyString(), anyList(), anyString())).thenReturn("## 判断\n\n这是同一主题的延续报道。");
        doAnswer(invocation -> {
            @SuppressWarnings("unchecked")
            List<NewsItem> items = invocation.getArgument(0, List.class);
            items.get(0).setFollowUpOf("old-1");
            items.get(0).setFollowUpTag("📌 后续: 更早的一条相关报道");
            return List.of();
        }).when(trackingService).findRecentFollowUpsWithAlerts(anyList(), eq(3));
        AssistantConversationService secondService = service(newsService, weatherService, trackingService, secondMlClient, pusher, stateDir, tempDir.resolve("cards-10"));

        AssistantChatResponse response = secondService.chat(request("conv-restart", "这些新闻有没有后续"));

        verify(newsService, times(1)).fetchAll();
        verify(trackingService).findRecentFollowUpsWithAlerts(anyList(), eq(3));
        assertTrue(response.getReply().contains("## 近 7 天后续追踪"));
    }

    @Test
    void clearCommandDeletesPersistedSnapshot(@TempDir Path tempDir) {
        NewsService newsService = mock(NewsService.class);
        WeatherService weatherService = mock(WeatherService.class);
        TrackingService trackingService = mock(TrackingService.class);
        WeChatPusher pusher = mock(WeChatPusher.class);
        Path stateDir = tempDir.resolve("assistant-state-3");

        when(newsService.fetchAll()).thenReturn(List.of(news("n1", "测试新闻", "news")));

        PythonMLClient firstMlClient = mock(PythonMLClient.class);
        AssistantConversationService firstService = service(newsService, weatherService, trackingService, firstMlClient, pusher, stateDir, tempDir.resolve("cards-11"));
        firstService.chat(request("conv-clear", "最近有什么新闻"));

        AssistantConversationService clearingService = service(newsService, weatherService, trackingService, mock(PythonMLClient.class), pusher, stateDir, tempDir.resolve("cards-12"));
        clearingService.chat(request("conv-clear", "清空上下文"));

        NewsService restartedNewsService = mock(NewsService.class);
        when(restartedNewsService.fetchLayered(eq("news"), eq("all"))).thenReturn(List.of());
        PythonMLClient restartedMlClient = mock(PythonMLClient.class);
        AssistantConversationService restartedService = service(restartedNewsService, weatherService, trackingService, restartedMlClient, pusher, stateDir, tempDir.resolve("cards-13"));

        AssistantChatResponse response = restartedService.chat(request("conv-clear", "这些新闻有没有后续"));

        verify(restartedNewsService).fetchLayered(eq("news"), eq("all"));
        verify(restartedMlClient, never()).chat(anyString(), anyList(), anyString());
        assertEquals("当前 RSS/聚合 API 没拉到数据，我不输出新闻结论。", response.getReply());
    }

    private AssistantConversationService service(NewsService newsService,
                                                 WeatherService weatherService,
                                                 TrackingService trackingService,
                                                 PythonMLClient mlClient,
                                                 WeChatPusher pusher,
                                                 Path stateDir,
                                                 Path cardDir) {
        return service(newsService, weatherService, trackingService, mock(CommentSourceService.class), mlClient, pusher, renderer(cardDir), stateDir);
        }

        private AssistantConversationService service(NewsService newsService,
                             WeatherService weatherService,
                             TrackingService trackingService,
                             CommentSourceService commentSourceService,
                             PythonMLClient mlClient,
                             WeChatPusher pusher,
                             NewsCardRenderer renderer,
                             Path stateDir) {
        // Default: L2 returns "default"/0.0 -> falls through to keyword behavior
        when(mlClient.classifyIntent(any())).thenReturn(
                new PythonMLClient.IntentClassification("default", 0.0));
        return new AssistantConversationService(
                newsService,
                weatherService,
                trackingService,
            commentSourceService,
                mlClient,
                pusher,
            renderer,
                stateStore(stateDir)
        );
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

    private AssistantConversationStateStore stateStore(Path stateDir) {
        AppConfig.BotConfig botConfig = new AppConfig.BotConfig();
        if (stateDir != null) {
            AppConfig.BotConfig.AssistantConfig assistantConfig = new AppConfig.BotConfig.AssistantConfig();
            assistantConfig.setStateDir(stateDir.toString());
            botConfig.setAssistant(assistantConfig);
        }
        return new AssistantConversationStateStore(botConfig, new ObjectMapper());
    }

    private AssistantChatRequest request(String conversationId, String content) {
        AssistantChatRequest request = new AssistantChatRequest();
        request.setConversationId(conversationId);
        request.setContent(content);
        request.setSendReply(false);
        return request;
    }

    private NewsItem news(String id, String title, String sourceType) {
        return NewsItem.builder()
                .id(id)
                .title(title)
                .summary(title + " 的摘要内容，用于测试图片摘要渲染。")
                .url("https://example.com/" + id)
                .source("测试源")
                .sourceType(sourceType)
                .category("general")
                .trustLevel("aggregated")
                .publishTime("2026-05-21T08:00:00+08:00")
                .build();
    }

    private NewsItem newsWithSource(String id, String title, String sourceType, String source, String summary) {
        NewsItem item = news(id, title, sourceType);
        item.setSource(source);
        item.setSummary(summary);
        item.setDetailExcerpt(summary);
        return item;
    }
}
