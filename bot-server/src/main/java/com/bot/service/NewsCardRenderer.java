package com.bot.service;

import com.bot.config.AppConfig;
import com.bot.model.NewsItem;
import com.bot.util.NewsDisplayText;
import com.bot.util.NewsTimeUtils;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import javax.imageio.ImageIO;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.GradientPaint;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.attribute.FileTime;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;

import static com.bot.util.TextUtils.defaultText;
import static com.bot.util.TextUtils.hasText;

@Slf4j
@Service
public class NewsCardRenderer {

    private static final int CARD_WIDTH = 1120;
    private static final int OUTER_PADDING = 42;
    private static final int HEADER_PADDING = 32;
    private static final int ITEM_GAP = 18;
    private static final int ITEM_PADDING = 24;
    private static final int INDEX_BADGE_SIZE = 42;
    private static final int MAX_ITEMS = 12;
    private static final Duration STALE_FILE_RETENTION = Duration.ofHours(12);
    private static final Font TITLE_FONT = new Font("Microsoft YaHei", Font.BOLD, 40);
    private static final Font SUBTITLE_FONT = new Font("Microsoft YaHei", Font.PLAIN, 20);
    private static final Font ITEM_TITLE_FONT = new Font("Microsoft YaHei", Font.BOLD, 26);
    private static final Font META_FONT = new Font("Microsoft YaHei", Font.PLAIN, 18);
    private static final Font SUMMARY_FONT = new Font("Microsoft YaHei", Font.PLAIN, 19);
    private static final Font BADGE_FONT = new Font("Microsoft YaHei", Font.BOLD, 18);
    private static final Font FOOTER_FONT = new Font("Microsoft YaHei", Font.PLAIN, 16);
    private static final Font HINT_FONT = new Font("Microsoft YaHei", Font.PLAIN, 16);

    private final Path outputDir;

    public NewsCardRenderer(AppConfig.BotConfig botConfig) {
        String configuredOutputDir = botConfig != null
                && botConfig.getNews() != null
                ? botConfig.getNews().getCardOutputDir()
                : null;
        this.outputDir = hasText(configuredOutputDir)
                ? Paths.get(configuredOutputDir).toAbsolutePath().normalize()
                : Paths.get(System.getProperty("java.io.tmpdir"), "hotspot-bot", "news-cards").toAbsolutePath().normalize();
    }

    public String renderNewsCard(String title, String subtitle, List<NewsItem> items, Map<String, String> translatedTitles) {
        return renderOverviewCard(title, subtitle, items, translatedTitles, Map.of(), null);
    }

    public String renderOverviewCard(String title,
                                     String subtitle,
                                     List<NewsItem> items,
                                     Map<String, String> translatedTitles,
                                     Map<String, String> translatedSummaries,
                                     String promptText) {
        if (items == null || items.isEmpty()) {
            return null;
        }
        try {
            Files.createDirectories(outputDir);
            cleanupStaleFiles();

            CardLayout layout = measureLayout(graphics -> buildOverviewLayout(graphics, title, subtitle, items, translatedTitles, translatedSummaries, promptText));
            BufferedImage image = new BufferedImage(CARD_WIDTH, layout.totalHeight(), BufferedImage.TYPE_INT_ARGB);
            Graphics2D graphics = image.createGraphics();
            configureGraphics(graphics);
            try {
                paintBackground(graphics, layout.totalHeight());
                int currentY = paintHeader(graphics, title, subtitle, layout.subtitleLines());
                currentY += 20;
                for (CardBlock block : layout.blocks()) {
                    currentY = paintBlock(graphics, block, currentY);
                    currentY += ITEM_GAP;
                }
                paintFooter(graphics, layout.totalHeight(), layout.footerLines());
            } finally {
                graphics.dispose();
            }
            return writeImage(image);
        } catch (Exception e) {
            log.warn("Failed to render overview news card: {}", e.getMessage());
            return null;
        }
    }

