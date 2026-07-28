package com.anthropic.claudecode.eclipse.ui.handlers;

import java.nio.file.Path;
import java.nio.file.Paths;

import org.eclipse.core.commands.AbstractHandler;
import org.eclipse.core.commands.ExecutionEvent;
import org.eclipse.core.commands.ExecutionException;
import org.eclipse.core.resources.IFile;
import org.eclipse.ui.IEditorInput;
import org.eclipse.ui.IEditorPart;
import org.eclipse.ui.IFileEditorInput;
import org.eclipse.ui.IWorkbenchPage;
import org.eclipse.ui.IWorkbenchWindow;
import org.eclipse.ui.handlers.HandlerUtil;

import com.anthropic.claudecode.eclipse.Activator;
import com.anthropic.claudecode.eclipse.ui.ClaudeCliView;

/**
 * Adds the active editor's file to the Claude Terminal's context by typing an
 * {@code @<path>} mention into the embedded CLI terminal — the mechanism the CLI
 * parses for file references ({@code @<path> }, resolved against its working
 * directory). Requires an OPEN terminal: the command never opens one itself
 * (that would silently spawn a session), it tells the user instead.
 */
public class AddFileHandler extends AbstractHandler {

    @Override
    public Object execute(ExecutionEvent event) throws ExecutionException {
        try {
            IEditorPart editor = HandlerUtil.getActiveEditor(event);
            if (editor == null) return null;

            String filePath = filePathOf(editor.getEditorInput());
            if (filePath == null) return null;

            ClaudeCliView view = findOpenTerminal(event);
            if (view == null) return null;

            String mention = "@" + mentionPath(filePath, view) + " ";
            if (view.sendTextToActiveSession(mention)) {
                Activator.log("File added to Claude context: " + mention.trim());
            } else {
                showNoTerminalDialog(event);   // view open but no live session/tab
            }
        } catch (Exception e) {
            Activator.logError("Failed to add file to context", e);
        }
        return null;
    }

    static String filePathOf(IEditorInput input) {
        if (input instanceof IFileEditorInput fileInput) {
            IFile file = fileInput.getFile();
            return file.getLocation() != null ? file.getLocation().toOSString() : null;
        } else if (input instanceof org.eclipse.ui.IURIEditorInput uriInput) {
            return uriInput.getURI().getPath();
        }
        return null;
    }

    /**
     * The already-open Claude Terminal view, or {@code null} after informing the
     * user. Deliberately {@code findView}, not {@code showView}: these commands
     * target a terminal the user has open — they must not open the view and spawn
     * a fresh session as a side effect.
     */
    static ClaudeCliView findOpenTerminal(ExecutionEvent event) {
        IWorkbenchWindow window = HandlerUtil.getActiveWorkbenchWindow(event);
        if (window == null) return null;
        IWorkbenchPage page = window.getActivePage();
        if (page == null) return null;
        if (page.findView(ClaudeCliView.VIEW_ID) instanceof ClaudeCliView view) return view;
        showNoTerminalDialog(event);
        return null;
    }

    /** "No Claude Terminal open" info dialog (view closed, or open without a session). */
    static void showNoTerminalDialog(ExecutionEvent event) {
        IWorkbenchWindow window = HandlerUtil.getActiveWorkbenchWindow(event);
        org.eclipse.jface.dialogs.MessageDialog.openInformation(
                window != null ? window.getShell() : null,
                "Claude Code", "No Claude Terminal open.");
    }

    /**
     * Renders {@code absPath} for an {@code @}-mention: relative to the active
     * session's working directory when the file lives under it, otherwise the
     * absolute path. Backslashes are normalised to forward slashes.
     */
    static String mentionPath(String absPath, ClaudeCliView view) {
        String result = absPath;
        String cwd = view != null ? view.getActiveSessionCwd() : null;
        if (cwd != null && !cwd.isEmpty()) {
            try {
                Path rel = Paths.get(cwd).relativize(Paths.get(absPath));
                // Prefer the relative form only when it stays within the cwd; an
                // absolute path is clearer than a long "../../.." chain.
                if (!rel.toString().isEmpty() && !rel.startsWith("..")) {
                    result = rel.toString();
                }
            } catch (IllegalArgumentException ignore) {
                // Different roots (e.g. another Windows drive) — keep absolute.
            }
        }
        return result.replace('\\', '/');
    }
}
