package com.anthropic.claudecode.eclipse.resolvers;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

import org.eclipse.cdt.core.CCorePlugin;
import org.eclipse.cdt.core.dom.ast.IFunction;
import org.eclipse.cdt.core.index.IIndex;
import org.eclipse.cdt.core.index.IIndexBinding;
import org.eclipse.cdt.core.index.IIndexMacro;
import org.eclipse.cdt.core.index.IIndexManager;
import org.eclipse.cdt.core.index.IndexFilter;
import org.eclipse.cdt.core.model.CModelException;
import org.eclipse.cdt.core.model.CoreModel;
import org.eclipse.cdt.core.model.ICElement;
import org.eclipse.cdt.core.model.ICProject;
import org.eclipse.cdt.internal.core.model.ext.ICElementHandle;
import org.eclipse.cdt.internal.ui.util.EditorUtility;
import org.eclipse.cdt.internal.ui.viewsupport.CElementImageProvider;
import org.eclipse.cdt.internal.ui.viewsupport.CElementLabels;
import org.eclipse.cdt.internal.ui.viewsupport.CUILabelProvider;
import org.eclipse.cdt.internal.ui.viewsupport.IndexUI;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.NullProgressMonitor;
import org.eclipse.jface.window.Window;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.ui.IEditorPart;
import org.eclipse.ui.IWorkbenchPage;
import org.eclipse.ui.PartInitException;
import org.eclipse.ui.dialogs.SearchPattern;

import com.anthropic.claudecode.eclipse.Activator;
import com.anthropic.claudecode.eclipse.editor.UiHelper;

/**
 * Recognizes a C/C++ identifier (e.g. {@code MyClass}, {@code ns::Foo::bar}, {@code freeFunc()},
 * {@code Foo::bar(int)}, a macro {@code MY_MACRO}) inside text taken from a Claude Code answer and opens
 * its definition via the CDT index.
 *
 * <p><b>Optional CDT.</b> Every direct CDT API reference lives in this class, so it must only ever be
 * loaded when CDT is installed. {@link EntitiesRegistry} enforces that by checking for the CDT bundles
 * before instantiating it; see the registry for the guard. Do not reference this class from code that
 * runs without that guard.
 *
 * <p>The lookup is performed eagerly in {@link #resolve} (which runs off the UI thread inside
 * {@code OpenEntityHandler}'s background job, like {@link PythonIdentifierEntityResolver#resolve}), so a
 * token that merely <em>looks</em> like a C/C++ name but is not indexed yields no match. The CDT index is
 * searched by the simple (final) name and the hits are then narrowed to the {@code ::} qualifier (so
 * {@code ClassName::method} resolves to {@code ClassName}'s member, not every same-named token) — see
 * {@link #findMatches}. A single hit opens directly; several hits open a selection dialog; no hit returns
 * {@code null} (the handler then shows the status-bar miss message).
 *
 * <p>This resolves <em>one already-typed token</em>, so it follows CDT's <em>Open Declaration</em> model
 * — an exact {@link IIndex#findBindings(char[], IndexFilter, IProgressMonitor) findBindings}, mapped to an
 * element with {@link IndexUI#findRepresentative} and opened with {@link EditorUtility} — rather than the
 * <em>Open Element</em> dialog's prefix browse. From Open Element it borrows the navigation-fragment index
 * acquisition ({@link IIndexManager#ADD_EXTENSION_FRAGMENTS_NAVIGATION}) and the {@code ALL_DECLARED}
 * filter. Identifier recognition is a self-contained regex that mirrors
 * {@link JavaIdentifierEntityResolver}'s matcher, since recognizing and trimming a token from a Claude
 * answer is language-independent.
 */
@SuppressWarnings("restriction") // org.eclipse.cdt.internal.* — index→element mapping, open, and colored labels (no public CDT equivalent)
public class CppIdentifierEntityResolver implements IEntityResolver {

	/**
	 * A recognized C/C++ reference.
	 *
	 * @param segments    the {@code ::}-split identifier, e.g. {@code [ns, Foo, bar]}; the last segment is
	 *                    the simple name the index is searched by, the whole list is the qualifier suffix
	 *                    matched against each candidate's qualified name
	 * @param paramCount  the arity from a trailing {@code (...)} ({@code 0} for {@code ()} or {@code (...)}),
	 *                    or {@code -1} when the token carried no argument list
	 * @param globalScope {@code true} when the token started with a leading {@code ::} (root-namespace
	 *                    qualifier); only bindings whose CDT qualified name has exactly the same depth as
	 *                    {@code segments} should match — CDT returns {@code ["Foo"]} for a global {@code Foo}
	 *                    and {@code ["ns","Foo"]} for {@code ns::Foo}, so depth equality is the right predicate
	 */
	record CppReference(List<String> segments, int paramCount, boolean globalScope) {}

