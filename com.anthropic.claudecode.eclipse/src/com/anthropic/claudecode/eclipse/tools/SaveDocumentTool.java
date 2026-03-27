package com.anthropic.claudecode.eclipse.tools;

import java.nio.file.Path;

import org.eclipse.core.runtime.NullProgressMonitor;
import org.eclipse.ui.IEditorPart;
import org.eclipse.ui.IEditorReference;
import org.eclipse.ui.IWorkbenchPage;

import com.anthropic.claudecode.eclipse.editor.UiHelper;
import com.anthropic.claudecode.eclipse.mcp.McpTool;
import com.anthropic.claudecode.eclipse.mcp.McpToolResult;
import com.google.gson.JsonObject;

public class SaveDocumentTool implements McpTool {

    @Override
    public String toolName() {
        return "saveDocument";
    }

    @Override
    public String description() {
        return "Save a file that is currently open in the Eclipse editor.";
    }

    @Override
    public JsonObject inputSchema() {
        JsonObject schema = new JsonObject();
        schema.addProperty("type", "object");

        JsonObject props = new JsonObject();
        JsonObject filePath = new JsonObject();
        filePath.addProperty("type", "string");
        filePath.addProperty("description", "Absolute path of the file to save");
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
                    return McpToolResult.error("No active workbench page");
                }

                for (IEditorReference ref : page.getEditorReferences()) {
                    try {
                        var input = ref.getEditorInput();
                        String editorPath = EditorUtils.getFilePath(input);
                        if (editorPath != null) {
                            String normalizedEditor = Path.of(editorPath).toAbsolutePath().normalize().toString();
                            if (normalizedEditor.equals(normalizedTarget)) {
                                IEditorPart editor = ref.getEditor(true);
                                if (editor != null && editor.isDirty()) {
                                    editor.doSave(new NullProgressMonitor());
                                }
                                JsonObject result = new JsonObject();
                                result.addProperty("success", true);
                                result.addProperty("filePath", targetPath);
                                return McpToolResult.success(result);
                            }
                        }
                    } catch (Exception ignored) {}
                }

                return McpToolResult.error("File not open in editor: " + targetPath);
            } catch (Exception e) {
                return McpToolResult.error("Failed to save document: " + e.getMessage());
            }
        });
    }
}
