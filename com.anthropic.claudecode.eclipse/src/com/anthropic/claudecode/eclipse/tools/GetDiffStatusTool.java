package com.anthropic.claudecode.eclipse.tools;

import java.util.Map;

import com.anthropic.claudecode.eclipse.editor.UiHelper;
import com.anthropic.claudecode.eclipse.mcp.McpTool;
import com.anthropic.claudecode.eclipse.mcp.McpToolResult;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

public class GetDiffStatusTool implements McpTool {

    @Override
    public String toolName() {
        return "getDiffStatus";
    }

    @Override
    public String description() {
        return "List currently open diff views and whether the proposed content has been modified by the user.";
    }

    @Override
    public JsonObject inputSchema() {
        JsonObject schema = new JsonObject();
        schema.addProperty("type", "object");

        JsonObject props = new JsonObject();
        JsonObject filePath = new JsonObject();
        filePath.addProperty("type", "string");
        filePath.addProperty("description", "Optional: absolute path to check a specific diff. Omit to list all.");
        props.add("file_path", filePath);
        schema.add("properties", props);

        // No required fields — file_path is optional.
        schema.add("required", new JsonArray());

        return schema;
    }

    @Override
    public McpToolResult execute(JsonObject params) {
        String filePath = EditorUtils.getString(params, "file_path", "filePath");

        return UiHelper.syncCall(() -> {
            try {
                JsonArray diffs = new JsonArray();

                if (filePath != null) {
                    DiffRegistry.DiffEntry entry = DiffRegistry.getInstance().get(filePath);
                    if (entry != null) {
                        diffs.add(buildStatus(entry));
                    }
                } else {
                    for (Map.Entry<String, DiffRegistry.DiffEntry> e : DiffRegistry.getInstance().getAll().entrySet()) {
                        diffs.add(buildStatus(e.getValue()));
                    }
                }

                JsonObject result = new JsonObject();
                result.addProperty("count", diffs.size());
                result.add("diffs", diffs);
                return McpToolResult.success(result);
            } catch (Exception e) {
                return McpToolResult.error("Failed to get diff status: " + e.getMessage());
            }
        });
    }

    private JsonObject buildStatus(DiffRegistry.DiffEntry entry) {
        // Left item holds the current file content (updated when user clicks ← in the diff).
        String currentContent = entry.getLeftItem().getContentString();
        boolean isModified = !currentContent.equals(entry.getOriginalContent());

        JsonObject obj = new JsonObject();
        obj.addProperty("filePath", entry.getFilePath());
        obj.addProperty("isModified", isModified);
        return obj;
    }
}