	@Override
	public String getName() {
		return "C/C++ Identifier";
	}

	@Override
	public IResolvedEntity resolve(String text, boolean allowStripEdges) {
		CppReference ref = CppIdentifierMatcher.classify(text, allowStripEdges);
		if (ref == null) {
			return null; // not a C/C++ identifier — skip the (otherwise pointless) lookup entirely
		}
		List<ICElement> matches = new ArrayList<>();
		findMatches(ref, matches);
		if (matches.isEmpty()) {
			return null;
		}
		if (matches.size() == 1) {
			ICElement only = matches.get(0);
			return () -> open(only);
		}
		List<ICElement> all = matches;
		return () -> openChooser(all);
	}

	/**
	 * Looks up the binding(s) {@code ref} names in the CDT index across every C/C++ project (including
	 * navigation index fragments), narrows them to {@code ref}'s {@code ::} qualifier and, for the token's
	 * arity, maps each kept binding to its representative {@link ICElement} (appended to {@code matches}).
	 * Runs off the UI thread. Package-private so tests can override it without a live index.
	 *
	 * <p>The index is keyed by simple name, so it is probed by {@code ref}'s final segment and the hits are
	 * then narrowed by {@link #qualifierMatches}; this supports a partial qualifier ({@code Foo::bar}
	 * resolving {@code app::ns::Foo::bar}). When the token carried an argument list, overloads are filtered
	 * by arity, falling back to every arity if that leaves no function (C++ default arguments mean a written
	 * {@code f(int)} need not equal a 2-parameter signature). A bare name additionally searches macros.
	 */
	void findMatches(CppReference ref, List<ICElement> matches) {
		ICProject[] projects;
		try {
			projects = CoreModel.getDefault().getCModel().getCProjects();
		} catch (CModelException e) {
			return; // no C model — treated as no match
		}
		if (projects.length == 0) {
			return;
		}
		IIndex index;
		try {
			index = CCorePlugin.getIndexManager().getIndex(projects, IIndexManager.ADD_EXTENSION_FRAGMENTS_NAVIGATION);
		} catch (CoreException e) {
			return;
		}
		try {
			index.acquireReadLock();
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			return;
		}
		try {
			collect(ref, index, projects, matches);
		} catch (CoreException e) {
			// Indexing/model error — return whatever was collected (likely empty) → treated as no match.
		} finally {
			index.releaseReadLock();
		}
	}

	/** Performs the index queries under the read lock (see {@link #findMatches}). */
	private void collect(CppReference ref, IIndex index, ICProject[] projects, List<ICElement> matches)
			throws CoreException {
		IProgressMonitor monitor = new NullProgressMonitor();
		String simpleName = ref.segments().get(ref.segments().size() - 1);
		List<IIndexBinding> arityMatched = new ArrayList<>();
		List<IIndexBinding> allFunctions = new ArrayList<>();
		List<IIndexBinding> nonFunctions = new ArrayList<>();
		// A bare (one-segment, non-global) reference keeps every same-named binding: findBindings already
		// matched the exact simple name, which is the last segment of each candidate's qualified name, so
		// qualifierMatches is unconditionally true. Skip computing it then — getQualifiedName() walks the
		// binding's owner chain through the PDOM per hit, the dominant per-candidate cost for a common name
		// in a large project. Qualified/global references still take the full narrowing path below.
		boolean bareName = ref.segments().size() == 1 && !ref.globalScope();
		// filescope=false: also return bindings nested in namespaces/classes (e.g. ns::Foo, Foo::bar) — the
		// 3-arg findBindings convenience overload forces filescope=true, which only finds global-scope names.
		for (IIndexBinding binding : index.findBindings(simpleName.toCharArray(), false, IndexFilter.ALL_DECLARED, monitor)) {
			if (!bareName && !qualifierMatches(binding.getQualifiedName(), ref.segments(), ref.globalScope())) {
				continue;
			}
			if (binding instanceof IFunction function) {
				allFunctions.add(binding);
				if (ref.paramCount() < 0 || function.getParameters().length == ref.paramCount()) {
					arityMatched.add(binding);
				}
			} else {
				nonFunctions.add(binding);
			}
		}
		// An arity-constrained search that matched no function falls back to every arity (C++ default
		// arguments / overload resolution mean the written arity need not equal a declared signature).
		List<IIndexBinding> functions = (ref.paramCount() >= 0 && arityMatched.isEmpty()) ? allFunctions : arityMatched;
		for (IIndexBinding binding : nonFunctions) {
			addRepresentatives(index, projects, binding, matches);
		}
		for (IIndexBinding binding : functions) {
			addRepresentatives(index, projects, binding, matches);
		}
		if (ref.segments().size() == 1) { // a bare simple name may also be a macro
			for (IIndexMacro macro : index.findMacros(simpleName.toCharArray(), IndexFilter.ALL_DECLARED, monitor)) {
				ICElementHandle element = macroElement(index, projects, macro);
				if (element != null) {
					matches.add(element);
				}
			}
		}
	}

