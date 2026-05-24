package com.bot.service;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class IntentRouterTest {

    private final IntentRouter intentRouter = new IntentRouter();

    @Test
    void routesBlankContentAsEmptyIntent() {
        IntentRouter.ChatIntent intent = intentRouter.route("   ");

        assertEquals(IntentRouter.ChatIntentType.EMPTY, intent.type());
        assertNull(intent.requestedCity());
    }

    @Test
    void routesClearCommand() {
        IntentRouter.ChatIntent intent = intentRouter.route("清空上下文");

        assertEquals(IntentRouter.ChatIntentType.CLEAR, intent.type());
        assertNull(intent.requestedCity());
    }

    @Test
    void routesWeatherQueryAndExtractsConfiguredCityCandidate() {
        IntentRouter.ChatIntent intent = intentRouter.route("广州今天天气怎么样");

        assertEquals(IntentRouter.ChatIntentType.WEATHER, intent.type());
        assertEquals("广州", intent.requestedCity());
    }

    @Test
    void routesOverviewNewsQuery() {
        IntentRouter.ChatIntent intent = intentRouter.route("最近有什么新闻");

        assertEquals(IntentRouter.ChatIntentType.OVERVIEW, intent.type());
    }

    @Test
    void routesHotNewsCommandAsOverview() {
        IntentRouter.ChatIntent intent = intentRouter.route("今日热点");

        assertEquals(IntentRouter.ChatIntentType.OVERVIEW, intent.type());
    }

    @Test
    void routesHelpAlias() {
        IntentRouter.ChatIntent intent = intentRouter.route("/help");

        assertEquals(IntentRouter.ChatIntentType.HELP, intent.type());
    }

    @Test
    void resolvesAnalysisNewsIntentWithCategory() {
        IntentRouter.NewsIntent intent = intentRouter.resolveNewsIntent("分析 AI 芯片热点", null);

        assertTrue(intent.analysisRequested());
        assertFalse(intent.followUpRequested());
        assertFalse(intent.overviewQuery());
        assertTrue(intent.requiresFreshNews());
        assertEquals("hotlist", intent.requestedLayer());
        assertEquals("tech_science", intent.requestedCategory());
    }

    @Test
    void analysisIntentDoesNotCountAsOverviewEvenWhenItMentionsNews() {
        IntentRouter.NewsIntent intent = intentRouter.resolveNewsIntent("分析今天的热点新闻", null);

        assertTrue(intent.analysisRequested());
        assertFalse(intent.followUpRequested());
        assertFalse(intent.overviewQuery());
        assertTrue(intent.requiresFreshNews());
        assertEquals("hotlist", intent.requestedLayer());
        assertEquals("all", intent.requestedCategory());
    }

    @Test
    void resolvesFollowUpNewsIntent() {
        IntentRouter.NewsIntent intent = intentRouter.resolveNewsIntent("这些新闻有没有后续", null);

        assertFalse(intent.analysisRequested());
        assertTrue(intent.followUpRequested());
        assertFalse(intent.overviewQuery());
        assertTrue(intent.requiresFreshNews());
        assertEquals("news", intent.requestedLayer());
        assertEquals("all", intent.requestedCategory());
    }

    @Test
    void resolvesHumanitiesCategoryFromSharedKeywordGroup() {
        IntentRouter.NewsIntent intent = intentRouter.resolveNewsIntent("看看国旗冷知识热点", null);

        assertFalse(intent.analysisRequested());
        assertFalse(intent.followUpRequested());
        assertTrue(intent.overviewQuery());
        assertTrue(intent.requiresFreshNews());
        assertEquals("hotlist", intent.requestedLayer());
        assertEquals("humanities_nature", intent.requestedCategory());
    }

    @Test
    void resolvesEntertainmentCategoryFromSharedKeywordGroup() {
        IntentRouter.NewsIntent intent = intentRouter.resolveNewsIntent("最近有哪些电影新闻", null);

        assertFalse(intent.analysisRequested());
        assertFalse(intent.followUpRequested());
        assertTrue(intent.overviewQuery());
        assertTrue(intent.requiresFreshNews());
        assertEquals("news", intent.requestedLayer());
        assertEquals("entertainment", intent.requestedCategory());
    }

    @Test
    void resolvesCurrentAffairsCategoryFromSharedKeywordGroup() {
        IntentRouter.NewsIntent intent = intentRouter.resolveNewsIntent("最近有什么财经新闻", null);

        assertFalse(intent.analysisRequested());
        assertFalse(intent.followUpRequested());
        assertTrue(intent.overviewQuery());
        assertTrue(intent.requiresFreshNews());
        assertEquals("news", intent.requestedLayer());
        assertEquals("current_affairs", intent.requestedCategory());
    }

    @Test
    void forcedLayerOverridesInferredRequestedLayer() {
        IntentRouter.NewsIntent intent = intentRouter.resolveNewsIntent("最近有什么新闻", "hotlist");

        assertFalse(intent.analysisRequested());
        assertFalse(intent.followUpRequested());
        assertTrue(intent.overviewQuery());
        assertEquals("hotlist", intent.requestedLayer());
        assertEquals("all", intent.requestedCategory());
    }
}