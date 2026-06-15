package com.bot.agent;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Registry of all tools available to the LLM.
 * Generates the "可用工具" section of the system prompt.
 */
public class ToolRegistry {

    private final Map<String, Tool> tools = new LinkedHashMap<>();

    public void register(Tool tool) {
        tools.put(tool.name(), tool);
    }

    public Tool get(String name) {
        return tools.get(name);
    }

    public List<Tool> all() {
        return List.copyOf(tools.values());
    }

    /**
     * Generate the tools section for the LLM system prompt.
     */
    public String describeForLLM() {
        if (tools.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        sb.append("""

                可用工具:

                当用户请求需要执行操作时（如获取新闻、分析文章、查后续等），
                你可以调用以下工具。每次调用使用格式:
                [TOOL:工具名] {"参数名":"参数值",...}

                遇到复杂请求时，先在心中列出执行计划，再逐步调用工具。
                每一步完成后检查结果，决定下一步或结束。
                工具调用后会立即返回结果，你可以根据结果决定是否继续调用工具。
                最多连续调用 3 个工具，完成后请直接输出最终回复。

                """);
        for (Tool tool : tools.values()) {
            sb.append("### ").append(tool.name()).append("\n");
            sb.append("说明: ").append(tool.description()).append("\n");
            sb.append("参数: ").append(tool.parameters()).append("\n\n");
        }
        return sb.toString();
    }

    public boolean isEmpty() {
        return tools.isEmpty();
    }
}
