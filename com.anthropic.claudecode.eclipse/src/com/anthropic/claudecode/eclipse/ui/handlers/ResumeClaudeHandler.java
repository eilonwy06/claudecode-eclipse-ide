package com.anthropic.claudecode.eclipse.ui.handlers;

import org.eclipse.core.commands.AbstractHandler;
import org.eclipse.core.commands.ExecutionEvent;
import org.eclipse.core.commands.ExecutionException;
import org.eclipse.ui.IViewPart;
import org.eclipse.ui.IWorkbenchPage;
import org.eclipse.ui.handlers.HandlerUtil;

import com.anthropic.claudecode.eclipse.Activator;
import com.anthropic.claudecode.eclipse.ui.ClaudeCodeView;

public class ResumeClaudeHandler extends AbstractHandler {

    @Override
    public Object execute(ExecutionEvent event) throws ExecutionException {
        try {
            IWorkbenchPage page = HandlerUtil.getActiveWorkbenchWindow(event).getActivePage();
            if (page == null) return null;

            IViewPart viewPart = page.showView(ClaudeCodeView.VIEW_ID);
            if (viewPart instanceof ClaudeCodeView view) {
                view.restartClaude("--resume");
            }
        } catch (Exception e) {
            Activator.logError("Failed to resume Claude", e);
        }
        return null;
    }
}
