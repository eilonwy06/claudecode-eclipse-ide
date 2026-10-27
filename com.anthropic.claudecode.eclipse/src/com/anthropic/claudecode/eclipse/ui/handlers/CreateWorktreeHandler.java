package com.anthropic.claudecode.eclipse.ui.handlers;

import org.eclipse.core.commands.AbstractHandler;
import org.eclipse.core.commands.ExecutionEvent;

import com.anthropic.claudecode.eclipse.ui.ClaudeGuiView;

/**
 * Opens the Claude Code view's Create Worktree dialog, the same one its command menu opens.
 *
 * <p>No default key; bindable under Preferences &gt; Keys.
 */
public class CreateWorktreeHandler extends AbstractHandler {

    @Override
    public Object execute(ExecutionEvent event) {
        ClaudeGuiView.openCreateWorktree();
        return null;
    }
}
