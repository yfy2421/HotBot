package com.bot.service;

import com.bot.config.AppConfig;
import com.bot.model.FetchResult;
import com.bot.model.NewsItem;
import com.bot.model.SystemAlert;
import com.bot.util.NewsTimeUtils;
import static com.bot.util.TextUtils.hasText;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.ByteArrayInputStream;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static com.bot.util.TextUtils.defaultText;

@Slf4j
@Service
public class NewsService {

    private static final long NEWS_CACHE_TTL_MILLIS = 120_000;
    private static final long NEWS_SOURCE_TIMEOUT_SECONDS = 8;

    private static final String SOURCE_TYPE_NEWS = "news";
    private static final String SOURCE_TYPE_HOTLIST = "hotlist";
    private static final String CATEGORY_GENERAL = "general";
    private static final String CATEGORY_TECH = "tech";
    private static final String TRUST_OFFICIAL_RSS = "official_rss";
    private static final String TRUST_AGGREGATED = "aggregated";
    private static final String TRUST_COMMUNITY = "community";
    private static final int OVERVIEW_RESULT_LIMIT = 24;
    private static final int SUMMARY_PREVIEW_LIMIT = 220;
    private static final Pattern HN_POINTS_PATTERN = Pattern.compile("(?:^|\\s)Points:\\s*(\\d+)", Pattern.CASE_INSENSITIVE);
    private static final Pattern HN_COMMENTS_PATTERN = Pattern.compile("#\\s*Comments:\\s*(\\d+)", Pattern.CASE_INSENSITIVE);

    private final RestTemplate restTemplate;
    private final Executor taskExecutor;
    private final AppConfig.BotConfig botConfig;

    public NewsService(RestTemplate restTemplate, Executor taskExecutor, AppConfig.BotConfig botConfig) {
        this.restTemplate = restTemplate;
        this.taskExecutor = taskExecutor;
        this.botConfig = botConfig;
    }

    private volatile FetchResult<List<NewsItem>> cachedNewsResult = FetchResult.of(List.of());
    private volatile long cachedNewsExpiresAt;

    public List<NewsItem> fetchAll() {
        return fetchAllWithAlert().data();
    }

    public FetchResult<List<NewsItem>> fetchAllWithAlert() {
        long now = System.currentTimeMillis();
        if (now < cachedNewsExpiresAt) {
            return cachedNewsResult;
        }

        synchronized (this) {
            now = System.currentTimeMillis();
            if (now < cachedNewsExpiresAt) {
                return cachedNewsResult;
            }

            FetchResult<List<NewsItem>> freshResult = fetchAllFresh();
            cachedNewsResult = freshResult;
            cachedNewsExpiresAt = now + NEWS_CACHE_TTL_MILLIS;
            return freshResult;
        }
    }

    private FetchResult<List<NewsItem>> fetchAllFresh() {
        List<NewsItem> all = new ArrayList<>();
        List<SystemAlert> alerts = new ArrayList<>();

        List<AppConfig.BotConfig.NewsConfig.FeedConfig> feeds = botConfig.getNews().getRssFeeds();
        if (feeds == null || feeds.isEmpty()) {
            return FetchResult.of(List.of(), alerts);
        }

        List<FeedTask> feedTasks = feeds.stream()
                .map(feed -> new FeedTask(feed.getName(), al -> dispatchFeed(feed, al)))
                .toList();

        List<CompletableFuture<FeedBatch>> futures = feedTasks.stream()
                .map(this::startFeedFetch)
                .toList();

        for (CompletableFuture<FeedBatch> future : futures) {
            FeedBatch batch = future.join();
            all.addAll(batch.items());
            alerts.addAll(batch.alerts());
        }

        return FetchResult.of(deduplicate(all), alerts);
    }

    private List<NewsItem> dispatchFeed(AppConfig.BotConfig.NewsConfig.FeedConfig feed, List<SystemAlert> alerts) {
        String type = defaultText(feed.getType(), "rss");
        String name = feed.getName();
        String url = feed.getUrl();
        String category = defaultText(feed.getCategory(), CATEGORY_GENERAL);
        String trust = defaultText(feed.getTrust(), TRUST_AGGREGATED);
        String fallback = feed.getFallback();
        List<String> fallbackUrls = feed.getFallbackUrls();

        return switch (type) {
            case "rss" -> fetchRssWithFallback(url, name, category, trust, alerts,
                    alertCode(name, "FETCH_FAILED"), alertCode(name, "RSS_PARSE_FAILED"),
                    fallback, fallbackUrls);
            case "hn" -> fetchHackerNewsRss(feed, alerts);
            case "juejin" -> fetchJuejinHot(alerts, feed);
            case "zhihu" -> fetchZhihuDaily(alerts, feed);
            default -> {
                log.warn("Unknown feed type '{}' for '{}', skipping", type, name);
                yield List.of();
            }
        };
    }

