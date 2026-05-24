package com.bot.service;

import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

final class IntentKeywords {

    static final List<String> HOTLIST_WORDS = List.of("热搜", "热榜", "热点");
    static final List<String> OVERVIEW_NEWS_WORDS = List.of("热点", "新闻", "热搜", "热榜");
    static final List<String> OVERVIEW_VERBS = List.of("今日", "今天", "最近", "最新", "有什么", "有哪些", "来点", "看看", "看下", "发生了什么");
    static final List<String> WEATHER_WORDS = List.of("天气", "气温", "温度", "空气质量", "aqi", "下雨", "冷不冷", "热不热", "风大", "穿什么");
    static final List<String> ANALYSIS_WORDS = List.of("分析", "解读");
    static final List<String> FOLLOW_UP_WORDS = List.of("后续", "进展", "跟进", "延续", "老新闻", "提过", "新进展", "后面怎么样", "后面咋样");
    static final List<String> REQUIRES_FRESH_NEWS_WORDS = List.of("热点", "新闻", "热搜", "追踪", "趋势", "发生了什么", "最近", "最新");
    static final List<String> NEWS_LAYER_WORDS = List.of("新闻", "最近", "最新", "发生了什么");
    static final List<String> CLEAR_COMMANDS = List.of("清空", "清空上下文", "clear", "/clear");
    static final List<String> HELP_COMMANDS = List.of("帮助", "help", "/help");
    static final List<String> HOT_NEWS_COMMANDS = List.of("今日热点", "热点", "/hot", "hot", "news");
    static final List<String> SNAPSHOT_REFERENCE_WORDS = List.of(
            "这些新闻", "这些热点", "这些消息", "这些内容", "这个热点", "这个新闻", "这个话题",
            "这篇论文", "这几条", "这条", "那几条", "那条", "上面", "前面", "刚才", "刚刚", "上一条", "这些");
    static final List<String> SINGULAR_REFERENCE_WORDS = List.of(
            "这个热点", "这个新闻", "这个话题", "这个产品", "这个政策", "这条热点", "这条新闻",
            "这条消息", "这条", "这篇论文", "这篇", "这件事");
    static final List<String> FOCUSED_ITEM_REFERENCE_WORDS = List.of(
            "原文", "正文", "全文", "详情", "完整", "详细内容", "完整内容", "看原文", "看正文",
            "看全文", "看详情", "看完整", "原文呢", "正文呢", "全文呢");

    static final List<String> ANALYSIS_PAPER_WORDS = List.of("arxiv", "论文", "paper", "study", "research", "preprint", "研究");
    static final List<String> ANALYSIS_POLICY_WORDS = List.of("政策", "监管", "法案", "条例", "通知", "意见", "办法", "国务院", "工信部", "发改委", "证监会");
    static final List<String> ANALYSIS_PRODUCT_WORDS = List.of("发布", "上线", "推出", "产品", "模型", "版本", "应用", "平台", "开源", "芯片", "软件");
    static final List<String> ANALYSIS_COMMUNITY_WORDS = List.of("知乎日报", "掘金热榜", "冷知识", "问答", "分钟阅读", "教程", "指南", "盘点", "经验", "为什么", "是什么", "如何", "正确");
    static final List<String> ANALYSIS_COMMUNITY_SOURCE_WORDS = List.of("知乎日报", "掘金热榜", "小众软件");

    static final List<String> DETAIL_SELECTION_ACTION_WORDS = List.of("回复", "查看", "看看", "看下", "点开", "打开", "详情", "切到", "切换到", "分析", "解读", "看", "选", "选择");
    static final List<String> DETAIL_SELECTION_REFERENCE_PREFIX_WORDS = List.of(
            "这个热点", "这个新闻", "这个话题", "这条热点", "这条新闻", "这条消息", "这条", "这篇论文", "这篇",
            "这个产品", "这个政策", "这个", "那个热点", "那个新闻", "那个话题", "那条热点", "那条新闻",
            "那条消息", "那条", "那篇论文", "那篇", "那个产品", "那个政策", "那个");
    static final List<String> DETAIL_SELECTION_SUFFIX_NOISE_WORDS = List.of(
            "的原文", "的正文", "的全文", "原文", "正文", "全文",
            "那个", "这个", "那篇", "这篇", "那条", "这条", "详情", "热点", "新闻", "看看", "看下", "瞅瞅", "一下");
    static final List<String> DETAIL_SELECTION_TOKEN_NOISE_WORDS = List.of(
            "的原文", "的正文", "的全文",
            "这个", "那个", "这条", "那条", "这篇", "那篇", "热点", "新闻", "详情", "看看", "看下", "瞅瞅", "一下");