    public String renderDetailCard(String title,
                                   String subtitle,
                                   NewsItem item,
                                   Map<String, String> translatedTitles,
                                   Map<String, String> translatedSummaries,
                                   String promptText) {
        if (item == null) {
            return null;
        }
        try {
            Files.createDirectories(outputDir);
            cleanupStaleFiles();

            CardLayout layout = measureLayout(graphics -> buildDetailLayout(graphics, title, subtitle, item, translatedTitles, translatedSummaries, promptText));
            BufferedImage image = new BufferedImage(CARD_WIDTH, layout.totalHeight(), BufferedImage.TYPE_INT_ARGB);
            Graphics2D graphics = image.createGraphics();
            configureGraphics(graphics);
            try {
                paintBackground(graphics, layout.totalHeight());
                int currentY = paintHeader(graphics, title, subtitle, layout.subtitleLines());
                currentY += 20;
                for (CardBlock block : layout.blocks()) {
                    currentY = paintBlock(graphics, block, currentY);
                    currentY += ITEM_GAP;
                }
                paintFooter(graphics, layout.totalHeight(), layout.footerLines());
            } finally {
                graphics.dispose();
            }
            return writeImage(image);
        } catch (Exception e) {
            log.warn("Failed to render detail news card: {}", e.getMessage());
            return null;
        }
    }

    public String buildCardUrl(String filePath, String baseUrl) {
        if (!hasText(filePath) || !hasText(baseUrl)) {
            return null;
        }
        try {
            Path normalizedPath = Paths.get(filePath).toAbsolutePath().normalize();
            if (!normalizedPath.startsWith(outputDir)) {
                return null;
            }
            String encodedFileName = URLEncoder.encode(normalizedPath.getFileName().toString(), StandardCharsets.UTF_8)
                    .replace("+", "%20");
            return baseUrl.replaceAll("/+$", "") + "/api/media/cards/" + encodedFileName;
        } catch (Exception e) {
            log.debug("Failed to build card url for {}: {}", filePath, e.getMessage());
            return null;
        }
    }

    public Path resolveCardPath(String fileName) {
        if (!hasText(fileName)) {
            return null;
        }
        try {
            Path path = outputDir.resolve(fileName).normalize();
            if (!path.startsWith(outputDir)) {
                return null;
            }
            return path;
        } catch (Exception e) {
            log.debug("Failed to resolve card path for {}: {}", fileName, e.getMessage());
            return null;
        }
    }

    private CardLayout buildOverviewLayout(Graphics2D graphics,
                                           String title,
                                           String subtitle,
                                           List<NewsItem> items,
                                           Map<String, String> translatedTitles,
                                           Map<String, String> translatedSummaries,
                                           String promptText) {
        List<String> subtitleLines = wrapText(graphics, defaultText(subtitle, "热点摘要"), SUBTITLE_FONT,
                CARD_WIDTH - OUTER_PADDING * 2 - HEADER_PADDING * 2, 3);
        int headerHeight = headerHeight(graphics, subtitleLines);

        List<CardBlock> blocks = new ArrayList<>();
        int totalHeight = OUTER_PADDING + headerHeight + 20;
        List<NewsItem> scopedItems = items.stream().limit(MAX_ITEMS).toList();
        for (int index = 0; index < scopedItems.size(); index++) {
            CardBlock block = buildBlock(graphics, scopedItems.get(index), translatedTitles, translatedSummaries, index + 1, true, 2, 3, false);
            blocks.add(block);
            totalHeight += block.height() + ITEM_GAP;
        }

        List<String> footerLines = wrapFooterLines(graphics, promptText);
        totalHeight += footerHeight(graphics, footerLines);
        return new CardLayout(subtitleLines, blocks, footerLines, totalHeight);
    }

    private CardLayout buildDetailLayout(Graphics2D graphics,
                                         String title,
                                         String subtitle,
                                         NewsItem item,
                                         Map<String, String> translatedTitles,
                                         Map<String, String> translatedSummaries,
                                         String promptText) {
        List<String> subtitleLines = wrapText(graphics, defaultText(subtitle, "热点详情"), SUBTITLE_FONT,
                CARD_WIDTH - OUTER_PADDING * 2 - HEADER_PADDING * 2, 3);
        int headerHeight = headerHeight(graphics, subtitleLines);

        CardBlock block = buildBlock(graphics, item, translatedTitles, translatedSummaries, 0, false, 3, 52, true);
        List<String> footerLines = wrapFooterLines(graphics, promptText);
        int totalHeight = OUTER_PADDING + headerHeight + 20 + block.height() + ITEM_GAP + footerHeight(graphics, footerLines);
        return new CardLayout(subtitleLines, List.of(block), footerLines, totalHeight);
    }

