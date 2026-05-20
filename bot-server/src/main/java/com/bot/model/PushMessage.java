package com.bot.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PushMessage {
    private String date;
    private String dayOfWeek;
    private WeatherInfo weather;
    private List<NewsItem> newsList;
    private List<String> trackingLines;   // topic timeline lines
    private List<SystemAlert> systemAlerts;
    private String historyToday;
    private DailyWord dailyWord;
    private JokeItem dailyJoke;
}
