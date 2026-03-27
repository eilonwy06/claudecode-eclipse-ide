package com.anthropic.claudecode.eclipse.mcp;

import com.google.gson.JsonObject;

public interface McpTool {

    String toolName();

    String description();

    JsonObject inputSchema();

    McpToolResult execute(JsonObject params);
}
