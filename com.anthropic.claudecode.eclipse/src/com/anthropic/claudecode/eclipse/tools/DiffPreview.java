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
