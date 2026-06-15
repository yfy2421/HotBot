package com.bot.scheduler;

import com.bot.client.PythonMLClient;
import com.bot.model.DailyWord;
import com.bot.model.FetchResult;
import com.bot.model.JokeItem;
import com.bot.model.NewsItem;
import com.bot.model.PushMessage;
import com.bot.model.SystemAlert;
import com.bot.model.WeatherInfo;
import com.bot.service.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;

@Slf4j
@Component
@RequiredArgsConstructor
public class DailyBotScheduler {

    private final WeatherService weatherService;
    private final NewsService newsService;
    private final WordService wordService;
    private final JokeService jokeService;
    private final TrackingService trackingService;
    private final CommentSourceService commentSourceService;
    private final HistoryTodayService historyTodayService;
    private final TemplateRenderer renderer;
    private final WeChatPusher pusher;
    private final PythonMLClient mlClient;

    /** Last push signature — used for dedup. */
    private volatile String lastPushSignature;

    /** Scheduled at 7:00 AM every day. */
    @Scheduled(cron = "0 0 7 * * ?")
    public void scheduledPush() {
        log.info("=== Daily bot push started ===");
        execute();
    }

    /** Manual trigger for testing. */
    public void manualPush() {
        log.info("=== Manual push triggered ===");
        execute();
    }

    private void execute() {
        try {
            // Parallel fetch of independent data sources
            var weatherFuture = CompletableFuture.supplyAsync(weatherService::fetchTodayWithAlert);
            var newsFuture = CompletableFuture.supplyAsync(newsService::fetchAllWithAlert);
            var wordFuture = CompletableFuture.supplyAsync(wordService::fetchWithAlert);
            var jokeFuture = CompletableFuture.supplyAsync(jokeService::randomJoke);

            FetchResult<WeatherInfo> weatherResult = weatherFuture.join();
            FetchResult<List<NewsItem>> newsResult = newsFuture.join();
            FetchResult<DailyWord> wordResult = wordFuture.join();
            JokeItem joke = jokeFuture.join();
            WeatherInfo weather = weatherResult.data();
            List<NewsItem> news = newsResult.data();
            DailyWord word = wordResult.data();
            List<SystemAlert> alerts = Collections.synchronizedList(new ArrayList<>());
            alerts.addAll(weatherResult.alerts());
            alerts.addAll(newsResult.alerts());
            alerts.addAll(wordResult.alerts());

            log.info("Fetched: weather={}, news={} items, word={}, joke={}",
                    weather.getCondition(), news.size(), word.getWord(), joke.getContent());

            // Push dedup: skip if news content hasn't changed since last push
            String newsSignature = computeNewsSignature(news);
            if (newsSignature.equals(lastPushSignature)) {
                log.info("Daily push skipped — news signature unchanged ({})", newsSignature);
                return;
            }
            lastPushSignature = newsSignature;

            // AI commentary for each news (parallel)
            List<CompletableFuture<Void>> commentaryFutures = news.stream()
                    .map(n -> CompletableFuture.runAsync(() -> {
                        String text = n.getTitle() + (n.summaryPreviewText().isBlank() ? "" : " " + n.summaryPreviewText());
                        FetchResult<String> commentaryResult = mlClient.commentaryWithAlert(
                                text,
                                "AI点评",
                                "AI_COMMENTARY_FAILED",
                                n.getTitle());
                        alerts.addAll(commentaryResult.alerts());
                        String commentary = commentaryResult.data();
                        if (commentary != null && !commentary.isBlank()) {
                            n.setCommentary(commentary);
                        }
                    }))
                    .toList();

            // Sentiment analysis for each news (parallel)
            List<CompletableFuture<Void>> sentimentFutures = news.stream()
                    .map(n -> CompletableFuture.runAsync(() -> {
                        FetchResult<List<String>> commentResult = commentSourceService.fetchCommentsWithAlert(n);
                        alerts.addAll(commentResult.alerts());
                        List<String> comments = commentResult.data();
                        if (comments == null || comments.isEmpty()) {
                            return;
                        }
                        FetchResult<java.util.Map<String, Object>> sentimentResult = mlClient.sentimentWithAlert(
                                comments,
                                "情感分析",
                                "AI_SENTIMENT_FAILED",
                                n.getTitle());
                        alerts.addAll(sentimentResult.alerts());
                        var result = sentimentResult.data();
                        Object summary = result.get("summary");
                        if (summary instanceof String text && !text.isBlank()) {
                            n.setSentiment(text);
                        }
                    }))
                    .toList();

            // Wait for all AI tasks
            CompletableFuture.allOf(
                    commentaryFutures.toArray(new CompletableFuture[0])
            ).join();
            CompletableFuture.allOf(
                    sentimentFutures.toArray(new CompletableFuture[0])
            ).join();

            // Tracking (must run after commentary since it uses embeddings)
                alerts.addAll(trackingService.shortTermTrackingWithAlerts(news));
                TrackingService.TrackingBatchResult trackingResult = trackingService.longTermTrackingWithAlerts(news);
                List<String> trackingLines = trackingResult.lines();
                alerts.addAll(trackingResult.alerts());

            // Build push message
            LocalDate today = LocalDate.now();
            String[] weekDays = {"周日", "周一", "周二", "周三", "周四", "周五", "周六"};

            PushMessage msg = PushMessage.builder()
                    .date(today.format(DateTimeFormatter.ofPattern("yyyy年M月d日")))
                    .dayOfWeek(weekDays[today.getDayOfWeek().getValue() % 7])
                    .weather(weather)
                    .newsList(news)
                    .trackingLines(trackingLines)
                    .systemAlerts(compactAlerts(alerts))
                    .historyToday(historyTodayService.getTodaySummary())
                    .dailyWord(word)
                    .dailyJoke(joke)
                    .build();

            // Render and push
            String markdown = renderer.render(msg);
            pusher.push(markdown);

                if (!alerts.isEmpty()) {
                log.warn("Daily bot alerts summary: {}",
                    compactAlerts(alerts).stream()
                        .map(this::formatAlert)
                        .collect(Collectors.joining(" | ")));
                }

            log.info("=== Daily bot push completed ===");

        } catch (Exception e) {
            log.error("Daily bot push failed", e);
        }
    }

    private List<SystemAlert> compactAlerts(List<SystemAlert> alerts) {
        if (alerts == null || alerts.isEmpty()) {
            return List.of();
        }
        return alerts.stream()
                .distinct()
                .limit(8)
                .toList();
    }

    private String formatAlert(SystemAlert alert) {
        return alert.source() + "/" + alert.code() + ": " + alert.message();
    }

    private String computeNewsSignature(List<NewsItem> news) {
        if (news == null || news.isEmpty()) {
            return "";
        }
        try {
            String titles = news.stream()
                    .map(n -> n.getTitle() == null ? "" : n.getTitle())
                    .sorted()
                    .collect(Collectors.joining("|"));
            byte[] digest = MessageDigest.getInstance("MD5")
                    .digest(titles.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder();
            for (byte b : digest) {
                hex.append(String.format("%02x", b));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            return String.valueOf(news.hashCode());
        }
    }
}
