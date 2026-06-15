package com.bot.agent;

import java.util.Map;

/**
 * A tool that the LLM can invoke during a tool-calling conversation.
 * Each tool declares its name, human-readable description, and parameter schema,
 * then executes with the given arguments.
 */
public interface Tool {

    /** Short identifier used in the [TOOL: name] call syntax. */
    String name();

    /** Human-readable description — injected into the LLM system prompt. */
    String description();

    /** JSON Schema describing the expected arguments (for LLM to read). */
    String parameters();

    /** Execute this tool and return the result. */
    ToolsResult execute(Map<String, Object> args);
}
