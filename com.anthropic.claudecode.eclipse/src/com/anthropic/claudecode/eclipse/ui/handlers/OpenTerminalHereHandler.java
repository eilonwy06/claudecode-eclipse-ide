package com.anthropic.claudecode.eclipse.ui.handlers;

import org.eclipse.core.resources.IResource;
import org.eclipse.ui.IWorkbenchPage;

import com.anthropic.claudecode.eclipse.ui.ClaudeCliView;

/**
 * "Open Claude Here > Claude Terminal" - starts a terminal session in the selected
 * folder. See {@link OpenClaudeHereHandler} for why both entries are commands rather
 * than the objectContribution actions they replaced.
 */
public class OpenTerminalHereHandler extends OpenClaudeHereHandler {

    @Override
    protected void open(IWorkbenchPage page, IResource folder) throws Exception {
        ClaudeCliView view = (ClaudeCliView) page.showView(ClaudeCliView.VIEW_ID);
        view.launchProcessInDirectory(folder.getLocation().toOSString(), labelFor(folder));
    }

    @Override
    protected String failureMessage() { return "Failed to open Claude Terminal"; }
}
