package com.bot.agent;

import com.bot.client.PythonMLClient;

import java.util.Map;

/**
 * Tool: analyze_article
 *
 * Generates an AI commentary for a given article title or content excerpt.
 */
public class AnalyzeArticleTool implements Tool {

    private final PythonMLClient mlClient;

    public AnalyzeArticleTool(PythonMLClient mlClient) {
        this.mlClient = mlClient;
    }

    @Override
    public String name() {
        return "analyze_article";
    }

    @Override
    public String description() {
        return "对一篇新闻进行 AI 点评分析，输出核心判断、为什么值得看、别高估什么、接下来观察什么。";
    }

    @Override
    public String parameters() {
        return """
                {
                  "title": "必填, 新闻标题",
                  "content": "可选, 新闻正文摘要（如有）"
                }""";
    }

    @Override
    public ToolsResult execute(Map<String, Object> args) {
        String title = stringArg(args, "title", null);
        String content = stringArg(args, "content", "");

        if (title == null || title.isBlank()) {
            return ToolsResult.fail("缺少 title 参数");
        }

        String commentary = mlClient.commentary(title + "\n" + content);
        if (commentary == null || commentary.isBlank() || commentary.startsWith("（")) {
            return ToolsResult.ok("无法为《" + title + "》生成 AI 分析（AI 服务不可用或返回空）。");
        }

        return ToolsResult.ok("《" + title + "》AI 分析:\n\n" + commentary);
    }

    private static String stringArg(Map<String, Object> args, String key, String fallback) {
        Object value = args.get(key);
        if (value instanceof String s && !s.isBlank()) {
            return s.trim();
        }
        return fallback;
    }
}
