package com.anthropic.claudecode.eclipse.resolvers;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Pattern;

import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.Status;
import org.eclipse.jface.dialogs.IDialogSettings;
import org.eclipse.jface.viewers.DelegatingStyledCellLabelProvider.IStyledLabelProvider;
import org.eclipse.jface.viewers.StyledString;
import org.eclipse.jface.window.Window;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.ui.IWorkbenchPage;
import org.eclipse.ui.dialogs.FilteredItemsSelectionDialog;
import org.eclipse.ui.dialogs.SearchPattern;
import org.python.pydev.ast.codecompletion.revisited.CompletionState;
import org.python.pydev.ast.interpreter_managers.InterpreterManagersAPI;
import org.python.pydev.ast.item_pointer.ItemPointer;
import org.python.pydev.core.ICodeCompletionASTManager;
import org.python.pydev.core.IInfo;
import org.python.pydev.core.IInterpreterInfo;
import org.python.pydev.core.IInterpreterManager;
import org.python.pydev.core.IPythonNature;
import org.python.pydev.core.MisconfigurationException;
import org.python.pydev.editor.actions.PyOpenAction;
import org.python.pydev.plugin.nature.PythonNature;
import org.python.pydev.plugin.nature.SystemPythonNature;

import com.anthropic.claudecode.eclipse.Activator;
import com.anthropic.claudecode.eclipse.editor.UiHelper;
import com.python.pydev.analysis.AnalysisPlugin;
import com.python.pydev.analysis.actions.ModuleIInfoLabelProvider;
import com.python.pydev.analysis.actions.NameIInfoLabelProvider;
import com.python.pydev.analysis.additionalinfo.AbstractAdditionalDependencyInfo;
import com.python.pydev.analysis.additionalinfo.AbstractAdditionalTokensInfo;
import com.python.pydev.analysis.additionalinfo.AdditionalInfoAndIInfo;
import com.python.pydev.analysis.additionalinfo.AdditionalProjectInterpreterInfo;
import com.python.pydev.analysis.additionalinfo.AdditionalSystemInterpreterInfo;

/**
 * Recognizes a Python identifier (e.g. {@code MyClass}, {@code my_func}, {@code os.path.join},
 * {@code join(a, b)}) inside text taken from a Claude Code answer and opens its definition in a Python
 * editor via PyDev.
 *
 * <p><b>Optional PyDev.</b> Every direct PyDev API reference lives in this class, so it must only ever be
 * loaded when PyDev is installed. {@link EntitiesRegistry} enforces that by checking for the PyDev bundles
 * before instantiating it; see the registry for the guard. Do not reference this class from code that runs
 * without that guard.
 *
 * <p>The lookup is performed eagerly in {@link #resolve} (which runs off the UI thread inside
 * {@code OpenEntityHandler}'s background job, like {@link JavaIdentifierEntityResolver#resolve}), so a token
 * that merely <em>looks</em> like a Python name but is not indexed by PyDev yields no match. PyDev is
 * searched by the simple (final) name and the hits are then narrowed to the dotted qualifier (so
 * {@code ClassName.method_name} resolves to {@code ClassName}'s member, not every same-named token) — see
 * {@link #findMatches}. A single hit opens directly; several hits open a selection dialog; no hit returns
 * {@code null} (the handler then shows the status-bar miss message).
 *
 * <p>The PyDev interaction (gathering the interpreter/project additional info, resolving an {@link IInfo} to
 * an {@link ItemPointer}, and opening it with {@link PyOpenAction}) is modeled on PyDev's own
 * {@code PyGlobalsBrowser}. Identifier recognition is a self-contained regex (PyDev's
 * {@code PySelection.isIdentifier} is unsuitable here — it matches {@code \w*}, so it accepts an empty
 * string and a leading digit); it mirrors {@link JavaIdentifierEntityResolver}'s matcher, since recognizing
 * and trimming a token from a Claude answer is language-independent.
 */
public class PythonIdentifierEntityResolver implements IEntityResolver {

