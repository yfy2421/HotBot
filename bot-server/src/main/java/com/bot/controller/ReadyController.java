package com.bot.controller;

import com.bot.service.StartupWarmupService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class ReadyController {

    private final StartupWarmupService startupWarmupService;

    @GetMapping("/ready")
    public Map<String, Object> ready() {
        return Map.of(
                "ready", startupWarmupService.isReady(),
                "status", startupWarmupService.status()
        );
    }
}