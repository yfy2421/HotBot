package com.bot.service;

import com.bot.model.SystemAlert;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

@Slf4j
@Component
@RequiredArgsConstructor
public class StartupWarmupService {

    private final NewsService newsService;
    private final AssistantConversationService assistantConversationService;
    private final AtomicBoolean ready = new AtomicBoolean(false);
    private final AtomicReference<String> status = new AtomicReference<>("warming");

    @Async
    @EventListener(ApplicationReadyEvent.class)
    public void warmNewsCache() {
        try {
            status.set("warming-news");
            long fetchStart = System.nanoTime();
            var result = newsService.fetchAllWithAlert();
            long fetchElapsedMs = (System.nanoTime() - fetchStart) / 1_000_000;
            for (SystemAlert alert : result.alerts()) {
                log.warn("Startup warmup alert source={} code={} message={}", alert.source(), alert.code(), alert.message());
            }
            status.set("warming-overview-assets");
            long assetWarmStart = System.nanoTime();
            assistantConversationService.warmOverviewAssets();
            long assetWarmElapsedMs = (System.nanoTime() - assetWarmStart) / 1_000_000;
            ready.set(true);
            status.set("ready");
            log.info("Startup warmup completed newsCount={} fetchMs={} assetWarmMs={}",
                    result.data().size(),
                    fetchElapsedMs,
                    assetWarmElapsedMs);
        } catch (Exception e) {
            status.set("failed: " + e.getMessage());
            log.warn("Startup warmup failed: {}", e.getMessage());
        }
    }

    public boolean isReady() {
        return ready.get();
    }

    public String status() {
        return status.get();
    }
}