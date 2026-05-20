package com.bot.controller;

import com.bot.scheduler.DailyBotScheduler;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequiredArgsConstructor
public class TestController {

    private final DailyBotScheduler scheduler;

    /** Manual trigger: POST /api/trigger */
    @PostMapping("/api/trigger")
    public Map<String, String> trigger() {
        scheduler.manualPush();
        return Map.of("status", "ok", "message", "Push triggered, check logs");
    }
}
