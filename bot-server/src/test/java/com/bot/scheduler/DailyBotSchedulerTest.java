package com.bot.scheduler;

import com.bot.client.PythonMLClient;
import com.bot.model.DailyWord;
import com.bot.model.FetchResult;
import com.bot.model.JokeItem;
import com.bot.model.NewsItem;
import com.bot.model.PushMessage;
import com.bot.model.SystemAlert;
import com.bot.model.WeatherInfo;
import com.bot.service.CommentSourceService;
import com.bot.service.HistoryTodayService;
import com.bot.service.JokeService;
import com.bot.service.NewsService;
import com.bot.service.TemplateRenderer;
import com.bot.service.TrackingService;
import com.bot.service.WeChatPusher;
import com.bot.service.WeatherService;
import com.bot.service.WordService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DailyBotSchedulerTest {

    @Test
    void skipsSentimentWhenNoRealCommentsExist() {
        WeatherService weatherService = mock(WeatherService.class);
        NewsService newsService = mock(NewsService.class);
        WordService wordService = mock(WordService.class);
        JokeService jokeService = mock(JokeService.class);
        TrackingService trackingService = mock(TrackingService.class);
        CommentSourceService commentSourceService = mock(CommentSourceService.class);
        HistoryTodayService historyTodayService = mock(HistoryTodayService.class);
        TemplateRenderer renderer = mock(TemplateRenderer.class);
        WeChatPusher pusher = mock(WeChatPusher.class);
        PythonMLClient mlClient = mock(PythonMLClient.class);

        NewsItem newsItem = NewsItem.builder().id("n1").title("测试新闻").summary("摘要").build();
        WeatherInfo weather = WeatherInfo.builder().condition("晴").tempLow(20).tempHigh(30).aqiLevel("优").build();
        DailyWord word = DailyWord.builder().word("focus").definition("专注").build();
        JokeItem joke = JokeItem.builder().content("笑话").build();

        when(weatherService.fetchTodayWithAlert()).thenReturn(FetchResult.of(weather));
        when(newsService.fetchAllWithAlert()).thenReturn(FetchResult.of(List.of(newsItem)));
        when(wordService.fetchWithAlert()).thenReturn(FetchResult.of(word));
        when(jokeService.randomJoke()).thenReturn(joke);
        when(mlClient.commentaryWithAlert(anyString(), anyString(), anyString(), anyString())).thenReturn(FetchResult.of("点评"));
        when(commentSourceService.fetchCommentsWithAlert(newsItem)).thenReturn(FetchResult.of(List.of()));
        when(historyTodayService.getTodaySummary()).thenReturn("测试历史事件");
        when(renderer.render(any(PushMessage.class))).thenReturn("rendered");
        when(trackingService.longTermTrackingWithAlerts(any())).thenReturn(new TrackingService.TrackingBatchResult(List.of(), List.of()));
        when(trackingService.shortTermTrackingWithAlerts(any())).thenReturn(List.of());
        doNothing().when(pusher).push("rendered");

        DailyBotScheduler scheduler = new DailyBotScheduler(
                weatherService,
                newsService,
                wordService,
                jokeService,
                trackingService,
                commentSourceService,
                historyTodayService,
                renderer,
                pusher,
                mlClient);

        scheduler.manualPush();

        ArgumentCaptor<PushMessage> messageCaptor = ArgumentCaptor.forClass(PushMessage.class);
        verify(renderer).render(messageCaptor.capture());
    verify(mlClient, never()).sentimentWithAlert(anyList(), anyString(), anyString(), anyString());
        assertEquals("测试历史事件", messageCaptor.getValue().getHistoryToday());
        assertNull(messageCaptor.getValue().getNewsList().get(0).getSentiment());
    }

    @Test
    void collectsMlAlertsFromUnifiedClientResult() {
    WeatherService weatherService = mock(WeatherService.class);
    NewsService newsService = mock(NewsService.class);
    WordService wordService = mock(WordService.class);
    JokeService jokeService = mock(JokeService.class);
    TrackingService trackingService = mock(TrackingService.class);
    CommentSourceService commentSourceService = mock(CommentSourceService.class);
    HistoryTodayService historyTodayService = mock(HistoryTodayService.class);
    TemplateRenderer renderer = mock(TemplateRenderer.class);
    WeChatPusher pusher = mock(WeChatPusher.class);
    PythonMLClient mlClient = mock(PythonMLClient.class);

    NewsItem newsItem = NewsItem.builder().id("n1").title("测试新闻").summary("摘要").build();
    WeatherInfo weather = WeatherInfo.builder().condition("晴").tempLow(20).tempHigh(30).aqiLevel("优").build();
    DailyWord word = DailyWord.builder().word("focus").definition("专注").build();
    JokeItem joke = JokeItem.builder().content("笑话").build();

    when(weatherService.fetchTodayWithAlert()).thenReturn(FetchResult.of(weather));
    when(newsService.fetchAllWithAlert()).thenReturn(FetchResult.of(List.of(newsItem)));
    when(wordService.fetchWithAlert()).thenReturn(FetchResult.of(word));
    when(jokeService.randomJoke()).thenReturn(joke);
    when(mlClient.commentaryWithAlert(anyString(), anyString(), anyString(), anyString())).thenReturn(
        FetchResult.of("", List.of(SystemAlert.warn("AI点评", "AI_COMMENTARY_FAILED", "测试新闻 -> commentary/ResourceAccessException: timeout"))));
    when(commentSourceService.fetchCommentsWithAlert(newsItem)).thenReturn(FetchResult.of(List.of()));
    when(historyTodayService.getTodaySummary()).thenReturn("测试历史事件");
    when(renderer.render(any(PushMessage.class))).thenReturn("rendered");
    when(trackingService.longTermTrackingWithAlerts(any())).thenReturn(new TrackingService.TrackingBatchResult(List.of(), List.of()));
    when(trackingService.shortTermTrackingWithAlerts(any())).thenReturn(List.of());
    doNothing().when(pusher).push("rendered");

    DailyBotScheduler scheduler = new DailyBotScheduler(
        weatherService,
        newsService,
        wordService,
        jokeService,
        trackingService,
        commentSourceService,
        historyTodayService,
        renderer,
        pusher,
        mlClient);

    scheduler.manualPush();

    ArgumentCaptor<PushMessage> messageCaptor = ArgumentCaptor.forClass(PushMessage.class);
    verify(renderer).render(messageCaptor.capture());
    assertTrue(messageCaptor.getValue().getSystemAlerts().stream()
        .anyMatch(alert -> "AI_COMMENTARY_FAILED".equals(alert.code()) && alert.message().contains("commentary/ResourceAccessException")));
    }
}