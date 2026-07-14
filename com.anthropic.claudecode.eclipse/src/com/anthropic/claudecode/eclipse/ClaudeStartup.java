package com.anthropic.claudecode.eclipse;

import org.eclipse.ui.IStartup;
import org.eclipse.ui.IWorkbenchPage;

import com.anthropic.claudecode.eclipse.editor.UiHelper;
import com.anthropic.claudecode.eclipse.ui.ClaudeCliView;

public class ClaudeStartup implements IStartup {

    @Override
    public void earlyStartup() {
        Activator activator = Activator.getDefault();
        if (activator == null) return;

        // The MCP server always starts on launch — the plugin is non-functional without it,
        // so this is unconditional (initialize() is idempotent: a no-op if already running).
        activator.initialize();

        // Optionally open a fresh Claude Terminal tab on launch (PREF_AUTO_START, opt-in).
        if (activator.getPreferenceStore().getBoolean(Constants.PREF_AUTO_START)) {
            UiHelper.asyncExec(this::openClaudeTerminal);
        }
    }

    /** Opens the Claude Terminal view and launches a fresh session (UI thread). */
    private void openClaudeTerminal() {
        try {
            IWorkbenchPage page = UiHelper.getActivePage();
            if (page == null) return;
            ClaudeCliView cliView = (ClaudeCliView) page.showView(ClaudeCliView.VIEW_ID);
            cliView.launchProcess();
        } catch (Exception e) {
            Activator.logError("Failed to auto-open Claude Terminal on launch", e);
        }
    }
}
