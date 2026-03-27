package com.anthropic.claudecode.eclipse.tools;

import java.nio.file.Path;

import org.eclipse.core.filesystem.EFS;
import org.eclipse.core.filesystem.IFileStore;
import org.eclipse.jface.text.IDocument;
import org.eclipse.ui.IEditorPart;
import org.eclipse.ui.IWorkbenchPage;
import org.eclipse.ui.ide.IDE;
import org.eclipse.ui.texteditor.ITextEditor;

import com.anthropic.claudecode.eclipse.editor.UiHelper;
import com.anthropic.claudecode.eclipse.mcp.McpTool;
import com.anthropic.claudecode.eclipse.mcp.McpToolResult;
import com.google.gson.JsonObject;

public class OpenFileTool implements McpTool {

    @Override
    public String toolName() {
        return "openFile";
    }

    @Override
    public String description() {
        return "Open a file in the Eclipse editor at an optional line and column, with optional text selection.";
    }

    @Override
    public JsonObject inputSchema() {
        JsonObject schema = new JsonObject();
        schema.addProperty("type", "object");

        JsonObject props = new JsonObject();

        JsonObject filePath = new JsonObject();
        filePath.addProperty("type", "string");
        filePath.addProperty("description", "Absolute path to the file to open");
        props.add("file_path", filePath);

        JsonObject line = new JsonObject();
        line.addProperty("type", "integer");
        line.addProperty("description", "Line number to navigate to (1-based)");
        props.add("line", line);

        JsonObject column = new JsonObject();
        column.addProperty("type", "integer");
        column.addProperty("description", "Column number (1-based)");
        props.add("column", column);

        JsonObject selectText = new JsonObject();
        selectText.addProperty("type", "string");
        selectText.addProperty("description", "Text to select after opening");
        props.add("select_text", selectText);

        schema.add("properties", props);

        var required = new com.google.gson.JsonArray();
        required.add("file_path");
        schema.add("required", required);

        return schema;
    }

    @Override
    public McpToolResult execute(JsonObject params) {
        String filePath = EditorUtils.requireString(params, "file_path", "filePath");
        int line = EditorUtils.getInt(params, "line", "line", 0);
        int column = EditorUtils.getInt(params, "column", "column", 0);
        String selectText = EditorUtils.getString(params, "select_text", "selectText");

        return UiHelper.syncCall(() -> {
            try {
                IWorkbenchPage page = UiHelper.getActivePage();
                if (page == null) {
                    return McpToolResult.error("No active workbench page");
                }

                IFileStore fileStore = EFS.getLocalFileSystem().getStore(Path.of(filePath).toUri());
                IEditorPart editor = IDE.openEditorOnFileStore(page, fileStore);

                if (editor instanceof ITextEditor textEditor && line > 0) {
                    IDocument doc = textEditor.getDocumentProvider().getDocument(textEditor.getEditorInput());
                    if (doc != null) {
                        int lineOffset = doc.getLineOffset(line - 1);
                        int offset = lineOffset + Math.max(0, column - 1);

                        if (selectText != null && !selectText.isEmpty()) {
                            String content = doc.get();
                            int selectStart = content.indexOf(selectText, lineOffset);
                            if (selectStart >= 0) {
                                textEditor.selectAndReveal(selectStart, selectText.length());
                            } else {
                                textEditor.selectAndReveal(offset, 0);
                            }
                        } else {
                            textEditor.selectAndReveal(offset, 0);
                        }
                    }
                }

                JsonObject result = new JsonObject();
                result.addProperty("success", true);
                result.addProperty("filePath", filePath);
                return McpToolResult.success(result);
            } catch (Exception e) {
                return McpToolResult.error("Failed to open file: " + e.getMessage());
            }
        });
    }
}
