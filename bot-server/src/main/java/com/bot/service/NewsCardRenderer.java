package com.bot.service;

import com.bot.config.AppConfig;
import com.bot.model.NewsItem;
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
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.attribute.FileTime;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;

@Slf4j
@Service
public class NewsCardRenderer {

    private static final int CARD_WIDTH = 1120;
    private static final int OUTER_PADDING = 42;
    private static final int HEADER_PADDING = 32;
    private static final int ITEM_GAP = 18;
    private static final int ITEM_PADDING = 24;
    private static final int INDEX_BADGE_SIZE = 42;
    private static final int MAX_ITEMS = 4;
    private static final Duration STALE_FILE_RETENTION = Duration.ofHours(12);
    private static final ZoneId DISPLAY_ZONE = ZoneId.of("Asia/Shanghai");
    private static final DateTimeFormatter DATE_TIME_FORMAT = DateTimeFormatter.ofPattern("M月d日 HH:mm");
    private static final DateTimeFormatter DATE_FORMAT = DateTimeFormatter.ofPattern("M月d日");
    private static final Font TITLE_FONT = new Font("Microsoft YaHei", Font.BOLD, 40);
    private static final Font SUBTITLE_FONT = new Font("Microsoft YaHei", Font.PLAIN, 20);
    private static final Font ITEM_TITLE_FONT = new Font("Microsoft YaHei", Font.BOLD, 26);
    private static final Font META_FONT = new Font("Microsoft YaHei", Font.PLAIN, 18);
    private static final Font TAG_FONT = new Font("Microsoft YaHei", Font.BOLD, 17);
    private static final Font BADGE_FONT = new Font("Microsoft YaHei", Font.BOLD, 18);
    private static final Font FOOTER_FONT = new Font("Microsoft YaHei", Font.PLAIN, 16);

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
        if (items == null || items.isEmpty()) {
            return null;
        }

        try {
            Files.createDirectories(outputDir);
            cleanupStaleFiles();

            BufferedImage measurementImage = new BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB);
            Graphics2D measurementGraphics = measurementImage.createGraphics();
            configureGraphics(measurementGraphics);
            Layout layout;
            try {
                layout = buildLayout(measurementGraphics, title, subtitle, items, translatedTitles);
            } finally {
                measurementGraphics.dispose();
            }

            BufferedImage image = new BufferedImage(CARD_WIDTH, layout.totalHeight(), BufferedImage.TYPE_INT_ARGB);
            Graphics2D graphics = image.createGraphics();
            configureGraphics(graphics);
            try {
                paintBackground(graphics, layout.totalHeight());
                int currentY = paintHeader(graphics, title, subtitle, layout.subtitleLines());
                currentY += 20;
                for (LayoutItem item : layout.items()) {
                    currentY = paintItem(graphics, item, currentY);
                    currentY += ITEM_GAP;
                }
                paintFooter(graphics, layout.totalHeight());
            } finally {
                graphics.dispose();
            }

