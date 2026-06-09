package com.anthropic.claudecode.eclipse.resolvers;

import org.eclipse.core.runtime.Adapters;
import org.eclipse.jface.text.BadLocationException;
import org.eclipse.jface.text.IDocument;
import org.eclipse.ui.IEditorPart;
import org.eclipse.ui.texteditor.ITextEditor;

public final class EntityResolverHelpers {

	private EntityResolverHelpers() {}

	/**
	 * Reveals 1-based {@code line} in {@code editor} when it adapts to a text editor. A non-positive
	 * line, a non-text editor, or a line outside the document is a no-op (the editor stays where it
	 * opened). Must run on the SWT thread.
	 */
	public static void revealLine(IEditorPart editor, int line) {
		if (line <= 0) {
			return;
		}
		ITextEditor textEditor = Adapters.adapt(editor, ITextEditor.class);
		if (textEditor == null) {
			return;
		}
		IDocument document = textEditor.getDocumentProvider().getDocument(textEditor.getEditorInput());
		if (document == null) {
			return;
		}
		try {
			textEditor.selectAndReveal(document.getLineOffset(line - 1), 0);
		} catch (BadLocationException e) {
			// Line is outside the document — leave the editor where it opened.
		}
	}
}
