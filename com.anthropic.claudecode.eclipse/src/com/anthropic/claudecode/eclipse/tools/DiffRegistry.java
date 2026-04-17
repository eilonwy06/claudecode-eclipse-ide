package com.anthropic.claudecode.eclipse.tools;

import java.util.Collections;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

import org.eclipse.compare.CompareEditorInput;

import com.anthropic.claudecode.eclipse.mcp.McpToolResult;

/**
 * Singleton registry tracking open diff editors.
 * Keyed by absolute file path so accept/reject tools can find the right diff.
 */
public final class DiffRegistry {

    private static final DiffRegistry INSTANCE = new DiffRegistry();

    private final Map<String, DiffEntry> openDiffs = new ConcurrentHashMap<>();
    private volatile Runnable changeListener;

    private DiffRegistry() {}

    public static DiffRegistry getInstance() {
        return INSTANCE;
    }

    public void setChangeListener(Runnable listener) {
        this.changeListener = listener;
    }

    private void fireChange() {
        Runnable l = changeListener;
        if (l != null) l.run();
    }

    public void register(String filePath, DiffEntry entry) {
        openDiffs.put(normalizePath(filePath), entry);
        fireChange();
    }

    public void unregister(String filePath) {
        openDiffs.remove(normalizePath(filePath));
        fireChange();
    }

    public DiffEntry get(String filePath) {
        return openDiffs.get(normalizePath(filePath));
    }

    public Map<String, DiffEntry> getAll() {
        return Collections.unmodifiableMap(openDiffs);
    }

    private static String normalizePath(String path) {
        return path.replace('\\', '/');
    }

    /**
     * An open diff entry.  The {@code decisionFuture} is completed when the user
     * resolves the diff — either by saving in the UI, closing the editor, or via
     * the CLI accept/reject tools.  {@code OpenDiffTool.execute()} blocks on it so
     * Claude waits for the decision rather than racing ahead with its own file write.
     */
    public static final class DiffEntry {
        private final String filePath;
        private final CompareItem leftItem;
        private final CompareItem rightItem;
        private final CompareEditorInput editorInput;
        private final String originalContent;
        private final String proposedContent;
        private final CompletableFuture<McpToolResult> decisionFuture;

        public DiffEntry(String filePath, CompareItem leftItem, CompareItem rightItem,
                         CompareEditorInput editorInput, String originalContent,
                         String proposedContent,
                         CompletableFuture<McpToolResult> decisionFuture) {
            this.filePath = filePath;
            this.leftItem = leftItem;
            this.rightItem = rightItem;
            this.editorInput = editorInput;
            this.originalContent = originalContent;
            this.proposedContent = proposedContent;
            this.decisionFuture = decisionFuture;
        }

        public String getFilePath()                                  { return filePath; }
        public CompareItem getLeftItem()                             { return leftItem; }
        public CompareItem getRightItem()                            { return rightItem; }
        public CompareEditorInput getEditorInput()                   { return editorInput; }
        public String getOriginalContent()                           { return originalContent; }
        public String getProposedContent()                           { return proposedContent; }
        public CompletableFuture<McpToolResult> getDecisionFuture()  { return decisionFuture; }
    }
}
