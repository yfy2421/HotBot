package com.bot.service;

import com.bot.model.NewsItem;
import com.bot.model.PushMessage;
import com.bot.model.SystemAlert;
import com.bot.model.WeatherInfo;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class TemplateRenderer {

    public String render(PushMessage msg) {
        var sb = new StringBuilder();

        // ---- Header ----
        WeatherInfo w = msg.getWeather();
        sb.append("📅 ").append(msg.getDate()).append(" ").append(msg.getDayOfWeek())
                .append(" | ").append(weatherIcon(w.getCondition())).append(" ")
                .append(w.getCondition()).append(" ").append(w.getTempLow()).append("°C~")
                .append(w.getTempHigh()).append("°C")
                .append(" | 💨 ").append(w.getAqiLevel())
                .append("\n\n");

        // ---- News section ----
        sb.append("━━━━━━━━━━━━━━━━━━━━\n");
        sb.append("📰 今日要闻 (").append(msg.getNewsList().size()).append("条)\n");
        sb.append("━━━━━━━━━━━━━━━━━━━━\n\n");

        List<NewsItem> newsList = msg.getNewsList();
        for (int i = 0; i < newsList.size(); i++) {
            NewsItem n = newsList.get(i);
            sb.append(i + 1).append(". ").append(n.getTitle()).append("\n");

            if (n.getCommentary() != null && !n.getCommentary().isEmpty()) {
                sb.append("   🤖 AI点评: ").append(n.getCommentary()).append("\n");
            }
            if (n.getSentiment() != null && !n.getSentiment().isEmpty()) {
                sb.append("   👥 舆论: ").append(n.getSentiment()).append("\n");
            }
            if (n.getFollowUpTag() != null && !n.getFollowUpTag().isEmpty()) {
                sb.append("   ").append(n.getFollowUpTag()).append("\n");
            }
            sb.append("\n");
        }

        // ---- Topic tracking section ----
        if (msg.getTrackingLines() != null && !msg.getTrackingLines().isEmpty()) {
            sb.append("━━━━━━━━━━━━━━━━━━━━\n");
            sb.append("📎 话题追踪\n");
            sb.append("━━━━━━━━━━━━━━━━━━━━\n");
            for (String line : msg.getTrackingLines()) {
                sb.append(line).append("\n");
            }
            sb.append("\n");
        }

        if (msg.getSystemAlerts() != null && !msg.getSystemAlerts().isEmpty()) {
            sb.append("━━━━━━━━━━━━━━━━━━━━\n");
            sb.append("⚠️ 数据告警摘要\n");
            sb.append("━━━━━━━━━━━━━━━━━━━━\n");
            for (SystemAlert alert : msg.getSystemAlerts()) {
                sb.append("- [")
                        .append(alert.severity())
                        .append("] ")
                        .append(alert.source())
                        .append(" / ")
                        .append(alert.code())
                        .append(": ")
                        .append(alert.message())
                        .append("\n");
            }
            sb.append("\n");
        }

        // ---- Footer ----
        sb.append("━━━━━━━━━━━━━━━━━━━━\n");
        sb.append("💬 历史上的今天: ").append(msg.getHistoryToday() != null ? msg.getHistoryToday() : "—").append("\n");
        if (msg.getDailyWord() != null) {
            sb.append("📖 每日单词: ").append(msg.getDailyWord().getWord())
                    .append(" — ").append(msg.getDailyWord().getDefinition()).append("\n");
        }
        if (msg.getDailyJoke() != null) {
            sb.append("😄 每日一笑: ").append(msg.getDailyJoke().getContent()).append("\n");
        }
        sb.append("━━━━━━━━━━━━━━━━━━━━\n");

        return sb.toString();
    }

    private String weatherIcon(String condition) {
        if (condition == null) return "☀️";
        return switch (condition) {
            case "晴" -> "☀️";
            case "多云" -> "⛅";
            case "阴" -> "☁️";
            case "小雨", "阵雨" -> "🌧️";
            case "中雨", "大雨" -> "🌧️";
            case "雪" -> "❄️";
            default -> "🌤️";
        };
    }
}
