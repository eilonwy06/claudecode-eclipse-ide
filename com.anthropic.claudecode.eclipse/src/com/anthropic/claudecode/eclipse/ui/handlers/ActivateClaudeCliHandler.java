package com.anthropic.claudecode.eclipse.ui.handlers;

import org.eclipse.core.commands.AbstractHandler;
import org.eclipse.core.commands.ExecutionEvent;
import org.eclipse.core.commands.ExecutionException;
import org.eclipse.ui.IWorkbenchPage;
import org.eclipse.ui.handlers.HandlerUtil;

import com.anthropic.claudecode.eclipse.Activator;
import com.anthropic.claudecode.eclipse.ui.ClaudeCliView;

public class ActivateClaudeCliHandler extends AbstractHandler {

    @Override
    public Object execute(ExecutionEvent event) throws ExecutionException {
        try {
            IWorkbenchPage page = HandlerUtil.getActiveWorkbenchWindow(event).getActivePage();
            if (page == null) return null;

            if (!Activator.getDefault().isServerRunning()) {
                Activator.getDefault().initialize();
            }
            ClaudeCliView view = (ClaudeCliView) page.showView(ClaudeCliView.VIEW_ID);
            if (view != null) view.ensureAtLeastOneTab();
        } catch (Exception e) {
            Activator.logError("Failed to activate Claude Terminal view", e);
        }
        return null;
    }
}
