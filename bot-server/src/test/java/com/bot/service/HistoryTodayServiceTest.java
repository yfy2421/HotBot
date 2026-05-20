package com.bot.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HistoryTodayServiceTest {

    @Test
    void returnsConfiguredSummaryForKnownDate() {
        HistoryTodayService service = new HistoryTodayService(new ObjectMapper());

        String summary = service.getSummary(LocalDate.of(2026, 5, 20));

        assertNotNull(summary);
        assertTrue(summary.contains("米制公约"));
    }

    @Test
    void returnsNullForMissingDate() {
        HistoryTodayService service = new HistoryTodayService(new ObjectMapper());

        String summary = service.getSummary(LocalDate.of(2026, 2, 28));

        assertNull(summary);
    }
}