package com.anthropic.claudecode.eclipse.tools;

import com.anthropic.claudecode.eclipse.Activator;
import com.anthropic.claudecode.eclipse.editor.SelectionTracker;
import com.anthropic.claudecode.eclipse.mcp.McpTool;
import com.anthropic.claudecode.eclipse.mcp.McpToolResult;
import com.google.gson.JsonObject;

public class GetLatestSelectionTool implements McpTool {

    @Override
    public String toolName() {
        return "getLatestSelection";
    }

    @Override
    public String description() {
        return "Get the most recent text selection tracked by the selection tracker, even if the editor has since lost focus.";
    }

    @Override
    public JsonObject inputSchema() {
        JsonObject schema = new JsonObject();
        schema.addProperty("type", "object");
        schema.add("properties", new JsonObject());
        return schema;
    }

    @Override
    public McpToolResult execute(JsonObject params) {
        SelectionTracker tracker = Activator.getDefault().getSelectionTracker();
        if (tracker == null) {
            return McpToolResult.success(emptySelection());
        }

        JsonObject latest = tracker.getLatestSelection();
        return McpToolResult.success(latest != null ? latest : emptySelection());
    }

    private JsonObject emptySelection() {
        JsonObject result = new JsonObject();
        result.addProperty("text", "");
        result.addProperty("isEmpty", true);
        return result;
    }
}
