package com.bot.service;

import com.bot.client.PythonMLClient;
import com.bot.model.AssistantChatRequest;
import com.bot.model.AssistantChatResponse;
import com.bot.model.AssistantMessage;
import com.bot.model.NewsItem;
import com.bot.model.WeatherInfo;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.time.Duration;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

@Slf4j
@Service
@RequiredArgsConstructor
public class AssistantConversationService {

    private static final int MAX_HISTORY_MESSAGES = 12;
    private static final int NEWS_SNAPSHOT_LIMIT = 4;
    private static final int CHAT_FOLLOW_UP_LIMIT = 3;
    private static final int NEWS_SNAPSHOT_RETENTION_MINUTES = 30;
    private static final String NO_NEWS_REPLY = "当前 RSS/聚合 API 没拉到数据，我不输出新闻结论。";
    private static final String NO_WEATHER_REPLY = "当前天气服务未配置或不可用，暂时不能提供实时天气。";
    private static final Pattern TRANSLATION_LINE_PATTERN = Pattern.compile("^(\\d+)\\s*(?:[\\t\\.|、:：-]\\s*)?(.+)$");
    private static final ZoneId NEWS_DISPLAY_ZONE = ZoneId.of("Asia/Shanghai");
    private static final DateTimeFormatter NEWS_DATE_TIME_FORMAT = DateTimeFormatter.ofPattern("M月d日 HH:mm");
    private static final DateTimeFormatter NEWS_DATE_FORMAT = DateTimeFormatter.ofPattern("M月d日");

    private final NewsService newsService;
    private final WeatherService weatherService;
    private final TrackingService trackingService;
    private final PythonMLClient mlClient;
    private final WeChatPusher pusher;
    private final NewsCardRenderer newsCardRenderer;

    private final Map<String, Deque<AssistantMessage>> conversations = new ConcurrentHashMap<>();
    private final Map<String, String> translatedNewsTitles = new ConcurrentHashMap<>();
    private final Map<String, NewsSnapshotState> recentNewsSnapshots = new ConcurrentHashMap<>();

    public AssistantChatResponse chat(AssistantChatRequest request) {
        String scene = normalizeScene(request.getScene());
        String targetId = resolveTargetId(request, scene);
        String conversationId = resolveConversationId(request, scene, targetId);
        String content = normalizeText(request.getContent());

        if (content.isEmpty()) {
            return buildResponse(conversationId, scene, targetId, ReplyPlan.of("先发具体问题，比如：今日热点、分析 AI 芯片、清空上下文。"), false);
        }

        ReplyPlan replyPlan;
        if (isClearCommand(content)) {
            conversations.remove(conversationId);
            recentNewsSnapshots.remove(conversationId);
            replyPlan = ReplyPlan.of("上下文已清空。");
        } else if (isHelpCommand(content)) {
            replyPlan = ReplyPlan.of(helpText());
        } else if (isWeatherQuery(content)) {
            replyPlan = buildWeatherReply(conversationId, content);
        } else if (isHotNewsCommand(content)) {
            replyPlan = buildHotNewsReply(conversationId, content);
        } else {
            replyPlan = buildAiReply(conversationId, content);
        }

        boolean sentToQq = false;
        if (request.isSendReply() && targetId != null && !targetId.isBlank()) {
            sentToQq = sendToQq(scene, targetId, replyPlan.reply(), request.getMsgId());
        }
        return buildResponse(conversationId, scene, targetId, replyPlan, sentToQq);
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
        List<AssistantMessage> history = snapshot(conversationId);
        NewsSnapshotDecision newsDecision = resolveNewsSnapshot(conversationId, content, null);
        if (newsDecision.requiresAnyNewsContext() && newsDecision.items().isEmpty()) {
            String reply = NO_NEWS_REPLY;
            remember(conversationId, content, reply);
            return ReplyPlan.of(reply);
        }

        enrichChatFollowUps(newsDecision);
        Map<String, String> translatedTitles = translateNewsTitles(newsDecision.items());
        String systemPrompt = buildSystemPrompt(content, newsDecision, translatedTitles);
        String aiReply = mlClient.chat(content, history, systemPrompt);
        String reply = newsDecision.hasSnapshot()
            ? buildNewsGroundedReply(newsDecision, translatedTitles, aiReply)
                : aiReply;
        remember(conversationId, content, reply);
        if (newsDecision.hasSnapshot()) {
            rememberNewsSnapshot(conversationId, newsDecision);
            logNewsSnapshot(conversationId, content, newsDecision.items());
        }
        return withNewsCard(reply, newsDecision, translatedTitles);
    }