	/** Maps {@code binding} to its representative element(s) — its definition, else a declaration. */
	private static void addRepresentatives(IIndex index, ICProject[] projects, IIndexBinding binding,
			List<ICElement> out) {
		try {
			ICElementHandle[] representatives = IndexUI.findRepresentative(index, binding);
			if (representatives.length == 0) {
				ICElementHandle declaration = IndexUI.findAnyDeclaration(index, projects[0], binding);
				if (declaration != null) {
					out.add(declaration);
				}
				return;
			}
			for (ICElementHandle representative : representatives) {
				if (representative != null) {
					out.add(representative);
				}
			}
		} catch (CoreException e) {
			// This binding has no resolvable location — skip it.
		}
	}

	/** The element for a macro, taken from the first project that can resolve it, or {@code null}. */
	private static ICElementHandle macroElement(IIndex index, ICProject[] projects, IIndexMacro macro)
			throws CoreException {
		for (ICProject project : projects) {
			ICElementHandle element = IndexUI.getCElementForMacro(project, index, macro);
			if (element != null) {
				return element;
			}
		}
		return null;
	}

	/**
	 * Whether {@code bindingQualifiedName} ends — segment by segment — with {@code refSegments}, so
	 * {@code [ns, Foo, bar]} matches {@code [Foo, bar]} and {@code [bar]} but not {@code [Other, bar]}. A
	 * bare reference (one segment) keeps every same-named binding; a qualified reference enforces its
	 * suffix, with the segment-aligned comparison rejecting {@code [Name, x]} against a container ending in
	 * {@code ClassName}. When {@code globalScope} is {@code true} (the reference started with {@code ::}),
	 * only bindings whose qualified name has exactly the same depth as {@code refSegments} are kept — CDT
	 * returns {@code ["Foo"]} for a global symbol and {@code ["ns","Foo"]} for a namespaced one, so depth
	 * equality correctly restricts to the root namespace. Pure (no CDT) so it is unit-testable.
	 */
	static boolean qualifierMatches(String[] bindingQualifiedName, List<String> refSegments, boolean globalScope) {
		if (refSegments.size() > bindingQualifiedName.length) {
			return false;
		}
		if (globalScope && bindingQualifiedName.length != refSegments.size()) {
			return false; // root-scope qualifier: discard names nested inside any outer namespace
		}
		int offset = bindingQualifiedName.length - refSegments.size();
		for (int i = 0; i < refSegments.size(); i++) {
			if (!refSegments.get(i).equals(bindingQualifiedName[offset + i])) {
				return false;
			}
		}
		return true;
	}

	/**
	 * Opens a single matched element in a C/C++ editor and reveals it. Hops to the SWT thread (the resolve
	 * callback runs off it). Package-private so tests can observe it without a workbench.
	 */
	void open(ICElement element) {
		UiHelper.asyncExec(() -> doOpen(element));
	}

	private void doOpen(ICElement element) {
		try {
			IEditorPart editor = EditorUtility.openInEditor(element);
			EditorUtility.revealInEditor(editor, element);
		} catch (CModelException | PartInitException e) {
			Activator.logError("Failed to open C/C++ element: " + element.getElementName(), e);
		}
	}

	/**
	 * Pops a selection dialog listing every matched element so the user picks which to open — the C/C++
	 * analog of {@link JavaIdentifierEntityResolver#openChooser}. Package-private for tests.
	 */
	void openChooser(List<ICElement> matches) {
		UiHelper.asyncExec(() -> {
			IWorkbenchPage page = UiHelper.getActivePage();
			if (page == null) {
				return;
			}
			Shell shell = page.getWorkbenchWindow().getShell();
			CElementSelectionDialog dialog = new CElementSelectionDialog(shell, matches);
			if (dialog.open() != Window.OK) {
				return;
			}
			for (Object selected : dialog.getResult()) {
				doOpen((ICElement) selected);
			}
		});
	}

