package com.bot.service;

import com.bot.client.PythonMLClient;
import com.bot.model.FetchResult;
import com.bot.model.NewsItem;
import com.bot.model.SystemAlert;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.*;

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
            FetchResult<Void> itemResult = applyShortTermTrackingItem(news, storeCurrentNews);
            alerts.addAll(itemResult.alerts());
        }
        return List.copyOf(alerts);
    }

    private FetchResult<Void> applyShortTermTrackingItem(NewsItem news, boolean storeCurrentNews) {
        if (news == null) {
            return FetchResult.of((Void) null);
        }
        List<SystemAlert> alerts = new ArrayList<>();
        news.setFollowUpOf(null);
        news.setFollowUpTag(null);

        FetchResult<List<List<Float>>> vectorsResult = mlClient.embedWithAlert(
                List.of(news.getTitle()),
                "TrackingService",
                "SHORT_TERM_TRACKING_FAILED",
                alertSubject(news));
        alerts.addAll(vectorsResult.alerts());
        List<List<Float>> vectors = vectorsResult.data();
        if (vectors == null || vectors.isEmpty()) {
            return FetchResult.of((Void) null, alerts);
        }

        List<Float> vector = vectors.get(0);
        FetchResult<List<Map<String, Object>>> matchesResult = mlClient.querySimilarWithAlert(
                vector,
                DEFAULT_SHORT_TERM_DAYS,
                DEFAULT_SHORT_TERM_THRESHOLD,
                "TrackingService",
                "SHORT_TERM_TRACKING_FAILED",
                alertSubject(news));
        alerts.addAll(matchesResult.alerts());
        List<Map<String, Object>> matches = matchesResult.data();

        if (matches != null && !matches.isEmpty()) {
            var match = matches.get(0);
            String oldTitle = (String) match.getOrDefault("text", "");
            news.setFollowUpOf((String) match.get("id"));
            news.setFollowUpTag("📌 后续: " + oldTitle);
        }

        if (storeCurrentNews) {
            Map<String, Object> meta = new HashMap<>();
            meta.put("date", LocalDate.now().format(DateTimeFormatter.ISO_DATE));
            meta.put("title", news.getTitle());
            FetchResult<Void> storeResult = mlClient.addNewsWithAlert(
                    news.getId(),
                    news.getTitle(),
                    vector,
                    meta,
                    "TrackingService",
                    "SHORT_TERM_TRACKING_FAILED",
                    alertSubject(news));
            alerts.addAll(storeResult.alerts());
        }
        return FetchResult.of((Void) null, alerts);
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
            FetchResult<List<String>> itemResult = applyLongTermTrackingItem(news);
            lines.addAll(itemResult.data());
            alerts.addAll(itemResult.alerts());
        }
        return new TrackingBatchResult(List.copyOf(lines), List.copyOf(alerts));
    }

    private FetchResult<List<String>> applyLongTermTrackingItem(NewsItem news) {
        if (news == null) {
            return FetchResult.of(List.of());
        }
        List<String> lines = new ArrayList<>();
        List<SystemAlert> alerts = new ArrayList<>();

        String text = news.getTitle() + (news.summaryPreviewText().isBlank() ? "" : " " + news.summaryPreviewText());
        FetchResult<List<Map<String, Object>>> entitiesResult = mlClient.nerWithAlert(
                text,
                "TrackingService",
                "LONG_TERM_TRACKING_FAILED",
                alertSubject(news));
        alerts.addAll(entitiesResult.alerts());
        List<Map<String, Object>> entities = entitiesResult.data();
        if (entities == null || entities.isEmpty()) {
            return FetchResult.of(List.copyOf(lines), alerts);
        }

        for (var entity : entities) {
            String name = (String) entity.get("name");
            String type = (String) entity.get("type");
            String entityText = name + " [" + type + "]";

            FetchResult<List<List<Float>>> vecsResult = mlClient.embedWithAlert(
                    List.of(entityText),
                    "TrackingService",
                    "LONG_TERM_TRACKING_FAILED",
                    alertSubject(news));
            alerts.addAll(vecsResult.alerts());
            List<List<Float>> vecs = vecsResult.data();
            if (vecs == null || vecs.isEmpty()) {
                continue;
            }

            List<Float> vec = vecs.get(0);
            FetchResult<List<Map<String, Object>>> historyResult = mlClient.queryEntityHistoryWithAlert(
                    vec,
                    0.75,
                    "TrackingService",
                    "LONG_TERM_TRACKING_FAILED",
                    alertSubject(news));
            alerts.addAll(historyResult.alerts());
            List<Map<String, Object>> history = historyResult.data();

            if (history != null && !history.isEmpty()) {
                var hist = history.get(0);
                String histText = (String) hist.get("text");
                String timeline = "「" + name + "」" + histText + " → " + news.getTitle().substring(0, Math.min(30, news.getTitle().length()));
                if (!lines.contains(timeline)) {
                    lines.add(timeline);
                }
            }

            Map<String, Object> meta = new HashMap<>();
            meta.put("date", LocalDate.now().format(DateTimeFormatter.ISO_DATE));
            meta.put("news_title", news.getTitle());
            String entityId = "ent-" + name.hashCode() + "-" + LocalDate.now();
            FetchResult<Void> storeResult = mlClient.addEntityWithAlert(
                    entityId,
                    name,
                    type,
                    vec,
                    meta,
                    "TrackingService",
                    "LONG_TERM_TRACKING_FAILED",
                    alertSubject(news));
            alerts.addAll(storeResult.alerts());
        }
        return FetchResult.of(List.copyOf(lines), alerts);
    }

    private String alertSubject(NewsItem news) {
        return news == null || news.getTitle() == null || news.getTitle().isBlank()
                ? "未命名新闻"
                : news.getTitle();
    }

    public record TrackingBatchResult(List<String> lines, List<SystemAlert> alerts) {
    }
}