    private ReplyPlan buildWeatherReply(String conversationId, String content) {
        if (!weatherService.isConfigured()) {
            remember(conversationId, content, NO_WEATHER_REPLY);
            return ReplyPlan.of(NO_WEATHER_REPLY);
        }

        String requestedCity = detectRequestedCity(content);
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
                你是“热点追踪分析 bot”的 QQ 助手。
                回答要求：
                1. 先给结论，再给依据。
                2. 语气直接，别写成公文。
                3. 用户问热点、新闻、趋势时，优先回答：发生了什么、为什么重要、接下来怎么看。
                4. 信息不够就明确说，不要编造。
                5. 控制篇幅，默认 3 到 6 句话。
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
        }

        if (newsDecision.requiresAnyNewsContext()) {
            prompt.append("\n本次新闻查询层级：")
                    .append(displaySourceType(newsDecision.requestedLayer()))
                    .append("；分类：")
                    .append(displayCategory(newsDecision.requestedCategory()))
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
                    .append(displaySourceType(item.getSourceType()))
                    .append("\n   分类: ")
                    .append(displayCategory(item.getCategory()))
                    .append("\n   可信度: ")
                    .append(displayTrustLevel(item.getTrustLevel()))
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
            if (item.getSummary() != null && !item.getSummary().isBlank()) {
                sb.append("\n   摘要: ").append(limit(item.getSummary(), 100));
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
        boolean requiresFreshNews = containsKeyword(lower, "热点", "新闻", "热搜", "追踪", "趋势", "发生了什么", "最近", "最新");
        boolean requestedAnalysis = containsKeyword(lower, "分析", "解读");
        boolean followUpRequested = isFollowUpQuery(lower);
        NewsSnapshotState previousSnapshot = recentNewsSnapshots.get(conversationId);
        boolean reusePreviousSnapshot = forcedLayer == null && shouldReusePreviousNewsSnapshot(conversationId, lower, previousSnapshot);
        if (reusePreviousSnapshot) {
            return new NewsSnapshotDecision(false, true, true, followUpRequested,
                    previousSnapshot.requestedLayer(),
                    previousSnapshot.requestedCategory(),
                    snapshotItems(previousSnapshot.items()));
        }
        boolean needsNewsContext = requiresFreshNews || requestedAnalysis || followUpRequested;
        if (!needsNewsContext) {
            return NewsSnapshotDecision.none();
        }
        String requestedLayer = forcedLayer != null ? forcedLayer : resolveRequestedLayer(lower);
        String requestedCategory = resolveRequestedCategory(lower);
        try {
            List<NewsItem> newsList = snapshotItems(newsService.fetchLayered(requestedLayer, requestedCategory));
            return new NewsSnapshotDecision(requiresFreshNews, needsNewsContext, false, followUpRequested, requestedLayer, requestedCategory, newsList);
        } catch (Exception e) {
            log.warn("Failed to resolve news snapshot: {}", e.getMessage());
            return new NewsSnapshotDecision(requiresFreshNews, needsNewsContext, false, followUpRequested, requestedLayer, requestedCategory, List.of());
        }
    }

    private ReplyPlan buildHotNewsReply(String conversationId, String content) {
        NewsSnapshotDecision newsDecision = resolveNewsSnapshot(conversationId, content, "hotlist");
        if (newsDecision.items().isEmpty()) {
            remember(conversationId, content, NO_NEWS_REPLY);
            return ReplyPlan.of(NO_NEWS_REPLY);
        }

        Map<String, String> translatedTitles = translateNewsTitles(newsDecision.items());
        var sb = new StringBuilder("## 今日热点快照（热榜层）\n\n");
        sb.append(formatNewsSnapshotForUser(newsDecision.items(), translatedTitles));
        sb.append("\n> 继续发“分析 + 关键词”或“解读 + 关键词”，我会只基于以上快照展开。比如：分析 AI 芯片。");
        String reply = sb.toString();
        rememberNewsSnapshot(conversationId, newsDecision);
        remember(conversationId, content, reply);
        logNewsSnapshot(conversationId, content, newsDecision.items());
        return withNewsCard(
                reply,
                newsDecision,
                translatedTitles,
                "今日热点卡片",
                "热榜层最新快照 · 共 " + newsDecision.items().size() + " 条"
        );
    }

    private ReplyPlan withNewsCard(String reply, NewsSnapshotDecision newsDecision, Map<String, String> translatedTitles) {
        String title = newsDecision.followUpRequested()
                ? "热点跟进卡片"
                : "hotlist".equals(newsDecision.requestedLayer()) ? "今日热点卡片" : "新闻摘要卡片";
        String subtitle = displaySourceType(newsDecision.requestedLayer())
                + " · "
                + displayCategory(newsDecision.requestedCategory())
                + " · 共 "
                + newsDecision.items().size()
                + " 条"
                + (newsDecision.followUpRequested() ? " · 含近 7 天跟进线索" : "");
        return withNewsCard(reply, newsDecision, translatedTitles, title, subtitle);
    }

    private ReplyPlan withNewsCard(String reply, NewsSnapshotDecision newsDecision, Map<String, String> translatedTitles, String cardTitle, String cardSubtitle) {
        String mediaPath = newsCardRenderer.renderNewsCard(cardTitle, cardSubtitle, newsDecision.items(), translatedTitles);
        if (!hasText(mediaPath)) {
            return new ReplyPlan(reply, newsDecision.items(), null, null, null);
        }
        return new ReplyPlan(reply, newsDecision.items(), "image", mediaPath, null);
    }

    private String buildNewsGroundedReply(NewsSnapshotDecision newsDecision, Map<String, String> translatedTitles, String aiReply) {
        String conclusion = aiReply == null ? "" : aiReply.trim();
        String followUpSection = newsDecision.followUpRequested()
                ? buildFollowUpSection(newsDecision.items(), translatedTitles)
                : "";
        String titledFollowUpSection = hasText(followUpSection)
                ? "## 近 7 天后续追踪\n\n" + followUpSection
                : "";

        if (newsDecision.reusesPreviousSnapshot()) {
            if (hasText(titledFollowUpSection) && hasText(conclusion)) {
                return titledFollowUpSection + "\n\n## 基于以上跟进线索的判断\n\n" + conclusion;
            }
            if (hasText(titledFollowUpSection)) {
                return titledFollowUpSection;
            }
            return hasText(conclusion) ? conclusion : "基于上一轮新闻快照，暂时没生成新的分析结论。";
        }

        var sb = new StringBuilder("## 本次回答依据的新闻快照\n\n");
        sb.append(formatNewsSnapshotForUser(newsDecision.items(), translatedTitles));
        if (hasText(titledFollowUpSection)) {
            sb.append("\n").append(titledFollowUpSection);
        }
        if (hasText(conclusion)) {
            sb.append(hasText(titledFollowUpSection)
                    ? "\n## 基于以上快照和跟进线索的结论\n\n"
                    : "\n## 基于以上快照的结论\n\n");
            sb.append(conclusion);
        }
        return sb.toString();
    }

    private String formatNewsSnapshotForUser(List<NewsItem> newsSnapshot, Map<String, String> translatedTitles) {
        var sb = new StringBuilder();
        for (int i = 0; i < newsSnapshot.size(); i++) {
            NewsItem item = newsSnapshot.get(i);
            String displayTitle = resolveDisplayTitle(item, translatedTitles);
            sb.append(i + 1)
                    .append(". ")
                    .append(formatMarkdownLink(displayTitle, item.getUrl()))
                    .append("\n   - 来源: ")
                    .append(item.getSource())
                    .append("\n   - 来源层级: ")
                    .append(displaySourceType(item.getSourceType()))
                    .append("\n   - 分类: ")
                    .append(displayCategory(item.getCategory()))
                    .append("\n   - 可信度: ")
                    .append(displayTrustLevel(item.getTrustLevel()));
            NewsDisplayTime displayTime = resolveNewsDisplayTime(item);
            if (displayTime != null) {
                sb.append("\n   - ")
                        .append(displayTime.label())
                        .append(": ")
                        .append(displayTime.value());
            }
            if (hasText(item.getFollowUpTag())) {
                sb.append("\n   - 近7天跟进线索: ").append(item.getFollowUpTag());
            }
            sb.append("\n");
        }
        return sb.toString();
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

    private Map<String, String> requestTitleTranslations(List<String> titles) {
        if (titles == null || titles.isEmpty()) {
            return Map.of();
        }

        String message = IntStream.range(0, titles.size())
                .mapToObj(index -> (index + 1) + "\t" + titles.get(index))
                .collect(Collectors.joining("\n"));
        String systemPrompt = """
                你是新闻标题翻译器。
                任务：把输入中的英文新闻标题翻译成简体中文。
                要求：
                1. 只翻译标题，不解释，不扩写。
                2. 保留品牌名、产品名、公司名、人名的常见中文或原文写法。
                3. 每行严格输出：序号<TAB>中文标题。
                4. 不要输出 Markdown，不要输出代码块，不要输出任何额外说明。
                """;
        try {
            String reply = mlClient.chat(message, List.of(), systemPrompt);
            return parseTranslatedTitles(titles, reply);
        } catch (Exception e) {
            log.warn("Failed to translate news titles: {}", e.getMessage());
            return Map.of();
        }
    }

    private Map<String, String> parseTranslatedTitles(List<String> titles, String reply) {
        if (!hasText(reply)) {
            return Map.of();
        }

        Map<String, String> translated = new LinkedHashMap<>();
        String normalizedReply = reply.replace("```", "").trim();
        for (String rawLine : normalizedReply.split("\\R")) {
            String line = rawLine.trim();
            if (!hasText(line)) {
                continue;
            }
            var matcher = TRANSLATION_LINE_PATTERN.matcher(line);
            if (!matcher.matches()) {
                continue;
            }
            int index;
            try {
                index = Integer.parseInt(matcher.group(1)) - 1;
            } catch (NumberFormatException e) {
                continue;
            }
            if (index < 0 || index >= titles.size()) {
                continue;
            }
            String translatedTitle = sanitizeTranslatedTitle(matcher.group(2));
            if (hasText(translatedTitle)) {
                translated.put(titles.get(index), translatedTitle);
            }
        }
        return translated;
    }

    private String sanitizeTranslatedTitle(String translatedTitle) {
        String normalized = translatedTitle == null ? "" : translatedTitle.trim();
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
                .map(item -> NewsItem.builder()
                        .id(item.getId())
                        .title(item.getTitle())
                        .summary(item.getSummary())
                        .url(item.getUrl())
                        .source(item.getSource())
                        .sourceType(item.getSourceType())
                        .category(item.getCategory())
                        .trustLevel(item.getTrustLevel())
                        .publishTime(item.getPublishTime())
                        .fetchedAt(item.getFetchedAt())
                        .discussionUrl(item.getDiscussionUrl())
                        .followUpOf(item.getFollowUpOf())
                        .followUpTag(item.getFollowUpTag())
                        .build())
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

    private String buildFollowUpSection(List<NewsItem> newsSnapshot, Map<String, String> translatedTitles) {
        if (newsSnapshot == null || newsSnapshot.isEmpty()) {
            return "";
        }

        List<NewsItem> scopedItems = newsSnapshot.stream()
                .limit(CHAT_FOLLOW_UP_LIMIT)
                .toList();
        List<NewsItem> matchedItems = scopedItems.stream()
                .filter(item -> hasText(item.getFollowUpTag()))
                .toList();
        if (matchedItems.isEmpty()) {
            return "- 当前快照前 " + scopedItems.size() + " 条新闻中，暂未命中近 7 天相似报道。";
        }

        var sb = new StringBuilder();
        for (int i = 0; i < matchedItems.size(); i++) {
            NewsItem item = matchedItems.get(i);
            String displayTitle = resolveDisplayTitle(item, translatedTitles);
            sb.append(i + 1)
                    .append(". ")
                    .append(formatMarkdownLink(displayTitle, item.getUrl()))
                    .append("\n   - ")
                    .append(item.getFollowUpTag())
                    .append("\n");
        }
        return sb.toString().trim();
    }

            private void rememberNewsSnapshot(String conversationId, NewsSnapshotDecision newsDecision) {
                if (!newsDecision.hasSnapshot()) {
                    return;
                }
                recentNewsSnapshots.put(conversationId,
                        new NewsSnapshotState(
                                newsDecision.requestedLayer(),
                                newsDecision.requestedCategory(),
                                snapshotItems(newsDecision.items()),
                                System.currentTimeMillis()));
            }

            private boolean shouldReusePreviousNewsSnapshot(String conversationId, String lower, NewsSnapshotState previousSnapshot) {
                if (!hasReusableNewsSnapshot(conversationId, previousSnapshot)) {
                    return false;
                }
                return containsKeyword(lower,
                        "这些新闻",
                        "这些热点",
                        "这些消息",
                        "这些内容",
                        "这几条",
                        "这条",
                        "那几条",
                        "那条",
                        "上面",
                        "前面",
                        "刚才",
                        "刚刚",
                        "上一条",
                        "这些");
            }

            private boolean hasReusableNewsSnapshot(String conversationId, NewsSnapshotState snapshot) {
                if (snapshot == null || snapshot.items() == null || snapshot.items().isEmpty()) {
                    return false;
                }
                long maxAgeMillis = Duration.ofMinutes(NEWS_SNAPSHOT_RETENTION_MINUTES).toMillis();
                boolean reusable = System.currentTimeMillis() - snapshot.storedAtEpochMillis() <= maxAgeMillis;
                if (!reusable) {
                    recentNewsSnapshots.remove(conversationId, snapshot);
                }
                return reusable;
            }

            private NewsDisplayTime resolveNewsDisplayTime(NewsItem item) {
                if (item == null) {
                    return null;
                }
                String publishTime = formatNewsTime(item.getPublishTime());
                if (hasText(publishTime)) {
                    return new NewsDisplayTime("发布时间", publishTime);
                }
                String fetchedAt = formatNewsTime(item.getFetchedAt());
                if (hasText(fetchedAt)) {
                    return new NewsDisplayTime("抓取时间", fetchedAt);
                }
                return null;
            }

            private String formatNewsTime(String rawTime) {
                if (!hasText(rawTime)) {
                    return "";
                }
                try {
                    return OffsetDateTime.parse(rawTime, DateTimeFormatter.ISO_OFFSET_DATE_TIME)
                            .atZoneSameInstant(NEWS_DISPLAY_ZONE)
                            .format(NEWS_DATE_TIME_FORMAT);
                } catch (DateTimeParseException ignored) {
                }
                try {
                    return ZonedDateTime.parse(rawTime, DateTimeFormatter.RFC_1123_DATE_TIME)
                            .withZoneSameInstant(NEWS_DISPLAY_ZONE)
                            .format(NEWS_DATE_TIME_FORMAT);
                } catch (DateTimeParseException ignored) {
                }
                try {
                    return LocalDate.parse(rawTime, DateTimeFormatter.ISO_DATE)
                            .format(NEWS_DATE_FORMAT);
                } catch (DateTimeParseException ignored) {
                }
                return rawTime;
            }

    private boolean isFollowUpQuery(String lower) {
        return containsKeyword(lower,
                "后续",
                "进展",
                "跟进",
                "延续",
                "老新闻",
                "提过",
                "新进展",
                "后面怎么样",
                "后面咋样");
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

    private String defaultText(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private String resolveRequestedLayer(String lower) {
        if (containsKeyword(lower, "热搜", "热榜", "热点")) {
            return "hotlist";
        }
        if (containsKeyword(lower, "新闻", "最近", "最新", "发生了什么")) {
            return "news";
        }
        return "all";
    }

    private String resolveRequestedCategory(String lower) {
        if (containsKeyword(lower, "ai", "人工智能", "大模型", "模型", "芯片", "科技", "技术", "互联网", "开源", "编程", "软件", "英伟达", "nvidia", "苹果", "apple", "谷歌", "google")) {
            return "tech";
        }
        return "all";
    }

    private boolean containsKeyword(String text, String... keywords) {
        return List.of(keywords).stream().anyMatch(text::contains);
    }

    private boolean isWeatherQuery(String content) {
        String lower = content.toLowerCase();
        return containsKeyword(lower, "天气", "气温", "温度", "空气质量", "aqi", "下雨", "冷不冷", "热不热", "风大", "穿什么");
    }

    private String detectRequestedCity(String content) {
        return List.of("北京", "上海", "广州", "深圳", "杭州", "成都", "武汉", "南京", "西安", "重庆", "天津", "长沙", "苏州", "郑州", "青岛", "厦门")
                .stream()
                .filter(content::contains)
                .findFirst()
                .orElse(null);
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

    private String displaySourceType(String sourceType) {
        return switch (defaultText(sourceType, "all")) {
            case "news" -> "新闻源";
            case "hotlist" -> "热榜源";
            case "all" -> "全部来源";
            default -> sourceType;
        };
    }

    private String displayCategory(String category) {
        return switch (defaultText(category, "all")) {
            case "tech" -> "科技";
            case "general" -> "综合";
            case "all" -> "全部分类";
            default -> category;
        };
    }

    private String displayTrustLevel(String trustLevel) {
        return switch (defaultText(trustLevel, "unknown")) {
            case "official_rss" -> "官方 RSS";
            case "aggregated" -> "聚合来源";
            case "community" -> "社区热榜";
            default -> trustLevel;
        };
    }

    private void remember(String conversationId, String userText, String assistantText) {
        Deque<AssistantMessage> deque = conversations.computeIfAbsent(conversationId, key -> new ArrayDeque<>());
        synchronized (deque) {
            deque.addLast(AssistantMessage.builder().role("user").content(limit(userText, 500)).build());
            deque.addLast(AssistantMessage.builder().role("assistant").content(limit(assistantText, 1000)).build());
            while (deque.size() > MAX_HISTORY_MESSAGES) {
                deque.removeFirst();
            }
        }
    }

    private List<AssistantMessage> snapshot(String conversationId) {
        Deque<AssistantMessage> deque = conversations.computeIfAbsent(conversationId, key -> new ArrayDeque<>());
        synchronized (deque) {
            return List.copyOf(deque);
        }
    }

    private boolean sendToQq(String scene, String targetId, String reply, String msgId) {
        if ("group".equals(scene)) {
            return pusher.replyToQqGroup(targetId, reply, msgId);
        }
        return pusher.replyToQqUser(targetId, reply, msgId);
    }

    private boolean isHotNewsCommand(String content) {
        return List.of("今日热点", "热点", "/hot", "hot", "news")
                .stream()
                .anyMatch(content::equalsIgnoreCase);
    }

    private boolean isHelpCommand(String content) {
        return List.of("帮助", "help", "/help")
                .stream()
                .anyMatch(content::equalsIgnoreCase);
    }

    private boolean isClearCommand(String content) {
        return List.of("清空", "清空上下文", "clear", "/clear")
                .stream()
                .anyMatch(content::equalsIgnoreCase);
    }

    private String helpText() {
        return "可用方式：\n"
                + "1. 发“今日热点”查看最新热点列表\n"
                + "2. 发“最近有什么新闻”查看新闻源层的数据\n"
                + "3. 发“分析 + 关键词”让我按主题展开\n"
                + "4. 发“今天天气怎么样”查看配置城市天气\n"
                + "5. 发“清空上下文”重置会话";
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

    private record NewsSnapshotDecision(boolean requiresFreshNews, boolean requiresAnyNewsContext, boolean reusesPreviousSnapshot, boolean followUpRequested, String requestedLayer, String requestedCategory, List<NewsItem> items) {
        private static NewsSnapshotDecision none() {
            return new NewsSnapshotDecision(false, false, false, false, "all", "all", List.of());
        }

        private boolean hasSnapshot() {
            return items != null && !items.isEmpty();
        }
    }

    private record NewsSnapshotState(String requestedLayer, String requestedCategory, List<NewsItem> items, long storedAtEpochMillis) {
    }

    private record NewsDisplayTime(String label, String value) {
    }

    private record ReplyPlan(String reply, List<NewsItem> newsSnapshot, String mediaType, String mediaPath, String mediaCaption) {
        private static ReplyPlan of(String reply) {
            return new ReplyPlan(reply, List.of(), null, null, null);
        }
    }
}