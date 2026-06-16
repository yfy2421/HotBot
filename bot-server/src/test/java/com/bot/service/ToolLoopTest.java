package com.bot.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * Verify toolLoop core parsing logic — findMatchingBrace edge cases
 * and tool call format robustness against real LLM output patterns.
 */
class ToolLoopTest {

    // ── findMatchingBrace ──

    @Test
    void normalJsonObject() {
        assertEquals(15, AssistantConversationService.findMatchingBrace(
                "{\"key\": \"value\"}", 0));
    }

    @Test
    void emptyObject() {
        assertEquals(1, AssistantConversationService.findMatchingBrace("{}", 0));
    }

    @Test
    void nestedObject() {
        assertEquals(26, AssistantConversationService.findMatchingBrace(
                "{\"outer\": {\"inner\": \"val\"}}", 0));
    }

    @Test
    void stringContainingBrace() {
        // String value contains a literal { — must NOT be counted as a brace
        assertEquals(24, AssistantConversationService.findMatchingBrace(
                "{\"text\": \"hello {world}\"}", 0));
    }

    @Test
    void escapedQuoteInString() {
        String json = "{\"t\": \"he said \\\"hi\\\"\"}";
        assertEquals(json.length() - 1, AssistantConversationService.findMatchingBrace(json, 0));
    }

    @Test
    void unmatchedBrace() {
        assertEquals(-1, AssistantConversationService.findMatchingBrace("{\"a\": 1", 0));
    }

    @Test
    void startNotAtBrace() {
        // Returns -1: the start position 0 points to 'a', not '{'
        assertEquals(-1, AssistantConversationService.findMatchingBrace("abc {\"x\": 1}", 0));
    }

    @Test
    void startAtCorrectBraceInMidString() {
        String text = "abc {\"x\": 1}";
        int bracePos = text.indexOf('{');
        assertEquals(4, bracePos);
        // Find matching } — it's the last char
        int endPos = AssistantConversationService.findMatchingBrace(text, bracePos);
        assertEquals(text.lastIndexOf('}'), endPos);
    }

    @Test
    void startAtClosingBraceReturnsNegOne() {
        // Starting at '}' returns -1 immediately (not an opening brace)
        assertEquals(-1, AssistantConversationService.findMatchingBrace("{}{}", 1));
    }

    @Test
    void deeplyNested() {
        String json = "{\"a\": {\"b\": {\"c\": {\"d\": \"e\"}}}}";
        assertEquals(json.length() - 1, AssistantConversationService.findMatchingBrace(json, 0));
    }

    @Test
    void realWorldToolCallPattern() {
        String response = "[TOOL:fetch_news] {\"keyword\": \"AI\", \"count\": 3}";
        int toolStart = response.indexOf("[TOOL:");
        int toolEnd = response.indexOf("]", toolStart);
        assertEquals(0, toolStart);

        int argsStart = response.indexOf("{", toolEnd);
        assertTrue(argsStart > toolEnd, "args should start after tool name");
        int argsEnd = AssistantConversationService.findMatchingBrace(response, argsStart);
        assertEquals(response.lastIndexOf('}'), argsEnd);

        String args = response.substring(argsStart, argsEnd + 1);
        assertEquals("{\"keyword\": \"AI\", \"count\": 3}", args);
    }

    @Test
    void toolCallWithTextBeforeAndAfter() {
        String response = "好的，我先拉取新闻。\n[TOOL:fetch_news] {\"keyword\": \"芯片\", \"count\": 2}\n请稍候...";
        int toolStart = response.indexOf("[TOOL:");
        assertTrue(toolStart > 0, "should find tool call after Chinese text");

        int toolEnd = response.indexOf("]", toolStart);
        int argsStart = response.indexOf("{", toolEnd);
        assertTrue(argsStart > toolEnd);

        int argsEnd = AssistantConversationService.findMatchingBrace(response, argsStart);
        assertTrue(argsEnd > argsStart, "should find matching brace");

        String args = response.substring(argsStart, argsEnd + 1);
        assertEquals("{\"keyword\": \"芯片\", \"count\": 2}", args);
    }

    @Test
    void toolCallWithNestedBracesInArgs() {
        // Some LLMs might output nested JSON in tool args
        String response = "[TOOL:analyze_article] {\"title\": \"测试\", \"meta\": {\"source\": \"36氪\"}}";
        int toolStart = response.indexOf("[TOOL:");
        int toolEnd = response.indexOf("]", toolStart);
        int argsStart = response.indexOf("{", toolEnd);
        int argsEnd = AssistantConversationService.findMatchingBrace(response, argsStart);
        assertEquals(response.length() - 1, argsEnd);
    }

    @Test
    void noClosingBrace() {
        String response = "[TOOL:fetch_news] {\"keyword\": \"AI\"";
        int toolStart = response.indexOf("[TOOL:");
        int toolEnd = response.indexOf("]", toolStart);
        int argsStart = response.indexOf("{", toolEnd);
        // No closing brace — should return -1
        assertEquals(-1, AssistantConversationService.findMatchingBrace(response, argsStart));
    }

    // ── Tool name extraction ──

    @Test
    void extractToolNameFromResponse() {
        String response = "[TOOL:fetch_news] {\"keyword\": \"AI\"}";
        int toolStart = response.indexOf("[TOOL:");
        int toolEnd = response.indexOf("]", toolStart);
        String toolName = response.substring(toolStart + 6, toolEnd).trim();
        assertEquals("fetch_news", toolName);
    }

    @Test
    void extractToolNameWithSpaces() {
        String response = "[TOOL: analyze_article ] {\"title\": \"x\"}";
        int toolStart = response.indexOf("[TOOL:");
        int toolEnd = response.indexOf("]", toolStart);
        String toolName = response.substring(toolStart + 6, toolEnd).trim();
        assertEquals("analyze_article", toolName);
    }

    @Test
    void responseWithNoToolCall() {
        String response = "这是一条普通的回复，没有工具调用。";
        int toolStart = response.indexOf("[TOOL:");
        assertTrue(toolStart < 0, "should not find tool call in normal response");
    }

    // ── LLM real-output patterns (simulated from common model behaviors) ──

    @Test
    void chineseTextAfterToolCall() {
        // Common pattern: tool call followed by Chinese explanation
        String response = "[TOOL:fetch_news] {\"keyword\":\"科技\"}现在为您查询最新科技新闻...";
        int toolStart = response.indexOf("[TOOL:");
        int toolEnd = response.indexOf("]", toolStart);
        assertEquals("fetch_news", response.substring(toolStart + 6, toolEnd).trim());

        int argsStart = response.indexOf("{", toolEnd);
        int argsEnd = AssistantConversationService.findMatchingBrace(response, argsStart);
        assertTrue(argsEnd > argsStart, "should find matching brace before Chinese text");
        assertEquals("{\"keyword\":\"科技\"}", response.substring(argsStart, argsEnd + 1));
    }

    @Test
    void multiLineResponse() {
        String response = """
                好的，我先获取新闻。
                [TOOL:fetch_news] {"keyword": "AI", "count": 3}

                正在查询中...""";
        int toolStart = response.indexOf("[TOOL:");
        assertTrue(toolStart > 0);
        int toolEnd = response.indexOf("]", toolStart);
        assertEquals("fetch_news", response.substring(toolStart + 6, toolEnd).trim());
    }
}