	/**
	 * Label flags mirroring CDT's own multiple-target chooser (the F3 <em>Open Declaration</em>
	 * disambiguator: {@code OpenActionUtil.selectCElement} / {@code OpenDeclarationsAction.DialogTargetDisambiguator},
	 * which pass exactly these to a {@link CUILabelProvider}): the fully qualified name, the parameter
	 * types and a trailing {@code " - "} source file path, e.g.
	 * {@code openhd::kbits_to_bits_per_second(int) - /test/main.cpp}. Used both for the list rows (via the
	 * {@link CUILabelProvider}) and, through {@link #fqnOf}, for the chooser's filter/sort/name, so the
	 * displayed label and the narrowing logic stay in sync.
	 */
	static final long LABEL_FLAGS = CElementLabels.ALL_FULLY_QUALIFIED
			| CElementLabels.M_PARAMETER_TYPES | CElementLabels.MF_POST_FILE_QUALIFIED;

	/**
	 * The human-readable label of {@code element} — the fully qualified name (declaring namespace/type
	 * prefix + simple name), the parameter types (for functions) and the source file path, e.g.
	 * {@code ns::Foo::bar(int) - /proj/src/foo.cpp} (see {@link #LABEL_FLAGS}). This is both what
	 * {@link #fqnMatches} filters against and what the chooser sorts and de-duplicates by, and it is the
	 * plain-text form of what the {@link CUILabelProvider} renders in the list, so the displayed label and
	 * the narrowing logic stay in sync. Touches CDT.
	 */
	static String fqnOf(ICElement element) {
		return CElementLabels.getTextLabel(element, LABEL_FLAGS);
	}

	/**
	 * Whether a matched element's {@link #fqnOf label} matches {@code matcher}. When {@code matcher} is
	 * built with {@code DEFAULT_MATCH_RULES | RULE_SUBSTRING_MATCH} (as {@link CElementSelectionDialog}'s
	 * filter is) this is case-insensitive substring matching with {@code *}/{@code ?} wildcards, so typing
	 * any fragment of what the list shows — the name ({@code Foo}, {@code *Foo*}, {@code Foo::bar}) or the
	 * trailing source file path ({@code main.cpp}) — narrows to it. Pure (no CDT) so it is unit-testable;
	 * the CDT-touching {@link #fqnOf} is the seam.
	 */
	static boolean fqnMatches(SearchPattern matcher, String fqn) {
		return matcher.matches(fqn);
	}

	/**
	 * Selection dialog for several matched C/C++ elements, the C/C++ analog of
	 * {@link JavaIdentifierEntityResolver.JavaElementSelectionDialog}. The list rows reuse CDT's own
	 * {@link CUILabelProvider} with {@link #LABEL_FLAGS} — exactly the provider and flags CDT's F3
	 * <em>Open Declaration</em> disambiguator builds in {@code OpenActionUtil.selectCElement} — so each row
	 * reads {@code ns::name(params) - /project/path/file.cpp} (CDT's per-type icon, with the qualifier and
	 * file path greyed by the {@link CElementLabels#COLORIZE} that {@code CUILabelProvider.getStyledText}
	 * applies). The bottom details panel uses the same {@link CUILabelProvider} with
	 * {@link CElementLabels#ALL_FULLY_QUALIFIED}, {@link CElementLabels#M_PARAMETER_TYPES}, and
	 * {@link CElementLabels#M_APP_RETURNTYPE} — omitting the file-path suffix. The public
	 * {@link org.eclipse.cdt.ui.CElementLabelProvider} is not used because its {@code getTextFlags()}
	 * silently drops {@code SHOW_QUALIFIED}, so qualified names would be missing from the details strip.
	 *
	 * <p>The shared {@link EntitySelectionDialog} base provides the FISD plumbing (the empty-pattern
	 * workaround, the suppressed Help button, dialog settings, content); this subclass filters and sorts by
	 * that same {@link #fqnOf label} (see {@link #filterText} and {@link #fqnMatches}).
	 */
	static final class CElementSelectionDialog extends EntitySelectionDialog<ICElement> {

