package com.bot.model;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NewsItemTest {

    @Test
    void preferredDetailTextPrefersFullBodyAndFallsBackToExcerpt() {
        NewsItem item = NewsItem.builder()
                .detailExcerpt("详情摘录")
                .fullBody("完整正文")
                .build();

        assertEquals("完整正文", item.preferredDetailText());

        item.setFullBody(null);

        assertEquals("详情摘录", item.preferredDetailText());
    }

    @Test
    void translatedPreferredDetailTextPrefersTranslatedFullBodyAndFallsBackToExcerpt() {
        NewsItem item = NewsItem.builder()
                .translatedDetailExcerpt("详情摘录译文")
                .translatedFullBody("完整正文译文")
                .build();

        assertEquals("完整正文译文", item.translatedPreferredDetailText());

        item.setTranslatedFullBody(null);

        assertEquals("详情摘录译文", item.translatedPreferredDetailText());
    }

    @Test
    void promoteLegacyDetailExcerptBackfillsLegacyOnlyField() {
        NewsItem item = NewsItem.builder()
                .detailContent("legacy 详情字段")
                .build();

        assertTrue(item.hasLegacyOnlyDetailText());
        assertTrue(item.promoteLegacyDetailExcerpt());
        assertEquals("legacy 详情字段", item.getDetailExcerpt());
    }

    @Test
    void legacyPromotionDoesNotOverwriteExistingExcerpt() {
        NewsItem item = NewsItem.builder()
                .detailExcerpt("新详情摘录")
                .detailContent("旧 detailContent")
                .build();

        assertFalse(item.hasLegacyOnlyDetailText());
        assertFalse(item.promoteLegacyDetailExcerpt());
        assertEquals("新详情摘录", item.getDetailExcerpt());
    }
}