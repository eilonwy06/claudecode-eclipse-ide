package com.anthropic.claudecode.eclipse.tools;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.lang.reflect.InvocationTargetException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.eclipse.compare.CompareConfiguration;
import org.eclipse.compare.CompareEditorInput;
import org.eclipse.compare.CompareUI;
import org.eclipse.compare.IEditableContent;
import org.eclipse.compare.IModificationDate;
import org.eclipse.compare.IStreamContentAccessor;
import org.eclipse.compare.ITypedElement;
import org.eclipse.compare.structuremergeviewer.DiffNode;
import org.eclipse.compare.structuremergeviewer.Differencer;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.swt.graphics.Image;

import com.anthropic.claudecode.eclipse.editor.UiHelper;
import com.anthropic.claudecode.eclipse.mcp.McpTool;
import com.anthropic.claudecode.eclipse.mcp.McpToolResult;
import com.google.gson.JsonObject;

public class OpenDiffTool implements McpTool {

    @Override
    public String toolName() {
        return "openDiff";
    }

    @Override
    public String description() {
        return "Open a diff view in Eclipse comparing old and new file content, with an optional tab label.";
    }

    @Override
    public JsonObject inputSchema() {
        JsonObject schema = new JsonObject();
        schema.addProperty("type", "object");

        JsonObject props = new JsonObject();

        JsonObject oldFilePath = new JsonObject();
        oldFilePath.addProperty("type", "string");
        oldFilePath.addProperty("description", "Absolute path to the original file");
        props.add("old_file_path", oldFilePath);

        JsonObject newFilePath = new JsonObject();
        newFilePath.addProperty("type", "string");
        newFilePath.addProperty("description", "Absolute path to the new file (often same as old_file_path)");
        props.add("new_file_path", newFilePath);

        JsonObject newFileContents = new JsonObject();
        newFileContents.addProperty("type", "string");
        newFileContents.addProperty("description", "Proposed new content for the file");
        props.add("new_file_contents", newFileContents);

        JsonObject tabName = new JsonObject();
        tabName.addProperty("type", "string");
        tabName.addProperty("description", "Label for the diff tab");
        props.add("tab_name", tabName);

        schema.add("properties", props);

        var required = new com.google.gson.JsonArray();
        required.add("old_file_path");
        required.add("new_file_contents");
        schema.add("required", required);

        return schema;
    }

    @Override
    public McpToolResult execute(JsonObject params) {
        String filePath = EditorUtils.requireString(params, "old_file_path", "filePath");
        String newContent = EditorUtils.requireString(params, "new_file_contents", "newContent");
        String rawTabLabel = EditorUtils.getString(params, "tab_name", "tabLabel");
        String tabLabel = rawTabLabel != null ? rawTabLabel : Path.of(filePath).getFileName().toString() + " (proposed)";

        return UiHelper.syncCall(() -> {
            try {
                String originalContent;
                Path path = Path.of(filePath);
                boolean isNewFile = !Files.exists(path);

                if (isNewFile) {
                    originalContent = "";
                } else {
                    originalContent = Files.readString(path);
                }

                CompareConfiguration config = new CompareConfiguration();
                config.setLeftEditable(false);
                config.setRightEditable(true);
                config.setLeftLabel("Current: " + path.getFileName());
                config.setRightLabel("Proposed: " + path.getFileName());

                CompareItem left = new CompareItem(path.getFileName().toString(), originalContent, false);
                CompareItem right = new CompareItem(path.getFileName().toString() + " (proposed)", newContent, true);

                CompareEditorInput input = new CompareEditorInput(config) {
                    @Override
                    protected Object prepareInput(IProgressMonitor monitor)
                            throws InvocationTargetException, InterruptedException {
                        return new DiffNode(null, Differencer.CHANGE, null, left, right);
                    }
                };
                input.setTitle(tabLabel);

                CompareUI.openCompareEditor(input);

                JsonObject result = new JsonObject();
                result.addProperty("success", true);
                result.addProperty("filePath", filePath);
                result.addProperty("isNewFile", isNewFile);
                return McpToolResult.success(result);
            } catch (Exception e) {
                return McpToolResult.error("Failed to open diff: " + e.getMessage());
            }
        });
    }

    private static class CompareItem implements ITypedElement, IStreamContentAccessor, IModificationDate, IEditableContent {

        private final String label;
        private String content;
        private final boolean editable;

        CompareItem(String label, String content, boolean editable) {
            this.label = label;
            this.content = content;
            this.editable = editable;
        }

        @Override
        public String getName() { return label; }

        @Override
        public Image getImage() { return null; }

        @Override
        public String getType() { return ITypedElement.TEXT_TYPE; }

        @Override
        public InputStream getContents() throws CoreException {
            return new ByteArrayInputStream(content.getBytes(StandardCharsets.UTF_8));
        }

        @Override
        public long getModificationDate() {
            return System.currentTimeMillis();
        }

        @Override
        public boolean isEditable() { return editable; }

        @Override
        public ITypedElement replace(ITypedElement dest, ITypedElement src) { return dest; }

        @Override
        public void setContent(byte[] newContent) {
            this.content = new String(newContent, StandardCharsets.UTF_8);
        }
    }
}
