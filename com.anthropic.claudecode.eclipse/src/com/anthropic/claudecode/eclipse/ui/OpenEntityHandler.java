package com.anthropic.claudecode.eclipse.ui;

import java.util.List;

import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.Status;
import org.eclipse.core.runtime.jobs.Job;
import org.eclipse.jface.action.IStatusLineManager;
import org.eclipse.swt.SWT;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Menu;
import org.eclipse.swt.widgets.MenuItem;
import org.eclipse.terminal.control.ITerminalMouseListener;
import org.eclipse.terminal.control.ITerminalViewControl;
import org.eclipse.terminal.model.ITerminalTextDataReadOnly;

import com.anthropic.claudecode.eclipse.editor.UiHelper;
import com.anthropic.claudecode.eclipse.resolvers.EntitiesRegistry;
import com.anthropic.claudecode.eclipse.resolvers.EntitiesRegistry.NamedResolvedEntity;
import com.anthropic.claudecode.eclipse.resolvers.FileEntityResolver;

/**
 * Opens an entity from the Claude CLI terminal, either on Ctrl/Cmd + left-click (the hovered
 * token) or on demand from the context menu (the current selection).
 *
 * <p>The mouse gesture is modeled on Eclipse's {@code OpenFileMouseHandler} (only MOD1 +
 * left-button is handled), but recognition is delegated to {@link EntitiesRegistry}: the text is
 * run past every resolver and {@link EntitiesRegistry.NamedResolvedEntity#entity() locate()} is
 * invoked on the first match (a file path opens an editor, a URL opens a browser). Recognition
 * runs in a background {@link org.eclipse.core.runtime.jobs.Job} (a file miss can traverse the
 * whole workspace and must not block the SWT thread); the result is handled back on the SWT thread.
 */
public class OpenEntityHandler implements ITerminalMouseListener {

	private final ITerminalViewControl terminal;
	private final EntitiesRegistry registry;
	private final IStatusLineManager statusLine;

	/** The most recently scheduled resolve job, so a new click can cancel a still-running one.
	 * Touched only on the SWT thread (from {@link #openEntity}), so it needs no synchronization. */
	private Job currentResolveJob;

	public OpenEntityHandler(ITerminalViewControl terminal, EntitiesRegistry registry,
			IStatusLineManager statusLine) {
		this.terminal = terminal;
		this.registry = registry;
		this.statusLine = statusLine;
	}

	@Override
	public void mouseDown(ITerminalTextDataReadOnly terminalText, int line, int column, int button, int stateMask) {
		statusLine.setErrorMessage(null);
		statusLine.setMessage(null);
	}

	/** Wrapping / sentence punctuation trimmed from a recovered path's trailing edge. A {@code :NN}
	 *  line reference is preserved (it has no chars in this set) for {@link FileEntityResolver}. */
	private static final String TRAILING_PUNCT = ")]}>\"'`,;!?.";

	@Override
	public void mouseUp(ITerminalTextDataReadOnly terminalText, int line, int column, int button, int stateMask) {
		// Only Ctrl-click (Cmd on macOS) on the left button; leave normal clicks/selection alone.
		if ((stateMask & SWT.MODIFIER_MASK) != SWT.MOD1 || button != 1) {
			return;
		}
		String hover = terminal.getHoverSelection();
		// A Windows path containing spaces (C:\Users\Windows 10\...) is split by
		// getHoverSelection() at every space, so clicking any segment gives only a fragment.
		// Recover the full path from the drive root before the click. Fully defensive: returns
		// null on anything unexpected, so the normal hovered-token flow (below) is unchanged.
		String recovered = recoverSpacedPath(terminalText, line, hover);
		if (recovered != null) {
			openEntity(recovered, false);
		} else {
			// Hovered token may carry wrapping punctuation/quotes → strip edges.
			openEntity(hover, true);
		}
	}

	/**
	 * Recovers a space-containing Windows path when {@code getHoverSelection()} split it at the spaces.
	 * Works for a click on <em>any</em> segment: it finds the drive/UNC root at or before the clicked
	 * fragment and grows from there, shrinking word-by-word from the right to the longest span that
	 * names an existing file. The span must still cover the clicked fragment, so clicking trailing
	 * prose after a path does not open the path. Anchors via string search, not the mouse line/column
	 * coordinates. Returns {@code null} — caller uses the hovered token unchanged — on any miss or
	 * error, and never throws.
	 */
	private static String recoverSpacedPath(ITerminalTextDataReadOnly term, int line, String hover) {
		try {
			if (hover == null || hover.isBlank() || FileEntityResolver.existingAbsoluteFile(hover)) {
				return null; // nothing to recover, or the hovered token already names a file
			}
			String text = lineChars(term, line);
			if (text == null) {
				return null;
			}
			int hoverStart = text.indexOf(hover);
			if (hoverStart < 0) {
				return null; // fragment not on this line (wrapped/scrolled) — don't guess
			}
			int hoverEnd = hoverStart + hover.length();
			int start = drivePathStart(text, hoverStart);
			if (start < 0) {
				return null; // no drive/UNC root before the click
			}
			String candidate = text.substring(start).stripTrailing();
			while (!candidate.isEmpty()) {
				String probe = trimTrailingPunct(candidate);
				if (FileEntityResolver.existingAbsoluteFile(probe)) {
					// Accept only if the file span still covers the clicked fragment.
					return start + probe.length() >= hoverEnd ? probe : null;
				}
				int sp = candidate.lastIndexOf(' ');
				if (sp < 0) {
					break;
				}
				candidate = candidate.substring(0, sp).stripTrailing();
			}
			return null;
		} catch (RuntimeException e) {
			return null; // recovery must never break a click
		}
	}

