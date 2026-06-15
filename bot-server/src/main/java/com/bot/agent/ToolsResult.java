package com.bot.agent;

/**
 * Result of a tool execution.
 *
 * @param content The text result to feed back into the LLM conversation.
 * @param success Whether the tool completed successfully.
 */
public record ToolsResult(String content, boolean success) {

    public static ToolsResult ok(String content) {
        return new ToolsResult(content, true);
    }

    public static ToolsResult fail(String reason) {
        return new ToolsResult("工具调用失败: " + reason, false);
    }
}
