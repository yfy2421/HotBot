package com.bot.service;

import com.bot.client.PythonMLClient;
import com.bot.model.NewsItem;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class DetailSelectorTest {

    @Test
    void selectsItemByIndex() {
        DetailSelector selector = selector(mock(PythonMLClient.class));

        DetailSelector.DetailSelection selection = selector.select("1", List.of(
                news("n1", "测试热点甲", "hotlist"),
                news("n2", "测试热点乙", "hotlist")
        ), Map.of());

        assertTrue(selection.matched());
        assertEquals("测试热点甲", selection.item().getTitle());
    }

        @Test
        void selectsItemByChineseOrdinal() {
                DetailSelector selector = selector(mock(PythonMLClient.class));

                DetailSelector.DetailSelection selection = selector.select("第二条", List.of(
                                news("n1", "测试热点甲", "hotlist"),
                                news("n2", "测试热点乙", "hotlist")
                ), Map.of());

                assertTrue(selection.matched());
                assertEquals("测试热点乙", selection.item().getTitle());
        }

        @Test
        void outOfRangeIndexDoesNotMatchAnyItem() {
                DetailSelector selector = selector(mock(PythonMLClient.class));

                DetailSelector.DetailSelection selection = selector.select("第3条", List.of(
                                news("n1", "测试热点甲", "hotlist"),
                                news("n2", "测试热点乙", "hotlist")
                ), Map.of());

                assertFalse(selection.matched());
                assertNull(selection.clarificationReply());
        }

    @Test
    void fuzzyTitleMatchSelectsSingleItem() {
        DetailSelector selector = selector(mock(PythonMLClient.class));

        DetailSelector.DetailSelection selection = selector.select("多流LLM", List.of(
                news("n1", "多流LLM 论文", "hotlist"),
                news("n2", "测试热点乙", "hotlist")
        ), Map.of());

        assertTrue(selection.matched());
        assertEquals("多流LLM 论文", selection.item().getTitle());
    }

        @Test
        void stripsSharedNoiseWordsBeforeMatchingTitle() {
                DetailSelector selector = selector(mock(PythonMLClient.class));

                DetailSelector.DetailSelection selection = selector.select("这个热点 多流LLM 看看", List.of(
                                news("n1", "多流LLM 论文", "hotlist"),
                                news("n2", "测试热点乙", "hotlist")
                ), Map.of());

                assertTrue(selection.matched());
                assertEquals("多流LLM 论文", selection.item().getTitle());
        }

        @Test
        void translatedTitleMatchSelectsSingleItem() {
                DetailSelector selector = selector(mock(PythonMLClient.class));

                DetailSelector.DetailSelection selection = selector.select("Waymo 高速服务", List.of(
                                news("n1", "Waymo pauses highway service", "news"),
                                news("n2", "测试热点乙", "news")
                ), Map.of("Waymo pauses highway service", "Waymo 高速服务"));

                assertTrue(selection.matched());
                assertEquals("Waymo pauses highway service", selection.item().getTitle());
        }

        @Test
        void ambiguousFuzzyMatchRequestsMoreSpecificTitleWhenSemanticCannotResolve() {
                DetailSelector selector = selector(mock(PythonMLClient.class));

                DetailSelector.DetailSelection selection = selector.select("Waymo", List.of(
                                news("n1", "Waymo pauses highway service", "news"),
                                news("n2", "Waymo expands airport pickup", "news")
                ), Map.of());

                assertFalse(selection.matched());
                assertEquals("匹配到多条内容，请直接回复 1-2 或更具体一点的标题。", selection.clarificationReply());
        }

    @Test
    void semanticRankingSelectsBestCandidate() {
        PythonMLClient mlClient = mock(PythonMLClient.class);
        DetailSelector selector = selector(mlClient);

        when(mlClient.rankCandidates(eq("waymo在无人驾驶出租车"), anyList(), eq(2))).thenReturn(List.of(
                new PythonMLClient.SemanticRankMatch(1, "candidate-2", 0.88d, 0.71d, 0.94d),
                new PythonMLClient.SemanticRankMatch(0, "candidate-1", 0.73d, 0.69d, 0.75d)
        ));

        DetailSelector.DetailSelection selection = selector.select("Waymo在无人驾驶出租车那条", List.of(
                news("n1", "Waymo在无人驾驶出租车持续驶入洪水区域之后，将暂停扩展至四个城市", "news"),
                news("n2", "Waymo在无人驾驶出租车在施工区域行驶困难后暂停高速公路服务", "news")
        ), Map.of());

        assertTrue(selection.matched());
        assertEquals("Waymo在无人驾驶出租车在施工区域行驶困难后暂停高速公路服务", selection.item().getTitle());
    }

    @Test
    void semanticRankingReturnsClarificationWhenScoresAreClose() {
        PythonMLClient mlClient = mock(PythonMLClient.class);
        DetailSelector selector = selector(mlClient);

        when(mlClient.rankCandidates(eq("waymo"), anyList(), eq(2))).thenReturn(List.of(
                new PythonMLClient.SemanticRankMatch(1, "candidate-2", 0.69d, 0.70d, 0.68d),
                new PythonMLClient.SemanticRankMatch(0, "candidate-1", 0.66d, 0.68d, 0.65d)
        ));

        DetailSelector.DetailSelection selection = selector.select("Waymo那条", List.of(
                news("n1", "Waymo在无人驾驶出租车持续驶入洪水区域之后，将暂停扩展至四个城市", "news"),
                news("n2", "Waymo在无人驾驶出租车在施工区域行驶困难后暂停高速公路服务", "news")
        ), Map.of());

        assertFalse(selection.matched());
        assertTrue(selection.clarificationReply().contains("我更可能指这几条"));
        assertTrue(selection.clarificationReply().contains("1. Waymo在无人驾驶出租车持续驶入洪水区域之后，将暂停扩展至四个城市"));
        assertTrue(selection.clarificationReply().contains("2. Waymo在无人驾驶出租车在施工区域行驶困难后暂停高速公路服务"));
    }

        @Test
        void semanticRankingBelowClarifyThresholdReturnsNone() {
                PythonMLClient mlClient = mock(PythonMLClient.class);
                DetailSelector selector = selector(mlClient);

                when(mlClient.rankCandidates(eq("routefailure"), anyList(), eq(2))).thenReturn(List.of(
                                new PythonMLClient.SemanticRankMatch(1, "candidate-2", 0.55d, 0.54d, 0.56d),
                                new PythonMLClient.SemanticRankMatch(0, "candidate-1", 0.52d, 0.51d, 0.53d)
                ));

                DetailSelector.DetailSelection selection = selector.select("route failure", List.of(
                                news("n1", "Waymo在无人驾驶出租车持续驶入洪水区域之后，将暂停扩展至四个城市", "news"),
                                news("n2", "Waymo在无人驾驶出租车在施工区域行驶困难后暂停高速公路服务", "news")
                ), Map.of());

                assertFalse(selection.matched());
                assertNull(selection.clarificationReply());
        }

    private DetailSelector selector(PythonMLClient mlClient) {
        return new DetailSelector(
                mlClient,
                12,
                                item -> item == null ? "" : item.resolvedDetailExcerpt()
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