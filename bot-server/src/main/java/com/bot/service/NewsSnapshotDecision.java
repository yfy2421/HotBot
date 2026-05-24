package com.bot.service;

import com.bot.model.NewsItem;

import java.util.List;

record NewsSnapshotDecision(boolean requiresFreshNews,
                            boolean requiresAnyNewsContext,
                            boolean reusesPreviousSnapshot,
                            boolean followUpRequested,
                            boolean analysisRequested,
                            String requestedLayer,
                            String requestedCategory,
                            List<NewsItem> items) {

    static NewsSnapshotDecision none() {
        return new NewsSnapshotDecision(false, false, false, false, false, "all", "all", List.of());
    }

    boolean hasSnapshot() {
        return items != null && !items.isEmpty();
    }

    NewsSnapshotDecision withItems(List<NewsItem> scopedItems) {
        return new NewsSnapshotDecision(
                requiresFreshNews,
                requiresAnyNewsContext,
                reusesPreviousSnapshot,
                followUpRequested,
                analysisRequested,
                requestedLayer,
                requestedCategory,
                scopedItems == null ? List.of() : scopedItems
        );
    }
}