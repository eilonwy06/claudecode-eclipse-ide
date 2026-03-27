package com.anthropic.claudecode.eclipse.tools;

import org.eclipse.compare.internal.CompareEditor;
import org.eclipse.ui.IEditorReference;
import org.eclipse.ui.IWorkbenchPage;

import com.anthropic.claudecode.eclipse.editor.UiHelper;
import com.anthropic.claudecode.eclipse.mcp.McpTool;
import com.anthropic.claudecode.eclipse.mcp.McpToolResult;
import com.google.gson.JsonObject;

@SuppressWarnings("restriction")
public class CloseAllDiffTabsTool implements McpTool {

    @Override
    public String toolName() {
        return "closeAllDiffTabs";
    }

    @Override
    public String description() {
        return "Close all open diff/compare editor tabs in Eclipse.";
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
                    result.addProperty("closed", 0);
                    return McpToolResult.success(result);
                }

                int closed = 0;
                for (IEditorReference ref : page.getEditorReferences()) {
                    try {
                        var editor = ref.getEditor(false);
                        if (editor instanceof CompareEditor) {
                            page.closeEditor(editor, false);
                            closed++;
                        }
                    } catch (Exception ignored) {}
                }

                JsonObject result = new JsonObject();
                result.addProperty("closed", closed);
                result.addProperty("success", true);
                return McpToolResult.success(result);
            } catch (Exception e) {
                return McpToolResult.error("Failed to close diff tabs: " + e.getMessage());
            }
        });
    }
}