	/**
	 * A recognized Python reference.
	 *
	 * @param qualifiedName the whole (possibly dotted) identifier, e.g. {@code os.path.join}
	 * @param name          the final segment — the simple token PyDev is searched by, e.g. {@code join}
	 */
	record PythonReference(String qualifiedName, String name) {}

	@Override
	public String getName() {
		return "Python Identifier";
	}

	@Override
	public IResolvedEntity resolve(String text, boolean allowStripEdges) {
		PythonReference ref = PythonIdentifierMatcher.classify(text, allowStripEdges);
		if (ref == null) {
			return null; // not a Python identifier — skip the (otherwise pointless) lookup entirely
		}
		List<AdditionalInfoAndIInfo> matches = new ArrayList<>();
		findMatches(ref, matches);
		if (matches.isEmpty()) {
			return null;
		}
		if (matches.size() == 1) {
			AdditionalInfoAndIInfo only = matches.get(0);
			return () -> open(only);
		}
		List<AdditionalInfoAndIInfo> all = matches;
		return () -> openChooser(all);
	}

	/**
	 * Looks up every indexed Python token whose simple name equals {@code ref.name()} across the system
	 * interpreter info and every related project's info, then narrows the hits to those whose container
	 * is consistent with {@code ref}'s qualifier (see {@link #filterByQualifier}), appending each kept hit
	 * (paired with the info it came from, so its nature can be derived when opening) to {@code matches}.
	 * Runs off the UI thread. Package-private so tests can override it without a live PyDev model.
	 *
	 * <p>PyDev is searched by simple name only — a method {@code ClassName.method_name} is indexed as an
	 * INNER token named {@code method_name} with path {@code ClassName} — so a bare name search returns
	 * every same-named token regardless of class/module. The qualifier filter restores the precision a
	 * dotted reference asks for: when a qualifier is written it is enforced strictly, so a dotted reference
	 * whose qualifier matches no hit's container yields a miss rather than every same-named token.
	 */
	void findMatches(PythonReference ref, List<AdditionalInfoAndIInfo> matches) {
		List<AdditionalInfoAndIInfo> hits = new ArrayList<>();
		for (AbstractAdditionalTokensInfo info : gatherInfos()) {
			for (IInfo hit : info.getTokensEqualTo(ref.name(),
					AbstractAdditionalTokensInfo.TOP_LEVEL | AbstractAdditionalTokensInfo.INNER)) {
				hits.add(new AdditionalInfoAndIInfo(info, hit));
			}
		}
		matches.addAll(filterByQualifier(hits, qualifierOf(ref)));
	}

	/** The dotted qualifier preceding the simple name ({@code os.path} for {@code os.path.join}), or
	 *  {@code null} for a bare simple name (no dot). */
	static String qualifierOf(PythonReference ref) {
		int lastDot = ref.qualifiedName().lastIndexOf('.');
		return lastDot < 0 ? null : ref.qualifiedName().substring(0, lastDot);
	}

	/**
	 * Narrows simple-name {@code hits} to those whose container matches {@code qualifier} (see
	 * {@link #containerMatchesQualifier}). A {@code null} qualifier (a bare name) keeps every hit. A written
	 * qualifier is enforced strictly: when it matches no hit's container the result is empty (a miss), rather
	 * than degrading to every same-named token. This means a module-alias qualifier (e.g. {@code os.path} for
	 * a token declared in {@code posixpath}) that matches no container does not resolve — respecting the
	 * written qualifier is preferred over a flood of unrelated same-named hits.
	 */
	static List<AdditionalInfoAndIInfo> filterByQualifier(List<AdditionalInfoAndIInfo> hits, String qualifier) {
		if (qualifier == null) {
			return hits;
		}
		return hits.stream()
				.filter(h -> containerMatchesQualifier(h.info, qualifier))
				.toList();
	}

