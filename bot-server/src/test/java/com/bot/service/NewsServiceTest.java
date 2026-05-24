package com.bot.service;

import com.bot.config.AppConfig;
import com.bot.config.AppConfig.BotConfig.NewsConfig.FeedConfig;
import com.bot.model.FetchResult;
import com.bot.model.NewsItem;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestTemplate;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class NewsServiceTest {

    private NewsService newService(RestTemplate restTemplate) {
        return new NewsService(restTemplate, Runnable::run, testBotConfig());
    }

    private AppConfig.BotConfig testBotConfig() {
        AppConfig.BotConfig.NewsConfig news = new AppConfig.BotConfig.NewsConfig();
                news.setRssFeeds(new ArrayList<>(List.of(
                                feed("TechCrunch RSS", "https://techcrunch.com/feed/", "rss", "tech", "official_rss", null),
                                feed("新华社新闻_新华网", "https://plink.anyfeeder.com/newscn/whxw", "rss", "general", "aggregated", null),
                                feed("环球科学", "https://plink.anyfeeder.com/weixin/ScientificAmerican", "rss", "tech", "aggregated", null),
                                feed("环球时报", "https://plink.anyfeeder.com/weixin/hqsbwx", "rss", "general", "aggregated", null),
                                feed("中国国家地理", "https://plink.anyfeeder.com/weixin/dili360", "rss", "general", "aggregated", null),
                                feed("小众软件", "https://plink.anyfeeder.com/appinn", "rss", "tech", "aggregated", null),
                                feed("HackerNews", "https://hnrss.org/frontpage?count=5", "hn", "tech", "aggregated", "https://news.ycombinator.com/rss"),
                                feed("掘金热榜", "https://api.juejin.cn/content_api/v1/content/article_rank?type=hot", "juejin", "tech", "community", null),
                                feed("知乎日报", "https://news-at.zhihu.com/api/4/news/latest", "zhihu", "general", "aggregated", null)
                )));
        AppConfig.BotConfig botConfig = new AppConfig.BotConfig();
        botConfig.setNews(news);
        return botConfig;
    }

        private FeedConfig feed(String name, String url, String type, String category, String trust, String fallback) {
                FeedConfig feed = new FeedConfig();
                feed.setName(name);
                feed.setUrl(url);
                feed.setType(type);
                feed.setCategory(category);
                feed.setTrust(trust);
                feed.setFallback(fallback);
                return feed;
        }

    @Test
    void fetchAllIncludesNewRssFeedsAndKeepsNewestItems() {
        RestTemplate restTemplate = mock(RestTemplate.class);
        NewsService service = newService(restTemplate);

        when(restTemplate.getForObject(eq("https://techcrunch.com/feed/"), eq(byte[].class)))
                .thenReturn(rssBytes("TechCrunch 标题", "https://tech.example/1", sortTimestampToday(8)));
        when(restTemplate.getForObject(eq("https://plink.anyfeeder.com/newscn/whxw"), eq(byte[].class)))
                .thenReturn(rssBytes("新华社 标题", "https://xinhua.example/1", sortTimestampToday(9)));
        when(restTemplate.getForObject(eq("https://plink.anyfeeder.com/weixin/ScientificAmerican"), eq(byte[].class)))
                .thenReturn(rssBytes("环球科学 标题", "https://science.example/1", sortTimestampToday(10)));
        when(restTemplate.getForObject(eq("https://plink.anyfeeder.com/weixin/hqsbwx"), eq(byte[].class)))
                .thenReturn(rssBytes("环球时报 标题", "https://globaltimes.example/1", sortTimestampToday(11)));
        when(restTemplate.getForObject(eq("https://plink.anyfeeder.com/weixin/dili360"), eq(byte[].class)))
                .thenReturn(rssBytes("中国国家地理 标题", "https://dili360.example/1", sortTimestampToday(12)));
        when(restTemplate.getForObject(eq("https://plink.anyfeeder.com/appinn"), eq(byte[].class)))
                .thenReturn(rssBytes("小众软件 标题", "https://appinn.example/1", sortTimestampToday(13)));
        when(restTemplate.getForObject(eq("https://hnrss.org/frontpage?count=5"), eq(byte[].class)))
                .thenReturn(rssBytes("HackerNews 标题", "https://news.ycombinator.com/item?id=1", sortTimestampToday(7)));
        when(restTemplate.postForObject(eq("https://api.juejin.cn/content_api/v1/content/article_rank?type=hot"), any(), eq(Map.class)))
                .thenReturn(Map.of("data", List.of(
                        Map.of("article_id", "jj1", "content", Map.of("title", "掘金 标题", "brief", "brief"))
                )));
        when(restTemplate.getForObject(eq("https://news-at.zhihu.com/api/4/news/latest"), eq(Map.class)))
                .thenReturn(Map.of(
                        "date", LocalDate.now(ZoneOffset.UTC).format(DateTimeFormatter.BASIC_ISO_DATE),
                        "stories", List.of(Map.of("id", 1, "title", "知乎 标题", "hint", "hint", "url", "https://zhihu.example/1"))
                ));

        FetchResult<List<NewsItem>> result = service.fetchAllWithAlert();

        assertEquals(9, result.data().size());
        assertTrue(result.data().stream().map(NewsItem::getSource).toList().contains("环球科学"));
        assertTrue(result.data().stream().map(NewsItem::getSource).toList().contains("小众软件"));
        assertEquals("小众软件", result.data().stream()
                .filter(item -> "news".equals(item.getSourceType()))
                .findFirst()
                .orElseThrow()
                .getSource());
    }

    @Test
    void fetchAllSkipsMalformedJuejinPayloadWithoutThrowing() {
        RestTemplate restTemplate = mock(RestTemplate.class);
        NewsService service = newService(restTemplate);

        when(restTemplate.getForObject(eq("https://techcrunch.com/feed/"), eq(byte[].class)))
                .thenReturn(rssBytes("TechCrunch 标题", "https://tech.example/1", sortTimestampToday(8)));
        when(restTemplate.getForObject(eq("https://plink.anyfeeder.com/newscn/whxw"), eq(byte[].class)))
                .thenReturn(rssBytes("新华社 标题", "https://xinhua.example/1", sortTimestampToday(9)));
        when(restTemplate.getForObject(eq("https://plink.anyfeeder.com/weixin/ScientificAmerican"), eq(byte[].class)))
                .thenReturn(rssBytes("环球科学 标题", "https://science.example/1", sortTimestampToday(10)));
        when(restTemplate.getForObject(eq("https://plink.anyfeeder.com/weixin/hqsbwx"), eq(byte[].class)))
                .thenReturn(rssBytes("环球时报 标题", "https://globaltimes.example/1", sortTimestampToday(11)));
        when(restTemplate.getForObject(eq("https://plink.anyfeeder.com/weixin/dili360"), eq(byte[].class)))
                .thenReturn(rssBytes("中国国家地理 标题", "https://dili360.example/1", sortTimestampToday(12)));
        when(restTemplate.getForObject(eq("https://plink.anyfeeder.com/appinn"), eq(byte[].class)))
                .thenReturn(rssBytes("小众软件 标题", "https://appinn.example/1", sortTimestampToday(13)));
        when(restTemplate.getForObject(eq("https://hnrss.org/frontpage?count=5"), eq(byte[].class)))
                .thenReturn(rssBytes("HackerNews 标题", "https://news.ycombinator.com/item?id=1", sortTimestampToday(7)));
        when(restTemplate.postForObject(eq("https://api.juejin.cn/content_api/v1/content/article_rank?type=hot"), any(), eq(Map.class)))
                .thenReturn(Map.of("data", "unexpected-string"));
        when(restTemplate.getForObject(eq("https://news-at.zhihu.com/api/4/news/latest"), eq(Map.class)))
                .thenReturn(Map.of(
                        "date", LocalDate.now(ZoneOffset.UTC).format(DateTimeFormatter.BASIC_ISO_DATE),
                        "stories", List.of(Map.of("id", 1, "title", "知乎 标题", "hint", "hint", "url", "https://zhihu.example/1"))
                ));

        FetchResult<List<NewsItem>> result = service.fetchAllWithAlert();

        assertEquals(8, result.data().size());
        assertTrue(result.alerts().stream().anyMatch(alert -> "JUEJIN_UNEXPECTED_DATA".equals(alert.code())));
        assertTrue(result.data().stream().noneMatch(item -> "掘金热榜".equals(item.getSource())));
    }

    @Test
    void fetchAllKeepsUtf8ChineseTitlesAndFormatsHackerNewsSummary() {
        RestTemplate restTemplate = mock(RestTemplate.class);
        NewsService service = newService(restTemplate);

        when(restTemplate.getForObject(eq("https://techcrunch.com/feed/"), eq(byte[].class)))
                .thenReturn(rssBytes("TechCrunch 标题", "https://tech.example/1", sortTimestampToday(8)));
        when(restTemplate.getForObject(eq("https://plink.anyfeeder.com/newscn/whxw"), eq(byte[].class)))
                .thenReturn(rssBytes("新华社 标题", "https://xinhua.example/1", sortTimestampToday(9)));
        when(restTemplate.getForObject(eq("https://plink.anyfeeder.com/weixin/ScientificAmerican"), eq(byte[].class)))
                .thenReturn(rssBytes("环球科学 标题", "https://science.example/1", sortTimestampToday(10)));
        when(restTemplate.getForObject(eq("https://plink.anyfeeder.com/weixin/hqsbwx"), eq(byte[].class)))
                .thenReturn(rssBytes("运动“长脑子”被证实：坚持3个月，大脑变化太明显", "https://globaltimes.example/1", sortTimestampToday(11)));
        when(restTemplate.getForObject(eq("https://plink.anyfeeder.com/weixin/dili360"), eq(byte[].class)))
                .thenReturn(rssBytes("中国国家地理 标题", "https://dili360.example/1", sortTimestampToday(12)));
        when(restTemplate.getForObject(eq("https://plink.anyfeeder.com/appinn"), eq(byte[].class)))
                .thenReturn(rssBytes("小众软件 标题", "https://appinn.example/1", sortTimestampToday(13)));
        when(restTemplate.getForObject(eq("https://hnrss.org/frontpage?count=5"), eq(byte[].class)))
                .thenReturn(rssBytes(
                        "Samsung chip workers will get an average $340k bonus as AI profits soar",
                        "https://qz.com/samsung-chip-workers-bonus-ai-profits-052126",
                        hnDescription("https://qz.com/samsung-chip-workers-bonus-ai-profits-052126",
                                "https://news.ycombinator.com/item?id=48230892",
                                66,
                                16),
                        sortTimestampToday(7),
                        "https://news.ycombinator.com/item?id=48230892"
                ));
        when(restTemplate.postForObject(eq("https://api.juejin.cn/content_api/v1/content/article_rank?type=hot"), any(), eq(Map.class)))
                .thenReturn(Map.of("data", List.of(
                        Map.of("article_id", "jj1", "content", Map.of("title", "掘金 标题", "brief", "brief"))
                )));
        when(restTemplate.getForObject(eq("https://news-at.zhihu.com/api/4/news/latest"), eq(Map.class)))
                .thenReturn(Map.of(
                        "date", LocalDate.now(ZoneOffset.UTC).format(DateTimeFormatter.BASIC_ISO_DATE),
                        "stories", List.of(Map.of("id", 1, "title", "知乎 标题", "hint", "hint", "url", "https://zhihu.example/1"))
                ));

        FetchResult<List<NewsItem>> result = service.fetchAllWithAlert();

        NewsItem globalTimes = result.data().stream()
                .filter(item -> "环球时报".equals(item.getSource()))
                .findFirst()
                .orElseThrow();
        assertEquals("运动“长脑子”被证实：坚持3个月，大脑变化太明显", globalTimes.getTitle());

        NewsItem hackerNews = result.data().stream()
                .filter(item -> "HackerNews".equals(item.getSource()))
                .findFirst()
                .orElseThrow();
        assertEquals("Hacker News 热度：66 分，16 条评论", hackerNews.getSummary());
        assertEquals("Hacker News 热度：66 分，16 条评论", hackerNews.summaryPreviewText());
        assertEquals("", hackerNews.resolvedDetailExcerpt());
        assertNull(hackerNews.getDetailContent());
        assertEquals("https://news.ycombinator.com/item?id=48230892", hackerNews.getDiscussionUrl());
        assertNotNull(hackerNews.getUrl());
    }

    private byte[] rssBytes(String title, String link, String pubDate) {
        return rssBytes(title, link, "summary", pubDate, null);
    }

    private byte[] rssBytes(String title, String link, String description, String pubDate, String commentsUrl) {
        return rss(title, link, description, pubDate, commentsUrl).getBytes(StandardCharsets.UTF_8);
    }

    private String rss(String title, String link, String description, String pubDate, String commentsUrl) {
        return """
                <rss version=\"2.0\">
                  <channel>
                    <title>Feed</title>
                    <item>
                      <title>%s</title>
                      <link>%s</link>
                      <description>%s</description>
                      <pubDate>%s</pubDate>
                      %s
                    </item>
                  </channel>
                </rss>
                """.formatted(title, link, description, pubDate,
                commentsUrl == null ? "" : "<comments>" + commentsUrl + "</comments>");
    }

    private String hnDescription(String articleUrl, String commentsUrl, int points, int commentCount) {
        return """
                <![CDATA[
                <p>Article URL: <a href=\"%s\">%s</a></p>
                <p>Comments URL: <a href=\"%s\">%s</a></p>
                <p>Points: %d</p>
                <p># Comments: %d</p>
                ]]>
                """.formatted(articleUrl, articleUrl, commentsUrl, commentsUrl, points, commentCount);
    }

        private String sortTimestampToday(int hourUtc) {
                return LocalDate.now(ZoneOffset.UTC)
                                .atTime(hourUtc, 0)
                                .atOffset(ZoneOffset.UTC)
                                .format(DateTimeFormatter.ISO_OFFSET_DATE_TIME);
        }
}