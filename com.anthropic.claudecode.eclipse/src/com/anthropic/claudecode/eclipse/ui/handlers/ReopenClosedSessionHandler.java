package com.anthropic.claudecode.eclipse.ui.handlers;

import org.eclipse.core.commands.AbstractHandler;
import org.eclipse.core.commands.ExecutionEvent;

import com.anthropic.claudecode.eclipse.ui.ClaudeGuiView;

/**
 * Reopens the Claude Code conversation closed most recently in the view.
 *
 * <p>Bound to Ctrl+Alt+Shift+R by default (rebindable under Preferences &gt; Keys), scoped to
 * the {@code contexts.guiViewFocus} context like Find in Conversation, so it is live only
 * while the view has keyboard focus.
 */
public class ReopenClosedSessionHandler extends AbstractHandler {

    @Override
    public Object execute(ExecutionEvent event) {
        ClaudeGuiView.reopenClosedSession(eventTime(event));
        return null;
    }

    /**
     * The OS-level timestamp of the key event that triggered this command, or -1 if
     * unavailable (programmatic execution, no underlying SWT event). One physical press that
     * Eclipse delivers more than once carries the same time, so the view can drop the repeats.
     */
    private static int eventTime(ExecutionEvent event) {
        Object trigger = event.getTrigger();
        if (trigger instanceof org.eclipse.swt.widgets.Event swtEvent) return swtEvent.time;
        return -1;
    }
}
