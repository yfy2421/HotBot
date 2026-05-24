package com.bot.service;

import com.bot.model.NewsItem;
import com.bot.util.NewsDisplayText;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.BiFunction;
import java.util.function.Function;

import static com.bot.util.TextUtils.defaultText;
import static com.bot.util.TextUtils.hasText;

class ReplyRenderer {

    private static final String DETAIL_CARD_PROMPT = "继续回复“分析这条”“看后续”，也可直接发别的标题切换条目。";

    private final NewsCardRenderer newsCardRenderer;
    private final int newsSnapshotLimit;
    private final int chatFollowUpLimit;
    private final Function<NewsItem, String> newsDisplayTimeTextResolver;
    private final BiFunction<NewsItem, Map<String, String>, String> displayTitleResolver;
    private final BiFunction<String, String, String> markdownLinkFormatter;

    ReplyRenderer(NewsCardRenderer newsCardRenderer,
                  int newsSnapshotLimit,
                  int chatFollowUpLimit,
                  Function<NewsItem, String> newsDisplayTimeTextResolver,
                  BiFunction<NewsItem, Map<String, String>, String> displayTitleResolver,
                  BiFunction<String, String, String> markdownLinkFormatter) {
        this.newsCardRenderer = newsCardRenderer;
        this.newsSnapshotLimit = newsSnapshotLimit;
        this.chatFollowUpLimit = chatFollowUpLimit;
        this.newsDisplayTimeTextResolver = newsDisplayTimeTextResolver;
        this.displayTitleResolver = displayTitleResolver;
        this.markdownLinkFormatter = markdownLinkFormatter;
    }

    ReplyPlan buildOverviewReplyPlan(String content,
                                     String requestedCategory,
                                     List<NewsItem> items,
                                     Map<String, String> translatedTitles,
                                     Map<String, String> translatedSummaries) {
        String reply = buildOverviewReplyText(content, items == null ? 0 : items.size(), requestedCategory);
        String mediaPath = newsCardRenderer.renderOverviewCard(
                buildOverviewCardTitle(content),
                buildOverviewCardSubtitle(requestedCategory, items == null ? 0 : items.size()),
                items,
                translatedTitles,
                translatedSummaries,
                buildSelectionPrompt(items == null ? 0 : items.size())
        );
        if (!hasText(mediaPath)) {
            return new ReplyPlan(reply, items == null ? List.of() : items, null, null, null);
        }
        return new ReplyPlan(reply, items == null ? List.of() : items, "image", mediaPath, null);
    }

    ReplyPlan buildDetailSnapshotPlan(List<NewsItem> snapshotItems,
                                      NewsItem item,
                                      Map<String, String> translatedTitles,
                                      Map<String, String> translatedSummaries) {
        String reply = buildDetailSnapshotReply(item);
        return buildDetailPlan(reply, snapshotItems, item, translatedTitles, translatedSummaries);
    }

    ReplyPlan buildDetailSwitchPlan(List<NewsItem> snapshotItems,
                                    NewsItem item,
                                    Map<String, String> translatedTitles,
                                    Map<String, String> translatedSummaries) {
        String reply = buildDetailSwitchReply(item);
        return buildDetailPlan(reply, snapshotItems, item, translatedTitles, translatedSummaries);
    }

    String buildScopedItemClarification(int itemCount) {
        int maxIndex = Math.max(1, Math.min(itemCount, newsSnapshotLimit));
        return "当前快照里有多条内容。你可以回复 1-" + maxIndex + "、标题关键词，或直接说“分析 1”。";
    }

    String buildNewsGroundedReply(boolean followUpRequested,
                                  List<NewsItem> newsSnapshot,
                                  Map<String, String> translatedTitles,
                                  String aiReply) {
        String conclusion = aiReply == null ? "" : aiReply.trim();
        String followUpSection = followUpRequested
                ? buildFollowUpSection(newsSnapshot, translatedTitles)
                : "";
        List<String> sections = new ArrayList<>();
        if (hasText(followUpSection)) {
            sections.add("## 近 7 天后续追踪\n\n" + followUpSection);
        }
        if (hasText(conclusion)) {
            sections.add(conclusion);
        }
        if (!sections.isEmpty()) {
            return String.join("\n\n", sections);
        }
        return followUpRequested
                ? "基于当前快照，暂时没有明确的新后续线索。"
                : "基于当前快照，暂时没生成新的分析结论。";
    }

