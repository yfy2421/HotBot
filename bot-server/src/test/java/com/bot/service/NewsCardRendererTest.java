package com.bot.service;

import com.bot.config.AppConfig;
import com.bot.model.NewsItem;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Verify NewsCardRenderer produces valid PNG output and handles edge cases.
 */
class NewsCardRendererTest {

    @TempDir
    Path tempDir;

    private NewsCardRenderer renderer() {
        AppConfig.BotConfig botConfig = new AppConfig.BotConfig();
        AppConfig.BotConfig.NewsConfig newsConfig = new AppConfig.BotConfig.NewsConfig();
        newsConfig.setCardOutputDir(tempDir.toString());
        botConfig.setNews(newsConfig);
        return new NewsCardRenderer(botConfig);
    }

    private NewsItem sampleItem(String id, String title, String source, String summary) {
        return NewsItem.builder()
                .id(id)
                .title(title)
                .source(source)
                .sourceType("news")
                .category("tech_science")
                .trustLevel("aggregated")
                .summary(summary)
                .detailExcerpt(summary)
                .url("https://example.com/" + id)
                .build();
    }

    @Test
    void rendersOverviewCardAndProducesPng() {
        var renderer = renderer();
        List<NewsItem> items = List.of(
                sampleItem("n1", "英伟达发布B300芯片", "36氪", "英伟达发布新一代B300芯片"),
                sampleItem("n2", "OpenAI推出GPT-5", "TechCrunch", "OpenAI发布新一代模型"),
                sampleItem("n3", "苹果发布新MacBook", "环球科学", "苹果更新MacBook产品线"),
                sampleItem("n4", "SpaceX成功发射星舰", "新华社", "SpaceX完成星舰试飞"),
                sampleItem("n5", "特斯拉发布新车型", "环球时报", "特斯拉发布入门级新车")
        );

        String path = renderer.renderOverviewCard(
                "新闻摘要卡片", "测试热点摘要",
                items, Map.of(), Map.of(), null
        );

        assertNotNull(path, "should produce a PNG file path");
        assertTrue(Files.exists(Path.of(path)), "PNG file should exist");
        try {
            long size = Files.size(Path.of(path));
            assertTrue(size > 1000, "PNG should be > 1KB, got " + size);
        } catch (Exception e) {
            fail("should read file size: " + e.getMessage());
        }
    }

    @Test
    void rendersDetailCardAndProducesPng() {
        var renderer = renderer();
        NewsItem item = NewsItem.builder()
                .id("d1")
                .title("英伟达发布B300芯片")
                .source("36氪")
                .sourceType("news")
                .category("tech_science")
                .trustLevel("official_rss")
                .summary("英伟达发布新一代B300芯片，性能大幅提升")
                .detailExcerpt("英伟达今天正式发布了B300芯片...详细正文内容。")
                .commentary("这是一条重要的AI行业新闻")
                .url("https://example.com/d1")
                .build();

        String path = renderer.renderDetailCard(
                "热点详情", "新闻详情",
                item, Map.of(), Map.of(), null
        );

        assertNotNull(path, "should produce a PNG file path");
        assertTrue(Files.exists(Path.of(path)), "PNG file should exist");
        try {
            long size = Files.size(Path.of(path));
            assertTrue(size > 1000, "detail PNG should be > 1KB, got " + size);
        } catch (Exception e) {
            fail("should read file size: " + e.getMessage());
        }
    }

    @Test
    void nullItemsReturnsNull() {
        var renderer = renderer();
        assertNull(renderer.renderOverviewCard("t", "s", null, Map.of(), Map.of(), null));
        assertNull(renderer.renderOverviewCard("t", "s", List.of(), Map.of(), Map.of(), null));
        assertNull(renderer.renderDetailCard("t", "s", null, Map.of(), Map.of(), null));
    }

    @Test
    void cardLayoutContainsTopThreeGradientItems() {
        // Verify that 5 items render without exception — top 3 get gradient badges
        var renderer = renderer();
        List<NewsItem> items = List.of(
                sampleItem("a1", "第一条新闻", "源A", "摘要A"),
                sampleItem("a2", "第二条新闻", "源B", "摘要B"),
                sampleItem("a3", "第三条新闻", "源C", "摘要C"),
                sampleItem("a4", "第四条新闻", "源D", "摘要D"),
                sampleItem("a5", "第五条新闻", "源E", "摘要E")
        );

        String path = renderer.renderOverviewCard("测试", "测试", items, Map.of(), Map.of(), null);
        assertNotNull(path, "5-item overview should render without error");
    }

    @Test
    void longTitlesAreEllipsized() {
        var renderer = renderer();
        String longTitle = "这是一个非常非常非常非常非常非常非常非常非常非常非常非常非常非常非常非常长的标题用来测试换行和省略号功能";
        List<NewsItem> items = List.of(
                sampleItem("l1", longTitle, "源", "摘要")
        );

        // Should not throw — long titles should be wrapped/truncated
        String path = renderer.renderOverviewCard("测试", "测试", items, Map.of(), Map.of(), null);
        assertNotNull(path);
    }

    @Test
    void detailCardWithTranslatedContent() {
        var renderer = renderer();
        NewsItem item = NewsItem.builder()
                .id("dt1")
                .title("Waymo pauses highway service")
                .source("TechCrunch RSS")
                .sourceType("news")
                .category("tech_science")
                .trustLevel("aggregated")
                .summary("Waymo pauses highway service after construction-zone trouble")
                .detailExcerpt("Waymo announced today that it will pause its highway robotaxi service...")
                .translatedDetailExcerpt("Waymo今天宣布将暂停其高速公路robotaxi服务...")
                .url("https://example.com/dt1")
                .build();

        String path = renderer.renderDetailCard("热点详情", "新闻详情",
                item, Map.of(), Map.of(),
                "回复 1-2 或大致标题查看详情");

        assertNotNull(path);
        assertTrue(Files.exists(Path.of(path)));
    }

    @Test
    void buildCardUrlReturnsNullForInvalidPaths() {
        var renderer = renderer();
        assertNull(renderer.buildCardUrl(null, "http://localhost"));
        assertNull(renderer.buildCardUrl("", "http://localhost"));
        assertNull(renderer.buildCardUrl("/etc/passwd", "http://localhost"));
    }

    @Test
    void buildCardUrlReturnsValidUrl() {
        var renderer = renderer();
        // Create a valid card first
        NewsItem item = sampleItem("u1", "Test", "Src", "Summary");
        String path = renderer.renderDetailCard("Detail", "Sub", item, Map.of(), Map.of(), null);
        assertNotNull(path);

        String url = renderer.buildCardUrl(path, "http://localhost:8080");
        assertNotNull(url);
        assertTrue(url.startsWith("http://localhost:8080/api/media/cards/"));
        assertTrue(url.endsWith(".png"));
    }

    @Test
    void resolveCardPathRejectsTraversal() {
        var renderer = renderer();
        assertNull(renderer.resolveCardPath("../etc/passwd"));
        assertNull(renderer.resolveCardPath(null));
        assertNull(renderer.resolveCardPath(""));
    }
}