    private static final List<CategoryKeywords> REQUEST_CATEGORY_KEYWORDS = List.of(
            new CategoryKeywords("tech_science", List.of(
                    "techcrunch", "hackernews", "掘金", "小众软件", "ai", "人工智能", "大模型", "模型", "芯片", "技术",
                    "科技", "互联网", "开源", "编程", "软件", "英伟达", "nvidia", "苹果", "apple", "谷歌",
                    "google", "航天", "spacex", "space", "火箭")),
            new CategoryKeywords("humanities_nature", List.of(
                    "人文", "自然", "地理", "历史", "文化", "科普", "考古", "动物", "植物", "国旗",
                    "国歌", "国徽", "博物", "冷知识", "科学")),
            new CategoryKeywords("entertainment", List.of(
                    "娱乐", "明星", "电影", "电视剧", "综艺", "音乐", "演唱会", "游戏", "八卦", "票房", "偶像")),
            new CategoryKeywords("current_affairs", List.of(
                    "时事", "社会", "国际", "外交", "政策", "财经", "经济", "洪水", "事故", "会议"))
    );

    private static final List<CategoryKeywords> OVERVIEW_CATEGORY_KEYWORDS = List.of(
            new CategoryKeywords("tech_science", List.of(
                    "techcrunch", "hackernews", "掘金", "小众软件", "ai", "人工智能", "大模型", "芯片", "技术",
                    "科技", "软件", "开源", "space", "spacex", "火箭", "航天")),
            new CategoryKeywords("humanities_nature", List.of(
                    "地理", "自然", "动物", "植物", "考古", "历史", "文化", "国旗", "国歌", "国徽", "冷知识", "科学", "人文", "博物")),
            new CategoryKeywords("entertainment", List.of(
                    "娱乐", "明星", "电影", "电视剧", "综艺", "音乐", "演唱会", "票房", "偶像", "游戏"))
    );

    private static final Pattern DETAIL_SELECTION_ACTION_PREFIX_PATTERN = compilePrefixPattern(DETAIL_SELECTION_ACTION_WORDS);
    private static final Pattern DETAIL_SELECTION_REFERENCE_PREFIX_PATTERN = compilePrefixPattern(DETAIL_SELECTION_REFERENCE_PREFIX_WORDS);
    private static final Pattern DETAIL_SELECTION_SUFFIX_NOISE_PATTERN = compileRepeatedPattern(DETAIL_SELECTION_SUFFIX_NOISE_WORDS, true);
    private static final Pattern DETAIL_SELECTION_TOKEN_NOISE_PATTERN = compileRepeatedPattern(DETAIL_SELECTION_TOKEN_NOISE_WORDS, false);

    private IntentKeywords() {
    }

    static boolean containsAny(String text, List<String> keywords) {
        String normalized = normalize(text);
        return !normalized.isEmpty() && keywords.stream().anyMatch(normalized::contains);
    }

    static boolean matchesCommand(String text, List<String> commands) {
        return commands.contains(normalize(text));
    }

    static boolean isClearCommand(String text) {
        return matchesCommand(text, CLEAR_COMMANDS);
    }

    static boolean isHelpCommand(String text) {
        return matchesCommand(text, HELP_COMMANDS);
    }

    static boolean isWeatherQuery(String text) {
        return containsAny(text, WEATHER_WORDS);
    }

    static boolean isAnalysisQuery(String text) {
        return containsAny(text, ANALYSIS_WORDS);
    }

    static boolean isFollowUpQuery(String text) {
        return containsAny(text, FOLLOW_UP_WORDS);
    }

    static boolean isHotNewsCommand(String text) {
        return matchesCommand(text, HOT_NEWS_COMMANDS);
    }

    static boolean requiresFreshNews(String text) {
        return containsAny(text, REQUIRES_FRESH_NEWS_WORDS);
    }

    static boolean containsOverviewNewsWord(String text) {
        return containsAny(text, OVERVIEW_NEWS_WORDS);
    }

