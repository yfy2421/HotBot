package com.bot.service;

import com.bot.agent.AnalyzeArticleTool;
import com.bot.agent.FetchNewsTool;
import com.bot.agent.ToolRegistry;
import com.bot.agent.ToolsResult;
import com.bot.agent.TrackFollowUpTool;
import com.bot.client.PythonMLClient;
import com.bot.model.AssistantChatRequest;
import com.bot.model.AssistantChatResponse;
import com.bot.model.AssistantMessage;
import com.bot.model.FetchResult;
import com.bot.model.NewsItem;
import com.bot.model.WeatherInfo;
import com.bot.util.NewsDisplayText;
import com.bot.util.NewsTimeUtils;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.time.Duration;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static com.bot.util.TextUtils.defaultText;
import static com.bot.util.TextUtils.hasText;

@Slf4j
@Service
public class AssistantConversationService {

    private static final int MAX_HISTORY_MESSAGES = 12;
    private static final int NEWS_SNAPSHOT_LIMIT = 12;
    private static final int CHAT_FOLLOW_UP_LIMIT = 3;
    private static final int NEWS_SNAPSHOT_RETENTION_MINUTES = 30;
    private static final String NO_NEWS_REPLY = "当前 RSS/聚合 API 没拉到数据，我不输出新闻结论。";
    private static final String NO_WEATHER_REPLY = "当前天气服务未配置或不可用，暂时不能提供实时天气。";
    private static final int DETAIL_EXCERPT_LIMIT = 2500;
    private static final int FULL_BODY_TRANSLATION_CHUNK_LENGTH = 320;

    private final NewsService newsService;
    private final WeatherService weatherService;
    private final TrackingService trackingService;
    private final CommentSourceService commentSourceService;
    private final PythonMLClient mlClient;
    private final WeChatPusher pusher;
    private final NewsCardRenderer newsCardRenderer;
    private final ConversationStateManager conversationStateManager;
    private final IntentRouter intentRouter;
    private final NewsSnapshotManager newsSnapshotManager;
    private final DetailSelector detailSelector;
    private final ReplyRenderer replyRenderer;
    private final ToolRegistry toolRegistry;

    private final Map<String, String> translatedNewsTitles = new ConcurrentHashMap<>();
    private final Map<String, String> translatedNewsSummaries = new ConcurrentHashMap<>();
    private final Map<String, String> translatedNewsBodies = new ConcurrentHashMap<>();

    public AssistantConversationService(NewsService newsService,
                                        WeatherService weatherService,
                                        TrackingService trackingService,
                                        CommentSourceService commentSourceService,
                                        PythonMLClient mlClient,
                                        WeChatPusher pusher,
                                        NewsCardRenderer newsCardRenderer,
                                        AssistantConversationStateStore stateStore) {
        this.newsService = newsService;
        this.weatherService = weatherService;
        this.trackingService = trackingService;
        this.commentSourceService = commentSourceService;
        this.mlClient = mlClient;
        this.pusher = pusher;
        this.newsCardRenderer = newsCardRenderer;
        this.conversationStateManager = new ConversationStateManager(
            stateStore,
            MAX_HISTORY_MESSAGES,
            Duration.ofMinutes(NEWS_SNAPSHOT_RETENTION_MINUTES),
            this::snapshotItems
        );
        this.intentRouter = new IntentRouter(mlClient);
        this.newsSnapshotManager = new NewsSnapshotManager(
            newsService,
            intentRouter,
            NEWS_SNAPSHOT_LIMIT,
            this::snapshotItems,
            this::resolveDetailExcerpt
        );
        this.detailSelector = new DetailSelector(
                mlClient,
                NEWS_SNAPSHOT_LIMIT,
            this::resolveDetailExcerpt
        );
        this.replyRenderer = new ReplyRenderer(
            newsCardRenderer,
            NEWS_SNAPSHOT_LIMIT,
            CHAT_FOLLOW_UP_LIMIT,
            this::buildNewsDisplayTimeText,
            this::resolveDisplayTitle,
            this::formatMarkdownLink
        );
        this.toolRegistry = new ToolRegistry();
        this.toolRegistry.register(new FetchNewsTool(newsService));
        this.toolRegistry.register(new AnalyzeArticleTool(mlClient));
        this.toolRegistry.register(new TrackFollowUpTool(trackingService, newsService));
    }

    public AssistantChatResponse chat(AssistantChatRequest request) {
        String scene = normalizeScene(request.getScene());
        String targetId = resolveTargetId(request, scene);
        String conversationId = resolveConversationId(request, scene, targetId);
        String content = normalizeText(request.getContent());
        IntentRouter.ChatIntent intent = intentRouter.route(content);

        ReplyPlan replyPlan;
        if (intent.type() == IntentRouter.ChatIntentType.EMPTY) {
            replyPlan = ReplyPlan.of("先发具体问题，比如：今日热点、分析 AI 芯片、清空上下文。");
        } else if (intent.type() == IntentRouter.ChatIntentType.CLEAR) {
            conversationStateManager.clear(conversationId);
            replyPlan = ReplyPlan.of("上下文已清空。");
        } else if (intent.type() == IntentRouter.ChatIntentType.HELP) {
            replyPlan = ReplyPlan.of(helpText());
        } else if (intent.type() == IntentRouter.ChatIntentType.WEATHER) {
            replyPlan = buildWeatherReply(conversationId, content, intent.requestedCity());
        } else if (intent.type() == IntentRouter.ChatIntentType.CASUAL_CHAT) {
            replyPlan = buildAiReply(conversationId, content);
        } else {
            ReplyPlan detailReply = tryBuildDirectNewsDetailReply(conversationId, content);
            if (detailReply != null) {
                replyPlan = detailReply;
            } else if (intent.type() == IntentRouter.ChatIntentType.OVERVIEW) {
                replyPlan = buildNewsOverviewReply(conversationId, content);
            } else if (intent.confidence() < 0.55f
                    && intent.type() != IntentRouter.ChatIntentType.DEFAULT) {
                String clarification = buildClarification(conversationId, content);
                replyPlan = ReplyPlan.of(clarification);
                remember(conversationId, content, clarification);
            } else {
                replyPlan = buildAiReply(conversationId, content);
            }
        }

        boolean sentToQq = false;
        if (request.isSendReply() && targetId != null && !targetId.isBlank()) {
            sentToQq = sendToQq(scene, targetId, replyPlan.reply(), request.getMsgId());
        }
        return buildResponse(conversationId, scene, targetId, replyPlan, sentToQq);
    }