    private ReplyPlan buildDetailPlan(String reply,
                                      List<NewsItem> snapshotItems,
                                      NewsItem item,
                                      Map<String, String> translatedTitles,
                                      Map<String, String> translatedSummaries) {
        String mediaPath = newsCardRenderer.renderDetailCard(
                buildDetailCardTitle(item),
                buildDetailCardSubtitle(item),
                item,
                translatedTitles,
                translatedSummaries,
                DETAIL_CARD_PROMPT
        );
        if (!hasText(mediaPath)) {
            return new ReplyPlan(reply, snapshotItems == null ? List.of() : snapshotItems, null, null, null);
        }
        return new ReplyPlan(reply, snapshotItems == null ? List.of() : snapshotItems, "image", mediaPath, null);
    }

    private String buildOverviewReplyText(String content, int itemCount, String requestedCategory) {
        String lower = defaultText(content, "").toLowerCase();
        String prefix = IntentKeywords.usesNewsOverviewLabel(lower)
                ? "已整理成新闻总览长图。"
                : "已整理成热点总览长图。";
        if (!"all".equals(defaultText(requestedCategory, "all"))) {
            prefix = "已整理成“" + NewsDisplayText.displayCategory(requestedCategory) + "”总览长图。";
        }
        return prefix + buildSelectionPrompt(itemCount);
    }

    private String buildOverviewCardTitle(String content) {
        String lower = defaultText(content, "").toLowerCase();
        return IntentKeywords.usesNewsOverviewLabel(lower) ? "今日新闻总览" : "今日热点总览";
    }

    private String buildOverviewCardSubtitle(String requestedCategory, int itemCount) {
        return "all".equals(defaultText(requestedCategory, "all"))
                ? "按内容归类 · 共 " + itemCount + " 条"
                : NewsDisplayText.displayCategory(requestedCategory) + " · 共 " + itemCount + " 条";
    }

    private String buildSelectionPrompt(int itemCount) {
        int maxIndex = Math.max(1, Math.min(itemCount, newsSnapshotLimit));
        return "回复 1-" + maxIndex + " 或大致标题查看详情，也可直接说“分析 1”。";
    }

    private String buildDetailCardTitle(NewsItem item) {
        return isHotlist(item) ? "热点详情" : "新闻详情";
    }

    private String buildDetailCardSubtitle(NewsItem item) {
        List<String> parts = new ArrayList<>();
        String displayTime = newsDisplayTimeTextResolver == null ? "" : newsDisplayTimeTextResolver.apply(item);
        if (hasText(displayTime)) {
            parts.add(displayTime);
        }
        parts.add(defaultText(item == null ? null : item.getSource(), "未知来源"));
        parts.add(NewsDisplayText.displayCategory(item == null ? null : item.getCategory()));
        parts.add(NewsDisplayText.displayTrustLevel(item == null ? null : item.getTrustLevel()));
        return String.join(" · ", parts);
    }

    private String buildDetailSnapshotReply(NewsItem item) {
        return "已整理成这条" + detailLabel(item) + "详情图。继续回复“分析这条”“看后续”，也可直接发别的标题切换条目。";
    }

    private String buildDetailSwitchReply(NewsItem item) {
        return "已切到这条" + detailLabel(item) + "详情。继续回复“分析这条”“看后续”，也可直接发别的标题切换条目。";
    }

    private String detailLabel(NewsItem item) {
        return isHotlist(item) ? "热点" : "新闻";
    }

    private boolean isHotlist(NewsItem item) {
        return "hotlist".equals(defaultText(item == null ? null : item.getSourceType(), "all"));
    }

    private String buildFollowUpSection(List<NewsItem> newsSnapshot, Map<String, String> translatedTitles) {
        if (newsSnapshot == null || newsSnapshot.isEmpty()) {
            return "";
        }

        List<NewsItem> scopedItems = newsSnapshot.stream()
                .limit(chatFollowUpLimit)
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
            String displayTitle = displayTitleResolver == null ? defaultText(item.getTitle(), "无标题") : displayTitleResolver.apply(item, translatedTitles);
            sb.append(i + 1)
                    .append(". ")
                    .append(formatMarkdownLink(displayTitle, item == null ? null : item.getUrl()))
                    .append("\n   - ")
                    .append(item.getFollowUpTag())
                    .append("\n");
        }
        return sb.toString().trim();
    }

    private String formatMarkdownLink(String text, String url) {
        if (markdownLinkFormatter == null) {
            return defaultText(text, "无标题");
        }
        return markdownLinkFormatter.apply(text, url);
    }

}