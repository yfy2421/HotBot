package com.bot.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

import static com.bot.util.TextUtils.hasText;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class NewsItem {
    private String id;
    private String title;
    private String summary;
    private String translatedSummaryPreview;
    private String detailExcerpt;
    private String fullBody;
    private String translatedDetailExcerpt;
    private String translatedFullBody;
    private String url;
    private String source;
    private String sourceType;
    private String category;
    private String trustLevel;
    private String publishTime;
    private String fetchedAt;
    private String discussionUrl;
    private String detailContent;

    public String summaryPreviewText() {
        return hasText(summary) ? summary : "";
    }

    public String translatedSummaryPreviewText() {
        return hasText(translatedSummaryPreview) ? translatedSummaryPreview : "";
    }

    public String resolvedDetailExcerpt() {
        return hasText(detailExcerpt) ? detailExcerpt : "";
    }

    public String preferredDetailText() {
        return hasText(fullBody) ? fullBody : resolvedDetailExcerpt();
    }

    public String translatedPreferredDetailText() {
        if (hasText(translatedFullBody)) {
            return translatedFullBody;
        }
        return hasText(translatedDetailExcerpt) ? translatedDetailExcerpt : "";
    }

    public boolean hasLegacyOnlyDetailText() {
        return !hasText(detailExcerpt) && hasText(detailContent);
    }

    public boolean promoteLegacyDetailExcerpt() {
        if (!hasLegacyOnlyDetailText()) {
            return false;
        }
        detailExcerpt = detailContent;
        return true;
    }

    // AI evaluation
    private String commentary;

    // Sentiment analysis
    private String sentiment;

    // Tags
    private List<String> tags;

    // Short-term follow-up tracking
    private String followUpOf;   // previous news id this follows
    private String followUpTag;  // e.g. "📌 后续: <previous title>"

    // Long-term topic tracking
    private String topicLine;    // e.g. "「GPT系列」GPT-4(2023) → GPT-5(2026)"
}