    private CardBlock buildBlock(Graphics2D graphics,
                                 NewsItem item,
                                 Map<String, String> translatedTitles,
                                 Map<String, String> translatedSummaries,
                                 int index,
                                 boolean showBadge,
                                 int titleMaxLines,
                                 int summaryMaxLines,
                                 boolean detailMode) {
        int textWidth = CARD_WIDTH - OUTER_PADDING * 2 - ITEM_PADDING * 2 - (showBadge ? INDEX_BADGE_SIZE + 18 : 0);
        List<String> titleLines = wrapText(graphics, resolveDisplayTitle(item, translatedTitles), ITEM_TITLE_FONT, textWidth, titleMaxLines);
        List<String> metaLines = wrapText(graphics, buildMetaLine(item), META_FONT, textWidth, 2);
        List<String> summaryLines = wrapText(graphics, buildSummaryText(item, translatedSummaries, detailMode), SUMMARY_FONT, textWidth, summaryMaxLines);

        int height = ITEM_PADDING * 2
                + titleLines.size() * lineHeight(graphics, ITEM_TITLE_FONT)
                + 8
                + metaLines.size() * lineHeight(graphics, META_FONT);
        if (!summaryLines.isEmpty()) {
            height += 14 + summaryLines.size() * lineHeight(graphics, SUMMARY_FONT);
        }
        return new CardBlock(index, showBadge, titleLines, metaLines, summaryLines, height);
    }

