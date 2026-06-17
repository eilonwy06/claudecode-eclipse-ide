package com.anthropic.claudecode.eclipse.resolvers;

import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.eclipse.core.filesystem.EFS;
import org.eclipse.core.filesystem.IFileStore;
import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.Status;
import org.eclipse.jface.dialogs.IDialogSettings;
import org.eclipse.jface.viewers.LabelProvider;
import org.eclipse.jface.viewers.StyledString;
import org.eclipse.jface.window.Window;
import org.eclipse.swt.graphics.Image;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.ui.IWorkbenchPage;
import org.eclipse.ui.dialogs.FilteredItemsSelectionDialog;
import org.eclipse.ui.dialogs.SearchPattern;
import org.eclipse.ui.ide.IDE;
import org.eclipse.ui.model.WorkbenchLabelProvider;

import com.anthropic.claudecode.eclipse.Activator;
import com.anthropic.claudecode.eclipse.editor.UiHelper;

/**
 * Recognizes a file path inside a single space-delimited token taken from a Claude Code answer and
 * resolves it to something openable.
 *
 * <p>Handling, in order: optional edge trimming of wrapping/quoting/sentence punctuation; a shortcut
 * that defers {@code http://}/{@code https://} tokens to {@link WebLinkEntityResolver}; stripping a
 * {@code file://} prefix; pulling a trailing line reference ({@code :10} or {@code :10-15}); then a
 * lookup. An existing absolute path resolves directly; otherwise the workspace is searched for files
 * whose path ends with the token — a single hit opens it, several hits open a selection dialog listing
 * just those matches (the file analog of the identifier resolvers' choosers).
 */
public class FileEntityResolver implements IEntityResolver {

	private static final String FILE_SCHEME = "file://";

	/**
	 * Wrapping / quoting / sentence punctuation trimmed from a token's <em>trailing</em> edge (when
	 * allowed); the leading edge uses {@link #LEADING_EDGE_JUNK}. A '.' belongs here so a
	 * sentence-ending period after a path (e.g. "see Foo.java.") does not defeat the match.
	 */
	private static final String EDGE_JUNK = " \t\r\n\u00A0()[]{}<>\"'`,;!?.:@";

	/**
	 * Same as {@link #EDGE_JUNK} but without '.', used for the <em>leading</em> edge only, so a leading
	 * dot in a dotfile ({@code .gitignore}) or a relative path ({@code ./src}, {@code ../foo}) survives.
	 */
	private static final String LEADING_EDGE_JUNK = EDGE_JUNK.replace(".", "");

	/** Trailing line reference: {@code :10} or {@code :10-15} (digits, with at most one hyphen). */
	private static final Pattern LINE_SUFFIX = Pattern.compile(":(\\d+)([-\u2013]\\d+)?$");

	@Override
	public String getName() {
		return "File";
	}

	@Override
	public IResolvedEntity resolve(String text, boolean allowStripEdges) {
		if (text == null || text.isBlank()) {
			return null;
		}

		if (allowStripEdges) {
			text = stripEdges(text);
		}
		if (text.isEmpty()) {
			return null;
		}

		// Optimization: don't run workspace searches for web URLs despite they may look like file paths.
		String lower = text.toLowerCase();
		if (lower.startsWith("http://") || lower.startsWith("https://")) {
			return null;
		}
		if (lower.startsWith(FILE_SCHEME)) {
			text = text.substring(FILE_SCHEME.length());
		}

		int lineNumber = 0;
		Matcher matcher = LINE_SUFFIX.matcher(text);
		if (matcher.find()) {
			try {
				lineNumber = Integer.parseInt(matcher.group(1));
			} catch (NumberFormatException e) {
				lineNumber = 0; // overflowing line numbers are meaningless; just drop navigation
			}
			text = text.substring(0, matcher.start());
		}

		// Normalize once here so every downstream path (absolute lookup, workspace search) sees a clean,
		// portable path. An OS-invalid token normalizes to "" and misses.
		text = normalizeSep(text);
		if (text.isEmpty()) {
			return null;
		}

		if (isAbsolute(text) && fileExists(text)) {
			final String filePath = text;
			final int line = lineNumber;
			return () -> locate(filePath, line);
		}

		List<IFile> matches = browseWorkspaceFiles(text);
		if (matches.isEmpty()) {
			return null;
		}
		if (matches.size() == 1) {
			final IFile only = matches.get(0);
			final int line = lineNumber;
			return () -> open(only, line);
		}
		final List<IFile> all = matches;
		final int line = lineNumber;
		return () -> openChooser(all, line);
	}

