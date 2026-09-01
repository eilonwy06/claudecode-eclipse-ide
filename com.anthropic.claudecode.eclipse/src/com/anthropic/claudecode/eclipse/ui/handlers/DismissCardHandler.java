package com.anthropic.claudecode.eclipse.ui.handlers;

import org.eclipse.core.commands.AbstractHandler;
import org.eclipse.core.commands.ExecutionEvent;

import com.anthropic.claudecode.eclipse.ui.ClaudeGuiView;

/**
 * Dismisses the permission / question / diff-review card currently awaiting an answer.
 *
 * <p>Bound per scheme in {@code plugin.xml} — Esc under the default scheme, Ctrl+G under
 * Emacs, where Esc is a multi-stroke prefix and never reaches the page. Both bindings are
 * scoped to the {@code contexts.cardOpen} context, which is only active while a card is up,
 * so neither key is taken away from the rest of the IDE.
 *
 * <p>The work is deliberately delegated to the page rather than reimplemented here: the
 * card's own JS {@code cancel()} already answers the CLI, resolves the step dot and tears
 * the card down. Going through it means the keyboard path and the in-page Esc path cannot
 * drift apart, and the existing {@code resolved} guard makes a double-fire a no-op.
 */
public class DismissCardHandler extends AbstractHandler {

    @Override
    public Object execute(ExecutionEvent event) {
        ClaudeGuiView.dismissActiveCard();
        return null;
    }
}
