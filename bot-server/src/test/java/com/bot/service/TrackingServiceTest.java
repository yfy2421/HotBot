package com.bot.service;

import com.bot.client.PythonMLClient;
import com.bot.model.FetchResult;
import com.bot.model.NewsItem;
import com.bot.model.SystemAlert;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TrackingServiceTest {

    @Test
    void queryOnlyFollowUpDoesNotWriteBackToChroma() {
        PythonMLClient mlClient = mock(PythonMLClient.class);
        TrackingService service = new TrackingService(mlClient);
        NewsItem newsItem = NewsItem.builder().id("n1").title("测试新闻").build();

        when(mlClient.embedWithAlert(anyList(), anyString(), anyString(), anyString()))
            .thenReturn(FetchResult.of(List.of(List.of(0.1f, 0.2f))));
        when(mlClient.querySimilarWithAlert(anyList(), any(Integer.class), any(Double.class), anyString(), anyString(), anyString()))
            .thenReturn(FetchResult.of(List.of()));

        service.findRecentFollowUpsWithAlerts(List.of(newsItem), 1);

        verify(mlClient, never()).addNewsWithAlert(anyString(), anyString(), anyList(), anyMap(), anyString(), anyString(), anyString());
        assertTrue(newsItem.getFollowUpTag() == null);
    }

        @Test
        void returnsMlAlertsFromUnifiedResultModel() {
        PythonMLClient mlClient = mock(PythonMLClient.class);
        TrackingService service = new TrackingService(mlClient);
        NewsItem newsItem = NewsItem.builder().id("n1").title("测试新闻").build();

        when(mlClient.embedWithAlert(anyList(), anyString(), anyString(), anyString())).thenReturn(
            FetchResult.of(List.of(), List.of(SystemAlert.warn("TrackingService", "SHORT_TERM_TRACKING_FAILED",
                "测试新闻 -> embed/ResourceAccessException: timeout"))));

        List<SystemAlert> alerts = service.shortTermTrackingWithAlerts(List.of(newsItem));

        assertEquals(1, alerts.size());
        assertEquals("SHORT_TERM_TRACKING_FAILED", alerts.get(0).code());
        verify(mlClient, never()).querySimilarWithAlert(anyList(), any(Integer.class), any(Double.class), anyString(), anyString(), anyString());
        }
}