package com.bot.service;

import com.bot.model.PushMessage;
import com.bot.model.SystemAlert;
import com.bot.model.WeatherInfo;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

class TemplateRendererTest {

    @Test
    void rendersSystemAlertsSectionWhenPresent() {
        TemplateRenderer renderer = new TemplateRenderer();
        PushMessage message = PushMessage.builder()
                .date("2026年5月20日")
                .dayOfWeek("周三")
                .weather(WeatherInfo.builder().condition("晴").tempLow(20).tempHigh(30).aqiLevel("优").build())
                .newsList(List.of())
                .systemAlerts(List.of(SystemAlert.warn("HackerNews", "HNRSS_FETCH_FAILED", "502 Bad Gateway")))
                .build();

        String rendered = renderer.render(message);

        assertTrue(rendered.contains("⚠️ 数据告警摘要"));
        assertTrue(rendered.contains("HNRSS_FETCH_FAILED"));
    }
}