package com.anthropic.claudecode.eclipse.ui;

import org.eclipse.ui.IViewReference;
import org.eclipse.ui.IWorkbench;
import org.eclipse.ui.IWorkbenchPage;
import org.eclipse.ui.IWorkbenchWindow;
import org.eclipse.ui.PlatformUI;

import com.anthropic.claudecode.eclipse.Activator;
import com.anthropic.claudecode.eclipse.Constants;

/**
 * Helpers for the debug-only UI (the "Claude IDE Server" view).
 *
 * <p>Visibility is driven declaratively: the debug activity in plugin.xml has an
 * {@code enabledWhen} expression bound to the {@code debugMode} source variable
 * ({@link DebugModeSourceProvider}), and the "Activate Claude IDE Server" menu
 * item uses a {@code visibleWhen} on the same variable. That covers Show View,
 * key bindings and the menu. The only thing left to do imperatively is close an
 * already-open instance of the view when debug mode is switched off.
 */
public final class DebugModeUi {

    private DebugModeUi() {
    }

    public static boolean isDebugEnabled() {
        Activator activator = Activator.getDefault();
        if (activator == null) return false;
        return activator.getPreferenceStore().getBoolean(Constants.PREF_DEBUG_MODE);
    }

    /** Close the Claude IDE Server view in every window if it is currently open.
     *  Must be called on the UI thread. */
    public static void closeServerViewIfOpen() {
        if (!PlatformUI.isWorkbenchRunning()) return;
        IWorkbench workbench = PlatformUI.getWorkbench();
        for (IWorkbenchWindow window : workbench.getWorkbenchWindows()) {
            IWorkbenchPage page = window.getActivePage();
            if (page == null) continue;
            IViewReference ref = page.findViewReference(ClaudeCodeView.VIEW_ID);
            if (ref != null) {
                page.hideView(ref);
            }
        }
    }
}
