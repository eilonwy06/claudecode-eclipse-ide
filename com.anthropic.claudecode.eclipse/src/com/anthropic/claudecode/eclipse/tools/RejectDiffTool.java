package com.anthropic.claudecode.eclipse.tools;

import org.eclipse.compare.internal.CompareEditor;
import org.eclipse.ui.IEditorReference;
import org.eclipse.ui.IWorkbenchPage;

import com.anthropic.claudecode.eclipse.editor.UiHelper;
import com.anthropic.claudecode.eclipse.mcp.McpTool;
import com.anthropic.claudecode.eclipse.mcp.McpToolResult;
import com.google.gson.JsonObject;

@SuppressWarnings("restriction")
public class RejectDiffTool implements McpTool {

    @Override
    public String toolName() {
        return "rejectDiff";
    }

    @Override
    public String description() {
        return "Reject the proposed changes from a diff view, closing the diff tab without writing.";
    }

    @Override
    public JsonObject inputSchema() {
        JsonObject schema = new JsonObject();
        schema.addProperty("type", "object");

        JsonObject props = new JsonObject();
        JsonObject filePath = new JsonObject();
        filePath.addProperty("type", "string");
        filePath.addProperty("description", "Absolute path of the file whose diff to reject");
        props.add("file_path", filePath);
        schema.add("properties", props);

        var required = new com.google.gson.JsonArray();
        required.add("file_path");
        schema.add("required", required);

        return schema;
    }

    @Override
    public McpToolResult execute(JsonObject params) {
        String filePath = EditorUtils.requireString(params, "file_path", "filePath");

        DiffRegistry.DiffEntry entry = DiffRegistry.getInstance().get(filePath);
        if (entry == null) {
            return McpToolResult.error("No open diff found for: " + filePath);
        }

        return UiHelper.syncCall(() -> {
            try {
                McpToolResult toolResult = McpToolResult.success("DIFF_REJECTED");

                // Complete the blocking future in openDiff (if still pending),
                // then close the editor and unregister.
                entry.getDecisionFuture().complete(toolResult);

                IWorkbenchPage page = UiHelper.getActivePage();
                if (page != null) {
                    for (IEditorReference ref : page.getEditorReferences()) {
                        var editor = ref.getEditor(false);
                        if (editor instanceof CompareEditor && editor.getEditorInput() == entry.getEditorInput()) {
                            page.closeEditor(editor, false);
                            break;
                        }
                    }
                }

                DiffRegistry.getInstance().unregister(filePath);
                return toolResult;
            } catch (Exception e) {
                return McpToolResult.error("Failed to reject diff: " + e.getMessage());
            }
        });
    }
}
