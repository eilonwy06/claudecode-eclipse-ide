package com.anthropic.claudecode.eclipse.tools;

import java.nio.file.Files;
import java.nio.file.Path;

import org.eclipse.compare.CompareConfiguration;
import org.eclipse.compare.CompareEditorInput;
import org.eclipse.compare.CompareUI;
import org.eclipse.compare.structuremergeviewer.DiffNode;
import org.eclipse.compare.structuremergeviewer.Differencer;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.ui.IEditorPart;
import org.eclipse.ui.IEditorReference;
import org.eclipse.ui.IWorkbenchPage;

import com.anthropic.claudecode.eclipse.editor.UiHelper;

/**
 * Opens a read-only Eclipse diff editor showing current-vs-proposed file content
 * while the in-chat permission decision card is up. Unlike {@link OpenDiffTool}
 * this does NOT block or own the accept/reject decision — the card does. The
 * editor is purely a preview and is closed once the user decides.
 */
public final class DiffPreview {

    private DiffPreview() {}

    /** Opens the preview on the UI thread and returns its input (or null on failure). */
    public static CompareEditorInput open(String filePath, String proposedContent, String label) {
        final CompareEditorInput[] ref = { null };
        UiHelper.syncCall(() -> {
            try {
                Path path = Path.of(filePath);
                String original = Files.exists(path) ? Files.readString(path) : "";

                CompareConfiguration cfg = new CompareConfiguration();
                cfg.setLeftEditable(false);
                cfg.setRightEditable(false);
                cfg.setLeftLabel("Current: " + path.getFileName());
                cfg.setRightLabel("Proposed: " + path.getFileName());

                CompareItem left  = new CompareItem(path.getFileName().toString(), original, false);
                CompareItem right = new CompareItem(path.getFileName().toString(), proposedContent, false);

                CompareEditorInput input = new CompareEditorInput(cfg) {
                    @Override
                    protected Object prepareInput(IProgressMonitor monitor) {
                        return new DiffNode(null, Differencer.CHANGE, null, left, right);
                    }
                };
                input.setTitle(label);
                CompareUI.openCompareEditor(input);
                ref[0] = input;
            } catch (Exception ignored) {}
            return null;
        });
        return ref[0];
    }

    /**
     * Opens a read-only Eclipse diff editor comparing two in-memory texts directly, with no
     * backing file on disk. Used for "View full diff" on a chat transcript's Write/Edit/
     * MultiEdit/NotebookEdit card: by the time that's clicked the edit has typically already
     * been applied, so reading the current file (as {@link #open} does for the LIVE pending
     * decision) would just compare the new content against itself. The old/new snippet text
     * already rendered inline is passed straight through instead.
     */
    public static void openText(String oldText, String newText, String title) {
        UiHelper.syncCall(() -> {
            try {
                CompareConfiguration cfg = new CompareConfiguration();
                cfg.setLeftEditable(false);
                cfg.setRightEditable(false);
                cfg.setLeftLabel("Before");
                cfg.setRightLabel("After");

                CompareItem left  = new CompareItem(title, oldText, false);
                CompareItem right = new CompareItem(title, newText, false);

                CompareEditorInput input = new CompareEditorInput(cfg) {
                    @Override
                    protected Object prepareInput(IProgressMonitor monitor) {
                        return new DiffNode(null, Differencer.CHANGE, null, left, right);
                    }
                };
                input.setTitle(title);
                CompareUI.openCompareEditor(input);
            } catch (Exception ignored) {}
            return null;
        });
    }

    /** Closes a preview opened by {@link #open}. Safe to call with null. */
    public static void close(CompareEditorInput input) {
        if (input == null) return;
        UiHelper.syncCall(() -> {
            IWorkbenchPage page = UiHelper.getActivePage();
            if (page != null) {
                for (IEditorReference ref : page.getEditorReferences()) {
                    IEditorPart editor = ref.getEditor(false);
                    if (editor != null && input.equals(editor.getEditorInput())) {
                        page.closeEditor(editor, false);
                        break;
                    }
                }
            }
            return null;
        });
    }
}
