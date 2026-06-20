package com.anthropic.claudecode.eclipse.resolvers;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

import org.eclipse.core.runtime.NullProgressMonitor;
import org.eclipse.jdt.core.IField;
import org.eclipse.jdt.core.IJavaElement;
import org.eclipse.jdt.core.IMethod;
import org.eclipse.jdt.core.IType;
import org.eclipse.jdt.core.JavaModelException;
import org.eclipse.jdt.core.search.IJavaSearchConstants;
import org.eclipse.jdt.core.search.SearchEngine;
import org.eclipse.jdt.core.search.SearchPattern;
import org.eclipse.jdt.core.search.TypeNameMatch;
import org.eclipse.jdt.core.search.TypeNameMatchRequestor;
import org.eclipse.jdt.ui.JavaElementLabelProvider;
import org.eclipse.jdt.ui.JavaElementLabels;
import org.eclipse.jdt.ui.JavaUI;
import org.eclipse.jface.viewers.StyledString;
import org.eclipse.jface.window.Window;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.ui.IEditorPart;
import org.eclipse.ui.IWorkbenchPage;
import org.eclipse.ui.PartInitException;

import com.anthropic.claudecode.eclipse.Activator;
import com.anthropic.claudecode.eclipse.editor.UiHelper;

/**
 * Recognizes a Java type or member reference (e.g. {@code java.util.List}, {@code com.foo.Bar},
 * {@code com.foo.Bar : 21}, {@code Foo#bar}, {@code Bar.baz(int)}) inside text taken from a Claude Code
 * answer and opens it in a Java editor.
 *
 * <p><b>Optional JDT.</b> Every direct JDT API reference lives in this class, so it must only ever be
 * loaded when JDT is installed. {@link EntitiesRegistry} enforces that by checking for the JDT bundles
 * before instantiating it; see the registry for the guard. Do not reference this class from code that
 * runs without that guard.
 *
 * <p>The search is performed eagerly in {@link #resolve} (which runs off the UI thread inside
 * {@code OpenEntityHandler}'s background job, like {@link FileEntityResolver#browseWorkspaceFiles}), so
 * a token that merely <em>looks</em> like a Java name but resolves to nothing yields no match. A single
 * hit opens directly; several hits open a selection dialog.
 *
 * <p>A type reference that carried a {@code : line} suffix is revealed at that line; a qualified name
 * whose last segment is not itself a type is also tried as a field/method of the preceding type (so
 * {@code Outer.member} resolves), and an empty {@code ()} that matches no zero-arg method falls back to
 * any arity.
 */
public class JavaIdentifierEntityResolver implements IEntityResolver {
	// NOTE: The implementation of this class is significantly inspired by the OpenFromClipboardAction.

	/** The kind of Java reference a token was recognized as. */
	enum Kind {
		/** A type name, simple or qualified. */
		TYPE,
		/** A {@code Type#member} reference with no argument list (a field, or a no-arg member). */
		MEMBER,
		/** A method reference carrying an argument list ({@code Type#m(..)} or {@code Type.m(..)}). */
		METHOD
	}

	/**
	 * A recognized Java reference, decomposed for the search.
	 *
	 * @param kind        what the token was recognized as
	 * @param packageName dotted package of the type, or {@code null} for an unqualified name
	 * @param typeName    simple name of the type (the last segment of the type part)
	 * @param memberName  member (field/method) name for {@link Kind#MEMBER}/{@link Kind#METHOD}, else {@code null}
	 * @param paramCount  method arity for {@link Kind#METHOD} ({@code 0} for {@code ()} or {@code (...)}), else {@code -1}
	 * @param lineNumber  1-based line to navigate to for a {@link Kind#TYPE} that carried a {@code : line}
	 *                    suffix, or {@code 0} when none
	 */
	record JavaReference(Kind kind, String packageName, String typeName, String memberName, int paramCount,
			int lineNumber) {}

	@Override
	public String getName() {
		return "Java Identifier";
	}

	@Override
	public IResolvedEntity resolve(String text, boolean allowStripEdges) {
		JavaReference ref = JavaIdentifierMatcher.classify(text, allowStripEdges);
		if (ref == null) {
			return null; // not a Java reference — skip the (otherwise pointless) search entirely
		}
		List<IJavaElement> matches = new ArrayList<>();
		findMatches(ref, matches);
		if (matches.isEmpty()) {
			return null;
		}
		int line = ref.lineNumber();
		if (matches.size() == 1) {
			IJavaElement only = matches.get(0);
			return () -> open(only, line);
		}
		return () -> openChooser(matches, line);
	}

