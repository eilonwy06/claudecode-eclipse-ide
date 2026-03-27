package com.anthropic.claudecode.eclipse.ui.handlers;

import org.eclipse.core.commands.AbstractHandler;
import org.eclipse.core.commands.ExecutionEvent;
import org.eclipse.core.commands.ExecutionException;
import org.eclipse.jface.text.ITextSelection;
import org.eclipse.jface.viewers.ISelection;
import org.eclipse.ui.IEditorPart;
import org.eclipse.ui.handlers.HandlerUtil;
import org.eclipse.ui.texteditor.ITextEditor;

import com.anthropic.claudecode.eclipse.Activator;

public class SendSelectionHandler extends AbstractHandler {

    @Override
    public Object execute(ExecutionEvent event) throws ExecutionException {
        try {
            IEditorPart editor = HandlerUtil.getActiveEditor(event);
            if (!(editor instanceof ITextEditor textEditor)) return null;

            ISelection selection = textEditor.getSelectionProvider().getSelection();
            if (!(selection instanceof ITextSelection textSelection) || textSelection.isEmpty()) {
                return null;
            }

            // The selection is already tracked and available to Claude via the
            // getCurrentSelection/getLatestSelection MCP tools.
            // This handler acts as a user-initiated "attention" signal.
            Activator.log("Selection sent to Claude context: "
                    + textSelection.getText().length() + " chars");

        } catch (Exception e) {
            Activator.logError("Failed to send selection", e);
        }
        return null;
    }
}
