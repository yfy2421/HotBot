package com.bot.service;

import com.bot.client.PythonMLClient;
import com.bot.model.NewsItem;
import com.bot.model.SystemAlert;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.*;

@Slf4j
@Service
@RequiredArgsConstructor
public class TrackingService {

    private static final int DEFAULT_SHORT_TERM_DAYS = 7;
    private static final double DEFAULT_SHORT_TERM_THRESHOLD = 0.8;

    private final PythonMLClient mlClient;

    /**
     * Short-term tracking: find similar news within 7 days.
     * Tags news with followUpTag if similarity > 0.8.
     */
    public void shortTermTracking(List<NewsItem> newsList) {
        shortTermTrackingWithAlerts(newsList);
    }

    public List<SystemAlert> shortTermTrackingWithAlerts(List<NewsItem> newsList) {
        return applyShortTermTracking(newsList, newsList == null ? 0 : newsList.size(), true);
    }

    public List<SystemAlert> findRecentFollowUpsWithAlerts(List<NewsItem> newsList, int limit) {
        return applyShortTermTracking(newsList, limit, false);
    }

    private List<SystemAlert> applyShortTermTracking(List<NewsItem> newsList, int limit, boolean storeCurrentNews) {
        List<SystemAlert> alerts = new ArrayList<>();
        if (newsList == null || newsList.isEmpty() || limit <= 0) {
            return List.of();
        }

        int scopedLimit = Math.min(limit, newsList.size());
        for (int index = 0; index < scopedLimit; index++) {
            NewsItem news = newsList.get(index);
            try {
                news.setFollowUpOf(null);
                news.setFollowUpTag(null);

                // Get embedding for this news title
                var vectors = mlClient.embed(List.of(news.getTitle()));
                if (vectors.isEmpty()) {
                    continue;
                }

                var vector = vectors.get(0);
                var matches = mlClient.querySimilar(vector, DEFAULT_SHORT_TERM_DAYS, DEFAULT_SHORT_TERM_THRESHOLD);

                if (!matches.isEmpty()) {
                    var match = matches.get(0);
                    String oldTitle = (String) match.getOrDefault("text", "");
                    news.setFollowUpOf((String) match.get("id"));
                    news.setFollowUpTag("📌 后续: " + oldTitle);
                }

                if (storeCurrentNews) {
                    // Store this news for future tracking
                    Map<String, Object> meta = new HashMap<>();
                    meta.put("date", LocalDate.now().format(DateTimeFormatter.ISO_DATE));
                    meta.put("title", news.getTitle());
                    mlClient.addNews(news.getId(), news.getTitle(), vector, meta);
                }

            } catch (Exception e) {
                log.warn("Short-term tracking failed for '{}': {}", news.getTitle(), e.getMessage());
                alerts.add(SystemAlert.warn("TrackingService", "SHORT_TERM_TRACKING_FAILED",
                        summarizeFailure(news, e)));
            }
        }
        return List.copyOf(alerts);
    }

    /**
     * Long-term tracking: extract entities, match with historical entities,
     * build topic timeline lines.
     */
    public List<String> longTermTracking(List<NewsItem> newsList) {
        return longTermTrackingWithAlerts(newsList).lines();
    }

    public TrackingBatchResult longTermTrackingWithAlerts(List<NewsItem> newsList) {
        List<String> lines = new ArrayList<>();
        List<SystemAlert> alerts = new ArrayList<>();

        for (NewsItem news : newsList) {
            try {
                String text = news.getTitle() + (news.getSummary() != null ? " " + news.getSummary() : "");
                var entities = mlClient.ner(text);

                for (var entity : entities) {
                    String name = (String) entity.get("name");
                    String type = (String) entity.get("type");
                    String entityText = name + " [" + type + "]";

                    // Get embedding for the entity text
                    var vecs = mlClient.embed(List.of(entityText));
                    if (vecs.isEmpty()) continue;

                    var vec = vecs.get(0);
                    var history = mlClient.queryEntityHistory(vec, 0.75);

                    if (!history.isEmpty()) {
                        var hist = history.get(0);
                        String histText = (String) hist.get("text");
                        String timeline = "「" + name + "」" + histText + " → " + news.getTitle().substring(0, Math.min(30, news.getTitle().length()));
                        if (!lines.contains(timeline)) {
                            lines.add(timeline);
                        }
                    }

                    // Store entity for future
                    Map<String, Object> meta = new HashMap<>();
                    meta.put("date", LocalDate.now().format(DateTimeFormatter.ISO_DATE));
                    meta.put("news_title", news.getTitle());
                    String entityId = "ent-" + name.hashCode() + "-" + LocalDate.now();
                    mlClient.addEntity(entityId, name, type, vec, meta);

                }
            } catch (Exception e) {
                log.warn("Long-term tracking failed for '{}': {}", news.getTitle(), e.getMessage());
                alerts.add(SystemAlert.warn("TrackingService", "LONG_TERM_TRACKING_FAILED",
                        summarizeFailure(news, e)));
            }
        }
        return new TrackingBatchResult(List.copyOf(lines), List.copyOf(alerts));
    }

    private String summarizeFailure(NewsItem news, Exception e) {
        String title = news == null || news.getTitle() == null ? "未命名新闻" : news.getTitle();
        String message = e.getMessage();
        if (message == null || message.isBlank()) {
            message = "无详细错误信息";
        }
        return title + " -> " + e.getClass().getSimpleName() + ": " + message;
    }

    public record TrackingBatchResult(List<String> lines, List<SystemAlert> alerts) {
    }
}