	/**
	 * Looks up the element(s) {@code ref} names in the workspace and its referenced libraries, appending
	 * each hit to {@code matches}. The 1-based line to reveal after opening is carried by
	 * {@code ref.lineNumber()} ({@code 0} for none — only a {@code Type : line} reference carries one, so
	 * it applies to the whole resolution). Runs off the UI thread. Package-private so tests can override it
	 * without a live Java model.
	 */
	void findMatches(JavaReference ref, List<IJavaElement> matches) {
		List<IType> types = searchTypes(ref.packageName(), ref.typeName());
		for (IType type : types) {
			if (ref.kind() == Kind.TYPE) {
				matches.add(type);
			} else {
				collectMembersNamed(type, ref.memberName(), ref.paramCount(), ref.kind() == Kind.MEMBER, matches);
			}
		}
		// A dotted name like "Outer.member" or "com.foo.Bar.CONSTANT" may instead be a member of the type
		// formed by the preceding segments. Only for qualified names; a bare simple name stays a type-only
		// match. Gated on no type having resolved: when the name is a real type that type is the answer, so
		// the second full workspace search is wasted (this halves the searches for the common qualified-type
		// case). The fallback only ever contributes when the last segment is not a type — and then the type
		// search above returns nothing — so this preserves it. Mirrors the empty-arity guard below.
		if (ref.kind() == Kind.TYPE && ref.packageName() != null && matches.isEmpty()) {
			int lastDot = ref.packageName().lastIndexOf('.');
			String memberPackage = lastDot < 0 ? null : ref.packageName().substring(0, lastDot);
			String memberType = lastDot < 0 ? ref.packageName() : ref.packageName().substring(lastDot + 1);
			for (IType type : searchTypes(memberPackage, memberType)) {
				collectMembersNamed(type, ref.typeName(), -1, true, matches);
			}
		}
		// An empty "()" with no exact-arity hit: retry any arity.
		if (ref.kind() == Kind.METHOD && ref.paramCount() == 0 && matches.isEmpty()) {
			for (IType type : types) {
				collectMembersNamed(type, ref.memberName(), -1, false, matches);
			}
		}
	}

	/** Exact, case-sensitive workspace search for a type by (optional) package and simple name. */
	private static List<IType> searchTypes(String packageName, String typeName) {
		List<IType> result = new ArrayList<>();
		TypeNameMatchRequestor requestor = new TypeNameMatchRequestor() {
			@Override
			public void acceptTypeNameMatch(TypeNameMatch match) {
				result.add(match.getType());
			}
		};
		try {
			new SearchEngine().searchAllTypeNames(
					packageName == null ? null : packageName.toCharArray(),
					SearchPattern.R_EXACT_MATCH | SearchPattern.R_CASE_SENSITIVE,
					typeName.toCharArray(),
					SearchPattern.R_EXACT_MATCH | SearchPattern.R_CASE_SENSITIVE,
					IJavaSearchConstants.TYPE,
					SearchEngine.createWorkspaceScope(),
					requestor,
					IJavaSearchConstants.WAIT_UNTIL_READY_TO_SEARCH,
					new NullProgressMonitor());
		} catch (JavaModelException e) {
			// Indexing/model error — return whatever was collected (likely empty) → treated as no match.
		}
		return result;
	}

	/**
	 * Collects {@code type}'s methods named {@code memberName} — constrained to {@code paramCount}
	 * parameters when it is {@code >= 0}, any arity otherwise — and, when {@code includeField} is set, a
	 * field of that name.
	 */
	private void collectMembersNamed(IType type, String memberName, int paramCount, boolean includeField,
			List<IJavaElement> out) {
		try {
			for (IMethod method : type.getMethods()) {
				if (method.getElementName().equals(memberName)
						&& (paramCount < 0 || method.getNumberOfParameters() == paramCount)) {
					out.add(method);
				}
			}
			if (includeField) { // a "#name" / dotted name without an argument list may also be a field
				IField field = type.getField(memberName);
				if (field.exists()) {
					out.add(field);
				}
			}
		} catch (JavaModelException e) {
			// Type has no resolvable source/classfile — skip its members.
		}
	}