    private List<NewsItem> fetchRssWithFallback(String url, String name, String category,
                                                 String trust, List<SystemAlert> alerts,
                                                 String fetchCode, String parseCode,
                                                 String legacyFallback, List<String> fallbackUrls) {
        // Try primary URL first
        List<NewsItem> items = fetchRssFeedQuietly(url, name, category, trust, alerts, fetchCode, parseCode);
        if (!items.isEmpty()) {
            return items;
        }

        // Try legacy single fallback
        if (hasText(legacyFallback) && !legacyFallback.equals(url)) {
            log.warn("RSS primary failed for '{}', trying legacy fallback: {}", name, legacyFallback);
            items = fetchRssFeedQuietly(legacyFallback, name, category, trust, alerts, fetchCode, parseCode);
            if (!items.isEmpty()) {
                return items;
            }
        }

        // Try list of fallback URLs
        if (fallbackUrls != null) {
            for (String fallbackUrl : fallbackUrls) {
                if (!hasText(fallbackUrl) || fallbackUrl.equals(url)) {
                    continue;
                }
                log.info("RSS fallback for '{}': trying {}", name, fallbackUrl);
                items = fetchRssFeedQuietly(fallbackUrl, name, category, trust, alerts, fetchCode, parseCode);
                if (!items.isEmpty()) {
                    return items;
                }
            }
        }

        return items; // empty list — all attempts failed
    }

    /** Like fetchRssFeed but returns empty list on failure instead of logging errors. */
    private List<NewsItem> fetchRssFeedQuietly(String url, String name, String category,
                                                String trust, List<SystemAlert> alerts,
                                                String fetchCode, String parseCode) {
        try {
            return fetchRssFeed(url, name, category, trust, alerts, fetchCode, parseCode);
        } catch (Exception e) {
            log.debug("RSS fetch failed for '{}' at {}: {}", name, url, e.getMessage());
            return List.of();
        }
    }

    private String alertCode(String name, String suffix) {
        return name.replaceAll("[^A-Za-z0-9]", "_").toUpperCase() + "_" + suffix;
    }