	/** Trims leading/trailing characters unlikely to belong to a path (see {@link #EDGE_JUNK}). */
	private static String stripEdges(String s) {
		int start = 0;
		int end = s.length();
		while (start < end && LEADING_EDGE_JUNK.indexOf(s.charAt(start)) >= 0) {
			start++;
		}
		while (end > start && EDGE_JUNK.indexOf(s.charAt(end - 1)) >= 0) {
			end--;
		}
		return s.substring(start, end);
	}

	private static boolean isAbsolute(String path) {
		try {
			return Path.of(path).isAbsolute();
		} catch (InvalidPathException e) {
			return false;
		}
	}

	/**
	 * Normalizes a path for portable suffix matching: collapses {@code "."}, duplicate separators, and
	 * internal {@code ".."} segments (a leading {@code ".."} is preserved), then forces {@code '/'}
	 * separators. Returns {@code ""} when the OS does not accept the token as a path — callers treat
	 * empty as "no path". Package-private for testing.
	 */
	static String normalizeSep(String path) {
		try {
			return Path.of(path).normalize().toString().replace('\\', '/');
		} catch (InvalidPathException e) {
			return "";
		}
	}

	/**
	 * Whether {@code fullPath} ends with {@code needle} on a path-segment boundary — so suffix
	 * {@code ui/Foo.java} matches {@code .../ui/Foo.java} but not {@code .../gui/Foo.java}. Both
	 * arguments are expected to already be path-normalized (see {@link #normalizeSep}). Package-private
	 * for testing.
	 */
	static boolean matchesSuffix(String fullPath, String needle) {
		return fullPath.endsWith(needle)
				&& (fullPath.length() == needle.length()
						|| fullPath.charAt(fullPath.length() - needle.length() - 1) == '/');
	}

	/**
	 * Whether {@code absolutePath} points to an existing regular file.
	 * Has package-private scope so tests can override it without touching the real file system.
	 */
	boolean fileExists(String absolutePath) {
		try {
			return Files.isRegularFile(Path.of(absolutePath));
		} catch (Exception e) {
			return false;
		}
	}

	/**
	 * Workspace files whose (path-normalized) location ends with {@code pathSuffix} on a segment
	 * boundary, mimicking Eclipse's "Open Resource" search. {@code pathSuffix} is expected to already be
	 * path-normalized (see {@link #normalizeSep}). Unlike the absolute-path branch this collects every
	 * hit, since several feed the {@link FileSelectionDialog} chooser. Has package-private scope so tests
	 * can override it without a running workspace.
	 */
	List<IFile> browseWorkspaceFiles(String pathSuffix) {
		List<IFile> out = new ArrayList<>();
		String needle = pathSuffix; // already path-normalized by resolve()
		try {
			ResourcesPlugin.getWorkspace().getRoot().accept(res -> {
				if (res.getType() == IResource.FILE && res.getLocation() != null
						&& matchesSuffix(normalizeSep(res.getLocation().toOSString()), needle)) {
					out.add((IFile) res);
				}
				return true;
			});
		} catch (CoreException e) {
			// Return whatever was collected before the failure.
		}
		return out;
	}

	/**
	 * Opens a single matched workspace file and reveals {@code lineNumber} (when positive). The file is
	 * already an {@link IFile}, so it is opened directly — the analog of the identifier resolvers'
	 * {@code open}. Hops to the SWT thread (the resolve callback runs off it). Package-private so tests
	 * can observe it without a workbench.
	 */
	void open(IFile file, int lineNumber) {
		UiHelper.asyncExec(() -> doOpen(file, lineNumber));
	}

	/**
	 * Opens {@code filePath} (an existing absolute path that may live outside the workspace) and reveals
	 * {@code lineNumber} (when positive). A path that maps to a workspace resource is opened as that
	 * {@link IFile} — so it shares the project editor's markers/builders/identity rather than spawning a
	 * second external editor — otherwise it is opened as an external file store. Runs on the SWT thread;
	 * the terminal callback may be off it.
	 */
	void locate(String filePath, int lineNumber) {
		UiHelper.asyncExec(() -> {
			// org.eclipse.core.runtime.Path is fully qualified: the file already imports java.nio.file.Path.
			// Path.fromOSString (vs IPath.fromOSString) keeps us compatible with older targets (e.g. 4.26).
			IFile file = ResourcesPlugin.getWorkspace().getRoot()
					.getFileForLocation(org.eclipse.core.runtime.Path.fromOSString(filePath));
			if (file != null && file.exists()) {
				doOpen(file, lineNumber);
				return;
			}
			try {
				IWorkbenchPage page = UiHelper.getActivePage();
				if (page == null) {
					return;
				}
				IFileStore store = EFS.getLocalFileSystem().getStore(Path.of(filePath).toUri());
				EntityResolverHelpers.revealLine(IDE.openEditorOnFileStore(page, store), lineNumber);
			} catch (Exception e) {
				Activator.logError("Failed to open file: " + filePath, e);
			}
		});
	}

