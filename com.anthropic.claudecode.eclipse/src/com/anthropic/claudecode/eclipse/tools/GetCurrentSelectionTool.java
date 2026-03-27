package com.anthropic.claudecode.eclipse.tools;

import org.eclipse.jface.text.ITextSelection;
import org.eclipse.jface.viewers.ISelection;
import org.eclipse.ui.IEditorInput;
import org.eclipse.ui.IEditorPart;
import org.eclipse.ui.IWorkbenchPage;
import org.eclipse.ui.texteditor.ITextEditor;

import com.anthropic.claudecode.eclipse.editor.UiHelper;
import com.anthropic.claudecode.eclipse.mcp.McpTool;
import com.anthropic.claudecode.eclipse.mcp.McpToolResult;
import com.google.gson.JsonObject;

public class GetCurrentSelectionTool implements McpTool {

    @Override
    public String toolName() {
        return "getCurrentSelection";
    }

    @Override
    public String description() {
        return "Get the current text selection in the active Eclipse editor, including file path, line range, and selected text.";
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
                    return McpToolResult.success(emptySelection());
                }

                IEditorPart editor = page.getActiveEditor();
                if (!(editor instanceof ITextEditor textEditor)) {
                    return McpToolResult.success(emptySelection());
                }

                ISelection selection = textEditor.getSelectionProvider().getSelection();
                if (!(selection instanceof ITextSelection textSelection) || textSelection.isEmpty()) {
                    return McpToolResult.success(emptySelection());
                }

                IEditorInput input = textEditor.getEditorInput();
                String filePath = EditorUtils.getFilePath(input);

                JsonObject result = new JsonObject();
                result.addProperty("filePath", filePath != null ? filePath : "");
                result.addProperty("text", textSelection.getText());
                result.addProperty("startLine", textSelection.getStartLine() + 1);
                result.addProperty("endLine", textSelection.getEndLine() + 1);
                result.addProperty("startColumn", 0);
                result.addProperty("endColumn", 0);
                result.addProperty("isEmpty", false);

                return McpToolResult.success(result);
            } catch (Exception e) {
                return McpToolResult.error("Failed to get selection: " + e.getMessage());
            }
        });
    }

    private JsonObject emptySelection() {
        JsonObject result = new JsonObject();
        result.addProperty("text", "");
        result.addProperty("isEmpty", true);
        return result;
    }
}