    private CompletableFuture<FeedBatch> startFeedFetch(FeedTask task) {
        return CompletableFuture.supplyAsync(() -> {
                    List<SystemAlert> alerts = new ArrayList<>();
                    List<NewsItem> items = task.fetcher().apply(alerts);
                    return new FeedBatch(items, alerts);
                }, taskExecutor)
                .completeOnTimeout(timeoutBatch(task.source()), NEWS_SOURCE_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                .exceptionally(error -> failureBatch(task.source(), error));
    }

    private FeedBatch timeoutBatch(String source) {
        return new FeedBatch(
                List.of(),
                List.of(SystemAlert.warn(source, "NEWS_SOURCE_TIMEOUT",
                        "新闻源抓取超过 " + NEWS_SOURCE_TIMEOUT_SECONDS + " 秒，已跳过")));
    }

    private FeedBatch failureBatch(String source, Throwable error) {
        String message = error == null || error.getMessage() == null || error.getMessage().isBlank()
                ? "无详细错误信息"
                : error.getMessage().replaceAll("\\s+", " ").trim();
        return new FeedBatch(
                List.of(),
                List.of(SystemAlert.warn(source, "NEWS_SOURCE_ASYNC_FAILED", message)));
    }

    public List<NewsItem> fetchLayered(String sourceType, String category) {
        return fetchAll().stream()
                .filter(item -> matchesSourceType(item, sourceType))
                .filter(item -> matchesCategory(item, category))
                .toList();
    }

    private boolean matchesSourceType(NewsItem item, String sourceType) {
        return sourceType == null || sourceType.isBlank() || "all".equalsIgnoreCase(sourceType)
                || sourceType.equalsIgnoreCase(item.getSourceType());
    }

    private boolean matchesCategory(NewsItem item, String category) {
        return category == null || category.isBlank() || "all".equalsIgnoreCase(category)
                || category.equalsIgnoreCase(item.getCategory());
    }

    private List<NewsItem> fetchHackerNewsRss(AppConfig.BotConfig.NewsConfig.FeedConfig feed, List<SystemAlert> alerts) {
        List<NewsItem> items = List.of();
        try {
            byte[] xmlBytes = restTemplate.getForObject(feed.getUrl(), byte[].class);
            items = parseRss(xmlBytes, currentFetchedAt(), feed.getName(), SOURCE_TYPE_HOTLIST, defaultText(feed.getCategory(), CATEGORY_TECH),
                    defaultText(feed.getTrust(), TRUST_AGGREGATED), alerts, "HN_RSS_PARSE_FAILED");
        } catch (Exception e) {
            log.warn("Failed to fetch {}: {}", feed.getName(), e.getMessage());
        }
        if (!items.isEmpty()) {
            return items;
        }
        if (feed.getFallback() == null || feed.getFallback().isBlank()) {
            alerts.add(SystemAlert.warn(feed.getName(), "HACKERNEWS_FETCH_FAILED", "HackerNews RSS 抓取失败且未配置 fallback"));
            return List.of();
        }
        try {
            byte[] xmlBytes = restTemplate.getForObject(feed.getFallback(), byte[].class);
            List<NewsItem> fallbackItems = parseRss(xmlBytes, currentFetchedAt(), feed.getName(), SOURCE_TYPE_HOTLIST,
                    defaultText(feed.getCategory(), CATEGORY_TECH), defaultText(feed.getTrust(), TRUST_AGGREGATED),
                    alerts, "HN_STANDARD_RSS_PARSE_FAILED");
            if (!fallbackItems.isEmpty()) {
                alerts.add(SystemAlert.warn(feed.getName(), "HN_STANDARD_RSS_FALLBACK", "hnrss 不可用，已回退标准 Hacker News RSS"));
            }
            return fallbackItems;
        } catch (Exception e) {
            alerts.add(SystemAlert.error(feed.getName(), "HN_STANDARD_RSS_FETCH_FAILED", summarizeException(e)));
            return List.of();
        }
    }

    private List<NewsItem> fetchRssFeed(String url, String source, String category, String trustLevel,
                                        List<SystemAlert> alerts, String fetchAlertCode, String parseAlertCode) {
        try {
            byte[] xmlBytes = restTemplate.getForObject(url, byte[].class);
            return parseRss(xmlBytes, currentFetchedAt(), source, SOURCE_TYPE_NEWS, category, trustLevel,
                    alerts, parseAlertCode);
        } catch (Exception e) {
            log.warn("Failed to fetch {}: {}", source, e.getMessage());
            alerts.add(SystemAlert.warn(source, fetchAlertCode, summarizeException(e)));
            return List.of();
        }
    }

    private List<NewsItem> parseRss(byte[] xmlBytes, String fetchedAt, String source, String sourceType, String category,
                                    String trustLevel, List<SystemAlert> alerts, String parseAlertCode) {
        if (xmlBytes == null || xmlBytes.length == 0) {
            return List.of();
        }

        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
            factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            factory.setExpandEntityReferences(false);

            Document document = factory.newDocumentBuilder().parse(new ByteArrayInputStream(xmlBytes));
            NodeList entries = document.getElementsByTagName("item");
            List<NewsItem> items = new ArrayList<>();
            for (int i = 0; i < Math.min(entries.getLength(), 5); i++) {
                Element entry = (Element) entries.item(i);
                String title = readTagText(entry, "title");
                String link = readTagText(entry, "link");
                String description = sanitizeFeedText(readTagText(entry, "description"));
                String publishTime = readTagText(entry, "pubDate");
                String discussionUrl = readTagText(entry, "comments");
                if ((discussionUrl == null || discussionUrl.isBlank()) && link != null && link.contains("news.ycombinator.com/item?id=")) {
                    discussionUrl = link;
                }
                if (title == null || title.isBlank()) {
                    continue;
                }
                String detailExcerpt = buildFeedDetailContent(source, description);

                items.add(NewsItem.builder()
                        .id(source.toLowerCase().replaceAll("[^a-z0-9]+", "-") + "-" + title.hashCode())
                        .title(title)
                        .summary(limit(buildFeedSummary(source, description), SUMMARY_PREVIEW_LIMIT))
                        .detailExcerpt(detailExcerpt)
                        .url(link)
                        .source(source)
                        .sourceType(sourceType)
                        .category(category)
                        .trustLevel(trustLevel)
                        .publishTime(publishTime != null && !publishTime.isBlank()
                                ? publishTime
                                : LocalDate.now().format(DateTimeFormatter.ISO_DATE))
                        .fetchedAt(fetchedAt)
                        .discussionUrl(discussionUrl)
                        .build());
            }
            return items;
        } catch (Exception e) {
            log.warn("Failed to parse RSS for {}: {}", source, e.getMessage());
            alerts.add(SystemAlert.warn(source, parseAlertCode, summarizeException(e)));
            return List.of();
        }
    }

