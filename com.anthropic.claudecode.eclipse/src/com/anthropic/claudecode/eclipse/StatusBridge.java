package com.anthropic.claudecode.eclipse;

import org.eclipse.swt.widgets.Display;
import org.eclipse.ui.IViewReference;
import org.eclipse.ui.IWorkbenchPage;
import org.eclipse.ui.IWorkbenchWindow;
import org.eclipse.ui.PlatformUI;

import com.anthropic.claudecode.eclipse.ui.ClaudeCliView;
import com.anthropic.claudecode.eclipse.ui.ClaudeStatus;

/**
 * Implements {@link NativeCore.StatusCallback} for the dedicated status channel.
 *
 * <p>It routes Claude statusLine updates to the correct terminal tab by the per-tab
 * routing token, NEVER through {@link com.anthropic.claudecode.eclipse.mcp.McpToolRegistry}
 * (status is not a tool). This is a sibling of
 * {@link NativeToolBridge} but for the parallel status subsystem.
 */
public class StatusBridge implements NativeCore.StatusCallback {

    /**
     * Called from a Rust worker/OS thread (NOT the UI thread). Parses the JSON here, off the
     * UI thread, then hops to the display thread for the (UI-only) tab lookup and widget update.
     */
    @Override
    public void onStatusUpdate(String tabToken, String statusJson) {
        if (tabToken == null || tabToken.isEmpty()) return;
        final ClaudeStatus status = ClaudeStatus.parse(statusJson);
        if (status == null) return;

        Display display = Display.getDefault();
        if (display.isDisposed()) return;
        display.asyncExec(() -> deliver(tabToken, status));
    }

    /** UI thread: enumerate live {@link ClaudeCliView}s and push the status to the matching tab. */
    private void deliver(String tabToken, ClaudeStatus status) {
        if (!PlatformUI.isWorkbenchRunning()) return;
        for (IWorkbenchWindow window : PlatformUI.getWorkbench().getWorkbenchWindows()) {
            for (IWorkbenchPage page : window.getPages()) {
                for (IViewReference ref : page.getViewReferences()) {
                    if (!ClaudeCliView.VIEW_ID.equals(ref.getId())) continue;
                    if (ref.getView(false) instanceof ClaudeCliView view
                            && view.deliverStatus(tabToken, status)) {
                        return; // delivered (token is globally unique → at most one match)
                    }
                }
            }
        }
        // No live session matched (tab closed, or a race) — drop silently.
    }
}
