package com.bot.service;

import com.bot.model.FetchResult;
import com.bot.model.NewsItem;
import com.bot.model.SystemAlert;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.StringReader;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

@Slf4j
@Service
@RequiredArgsConstructor
public class NewsService {

    private static final long NEWS_CACHE_TTL_MILLIS = 120_000;
    private static final long NEWS_SOURCE_TIMEOUT_SECONDS = 8;

    private static final String HN_RSS_URL = "https://hnrss.org/frontpage?count=5";
    private static final String HN_STANDARD_RSS_URL = "https://news.ycombinator.com/rss";
    private static final String TECHCRUNCH_RSS_URL = "https://techcrunch.com/feed/";
    private static final String XINHUA_RSS_URL = "https://plink.anyfeeder.com/newscn/whxw";
    private static final String SCIENTIFIC_AMERICAN_RSS_URL = "https://plink.anyfeeder.com/weixin/ScientificAmerican";
    private static final String GLOBAL_TIMES_RSS_URL = "https://plink.anyfeeder.com/weixin/hqsbwx";
    private static final String DILI360_RSS_URL = "https://plink.anyfeeder.com/weixin/dili360";
    private static final String APPINN_RSS_URL = "https://plink.anyfeeder.com/appinn";

    private static final String SOURCE_TYPE_NEWS = "news";
    private static final String SOURCE_TYPE_HOTLIST = "hotlist";
    private static final String CATEGORY_GENERAL = "general";
    private static final String CATEGORY_TECH = "tech";
    private static final String TRUST_OFFICIAL_RSS = "official_rss";
    private static final String TRUST_AGGREGATED = "aggregated";
    private static final String TRUST_COMMUNITY = "community";

    private final RestTemplate restTemplate;
    private final Executor taskExecutor;

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

        List<FeedTask> feedTasks = List.of(
                new FeedTask("TechCrunch RSS", this::fetchTechCrunchNews),
                new FeedTask("新华社新闻_新华网", this::fetchXinHuaNews),
                new FeedTask("环球科学", this::fetchScientificAmericanNews),
                new FeedTask("环球时报", this::fetchGlobalTimesNews),
                new FeedTask("中国国家地理", this::fetchDili360News),
                new FeedTask("小众软件", this::fetchAppinnNews),
                new FeedTask("HackerNews", this::fetchHackerNews),
                new FeedTask("掘金热榜", this::fetchJuejinHot),
                new FeedTask("知乎日报", this::fetchZhihuDaily)
        );

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

    private List<NewsItem> fetchTechCrunchNews(List<SystemAlert> alerts) {
        return fetchRssFeed(TECHCRUNCH_RSS_URL, "TechCrunch RSS", CATEGORY_TECH, TRUST_OFFICIAL_RSS,
                alerts, "TECHCRUNCH_FETCH_FAILED", "TECHCRUNCH_RSS_PARSE_FAILED");
    }

    private List<NewsItem> fetchXinHuaNews(List<SystemAlert> alerts) {
        return fetchRssFeed(XINHUA_RSS_URL, "新华社新闻_新华网", CATEGORY_GENERAL, TRUST_AGGREGATED,
                alerts, "XINHUA_FETCH_FAILED", "XINHUA_RSS_PARSE_FAILED");
    }

    private List<NewsItem> fetchScientificAmericanNews(List<SystemAlert> alerts) {
        return fetchRssFeed(SCIENTIFIC_AMERICAN_RSS_URL, "环球科学", CATEGORY_TECH, TRUST_AGGREGATED,
                alerts, "SCIENTIFIC_AMERICAN_FETCH_FAILED", "SCIENTIFIC_AMERICAN_RSS_PARSE_FAILED");
    }

    private List<NewsItem> fetchGlobalTimesNews(List<SystemAlert> alerts) {
        return fetchRssFeed(GLOBAL_TIMES_RSS_URL, "环球时报", CATEGORY_GENERAL, TRUST_AGGREGATED,
                alerts, "GLOBAL_TIMES_FETCH_FAILED", "GLOBAL_TIMES_RSS_PARSE_FAILED");
    }

    private List<NewsItem> fetchDili360News(List<SystemAlert> alerts) {
        return fetchRssFeed(DILI360_RSS_URL, "中国国家地理", CATEGORY_GENERAL, TRUST_AGGREGATED,
                alerts, "DILI360_FETCH_FAILED", "DILI360_RSS_PARSE_FAILED");
    }

    private List<NewsItem> fetchAppinnNews(List<SystemAlert> alerts) {
        return fetchRssFeed(APPINN_RSS_URL, "小众软件", CATEGORY_TECH, TRUST_AGGREGATED,
                alerts, "APPINN_FETCH_FAILED", "APPINN_RSS_PARSE_FAILED");
    }

    private List<NewsItem> fetchRssFeed(String url, String source, String category, String trustLevel,
                                        List<SystemAlert> alerts, String fetchAlertCode, String parseAlertCode) {
        try {
            String xml = restTemplate.getForObject(url, String.class);
            return parseRss(xml, currentFetchedAt(), source, SOURCE_TYPE_NEWS, category, trustLevel,
                    alerts, parseAlertCode);
        } catch (Exception e) {
            log.warn("Failed to fetch {}: {}", source, e.getMessage());
            alerts.add(SystemAlert.warn(source, fetchAlertCode, summarizeException(e)));
            return List.of();
        }
    }