	/**
	 * Whether {@code info}'s fully-dotted container ends (segment-aligned) with {@code qualifier}. The
	 * container is the declaring module joined with the in-module path, so a method {@code method_name} in
	 * class {@code ClassName} of module {@code app.models} has container {@code app.models.ClassName} and
	 * matches qualifier {@code ClassName} (via {@code .ClassName} suffix) or {@code models.ClassName}, while a
	 * same-named method in {@code Other} does not. The leading {@code .} on the {@code endsWith} test keeps
	 * the match segment-aligned, so {@code Name} does not match {@code ClassName}.
	 */
	static boolean containerMatchesQualifier(IInfo info, String qualifier) {
		String container = containerOf(info);
		return container.equals(qualifier) || container.endsWith("." + qualifier);
	}

	/**
	 * The fully-dotted container of a token: its declaring module joined with its in-module path
	 * ({@code app.models.ClassName} for a method {@code method_name} in class {@code ClassName} of module
	 * {@code app.models}); just the declaring module for a top-level token; {@code ""} when unknown. This is
	 * both what {@link #containerMatchesQualifier} matches against and what the chooser shows as the greyed
	 * qualifier, so the displayed label and the narrowing logic stay in sync.
	 */
	static String containerOf(IInfo info) {
		String container = info.getDeclaringModuleName(); // non-null per IInfo's contract
		String path = info.getPath(); // null/empty for a top-level token
		if (path != null && !path.isEmpty()) {
			container = container.isEmpty() ? path : container + "." + path;
		}
		return container;
	}

	/**
	 * Whether {@code info}'s fully qualified name — the {@link #containerOf container} plus the simple name
	 * ({@code main.LRUCacheManager.update_cache}) — matches {@code matcher}. When {@code matcher} is built with
	 * {@code DEFAULT_MATCH_RULES | RULE_SUBSTRING_MATCH} (as {@link PythonElementSelectionDialog}'s filter is)
	 * this is case-insensitive substring matching with {@code *}/{@code ?} wildcards, so typing any fragment of
	 * what the list shows — {@code LRU}, {@code *LRU*}, {@code LRUCacheManager.update} — narrows to it.
	 */
	static boolean fqnMatches(SearchPattern matcher, IInfo info) {
		String container = containerOf(info);
		String fqn = container.isEmpty() ? info.getName() : container + "." + info.getName();
		return matcher.matches(fqn);
	}

	/**
	 * Collects the additional-tokens info to search: the system interpreter's info plus each related
	 * project's info. Applies {@code PyGlobalsBrowser.getFromManagerAndRelatedNatures}'s gathering logic
	 * across <em>every</em> configured interpreter manager (Python / Jython / IronPython), rather than for a
	 * single one.
	 *
	 * <p>{@code PyGlobalsBrowser} drives that logic with exactly one manager — derived from the active
	 * editor's nature, or, when there is none, picked Python-first by
	 * {@code ChooseInterpreterManager.chooseInterpreterManager}. This resolver deliberately does not pick a
	 * single manager: it has no editor context, and {@link #doOpen} derives the right nature per hit (a
	 * project's nature, or a {@link SystemPythonNature} for system tokens), so gathering from all configured
	 * managers finds tokens in any interpreter type — e.g. a Jython project when Python is also configured —
	 * instead of silently dropping them, which {@code chooseInterpreterManager}'s Python-first single choice
	 * would do.
	 *
	 * <p>Uses the non-UI interpreter-manager getters and never prompts for configuration, so it is safe in
	 * the background resolve job. An unconfigured or absent interpreter contributes nothing, yielding no
	 * match.
	 */
	private static List<AbstractAdditionalTokensInfo> gatherInfos() {
		List<AbstractAdditionalTokensInfo> infos = new ArrayList<>();
		for (IInterpreterManager manager : Arrays.asList(
				InterpreterManagersAPI.getPythonInterpreterManager(),
				InterpreterManagersAPI.getJythonInterpreterManager(),
				InterpreterManagersAPI.getIronpythonInterpreterManager())) {
			if (manager == null) {
				continue;
			}
			try {
				IInterpreterInfo defaultInfo = manager.getDefaultInterpreterInfo(false);
				if (defaultInfo == null) {
					continue; // this interpreter type is not configured — skip it
				}
				infos.add(AdditionalSystemInterpreterInfo.getAdditionalSystemInfo(manager,
						defaultInfo.getExecutableOrJar()));
				for (IPythonNature nature : PythonNature.getPythonNaturesRelatedTo(manager.getInterpreterType())) {
					try {
						AbstractAdditionalDependencyInfo projectInfo =
								AdditionalProjectInterpreterInfo.getAdditionalInfoForProject(nature);
						if (projectInfo != null) {
							infos.add(projectInfo);
						}
					} catch (MisconfigurationException e) {
						// just go on to the next nature if one is not properly configured
					}
				}
			} catch (MisconfigurationException e) {
				// default interpreter not configured for this manager — skip it
			}
		}
		return infos;
	}