	/**
	 * Opens a single matched element in a Java editor and reveals it, navigating to 1-based {@code line}
	 * when positive. Hops to the SWT thread (the resolve callback runs off it). Package-private so tests
	 * can observe it without a workbench.
	 */
	void open(IJavaElement element, int line) {
		UiHelper.asyncExec(() -> doOpen(element, line));
	}

	private void doOpen(IJavaElement element, int line) {
		try {
			IEditorPart editor = JavaUI.openInEditor(element);
			if (line > 0) {
				EntityResolverHelpers.revealLine(editor, line);
			} else {
				JavaUI.revealInEditor(editor, element);
			}
		} catch (PartInitException | JavaModelException e) {
			Activator.logError("Failed to open Java element: " + element.getElementName(), e);
		}
	}

	/**
	 * Pops a selection dialog listing every matched element so the user picks which to open — the Java
	 * analog of {@link PythonIdentifierEntityResolver#openChooser}. The shared 1-based {@code line} (if any)
	 * is applied to whichever elements are chosen. Package-private for tests.
	 */
	void openChooser(List<IJavaElement> matches, int line) {
		UiHelper.asyncExec(() -> {
			IWorkbenchPage page = UiHelper.getActivePage();
			if (page == null) {
				return;
			}
			Shell shell = page.getWorkbenchWindow().getShell();
			JavaElementSelectionDialog dialog = new JavaElementSelectionDialog(shell, matches);
			if (dialog.open() != Window.OK) {
				return;
			}
			for (Object selected : dialog.getResult()) {
				doOpen((IJavaElement) selected, line);
			}
		});
	}

	/**
	 * The fully qualified, human-readable label of {@code element} — the declaring type/package, the simple
	 * name, and (for methods) the parameter types, e.g. {@code com.foo.Bar.baz(int)}. This is both what
	 * {@link #fqnMatches} filters against and what the chooser sorts and de-duplicates by, so the displayed
	 * label and the narrowing logic stay in sync. Touches JDT.
	 */
	static String fqnOf(IJavaElement element) {
		return JavaElementLabels.getTextLabel(element, JavaElementLabels.ALL_FULLY_QUALIFIED | JavaElementLabels.M_PARAMETER_TYPES);
	}

	/**
	 * Whether a matched element's {@link #fqnOf fully qualified name} matches {@code matcher}. When
	 * {@code matcher} is built with {@code DEFAULT_MATCH_RULES | RULE_SUBSTRING_MATCH} (as
	 * {@link JavaElementSelectionDialog}'s filter is) this is case-insensitive substring matching with
	 * {@code *}/{@code ?} wildcards, so typing any fragment of what the list shows — {@code List},
	 * {@code *List*}, {@code Map.put} — narrows to it. Pure (no JDT) so it is unit-testable; the JDT-touching
	 * {@link #fqnOf} is the seam.
	 */
	static boolean fqnMatches(org.eclipse.ui.dialogs.SearchPattern matcher, String fqn) {
		return matcher.matches(fqn);
	}

	/**
	 * Selection dialog for several matched Java elements, the Java analog of
	 * {@link PythonIdentifierEntityResolver.PythonElementSelectionDialog}. The list rows use a
	 * {@link ColoredListLabelProvider} — JDT's own {@link JavaElementLabelProvider} for the per-type icon,
	 * with the styled label re-rendered through {@link JavaElementLabels#COLORIZE} so the qualifier and root
	 * path are greyed like JDT's Open Type dialog and the Python chooser (the plain provider leaves them in
	 * the default colour). The bottom details panel uses a fuller plain provider.
	 *
	 * <p>The shared {@link EntitySelectionDialog} base provides the FISD plumbing (the empty-pattern
	 * workaround, the suppressed Help button, dialog settings, content); this subclass filters and sorts by
	 * the {@link #fqnOf fully qualified name} (see {@link #filterText} and {@link #fqnMatches}).
	 */
	static final class JavaElementSelectionDialog extends EntitySelectionDialog<IJavaElement> {

		JavaElementSelectionDialog(Shell shell, List<IJavaElement> matches) {
			super(shell, "com.anthropic.claudecode.eclipse.resolvers.JavaElementSelectionDialog",
					"Open Java Element",
					"Multiple Java elements match. Select one or more to open.\n"
							+ "Filter elements by name prefix or pattern (*, ?, or camel case):",
					matches, new ColoredListLabelProvider(), new JavaElementLabelProvider(
							JavaElementLabelProvider.SHOW_QUALIFIED
							| JavaElementLabelProvider.SHOW_ROOT
							| JavaElementLabelProvider.SHOW_PARAMETERS
							| JavaElementLabelProvider.SHOW_RETURN_TYPE));
		}