    static boolean containsOverviewVerb(String text) {
        return containsAny(text, OVERVIEW_VERBS);
    }

    static boolean containsHotlistWord(String text) {
        return containsAny(text, HOTLIST_WORDS);
    }

    static boolean usesNewsOverviewLabel(String text) {
        String normalized = normalize(text);
        return normalized.contains("新闻") && !containsHotlistWord(normalized);
    }

    static String resolveRequestedLayer(String text) {
        if (containsHotlistWord(text)) {
            return "hotlist";
        }
        if (containsAny(text, NEWS_LAYER_WORDS)) {
            return "news";
        }
        return "all";
    }

    static String resolveRequestedCategory(String text) {
        return resolveCategory(text, REQUEST_CATEGORY_KEYWORDS, "all");
    }

    static String classifyOverviewCategory(String text) {
        return resolveCategory(text, OVERVIEW_CATEGORY_KEYWORDS, "current_affairs");
    }

    static boolean referencesPreviousSnapshot(String text) {
        return containsAny(text, SNAPSHOT_REFERENCE_WORDS);
    }

    static boolean isSingularNewsReference(String text) {
        return containsAny(text, SINGULAR_REFERENCE_WORDS);
    }

    static boolean isFocusedItemReference(String text) {
        String normalized = normalize(text);
        for (String word : FOCUSED_ITEM_REFERENCE_WORDS) {
            if (normalized.equals(word)) {
                return true;
            }
        }
        return normalized.length() <= 4
                && (normalized.contains("原文") || normalized.contains("正文") || normalized.contains("全文"));
    }

    static boolean isPaperAnalysisContent(String text) {
        return containsAny(text, ANALYSIS_PAPER_WORDS);
    }

    static boolean isPolicyAnalysisContent(String text) {
        return containsAny(text, ANALYSIS_POLICY_WORDS);
    }

    static boolean isProductAnalysisContent(String text) {
        return containsAny(text, ANALYSIS_PRODUCT_WORDS);
    }

    static boolean isCommunityAnalysisContent(String text, String sourceType, String source) {
        return containsAny(text, ANALYSIS_COMMUNITY_WORDS)
                || ("hotlist".equals(normalize(sourceType)) && containsAny(source, ANALYSIS_COMMUNITY_SOURCE_WORDS));
    }

    static String simplifyDetailSelectionText(String text) {
        String normalized = normalize(text);
        normalized = DETAIL_SELECTION_ACTION_PREFIX_PATTERN.matcher(normalized).replaceFirst("");
        normalized = DETAIL_SELECTION_REFERENCE_PREFIX_PATTERN.matcher(normalized).replaceFirst("");
        normalized = DETAIL_SELECTION_SUFFIX_NOISE_PATTERN.matcher(normalized).replaceAll("");
        return normalized.trim();
    }

    static String stripDetailSelectionTokenNoise(String text) {
        return DETAIL_SELECTION_TOKEN_NOISE_PATTERN.matcher(normalize(text)).replaceAll("");
    }

    private static String resolveCategory(String text, List<CategoryKeywords> categories, String fallback) {
        String normalized = normalize(text);
        for (CategoryKeywords categoryKeywords : categories) {
            if (categoryKeywords.matches(normalized)) {
                return categoryKeywords.category();
            }
        }
        return fallback;
    }

    private static Pattern compilePrefixPattern(List<String> keywords) {
        return Pattern.compile("^(?:" + joinAlternation(keywords) + ")\\s*");
    }

    private static Pattern compileRepeatedPattern(List<String> keywords, boolean trailingOnly) {
        String suffix = trailingOnly ? "$" : "";
        return Pattern.compile("(?:" + joinAlternation(keywords) + ")+" + suffix);
    }

    private static String joinAlternation(List<String> keywords) {
        return keywords.stream()
                .sorted(Comparator.comparingInt(String::length).reversed())
                .map(Pattern::quote)
                .collect(Collectors.joining("|"));
    }

    private static String normalize(String text) {
        return text == null ? "" : text.trim().toLowerCase(Locale.ROOT);
    }

    private record CategoryKeywords(String category, List<String> keywords) {
        private boolean matches(String text) {
            return !text.isEmpty() && keywords.stream().anyMatch(text::contains);
        }
    }
}