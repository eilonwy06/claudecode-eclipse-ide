package com.anthropic.claudecode.eclipse.editor;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import org.eclipse.jface.text.ITextSelection;
import org.eclipse.jface.viewers.ISelection;
import org.eclipse.jface.viewers.ISelectionChangedListener;
import org.eclipse.jface.viewers.SelectionChangedEvent;
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

    private static final long DEBOUNCE_MS = 50;

    private final AtomicReference<JsonObject> latestSelection = new AtomicReference<>();

    private HttpSseServer server;
    private ScheduledExecutorService scheduler;
    private ScheduledFuture<?> pendingBroadcast;
    private ISelectionListener selectionListener;
    private IPartListener2 partListener;
    private volatile boolean active = false;

    public void start(HttpSseServer server) {
        if (active) return;
        this.server = server;
        this.active = true;

        scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "claude-selection-tracker");
            t.setDaemon(true);
            return t;
        });

        UiHelper.asyncExec(this::registerListeners);
    }

    public void stop() {
        if (!active) return;
        active = false;

        UiHelper.asyncExec(this::unregisterListeners);

        if (scheduler != null) {
            scheduler.shutdownNow();
            scheduler = null;
        }
    }

    public JsonObject getLatestSelection() {
        return latestSelection.get();
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

        JsonObject selData = new JsonObject();
        selData.addProperty("filePath", filePath);
        selData.addProperty("text", textSelection.getText());
        selData.addProperty("startLine", textSelection.getStartLine() + 1);
        selData.addProperty("endLine", textSelection.getEndLine() + 1);
        selData.addProperty("startColumn", 0);
        selData.addProperty("endColumn", 0);
        selData.addProperty("isEmpty", textSelection.isEmpty());

        latestSelection.set(selData);
        debouncedBroadcast(selData);
    }

    private void debouncedBroadcast(JsonObject selData) {
        if (scheduler == null || scheduler.isShutdown()) return;

        if (pendingBroadcast != null && !pendingBroadcast.isDone()) {
            pendingBroadcast.cancel(false);
        }

        pendingBroadcast = scheduler.schedule(() -> {
            if (active && server != null && server.hasConnectedClients()) {
                JsonObject notification = new JsonObject();
                notification.addProperty("jsonrpc", "2.0");
                notification.addProperty("method", "notifications/selectionChanged");
                JsonObject params = new JsonObject();
                params.add("selection", selData);
                notification.add("params", params);
                server.broadcast(notification.toString());
            }
        }, DEBOUNCE_MS, TimeUnit.MILLISECONDS);
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