	/**
	 * Opens a single matched token in a Python editor. Hops to the SWT thread (the resolve callback runs
	 * off it). Package-private so tests can observe it without a workbench.
	 */
	void open(AdditionalInfoAndIInfo match) {
		UiHelper.asyncExec(() -> doOpen(match));
	}

	/**
	 * Resolves {@code match} to a definition location and opens it, replicating
	 * {@code PyGlobalsBrowser.doSelect}: the nature is derived from the info the token was found in (a
	 * project's nature, or a fresh {@link SystemPythonNature} for system tokens), then
	 * {@link AnalysisPlugin#getDefinitionFromIInfo} resolves an {@link ItemPointer} that
	 * {@link PyOpenAction} opens. Must run on the SWT thread.
	 */
	private void doOpen(AdditionalInfoAndIInfo match) {
		try {
			IPythonNature nature;
			if (match.additionalInfo instanceof AdditionalProjectInterpreterInfo projectInfo) {
				nature = PythonNature.getPythonNature(projectInfo.getProject());
			} else if (match.additionalInfo instanceof AdditionalSystemInterpreterInfo systemInfo) {
				nature = new SystemPythonNature(systemInfo.getManager());
			} else {
				return;
			}
			if (nature == null) {
				return;
			}
			ICodeCompletionASTManager astManager = nature.getAstManager();
			if (astManager == null) {
				return;
			}
			List<ItemPointer> pointers = new ArrayList<>();
			AnalysisPlugin.getDefinitionFromIInfo(pointers, astManager, nature, match.info, new CompletionState(),
					false, true);
			if (!pointers.isEmpty()) {
				new PyOpenAction().run(pointers.get(0));
			} else {
				Activator.logError("Could not locate Python definition for: " + match.info.getName(), null);
			}
		} catch (MisconfigurationException e) {
			Activator.logError("Could not open Python definition for: " + match.info.getName(), e);
		}
	}

	/**
	 * Pops a selection dialog listing every matched token so the user picks which to open — the Python
	 * analog of {@link JavaIdentifierEntityResolver#openChooser}. The dialog ({@link PythonElementSelectionDialog})
	 * is modeled on PyDev's own Globals Browser: per-type icons, the greyed container qualifier, and a details
	 * panel. Each pick is opened via {@link #doOpen}. Package-private for tests.
	 */
	void openChooser(List<AdditionalInfoAndIInfo> matches) {
		UiHelper.asyncExec(() -> {
			IWorkbenchPage page = UiHelper.getActivePage();
			if (page == null) {
				return;
			}
			Shell shell = page.getWorkbenchWindow().getShell();
			PythonElementSelectionDialog dialog = new PythonElementSelectionDialog(shell, matches);
			if (dialog.open() != Window.OK) {
				return;
			}
			for (Object selected : dialog.getResult()) {
				doOpen((AdditionalInfoAndIInfo) selected);
			}
		});
	}

