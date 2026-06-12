package com.anthropic.claudecode.eclipse.resolvers;

import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.eclipse.core.filesystem.EFS;
import org.eclipse.core.filesystem.IFileStore;
import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.resources.IWorkspaceRoot;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.OperationCanceledException;
import org.eclipse.jface.window.Window;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.ui.IEditorPart;
import org.eclipse.ui.IWorkbenchPage;
import org.eclipse.ui.ide.IDE;
import org.eclipse.ui.internal.ide.dialogs.OpenResourceDialog;

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
 * whose path ends with the token — a single hit opens it, several hits open the resource picker.
 */
@SuppressWarnings("restriction") // org.eclipse.ui.internal.ide.dialogs.OpenResourceDialog (see openResourceDialog)
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

		// Normalize once here so every downstream path (absolute lookup, workspace search, resource
		// picker seed) sees a clean, portable path. An OS-invalid token normalizes to "" and misses.
		text = normalizeSep(text);
		if (text.isEmpty()) {
			return null;
		}

		if (isAbsolute(text) && fileExists(text)) {
			final String filePath = text;
			final int line = lineNumber;
			return () -> locate(filePath, line);
		}

		List<String> matches = browseWorkspaceFiles(text, 2);
		if (matches.isEmpty()) {
			return null;
		}
		if (matches.size() == 1) {
			final String filePath = matches.get(0);
			final int line = lineNumber;
			return () -> locate(filePath, line);
		}
		final String pattern = text;
		final int line = lineNumber;
		return () -> openResourceDialog(pattern, line);
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
	 * Whether {@code token} — after trailing line-suffix and edge-junk trimming — is an existing
	 * absolute regular file. Used by the terminal click handler to recover a drive path that
	 * contains spaces (e.g. {@code C:\Users\Windows 10\...}), where it must test candidate spans
	 * directly rather than via the overridable {@link #fileExists} instance hook. Never throws.
	 * Public so the click handler in the {@code ...ui} package can call it. */
	public static boolean existingAbsoluteFile(String token) {
		if (token == null) {
			return false;
		}
		String t = stripEdges(token);
		Matcher m = LINE_SUFFIX.matcher(t);
		if (m.find()) {
			t = t.substring(0, m.start());
		}
		if (t.isEmpty()) {
			return false;
		}
		try {
			Path p = Path.of(t);
			return p.isAbsolute() && Files.isRegularFile(p);
		} catch (InvalidPathException e) {
			return false;
		}
	}

	/**
	 * Whether {@code token} — after the same trailing line-suffix and edge trimming as
	 * {@link #existingAbsoluteFile} — names an existing regular file when resolved against the
	 * workspace root or an open project's location (or that location's parent). Lets the terminal
	 * click handler recover workspace-relative paths containing spaces, e.g.
	 * {@code Sample\src\aaa bbb\file.java}. Public for the {@code ...ui} package; never throws.
	 */
	public static boolean existingWorkspaceRelativeFile(String token) {
		if (token == null) {
			return false;
		}
		String t = stripEdges(token);
		Matcher m = LINE_SUFFIX.matcher(t);
		if (m.find()) {
			t = t.substring(0, m.start());
		}
		if (t.isEmpty()) {
			return false;
		}
		try {
			Path rel = Path.of(t);
			if (rel.isAbsolute()) {
				return false;
			}
			for (Path base : relativeLookupBases()) {
				if (Files.isRegularFile(base.resolve(rel))) {
					return true;
				}
			}
		} catch (Exception e) {
			// Invalid path characters, workspace unavailable, … — not a resolvable path.
		}
		return false;
	}

	/** Workspace root + open project locations (and their parents) as filesystem lookup bases. */
	private static List<Path> relativeLookupBases() {
		LinkedHashSet<Path> bases = new LinkedHashSet<>();
		try {
			IWorkspaceRoot root = ResourcesPlugin.getWorkspace().getRoot();
			if (root.getLocation() != null) {
				bases.add(Path.of(root.getLocation().toOSString()));
			}
			for (IProject project : root.getProjects()) {
				if (project.isOpen() && project.getLocation() != null) {
					Path loc = Path.of(project.getLocation().toOSString());
					bases.add(loc);
					if (loc.getParent() != null) {
						bases.add(loc.getParent());
					}
				}
			}
		} catch (Exception e) {
			// Workspace not running — no bases to resolve against.
		}
		return new ArrayList<>(bases);
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
	 * Absolute paths of workspace files whose (path-normalized) location ends with
	 * {@code pathSuffix} on a segment boundary, mimicking Eclipse's "Open Resource" search.
	 * {@code pathSuffix} is expected to already be path-normalized (see {@link #normalizeSep}).
	 * Has package-private scope so tests can override it without a running workspace.
	 */
	List<String> browseWorkspaceFiles(String pathSuffix, int maxFilesCount) {
		List<String> out = new ArrayList<>();
		String needle = pathSuffix; // already path-normalized by resolve()
		try {
			ResourcesPlugin.getWorkspace().getRoot().accept(res -> {
				if (res.getType() == IResource.FILE && res.getLocation() != null
						&& matchesSuffix(normalizeSep(res.getLocation().toOSString()), needle)) {
					out.add(res.getLocation().toOSString());
					if (out.size() >= maxFilesCount) {
						throw new OperationCanceledException();
					}
				}
				return true;
			});
		} catch (OperationCanceledException ignored) {
			// Early-out: two matches is enough to know we need the resource picker.
		} catch (CoreException e) {
			// Return whatever was collected before the failure.
		}
		return out;
	}

	/**
	 * Opens {@code filePath} in an editor and reveals {@code lineNumber} (when positive). A file that
	 * maps to a workspace resource is opened as that {@link IFile} — so it shares the project editor's
	 * markers/builders/identity rather than spawning a second external editor — otherwise it is opened
	 * as an external file store. Runs on the SWT thread; the terminal callback may be off it.
	 */
	void locate(String filePath, int lineNumber) {
		UiHelper.asyncExec(() -> {
			try {
				IWorkbenchPage page = UiHelper.getActivePage();
				if (page == null) {
					return;
				}
				IEditorPart editor;
				// org.eclipse.core.runtime.Path is fully qualified: the file already imports java.nio.file.Path.
				// Path.fromOSString (vs IPath.fromOSString) keeps us compatible with older targets (e.g. 4.26).
				IFile file = ResourcesPlugin.getWorkspace().getRoot()
						.getFileForLocation(org.eclipse.core.runtime.Path.fromOSString(filePath));
				if (file != null && file.exists()) {
					editor = IDE.openEditor(page, file, true);
				} else {
					IFileStore store = EFS.getLocalFileSystem().getStore(Path.of(filePath).toUri());
					editor = IDE.openEditorOnFileStore(page, store);
				}
				EntityResolverHelpers.revealLine(editor, lineNumber);
			} catch (Exception e) {
				Activator.logError("Failed to open file: " + filePath, e);
			}
		});
	}

	/**
	 * Pops Eclipse's standard "Open Resource" picker seeded with {@code filePattern} so the user can
	 * disambiguate between several workspace files, then reveals {@code lineNumber} (when positive) in
	 * each opened editor. {@link OpenResourceDialog} is used intentionally (over a
	 * {@link FilteredResourcesSelectionDialog}) for the full Ctrl+Shift+R experience ("Show In",
	 * "Open With" buttons, menus etc.)
	 */
	void openResourceDialog(String filePattern, int lineNumber) {
		UiHelper.asyncExec(() -> {
			try {
				IWorkbenchPage page = UiHelper.getActivePage();
				if (page == null) {
					return;
				}
				Shell shell = page.getWorkbenchWindow().getShell();
				OpenResourceDialog dialog = new OpenResourceDialog(shell,
						ResourcesPlugin.getWorkspace().getRoot(), IResource.FILE);
				dialog.setInitialPattern(filePattern);
				if (dialog.open() != Window.OK) {
					return;
				}
				for (Object result : dialog.getResult()) {
					if (result instanceof IFile file) {
						EntityResolverHelpers.revealLine(IDE.openEditor(page, file, true), lineNumber);
					}
				}
			} catch (Exception e) {
				Activator.logError("Failed to open resource picker for: " + filePattern, e);
			}
		});
	}
}
