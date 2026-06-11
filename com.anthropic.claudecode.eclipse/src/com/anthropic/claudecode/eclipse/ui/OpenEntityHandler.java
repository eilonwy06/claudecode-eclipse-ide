package com.anthropic.claudecode.eclipse.ui;

import java.util.ArrayList;
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

import com.anthropic.claudecode.eclipse.Activator;
import com.anthropic.claudecode.eclipse.Constants;
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

	/** Safety cap on how many wrapped rows are stitched into one logical path. */
	private static final int MAX_WRAP_ROWS = 64;

	/** Cap on the join-ambiguity branches explored while reconstructing a wrapped path. */
	private static final int MAX_BRANCHES = 64;

	/** Caps for workspace-relative recovery: candidate start words per row, stitched rows. */
	private static final int MAX_REL_STARTS = 16;
	private static final int MAX_REL_ROWS = 8;

	/** Cap on widened word-span candidates tried around the hovered token. */
	private static final int MAX_NAME_SPANS = 12;

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
		String recovered = null;
		try {
			recovered = recoverSpacedPath(terminalText, line, hover);
		} catch (Throwable t) {
			// Recovery is best-effort and must never take the baseline click down with it.
			// Throwable (not just RuntimeException): a stale class with baked-in compile
			// problems throws java.lang.Error, which previously escaped and killed ALL
			// Ctrl+clicks instead of degrading to the plain hovered-token behavior.
			dbg("recovery threw " + t.getClass().getSimpleName() + ": " + t.getMessage());
		}
		if (recovered != null) {
			openEntity(recovered, false);
		} else {
			// A filename containing spaces ("Cutie File.java") hovers as a single word and has no
			// separators for the path recovery to anchor on. Widen word-spans around the clicked
			// token and run them through the normal resolvers (which include the workspace
			// search). The hovered token stays first in the list, so every click that resolves
			// today behaves exactly as before; the widened spans only matter when it doesn't.
			// quietMiss: a Ctrl+click that lands on plain prose should do nothing, not flash
			// "Unable to recognize entity" for every ordinary word.
			openEntityCandidates(buildCandidates(terminalText, line, hover), hover, true, true);
		}
	}

	/**
	 * Recovers a space-containing absolute path that {@code getHoverSelection()} split at its spaces,
	 * including when Claude's TUI hard-wraps the path across several physical rows (which the terminal
	 * reports with {@code isWrappedLine == false} and a leading indent on each continuation row).
	 *
	 * <p>Finds the drive/UNC root at or above the clicked row, then concatenates the path's physical
	 * rows — stripping each continuation row's indent (none for a true soft-wrap row) — and shrinks
	 * word-by-word from the right to the longest span that names an existing file and still covers the
	 * clicked fragment. Every candidate is validated against the filesystem. Returns {@code null}
	 * (caller uses the hovered token unchanged) on any miss or error, and never throws.
	 */
	private static String recoverSpacedPath(ITerminalTextDataReadOnly term, int line, String hover) {
		try {
			if (hover == null || hover.isBlank() || FileEntityResolver.existingAbsoluteFile(hover)) {
				return null; // nothing to recover, or the hovered token already names a file
			}
			dumpRows(term, line); // diagnostics (debug mode only)
			int height = term.getHeight();
			if (line < 0 || line >= height) {
				return null;
			}

			// Find the row that begins the path: the clicked row if it holds a drive/UNC root, else
			// walk up across (indented) continuation rows to it. A blank row bounds the search.
			// On the clicked row the root must sit at or before the clicked fragment, so a line
			// holding several paths anchors to the one that was actually clicked.
			int startRow = -1;
			int driveCol = -1;
			for (int r = line, n = 0; r >= 0 && n <= MAX_WRAP_ROWS; r--, n++) {
				String rt = rowText(term, r);
				if (rt == null || rt.isBlank()) {
					break;
				}
				int before = rt.length();
				if (r == line) {
					int hs = rt.indexOf(hover);
					if (hs >= 0) {
						before = hs;
					}
				}
				int dc = drivePathStart(rt, before);
				if (dc >= 0) {
					startRow = r;
					driveCol = dc;
					break;
				}
			}
			if (startRow < 0) {
				dbg("no drive/UNC root at or above the click — trying workspace-relative");
				return recoverRelative(term, line, hover);
			}

			// Concatenate the path's rows: the start row from the drive root, then each continuation
			// row with its indent removed (a genuine soft-wrap row has none). A path space can land
			// exactly on a wrap boundary and be indistinguishable from padding, so at each boundary we
			// branch on "no space" vs "a space" and let the filesystem pick the path that exists.
			List<String> accs = new ArrayList<>();
			accs.add(rowText(term, startRow).substring(driveCol).stripTrailing());
			String found = matchAny(accs, hover, true);
			if (found != null) {
				dbg("MATCH=[" + found + "]");
				return found;
			}
			for (int r = startRow + 1, n = 0; r < height && n < MAX_WRAP_ROWS; r++, n++) {
				String rt = rowText(term, r);
				if (rt == null) {
					break;
				}
				// Continuation rows carry the TUI's hanging indent, whose width is NOT reliably
				// the path's start column — strip all leading/trailing padding and let the
				// filesystem checks validate the join instead of trusting the layout.
				String cont = rt.strip();
				if (cont.isEmpty()) {
					break; // blank row — end of the wrapped path block
				}
				List<String> next = new ArrayList<>();
				for (String a : accs) {
					next.add(a + cont);        // wrap fell mid-token
					next.add(a + " " + cont);  // wrap fell on a path space
					if (next.size() >= MAX_BRANCHES) {
						break;
					}
				}
				accs = next;
				found = matchAny(accs, hover, true);
				if (found != null) {
					dbg("MATCH(multi-row)=[" + found + "]");
					return found;
				}
			}
			dbg("absolute recovery missed (" + accs.size() + " branch(es)) — trying workspace-relative");
			return recoverRelative(term, line, hover);
		} catch (RuntimeException e) {
			return null; // recovery must never break a click
		}
	}

	/**
	 * Workspace-relative variant of the recovery: Claude often prints paths relative to the
	 * workspace ({@code Sample\src\aaa bbb\file.java}), which contain no drive root to anchor on.
	 * Candidate path starts are the word starts on the clicked row at or before the clicked
	 * fragment (and on the row above, for a click on the continuation of a wrapped path), words
	 * containing a path separator first. Each start is grown to the row end, then across following
	 * rows with the same boundary-space branching as the absolute flow; every span is validated
	 * against the workspace before being accepted. The winning span is returned in its relative
	 * form — the entity-resolver pipeline opens it via the workspace suffix search.
	 */
	private static String recoverRelative(ITerminalTextDataReadOnly term, int line, String hover) {
		int height = term.getHeight();
		String clicked = rowText(term, line);
		if (clicked == null) {
			return null;
		}
		int hs = clicked.indexOf(hover);
		if (hs < 0) {
			return null;
		}
		for (int startRow = line; startRow >= Math.max(0, line - 1); startRow--) {
			String rt = rowText(term, startRow);
			if (rt == null || rt.isBlank()) {
				break;
			}
			List<Integer> starts = wordStarts(rt, startRow == line ? hs : rt.length());
			// Pass 1: the path completes on this row.
			for (int s : starts) {
				String m = matchPath(rt.substring(s).stripTrailing(), hover, false);
				if (m != null) {
					dbg("MATCH(relative)=[" + m + "]");
					return m;
				}
			}
			// Pass 2: the path continues on following rows (hard-wrap; a space falling exactly on
			// the row boundary is indistinguishable from padding, hence the two-way branching).
			for (int s : starts) {
				if (rt.indexOf('\\', s) < 0 && rt.indexOf('/', s) < 0) {
					continue; // no separator after this start — not a path row, skip the stitching
				}
				List<String> accs = new ArrayList<>();
				accs.add(rt.substring(s).stripTrailing());
				for (int r = startRow + 1, n = 0; r < height && n < MAX_REL_ROWS; r++, n++) {
					String next = rowText(term, r);
					if (next == null) {
						break;
					}
					String cont = next.strip();
					if (cont.isEmpty()) {
						break;
					}
					List<String> grown = new ArrayList<>();
					for (String a : accs) {
						grown.add(a + cont);
						grown.add(a + " " + cont);
						if (grown.size() >= MAX_BRANCHES) {
							break;
						}
					}
					accs = grown;
					String m = matchAny(accs, hover, false);
					if (m != null) {
						dbg("MATCH(relative,multi-row)=[" + m + "]");
						return m;
					}
				}
			}
		}
		dbg("relative recovery missed");
		return null;
	}

	/**
	 * Word-start columns in {@code rt} at or before {@code maxStart}, words containing a path
	 * separator first (the likely start of a relative path), each group left to right, capped at
	 * {@link #MAX_REL_STARTS}.
	 */
	private static List<Integer> wordStarts(String rt, int maxStart) {
		List<Integer> withSep = new ArrayList<>();
		List<Integer> without = new ArrayList<>();
		for (int i = 0; i < rt.length() && i <= maxStart; i++) {
			if (rt.charAt(i) != ' ' && (i == 0 || rt.charAt(i - 1) == ' ')) {
				int end = rt.indexOf(' ', i);
				String word = end < 0 ? rt.substring(i) : rt.substring(i, end);
				(word.indexOf('\\') >= 0 || word.indexOf('/') >= 0 ? withSep : without).add(i);
			}
		}
		List<Integer> out = new ArrayList<>(withSep);
		for (Integer s : without) {
			if (out.size() >= MAX_REL_STARTS) {
				break;
			}
			out.add(s);
		}
		return out.size() > MAX_REL_STARTS ? out.subList(0, MAX_REL_STARTS) : out;
	}

	/** First non-null {@link #matchPath} across the candidate accumulators (join-ambiguity branches). */
	private static String matchAny(List<String> candidates, String hover, boolean absolute) {
		for (String c : candidates) {
			String m = matchPath(c, hover, absolute);
			if (m != null) {
				return m;
			}
		}
		return null;
	}

	/**
	 * The longest word-shrunk span of {@code path} that names an existing file and still covers
	 * {@code hover}. With {@code absolute} the span starts at a drive/UNC root and is tested
	 * directly; otherwise it is tested as a workspace-relative path and must contain a path
	 * separator, so prose spans cost no filesystem probes. Returns {@code null} if {@code hover}
	 * is not within {@code path} or nothing resolves.
	 */
	private static String matchPath(String path, String hover, boolean absolute) {
		int hoverEnd = path.indexOf(hover);
		if (hoverEnd < 0) {
			return null; // the clicked fragment isn't within this span yet
		}
		hoverEnd += hover.length();
		String candidate = path;
		while (!candidate.isEmpty()) {
			String probe = trimTrailingPunct(candidate);
			boolean exists = absolute
					? FileEntityResolver.existingAbsoluteFile(probe)
					: (probe.indexOf('\\') >= 0 || probe.indexOf('/') >= 0)
							&& FileEntityResolver.existingWorkspaceRelativeFile(probe);
			if (exists) {
				return probe.length() >= hoverEnd ? probe : null;
			}
			int sp = candidate.lastIndexOf(' ');
			if (sp < 0) {
				break;
			}
			candidate = candidate.substring(0, sp).stripTrailing();
		}
		return null;
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

	/** A single physical row's text with NUL cells rendered as spaces, or {@code null} if out of range. */
	private static String rowText(ITerminalTextDataReadOnly term, int r) {
		if (term == null || r < 0 || r >= term.getHeight()) {
			return null;
		}
		char[] cs = term.getChars(r);
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

	/** Number of whitespace-separated words in {@code s}. */
	private static int wordCount(String s) {
		String t = s.strip();
		return t.isEmpty() ? 0 : t.split("\\s+").length;
	}

	/** Whether {@code span}'s last word ends in a short {@code .ext} (a likely filename). */
	private static boolean looksLikeFilename(String span) {
		int sp = span.lastIndexOf(' ');
		String last = trimTrailingPunct(sp < 0 ? span : span.substring(sp + 1));
		int dot = last.lastIndexOf('.');
		if (dot <= 0 || dot == last.length() - 1 || last.length() - dot - 1 > 8) {
			return false;
		}
		for (int i = dot + 1; i < last.length(); i++) {
			if (!Character.isLetterOrDigit(last.charAt(i))) {
				return false;
			}
		}
		return true;
	}

	// ── Diagnostics (debug mode only) ────────────────────────────────────────

	private static boolean debug() {
		try {
			return Activator.getDefault().getPreferenceStore().getBoolean(Constants.PREF_DEBUG_MODE);
		} catch (Exception e) {
			return false;
		}
	}

	private static void dbg(String msg) {
		if (debug()) {
			try {
				Activator.log("[OpenEntity] " + msg);
			} catch (Exception ignore) {
				// logging must never affect a click
			}
		}
	}

	/** Dumps the raw cells and wrap flags of the rows around {@code line} so we can see how the
	 *  terminal laid out a multi-row path (NUL cells shown as '·'). Debug mode only. */
	private static void dumpRows(ITerminalTextDataReadOnly term, int line) {
		if (!debug()) {
			return;
		}
		try {
			int h = term.getHeight();
			Activator.log("[OpenEntity] click line=" + line + " height=" + h + " width=" + term.getWidth());
			for (int r = Math.max(0, line - 2); r <= Math.min(h - 1, line + 2); r++) {
				char[] cs = term.getChars(r);
				String s = cs == null ? "<null>" : new String(cs).replace('\0', '·');
				Activator.log("[OpenEntity] row " + r + " wrapped=" + term.isWrappedLine(r) + " |" + s + "|");
			}
		} catch (Exception ignore) {
			// diagnostics must never affect a click
		}
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
		// Deliberate action (context-menu "Open" on a selection) — report a miss in the status bar.
		openEntityCandidates(List.of(text), text, allowStripEdges, false);
	}

	/**
	 * Same as {@link #openEntity} but tries {@code candidates} in order inside one background
	 * {@link Job}, opening the first one any resolver recognizes; {@code label} is what the status
	 * bar shows while resolving. With {@code quietMiss} a total miss just clears the status line
	 * instead of showing the "Unable to recognize entity" error.
	 */
	private void openEntityCandidates(List<String> candidates, String label, boolean allowStripEdges,
			boolean quietMiss) {
		if (candidates.isEmpty()) {
			return;
		}
		if (currentResolveJob != null) {
			currentResolveJob.cancel();
		}
		statusLine.setErrorMessage(null);
		statusLine.setMessage("Opening entity from \"" + label + "\"...");
		Job job = new Job("Resolving entity from Claude CLI") {
			@Override
			protected IStatus run(IProgressMonitor monitor) {
				for (String candidate : candidates) {
					List<NamedResolvedEntity> entities = registry.resolve(candidate, allowStripEdges);
					if (monitor.isCanceled()) {
						return Status.CANCEL_STATUS; // superseded by a later click — drop the result
					}
					if (!entities.isEmpty()) {
						UiHelper.asyncExec(() -> handleResolved(candidate, entities));
						return Status.OK_STATUS;
					}
				}
				if (quietMiss) {
					UiHelper.asyncExec(() -> statusLine.setMessage(null));
				} else {
					UiHelper.asyncExec(() -> handleResolved(label, List.of()));
				}
				return Status.OK_STATUS;
			}
		};
		job.setSystem(true);
		job.setPriority(Job.INTERACTIVE);
		currentResolveJob = job;
		job.schedule();
	}

	/**
	 * The hovered token followed by widened word-spans around it on the clicked row, longest (most
	 * specific) first — so a filename containing spaces resolves even though hovering yields only
	 * one of its words. Spans go through the normal entity pipeline, which validates them against
	 * the workspace, so prose spans simply miss. Best-effort: on any failure the list still holds
	 * the hovered token.
	 */
	private static List<String> buildCandidates(ITerminalTextDataReadOnly term, int line, String hover) {
		List<String> out = new ArrayList<>();
		out.add(hover);
		try {
			String rt = rowText(term, line);
			if (rt == null) {
				return out;
			}
			int hs = rt.indexOf(hover);
			if (hs < 0) {
				return out;
			}
			// Token positions (non-space runs) and the index of the hovered token.
			List<int[]> tokens = new ArrayList<>();
			int ti = -1;
			for (int i = 0; i < rt.length();) {
				if (rt.charAt(i) == ' ') {
					i++;
					continue;
				}
				int end = rt.indexOf(' ', i);
				if (end < 0) {
					end = rt.length();
				}
				if (i <= hs && hs < end) {
					ti = tokens.size();
				}
				tokens.add(new int[] { i, end });
				i = end;
			}
			if (ti < 0) {
				return out;
			}
			List<String> spans = new ArrayList<>();
			for (int l = 0; l <= 3; l++) {
				for (int r = 0; r <= 3; r++) {
					if (l == 0 && r == 0) {
						continue; // that's the hovered token, already first in the list
					}
					int a = ti - l;
					int b = ti + r;
					if (a < 0 || b >= tokens.size()) {
						continue;
					}
					String span = rt.substring(tokens.get(a)[0], tokens.get(b)[1]);
					if (span.length() <= 120 && !spans.contains(span)) {
						spans.add(span);
					}
				}
			}
			// Rank a filename (last token has an extension) ahead of prose spans, and among those
			// the fewest-word span first — so "Cutie File.java" beats "actually Cutie File.java
			// with a capital" instead of being crowded out by longer spans and dropped by the cap.
			spans.sort(java.util.Comparator
					.comparingInt((String s) -> looksLikeFilename(s) ? 0 : 1)
					.thenComparingInt(OpenEntityHandler::wordCount)
					.thenComparingInt(String::length));
			for (String s : spans) {
				if (out.size() > MAX_NAME_SPANS) {
					break;
				}
				out.add(s);
			}
			dbg("widened to " + (out.size() - 1) + " span candidate(s) around [" + hover + "]");
		} catch (RuntimeException e) {
			// Candidate widening is best-effort; the hovered token alone is already in the list.
		}
		return out;
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
