package com.anthropic.claudecode.eclipse.tools;

import java.nio.file.Path;

import org.eclipse.ui.IEditorReference;
import org.eclipse.ui.IWorkbenchPage;

import com.anthropic.claudecode.eclipse.editor.UiHelper;
import com.anthropic.claudecode.eclipse.mcp.McpTool;
import com.anthropic.claudecode.eclipse.mcp.McpToolResult;
import com.google.gson.JsonObject;

public class CheckDocumentDirtyTool implements McpTool {

    @Override
    public String toolName() {
        return "checkDocumentDirty";
    }

    @Override
    public String description() {
        return "Check whether a file has unsaved changes in the Eclipse editor.";
    }

    @Override
    public JsonObject inputSchema() {
        JsonObject schema = new JsonObject();
        schema.addProperty("type", "object");

        JsonObject props = new JsonObject();
        JsonObject filePath = new JsonObject();
        filePath.addProperty("type", "string");
        filePath.addProperty("description", "Absolute path to check");
        props.add("file_path", filePath);
        schema.add("properties", props);

        var required = new com.google.gson.JsonArray();
        required.add("file_path");
        schema.add("required", required);

        return schema;
    }

    @Override
    public McpToolResult execute(JsonObject params) {
        String targetPath = EditorUtils.requireString(params, "file_path", "filePath");
        String normalizedTarget = Path.of(targetPath).toAbsolutePath().normalize().toString();

        return UiHelper.syncCall(() -> {
            try {
                IWorkbenchPage page = UiHelper.getActivePage();
                if (page == null) {
                    JsonObject result = new JsonObject();
                    result.addProperty("isDirty", false);
                    return McpToolResult.success(result);
                }

                for (IEditorReference ref : page.getEditorReferences()) {
                    try {
                        var input = ref.getEditorInput();
                        String editorPath = EditorUtils.getFilePath(input);
                        if (editorPath != null) {
                            String normalizedEditor = Path.of(editorPath).toAbsolutePath().normalize().toString();
                            if (normalizedEditor.equals(normalizedTarget)) {
                                JsonObject result = new JsonObject();
                                result.addProperty("isDirty", ref.isDirty());
                                result.addProperty("filePath", targetPath);
                                return McpToolResult.success(result);
                            }
                        }
                    } catch (Exception ignored) {}
                }

                // File not open in any editor
                JsonObject result = new JsonObject();
                result.addProperty("isDirty", false);
                result.addProperty("filePath", targetPath);
                result.addProperty("isOpen", false);
                return McpToolResult.success(result);
            } catch (Exception e) {
                return McpToolResult.error("Failed to check dirty state: " + e.getMessage());
            }
        });
    }
}