    private CardLayout measureLayout(LayoutBuilder builder) {
        BufferedImage measurementImage = new BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB);
        Graphics2D graphics = measurementImage.createGraphics();
        configureGraphics(graphics);
        try {
            return builder.build(graphics);
        } finally {
            graphics.dispose();
        }
    }

    private String writeImage(BufferedImage image) throws Exception {
        Path filePath = outputDir.resolve("news-card-" + System.currentTimeMillis() + "-" + UUID.randomUUID().toString().substring(0, 8) + ".png");
        ImageIO.write(image, "png", filePath.toFile());
        return filePath.toAbsolutePath().toString();
    }

    private void configureGraphics(Graphics2D graphics) {
        graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        graphics.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        graphics.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
    }

    private void paintBackground(Graphics2D graphics, int height) {
        graphics.setPaint(new GradientPaint(0, 0, new Color(247, 243, 235), CARD_WIDTH, height, new Color(231, 239, 247)));
        graphics.fillRect(0, 0, CARD_WIDTH, height);

        graphics.setColor(new Color(28, 79, 145, 28));
        graphics.fillOval(CARD_WIDTH - 280, -120, 320, 320);
        graphics.setColor(new Color(201, 91, 52, 24));
        graphics.fillOval(-120, height - 280, 340, 340);
    }

    private int paintHeader(Graphics2D graphics, String title, String subtitle, List<String> subtitleLines) {
        int headerHeight = headerHeight(graphics, subtitleLines);
        int x = OUTER_PADDING;
        int y = OUTER_PADDING;
        int width = CARD_WIDTH - OUTER_PADDING * 2;

        paintShadow(graphics, x, y, width, headerHeight, 32, new Color(25, 51, 84, 20));
        graphics.setPaint(new GradientPaint(x, y, new Color(29, 67, 116), x + width, y + headerHeight, new Color(194, 96, 55)));
        graphics.fillRoundRect(x, y, width, headerHeight, 32, 32);

        graphics.setColor(new Color(255, 255, 255, 90));
        graphics.setStroke(new BasicStroke(1.2f));
        graphics.drawRoundRect(x, y, width, headerHeight, 32, 32);

        int textX = x + HEADER_PADDING;
        int currentY = y + HEADER_PADDING + lineHeight(graphics, TITLE_FONT);
        graphics.setFont(TITLE_FONT);
        graphics.setColor(Color.WHITE);
        graphics.drawString(defaultText(title, "新闻摘要卡片"), textX, currentY);

        currentY += 10;
        graphics.setFont(SUBTITLE_FONT);
        graphics.setColor(new Color(245, 247, 250));
        for (String line : subtitleLines) {
            currentY += lineHeight(graphics, SUBTITLE_FONT);
            graphics.drawString(line, textX, currentY);
        }

        graphics.setFont(FOOTER_FONT);
        graphics.setColor(new Color(255, 255, 255, 210));
        graphics.drawString("生成时间  " + NewsTimeUtils.formatDisplayTime(java.time.OffsetDateTime.now().toString()), textX, y + headerHeight - 18);
        return y + headerHeight;
    }

    private int paintBlock(Graphics2D graphics, CardBlock block, int currentY) {
        int x = OUTER_PADDING;
        int width = CARD_WIDTH - OUTER_PADDING * 2;
        paintShadow(graphics, x, currentY, width, block.height(), 28, new Color(14, 35, 64, 16));
        graphics.setColor(new Color(255, 252, 248, 242));
        graphics.fillRoundRect(x, currentY, width, block.height(), 28, 28);
        graphics.setColor(new Color(227, 220, 211));
        graphics.drawRoundRect(x, currentY, width, block.height(), 28, 28);

        int textX = x + ITEM_PADDING;
        if (block.showBadge()) {
            int badgeX = x + ITEM_PADDING;
            int badgeY = currentY + ITEM_PADDING;
            graphics.setColor(new Color(29, 67, 116));
            graphics.fillOval(badgeX, badgeY, INDEX_BADGE_SIZE, INDEX_BADGE_SIZE);
            graphics.setFont(BADGE_FONT);
            graphics.setColor(Color.WHITE);
            String indexText = String.valueOf(block.index());
            int badgeTextWidth = graphics.getFontMetrics(BADGE_FONT).stringWidth(indexText);
            int badgeTextX = badgeX + (INDEX_BADGE_SIZE - badgeTextWidth) / 2;
            int badgeTextY = badgeY + ((INDEX_BADGE_SIZE - graphics.getFontMetrics(BADGE_FONT).getHeight()) / 2) + graphics.getFontMetrics(BADGE_FONT).getAscent();
            graphics.drawString(indexText, badgeTextX, badgeTextY);
            textX = badgeX + INDEX_BADGE_SIZE + 18;
        }

        int textY = currentY + ITEM_PADDING + graphics.getFontMetrics(ITEM_TITLE_FONT).getAscent();
        graphics.setFont(ITEM_TITLE_FONT);
        graphics.setColor(new Color(36, 40, 47));
        for (String line : block.titleLines()) {
            graphics.drawString(line, textX, textY);
            textY += lineHeight(graphics, ITEM_TITLE_FONT);
        }

        textY += 4;
        graphics.setFont(META_FONT);
        graphics.setColor(new Color(92, 96, 104));
        for (String line : block.metaLines()) {
            graphics.drawString(line, textX, textY);
            textY += lineHeight(graphics, META_FONT);
        }

        if (!block.summaryLines().isEmpty()) {
            textY += 10;
            graphics.setFont(SUMMARY_FONT);
            graphics.setColor(new Color(59, 64, 72));
            for (String line : block.summaryLines()) {
                graphics.drawString(line, textX, textY);
                textY += lineHeight(graphics, SUMMARY_FONT);
            }
        }
        return currentY + block.height();
    }

    private void paintFooter(Graphics2D graphics, int height, List<String> footerLines) {
        int currentY = height - 44 - (footerLines.size() * lineHeight(graphics, HINT_FONT));
        if (!footerLines.isEmpty()) {
            graphics.setFont(HINT_FONT);
            graphics.setColor(new Color(78, 84, 92));
            for (String line : footerLines) {
                graphics.drawString(line, OUTER_PADDING, currentY);
                currentY += lineHeight(graphics, HINT_FONT);
            }
        }

        graphics.setFont(FOOTER_FONT);
        graphics.setColor(new Color(98, 104, 114));
        graphics.drawString("热点追踪分析 bot  ·  图片版新闻摘要", OUTER_PADDING, height - 18);
    }

    private void paintShadow(Graphics2D graphics, int x, int y, int width, int height, int radius, Color shadowColor) {
        graphics.setColor(shadowColor);
        graphics.fillRoundRect(x + 6, y + 8, width, height, radius, radius);
    }

    private List<String> wrapFooterLines(Graphics2D graphics, String promptText) {
        if (!hasText(promptText)) {
            return List.of();
        }
        return wrapText(graphics, promptText, HINT_FONT, CARD_WIDTH - OUTER_PADDING * 2, 2);
    }

    private List<String> wrapText(Graphics2D graphics, String text, Font font, int maxWidth, int maxLines) {
        graphics.setFont(font);
        String source = defaultText(text, "").replace("\r", "").trim();
        if (source.isEmpty()) {
            return List.of();
        }

        List<String> lines = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean truncated = false;
        for (int i = 0; i < source.length(); i++) {
            char ch = source.charAt(i);
            if (ch == '\n') {
                if (!current.isEmpty()) {
                    lines.add(current.toString().trim());
                    current.setLength(0);
                    if (lines.size() == maxLines) {
                        truncated = i < source.length() - 1;
                        break;
                    }
                }
                continue;
            }
            String candidate = current + String.valueOf(ch);
            if (graphics.getFontMetrics(font).stringWidth(candidate) <= maxWidth || current.isEmpty()) {
                current.append(ch);
                continue;
            }
            lines.add(current.toString().trim());
            current.setLength(0);
            if (!Character.isWhitespace(ch)) {
                current.append(ch);
            }
            if (lines.size() == maxLines) {
                truncated = true;
                break;
            }
        }
        if (!truncated && !current.isEmpty() && lines.size() < maxLines) {
            lines.add(current.toString().trim());
        }
        if (truncated && !lines.isEmpty()) {
            int lastIndex = lines.size() - 1;
            lines.set(lastIndex, ellipsize(graphics, lines.get(lastIndex), font, maxWidth));
        }
        return lines.stream().filter(com.bot.util.TextUtils::hasText).toList();
    }

    private String ellipsize(Graphics2D graphics, String text, Font font, int maxWidth) {
        graphics.setFont(font);
        String value = defaultText(text, "").trim();
        if (graphics.getFontMetrics(font).stringWidth(value + "…") <= maxWidth) {
            return value + "…";
        }
        while (!value.isEmpty() && graphics.getFontMetrics(font).stringWidth(value + "…") > maxWidth) {
            value = value.substring(0, value.length() - 1);
        }
        return value.isEmpty() ? "…" : value + "…";
    }

    private int lineHeight(Graphics2D graphics, Font font) {
        graphics.setFont(font);
        return graphics.getFontMetrics(font).getHeight() + 2;
    }

    private int headerHeight(Graphics2D graphics, List<String> subtitleLines) {
        int titleHeight = lineHeight(graphics, TITLE_FONT);
        int subtitleHeight = subtitleLines.isEmpty() ? 0 : subtitleLines.size() * lineHeight(graphics, SUBTITLE_FONT);
        return HEADER_PADDING * 2 + titleHeight + subtitleHeight + 26;
    }

    private int footerHeight(Graphics2D graphics, List<String> footerLines) {
        int hintHeight = footerLines.isEmpty() ? 0 : footerLines.size() * lineHeight(graphics, HINT_FONT) + 12;
        return hintHeight + 44;
    }

    private String buildMetaLine(NewsItem item) {
        List<String> parts = new ArrayList<>();
        String displayTime = resolveDisplayTime(item);
        if (hasText(displayTime)) {
            parts.add(displayTime);
        }
        parts.add(defaultText(item.getSource(), "未知来源"));
        parts.add(NewsDisplayText.displayCategory(item.getCategory()));
        parts.add(NewsDisplayText.displayTrustLevel(item.getTrustLevel()));
        return String.join("  ·  ", parts);
    }

    private String buildSummaryText(NewsItem item, Map<String, String> translatedSummaries, boolean detailMode) {
        if (detailMode) {
            String detailContent = sanitizeDetailText(resolveDetailText(item));
            String translatedDetailContent = sanitizeDetailText(resolveTranslatedDetailText(item));
            String commentary = sanitizeDetailText(item == null ? null : item.getCommentary());
            List<String> sections = new ArrayList<>();
            if (hasText(detailContent)) {
                sections.add((hasText(item == null ? null : item.getFullBody()) ? "原文正文\n" : "原文摘录\n") + detailContent);
            }
            if (hasText(translatedDetailContent)) {
                sections.add((hasText(item == null ? null : item.getTranslatedFullBody()) ? "中文译文\n" : "中文译文（摘录）\n") + translatedDetailContent);
            }
            if (hasText(commentary)) {
                sections.add("AI评价\n" + commentary);
            }
            if (!sections.isEmpty()) {
                return String.join("\n\n", sections);
            }
        }
        String summary = resolveDisplaySummary(item, translatedSummaries);
        if (hasText(summary)) {
            return summary;
        }
        return detailMode
                ? "暂无原始摘要，可直接回复“分析这条”查看更聚焦的判断。"
                : "暂无摘要。";
    }

    private String sanitizeSummary(String summary) {
        String cleaned = defaultText(summary, "")
                .replaceAll("<[^>]+>", " ")
                .replace("&nbsp;", " ")
                .replace("&quot;", "\"")
                .replace("&#39;", "'")
                .replace("&amp;", "&")
                .replaceAll("\\s+", " ")
                .trim();
        return cleaned;
    }

    private String sanitizeDetailText(String text) {
        return defaultText(text, "")
                .replaceAll("<[^>]+>", " ")
                .replace("&nbsp;", " ")
                .replace("&quot;", "\"")
                .replace("&#39;", "'")
                .replace("&amp;", "&")
                .replaceAll("[ \\t\\x0B\\f\\r]+", " ")
                .replaceAll("\n{5,}", "\n\n\n\n")
                .trim();
    }

    private String resolveDetailText(NewsItem item) {
        if (item == null) {
            return "";
        }
        return item.preferredDetailText();
    }

    private String resolveTranslatedDetailText(NewsItem item) {
        if (item == null) {
            return "";
        }
        return item.translatedPreferredDetailText();
    }

    private String resolveDisplaySummary(NewsItem item, Map<String, String> translatedSummaries) {
        String originalSummary = sanitizeSummary(item == null ? null : item.summaryPreviewText());
        if (!hasText(originalSummary)) {
            return "";
        }
        String translatedSummary = sanitizeSummary(item == null ? null : item.translatedSummaryPreviewText());
        if (hasText(translatedSummary)) {
            return translatedSummary;
        }
        translatedSummary = translatedSummaries == null ? null : translatedSummaries.get(originalSummary);
        return hasText(translatedSummary) ? translatedSummary : originalSummary;
    }

    private String resolveDisplayTitle(NewsItem item, Map<String, String> translatedTitles) {
        if (item == null) {
            return "无标题";
        }
        String originalTitle = item.getTitle();
        String translatedTitle = translatedTitles.get(originalTitle);
        return hasText(translatedTitle) ? translatedTitle : defaultText(originalTitle, "无标题");
    }

    private String resolveDisplayTime(NewsItem item) {
        if (item == null) {
            return "";
        }
        String publishTime = NewsTimeUtils.formatDisplayTime(item.getPublishTime());
        if (hasText(publishTime)) {
            return publishTime;
        }
        return NewsTimeUtils.formatDisplayTime(item.getFetchedAt());
    }

    private void cleanupStaleFiles() {
        Instant cutoff = Instant.now().minus(STALE_FILE_RETENTION);
        try (Stream<Path> files = Files.list(outputDir)) {
            files.filter(path -> path.getFileName().toString().startsWith("news-card-") && path.getFileName().toString().endsWith(".png"))
                    .forEach(path -> deleteIfStale(path, cutoff));
        } catch (Exception e) {
            log.debug("Skip stale news card cleanup: {}", e.getMessage());
        }
    }

    private void deleteIfStale(Path path, Instant cutoff) {
        try {
            FileTime fileTime = Files.getLastModifiedTime(path);
            if (fileTime.toInstant().isBefore(cutoff)) {
                Files.deleteIfExists(path);
            }
        } catch (Exception e) {
            log.debug("Skip deleting stale news card {}: {}", path, e.getMessage());
        }
    }


    @FunctionalInterface
    private interface LayoutBuilder {
        CardLayout build(Graphics2D graphics);
    }

    private record CardLayout(List<String> subtitleLines, List<CardBlock> blocks, List<String> footerLines, int totalHeight) {
    }

    private record CardBlock(int index,
                             boolean showBadge,
                             List<String> titleLines,
                             List<String> metaLines,
                             List<String> summaryLines,
                             int height) {
    }
}
