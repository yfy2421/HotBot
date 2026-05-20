package com.bot.service;

import com.bot.client.PythonMLClient;
import com.bot.model.NewsItem;
import org.junit.jupiter.api.Test;

import java.util.List;

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

        when(mlClient.embed(anyList())).thenReturn(List.of(List.of(0.1f, 0.2f)));
        when(mlClient.querySimilar(anyList(), any(Integer.class), any(Double.class)))
                .thenReturn(List.of());

        service.findRecentFollowUpsWithAlerts(List.of(newsItem), 1);

        verify(mlClient, never()).addNews(anyString(), anyString(), anyList(), anyMap());
        assertTrue(newsItem.getFollowUpTag() == null);
    }
}