    private String readTagText(Element parent, String tag) {
        NodeList nodes = parent.getElementsByTagName(tag);
        if (nodes.getLength() == 0) {
            return null;
        }
        String text = nodes.item(0).getTextContent();
        if (text == null) {
            return null;
        }
        return text.trim();
    }

    /** Fetch Juejin hot articles. */
    @SuppressWarnings("unchecked")
    private List<NewsItem> fetchJuejinHot(List<SystemAlert> alerts, AppConfig.BotConfig.NewsConfig.FeedConfig feed) {
        try {
            String url = feed.getUrl();
            var resp = restTemplate.postForObject(url, Map.of("type", "hot"), Map.class);
            if (resp == null || resp.get("data") == null) {
                alerts.add(SystemAlert.warn("掘金热榜", "JUEJIN_EMPTY_RESPONSE", "掘金热榜返回空数据"));
                return List.of();
            }

            List<Map<String, Object>> items = extractJuejinItems(resp.get("data"));
            if (items.isEmpty()) {
                alerts.add(SystemAlert.warn("掘金热榜", "JUEJIN_UNEXPECTED_DATA", "掘金热榜返回结构异常，未解析出文章列表"));
                return List.of();
            }
            List<NewsItem> result = new ArrayList<>();
            String fetchedAt = currentFetchedAt();
            for (int i = 0; i < Math.min(items.size(), 5); i++) {
                Map<String, Object> item = items.get(i);
                Map<String, Object> content = mapValue(item.get("content"));
                String title = stringValue(content.getOrDefault("title", item.get("title")));
                String articleId = stringValue(item.getOrDefault("article_id", item.get("id")));
                String summary = stringValue(content.getOrDefault("brief", item.get("brief")));
                if (title.isBlank()) {
                    continue;
                }
                result.add(NewsItem.builder()
                        .id("jj-" + articleId)
                        .title(title)
                        .summary(summary != null ? summary : "")
                        .detailExcerpt(summary != null ? summary : "")
                        .url("https://juejin.cn/post/" + articleId)
                        .source("掘金热榜")
                        .sourceType(SOURCE_TYPE_HOTLIST)
                        .category(CATEGORY_TECH)
                        .trustLevel(TRUST_COMMUNITY)
                        .publishTime(LocalDate.now().format(DateTimeFormatter.ISO_DATE))
                        .fetchedAt(fetchedAt)
                        .build());
            }
            return result;
        } catch (Exception e) {
            log.warn("Failed to fetch Juejin: {}", e.getMessage());
            alerts.add(SystemAlert.warn("掘金热榜", "JUEJIN_FETCH_FAILED", summarizeException(e)));
            return List.of();
        }
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> extractJuejinItems(Object data) {
        if (data instanceof List<?> list) {
            return list.stream()
                    .filter(Map.class::isInstance)
                    .map(item -> (Map<String, Object>) item)
                    .toList();
        }
        if (data instanceof Map<?, ?> map) {
            Object nested = map.get("rank_list");
            if (nested == null) {
                nested = map.get("item_list");
            }
            if (nested == null) {
                nested = map.get("items");
            }
            if (nested == null) {
                nested = map.get("article_list");
            }
            return extractJuejinItems(nested);
        }
        return List.of();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> mapValue(Object value) {
        return value instanceof Map<?, ?> map ? (Map<String, Object>) map : Map.of();
    }

    private String stringValue(Object value) {
        return value == null ? "" : String.valueOf(value).trim();
    }

    /** Fetch Zhihu daily news. */
    @SuppressWarnings("unchecked")
    private List<NewsItem> fetchZhihuDaily(List<SystemAlert> alerts, AppConfig.BotConfig.NewsConfig.FeedConfig feed) {
        try {
            var resp = restTemplate.getForObject(feed.getUrl(), Map.class);
            if (resp == null || resp.get("stories") == null) {
                alerts.add(SystemAlert.warn("知乎日报", "ZHIHU_EMPTY_RESPONSE", "知乎日报返回空数据"));
                return List.of();
            }

            List<Map<String, Object>> stories = (List<Map<String, Object>>) resp.get("stories");
            String date = (String) resp.getOrDefault("date", LocalDate.now().toString());
            List<NewsItem> result = new ArrayList<>();
            String fetchedAt = currentFetchedAt();
            for (int i = 0; i < Math.min(stories.size(), 5); i++) {
                var story = stories.get(i);
                result.add(NewsItem.builder()
                        .id("zh-" + story.get("id"))
                        .title((String) story.get("title"))
                        .summary((String) story.getOrDefault("hint", ""))
                        .detailExcerpt((String) story.getOrDefault("hint", ""))
                        .url((String) story.get("url"))
                        .source("知乎日报")
                        .sourceType(SOURCE_TYPE_HOTLIST)
                        .category(CATEGORY_GENERAL)
                        .trustLevel(TRUST_AGGREGATED)
                        .publishTime(date)
                        .fetchedAt(fetchedAt)
                        .build());
            }
            return result;
        } catch (Exception e) {
            log.warn("Failed to fetch Zhihu: {}", e.getMessage());
            alerts.add(SystemAlert.warn("知乎日报", "ZHIHU_FETCH_FAILED", summarizeException(e)));
            return List.of();
        }
    }

    private String currentFetchedAt() {
        return OffsetDateTime.now().format(DateTimeFormatter.ISO_OFFSET_DATE_TIME);
    }

    private String summarizeException(Exception e) {
        String message = e.getMessage();
        if (message == null || message.isBlank()) {
            message = "无详细错误信息";
        }
        return e.getClass().getSimpleName() + ": " + message.replaceAll("\\s+", " ").trim();
    }

    /** Deduplicate by title similarity (simple substring check). */
    private List<NewsItem> deduplicate(List<NewsItem> items) {
        List<NewsItem> result = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (NewsItem item : items) {
            String key = item.getTitle().toLowerCase().replaceAll("\\s+", "");
            boolean dup = false;
            for (String s : seen) {
                if (s.contains(key.substring(0, Math.min(key.length(), 6)))
                        || key.contains(s.substring(0, Math.min(s.length(), 6)))) {
                    dup = true;
                    break;
                }
            }
            if (!dup) {
                seen.add(key);
                result.add(item);
            }
        }
        result.sort(Comparator.comparing(this::resolveSortInstant).reversed());
        return result.size() > OVERVIEW_RESULT_LIMIT ? new ArrayList<>(result.subList(0, OVERVIEW_RESULT_LIMIT)) : result;
    }

    private String sanitizeFeedText(String raw) {
        if (raw == null || raw.isBlank()) {
            return "";
        }
        return raw
                .replaceAll("(?i)<p[^>]*>", "\n")
                .replaceAll("(?i)<br\\s*/?>", "\n")
                .replaceAll("(?i)</p>", "\n")
                .replaceAll("<[^>]+>", " ")
                .replace("&quot;", "\"")
                .replace("&#39;", "'")
                .replace("&#x27;", "'")
                .replace("&amp;", "&")
                .replace("&nbsp;", " ")
                .replaceAll("\\s+", " ")
                .trim();
    }

    private String buildFeedSummary(String source, String cleanedDescription) {
        if (!"HackerNews".equals(source)) {
            return cleanedDescription;
        }
        String points = extractMatch(cleanedDescription, HN_POINTS_PATTERN);
        String comments = extractMatch(cleanedDescription, HN_COMMENTS_PATTERN);
        List<String> parts = new ArrayList<>();
        if (points != null) {
            parts.add(points + " 分");
        }
        if (comments != null) {
            parts.add(comments + " 条评论");
        }
        if (!parts.isEmpty()) {
            return "Hacker News 热度：" + String.join("，", parts);
        }
        return "";
    }

    private String buildFeedDetailContent(String source, String cleanedDescription) {
        if ("HackerNews".equals(source)) {
            return "";
        }
        return cleanedDescription;
    }

    private String extractMatch(String value, Pattern pattern) {
        if (value == null || value.isBlank()) {
            return null;
        }
        Matcher matcher = pattern.matcher(value);
        if (!matcher.find()) {
            return null;
        }
        return matcher.group(1);
    }

    private String limit(String text, int maxLength) {
        if (text == null || text.length() <= maxLength) {
            return text;
        }
        return text.substring(0, maxLength) + "…";
    }

    private Instant resolveSortInstant(NewsItem item) {
        Instant publishInstant = NewsTimeUtils.parseInstant(item == null ? null : item.getPublishTime());
        if (publishInstant != null) {
            return publishInstant;
        }
        Instant fetchedInstant = NewsTimeUtils.parseInstant(item == null ? null : item.getFetchedAt());
        return fetchedInstant != null ? fetchedInstant : Instant.EPOCH;
    }

    private record FeedTask(String source, Function<List<SystemAlert>, List<NewsItem>> fetcher) {
    }

    private record FeedBatch(List<NewsItem> items, List<SystemAlert> alerts) {
    }
}
