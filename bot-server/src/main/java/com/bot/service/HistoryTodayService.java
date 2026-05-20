package com.bot.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

import java.io.InputStream;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class HistoryTodayService {

    private static final DateTimeFormatter KEY_FORMAT = DateTimeFormatter.ofPattern("MM-dd");

    private final ObjectMapper objectMapper;

    private volatile boolean initialized;
    private Map<String, List<String>> historyByDate = Map.of();

    public String getTodaySummary() {
        return getSummary(LocalDate.now());
    }

    public String getSummary(LocalDate date) {
        ensureLoaded();
        List<String> events = historyByDate.get(date.format(KEY_FORMAT));
        if (events == null || events.isEmpty()) {
            return null;
        }
        return String.join(" / ", events.stream().limit(2).toList());
    }

    private void ensureLoaded() {
        if (initialized) {
            return;
        }
        synchronized (this) {
            if (initialized) {
                return;
            }
            historyByDate = loadHistoryData();
            initialized = true;
        }
    }

    private Map<String, List<String>> loadHistoryData() {
        try {
            ClassPathResource resource = new ClassPathResource("history-today.json");
            if (!resource.exists()) {
                log.warn("history-today.json not found on classpath");
                return Map.of();
            }
            try (InputStream inputStream = resource.getInputStream()) {
                Map<String, List<String>> loaded = objectMapper.readValue(inputStream, new TypeReference<>() {
                });
                return loaded == null ? Map.of() : loaded;
            }
        } catch (Exception e) {
            log.warn("Failed to load history-today.json: {}", e.getMessage());
            return Map.of();
        }
    }
}