	/**
	 * Selection dialog for several matched Python tokens, modeled on PyDev's {@code GlobalsTwoPanelElementSelector2}
	 * (its Globals Browser). Unlike PyDev's, it lists only the resolver's already-narrowed {@code matches} rather
	 * than the whole index. Reuses PyDev's {@link NameIInfoLabelProvider} (for the per-type icon) and
	 * {@link ModuleIInfoLabelProvider} (for the bottom details panel), and styles the container qualifier grey via
	 * {@link StyledListLabelProvider}. The Help button is suppressed by {@link #isHelpAvailable()}.
	 *
	 * <p>The filter box matches the fully qualified name displayed in the list (container + simple name) by
	 * case-insensitive substring (with {@code *}/{@code ?} wildcards): its {@code patternMatcher} is built with
	 * {@link SearchPattern#RULE_SUBSTRING_MATCH}, so typing any fragment of what's shown — {@code LRU},
	 * {@code *LRU*}, {@code LRUCacheManager.update} — narrows to it. See {@code createFilter} and
	 * {@link #fqnMatches}.
	 */
	static final class PythonElementSelectionDialog extends FilteredItemsSelectionDialog {

		private static final String DIALOG_SETTINGS =
				"com.anthropic.claudecode.eclipse.resolvers.PythonElementSelectionDialog";

		private final List<AdditionalInfoAndIInfo> matches;

		PythonElementSelectionDialog(Shell shell, List<AdditionalInfoAndIInfo> matches) {
			super(shell, true);
			this.matches = matches;
			setTitle("Open Python Element");
			setMessage("Multiple Python elements match. Select one or more to open.\n"
					 + "Filter elements by name prefix or pattern (*, ?, or camel case):");
			setListLabelProvider(new StyledListLabelProvider());
			setDetailsLabelProvider(new ModuleIInfoLabelProvider());
		}

		/** Suppresses the Help ('?') button — mirrors PyDev's Globals Browser. */
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
			return ((AdditionalInfoAndIInfo) item).info.getName();
		}

		@Override
		protected IStatus validateItem(Object item) {
			return Status.OK_STATUS;
		}

		@Override
		protected Comparator<AdditionalInfoAndIInfo> getItemsComparator() {
			return Comparator
					.comparing((AdditionalInfoAndIInfo m) -> m.info.getName())
					.thenComparing(m -> m.info.getDeclaringModuleName(),
							Comparator.nullsFirst(Comparator.naturalOrder()));
		}

