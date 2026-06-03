package com.bot.service;

import com.bot.client.PythonMLClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class IntentRouterTest {

    private PythonMLClient mlClient;
    private IntentRouter intentRouter;

    @BeforeEach
    void setUp() {
        mlClient = mock(PythonMLClient.class);
        // Default: L2 returns "default" with 0 confidence → falls back to keyword
        when(mlClient.classifyIntent(anyString()))
                .thenReturn(new PythonMLClient.IntentClassification("default", 0.0));
        intentRouter = new IntentRouter(mlClient);
    }

    @Test
    void routesBlankContentAsEmptyIntent() {
        IntentRouter.ChatIntent intent = intentRouter.route("   ");

        assertEquals(IntentRouter.ChatIntentType.EMPTY, intent.type());
        assertNull(intent.requestedCity());
        assertEquals(1.0f, intent.confidence());
    }

    @Test
    void routesClearCommand() {
        IntentRouter.ChatIntent intent = intentRouter.route("清空上下文");

        assertEquals(IntentRouter.ChatIntentType.CLEAR, intent.type());
        assertNull(intent.requestedCity());
        assertEquals(1.0f, intent.confidence());
    }

    @Test
    void routesWeatherQueryAndExtractsConfiguredCityCandidate() {
        IntentRouter.ChatIntent intent = intentRouter.route("广州今天天气怎么样");

        assertEquals(IntentRouter.ChatIntentType.WEATHER, intent.type());
        assertEquals("广州", intent.requestedCity());
        assertEquals(0.9f, intent.confidence());
    }

    @Test
    void routesOverviewNewsQuery() {
        // "最近有什么新闻" goes to L2 (default → keyword fallback → OVERVIEW)
        IntentRouter.ChatIntent intent = intentRouter.route("最近有什么新闻");

        assertEquals(IntentRouter.ChatIntentType.OVERVIEW, intent.type());
    }

    @Test
    void routesHotNewsCommandAsOverview() {
        IntentRouter.ChatIntent intent = intentRouter.route("今日热点");

        assertEquals(IntentRouter.ChatIntentType.OVERVIEW, intent.type());
        assertEquals(1.0f, intent.confidence());
    }

    @Test
    void routesHelpAlias() {
        IntentRouter.ChatIntent intent = intentRouter.route("/help");

        assertEquals(IntentRouter.ChatIntentType.HELP, intent.type());
        assertEquals(1.0f, intent.confidence());
    }

    // ── L2 embedding classification tests ──

    @Test
    void routesCasualChatViaL2() {
        when(mlClient.classifyIntent("早上好"))
                .thenReturn(new PythonMLClient.IntentClassification("casual_chat", 0.88));

        IntentRouter.ChatIntent intent = intentRouter.route("早上好");

        assertEquals(IntentRouter.ChatIntentType.CASUAL_CHAT, intent.type());
        assertTrue(intent.confidence() >= 0.75f);
    }

    @Test
    void routesDetailFollowUpViaL2() {
        when(mlClient.classifyIntent("分析一下"))
                .thenReturn(new PythonMLClient.IntentClassification("detail_followup", 0.82));

        IntentRouter.ChatIntent intent = intentRouter.route("分析一下");

        assertEquals(IntentRouter.ChatIntentType.DETAIL_FOLLOWUP, intent.type());
    }

    @Test
    void routesFollowUpViaL2() {
        when(mlClient.classifyIntent("后面怎么样"))
                .thenReturn(new PythonMLClient.IntentClassification("follow_up", 0.79));

        IntentRouter.ChatIntent intent = intentRouter.route("后面怎么样");

        assertEquals(IntentRouter.ChatIntentType.FOLLOW_UP, intent.type());
    }

    @Test
    void routesNewsOverviewViaL2() {
        when(mlClient.classifyIntent("有啥新鲜事"))
                .thenReturn(new PythonMLClient.IntentClassification("news_overview", 0.85));

        IntentRouter.ChatIntent intent = intentRouter.route("有啥新鲜事");

        assertEquals(IntentRouter.ChatIntentType.OVERVIEW, intent.type());
    }

    @Test
    void fallsBackToKeywordWhenL2ReturnsLowConfidence() {
        // L2 returns "casual_chat" with low confidence → fallback to keyword logic
        when(mlClient.classifyIntent("最近有什么新闻"))
                .thenReturn(new PythonMLClient.IntentClassification("casual_chat", 0.55));

        IntentRouter.ChatIntent intent = intentRouter.route("最近有什么新闻");

        // Keyword fallback should still detect "新闻" + "最近" → OVERVIEW
        assertEquals(IntentRouter.ChatIntentType.OVERVIEW, intent.type());
    }

    // ── resolveNewsIntent tests (these use keyword-only path, L2 not involved) ──

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