	/**
	 * Index of the drive ({@code C:\} / {@code C:/}) or UNC ({@code \\}) root closest to but not after
	 * {@code beforeIndex}, i.e. the start of the absolute path containing the click; {@code -1} if none.
	 * Only roots are matched (not interior {@code /}), so a forward-slash path resolves to its drive.
	 */
	private static int drivePathStart(String line, int beforeIndex) {
		int best = -1;
		int limit = Math.min(beforeIndex, line.length() - 2);
		for (int i = 0; i <= limit; i++) {
			char a = line.charAt(i);
			char b = line.charAt(i + 1);
			if (b == ':' && Character.isLetter(a) && i + 2 < line.length()
					&& (line.charAt(i + 2) == '\\' || line.charAt(i + 2) == '/')) {
				best = i;
			} else if (a == '\\' && b == '\\') {
				best = i;
			}
		}
		return best;
	}

	/** The full text of {@code line} (NUL cells rendered as spaces), or {@code null} if out of range. */
	private static String lineChars(ITerminalTextDataReadOnly term, int line) {
		if (term == null || line < 0 || line >= term.getHeight()) {
			return null;
		}
		char[] cs = term.getChars(line);
		if (cs == null) {
			return null;
		}
		StringBuilder sb = new StringBuilder(cs.length);
		for (char c : cs) {
			sb.append(c == 0 ? ' ' : c);
		}
		return sb.toString();
	}

	private static String trimTrailingPunct(String s) {
		int end = s.length();
		while (end > 0 && TRAILING_PUNCT.indexOf(s.charAt(end - 1)) >= 0) {
			end--;
		}
		return s.substring(0, end);
	}

	/**
	 * Resolves {@code text} against the registry and opens the matching entity (a file path opens
	 * an editor, a URL opens a browser). A single match opens directly; several matches pop a small
	 * menu so the user picks which one; no match shows a hint in the status bar.
	 *
	 * <p>Recognition runs in a background {@link Job}: a file token that misses can trigger a full
	 * workspace tree traversal, which must not block the SWT thread (this method is always called
	 * on it — mouse callback / context-menu action). A still-running resolve from an earlier click
	 * is cancelled first so rapid clicks don't pile up; the result is handled back on the SWT thread
	 * by {@link #handleResolved}.
	 *
	 * @param allowStripEdges strip wrapping punctuation/quotes before matching — {@code true} for
	 *     a hovered token, {@code false} when {@code text} is a deliberate user selection.
	 */
	public void openEntity(String text, boolean allowStripEdges) {
		if (text == null || text.isEmpty()) {
			return;
		}
		if (currentResolveJob != null) {
			currentResolveJob.cancel();
		}
		statusLine.setErrorMessage(null);
		statusLine.setMessage("Opening entity from \""+text+"\"...");
		Job job = new Job("Resolving entity from Claude CLI") {
			@Override
			protected IStatus run(IProgressMonitor monitor) {
				List<NamedResolvedEntity> entities = registry.resolve(text, allowStripEdges);
				if (monitor.isCanceled()) {
					return Status.CANCEL_STATUS; // superseded by a later click — drop the result
				}
				UiHelper.asyncExec(() -> handleResolved(text, entities));
				return Status.OK_STATUS;
			}
		};
		job.setSystem(true);
		job.setPriority(Job.INTERACTIVE);
		currentResolveJob = job;
		job.schedule();
	}

	/**
	 * Dispatches the resolved entities on the SWT thread: no match shows a status-bar hint, a single
	 * match opens directly ({@code locate()} hops to the SWT thread itself), and several matches pop
	 * the chooser menu.
	 */
	private void handleResolved(String text, List<NamedResolvedEntity> entities) {
		statusLine.setMessage(null);
		if (entities.isEmpty()) {
			statusLine.setErrorMessage("Unable to recognize entity to open from \""+text+"\"");
		} else if (entities.size() == 1) {
			entities.get(0).entity().locate();
		} else {
			showChooser(entities);
		}
	}

	/**
	 * Pops a menu at the cursor listing every resolved entity by its resolver name, so the user can
	 * choose which one to open. If the terminal canvas is gone (so no menu can be anchored), the
	 * miss is surfaced in the status bar rather than silently opening an arbitrary match.
	 */
	private void showChooser(List<NamedResolvedEntity> entities) {
		Control canvas = terminal.getControl();
		if (canvas == null || canvas.isDisposed()) {
			statusLine.setErrorMessage("Could not show the entity chooser: Claude CLI is unavailable.");
			return;
		}
		Menu menu = new Menu(canvas);
		for (NamedResolvedEntity entity : entities) {
			MenuItem item = new MenuItem(menu, SWT.PUSH);
			item.setText("Open as " + entity.resolverName());
			item.addListener(SWT.Selection, e -> entity.entity().locate());
		}
		// Dispose once hidden, but defer so the chosen item's Selection handler runs first.
		menu.addListener(SWT.Hide, e -> canvas.getDisplay().asyncExec(menu::dispose));
		menu.setLocation(canvas.getDisplay().getCursorLocation());
		menu.setVisible(true);
	}
}