		@Override
		protected String filterText(IJavaElement item) {
			return fqnOf(item);
		}
	}

	/**
	 * List-row label provider for {@link JavaElementSelectionDialog}: JDT's {@link JavaElementLabelProvider}
	 * for the per-type icon, but with the styled text re-rendered through {@link JavaElementLabels#COLORIZE}
	 * so the post-qualifier and root path are greyed (the {@code SHOW_*} int flags the base provider takes
	 * don't carry {@code COLORIZE}, so its {@code getStyledText} leaves them the default colour). Produces the
	 * "{@code Name} - {@code package.Declaring} - {@code project/src}" form of JDT's Open Type dialog and the
	 * Python chooser. The greyed parts are still in the text, so the dialog's substring filter is unaffected.
	 */
	static final class ColoredListLabelProvider extends JavaElementLabelProvider {

		/** Name, then greyed post-qualifier and appended root path; {@code COLORIZE} is what greys them. */
		private static final long FLAGS = JavaElementLabels.ALL_POST_QUALIFIED
				| JavaElementLabels.M_PARAMETER_TYPES | JavaElementLabels.APPEND_ROOT_PATH
				| JavaElementLabels.COLORIZE;

		ColoredListLabelProvider() {
			super(SHOW_OVERLAY_ICONS | SHOW_SMALL_ICONS); // drives getImage; getStyledText is overridden below
		}

		@Override
		public StyledString getStyledText(Object element) {
			return JavaElementLabels.getStyledTextLabel(element, FLAGS);
		}
	}

	/**
	 * Pure, JDT-free recognizer that classifies a token taken from a Claude Code answer as a reference to
	 * a Java type or member.
	 *
	 * <p>It only decides <em>what the text looks like</em> and decomposes it; the actual workspace lookup
	 * and editor open live in {@link JavaIdentifierEntityResolver}, which is the only class that touches
	 * JDT. Keeping recognition here makes it trivially unit-testable and free of any JDT dependency.
	 *
	 * <p>Recognized shapes (most specific first):
	 * <pre>
	 *   com.foo.Bar#baz(int, String)  -&gt; METHOD  type=com.foo.Bar member=baz params=2   (javadoc + args)
	 *   com.foo.Bar#baz               -&gt; MEMBER  type=com.foo.Bar member=baz            (javadoc ref)
	 *   com.foo.Bar.baz(x)            -&gt; METHOD  type=com.foo.Bar member=baz params=1   (dotted call)
	 *   com.foo.Bar : 21              -&gt; TYPE    package=com.foo   type=Bar line=21     (type + line)
	 *   java.util.List                -&gt; TYPE    package=java.util type=List
	 *   List                          -&gt; TYPE    package=null      type=List
	 * </pre>
	 *
	 * <p>When {@code allowStripEdges} is {@code true} the token may carry wrapping junk (quotes, brackets,
	 * leading {@code @}, trailing sentence punctuation), which is trimmed before matching; closing brackets
	 * are trimmed only when unbalanced, so {@code Bar.baz(x)} keeps its {@code )} while a wrapping
	 * {@code (Bar)} is unwrapped. When {@code false} the text must be <em>exactly</em> the reference.
	 */
	static final class JavaIdentifierMatcher {

		/** Matches a simple name e.g. {@code List}. */
		private static final String SIMPLE_NAME = "\\p{javaJavaIdentifierStart}\\p{javaJavaIdentifierPart}*";
		/** Matches a qualified name e.g. {@code java.util.List}, or a simple name e.g. {@code List}. */
		private static final String QUALIFIED_NAME = "(?:" + SIMPLE_NAME + "\\.)*" + SIMPLE_NAME;
		/** Matches whitespace characters. */
		private static final String WS = "\\s*";

