package com.anthropic.claudecode.eclipse.mcp;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

public record McpToolResult(JsonArray content, boolean isError) {

    public static McpToolResult success(String text) {
        return fromText(text, false);
    }

    public static McpToolResult success(JsonObject data) {
        return fromText(data.toString(), false);
    }

    public static McpToolResult error(String message) {
        return fromText(message, true);
    }

    private static McpToolResult fromText(String text, boolean isError) {
        JsonObject textBlock = new JsonObject();
        textBlock.addProperty("type", "text");
        textBlock.addProperty("text", text);

        JsonArray content = new JsonArray();
        content.add(textBlock);

        return new McpToolResult(content, isError);
    }

    public JsonObject toJson() {
        JsonObject result = new JsonObject();
        result.add("content", content);
        result.addProperty("isError", isError);
        return result;
    }
}
