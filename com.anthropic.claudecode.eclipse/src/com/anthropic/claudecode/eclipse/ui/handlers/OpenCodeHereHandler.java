package com.anthropic.claudecode.eclipse.ui.handlers;

import org.eclipse.core.resources.IResource;
import org.eclipse.ui.IWorkbenchPage;

import com.anthropic.claudecode.eclipse.ui.ClaudeGuiView;

/**
 * "Open Claude Here > Claude Code" - opens a conversation in the Claude Code (GUI)
 * view rooted at the selected folder, adding a working root ("supertab") for it if
 * there is not one yet. Same outcome as Show In > Claude Code.
 */
public class OpenCodeHereHandler extends OpenClaudeHereHandler {

    @Override
    protected void open(IWorkbenchPage page, IResource folder) throws Exception {
        ClaudeGuiView view = (ClaudeGuiView) page.showView(ClaudeGuiView.VIEW_ID);
        // Queued by the view when the webview is still loading - showView on a closed
        // view returns before its page has finished coming up.
        view.openRootDirectory(folder.getLocation().toOSString());
    }

    @Override
    protected String failureMessage() { return "Failed to open Claude Code"; }
}
