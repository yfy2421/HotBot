package com.bot.controller;

import com.bot.config.AppConfig;
import com.bot.model.NewsItem;
import com.bot.service.NewsCardRenderer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.core.io.Resource;
import org.springframework.http.ResponseEntity;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class MediaControllerTest {

    @Test
    void getCardServesRenderedPng(@TempDir Path tempDir) {
        AppConfig.BotConfig botConfig = new AppConfig.BotConfig();
        AppConfig.BotConfig.NewsConfig newsConfig = new AppConfig.BotConfig.NewsConfig();
        newsConfig.setCardOutputDir(tempDir.toString());
        botConfig.setNews(newsConfig);
        NewsCardRenderer renderer = new NewsCardRenderer(botConfig);
        MediaController controller = new MediaController(renderer);

        String cardPath = renderer.renderNewsCard(
                "今日热点卡片",
                "热榜层最新快照",
                List.of(NewsItem.builder()
                        .id("n1")
                        .title("测试热点")
                        .url("https://example.com/n1")
                        .source("测试源")
                        .sourceType("hotlist")
                        .category("general")
                        .trustLevel("aggregated")
                        .publishTime("2026-05-22")
                        .build()),
                Map.of());

        ResponseEntity<Resource> response = controller.getCard(Path.of(cardPath).getFileName().toString());

        assertEquals(200, response.getStatusCode().value());
        assertNotNull(response.getBody());
        assertEquals("image/png", response.getHeaders().getContentType().toString());
    }
}