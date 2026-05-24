package com.bot.service;

import com.bot.model.NewsItem;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;

import static com.bot.util.TextUtils.defaultText;

class NewsSnapshotManager {

    private final NewsService newsService;
    private final IntentRouter intentRouter;
    private final int newsSnapshotLimit;
    private final Function<List<NewsItem>, List<NewsItem>> snapshotItemsCopier;
    private final Function<NewsItem, String> detailExcerptResolver;

    NewsSnapshotManager(NewsService newsService,
                        IntentRouter intentRouter,
                        int newsSnapshotLimit,
                        Function<List<NewsItem>, List<NewsItem>> snapshotItemsCopier,
                        Function<NewsItem, String> detailExcerptResolver) {
        this.newsService = newsService;
        this.intentRouter = intentRouter;
        this.newsSnapshotLimit = newsSnapshotLimit;
        this.snapshotItemsCopier = snapshotItemsCopier;
        this.detailExcerptResolver = detailExcerptResolver;
    }

    NewsSnapshotDecision resolveNewsSnapshot(String content, String forcedLayer) {
        IntentRouter.NewsIntent newsIntent = intentRouter.resolveNewsIntent(content, forcedLayer);
        boolean requiresFreshNews = newsIntent.requiresFreshNews();
        boolean requestedAnalysis = newsIntent.analysisRequested();
        boolean followUpRequested = newsIntent.followUpRequested();
        boolean needsNewsContext = requiresFreshNews || requestedAnalysis || followUpRequested;
        if (!needsNewsContext) {
            return NewsSnapshotDecision.none();
        }

        String requestedLayer = newsIntent.requestedLayer();
        String requestedCategory = newsIntent.requestedCategory();
        try {
            String fetchCategory = resolveFetchCategory(requestedCategory);
            List<NewsItem> newsList = copySnapshotItems(newsService.fetchLayered(requestedLayer, fetchCategory));
            if (!"all".equals(requestedCategory)) {
                newsList = newsList.stream()
                        .map(this::copyForOverview)
                        .filter(item -> matchesOverviewCategory(item, requestedCategory))
                        .toList();
            }
            return new NewsSnapshotDecision(requiresFreshNews, needsNewsContext, false, followUpRequested, requestedAnalysis, requestedLayer, requestedCategory, newsList);
        } catch (Exception e) {
            return new NewsSnapshotDecision(requiresFreshNews, needsNewsContext, false, followUpRequested, requestedAnalysis, requestedLayer, requestedCategory, List.of());
        }
    }

    NewsSnapshotDecision resolveOverviewSnapshot(String content) {
        IntentRouter.NewsIntent newsIntent = intentRouter.resolveNewsIntent(content, "overview");
        String requestedCategory = newsIntent.requestedCategory();
        List<NewsItem> items = selectOverviewItems(requestedCategory);
        return new NewsSnapshotDecision(true, true, false, false, false, "overview", requestedCategory, items);
    }

    private List<NewsItem> selectOverviewItems(String requestedCategory) {
        List<NewsItem> candidates = fetchOverviewCandidates().stream()
                .map(this::copyForOverview)
                .toList();
        if (candidates.isEmpty()) {
            return List.of();
        }

        List<NewsItem> filtered = candidates.stream()
                .filter(item -> matchesOverviewCategory(item, requestedCategory))
                .toList();
        if (!filtered.isEmpty() && !"all".equals(defaultText(requestedCategory, "all"))) {
            return filtered.stream().limit(newsSnapshotLimit).toList();
        }

        List<NewsItem> source = filtered.isEmpty() ? candidates : filtered;
        if ("all".equals(defaultText(requestedCategory, "all"))) {
            return balanceOverviewItems(source);
        }
        return source.stream().limit(newsSnapshotLimit).toList();
    }

    private List<NewsItem> fetchOverviewCandidates() {
        List<NewsItem> items = sanitizeOverviewItems(newsService.fetchAll());
        if (!items.isEmpty()) {
            return items;
        }

        items = sanitizeOverviewItems(newsService.fetchLayered("all", "all"));
        if (!items.isEmpty()) {
            return items;
        }

        List<NewsItem> merged = new ArrayList<>();
        merged.addAll(sanitizeOverviewItems(newsService.fetchLayered("news", "all")));
        merged.addAll(sanitizeOverviewItems(newsService.fetchLayered("hotlist", "all")));
        return merged;
    }

    private List<NewsItem> sanitizeOverviewItems(List<NewsItem> items) {
        if (items == null || items.isEmpty()) {
            return List.of();
        }
        return items.stream().filter(Objects::nonNull).toList();
    }

    private NewsItem copyForOverview(NewsItem item) {
        if (item == null) {
            return null;
        }
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
                .category(classifyOverviewCategory(item))
                .trustLevel(item.getTrustLevel())
                .publishTime(item.getPublishTime())
                .fetchedAt(item.getFetchedAt())
                .discussionUrl(item.getDiscussionUrl())
                .commentary(item.getCommentary())
                .build();
    }

    private List<NewsItem> balanceOverviewItems(List<NewsItem> items) {
        Map<String, List<NewsItem>> buckets = new LinkedHashMap<>();
        List<String> order = List.of("current_affairs", "tech_science", "humanities_nature", "entertainment");
        order.forEach(category -> buckets.put(category, new ArrayList<>()));
        for (NewsItem item : items) {
            buckets.computeIfAbsent(defaultText(item == null ? null : item.getCategory(), "current_affairs"), key -> new ArrayList<>()).add(item);
        }

        List<NewsItem> selected = new ArrayList<>();
        int round = 0;
        while (selected.size() < newsSnapshotLimit) {
            boolean added = false;
            for (String category : order) {
                List<NewsItem> bucket = buckets.getOrDefault(category, List.of());
                if (round < bucket.size()) {
                    selected.add(bucket.get(round));
                    added = true;
                    if (selected.size() == newsSnapshotLimit) {
                        break;
                    }
                }
            }
            if (!added) {
                break;
            }
            round++;
        }
        return selected;
    }

    private boolean matchesOverviewCategory(NewsItem item, String requestedCategory) {
        return "all".equals(defaultText(requestedCategory, "all"))
                || defaultText(item == null ? null : item.getCategory(), "all").equals(requestedCategory);
    }

    private String classifyOverviewCategory(NewsItem item) {
        String text = String.join(" ",
                        defaultText(item == null ? null : item.getSource(), ""),
                        defaultText(item == null ? null : item.getTitle(), ""),
                defaultText(item == null ? null : item.summaryPreviewText(), ""),
                        defaultText(resolveDetailExcerpt(item), ""))
                .toLowerCase();
        return IntentKeywords.classifyOverviewCategory(text);
    }

    private String resolveFetchCategory(String requestedCategory) {
        return switch (defaultText(requestedCategory, "all")) {
            case "tech_science", "tech" -> "tech";
            default -> "all";
        };
    }

    private List<NewsItem> copySnapshotItems(List<NewsItem> items) {
        if (snapshotItemsCopier == null || items == null || items.isEmpty()) {
            return List.of();
        }
        List<NewsItem> copied = snapshotItemsCopier.apply(items);
        return copied == null ? List.of() : copied;
    }

    private String resolveDetailExcerpt(NewsItem item) {
        if (item == null || detailExcerptResolver == null) {
            return "";
        }
        return defaultText(detailExcerptResolver.apply(item), "");
    }

}