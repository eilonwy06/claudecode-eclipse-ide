package com.anthropic.claudecode.eclipse.ui.handlers;

import org.eclipse.core.commands.AbstractHandler;
import org.eclipse.core.commands.ExecutionEvent;
import org.eclipse.core.commands.ExecutionException;
import org.eclipse.ui.IViewPart;
import org.eclipse.ui.IWorkbenchPage;
import org.eclipse.ui.handlers.HandlerUtil;

import com.anthropic.claudecode.eclipse.Activator;
import com.anthropic.claudecode.eclipse.ui.ClaudeCodeView;

public class RestartServerHandler extends AbstractHandler {

    @Override
    public Object execute(ExecutionEvent event) throws ExecutionException {
        try {
            Activator.getDefault().restart();

            IWorkbenchPage page = HandlerUtil.getActiveWorkbenchWindow(event).getActivePage();
            if (page != null) {
                IViewPart viewPart = page.findView(ClaudeCodeView.VIEW_ID);
                if (viewPart instanceof ClaudeCodeView view) {
                    view.restartClaude();
                }
            }
        } catch (Exception e) {
            Activator.logError("Failed to restart server", e);
        }
        return null;
    }
}
