package com.bot.service;

import com.bot.client.PythonMLClient;
import com.bot.model.NewsItem;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class NewsSnapshotManagerTest {

    private PythonMLClient mlClient;

    @BeforeEach
    void setUp() {
        mlClient = mock(PythonMLClient.class);
        when(mlClient.classifyIntent(anyString()))
                .thenReturn(new PythonMLClient.IntentClassification("default", 0.0));
    }

    @Test
    void overviewSnapshotBalancesContentBuckets() {
        NewsService newsService = mock(NewsService.class);
        when(newsService.fetchAll()).thenReturn(List.of(
                overviewItem("ca-1", "国际时事 1", "新华社新闻_新华网", "外交会议与社会新闻摘要", "news"),
                overviewItem("tech-1", "SpaceX 火箭技术 1", "TechCrunch RSS", "科技与航天进展", "news"),
                overviewItem("hum-1", "各国国旗冷知识 1", "知乎日报", "文化与地理知识帖", "hotlist"),
                overviewItem("ent-1", "电影票房观察 1", "测试娱乐源", "明星电影娱乐资讯", "news"),
                overviewItem("ca-2", "国际时事 2", "新华社新闻_新华网", "外交会议与社会新闻摘要", "news"),
                overviewItem("tech-2", "SpaceX 火箭技术 2", "TechCrunch RSS", "科技与航天进展", "news"),
                overviewItem("hum-2", "各国国旗冷知识 2", "知乎日报", "文化与地理知识帖", "hotlist"),
                overviewItem("ent-2", "电影票房观察 2", "测试娱乐源", "明星电影娱乐资讯", "news")
        ));

        NewsSnapshotManager manager = manager(newsService);

        NewsSnapshotDecision decision = manager.resolveOverviewSnapshot("今日热点");

        assertTrue(decision.hasSnapshot());
        assertEquals(8, decision.items().size());
        assertEquals("current_affairs", decision.items().get(0).getCategory());
        assertEquals("tech_science", decision.items().get(1).getCategory());
        assertEquals("humanities_nature", decision.items().get(2).getCategory());
        assertEquals("entertainment", decision.items().get(3).getCategory());
    }

    @Test
    void freshSnapshotFiltersRequestedCategory() {
        NewsService newsService = mock(NewsService.class);
        when(newsService.fetchLayered(eq("news"), eq("tech"))).thenReturn(List.of(
                overviewItem("tech-1", "AI 芯片新闻", "TechCrunch RSS", "科技与芯片更新", "news"),
                overviewItem("ent-1", "电影票房观察", "测试娱乐源", "明星电影娱乐资讯", "news")
        ));

        NewsSnapshotManager manager = manager(newsService);

        NewsSnapshotDecision decision = manager.resolveNewsSnapshot("最近有什么科技新闻", null);

        assertTrue(decision.hasSnapshot());
        assertEquals("news", decision.requestedLayer());
        assertEquals("tech_science", decision.requestedCategory());
        assertEquals(1, decision.items().size());
        assertEquals("AI 芯片新闻", decision.items().get(0).getTitle());
        assertEquals("tech_science", decision.items().get(0).getCategory());
        assertNull(decision.items().get(0).getDetailContent());
        assertEquals("科技与芯片更新", decision.items().get(0).resolvedDetailExcerpt());
    }

    @Test
    void nonNewsQueryReturnsNone() {
        NewsSnapshotManager manager = manager(mock(NewsService.class));

        NewsSnapshotDecision decision = manager.resolveNewsSnapshot("你好", null);

        assertFalse(decision.hasSnapshot());
        assertFalse(decision.requiresAnyNewsContext());
    }

    private NewsSnapshotManager manager(NewsService newsService) {
        return new NewsSnapshotManager(
                newsService,
                new IntentRouter(mlClient),
                12,
                items -> items == null ? List.of() : new ArrayList<>(items),
            item -> item == null ? "" : item.resolvedDetailExcerpt()
        );
    }

    private NewsItem overviewItem(String id, String title, String source, String summary, String sourceType) {
        return NewsItem.builder()
                .id(id)
                .title(title)
                .source(source)
                .summary(summary)
                .detailExcerpt(summary)
                .url("https://example.com/" + id)
                .sourceType(sourceType)
                .category("general")
                .trustLevel("aggregated")
                .build();
    }
}