	/** Opens {@code file} in its workspace editor and reveals {@code lineNumber} (when positive). Must
	 *  run on the SWT thread. */
	private void doOpen(IFile file, int lineNumber) {
		try {
			IWorkbenchPage page = UiHelper.getActivePage();
			if (page == null) {
				return;
			}
			EntityResolverHelpers.revealLine(IDE.openEditor(page, file, true), lineNumber);
		} catch (Exception e) {
			Activator.logError("Failed to open file: " + file.getFullPath(), e);
		}
	}

	/**
	 * Pops a selection dialog listing every matched file so the user picks which to open — the file
	 * analog of {@link JavaIdentifierEntityResolver#openChooser}. The shared 1-based {@code lineNumber}
	 * (if any) is applied to whichever files are chosen. Package-private for tests.
	 */
	void openChooser(List<IFile> matches, int lineNumber) {
		UiHelper.asyncExec(() -> {
			IWorkbenchPage page = UiHelper.getActivePage();
			if (page == null) {
				return;
			}
			Shell shell = page.getWorkbenchWindow().getShell();
			FileSelectionDialog dialog = new FileSelectionDialog(shell, matches);
			if (dialog.open() != Window.OK) {
				return;
			}
			for (Object selected : dialog.getResult()) {
				if (selected instanceof IFile file) {
					doOpen(file, lineNumber);
				}
			}
		});
	}

	/**
	 * The workspace path of {@code file} (e.g. {@code /Project/src/Foo.java}) — the file analog of the
	 * identifier resolvers' {@code fqnOf}, named for files rather than as a "fully qualified name". This
	 * is both what {@link #pathMatches} filters against and what the chooser sorts and shows via
	 * {@code getElementName}, so the displayed label and the narrowing logic stay in sync.
	 */
	static String pathOf(IFile file) {
		return file.getFullPath().toString();
	}

	/**
	 * Whether a matched file's {@link #pathOf workspace path} matches {@code matcher}. When
	 * {@code matcher} is built with {@code DEFAULT_MATCH_RULES | RULE_SUBSTRING_MATCH} (as
	 * {@link FileSelectionDialog}'s filter is) this is case-insensitive substring matching with
	 * {@code *}/{@code ?} wildcards, so typing any fragment of what the list shows — the file name
	 * ({@code Foo.java}, {@code *Foo*}) or a folder ({@code src}, {@code src/Foo}) — narrows to it. Pure
	 * (no workbench) so it is unit-testable.
	 */
	static boolean pathMatches(SearchPattern matcher, String path) {
		return matcher.matches(path);
	}

	/**
	 * Selection dialog for several matched files, the file analog of
	 * {@link JavaIdentifierEntityResolver.JavaElementSelectionDialog}. Unlike Eclipse's standard "Open
	 * Resource" picker (which browses the whole workspace), it lists only the resolver's already-narrowed
	 * {@code matches}. The list rows use a {@link StyledFileLabelProvider} — the platform's
	 * {@link WorkbenchLabelProvider} for the per-type file icon, with the styled label re-rendered as the
	 * file name plus a greyed containing-folder path (the "{@code Foo.java} - {@code /Project/src}" form
	 * of the Open Resource dialog). The details panel shows the file's full filesystem location. The Help
	 * button is suppressed by {@link #isHelpAvailable()}.
	 *
	 * <p>The filter box matches the {@link #pathOf workspace path} by case-insensitive substring (with
	 * {@code *}/{@code ?} wildcards): its {@code patternMatcher} is built with
	 * {@link SearchPattern#RULE_SUBSTRING_MATCH}, so typing any fragment of what's shown — the file name
	 * ({@code Foo.java}, {@code *Foo*}) or a folder ({@code src}, {@code src/Foo}) — narrows to it. See
	 * {@code createFilter} and {@link #pathMatches}.
	 */
	static final class FileSelectionDialog extends FilteredItemsSelectionDialog {

		private static final String DIALOG_SETTINGS =
				"com.anthropic.claudecode.eclipse.resolvers.FileSelectionDialog";

		private final List<IFile> matches;

