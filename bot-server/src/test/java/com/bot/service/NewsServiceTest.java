package com.bot.service;

import com.bot.model.FetchResult;
import com.bot.model.NewsItem;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestTemplate;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class NewsServiceTest {

    @Test
    void fetchAllIncludesNewRssFeedsAndKeepsNewestItems() {
        RestTemplate restTemplate = mock(RestTemplate.class);
                NewsService service = new NewsService(restTemplate, Runnable::run);

        when(restTemplate.getForObject(eq("https://techcrunch.com/feed/"), eq(String.class)))
                .thenReturn(rss("TechCrunch 标题", "https://tech.example/1", "Thu, 21 May 2026 08:00:00 +0000"));
        when(restTemplate.getForObject(eq("https://plink.anyfeeder.com/newscn/whxw"), eq(String.class)))
                .thenReturn(rss("新华社 标题", "https://xinhua.example/1", "Thu, 21 May 2026 09:00:00 +0000"));
        when(restTemplate.getForObject(eq("https://plink.anyfeeder.com/weixin/ScientificAmerican"), eq(String.class)))
                .thenReturn(rss("环球科学 标题", "https://science.example/1", "Thu, 21 May 2026 10:00:00 +0000"));
        when(restTemplate.getForObject(eq("https://plink.anyfeeder.com/weixin/hqsbwx"), eq(String.class)))
                .thenReturn(rss("环球时报 标题", "https://globaltimes.example/1", "Thu, 21 May 2026 11:00:00 +0000"));
        when(restTemplate.getForObject(eq("https://plink.anyfeeder.com/weixin/dili360"), eq(String.class)))
                .thenReturn(rss("中国国家地理 标题", "https://dili360.example/1", "Thu, 21 May 2026 12:00:00 +0000"));
        when(restTemplate.getForObject(eq("https://plink.anyfeeder.com/appinn"), eq(String.class)))
                .thenReturn(rss("小众软件 标题", "https://appinn.example/1", "Thu, 21 May 2026 13:00:00 +0000"));
        when(restTemplate.getForObject(eq("https://hnrss.org/frontpage?count=5"), eq(String.class)))
                .thenReturn(rss("HackerNews 标题", "https://news.ycombinator.com/item?id=1", "Thu, 21 May 2026 07:00:00 +0000"));
        when(restTemplate.postForObject(eq("https://api.juejin.cn/content_api/v1/content/article_rank?type=hot"), any(), eq(Map.class)))
                .thenReturn(Map.of("data", List.of(
                        Map.of("article_id", "jj1", "content", Map.of("title", "掘金 标题", "brief", "brief"))
                )));
        when(restTemplate.getForObject(eq("https://news-at.zhihu.com/api/4/news/latest"), eq(Map.class)))
                .thenReturn(Map.of(
                        "date", "20260520",
                        "stories", List.of(Map.of("id", 1, "title", "知乎 标题", "hint", "hint", "url", "https://zhihu.example/1"))
                ));

        FetchResult<List<NewsItem>> result = service.fetchAllWithAlert();

        assertEquals(8, result.data().size());
        assertTrue(result.data().stream().map(NewsItem::getSource).toList().contains("环球科学"));
        assertTrue(result.data().stream().map(NewsItem::getSource).toList().contains("小众软件"));
        assertEquals("小众软件", result.data().get(0).getSource());
    }

    private String rss(String title, String link, String pubDate) {
        return """
                <rss version=\"2.0\">
                  <channel>
                    <title>Feed</title>
                    <item>
                      <title>%s</title>
                      <link>%s</link>
                      <description>summary</description>
                      <pubDate>%s</pubDate>
                    </item>
                  </channel>
                </rss>
                """.formatted(title, link, pubDate);
    }
}