    public void warmOverviewAssets() {
        NewsSnapshotDecision newsDecision = newsSnapshotManager.resolveOverviewSnapshot("今日新闻");
        if (newsDecision.items().isEmpty()) {
            return;
        }
        Map<String, String> translatedTitles = translateNewsTitles(newsDecision.items());
        Map<String, String> translatedSummaries = translateNewsSummaries(newsDecision.items());
        newsCardRenderer.renderOverviewCard(
                "启动预热",
                "预热新闻总览卡片",
                newsDecision.items(),
                translatedTitles,
                translatedSummaries,
                null
        );
    }

    private AssistantChatResponse buildResponse(String conversationId, String scene, String targetId, ReplyPlan replyPlan, boolean sentToQq) {
        return AssistantChatResponse.builder()
                .conversationId(conversationId)
                .scene(scene)
                .targetId(targetId)
                .reply(replyPlan.reply())
                .mediaType(replyPlan.mediaType())
                .mediaPath(replyPlan.mediaPath())
                .mediaCaption(replyPlan.mediaCaption())
                .sentToQq(sentToQq)
                .newsSnapshot(replyPlan.newsSnapshot())
                .build();
    }

    private ReplyPlan buildAiReply(String conversationId, String content) {
        List<AssistantMessage> history = conversationStateManager.snapshot(conversationId);
        NewsSnapshotDecision newsDecision = resolveNewsSnapshot(conversationId, content, null);
        if (newsDecision.requiresAnyNewsContext() && newsDecision.items().isEmpty()) {
            String reply = NO_NEWS_REPLY;
            remember(conversationId, content, reply);
            return ReplyPlan.of(reply);
        }

        Map<String, String> translatedTitles = translateNewsTitles(newsDecision.items());
        ScopedNewsDecision scopedDecision = scopeNewsDecision(conversationId, content, newsDecision, translatedTitles);
        if (scopedDecision.immediateReply() != null) {
            remember(conversationId, content, scopedDecision.immediateReply());
            return ReplyPlan.of(scopedDecision.immediateReply());
        }

        newsDecision = scopedDecision.newsDecision();
        newsDecision = hydrateScopedNewsDecision(newsDecision);
        translatedTitles = translateNewsTitles(newsDecision.items());
        enrichChatFollowUps(newsDecision);
        String systemPrompt = buildSystemPrompt(content, newsDecision, translatedTitles);
        String aiReply = toolLoop(content, history, systemPrompt);
        String reply = newsDecision.hasSnapshot()
            ? buildNewsGroundedReply(newsDecision, translatedTitles, aiReply)
                : aiReply;
        remember(conversationId, content, reply);
        if (newsDecision.hasSnapshot()) {
            if (newsDecision.reusesPreviousSnapshot()) {
                conversationStateManager.focusSingleNewsItem(conversationId, newsDecision.items());
            } else {
                rememberNewsSnapshot(conversationId, newsDecision);
            }
            logNewsSnapshot(conversationId, content, newsDecision.items());
        }
        return new ReplyPlan(reply, newsDecision.items(), null, null, null);
    }

