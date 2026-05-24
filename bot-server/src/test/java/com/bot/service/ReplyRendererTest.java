package com.bot.service;

import com.bot.model.NewsItem;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ReplyRendererTest {

    @Test
    void overviewPlanBuildsPromptAndOverviewCard() {
        NewsCardRenderer newsCardRenderer = mock(NewsCardRenderer.class);
        ReplyRenderer replyRenderer = renderer(newsCardRenderer);
        List<NewsItem> items = List.of(news("n1", "测试新闻甲", "news"), news("n2", "测试新闻乙", "news"));
        when(newsCardRenderer.renderOverviewCard(anyString(), anyString(), any(), anyMap(), anyMap(), anyString()))
                .thenReturn("/tmp/overview.png");

        ReplyPlan plan = replyRenderer.buildOverviewReplyPlan("最近有什么新闻", "all", items, Map.of(), Map.of());

        assertTrue(plan.reply().contains("已整理成新闻总览长图。"));
        assertTrue(plan.reply().contains("回复 1-2 或大致标题查看详情"));
        assertEquals("image", plan.mediaType());
        assertEquals("/tmp/overview.png", plan.mediaPath());
        verify(newsCardRenderer).renderOverviewCard(anyString(), anyString(), any(), anyMap(), anyMap(), anyString());
    }

    @Test
    void overviewPlanUsesHotlistCopyForHotlistKeywordQuery() {
        NewsCardRenderer newsCardRenderer = mock(NewsCardRenderer.class);
        ReplyRenderer replyRenderer = renderer(newsCardRenderer);
        List<NewsItem> items = List.of(news("n1", "测试热点甲", "hotlist"));
        when(newsCardRenderer.renderOverviewCard(anyString(), anyString(), any(), anyMap(), anyMap(), anyString()))
                .thenReturn("/tmp/hot-overview.png");

        ReplyPlan plan = replyRenderer.buildOverviewReplyPlan("今日热点", "all", items, Map.of(), Map.of());

        assertTrue(plan.reply().contains("已整理成热点总览长图。"));
        assertEquals("image", plan.mediaType());
        assertEquals("/tmp/hot-overview.png", plan.mediaPath());
    }

    @Test
    void overviewPlanUsesCategorySpecificCopyForScopedCategory() {
        NewsCardRenderer newsCardRenderer = mock(NewsCardRenderer.class);
        ReplyRenderer replyRenderer = renderer(newsCardRenderer);
        List<NewsItem> items = List.of(news("n1", "SpaceX 火箭技术", "news"));
        when(newsCardRenderer.renderOverviewCard(anyString(), anyString(), any(), anyMap(), anyMap(), anyString()))
                .thenReturn("/tmp/tech-overview.png");

        ReplyPlan plan = replyRenderer.buildOverviewReplyPlan("最近有什么科技新闻", "tech_science", items, Map.of(), Map.of());

        assertTrue(plan.reply().contains("已整理成“科技科普”总览长图。"));
        assertEquals("image", plan.mediaType());
        assertEquals("/tmp/tech-overview.png", plan.mediaPath());
    }

    @Test
    void detailSnapshotPlanUsesHotlistCopyAndDetailCard() {
        NewsCardRenderer newsCardRenderer = mock(NewsCardRenderer.class);
        ReplyRenderer replyRenderer = renderer(newsCardRenderer);
        NewsItem item = news("n1", "测试热点甲", "hotlist");
        when(newsCardRenderer.renderDetailCard(anyString(), anyString(), any(), anyMap(), anyMap(), anyString()))
                .thenReturn("/tmp/detail.png");

        ReplyPlan plan = replyRenderer.buildDetailSnapshotPlan(List.of(item), item, Map.of(), Map.of());

        assertTrue(plan.reply().contains("热点详情图"));
        assertTrue(plan.reply().contains("分析这条"));
        assertEquals("image", plan.mediaType());
        assertEquals("/tmp/detail.png", plan.mediaPath());
    }

    @Test
    void detailSwitchPlanUsesNewsCopyForNewsItem() {
        NewsCardRenderer newsCardRenderer = mock(NewsCardRenderer.class);
        ReplyRenderer replyRenderer = renderer(newsCardRenderer);
        NewsItem item = news("n1", "测试新闻甲", "news");
        when(newsCardRenderer.renderDetailCard(anyString(), anyString(), any(), anyMap(), anyMap(), anyString()))
                .thenReturn("/tmp/news-detail.png");

        ReplyPlan plan = replyRenderer.buildDetailSwitchPlan(List.of(item), item, Map.of(), Map.of());

        assertTrue(plan.reply().contains("已切到这条新闻详情。"));
        assertEquals("image", plan.mediaType());
        assertEquals("/tmp/news-detail.png", plan.mediaPath());
    }

    @Test
    void groundedReplyIncludesFollowUpSectionAndConclusion() {
        ReplyRenderer replyRenderer = renderer(mock(NewsCardRenderer.class));
        NewsItem item = news("n1", "测试新闻", "news");
        item.setUrl("https://example.com/n1");
        item.setFollowUpTag("📌 后续: 更早的一条相关报道");

        String reply = replyRenderer.buildNewsGroundedReply(true, List.of(item), Map.of(), "## 判断\n\n值得继续看。");

        assertTrue(reply.contains("## 近 7 天后续追踪"));
        assertTrue(reply.contains("📌 后续: 更早的一条相关报道"));
        assertTrue(reply.contains("## 判断"));
    }

    @Test
    void groundedReplyFallsBackToNoFollowUpHitSummary() {
        ReplyRenderer replyRenderer = renderer(mock(NewsCardRenderer.class));

        String reply = replyRenderer.buildNewsGroundedReply(true, List.of(
                news("n1", "测试新闻甲", "news"),
                news("n2", "测试新闻乙", "news")
        ), Map.of(), "");

        assertTrue(reply.contains("## 近 7 天后续追踪"));
        assertTrue(reply.contains("当前快照前 2 条新闻中，暂未命中近 7 天相似报道。"));
    }

    @Test
    void scopedItemClarificationUsesSelectionRange() {
        ReplyRenderer replyRenderer = renderer(mock(NewsCardRenderer.class));

        String reply = replyRenderer.buildScopedItemClarification(5);

        assertEquals("当前快照里有多条内容。你可以回复 1-5、标题关键词，或直接说“分析 1”。", reply);
    }

    private ReplyRenderer renderer(NewsCardRenderer newsCardRenderer) {
        return new ReplyRenderer(
                newsCardRenderer,
                12,
                3,
                item -> "发布时间 5月23日 10:00",
                (item, translatedTitles) -> translatedTitles.getOrDefault(item.getTitle(), item.getTitle()),
                (text, url) -> "[" + text + "](" + url + ")"
        );
    }

    private NewsItem news(String id, String title, String sourceType) {
        return NewsItem.builder()
                .id(id)
                .title(title)
                .summary(title + " 的摘要内容")
                .detailExcerpt(title + " 的详情摘录")
                .url("https://example.com/" + id)
                .source("测试源")
                .sourceType(sourceType)
                .category("current_affairs")
                .trustLevel("aggregated")
                .build();
    }
}