            Path filePath = outputDir.resolve("news-card-" + System.currentTimeMillis() + "-" + UUID.randomUUID().toString().substring(0, 8) + ".png");
            ImageIO.write(image, "png", filePath.toFile());
            return filePath.toAbsolutePath().toString();
        } catch (Exception e) {
            log.warn("Failed to render news card: {}", e.getMessage());
            return null;
        }
    }

    private Layout buildLayout(Graphics2D graphics, String title, String subtitle, List<NewsItem> items, Map<String, String> translatedTitles) {
        List<String> subtitleLines = wrapText(graphics, defaultText(subtitle, "热点摘要"), SUBTITLE_FONT, CARD_WIDTH - OUTER_PADDING * 2 - HEADER_PADDING * 2, 3);
        int titleHeight = lineHeight(graphics, TITLE_FONT);
        int subtitleHeight = subtitleLines.isEmpty() ? 0 : subtitleLines.size() * lineHeight(graphics, SUBTITLE_FONT);
        int headerHeight = HEADER_PADDING * 2 + titleHeight + subtitleHeight + 26;

        List<LayoutItem> layoutItems = new ArrayList<>();
        int totalHeight = OUTER_PADDING + headerHeight + 24;
        List<NewsItem> scopedItems = items.stream().limit(MAX_ITEMS).toList();
        int contentWidth = CARD_WIDTH - OUTER_PADDING * 2 - ITEM_PADDING * 2 - INDEX_BADGE_SIZE - 18;
        for (int index = 0; index < scopedItems.size(); index++) {
            NewsItem item = scopedItems.get(index);
            String displayTitle = resolveDisplayTitle(item, translatedTitles);
            List<String> titleLines = wrapText(graphics, displayTitle, ITEM_TITLE_FONT, contentWidth, 3);
            List<String> metaLines = wrapText(graphics, buildMetaLine(item), META_FONT, contentWidth, 2);
            List<String> followUpLines = hasText(item.getFollowUpTag())
                    ? wrapText(graphics, item.getFollowUpTag(), TAG_FONT, contentWidth - 24, 2)
                    : List.of();
            int itemHeight = ITEM_PADDING * 2
                    + titleLines.size() * lineHeight(graphics, ITEM_TITLE_FONT)
                    + 8
                    + metaLines.size() * lineHeight(graphics, META_FONT);
            if (!followUpLines.isEmpty()) {
                itemHeight += 16 + followUpLines.size() * lineHeight(graphics, TAG_FONT) + 18;
            }
            layoutItems.add(new LayoutItem(index + 1, titleLines, metaLines, followUpLines, itemHeight));
            totalHeight += itemHeight + ITEM_GAP;
        }
        totalHeight += 44;
        return new Layout(headerHeight, subtitleLines, layoutItems, totalHeight);
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
        int headerHeight = HEADER_PADDING * 2
                + lineHeight(graphics, TITLE_FONT)
                + (subtitleLines.isEmpty() ? 0 : subtitleLines.size() * lineHeight(graphics, SUBTITLE_FONT))
                + 26;
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
        graphics.drawString("生成时间  " + DATE_TIME_FORMAT.format(ZonedDateTime.now(DISPLAY_ZONE)), textX, y + headerHeight - 18);
        return y + headerHeight;
    }

    private int paintItem(Graphics2D graphics, LayoutItem item, int currentY) {
        int x = OUTER_PADDING;
        int width = CARD_WIDTH - OUTER_PADDING * 2;
        paintShadow(graphics, x, currentY, width, item.height(), 28, new Color(14, 35, 64, 16));
        graphics.setColor(new Color(255, 252, 248, 242));
        graphics.fillRoundRect(x, currentY, width, item.height(), 28, 28);
        graphics.setColor(new Color(227, 220, 211));
        graphics.drawRoundRect(x, currentY, width, item.height(), 28, 28);

        int badgeX = x + ITEM_PADDING;
        int badgeY = currentY + ITEM_PADDING;
        graphics.setColor(new Color(29, 67, 116));
        graphics.fillOval(badgeX, badgeY, INDEX_BADGE_SIZE, INDEX_BADGE_SIZE);
        graphics.setFont(BADGE_FONT);
        graphics.setColor(Color.WHITE);
        String indexText = String.valueOf(item.index());
        int badgeTextWidth = graphics.getFontMetrics().stringWidth(indexText);
        int badgeTextX = badgeX + (INDEX_BADGE_SIZE - badgeTextWidth) / 2;
        int badgeTextY = badgeY + ((INDEX_BADGE_SIZE - graphics.getFontMetrics().getHeight()) / 2) + graphics.getFontMetrics().getAscent();
        graphics.drawString(indexText, badgeTextX, badgeTextY);

        int textX = badgeX + INDEX_BADGE_SIZE + 18;
        int textY = currentY + ITEM_PADDING + graphics.getFontMetrics(ITEM_TITLE_FONT).getAscent();
        graphics.setFont(ITEM_TITLE_FONT);
        graphics.setColor(new Color(36, 40, 47));
        for (String line : item.titleLines()) {
            graphics.drawString(line, textX, textY);
            textY += lineHeight(graphics, ITEM_TITLE_FONT);
        }

        textY += 4;
        graphics.setFont(META_FONT);
        graphics.setColor(new Color(92, 96, 104));
        for (String line : item.metaLines()) {
            graphics.drawString(line, textX, textY);
            textY += lineHeight(graphics, META_FONT);
        }

        if (!item.followUpLines().isEmpty()) {
            textY += 8;
            int pillHeight = item.followUpLines().size() * lineHeight(graphics, TAG_FONT) + 16;
            graphics.setColor(new Color(231, 239, 255));
            graphics.fillRoundRect(textX - 10, textY - graphics.getFontMetrics(TAG_FONT).getAscent(), CARD_WIDTH - OUTER_PADDING * 2 - ITEM_PADDING * 2 - INDEX_BADGE_SIZE - 8, pillHeight, 18, 18);
            graphics.setColor(new Color(81, 110, 168));
            graphics.setFont(TAG_FONT);
            for (String line : item.followUpLines()) {
                graphics.drawString(line, textX, textY);
                textY += lineHeight(graphics, TAG_FONT);
            }
        }
        return currentY + item.height();
    }

    private void paintFooter(Graphics2D graphics, int height) {
        graphics.setFont(FOOTER_FONT);
        graphics.setColor(new Color(98, 104, 114));
        graphics.drawString("热点追踪分析 bot  ·  图片版新闻摘要", OUTER_PADDING, height - 18);
    }

    private void paintShadow(Graphics2D graphics, int x, int y, int width, int height, int radius, Color shadowColor) {
        graphics.setColor(shadowColor);
        graphics.fillRoundRect(x + 6, y + 8, width, height, radius, radius);
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
        return lines.stream().filter(this::hasText).toList();
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

    private String buildMetaLine(NewsItem item) {
        List<String> parts = new ArrayList<>();
        parts.add(defaultText(item.getSource(), "未知来源"));
        parts.add(displaySourceType(item.getSourceType()));
        parts.add(displayCategory(item.getCategory()));
        parts.add(displayTrustLevel(item.getTrustLevel()));
        String displayTime = resolveDisplayTime(item);
        if (hasText(displayTime)) {
            parts.add(displayTime);
        }
        return String.join("  ·  ", parts);
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
        String publishTime = formatTime(item.getPublishTime());
        if (hasText(publishTime)) {
            return publishTime;
        }
        return formatTime(item.getFetchedAt());
    }

    private String formatTime(String rawTime) {
        if (!hasText(rawTime)) {
            return "";
        }
        try {
            return OffsetDateTime.parse(rawTime, DateTimeFormatter.ISO_OFFSET_DATE_TIME)
                    .atZoneSameInstant(DISPLAY_ZONE)
                    .format(DATE_TIME_FORMAT);
        } catch (DateTimeParseException ignored) {
        }
        try {
            return ZonedDateTime.parse(rawTime, DateTimeFormatter.RFC_1123_DATE_TIME)
                    .withZoneSameInstant(DISPLAY_ZONE)
                    .format(DATE_TIME_FORMAT);
        } catch (DateTimeParseException ignored) {
        }
        try {
            return LocalDate.parse(rawTime, DateTimeFormatter.ISO_DATE)
                    .format(DATE_FORMAT);
        } catch (DateTimeParseException ignored) {
        }
        return rawTime;
    }

    private String displaySourceType(String sourceType) {
        return switch (defaultText(sourceType, "all")) {
            case "news" -> "新闻源";
            case "hotlist" -> "热榜源";
            case "all" -> "全部来源";
            default -> sourceType;
        };
    }

    private String displayCategory(String category) {
        return switch (defaultText(category, "all")) {
            case "tech" -> "科技";
            case "general" -> "综合";
            case "all" -> "全部分类";
            default -> category;
        };
    }

    private String displayTrustLevel(String trustLevel) {
        return switch (defaultText(trustLevel, "unknown")) {
            case "official_rss" -> "官方 RSS";
            case "aggregated" -> "聚合来源";
            case "community" -> "社区热榜";
            default -> trustLevel;
        };
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

    private String defaultText(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private record Layout(int headerHeight, List<String> subtitleLines, List<LayoutItem> items, int totalHeight) {
    }

    private record LayoutItem(int index, List<String> titleLines, List<String> metaLines, List<String> followUpLines, int height) {
    }
}