    /**
     * Tool-calling loop: lets the LLM invoke registered tools up to 3 rounds.
     * On each round the LLM can respond with [TOOL:name] {...} or a final reply.
     */
    private String toolLoop(String userMessage, List<AssistantMessage> history, String systemPrompt) {
        if (toolRegistry.isEmpty()) {
            return mlClient.chat(userMessage, history, systemPrompt);
        }

        String toolsSection = toolRegistry.describeForLLM();
        String augmentedSystem = systemPrompt + toolsSection;

        List<AssistantMessage> dynamicHistory = new ArrayList<>(history);
        String currentMessage = userMessage;

        for (int round = 0; round < 3; round++) {
            String response = mlClient.chat(currentMessage, dynamicHistory, augmentedSystem);
            if (response == null) {
                return "（AI 服务暂时不可用）";
            }

            // Check for tool call pattern: [TOOL:name] {...}
            int toolStart = response.indexOf("[TOOL:");
            if (toolStart < 0) {
                return response; // final reply — no tool call
            }

            int toolEnd = response.indexOf("]", toolStart);
            if (toolEnd < 0) {
                return response;
            }

            String toolName = response.substring(toolStart + 6, toolEnd).trim();
            var tool = toolRegistry.get(toolName);
            if (tool == null) {
                // Unknown tool — append note and continue
                dynamicHistory.add(AssistantMessage.builder()
                        .role("user").content(currentMessage).build());
                dynamicHistory.add(AssistantMessage.builder()
                        .role("assistant").content(response).build());
                currentMessage = "工具 " + toolName + " 不可用，请直接回复用户。";
                continue;
            }

            // Parse args JSON
            int argsStart = response.indexOf("{", toolEnd);
            int argsEnd = response.lastIndexOf("}") + 1;
            Map<String, Object> args = Map.of();
            if (argsStart >= 0 && argsEnd > argsStart) {
                try {
                    args = parseToolArgs(response.substring(argsStart, argsEnd));
                } catch (Exception e) {
                    log.debug("Failed to parse tool args: {}", e.getMessage());
                }
            }

            // Execute tool
            ToolsResult result = tool.execute(args);
            String toolOutput = "[" + toolName + " 执行结果]\n" + result.content();

            // Append to conversation
            dynamicHistory.add(AssistantMessage.builder()
                    .role("user").content(currentMessage).build());
            dynamicHistory.add(AssistantMessage.builder()
                    .role("assistant").content("[TOOL:" + toolName + "]").build());
            dynamicHistory.add(AssistantMessage.builder()
                    .role("user").content(toolOutput).build());
            currentMessage = "请根据以上工具执行结果，直接回复用户的原始问题。";
        }

        // Max rounds reached — ask LLM to wrap up
        return mlClient.chat("请根据以上所有工具结果，给用户一个最终回复。", dynamicHistory, augmentedSystem);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> parseToolArgs(String json) {
        try {
            var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
            return mapper.readValue(json, Map.class);
        } catch (Exception e) {
            log.debug("Failed to parse tool args JSON: {}", e.getMessage());
            return Map.of();
        }
    }

    private ReplyPlan buildWeatherReply(String conversationId, String content, String requestedCity) {
        if (!weatherService.isConfigured()) {
            remember(conversationId, content, NO_WEATHER_REPLY);
            return ReplyPlan.of(NO_WEATHER_REPLY);
        }

        String configuredCity = weatherService.getConfiguredCityName();
        if (requestedCity != null && !requestedCity.equals(configuredCity)) {
            String reply = "当前聊天天气只支持已配置城市“" + configuredCity + "”，暂不支持切换到“" + requestedCity + "”。";
            remember(conversationId, content, reply);
            return ReplyPlan.of(reply);
        }

        try {
            WeatherInfo weather = weatherService.fetchTodayStrict();
            String reply = formatWeatherReply(weather);
            remember(conversationId, content, reply);
            logWeatherSnapshot(conversationId, content, weather);
            return ReplyPlan.of(reply);
        } catch (Exception e) {
            log.warn("Weather chat failed: {}", e.getMessage());
            remember(conversationId, content, NO_WEATHER_REPLY);
            return ReplyPlan.of(NO_WEATHER_REPLY);
        }
    }

    private String buildSystemPrompt(String content, NewsSnapshotDecision newsDecision, Map<String, String> translatedTitles) {
        var prompt = new StringBuilder("""
                你是“热点追踪分析 bot”的助手。
                回答要求：
                1. 先给结论，再给依据。
                2. 语气直接，别写成公文。
                3. 用户问热点、新闻、趋势时，优先回答：发生了什么、为什么重要、接下来怎么看。
                4. 信息不够就明确说，不要编造。
                5. 控制篇幅，默认 3 到 6 句话。
                6. 用户发问候、闲聊、情绪化短句时，简短自然地回应，不做新闻分析，不追溯之前对话中的错误或道歉。
                """);

        if (newsDecision.requiresAnyNewsContext()) {
            prompt.append("""

                    新闻回答限制：
                    1. 只允许基于系统提供的 RSS/聚合 API 新闻快照回答近期新闻相关内容。
                    2. 如果系统没有提供新闻快照，就明确回复“当前 RSS/聚合 API 没拉到数据，我不输出新闻结论。”。
                    3. 不得把快照之外的事件说成最新新闻。
                    4. 只把系统提供的新闻快照当依据，不要编造额外新闻事实。
                    5. 如果当前问题是在追问分析，不要把完整快照逐条重写一遍，只引用与你结论直接相关的标题和链接。
                    6. 对用户可见的新闻/热点内容使用 Markdown。
                    7. 如果标题是英文，先翻译成简体中文再展示；必要时可在括号里保留英文原文。
                    8. 引用链接时使用 Markdown 链接格式 [标题](URL)，不要输出裸链接。
                    """);
            if (newsDecision.reusesPreviousSnapshot()) {
                prompt.append("\n当前问题是在上一轮新闻快照基础上的追问，直接输出判断，不要重复整块快照。\n");
            }
            if (newsDecision.followUpRequested()) {
                prompt.append("\n如果系统提供了近 7 天后续追踪线索，优先说明哪些条目命中了相似报道、这些线索说明了什么。\n");
            }
                    if (newsDecision.analysisRequested() || newsDecision.followUpRequested()) {
                    prompt.append("""

                        分析/解读输出格式：
                        1. 直接输出 1 到 3 个短段落，不要重复整块新闻快照，也不要把来源、分类、可信度逐项重抄一遍。
                        2. 优先覆盖：一句判断、为什么值得看、别高估什么、接下来观察什么。
                        3. 如果证据还早期、样本有限或只是单条快讯，要明确写出来，不要把可能性说成确定性。
                        4. 如果当前问题指向单条热点，就只围绕那一条展开，不要重新总览整组热点。
                        """);

                    AnalysisTemplate template = resolveAnalysisTemplate(newsDecision.items());
                    switch (template) {
                        case PAPER -> prompt.append("""

                            当前热点更像论文/研究类事件。
                            重点回答：这项研究到底提出了什么、为什么值得关注、现阶段证据强度够不够、离真实产品落地还有多远。
                            """);
                        case PRODUCT -> prompt.append("""

                            当前热点更像产品/发布类事件。
                            重点回答：到底上线了什么、对谁有用、短期真实影响是什么、别高估哪些宣传口径。
                            """);
                        case POLICY -> prompt.append("""

                            当前热点更像政策/监管类事件。
                            重点回答：规则具体变了什么、谁会受影响、执行层面还有哪些不确定性、下一步看哪些配套动作。
                            """);
                        case COMMUNITY -> prompt.append("""

                            当前条目更像社区热榜里的知识帖/内容帖，不是突发新闻事件。
                            重点回答：这篇内容主要讲了什么、最值得看的 1 到 2 个点、哪些部分只是作者或社区观点、值不值得收藏深挖。
                            如果没有明确时效性，不要硬写“后续观察什么”或“短期影响”。
                            """);
                        case GENERAL -> {
                        }
                    }
                    }
        }

        if (newsDecision.requiresAnyNewsContext()) {
            prompt.append("\n本次新闻查询层级：")
                    .append(NewsDisplayText.displaySourceType(newsDecision.requestedLayer()))
                    .append("；分类：")
                    .append(NewsDisplayText.displayCategory(newsDecision.requestedCategory()))
                    .append("。\n");
        }

        String newsContext = buildNewsContext(newsDecision.items(), translatedTitles);
        if (!newsContext.isBlank()) {
            prompt.append("\n以下是系统刚抓取到的热点快照（只能基于这些内容回答当前新闻结论）：\n")
                    .append(newsContext);
        }
        return prompt.toString();
    }

    private String buildNewsContext(List<NewsItem> newsItems, Map<String, String> translatedTitles) {
        if (newsItems == null || newsItems.isEmpty()) {
            return "";
        }
        var sb = new StringBuilder();
        for (int i = 0; i < newsItems.size(); i++) {
            NewsItem item = newsItems.get(i);
            String displayTitle = resolveDisplayTitle(item, translatedTitles);
            sb.append(i + 1)
                    .append(". 标题: ")
                    .append(displayTitle)
                    .append("\n   来源: ")
                    .append(item.getSource())
                    .append("\n   来源层级: ")
                    .append(NewsDisplayText.displaySourceType(item.getSourceType()))
                    .append("\n   分类: ")
                    .append(NewsDisplayText.displayCategory(item.getCategory()))
                    .append("\n   可信度: ")
                    .append(NewsDisplayText.displayTrustLevel(item.getTrustLevel()))
                    .append("\n   链接: ")
                    .append(defaultText(item.getUrl(), "无"));
            NewsDisplayTime displayTime = resolveNewsDisplayTime(item);
            if (displayTime != null) {
                sb.append("\n   ")
                        .append(displayTime.label())
                        .append(": ")
                        .append(displayTime.value());
            }
            if (isTranslatedTitle(item, translatedTitles)) {
                sb.append("\n   原文标题: ").append(item.getTitle());
            }
            String summaryPreview = item.summaryPreviewText();
            if (hasText(summaryPreview)) {
                sb.append("\n   摘要: ").append(limit(summaryPreview, 100));
            }
            String detailExcerpt = resolveDetailExcerpt(item);
            if (hasText(detailExcerpt)) {
                sb.append("\n   详情摘录: ").append(limit(detailExcerpt, 180));
            }
            if (hasText(item.getFollowUpTag())) {
                sb.append("\n   近7天跟进线索: ").append(item.getFollowUpTag());
            }
            sb.append("\n");
        }
        return sb.toString();
    }

    private NewsSnapshotDecision resolveNewsSnapshot(String conversationId, String content, String forcedLayer) {
        String lower = content.toLowerCase();
        ConversationStateManager.NewsSnapshotState previousSnapshot = conversationStateManager.reusableNewsSnapshot(conversationId);
        boolean reusePreviousSnapshot = forcedLayer == null && shouldReusePreviousNewsSnapshot(lower, previousSnapshot);
        if (reusePreviousSnapshot) {
            IntentRouter.NewsIntent newsIntent = intentRouter.resolveNewsIntent(content, forcedLayer);
            return new NewsSnapshotDecision(false, true, true, newsIntent.followUpRequested(), newsIntent.analysisRequested(),
                    previousSnapshot.requestedLayer(),
                    previousSnapshot.requestedCategory(),
                    snapshotItems(previousSnapshot.items()));
        }
        return newsSnapshotManager.resolveNewsSnapshot(content, forcedLayer);
    }

    private ReplyPlan buildNewsOverviewReply(String conversationId, String content) {
        NewsSnapshotDecision newsDecision = newsSnapshotManager.resolveOverviewSnapshot(content);
        if (newsDecision.items().isEmpty()) {
            remember(conversationId, content, NO_NEWS_REPLY);
            return ReplyPlan.of(NO_NEWS_REPLY);
        }

        Map<String, String> translatedTitles = translateNewsTitles(newsDecision.items());
        Map<String, String> translatedSummaries = translateNewsSummaries(newsDecision.items());
        rememberNewsSnapshot(conversationId, newsDecision);
        logNewsSnapshot(conversationId, content, newsDecision.items());

        if (newsDecision.items().size() == 1) {
            NewsItem item = newsDecision.items().get(0);
            hydrateDetailPresentation(item);
            ReplyPlan replyPlan = replyRenderer.buildDetailSnapshotPlan(List.of(item), item, translatedTitles, translatedSummaries);
            remember(conversationId, content, replyPlan.reply());
            return replyPlan;
        }

        ReplyPlan replyPlan = replyRenderer.buildOverviewReplyPlan(
            content,
            newsDecision.requestedCategory(),
            newsDecision.items(),
            translatedTitles,
            translatedSummaries
        );
        remember(conversationId, content, replyPlan.reply());
        return replyPlan;
    }

    private ReplyPlan tryBuildDirectNewsDetailReply(String conversationId, String content) {
        IntentRouter.NewsIntent newsIntent = intentRouter.resolveNewsIntent(content, null);
        if (newsIntent.analysisRequested() || newsIntent.followUpRequested()) {
            return null;
        }

        ConversationStateManager.NewsSnapshotState previousSnapshot = conversationStateManager.reusableNewsSnapshot(conversationId);
        if (!hasReusableNewsSnapshot(previousSnapshot)) {
            return null;
        }

        // Focused-item follow-up queries: "原文呢", "正文", "全文" etc.
        if (IntentKeywords.isFocusedItemReference(content)) {
            NewsItem focusedItem = resolveFocusedNewsItem(previousSnapshot, previousSnapshot.items());
            if (focusedItem != null) {
                hydrateDetailPresentation(focusedItem);
                Map<String, String> translatedTitles = translateNewsTitles(List.of(focusedItem));
                Map<String, String> translatedSummaries = translateNewsSummaries(List.of(focusedItem));
                ReplyPlan replyPlan = replyRenderer.buildDetailSwitchPlan(
                        List.of(focusedItem),
                        focusedItem,
                        translatedTitles,
                        translatedSummaries
                );
                remember(conversationId, content, replyPlan.reply());
                return replyPlan;
            }
        }

        Map<String, String> translatedTitles = translateNewsTitles(previousSnapshot.items());
        Map<String, String> translatedSummaries = translateNewsSummaries(previousSnapshot.items());
        DetailSelector.DetailSelection detailSelection = detailSelector.select(content, previousSnapshot.items(), translatedTitles);
        if (!detailSelection.matched() && detailSelection.clarificationReply() == null) {
            return null;
        }
        if (detailSelection.clarificationReply() != null) {
            remember(conversationId, content, detailSelection.clarificationReply());
            return ReplyPlan.of(detailSelection.clarificationReply());
        }

        conversationStateManager.focusNewsItem(conversationId, detailSelection.item().getId());
        hydrateDetailPresentation(detailSelection.item());
        ReplyPlan replyPlan = replyRenderer.buildDetailSwitchPlan(
                List.of(detailSelection.item()),
                detailSelection.item(),
                translatedTitles,
                translatedSummaries
        );
        remember(conversationId, content, replyPlan.reply());
        return replyPlan;
    }

    private ScopedNewsDecision scopeNewsDecision(String conversationId, String content, NewsSnapshotDecision newsDecision, Map<String, String> translatedTitles) {
        if (!newsDecision.hasSnapshot()) {
            return ScopedNewsDecision.keep(newsDecision);
        }

        ConversationStateManager.NewsSnapshotState previousSnapshot = conversationStateManager.newsSnapshot(conversationId);
        DetailSelector.DetailSelection explicitSelection = detailSelector.select(content, newsDecision.items(), translatedTitles);
        if (explicitSelection.clarificationReply() != null) {
            return ScopedNewsDecision.reply(explicitSelection.clarificationReply());
        }
        if (explicitSelection.matched()) {
            conversationStateManager.focusNewsItem(conversationId, explicitSelection.item().getId());
            return ScopedNewsDecision.keep(newsDecision.withItems(List.of(explicitSelection.item())));
        }

        if (newsDecision.items().size() == 1) {
            conversationStateManager.focusSingleNewsItem(conversationId, newsDecision.items());
            return ScopedNewsDecision.keep(newsDecision);
        }

        NewsItem focusedItem = resolveFocusedNewsItem(previousSnapshot, newsDecision.items());
        boolean singularReference = isSingularNewsReference(content.toLowerCase());
        if ((newsDecision.analysisRequested() || newsDecision.followUpRequested()) && singularReference) {
            if (focusedItem != null) {
                return ScopedNewsDecision.keep(newsDecision.withItems(List.of(focusedItem)));
            }
            return ScopedNewsDecision.reply(replyRenderer.buildScopedItemClarification(newsDecision.items().size()));
        }

        return ScopedNewsDecision.keep(newsDecision);
    }

    private String buildNewsGroundedReply(NewsSnapshotDecision newsDecision, Map<String, String> translatedTitles, String aiReply) {
        return replyRenderer.buildNewsGroundedReply(
                newsDecision.followUpRequested(),
                newsDecision.items(),
                translatedTitles,
                aiReply
        );
    }

    private NewsSnapshotDecision hydrateScopedNewsDecision(NewsSnapshotDecision newsDecision) {
        if (!newsDecision.hasSnapshot() || newsDecision.items().size() != 1) {
            return newsDecision;
        }
        hydrateDetailPresentation(newsDecision.items().get(0));
        return newsDecision;
    }

    private void hydrateDetailPresentation(NewsItem item) {
        hydrateDetailContent(item);
        hydrateDetailTranslation(item);
        hydrateCommentary(item);
    }

    private void hydrateDetailContent(NewsItem item) {
        if (item == null) {
            return;
        }
        promoteLegacyDetailExcerpt(item);
        if (hasText(item.getFullBody())) {
            if (!hasText(item.getDetailExcerpt())) {
                item.setDetailExcerpt(buildDetailExcerpt(item.getFullBody()));
            }
            return;
        }
        FetchResult<String> detailResult = commentSourceService == null ? null : commentSourceService.fetchDetailContentWithAlert(item);
        if (detailResult == null) {
            return;
        }
        detailResult.alerts().forEach(alert -> log.warn("Assistant detail content alert source={} code={} message={}",
                alert.source(),
                alert.code(),
                alert.message()));
        if (hasText(detailResult.data())) {
            item.setFullBody(normalizeDetailBody(detailResult.data()));
            if (!hasText(item.getDetailExcerpt())) {
                item.setDetailExcerpt(buildDetailExcerpt(item.getFullBody()));
            }
        }
    }

    private void hydrateDetailTranslation(NewsItem item) {
        if (item == null) {
            return;
        }

        String fullBody = resolveFullBody(item);
        if (hasText(fullBody) && needsChineseTranslation(fullBody) && !hasText(item.getTranslatedFullBody())) {
            String translatedFullBody = requestBodyTranslation(fullBody);
            if (hasText(translatedFullBody)) {
                item.setTranslatedFullBody(translatedFullBody);
                if (!hasText(item.getTranslatedDetailExcerpt())) {
                    item.setTranslatedDetailExcerpt(buildDetailExcerpt(translatedFullBody));
                }
            }
        }

        String detailExcerpt = resolveDetailExcerpt(item);
        if (!hasText(item.getTranslatedDetailExcerpt()) && hasText(detailExcerpt) && needsChineseTranslation(detailExcerpt)) {
            String translatedExcerpt = requestBodyTranslation(detailExcerpt);
            if (hasText(translatedExcerpt)) {
                item.setTranslatedDetailExcerpt(translatedExcerpt);
            }
        }
    }

    private void hydrateCommentary(NewsItem item) {
        if (item == null || hasText(item.getCommentary())) {
            return;
        }
        String commentaryBody = hasText(resolveFullBody(item))
                ? limit(resolveFullBody(item), DETAIL_EXCERPT_LIMIT)
                : resolveDetailExcerpt(item);
        String commentaryInput = String.join("\n", List.of(
                defaultText(item.getTitle(), ""),
            defaultText(commentaryBody, item.summaryPreviewText())
        )).trim();
        if (!hasText(commentaryInput)) {
            return;
        }
        String commentary = mlClient.commentary(commentaryInput);
        if (hasText(commentary) && !commentary.startsWith("（AI")) {
            item.setCommentary(commentary.trim());
        }
    }

    private NewsItem resolveFocusedNewsItem(ConversationStateManager.NewsSnapshotState snapshotState, List<NewsItem> items) {
        if (items == null || items.isEmpty()) {
            return null;
        }
        if (items.size() == 1) {
            return items.get(0);
        }
        if (snapshotState == null || !hasText(snapshotState.focusedNewsId())) {
            return null;
        }
        return items.stream()
                .filter(item -> snapshotState.focusedNewsId().equals(item.getId()))
                .findFirst()
                .orElse(null);
    }

    private boolean isSingularNewsReference(String lower) {
        return IntentKeywords.isSingularNewsReference(lower);
    }

    private AnalysisTemplate resolveAnalysisTemplate(List<NewsItem> items) {
        if (items == null || items.size() != 1) {
            return AnalysisTemplate.GENERAL;
        }
        NewsItem item = items.get(0);
        String text = String.join(" ",
                        defaultText(item.getTitle(), ""),
                item.summaryPreviewText(),
            defaultText(resolveDetailExcerpt(item), ""),
                        defaultText(item.getUrl(), ""),
                        defaultText(item.getSource(), ""))
                .toLowerCase();
        if (IntentKeywords.isPaperAnalysisContent(text)) {
            return AnalysisTemplate.PAPER;
        }
        if (IntentKeywords.isPolicyAnalysisContent(text)) {
            return AnalysisTemplate.POLICY;
        }
        if (IntentKeywords.isProductAnalysisContent(text)) {
            return AnalysisTemplate.PRODUCT;
        }
        if (IntentKeywords.isCommunityAnalysisContent(text, item.getSourceType(), item.getSource())) {
            return AnalysisTemplate.COMMUNITY;
        }
        return AnalysisTemplate.GENERAL;
    }

    private Map<String, String> translateNewsTitles(List<NewsItem> newsItems) {
        if (newsItems == null || newsItems.isEmpty()) {
            return Map.of();
        }

        List<String> pendingTitles = newsItems.stream()
                .map(NewsItem::getTitle)
                .filter(this::needsChineseTranslation)
                .distinct()
                .filter(title -> !translatedNewsTitles.containsKey(title))
                .toList();
        if (!pendingTitles.isEmpty()) {
            requestTitleTranslations(pendingTitles)
                    .forEach((original, translated) -> translatedNewsTitles.putIfAbsent(original, translated));
        }

        Map<String, String> translated = new LinkedHashMap<>();
        for (NewsItem item : newsItems) {
            String originalTitle = item.getTitle();
            if (!needsChineseTranslation(originalTitle)) {
                continue;
            }
            String translatedTitle = translatedNewsTitles.get(originalTitle);
            if (hasText(translatedTitle)) {
                translated.put(originalTitle, translatedTitle);
            }
        }
        return translated;
    }

    private Map<String, String> translateNewsSummaries(List<NewsItem> newsItems) {
        if (newsItems == null || newsItems.isEmpty()) {
            return Map.of();
        }

        Map<String, String> translated = new LinkedHashMap<>();
        for (NewsItem item : newsItems) {
            String originalSummary = normalizeSummaryForTranslation(item.summaryPreviewText());
            if (!needsChineseTranslation(originalSummary)) {
                continue;
            }
            String translatedSummary = sanitizeTranslatedText(item.getTranslatedSummaryPreview());
            if (hasText(translatedSummary)) {
                translated.put(originalSummary, translatedSummary);
                translatedNewsSummaries.putIfAbsent(originalSummary, translatedSummary);
            }
        }

        List<String> pendingSummaries = newsItems.stream()
                .map(NewsItem::summaryPreviewText)
                .map(this::normalizeSummaryForTranslation)
                .filter(this::needsChineseTranslation)
                .distinct()
                .filter(summary -> !translated.containsKey(summary))
                .filter(summary -> !translatedNewsSummaries.containsKey(summary))
                .toList();
        if (!pendingSummaries.isEmpty()) {
            requestSummaryTranslations(pendingSummaries)
                    .forEach((original, translatedValue) -> translatedNewsSummaries.putIfAbsent(original, translatedValue));
        }

        for (NewsItem item : newsItems) {
            String originalSummary = normalizeSummaryForTranslation(item.summaryPreviewText());
            if (!needsChineseTranslation(originalSummary)) {
                continue;
            }
            String translatedSummary = translated.get(originalSummary);
            if (!hasText(translatedSummary)) {
                translatedSummary = translatedNewsSummaries.get(originalSummary);
            }
            if (hasText(translatedSummary)) {
                item.setTranslatedSummaryPreview(translatedSummary);
                translated.put(originalSummary, translatedSummary);
            }
        }
        return translated;
    }

    private Map<String, String> requestTitleTranslations(List<String> titles) {
        if (titles == null || titles.isEmpty()) {
            return Map.of();
        }
        return mapLocalTranslations(titles, mlClient.translateTexts(titles, "title"));
    }

    private Map<String, String> requestSummaryTranslations(List<String> summaries) {
        if (summaries == null || summaries.isEmpty()) {
            return Map.of();
        }
        return mapLocalTranslations(summaries, mlClient.translateTexts(summaries, "summary"));
    }

    private String requestBodyTranslation(String body) {
        String normalizedBody = normalizeDetailBody(body);
        if (!hasText(normalizedBody) || !needsChineseTranslation(normalizedBody)) {
            return "";
        }
        String cachedTranslation = translatedNewsBodies.get(normalizedBody);
        if (hasText(cachedTranslation)) {
            return cachedTranslation;
        }

        List<String> chunks = splitBodyTranslationChunks(normalizedBody);
        if (chunks.isEmpty()) {
            return "";
        }

        List<String> translatedChunks = mlClient.translateTexts(chunks, "body");
        if (translatedChunks == null || translatedChunks.size() != chunks.size()) {
            return "";
        }
        List<String> sanitizedChunks = new ArrayList<>();
        for (int index = 0; index < chunks.size(); index++) {
            String translatedChunk = sanitizeTranslatedText(translatedChunks.get(index));
            if (!hasText(translatedChunk) || translatedChunk.equals(chunks.get(index))) {
                return "";
            }
            sanitizedChunks.add(translatedChunk);
        }
        String translatedBody = String.join("\n\n", sanitizedChunks).trim();
        if (hasText(translatedBody)) {
            translatedNewsBodies.putIfAbsent(normalizedBody, translatedBody);
        }
        return translatedBody;
    }

    private Map<String, String> mapLocalTranslations(List<String> sourceTexts, List<String> translatedTexts) {
        if (sourceTexts == null || sourceTexts.isEmpty() || translatedTexts == null || translatedTexts.isEmpty()) {
            return Map.of();
        }

        Map<String, String> translated = new LinkedHashMap<>();
        int size = Math.min(sourceTexts.size(), translatedTexts.size());
        for (int index = 0; index < size; index++) {
            String sourceText = sourceTexts.get(index);
            String translatedText = sanitizeTranslatedText(translatedTexts.get(index));
            if (hasText(translatedText) && !translatedText.equals(sourceText)) {
                translated.put(sourceText, translatedText);
            }
        }
        return translated;
    }

    private String normalizeSummaryForTranslation(String summary) {
        return defaultText(summary, "")
                .replaceAll("<[^>]+>", " ")
                .replace("&nbsp;", " ")
                .replace("&quot;", "\"")
                .replace("&#39;", "'")
                .replace("&amp;", "&")
                .replaceAll("\\s+", " ")
                .trim();
    }

    private String normalizeDetailBody(String body) {
        return defaultText(body, "")
                .replace("\r", "")
                .replaceAll("\n{3,}", "\n\n")
                .trim();
    }

    private String buildDetailExcerpt(String body) {
        String normalizedBody = normalizeDetailBody(body);
        if (!hasText(normalizedBody)) {
            return "";
        }
        if (normalizedBody.length() <= DETAIL_EXCERPT_LIMIT) {
            return normalizedBody;
        }
        return normalizedBody.substring(0, DETAIL_EXCERPT_LIMIT) + "…";
    }

    private String resolveDetailExcerpt(NewsItem item) {
        if (item == null) {
            return "";
        }
        promoteLegacyDetailExcerpt(item);
        return normalizeDetailBody(item.resolvedDetailExcerpt());
    }

    private void promoteLegacyDetailExcerpt(NewsItem item) {
        if (item == null) {
            return;
        }
        if (item.promoteLegacyDetailExcerpt()) {
            item.setDetailExcerpt(normalizeDetailBody(item.getDetailExcerpt()));
        }
    }

    private String resolveFullBody(NewsItem item) {
        if (item == null) {
            return "";
        }
        return normalizeDetailBody(item.getFullBody());
    }

    private List<String> splitBodyTranslationChunks(String body) {
        String normalizedBody = normalizeDetailBody(body);
        if (!hasText(normalizedBody)) {
            return List.of();
        }
        List<String> paragraphs = Arrays.stream(normalizedBody.split("\n+"))
                .map(String::trim)
            .filter(com.bot.util.TextUtils::hasText)
                .flatMap(paragraph -> splitOversizedBodySegment(paragraph).stream())
                .toList();
        if (paragraphs.isEmpty()) {
            return List.of();
        }

        List<String> chunks = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        for (String paragraph : paragraphs) {
            String candidate = current.isEmpty() ? paragraph : current + "\n\n" + paragraph;
            if (candidate.length() <= FULL_BODY_TRANSLATION_CHUNK_LENGTH || current.isEmpty()) {
                current.setLength(0);
                current.append(candidate);
                continue;
            }
            chunks.add(current.toString());
            current.setLength(0);
            current.append(paragraph);
        }
        if (!current.isEmpty()) {
            chunks.add(current.toString());
        }
        return chunks.stream().filter(com.bot.util.TextUtils::hasText).toList();
    }

    private List<String> splitOversizedBodySegment(String segment) {
        if (!hasText(segment)) {
            return List.of();
        }
        if (segment.length() <= FULL_BODY_TRANSLATION_CHUNK_LENGTH) {
            return List.of(segment);
        }
        List<String> slices = new ArrayList<>();
        for (int start = 0; start < segment.length(); start += FULL_BODY_TRANSLATION_CHUNK_LENGTH) {
            int end = Math.min(start + FULL_BODY_TRANSLATION_CHUNK_LENGTH, segment.length());
            slices.add(segment.substring(start, end).trim());
        }
        return slices;
    }

    private String sanitizeTranslatedText(String translatedText) {
        String normalized = translatedText == null ? "" : translatedText.trim();
        if ((normalized.startsWith("\"") && normalized.endsWith("\""))
                || (normalized.startsWith("“") && normalized.endsWith("”"))) {
            normalized = normalized.substring(1, normalized.length() - 1).trim();
        }
        return normalized;
    }

    private boolean needsChineseTranslation(String title) {
        if (!hasText(title)) {
            return false;
        }
        boolean containsChinese = title.codePoints()
                .anyMatch(codePoint -> Character.UnicodeScript.of(codePoint) == Character.UnicodeScript.HAN);
        boolean containsLatinLetters = title.codePoints()
                .anyMatch(codePoint -> (codePoint >= 'A' && codePoint <= 'Z') || (codePoint >= 'a' && codePoint <= 'z'));
        return !containsChinese && containsLatinLetters;
    }

    private String resolveDisplayTitle(NewsItem item, Map<String, String> translatedTitles) {
        if (item == null) {
            return "无标题";
        }
        String originalTitle = item.getTitle();
        String translatedTitle = translatedTitles.get(originalTitle);
        return hasText(translatedTitle) ? translatedTitle : defaultText(originalTitle, "无标题");
    }

    private boolean isTranslatedTitle(NewsItem item, Map<String, String> translatedTitles) {
        if (item == null) {
            return false;
        }
        String originalTitle = item.getTitle();
        String translatedTitle = translatedTitles.get(originalTitle);
        return hasText(translatedTitle) && !translatedTitle.equals(originalTitle);
    }

    private String formatMarkdownLink(String text, String url) {
        String linkText = escapeMarkdownLinkText(defaultText(text, "无标题"));
        if (!hasText(url)) {
            return linkText;
        }
        return "[" + linkText + "](" + url + ")";
    }

    private String escapeMarkdownLinkText(String text) {
        return defaultText(text, "无标题")
                .replace("[", "\\[")
                .replace("]", "\\]")
                .replace("\n", " ");
    }

    private List<NewsItem> snapshotItems(List<NewsItem> items) {
        if (items == null || items.isEmpty()) {
            return List.of();
        }
        return items.stream()
                .limit(NEWS_SNAPSHOT_LIMIT)
                .map(item -> {
                    String detailExcerpt = resolveDetailExcerpt(item);
                    return NewsItem.builder()
                        .id(item.getId())
                        .title(item.getTitle())
                        .summary(item.summaryPreviewText())
                        .translatedSummaryPreview(item.getTranslatedSummaryPreview())
                        .detailExcerpt(detailExcerpt)
                        .fullBody(item.getFullBody())
                        .translatedDetailExcerpt(item.getTranslatedDetailExcerpt())
                        .translatedFullBody(item.getTranslatedFullBody())
                        .url(item.getUrl())
                        .source(item.getSource())
                        .sourceType(item.getSourceType())
                        .category(item.getCategory())
                        .trustLevel(item.getTrustLevel())
                        .publishTime(item.getPublishTime())
                        .fetchedAt(item.getFetchedAt())
                        .discussionUrl(item.getDiscussionUrl())
                        .commentary(item.getCommentary())
                        .followUpOf(item.getFollowUpOf())
                        .followUpTag(item.getFollowUpTag())
                        .build();
                })
                .toList();
    }

    private void enrichChatFollowUps(NewsSnapshotDecision newsDecision) {
        if (!newsDecision.followUpRequested() || !newsDecision.hasSnapshot()) {
            return;
        }
        var alerts = trackingService.findRecentFollowUpsWithAlerts(newsDecision.items(), CHAT_FOLLOW_UP_LIMIT);
        alerts.forEach(alert -> log.warn("Assistant chat follow-up alert source={} code={} message={}",
                alert.source(),
                alert.code(),
                alert.message()));
    }

    private void rememberNewsSnapshot(String conversationId, NewsSnapshotDecision newsDecision) {
        if (!newsDecision.hasSnapshot()) {
            return;
        }
        String focusedNewsId = newsDecision.items().size() == 1 ? newsDecision.items().get(0).getId() : null;
        conversationStateManager.rememberNewsSnapshot(
                conversationId,
                newsDecision.requestedLayer(),
                newsDecision.requestedCategory(),
                newsDecision.items(),
                focusedNewsId
        );
    }

    private boolean shouldReusePreviousNewsSnapshot(String lower, ConversationStateManager.NewsSnapshotState previousSnapshot) {
        if (!hasReusableNewsSnapshot(previousSnapshot)) {
            return false;
        }
        return IntentKeywords.referencesPreviousSnapshot(lower);
    }

    private boolean hasReusableNewsSnapshot(ConversationStateManager.NewsSnapshotState snapshot) {
        return snapshot != null && snapshot.items() != null && !snapshot.items().isEmpty();
    }

    private NewsDisplayTime resolveNewsDisplayTime(NewsItem item) {
        if (item == null) {
            return null;
        }
        String publishTime = NewsTimeUtils.formatDisplayTime(item.getPublishTime());
        if (hasText(publishTime)) {
            return new NewsDisplayTime("发布时间", publishTime);
        }
        String fetchedAt = NewsTimeUtils.formatDisplayTime(item.getFetchedAt());
        if (hasText(fetchedAt)) {
            return new NewsDisplayTime("抓取时间", fetchedAt);
        }
        return null;
    }

    private String buildNewsDisplayTimeText(NewsItem item) {
        NewsDisplayTime displayTime = resolveNewsDisplayTime(item);
        if (displayTime == null) {
            return "";
        }
        return displayTime.label() + " " + displayTime.value();
    }

    private void logNewsSnapshot(String conversationId, String content, List<NewsItem> newsSnapshot) {
        if (newsSnapshot == null || newsSnapshot.isEmpty()) {
            return;
        }
        String snapshotText = newsSnapshot.stream()
                .map(item -> String.format("%s|%s|%s|%s|%s|%s|%s",
                        defaultText(item.getTitle(), "无标题"),
                        defaultText(item.getSource(), "未知来源"),
                    defaultText(item.getSourceType(), "未知层级"),
                    defaultText(item.getCategory(), "未知分类"),
                    defaultText(item.getTrustLevel(), "未知可信度"),
                        defaultText(item.getUrl(), "无链接"),
                        defaultText(item.getFetchedAt(), "未知抓取时间")))
                .reduce((left, right) -> left + " ; " + right)
                .orElse("");
        log.info("Assistant news snapshot conversationId={} question={} snapshot={}",
                conversationId,
                limit(content, 120),
                snapshotText);
    }

    private String formatWeatherReply(WeatherInfo weather) {
        var sb = new StringBuilder("## 今天天气快照\n\n");
        sb.append("- **城市**: ").append(weather.getCity()).append("\n")
            .append("- **来源**: 和风天气\n")
            .append("- **日期**: ").append(defaultText(weather.getDate(), "未知")).append("\n")
            .append("- **天气**: ").append(defaultText(weather.getCondition(), "未知")).append("\n")
            .append("- **温度**: ").append(weather.getTempLow()).append(" ~ ").append(weather.getTempHigh()).append(" °C\n");
        if (weather.getAqi() >= 0 && !"暂缺".equals(defaultText(weather.getAqiLevel(), "暂缺"))) {
            sb.append("- **空气质量**: AQI ").append(weather.getAqi()).append("（").append(defaultText(weather.getAqiLevel(), "未知")).append("）\n");
        } else {
            sb.append("- **空气质量**: 暂缺（当前凭据无空气质量数据权限）\n");
        }
        sb.append("- **风向风力**: ").append(defaultText(weather.getWindDirection(), "未知")).append(" ").append(defaultText(weather.getWindScale(), "未知")).append("\n")
            .append("- **建议**: ").append(buildWeatherSuggestion(weather)).append("\n\n")
            .append("> 当前聊天天气仅支持配置城市“").append(weatherService.getConfiguredCityName()).append("”。");
        return sb.toString();
    }

    private String buildWeatherSuggestion(WeatherInfo weather) {
        if (weather.getAqi() >= 150) {
            return "空气较差，尽量减少长时间户外活动。";
        }
        if (weather.getCondition() != null && (weather.getCondition().contains("雨") || weather.getCondition().contains("雷"))) {
            return "有降水风险，出门带伞更稳。";
        }
        if (weather.getTempHigh() >= 33) {
            return "气温偏高，注意补水和防晒。";
        }
        if (weather.getTempLow() <= 10) {
            return "早晚偏冷，建议加一层外套。";
        }
        return "体感整体正常，按日常出行即可。";
    }

    private void logWeatherSnapshot(String conversationId, String content, WeatherInfo weather) {
        log.info("Assistant weather snapshot conversationId={} question={} city={} date={} condition={} tempLow={} tempHigh={} aqi={} level={}",
                conversationId,
                limit(content, 120),
                weather.getCity(),
                weather.getDate(),
                weather.getCondition(),
                weather.getTempLow(),
                weather.getTempHigh(),
                weather.getAqi(),
                weather.getAqiLevel());
    }

    private void remember(String conversationId, String userText, String assistantText) {
        conversationStateManager.remember(
                conversationId,
                limit(userText, 500),
                limit(assistantText, 1000)
        );
    }

    private boolean sendToQq(String scene, String targetId, String reply, String msgId) {
        if ("group".equals(scene)) {
            return pusher.replyToQqGroup(targetId, reply, msgId);
        }
        return pusher.replyToQqUser(targetId, reply, msgId);
    }

    private String helpText() {
        return "可用方式：\n"
                + "1. 发“今日热点”查看最新热点列表\n"
                + "2. 发“最近有什么新闻”查看新闻源层的数据\n"
                + "3. 发“分析 + 关键词”让我按主题展开\n"
                + "4. 发“今天天气怎么样”查看配置城市天气\n"
                + "5. 发“清空上下文”重置会话";
    }

    /**
     * When intent confidence is too low for a reliable decision,
     * ask a clarifying question instead of guessing.
     */
    private String buildClarification(String conversationId, String content) {
        ConversationStateManager.NewsSnapshotState snapshot =
                conversationStateManager.reusableNewsSnapshot(conversationId);
        boolean hasFocusedItem = snapshot != null
                && hasText(snapshot.focusedNewsId());

        if (hasFocusedItem) {
            return "你是想了解这条新闻的详情、看它的后续进展，还是有其他问题？直接说就行。";
        }
        if (hasReusableNewsSnapshot(snapshot)) {
            return "你是想看刚才某条新闻的详情，还是想查新的新闻？直接说标题或发序号。";
        }
        return "不太确定你想做什么。你可以说 /今日热点/ 看新闻、/今天天气怎么样/ 查天气，或者直接发问题。";
    }


    private String resolveConversationId(AssistantChatRequest request, String scene, String targetId) {
        if (request.getConversationId() != null && !request.getConversationId().isBlank()) {
            return request.getConversationId().trim();
        }
        return scene + ":" + (targetId == null || targetId.isBlank() ? "unknown" : targetId.trim());
    }

    private String resolveTargetId(AssistantChatRequest request, String scene) {
        if (request.getChatId() != null && !request.getChatId().isBlank()) {
            return request.getChatId().trim();
        }
        if (!"group".equals(scene) && request.getSenderId() != null && !request.getSenderId().isBlank()) {
            return request.getSenderId().trim();
        }
        return null;
    }

    private String normalizeScene(String scene) {
        if (scene == null || scene.isBlank()) {
            return "c2c";
        }
        String normalized = scene.trim().toLowerCase();
        return switch (normalized) {
            case "group", "c2c", "user" -> normalized.equals("user") ? "c2c" : normalized;
            default -> "c2c";
        };
    }

    private String normalizeText(String content) {
        return content == null ? "" : content.trim();
    }

    private String limit(String text, int maxLen) {
        if (text == null || text.length() <= maxLen) {
            return text;
        }
        return text.substring(0, maxLen) + "…";
    }

    private record NewsDisplayTime(String label, String value) {
    }

    private record ScopedNewsDecision(NewsSnapshotDecision newsDecision, String immediateReply) {
        private static ScopedNewsDecision keep(NewsSnapshotDecision newsDecision) {
            return new ScopedNewsDecision(newsDecision, null);
        }

        private static ScopedNewsDecision reply(String reply) {
            return new ScopedNewsDecision(NewsSnapshotDecision.none(), reply);
        }
    }

    private enum AnalysisTemplate {
        GENERAL,
        COMMUNITY,
        PAPER,
        PRODUCT,
        POLICY
    }

}