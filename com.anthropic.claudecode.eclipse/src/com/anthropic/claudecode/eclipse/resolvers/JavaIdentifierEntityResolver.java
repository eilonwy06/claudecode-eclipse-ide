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
import org.eclipse.jdt.ui.JavaUI;
import org.eclipse.jface.window.Window;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.ui.IEditorPart;
import org.eclipse.ui.IWorkbenchPage;
import org.eclipse.ui.PartInitException;
import org.eclipse.ui.dialogs.ElementListSelectionDialog;

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
		return "Java Indentifier";
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
		// formed by the preceding segments. Only for qualified names; a bare simple name stays a type-only match.
		if (ref.kind() == Kind.TYPE && ref.packageName() != null) {
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
	 * analog of {@link FileEntityResolver#openResourceDialog}. The shared 1-based {@code line} (if any)
	 * is applied to whichever elements are chosen. Package-private for tests.
	 */
	void openChooser(List<IJavaElement> matches, int line) {
		UiHelper.asyncExec(() -> {
			IWorkbenchPage page = UiHelper.getActivePage();
			if (page == null) {
				return;
			}
			Shell shell = page.getWorkbenchWindow().getShell();
			ElementListSelectionDialog dialog = new ElementListSelectionDialog(shell,
					new JavaElementLabelProvider(
							JavaElementLabelProvider.SHOW_DEFAULT      // overlay icons + parameter types
							| JavaElementLabelProvider.SHOW_QUALIFIED  // fully-qualified declaring type prefix
							| JavaElementLabelProvider.SHOW_ROOT));    // " - <project>/<src folder>" suffix
			dialog.setTitle("Open Java Element");
			dialog.setMessage("Several Java elements match. Select one or more to open:");
			dialog.setMultipleSelection(true);
			dialog.setElements(matches.toArray());
			if (dialog.open() != Window.OK) {
				return;
			}
			for (Object selected : dialog.getResult()) {
				doOpen((IJavaElement) selected, line);
			}
		});
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
			String s = allowStripEdges ? stripEdges(text) : text;
			if (s.isEmpty()) {
				return null;
			}
			if (METHOD_JAVADOC.matcher(s).matches()) {
				int hash = s.indexOf('#');
				String afterHash = s.substring(hash + 1);
				String member = afterHash.substring(0, afterHash.indexOf('(')).trim();
				return build(Kind.METHOD, s.substring(0, hash), member, countParams(argsOf(s)), 0);
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
						countParams(argsOf(s)), 0);
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

		/**
		 * Number of top-level parameters in an argument list (the text inside the outer parentheses):
		 * {@code 0} when empty or the bare ellipsis placeholder {@code ...} (so {@code method(...)} resolves
		 * exactly like {@code method()}), otherwise one more than the count of commas not nested inside
		 * {@code () <> []} — so generics ({@code Map<K,V>}) and arrays don't inflate the arity.
		 */
		private static int countParams(String args) {
			String inner = args.trim();
			if (inner.isEmpty() || inner.equals("...")) {
				return 0;
			}
			int depth = 0;
			int count = 1;
			for (int i = 0; i < inner.length(); i++) {
				switch (inner.charAt(i)) {
					case '(', '<', '[' -> depth++;
					case ')', '>', ']' -> depth--;
					case ',' -> {
						if (depth == 0) {
							count++;
						}
					}
					default -> { /* not a delimiter */ }
				}
			}
			return count;
		}

		/**
		 * Trims wrapping junk: leading characters in {@link #LEADING_JUNK}, then trailing characters in
		 * {@link #TRAILING_PLAIN} plus closing brackets that have no matching opener in the remaining span
		 * (so a balanced {@code (x)} survives while a wrapping {@code )} is removed).
		 */
		private static String stripEdges(String s) {
			int start = 0;
			int end = s.length();
			while (start < end && LEADING_JUNK.indexOf(s.charAt(start)) >= 0) {
				start++;
			}
			while (end > start) {
				char c = s.charAt(end - 1);
				if (TRAILING_PLAIN.indexOf(c) >= 0) {
					end--;
					continue;
				}
				int closerIdx = CLOSERS.indexOf(c);
				if (closerIdx >= 0 && count(s, OPENERS.charAt(closerIdx), start, end) < count(s, c, start, end)) {
					end--;
					continue;
				}
				break;
			}
			return s.substring(start, end);
		}

		/** Number of occurrences of {@code c} within {@code s[from, to)}. */
		private static int count(String s, char c, int from, int to) {
			int n = 0;
			for (int i = from; i < to; i++) {
				if (s.charAt(i) == c) {
					n++;
				}
			}
			return n;
		}
	}
}