		@Override
		protected ItemsFilter createFilter() {
			// Filter by the fully qualified name shown in the list (container + simple name) via fqnMatches:
			// the patternMatcher is built with RULE_SUBSTRING_MATCH so typing any fragment of what's
			// displayed (LRU, *LRU*, LRUCacheManager.update) narrows to it. Built once per filter — FISD
			// makes a fresh filter per keystroke — and reused for every item, rather than per item.
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
					return matchAll || fqnMatches(patternMatcher, ((AdditionalInfoAndIInfo) item).info);
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
			for (AdditionalInfoAndIInfo match : matches) {
				contentProvider.add(match, itemsFilter);
			}
			if (progressMonitor != null) {
				progressMonitor.done();
			}
		}
	}

	/**
	 * Top-panel label provider: reuses PyDev's {@link NameIInfoLabelProvider} for the per-type icon and adds a
	 * styled label — the simple name followed by the full {@link #containerOf container} (module + in-module path)
	 * greyed with {@link StyledString#QUALIFIER_STYLER}. Mirrors PyDev's {@code NameIInfoStyledLabelProvider},
	 * differing only by showing the whole container rather than just the declaring module.
	 */
	static final class StyledListLabelProvider extends NameIInfoLabelProvider implements IStyledLabelProvider {

		StyledListLabelProvider() {
			super(true);
		}

		@Override
		public StyledString getStyledText(Object element) {
			IInfo info = NameIInfoLabelProvider.getInfo(element);
			if (info == null) {
				return new StyledString();
			}
			StyledString styled = new StyledString(info.getName());
			String container = containerOf(info);
			if (!container.isEmpty()) {
				styled.append(" - " + container, StyledString.QUALIFIER_STYLER);
			}
			return styled;
		}
	}

	/**
	 * Pure recognizer that decides whether a token taken from a Claude Code answer is a Python identifier
	 * and decomposes it. It only validates the shape; the PyDev lookup and editor open live in the enclosing
	 * {@link PythonIdentifierEntityResolver}.
	 *
	 * <p>Recognized shapes: a simple identifier ({@code Foo}, {@code my_func}), a dotted identifier
	 * ({@code os.path.join}), and either of those carrying a trailing call ({@code join(a, b)} —
	 * the argument list is dropped, since PyDev is searched by name only). The final dotted segment is the
	 * token searched.
	 *
	 * <p>When {@code allowStripEdges} is {@code true} the token may carry wrapping junk (quotes, brackets,
	 * a leading {@code @}/{@code *}, trailing sentence punctuation), trimmed before matching; closing
	 * brackets are trimmed only when unbalanced, so {@code join(a, b)} keeps its {@code )} while a wrapping
	 * {@code (Foo)} is unwrapped. When {@code false} the text must be <em>exactly</em> the reference.
	 *
	 * <p>Validation uses a self-contained dotted-identifier regex; the edge trimming mirrors
	 * {@link JavaIdentifierEntityResolver.JavaIdentifierMatcher}.
	 */
	static final class PythonIdentifierMatcher {

		/** A simple Python identifier: a letter/underscore start, then letters/digits/underscores. */
		private static final String SIMPLE_NAME = "[\\p{L}_][\\p{L}\\p{N}_]*";
		/** A dotted (or simple) Python identifier, e.g. {@code os.path.join} or {@code Foo}. */
		private static final Pattern DOTTED_IDENTIFIER = Pattern.compile(SIMPLE_NAME + "(?:\\." + SIMPLE_NAME + ")*");

		/** Wrapping characters trimmed from the leading edge — none can start a Python reference. */
		private static final String LEADING_JUNK = " \t\r\n \u00A0\"\'`*@.([{<";
		/** Plain trailing characters always trimmed; closing brackets are handled separately (balanced-aware). */
		private static final String TRAILING_PLAIN = " \t\r\n \u00A0\"\'`*.,;:!?";
		/** Closing brackets paired by index with their {@link #OPENERS}; trimmed only when unbalanced. */
		private static final String CLOSERS = ")]}>";
		private static final String OPENERS = "([{<";

		private PythonIdentifierMatcher() {}

		/**
		 * Classifies {@code text} as a Python identifier reference, or returns {@code null} if it is none.
		 *
		 * @param text            the candidate token
		 * @param allowStripEdges when {@code true}, trim wrapping junk before matching; when {@code false}
		 *                        the text must match exactly
		 * @return the decomposed reference, or {@code null}
		 */
		static PythonReference classify(String text, boolean allowStripEdges) {
			if (text == null || text.isBlank()) {
				return null;
			}
			String s = allowStripEdges ? IdentifierUtils.stripEdges(text, LEADING_JUNK, TRAILING_PLAIN, OPENERS, CLOSERS) : text;
			if (s.isEmpty()) {
				return null;
			}
			s = stripTrailingCall(s);
			if (!isDottedIdentifier(s)) {
				return null;
			}
			int lastDot = s.lastIndexOf('.');
			String name = lastDot < 0 ? s : s.substring(lastDot + 1);
			return new PythonReference(s, name);
		}

		/**
		 * Drops a trailing call: {@code join(a, b)} → {@code join}, {@code os.path.join(a, b)} →
		 * {@code os.path.join}. Only when the head before the {@code (} is itself a valid dotted identifier
		 * and the text ends with {@code )}, so {@code a(b).c} is left intact (and then rejected).
		 */
		private static String stripTrailingCall(String s) {
			int open = s.indexOf('(');
			if (open > 0 && s.endsWith(")")) {
				String head = s.substring(0, open).trim();
				if (isDottedIdentifier(head)) {
					return head;
				}
			}
			return s;
		}

		/** Whether {@code s} is a dotted Python identifier — each segment starts with a letter/underscore. */
		private static boolean isDottedIdentifier(String s) {
			return DOTTED_IDENTIFIER.matcher(s).matches();
		}
	}
}