		/** Matches a javadoc method reference with an argument list e.g. {@code com.foo.Bar#baz(int, String)}. */
		private static final Pattern METHOD_JAVADOC = Pattern.compile(QUALIFIED_NAME + "#" + SIMPLE_NAME + "\\s*\\(.*\\)");
		/** Matches a javadoc member reference e.g. {@code com.foo.Bar#baz}, {@code Worker#run}. */
		private static final Pattern MEMBER = Pattern.compile(QUALIFIED_NAME + "#" + SIMPLE_NAME);
		/** Matches a dotted method call e.g. {@code com.foo.Bar.baz(x)}, {@code Worker.run()}. */
		private static final Pattern METHOD = Pattern.compile(QUALIFIED_NAME + "\\s*\\(.*\\)");
		/** Matches a qualified or simple type name e.g. {@code java.util.List}, {@code List}. */
		private static final Pattern TYPE = Pattern.compile(QUALIFIED_NAME);
		/** Matches a qualified name followed by a line number e.g. {@code com.foo.Bar : 21}, {@code Foo:5}. */
		private static final Pattern TYPE_LINE = Pattern.compile(QUALIFIED_NAME + WS + ":" + WS + "\\d+");

		/** Wrapping characters trimmed from the leading edge — none can start a Java reference. */
		private static final String LEADING_JUNK = " \t\r\n \u00A0\"'`*@.([{<";
		/** Plain trailing characters always trimmed; closing brackets are handled separately (balanced-aware). */
		private static final String TRAILING_PLAIN = " \t\r\n \u00A0\"'`*.,;:!?";
		/** Closing brackets paired by index with their {@link #OPENERS}; trimmed only when unbalanced. */
		private static final String CLOSERS = ")]}>";
		private static final String OPENERS = "([{<";

		private JavaIdentifierMatcher() {}

		/**
		 * Classifies {@code text} as a Java type/member reference, or returns {@code null} if it is none.
		 *
		 * @param text            the candidate token
		 * @param allowStripEdges when {@code true}, trim wrapping junk before matching; when {@code false}
		 *                        the text must match exactly
		 * @return the decomposed reference, or {@code null}
		 */
		static JavaReference classify(String text, boolean allowStripEdges) {
			if (text == null || text.isBlank()) {
				return null;
			}
			String s = allowStripEdges ? IdentifierUtils.stripEdges(text, LEADING_JUNK, TRAILING_PLAIN, OPENERS, CLOSERS) : text;
			if (s.isEmpty()) {
				return null;
			}
			if (METHOD_JAVADOC.matcher(s).matches()) {
				int hash = s.indexOf('#');
				String afterHash = s.substring(hash + 1);
				String member = afterHash.substring(0, afterHash.indexOf('(')).trim();
				return build(Kind.METHOD, s.substring(0, hash), member, IdentifierUtils.countParams(argsOf(s)), 0);
			}
			if (MEMBER.matcher(s).matches()) {
				int hash = s.indexOf('#');
				return build(Kind.MEMBER, s.substring(0, hash), s.substring(hash + 1), -1, 0);
			}
			if (METHOD.matcher(s).matches()) {
				String qualified = s.substring(0, s.indexOf('(')).trim();
				int lastDot = qualified.lastIndexOf('.');
				if (lastDot < 0) {
					return null; // a bare "method(...)" has no type to resolve against
				}
				return build(Kind.METHOD, qualified.substring(0, lastDot), qualified.substring(lastDot + 1),
						IdentifierUtils.countParams(argsOf(s)), 0);
			}
			if (TYPE.matcher(s).matches()) {
				return build(Kind.TYPE, s, null, -1, 0);
			}
			if (TYPE_LINE.matcher(s).matches()) {
				int colon = s.indexOf(':');
				String type = s.substring(0, colon).trim();
				try {
					return build(Kind.TYPE, type, null, -1, Integer.parseInt(s.substring(colon + 1).trim()));
				} catch (NumberFormatException e) {
					return build(Kind.TYPE, type, null, -1, 0); // overflowing line: open the type, skip navigation
				}
			}
			return null;
		}

		/** Builds a reference, splitting {@code qualifiedType}'s last segment off as the simple type name. */
		private static JavaReference build(Kind kind, String qualifiedType, String member, int paramCount,
				int lineNumber) {
			int lastDot = qualifiedType.lastIndexOf('.');
			String pkg = lastDot < 0 ? null : qualifiedType.substring(0, lastDot);
			String simple = lastDot < 0 ? qualifiedType : qualifiedType.substring(lastDot + 1);
			return new JavaReference(kind, pkg, simple, member, paramCount, lineNumber);
		}

		/** The text between the first {@code (} and the last {@code )}, or {@code ""} if absent. */
		private static String argsOf(String s) {
			int open = s.indexOf('(');
			int close = s.lastIndexOf(')');
			return open >= 0 && close > open ? s.substring(open + 1, close) : "";
		}
	}
}
