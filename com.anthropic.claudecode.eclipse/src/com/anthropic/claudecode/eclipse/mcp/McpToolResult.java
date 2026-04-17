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

    /**
     * Returns the FILE_SAVED signal that Claude Code CLI expects when the user
     * saves in the IDE diff editor.  The CLI reads savedContent as the new file
     * content and applies it without showing a terminal permission prompt.
     * Format: two content items — ["FILE_SAVED", <savedContent>].
     */
    public static McpToolResult fileSaved(String savedContent) {
        JsonObject signal = new JsonObject();
        signal.addProperty("type", "text");
        signal.addProperty("text", "FILE_SAVED");

        JsonObject body = new JsonObject();
        body.addProperty("type", "text");
        body.addProperty("text", savedContent);

        JsonArray content = new JsonArray();
        content.add(signal);
        content.add(body);

        return new McpToolResult(content, false);
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
