package com.anthropic.claudecode.eclipse.tools;

import org.eclipse.ui.IEditorReference;
import org.eclipse.ui.IWorkbenchPage;

import com.anthropic.claudecode.eclipse.editor.UiHelper;
import com.anthropic.claudecode.eclipse.mcp.McpTool;
import com.anthropic.claudecode.eclipse.mcp.McpToolResult;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

public class GetOpenEditorsTool implements McpTool {

    @Override
    public String toolName() {
        return "getOpenEditors";
    }

    @Override
    public String description() {
        return "List all currently open editor tabs in Eclipse with their file paths and dirty (unsaved) status.";
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
        return UiHelper.syncCall(() -> {
            try {
                IWorkbenchPage page = UiHelper.getActivePage();
                if (page == null) {
                    JsonObject result = new JsonObject();
                    result.add("editors", new JsonArray());
                    return McpToolResult.success(result);
                }

                JsonArray editors = new JsonArray();
                for (IEditorReference ref : page.getEditorReferences()) {
                    try {
                        var input = ref.getEditorInput();
                        if (input != null) {
                            String filePath = EditorUtils.getFilePath(input);
                            if (filePath != null) {
                                JsonObject editorInfo = new JsonObject();
                                editorInfo.addProperty("filePath", filePath);
                                editorInfo.addProperty("isActive",
                                        ref.getEditor(false) == page.getActiveEditor());
                                editorInfo.addProperty("isDirty", ref.isDirty());
                                editorInfo.addProperty("label", ref.getTitle());
                                editors.add(editorInfo);
                            }
                        }
                    } catch (Exception ignored) {
                        // Skip editors that can't be queried
                    }
                }

                JsonObject result = new JsonObject();
                result.add("editors", editors);
                return McpToolResult.success(result);
            } catch (Exception e) {
                return McpToolResult.error("Failed to get open editors: " + e.getMessage());
            }
        });
    }
}
