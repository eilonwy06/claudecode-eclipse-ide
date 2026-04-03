package com.anthropic.claudecode.eclipse.editor;

import java.util.concurrent.atomic.AtomicReference;

import org.eclipse.jface.text.ITextSelection;
import org.eclipse.jface.viewers.ISelection;
import org.eclipse.ui.IEditorInput;
import org.eclipse.ui.IEditorPart;
import org.eclipse.ui.IPartListener2;
import org.eclipse.ui.ISelectionListener;
import org.eclipse.ui.IWorkbenchPage;
import org.eclipse.ui.IWorkbenchPart;
import org.eclipse.ui.IWorkbenchPartReference;
import org.eclipse.ui.IWorkbenchWindow;
import org.eclipse.ui.PlatformUI;
import org.eclipse.ui.texteditor.ITextEditor;

import com.anthropic.claudecode.eclipse.Activator;
import com.anthropic.claudecode.eclipse.server.HttpSseServer;
import com.google.gson.JsonObject;

public class SelectionTracker {

    /**
     * Snapshot of the most recent selection — stored so the getLatestSelection
     * MCP tool can retrieve it on demand without needing Gson in the hot path.
     */
    private record SelectionData(String filePath, String text,
                                  int startLine, int endLine, boolean isEmpty) {}

    private final AtomicReference<SelectionData> latestSelection = new AtomicReference<>();

    private HttpSseServer server;
    private ISelectionListener selectionListener;
    private IPartListener2 partListener;
    private volatile boolean active = false;

    public void start(HttpSseServer server) {
        if (active) return;
        this.server = server;
        this.active = true;
        UiHelper.asyncExec(this::registerListeners);
    }

    public void stop() {
        if (!active) return;
        active = false;
        UiHelper.asyncExec(this::unregisterListeners);
    }

    /** Returns the latest selection as a JsonObject for the getLatestSelection MCP tool. */
    public JsonObject getLatestSelection() {
        SelectionData d = latestSelection.get();
        if (d == null) return null;
        JsonObject sel = new JsonObject();
        sel.addProperty("filePath", d.filePath());
        sel.addProperty("text", d.text());
        sel.addProperty("startLine", d.startLine());
        sel.addProperty("endLine", d.endLine());
        sel.addProperty("startColumn", 0);
        sel.addProperty("endColumn", 0);
        sel.addProperty("isEmpty", d.isEmpty());
        return sel;
    }

    private void registerListeners() {
        try {
            IWorkbenchWindow window = PlatformUI.getWorkbench().getActiveWorkbenchWindow();
            if (window == null) return;

            IWorkbenchPage page = window.getActivePage();
            if (page == null) return;

            selectionListener = this::onSelectionChanged;
            page.addSelectionListener(selectionListener);

            partListener = new PartActivationListener();
            page.addPartListener(partListener);

            Activator.log("Selection tracking started");
        } catch (Exception e) {
            Activator.logError("Failed to register selection listeners", e);
        }
    }

    private void unregisterListeners() {
        try {
            IWorkbenchWindow window = PlatformUI.getWorkbench().getActiveWorkbenchWindow();
            if (window == null) return;

            IWorkbenchPage page = window.getActivePage();
            if (page == null) return;

            if (selectionListener != null) {
                page.removeSelectionListener(selectionListener);
                selectionListener = null;
            }
            if (partListener != null) {
                page.removePartListener(partListener);
                partListener = null;
            }
        } catch (Exception e) {
            Activator.logError("Failed to unregister selection listeners", e);
        }
    }

    private void onSelectionChanged(IWorkbenchPart part, ISelection selection) {
        if (!active || !(part instanceof ITextEditor textEditor)) return;
        if (!(selection instanceof ITextSelection textSelection)) return;

        IEditorInput input = textEditor.getEditorInput();
        String filePath = getFilePath(input);
        if (filePath == null) return;

        int startLine = textSelection.getStartLine() + 1;
        int endLine   = textSelection.getEndLine()   + 1;
        boolean empty = textSelection.isEmpty();
        String  text  = textSelection.getText();

        // Store for getLatestSelection() tool queries.
        latestSelection.set(new SelectionData(filePath, text, startLine, endLine, empty));

        // Debounce (50 ms) and broadcast happen entirely in Rust.
        if (server != null) {
            server.notifySelection(filePath, text, startLine, endLine, empty);
        }
    }

    private String getFilePath(IEditorInput input) {
        if (input instanceof org.eclipse.ui.IFileEditorInput fileInput) {
            var file = fileInput.getFile();
            return file.getLocation() != null ? file.getLocation().toOSString() : null;
        }
        if (input instanceof org.eclipse.ui.IURIEditorInput uriInput) {
            return uriInput.getURI().getPath();
        }
        return null;
    }

    private class PartActivationListener implements IPartListener2 {
        @Override
        public void partActivated(IWorkbenchPartReference ref) {
            if (!active) return;
            IWorkbenchPart part = ref.getPart(false);
            if (part instanceof ITextEditor textEditor) {
                ISelection sel = textEditor.getSelectionProvider().getSelection();
                onSelectionChanged(part, sel);
            }
        }

        @Override public void partBroughtToTop(IWorkbenchPartReference ref) {}
        @Override public void partClosed(IWorkbenchPartReference ref) {}
        @Override public void partDeactivated(IWorkbenchPartReference ref) {}
        @Override public void partOpened(IWorkbenchPartReference ref) {}
        @Override public void partHidden(IWorkbenchPartReference ref) {}
        @Override public void partVisible(IWorkbenchPartReference ref) {}
        @Override public void partInputChanged(IWorkbenchPartReference ref) {}
    }
}