		FileSelectionDialog(Shell shell, List<IFile> matches) {
			super(shell, true);
			this.matches = matches;
			setTitle("Open File");
			setMessage("Multiple files match. Select one or more to open.\n"
					 + "Filter files by name prefix or pattern (*, ?, or camel case):");
			setListLabelProvider(new StyledFileLabelProvider());
			setDetailsLabelProvider(new FileLocationLabelProvider());
		}

		/** Suppresses the Help ('?') button. */
		@Override
		public boolean isHelpAvailable() {
			return false;
		}

		@Override
		protected Control createExtendedContentArea(Composite parent) {
			return null;
		}

		@Override
		protected IDialogSettings getDialogSettings() {
			IDialogSettings settings = Activator.getDefault().getDialogSettings().getSection(DIALOG_SETTINGS);
			if (settings == null) {
				settings = Activator.getDefault().getDialogSettings().addNewSection(DIALOG_SETTINGS);
			}
			return settings;
		}

		@Override
		public String getElementName(Object item) {
			return pathOf((IFile) item);
		}

		@Override
		protected IStatus validateItem(Object item) {
			return Status.OK_STATUS;
		}

		@Override
		protected Comparator<IFile> getItemsComparator() {
			return Comparator.comparing(FileEntityResolver::pathOf);
		}

		@Override
		protected ItemsFilter createFilter() {
			// Filter by the workspace path shown in the list via pathMatches: the patternMatcher is built
			// with RULE_SUBSTRING_MATCH so typing any fragment of what's displayed (Foo.java, *Foo*, src,
			// src/Foo) narrows to it. Built once per filter — FISD makes a fresh filter per keystroke — and
			// reused for every item, rather than per item.
			return new ItemsFilter(new SearchPattern(
					SearchPattern.DEFAULT_MATCH_RULES | SearchPattern.RULE_SUBSTRING_MATCH)) {
				// FilteredItemsSelectionDialog skips filtering entirely (FilterJob guards filterContent()
				// with getPattern().length() != 0) when the pattern is empty — so an empty box, on open or
				// when cleared, shows nothing. Our list is small and already narrowed, so we want empty to
				// show every match instead: capture the empty state and present a non-empty pattern so the
				// filter runs, then match all. matchItem(...) below still matches the real text via
				// patternMatcher, so typed filtering is unaffected.
				private final boolean matchAll = super.getPattern().isEmpty();

				@Override
				public String getPattern() {
					return matchAll ? " " : super.getPattern();
				}

				@Override
				public boolean matchItem(Object item) {
					return matchAll || pathMatches(patternMatcher, pathOf((IFile) item));
				}

				@Override
				public boolean isConsistentItem(Object item) {
					return true;
				}
			};
		}

		@Override
		protected void fillContentProvider(AbstractContentProvider contentProvider, ItemsFilter itemsFilter,
				IProgressMonitor progressMonitor) {
			for (IFile match : matches) {
				contentProvider.add(match, itemsFilter);
			}
			if (progressMonitor != null) {
				progressMonitor.done();
			}
		}
	}

	/**
	 * List-row label provider for {@link FileSelectionDialog}: the platform's {@link WorkbenchLabelProvider}
	 * for the per-type file icon, with the styled text re-rendered as the file name followed by its greyed
	 * containing-folder path (the base provider's {@code getStyledText} returns only the name). Produces the
	 * "{@code Foo.java} - {@code /Project/src}" form of the Open Resource dialog; the greyed path is still in
	 * the text, so the dialog's substring filter is unaffected.
	 */
	static final class StyledFileLabelProvider extends WorkbenchLabelProvider {

		@Override
		public StyledString getStyledText(Object element) {
			if (!(element instanceof IFile file)) {
				return super.getStyledText(element);
			}
			StyledString styled = new StyledString(file.getName());
			String parent = file.getFullPath().removeLastSegments(1).toString();
			if (!parent.isEmpty()) {
				styled.append(" - " + parent, StyledString.QUALIFIER_STYLER);
			}
			return styled;
		}
	}

	/**
	 * Details-panel label provider for {@link FileSelectionDialog}: the file's per-type icon (from a
	 * {@link WorkbenchLabelProvider}) and its full filesystem location, which disambiguates same-named
	 * files beyond the workspace path shown in the list rows.
	 */
	static final class FileLocationLabelProvider extends LabelProvider {

		private final WorkbenchLabelProvider icons = new WorkbenchLabelProvider();

		@Override
		public Image getImage(Object element) {
			return icons.getImage(element);
		}

		@Override
		public String getText(Object element) {
			if (element instanceof IFile file && file.getLocation() != null) {
				return file.getLocation().toOSString();
			}
			return super.getText(element);
		}

		@Override
		public void dispose() {
			icons.dispose();
			super.dispose();
		}
	}
}
