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
import com.anthropic.claudecode.eclipse.ui.ClaudeCliView;

/**
 * Sends the active editor's selection to the Claude Terminal by typing an
 * {@code @<path>#L<start>-<end>} mention into the embedded CLI terminal.
 * Requires an OPEN terminal (dialog otherwise — never opens one as a side
 * effect). Shares the view/path helpers with {@link AddFileHandler}.
 */
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

            String filePath = AddFileHandler.filePathOf(editor.getEditorInput());
            if (filePath == null) return null;

            ClaudeCliView view = AddFileHandler.findOpenTerminal(event);
            if (view == null) return null;

            // ITextSelection lines are 0-based; Claude's #L references are 1-based.
            int start = textSelection.getStartLine() + 1;
            int end = textSelection.getEndLine() + 1;
            String lines = start == end ? "#L" + start : "#L" + start + "-" + end;
            String mention = "@" + AddFileHandler.mentionPath(filePath, view) + lines + " ";

            if (view.sendTextToActiveSession(mention)) {
                Activator.log("Selection sent to Claude: " + mention.trim());
            } else {
                AddFileHandler.showNoTerminalDialog(event);   // view open but no live session/tab
            }
        } catch (Exception e) {
            Activator.logError("Failed to send selection", e);
        }
        return null;
    }
}
