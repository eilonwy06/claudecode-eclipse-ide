package com.anthropic.claudecode.eclipse.resolvers;

import java.util.Comparator;
import java.util.List;

import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.Status;
import org.eclipse.jface.dialogs.IDialogSettings;
import org.eclipse.jface.viewers.ILabelProvider;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.ui.dialogs.FilteredItemsSelectionDialog;
import org.eclipse.ui.dialogs.SearchPattern;

import com.anthropic.claudecode.eclipse.Activator;

/**
 * Shared base for the entity resolvers' "multiple matches — pick one (or more)" choosers. Each resolver
 * ({@link FileEntityResolver}, {@link JavaIdentifierEntityResolver}, {@link CppIdentifierEntityResolver},
 * {@link PythonIdentifierEntityResolver}) resolves a token from a Claude Code answer; when several elements
 * match, it opens a small {@link FilteredItemsSelectionDialog} listing only those already-narrowed matches
 * (rather than browsing the whole workspace/index like the platform's Open Resource / Open Type pickers).
 * The four dialogs differ only in data — title/message, dialog-settings key, the two label providers, and
 * the text rows are filtered/sorted by — so all the FISD plumbing lives here and each domain supplies a
 * thin subclass providing {@link #filterText} plus its label providers.
 *
 * <p><b>Platform API only.</b> This base intentionally references nothing from JDT / CDT / PyDev: each
 * resolver's javadoc promises that all of its optional-plugin API lives in that one class, so the class is
 * only ever loaded when the plugin is present (see {@link EntitiesRegistry}'s guard). Keep the
 * domain-specific label providers in their resolvers and pass them in here as plain {@link ILabelProvider}s.
 *
 * <p>The filter box matches {@link #filterText} by case-insensitive substring (with {@code *}/{@code ?}
 * wildcards): {@code patternMatcher} is built with {@link SearchPattern#RULE_SUBSTRING_MATCH}, so typing any
 * fragment of what a row shows narrows to it. {@link #filterText} is also the default
 * {@link #getElementName} and sort key, so the displayed label and the narrowing logic stay in sync.
 *
 * @param <T> the matched element type (e.g. {@code IFile}, {@code IJavaElement})
 */
abstract class EntitySelectionDialog<T> extends FilteredItemsSelectionDialog {

	private final String dialogSettingsKey;
	private final List<T> matches;

	/**
	 * @param shell                 the parent shell
	 * @param dialogSettingsKey     the {@link IDialogSettings} section name; stable per domain so the
	 *                              dialog's persisted size/position carries over
	 * @param title                 the dialog title
	 * @param message               the prompt/message shown above the filter box
	 * @param matches               the already-narrowed matches to list
	 * @param listLabelProvider     row label provider (per-type icon + styled label)
	 * @param detailsLabelProvider  bottom details-panel label provider
	 */
	protected EntitySelectionDialog(Shell shell, String dialogSettingsKey, String title, String message,
			List<T> matches, ILabelProvider listLabelProvider, ILabelProvider detailsLabelProvider) {
		super(shell, true);
		this.dialogSettingsKey = dialogSettingsKey;
		this.matches = matches;
		setTitle(title);
		setMessage(message);
		setListLabelProvider(listLabelProvider);
		setDetailsLabelProvider(detailsLabelProvider);
	}

	/**
	 * The text a row is filtered against — by default also the {@link #getElementName} and sort key. It is
	 * the plain-text form of what the list label provider renders, so what is shown and what is matched stay
	 * in sync (e.g. a fully qualified name or a workspace path).
	 */
	protected abstract String filterText(T item);

	@Override
	@SuppressWarnings("unchecked")
	public String getElementName(Object item) {
		return filterText((T) item);
	}

	@Override
	protected Comparator<T> getItemsComparator() {
		return Comparator.comparing(this::filterText);
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
	protected IStatus validateItem(Object item) {
		return Status.OK_STATUS;
	}

	@Override
	protected IDialogSettings getDialogSettings() {
		IDialogSettings settings = Activator.getDefault().getDialogSettings().getSection(dialogSettingsKey);
		if (settings == null) {
			settings = Activator.getDefault().getDialogSettings().addNewSection(dialogSettingsKey);
		}
		return settings;
	}

	@Override
	protected ItemsFilter createFilter() {
		// Filter by filterText(item) via the patternMatcher: it is built with RULE_SUBSTRING_MATCH so typing
		// any fragment of what's displayed narrows to it. Built once per filter — FISD makes a fresh filter
		// per keystroke — and reused for every item, rather than per item.
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
			@SuppressWarnings("unchecked")
			public boolean matchItem(Object item) {
				return matchAll || patternMatcher.matches(filterText((T) item));
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
		for (T match : matches) {
			contentProvider.add(match, itemsFilter);
		}
		if (progressMonitor != null) {
			progressMonitor.done();
		}
	}
}