    /** Fetch HackerNews front page via RSS JSON. */
    private List<NewsItem> fetchHackerNews(List<SystemAlert> alerts) {
        try {
            String xml = restTemplate.getForObject(HN_RSS_URL, String.class);
            return parseRss(xml, currentFetchedAt(), "HackerNews", SOURCE_TYPE_HOTLIST, CATEGORY_TECH, TRUST_AGGREGATED,
                    alerts, "HN_RSS_PARSE_FAILED");
        } catch (Exception e) {
            log.warn("Failed to fetch HackerNews: {}", e.getMessage());
            alerts.add(SystemAlert.warn("HackerNews", "HNRSS_FETCH_FAILED", summarizeException(e)));
            return fetchHackerNewsFallback(alerts);
        }
    }

    private List<NewsItem> fetchHackerNewsFallback(List<SystemAlert> alerts) {
        try {
            String xml = restTemplate.getForObject(HN_STANDARD_RSS_URL, String.class);
            List<NewsItem> items = parseRss(xml, currentFetchedAt(), "HackerNews", SOURCE_TYPE_HOTLIST, CATEGORY_TECH,
                    TRUST_AGGREGATED, alerts, "HN_STANDARD_RSS_PARSE_FAILED");
            if (!items.isEmpty()) {
                alerts.add(SystemAlert.warn("HackerNews", "HN_STANDARD_RSS_FALLBACK",
                        "hnrss 不可用，已回退标准 Hacker News RSS"));
            }
            return items;
        } catch (Exception e) {
            alerts.add(SystemAlert.error("HackerNews", "HN_STANDARD_RSS_FETCH_FAILED", summarizeException(e)));
            return List.of();
        }
    }

    private List<NewsItem> parseRss(String xml, String fetchedAt, String source, String sourceType, String category,
                                    String trustLevel, List<SystemAlert> alerts, String parseAlertCode) {
        if (xml == null || xml.isBlank()) {
            return List.of();
        }

        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
            factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            factory.setExpandEntityReferences(false);

            Document document = factory.newDocumentBuilder().parse(new InputSource(new StringReader(xml)));
            NodeList entries = document.getElementsByTagName("item");
            List<NewsItem> items = new ArrayList<>();
            for (int i = 0; i < Math.min(entries.getLength(), 5); i++) {
                Element entry = (Element) entries.item(i);
                String title = readTagText(entry, "title");
                String link = readTagText(entry, "link");
                String desc = readTagText(entry, "description");
                String publishTime = readTagText(entry, "pubDate");
                String discussionUrl = readTagText(entry, "comments");
                if ((discussionUrl == null || discussionUrl.isBlank()) && link != null && link.contains("news.ycombinator.com/item?id=")) {
                    discussionUrl = link;
                }
                if (title == null || title.isBlank()) {
                    continue;
                }
                items.add(NewsItem.builder()
                        .id(source.toLowerCase().replaceAll("[^a-z0-9]+", "-") + "-" + title.hashCode())
                        .title(title)
                        .summary(desc != null ? desc.substring(0, Math.min(desc.length(), 200)) : "")
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
    private List<NewsItem> fetchJuejinHot(List<SystemAlert> alerts) {
        try {
            String url = "https://api.juejin.cn/content_api/v1/content/article_rank?type=hot";
            var resp = restTemplate.postForObject(url, Map.of("type", "hot"), Map.class);
            if (resp == null || resp.get("data") == null) {
                alerts.add(SystemAlert.warn("掘金热榜", "JUEJIN_EMPTY_RESPONSE", "掘金热榜返回空数据"));
                return List.of();
            }

            List<Map<String, Object>> items = (List<Map<String, Object>>) resp.get("data");
            List<NewsItem> result = new ArrayList<>();
            String fetchedAt = currentFetchedAt();
            for (int i = 0; i < Math.min(items.size(), 5); i++) {
                var content = (Map<String, Object>) items.get(i).getOrDefault("content", Map.of());
                String title = (String) content.getOrDefault("title", "");
                String articleId = (String) items.get(i).getOrDefault("article_id", "");
                String summary = (String) content.getOrDefault("brief", "");
                result.add(NewsItem.builder()
                        .id("jj-" + articleId)
                        .title(title)
                        .summary(summary != null ? summary : "")
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

    /** Fetch Zhihu daily news. */
    @SuppressWarnings("unchecked")
    private List<NewsItem> fetchZhihuDaily(List<SystemAlert> alerts) {
        try {
            String url = "https://news-at.zhihu.com/api/4/news/latest";
            var resp = restTemplate.getForObject(url, Map.class);
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
        // Return top 8
        return result.size() > 8 ? new ArrayList<>(result.subList(0, 8)) : result;
    }

    private Instant resolveSortInstant(NewsItem item) {
        Instant publishInstant = parseInstant(item == null ? null : item.getPublishTime());
        if (publishInstant != null) {
            return publishInstant;
        }
        Instant fetchedInstant = parseInstant(item == null ? null : item.getFetchedAt());
        return fetchedInstant != null ? fetchedInstant : Instant.EPOCH;
    }

    private Instant parseInstant(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }

        try {
            return OffsetDateTime.parse(value, DateTimeFormatter.ISO_OFFSET_DATE_TIME).toInstant();
        } catch (DateTimeParseException ignored) {
        }

        try {
            return ZonedDateTime.parse(value, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant();
        } catch (DateTimeParseException ignored) {
        }

        try {
            return LocalDate.parse(value, DateTimeFormatter.ISO_DATE)
                    .atStartOfDay(ZoneOffset.UTC)
                    .toInstant();
        } catch (DateTimeParseException ignored) {
        }

        try {
            return LocalDate.parse(value, DateTimeFormatter.BASIC_ISO_DATE)
                    .atStartOfDay(ZoneOffset.UTC)
                    .toInstant();
        } catch (DateTimeParseException ignored) {
        }

        return null;
    }

    private record FeedTask(String source, Function<List<SystemAlert>, List<NewsItem>> fetcher) {
    }

    private record FeedBatch(List<NewsItem> items, List<SystemAlert> alerts) {
    }
}
