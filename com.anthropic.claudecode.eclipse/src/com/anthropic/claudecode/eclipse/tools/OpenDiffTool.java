package com.anthropic.claudecode.eclipse.tools;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.Timer;
import java.util.TimerTask;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import org.eclipse.compare.CompareConfiguration;
import org.eclipse.compare.CompareEditorInput;
import org.eclipse.compare.CompareUI;
import org.eclipse.compare.structuremergeviewer.DiffNode;
import org.eclipse.compare.structuremergeviewer.Differencer;
import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.resources.IResourceChangeEvent;
import org.eclipse.core.resources.IResourceChangeListener;
import org.eclipse.core.resources.IResourceDelta;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.jface.text.DocumentEvent;
import org.eclipse.jface.text.IDocument;
import org.eclipse.jface.text.IDocumentListener;
import org.eclipse.swt.widgets.Display;
import org.eclipse.ui.IEditorPart;
import org.eclipse.ui.IEditorReference;
import org.eclipse.ui.IPartListener2;
import org.eclipse.ui.IWorkbenchPage;
import org.eclipse.ui.IWorkbenchPart;
import org.eclipse.ui.IWorkbenchPartReference;

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
        return "Open a diff view in Eclipse showing proposed vs current file content. "
             + "This tool BLOCKS until the user accepts or rejects the changes in the diff UI. "
             + "Returns outcome: 'accepted' (proposed changes written to disk), "
             + "'rejected' (file left unchanged), or 'partial' (user applied only some changes). "
             + "Do NOT write the file yourself after calling this tool — it handles the file write.";
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
        String tabLabel = rawTabLabel != null ? rawTabLabel
                : Path.of(filePath).getFileName().toString() + " (proposed)";

        // Completed by whichever path resolves the diff first:
        //   saveChanges() — user saved after merging in the diff UI
        //   partClosed()  — user closed the diff without saving
        //   AcceptDiffTool / RejectDiffTool — programmatic resolve
        // execute() blocks on it so Claude waits for the decision instead of
        // racing ahead with its own "Do you want to overwrite?" prompt.
        CompletableFuture<McpToolResult> decision = new CompletableFuture<>();
        AtomicReference<String> setupError = new AtomicReference<>();

        // Open the diff synchronously on the UI thread (same pattern as before).
        // syncCall returns once the editor is open and all callbacks are wired up.
        // The worker thread then blocks on decision.get() below — outside syncCall,
        // so the UI thread is never held while waiting for user input.
        UiHelper.syncCall(() -> {
            try {
                Path path = Path.of(filePath);
                boolean isNewFile = !Files.exists(path);
                String originalContent = isNewFile ? "" : Files.readString(path);

                CompareConfiguration config = new CompareConfiguration();
                // Left = current file (editable — user saves this side to decide).
                // Right = proposed / Claude's changes (read-only reference).
                // Ctrl+S saves whatever is in the left pane and resolves the diff.
                config.setLeftEditable(true);
                config.setRightEditable(false);
                config.setLeftLabel("Current: " + path.getFileName());
                config.setRightLabel("Proposed: " + path.getFileName());

                CompareItem left  = new CompareItem(path.getFileName().toString(), originalContent, true);
                CompareItem right = new CompareItem(path.getFileName().toString(), newContent, false);

                // Hoisted up here so saveChanges (inside the anonymous CompareEditorInput)
                // can reference them. Timer is filled in below once the class is created.
                final AtomicBoolean closed = new AtomicBoolean(false);
                final Timer[] mtimeTimerRef = { null };

                // Tracks whether the user actually edited anything in the diff.
                // `userEdited` flips true when either viewer's document reports a change
                // AFTER the initial Eclipse-driven setup has settled (see timerExec below).
                // `editedDoc` captures which side was edited, so we can read its final
                // content at save time — this dodges the ambiguity of `fLeft` vs `fRight`
                // reflection, because whichever side the user actually touches is the
                // authoritative "merged" pane.
                final AtomicBoolean userEdited = new AtomicBoolean(false);
                final AtomicReference<IDocument> editedDoc = new AtomicReference<>();

                CompareEditorInput input = new CompareEditorInput(config) {
                    @Override
                    protected Object prepareInput(IProgressMonitor monitor)
                            throws InvocationTargetException, InterruptedException {
                        return new DiffNode(null, Differencer.CHANGE, null, left, right);
                    }

                    // Eclipse resets fDirty to false after async content preparation,
                    // which prevents Ctrl+S from triggering doSave. Override isDirty()
                    // to return true until the user's decision is recorded so Ctrl+S
                    // always reaches saveChanges.
                    @Override
                    public boolean isDirty() {
                        return !decision.isDone();
                    }

                    /**
                     * Triggered by Ctrl+S in the diff editor.
                     *
                     * The `userEdited` flag is the authoritative signal — it's set only
                     * when one of the viewer documents received a real post-setup change
                     * event (i.e. the user actually merged or typed something). If the
                     * flag is false, Ctrl+S means "I looked, I'm done" and we report
                     * DIFF_REJECTED so the CLI leaves the file alone. If the flag is
                     * true, we read the final content from whichever document the user
                     * actually touched — this avoids the old reflection-based "is fLeft
                     * the left pane?" ambiguity that caused Ctrl+S to write proposed
                     * content even when the user did nothing.
                     */
                    @Override
                    public void saveChanges(IProgressMonitor monitor) throws CoreException {
                        // Do NOT write the file here. The CLI writes it after receiving
                        // FILE_SAVED. If we write first, the CLI's post-FILE_SAVED hash
                        // check sees the file already changed and errors.
                        // Do NOT call setDirty(false) here — isDirty() is overridden and
                        // returns !decision.isDone(), so it flips to false automatically
                        // once decision.complete() is called below.
                        if (!closed.compareAndSet(false, true)) return;
                        if (mtimeTimerRef[0] != null) mtimeTimerRef[0].cancel();
                        DiffRegistry.getInstance().unregister(filePath);
                        CompareEditorInput self = this;
                        Display.getDefault().asyncExec(() -> closeEditorByInput(self));

                        if (!userEdited.get()) {
                            // User hit Ctrl+S without touching anything — reject.
                            decision.complete(McpToolResult.success("DIFF_REJECTED"));
                            return;
                        }

                        // User actually edited a pane. Read the final content from the
                        // document they touched.
                        IDocument doc = editedDoc.get();
                        String mergedContent = doc != null ? doc.get() : null;
                        if (mergedContent == null) {
                            // Fallback: try the reflection read, then the flushed item.
                            mergedContent = readLeftViewerDocument(this);
                            if (mergedContent == null) {
                                flushViewers(monitor);
                                mergedContent = left.getContentString();
                            }
                        }

                        // If despite tracking an edit the content still equals what was
                        // on disk (e.g. user merged then reverted), treat as rejection.
                        if (mergedContent == null || mergedContent.equals(originalContent)) {
                            decision.complete(McpToolResult.success("DIFF_REJECTED"));
                        } else {
                            decision.complete(McpToolResult.fileSaved(mergedContent));
                        }
                    }
                };
                input.setTitle(tabLabel);

                // Also re-dirty if user copies a chunk back (e.g. partial merge).
                left.setOnChange(() -> input.setDirty(true));

                DiffRegistry.DiffEntry entry = new DiffRegistry.DiffEntry(
                        filePath, left, right, input, originalContent, newContent, decision);
                DiffRegistry.getInstance().register(filePath, entry);

                CompareUI.openCompareEditor(input);

                // Mark dirty immediately so Ctrl+S is available from the start —
                // the user shouldn't have to click ← before they can save.
                input.setDirty(true);

                // Attach IDocumentListener to BOTH viewer documents so we can detect
                // real user edits. Deferred by ~250ms so Eclipse's own setContent calls
                // during viewer init have already fired — those would otherwise mark
                // `userEdited` true before the user ever touches the editor.
                //
                // We listen to both fLeft and fRight because the reflection-based field
                // lookup in Eclipse's TextMergeViewer can return either side depending on
                // internal layout; by listening to both, whichever document the user
                // actually touches is detected regardless of which field holds it.
                // Right is read-only so should never fire; if it does, the viewer has it
                // swapped and we still capture the correct edited side.
                Display.getDefault().timerExec(250, () -> {
                    if (closed.get()) return;
                    try {
                        IDocument leftDoc  = getViewerDocumentByField(input, "fLeft");
                        IDocument rightDoc = getViewerDocumentByField(input, "fRight");
                        IDocumentListener listener = new IDocumentListener() {
                            @Override public void documentAboutToBeChanged(DocumentEvent e) {}
                            @Override public void documentChanged(DocumentEvent e) {
                                userEdited.set(true);
                                editedDoc.set(e.getDocument());
                            }
                        };
                        if (leftDoc  != null) leftDoc.addDocumentListener(listener);
                        if (rightDoc != null) rightDoc.addDocumentListener(listener);
                    } catch (Exception ignored) {}
                });

                // Auto-close the diff when the CLI writes the file externally (e.g. the
                // user clicked Yes in the terminal prompt).
                //
                // Two watchers cover both cases:
                //   1. Eclipse ResourceChangeListener (POST_CHANGE) — fires when the
                //      file lives inside the workspace AND Eclipse has refreshed it.
                //   2. File mtime poll — catches external writes for files outside
                //      the workspace OR when Eclipse has not yet refreshed. This is
                //      the one that reliably triggers on a CLI-level write.
                final Runnable onExternalWrite = () -> {
                    if (!closed.compareAndSet(false, true)) return;
                    DiffRegistry.getInstance().unregister(filePath);
                    Display.getDefault().asyncExec(() -> closeEditorByInput(input));
                    decision.complete(McpToolResult.success("TAB_CLOSED"));
                };

                final Path pollPath = Path.of(filePath);
                final FileTime[] initialMtime = { null };
                try {
                    if (Files.exists(pollPath)) {
                        initialMtime[0] = Files.getLastModifiedTime(pollPath);
                    }
                } catch (Exception ignored) {}

                final Timer mtimeTimer = new Timer("ClaudeDiffMtime-" + pollPath.getFileName(), true);
                mtimeTimerRef[0] = mtimeTimer;
                mtimeTimer.schedule(new TimerTask() {
                    @Override
                    public void run() {
                        if (decision.isDone() || closed.get()) {
                            cancel();
                            mtimeTimer.cancel();
                            return;
                        }
                        try {
                            boolean exists = Files.exists(pollPath);
                            if (!exists) {
                                if (initialMtime[0] != null) {
                                    // File was deleted — treat as external change.
                                    cancel();
                                    mtimeTimer.cancel();
                                    onExternalWrite.run();
                                }
                                return;
                            }
                            FileTime current = Files.getLastModifiedTime(pollPath);
                            if (initialMtime[0] == null) {
                                // New file that now exists — external write.
                                cancel();
                                mtimeTimer.cancel();
                                onExternalWrite.run();
                                return;
                            }
                            if (!current.equals(initialMtime[0])) {
                                cancel();
                                mtimeTimer.cancel();
                                onExternalWrite.run();
                            }
                        } catch (Exception ignored) {}
                    }
                }, 300, 300);

                final IResourceChangeListener[] resListener = { null };
                resListener[0] = event -> {
                    if (decision.isDone()) {
                        ResourcesPlugin.getWorkspace()
                                .removeResourceChangeListener(resListener[0]);
                        return;
                    }
                    IResourceDelta delta = event.getDelta();
                    if (delta == null) return;
                    try {
                        IFile[] files = ResourcesPlugin.getWorkspace().getRoot()
                                .findFilesForLocationURI(new java.io.File(filePath).toURI());
                        for (IFile f : files) {
                            IResourceDelta fd = delta.findMember(f.getFullPath());
                            if (fd != null && (fd.getFlags() & IResourceDelta.CONTENT) != 0) {
                                ResourcesPlugin.getWorkspace()
                                        .removeResourceChangeListener(resListener[0]);
                                mtimeTimer.cancel();
                                onExternalWrite.run();
                                break;
                            }
                        }
                    } catch (Exception ignored) {}
                };
                ResourcesPlugin.getWorkspace().addResourceChangeListener(
                        resListener[0], IResourceChangeEvent.POST_CHANGE);

                // Complete as "rejected" if the user closes the diff without saving.
                IWorkbenchPage page = UiHelper.getActivePage();
                if (page != null) {
                    page.addPartListener(new IPartListener2() {
                        @Override
                        public void partClosed(IWorkbenchPartReference partRef) {
                            IWorkbenchPart part = partRef.getPart(false);
                            if (part instanceof IEditorPart
                                    && input.equals(((IEditorPart) part).getEditorInput())) {
                                ResourcesPlugin.getWorkspace()
                                        .removeResourceChangeListener(resListener[0]);
                                mtimeTimer.cancel();
                                page.removePartListener(this);
                                if (!closed.compareAndSet(false, true)) return;
                                DiffRegistry.getInstance().unregister(filePath);
                                decision.complete(McpToolResult.success("DIFF_REJECTED"));
                            }
                        }
                        @Override public void partActivated(IWorkbenchPartReference r) {}
                        @Override public void partBroughtToTop(IWorkbenchPartReference r) {}
                        @Override public void partDeactivated(IWorkbenchPartReference r) {}
                        @Override public void partHidden(IWorkbenchPartReference r) {}
                        @Override public void partInputChanged(IWorkbenchPartReference r) {}
                        @Override public void partOpened(IWorkbenchPartReference r) {}
                        @Override public void partVisible(IWorkbenchPartReference r) {}
                    });
                }
            } catch (Exception e) {
                setupError.set(e.getMessage());
            }
            return null;
        });

        if (setupError.get() != null) {
            return McpToolResult.error("Failed to open diff: " + setupError.get());
        }

        // Block the MCP worker thread — Claude waits here until the diff is resolved.
        // The UI thread is free to process events (paint, respond to clicks, etc.).
        try {
            McpToolResult result = decision.get(30, TimeUnit.MINUTES);
            // After FILE_SAVED the CLI writes the file. Schedule a workspace refresh
            // so Eclipse picks up the change once the CLI write lands on disk.
            Display.getDefault().asyncExec(() -> refreshWorkspaceFile(filePath));
            return result;
        } catch (TimeoutException e) {
            UiHelper.syncCall(() -> {
                DiffRegistry.DiffEntry entry = DiffRegistry.getInstance().get(filePath);
                if (entry != null) {
                    closeEditorByInput(entry.getEditorInput());
                    DiffRegistry.getInstance().unregister(filePath);
                }
                return null;
            });
            return McpToolResult.error("Diff timed out after 30 minutes with no decision.");
        } catch (Exception e) {
            return McpToolResult.error("Diff interrupted: " + e.getMessage());
        }
    }

    private void closeEditorByInput(CompareEditorInput input) {
        IWorkbenchPage page = UiHelper.getActivePage();
        if (page == null) return;
        for (IEditorReference ref : page.getEditorReferences()) {
            IEditorPart editor = ref.getEditor(false);
            if (editor != null && input.equals(editor.getEditorInput())) {
                page.closeEditor(editor, false);
                return;
            }
        }
    }

    private void refreshWorkspaceFile(String filePath) {
        try {
            IFile[] files = ResourcesPlugin.getWorkspace().getRoot()
                    .findFilesForLocationURI(new java.io.File(filePath).toURI());
            for (IFile file : files) {
                file.refreshLocal(IResource.DEPTH_ZERO, null);
            }
        } catch (Exception ignored) {}
    }

    /**
     * Resolves the IDocument backing one of the TextMergeViewer's source viewers
     * by reflecting on the named field ("fLeft" or "fRight"). Returns null if any
     * step of the lookup fails — callers should fall back gracefully.
     */
    private static IDocument getViewerDocumentByField(CompareEditorInput input, String fieldName) {
        try {
            Field paneField = findDeclaredField(CompareEditorInput.class, "fContentInputPane");
            if (paneField == null) return null;
            paneField.setAccessible(true);
            Object pane = paneField.get(input);
            if (pane == null) return null;

            Method getViewer = findDeclaredMethod(pane.getClass(), "getViewer");
            if (getViewer == null) return null;
            getViewer.setAccessible(true);
            Object viewer = getViewer.invoke(pane);
            if (viewer == null) return null;

            Field sideField = findDeclaredField(viewer.getClass(), fieldName);
            if (sideField == null) return null;
            sideField.setAccessible(true);
            Object sideViewer = sideField.get(viewer);
            if (sideViewer == null) return null;

            Method getDoc = findDeclaredMethod(sideViewer.getClass(), "getDocument");
            if (getDoc != null) {
                getDoc.setAccessible(true);
                Object doc = getDoc.invoke(sideViewer);
                if (doc instanceof IDocument) return (IDocument) doc;
            }

            Method getSV = findDeclaredMethod(sideViewer.getClass(), "getSourceViewer");
            if (getSV != null) {
                getSV.setAccessible(true);
                Object sv = getSV.invoke(sideViewer);
                if (sv != null) {
                    getDoc = findDeclaredMethod(sv.getClass(), "getDocument");
                    if (getDoc != null) {
                        getDoc.setAccessible(true);
                        Object doc = getDoc.invoke(sv);
                        if (doc instanceof IDocument) return (IDocument) doc;
                    }
                }
            }
        } catch (Exception ignored) {}
        return null;
    }

    /**
     * Reads the live content of the left text viewer's IDocument via reflection.
     *
     * Eclipse's ContentMergeViewer.flush() only copies viewer content to the
     * CompareItem model if its internal fLeftDirty flag is set.  That flag is
     * reset during viewer initialisation, so by the time Ctrl+S fires it is
     * often already false — making flushViewers() a no-op even after the user
     * merged chunks with ←.  This method bypasses that flag entirely by reading
     * the IDocument directly from the viewer.
     *
     * Chain: CompareEditorInput.fContentInputPane (CompareViewerSwitchingPane)
     *        → getViewer() (TextMergeViewer)
     *        → fLeft (MergeSourceViewer)
     *        → getDocument() / getSourceViewer().getDocument()
     *
     * Returns null on any reflection failure so callers can fall back gracefully.
     */
    private static String readLeftViewerDocument(CompareEditorInput input) {
        try {
            Field paneField = findDeclaredField(CompareEditorInput.class, "fContentInputPane");
            if (paneField == null) return null;
            paneField.setAccessible(true);
            Object pane = paneField.get(input);
            if (pane == null) return null;

            Method getViewer = findDeclaredMethod(pane.getClass(), "getViewer");
            if (getViewer == null) return null;
            getViewer.setAccessible(true);
            Object viewer = getViewer.invoke(pane);
            if (viewer == null) return null;

            Field fLeft = findDeclaredField(viewer.getClass(), "fLeft");
            if (fLeft == null) return null;
            fLeft.setAccessible(true);
            Object leftViewer = fLeft.get(viewer);
            if (leftViewer == null) return null;

            // Try getDocument() directly on the MergeSourceViewer.
            Method getDoc = findDeclaredMethod(leftViewer.getClass(), "getDocument");
            if (getDoc != null) {
                getDoc.setAccessible(true);
                Object doc = getDoc.invoke(leftViewer);
                if (doc instanceof IDocument) return ((IDocument) doc).get();
            }

            // Fall back: MergeSourceViewer.getSourceViewer().getDocument()
            Method getSV = findDeclaredMethod(leftViewer.getClass(), "getSourceViewer");
            if (getSV != null) {
                getSV.setAccessible(true);
                Object sv = getSV.invoke(leftViewer);
                if (sv != null) {
                    getDoc = findDeclaredMethod(sv.getClass(), "getDocument");
                    if (getDoc != null) {
                        getDoc.setAccessible(true);
                        Object doc = getDoc.invoke(sv);
                        if (doc instanceof IDocument) return ((IDocument) doc).get();
                    }
                }
            }
        } catch (Exception ignored) {}
        return null;
    }

    /** Searches {@code cls} and all its superclasses for a declared field. */
    private static Field findDeclaredField(Class<?> cls, String name) {
        for (Class<?> c = cls; c != null; c = c.getSuperclass()) {
            try { return c.getDeclaredField(name); }
            catch (NoSuchFieldException ignored) {}
        }
        return null;
    }

    /** Searches {@code cls} and all its superclasses for a no-arg declared method. */
    private static Method findDeclaredMethod(Class<?> cls, String name) {
        for (Class<?> c = cls; c != null; c = c.getSuperclass()) {
            for (Method m : c.getDeclaredMethods()) {
                if (m.getName().equals(name) && m.getParameterCount() == 0) return m;
            }
        }
        return null;
    }
}
