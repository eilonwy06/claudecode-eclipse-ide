package com.anthropic.claudecode.eclipse.tools;

import java.nio.file.Files;
import java.nio.file.Path;

import org.eclipse.compare.internal.CompareEditor;
import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.IPath;
import org.eclipse.ui.IEditorReference;
import org.eclipse.ui.IWorkbenchPage;

import com.anthropic.claudecode.eclipse.editor.UiHelper;
import com.anthropic.claudecode.eclipse.mcp.McpTool;
import com.anthropic.claudecode.eclipse.mcp.McpToolResult;
import com.google.gson.JsonObject;

@SuppressWarnings("restriction")
public class AcceptDiffTool implements McpTool {

    @Override
    public String toolName() {
        return "acceptDiff";
    }

    @Override
    public String description() {
        return "Accept the proposed changes from a diff view, writing them to disk and closing the diff tab.";
    }

    @Override
    public JsonObject inputSchema() {
        JsonObject schema = new JsonObject();
        schema.addProperty("type", "object");

        JsonObject props = new JsonObject();
        JsonObject filePath = new JsonObject();
        filePath.addProperty("type", "string");
        filePath.addProperty("description", "Absolute path of the file whose diff to accept");
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
                // Write the proposed content to disk (CLI accept — no UI merge needed).
                String content = entry.getProposedContent();

                // Write to disk.
                Files.writeString(Path.of(filePath), content);

                // Refresh the workspace resource so Eclipse sees the change.
                refreshFile(filePath);

                McpToolResult toolResult = McpToolResult.success("TAB_CLOSED");

                // Complete the blocking future in openDiff (if still pending),
                // then close the editor and unregister.
                entry.getDecisionFuture().complete(toolResult);
                closeCompareEditor(entry);
                DiffRegistry.getInstance().unregister(filePath);

                return toolResult;
            } catch (Exception e) {
                return McpToolResult.error("Failed to accept diff: " + e.getMessage());
            }
        });
    }

    private void closeCompareEditor(DiffRegistry.DiffEntry entry) {
        IWorkbenchPage page = UiHelper.getActivePage();
        if (page == null) return;

        for (IEditorReference ref : page.getEditorReferences()) {
            var editor = ref.getEditor(false);
            if (editor instanceof CompareEditor && editor.getEditorInput() == entry.getEditorInput()) {
                page.closeEditor(editor, false);
                break;
            }
        }
    }

    private void refreshFile(String filePath) {
        try {
            IPath location = org.eclipse.core.runtime.Path.fromOSString(filePath);
            IFile[] files = ResourcesPlugin.getWorkspace().getRoot().findFilesForLocationURI(location.toFile().toURI());
            for (IFile file : files) {
                file.refreshLocal(org.eclipse.core.resources.IResource.DEPTH_ZERO, null);
            }
        } catch (Exception ignored) {
            // Best-effort refresh — file is already written to disk.
        }
    }
}
