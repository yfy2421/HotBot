package com.bot.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class WeatherInfo {
    private String city;
    private String date;
    private int tempHigh;
    private int tempLow;
    private String condition;     // e.g. 晴
    private String icon;          // weather icon code
    private int aqi;              // air quality index
    private String aqiLevel;      // e.g. 优/良
    private String windDirection;
    private String windScale;
}
