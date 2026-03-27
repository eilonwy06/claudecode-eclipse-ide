package com.anthropic.claudecode.eclipse.ui.handlers;

import org.eclipse.core.commands.AbstractHandler;
import org.eclipse.core.commands.ExecutionEvent;
import org.eclipse.core.commands.ExecutionException;
import org.eclipse.ui.IViewPart;
import org.eclipse.ui.IWorkbenchPage;
import org.eclipse.ui.handlers.HandlerUtil;

import com.anthropic.claudecode.eclipse.Activator;
import com.anthropic.claudecode.eclipse.ui.ClaudeCodeView;

public class ToggleClaudeHandler extends AbstractHandler {

    @Override
    public Object execute(ExecutionEvent event) throws ExecutionException {
        try {
            IWorkbenchPage page = HandlerUtil.getActiveWorkbenchWindow(event).getActivePage();
            if (page == null) return null;

            IViewPart view = page.findView(ClaudeCodeView.VIEW_ID);
            if (view != null) {
                if (page.isPartVisible(view)) {
                    page.hideView(view);
                } else {
                    page.showView(ClaudeCodeView.VIEW_ID);
                }
            } else {
                // Ensure server is running before showing the view
                if (!Activator.getDefault().isServerRunning()) {
                    Activator.getDefault().initialize();
                }
                page.showView(ClaudeCodeView.VIEW_ID);
            }
        } catch (Exception e) {
            Activator.logError("Failed to toggle Claude view", e);
        }
        return null;
    }
}