		CElementSelectionDialog(Shell shell, List<ICElement> matches) {
			super(shell, "com.anthropic.claudecode.eclipse.resolvers.CElementSelectionDialog",
					"Open C/C++ Element",
					"Multiple C/C++ elements match. Select one or more to open.\n"
							+ "Filter elements by name prefix or pattern (*, ?, or camel case):",
					matches,
					new CUILabelProvider(LABEL_FLAGS,
							CElementImageProvider.OVERLAY_ICONS | CElementImageProvider.SMALL_ICONS),
					new CUILabelProvider(
							CElementLabels.ALL_FULLY_QUALIFIED | CElementLabels.M_PARAMETER_TYPES | CElementLabels.M_APP_RETURNTYPE,
							CElementImageProvider.OVERLAY_ICONS | CElementImageProvider.SMALL_ICONS));
		}

		@Override
		protected String filterText(ICElement item) {
			return fqnOf(item);
		}
	}

	/**
	 * Pure, CDT-free recognizer that classifies a token taken from a Claude Code answer as a reference to a
	 * C/C++ identifier and decomposes it. It only validates the shape; the index lookup and editor open live
	 * in the enclosing {@link CppIdentifierEntityResolver}, the only class that touches CDT.
	 *
	 * <p>Recognized shapes: a simple identifier ({@code Foo}, {@code my_func}), a {@code ::}-qualified
	 * identifier ({@code ns::Foo::bar}, with an optional leading {@code ::} for global scope), and either of
	 * those carrying a trailing call ({@code freeFunc()}, {@code Foo::bar(int)} — the argument list is kept
	 * only to count the arity). The final {@code ::} segment is the name searched.
	 *
	 * <p>When {@code allowStripEdges} is {@code true} the token may carry wrapping junk (quotes, brackets, a
	 * leading {@code &}/{@code *}, trailing sentence punctuation or a {@code *}/{@code &} suffix), trimmed
	 * before matching; closing brackets are trimmed only when unbalanced, so {@code Foo::bar(int)} keeps its
	 * {@code )} while a wrapping {@code (Foo)} is unwrapped. When {@code false} the text must be
	 * <em>exactly</em> the reference. The edge trimming mirrors
	 * {@link JavaIdentifierEntityResolver.JavaIdentifierMatcher}; there is no leading-keyword stripping, so
	 * {@code class Foo} (a space-bearing token) is rejected.
	 */
	static final class CppIdentifierMatcher {

		/** A simple C/C++ identifier: a letter/underscore start, then letters/digits/underscores. */
		private static final String SIMPLE_NAME = "[A-Za-z_][A-Za-z0-9_]*";
		/** A {@code ::}-qualified (or simple) identifier, with an optional leading {@code ::} global scope. */
		private static final Pattern QUALIFIED =
				Pattern.compile("(?:::)?" + SIMPLE_NAME + "(?:::" + SIMPLE_NAME + ")*");

		/** Wrapping characters trimmed from the leading edge — none can start a C/C++ reference. */
		private static final String LEADING_JUNK = " \t\r\n \u00A0\"'`*&.([{<";
		/** Plain trailing characters always trimmed; closing brackets are handled separately (balanced-aware). */
		private static final String TRAILING_PLAIN = " \t\r\n \u00A0\"'`*&.,;:!?";
		/** Closing brackets paired by index with their {@link #OPENERS}; trimmed only when unbalanced. */
		private static final String CLOSERS = ")]}>";
		private static final String OPENERS = "([{<";

		private CppIdentifierMatcher() {}

		/**
		 * Classifies {@code text} as a C/C++ identifier reference, or returns {@code null} if it is none.
		 *
		 * @param text            the candidate token
		 * @param allowStripEdges when {@code true}, trim wrapping junk before matching; when {@code false}
		 *                        the text must match exactly
		 * @return the decomposed reference, or {@code null}
		 */
		static CppReference classify(String text, boolean allowStripEdges) {
			if (text == null || text.isBlank()) {
				return null;
			}
			String s = allowStripEdges ? IdentifierUtils.stripEdges(text, LEADING_JUNK, TRAILING_PLAIN, OPENERS, CLOSERS) : text;
			if (s.isEmpty()) {
				return null;
			}
			int paramCount = -1;
			int open = s.indexOf('(');
			if (open > 0 && s.endsWith(")")) {
				String head = s.substring(0, open).trim();
				if (QUALIFIED.matcher(head).matches()) {
					paramCount = IdentifierUtils.countParams(s.substring(open + 1, s.length() - 1));
					s = head;
				}
			}
			if (!QUALIFIED.matcher(s).matches()) {
				return null;
			}
			boolean globalScope = s.startsWith("::");
			if (globalScope) {
				s = s.substring(2);
			}
			return new CppReference(List.of(s.split("::")), paramCount, globalScope);
		}
	}
}
