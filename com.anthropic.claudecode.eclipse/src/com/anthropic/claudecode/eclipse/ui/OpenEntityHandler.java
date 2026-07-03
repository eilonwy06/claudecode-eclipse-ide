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

/**
 * Opens an entity from the Claude Terminal, either on Ctrl/Cmd + left-click (the hovered
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

	@Override
	public void mouseUp(ITerminalTextDataReadOnly terminalText, int line, int column, int button, int stateMask) {
		// Only Ctrl-click (Cmd on macOS) on the left button; leave normal clicks/selection alone.
		if ((stateMask & SWT.MODIFIER_MASK) != SWT.MOD1 || button != 1) {
			return;
		}
		// Hovered token may carry wrapping punctuation/quotes → strip edges.
		openEntity(terminal.getHoverSelection(), true);
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
		Job job = new Job("Resolving entity from Claude Terminal") {
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
			statusLine.setErrorMessage("Could not show the entity chooser: Claude Terminal is unavailable.");
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
