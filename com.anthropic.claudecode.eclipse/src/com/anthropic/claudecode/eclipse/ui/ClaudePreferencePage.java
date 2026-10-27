package com.anthropic.claudecode.eclipse.ui;

import java.util.ArrayList;
import java.util.List;

import org.eclipse.jface.dialogs.MessageDialog;
import org.eclipse.jface.preference.BooleanFieldEditor;
import org.eclipse.jface.preference.ComboFieldEditor;
import org.eclipse.jface.preference.FieldEditor;
import org.eclipse.jface.preference.FieldEditorPreferencePage;
import org.eclipse.jface.preference.IPreferenceStore;
import org.eclipse.jface.preference.IntegerFieldEditor;
import org.eclipse.jface.preference.RadioGroupFieldEditor;
import org.eclipse.jface.preference.StringFieldEditor;
import org.eclipse.jface.util.PropertyChangeEvent;
import org.eclipse.swt.SWT;
import org.eclipse.swt.events.FocusAdapter;
import org.eclipse.swt.events.FocusEvent;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Combo;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.Text;
import org.eclipse.ui.IViewPart;
import org.eclipse.ui.IWorkbench;
import org.eclipse.ui.IWorkbenchPage;
import org.eclipse.ui.IWorkbenchPreferencePage;

import com.anthropic.claudecode.eclipse.Activator;
import com.anthropic.claudecode.eclipse.Constants;
import com.anthropic.claudecode.eclipse.NativeCore;
import com.anthropic.claudecode.eclipse.editor.SelectionTracker;
import com.anthropic.claudecode.eclipse.editor.UiHelper;
import com.anthropic.claudecode.eclipse.tools.EclipseDialogTool;

public class ClaudePreferencePage extends FieldEditorPreferencePage implements IWorkbenchPreferencePage {

    private BooleanFieldEditor statuslineEnabled;
    private BooleanFieldEditor dictationEnabled;
    private BooleanFieldEditor dictationMacOS;
    private BooleanFieldEditor debugMode;
    private BooleanFieldEditor terminalOnly;
    private BooleanFieldEditor bypassAllowed;
    private DropDownFieldEditor initialPermissionMode;
    private Label codeViewHeading;

    /** The Claude Code view's own options: greyed out while the terminal is used exclusively. */
    private final List<FieldEditor> codeViewOptions = new ArrayList<>();
    private IntegerFieldEditor portMinEditor;
    private IntegerFieldEditor portMaxEditor;


    private final List<FieldEditor> statuslineDependents = new ArrayList<>();

    /**
     * Ports the plugin needs from the range at once: the MCP server, plus the two
     * the bridge relay binds when the Claude IDE Server view is open.
     */
    private static final int MIN_PORT_SPAN = 3;

    /**
     * Integer editor that folds the cross-field port-range check into its own
     * validity.
     *
     * <p>Reporting the problem at page level does not survive: every field editor
     * calls {@code clearErrorMessage()} when its own value is legal and
     * {@code showErrorMessage()} with its own text when it is not, and both run
     * after arbitrary events (focus, refresh), so a page-level message is clobbered
     * either way. Making the editor itself invalid instead means JFace re-displays
     * our message on every validation pass, and the message cannot outlive or fall
     * behind the condition.
     *
     * <p>The listeners exist because JFace wires VALIDATE_ON_KEY_STROKE with a key
     * listener plus focus-lost — which never fires for a context-menu paste. A
     * modify listener catches every mutation however it arrives.
     */
    private final class PortRangeFieldEditor extends IntegerFieldEditor {

        private boolean hooked;

        PortRangeFieldEditor(String name, String labelText, Composite parent) {
            super(name, labelText, parent);
        }

        /** This field's own complaint while it is invalid, else {@code null}. */
        String pendingErrorMessage() {
            return isValid() ? null : getErrorMessage();
        }

        @Override
        public Text getTextControl(Composite parent) {
            Text text = super.getTextControl(parent);
            if (!hooked) {
                hooked = true;
                // JFace wires VALIDATE_ON_KEY_STROKE with a key listener plus
                // focus-lost, so a context-menu paste is not validated until focus
                // leaves. A modify listener catches every mutation; the focus
                // listeners re-assert after the editors re-validate themselves.
                text.addModifyListener(e -> checkState());
                text.addFocusListener(new FocusAdapter() {
                    @Override
                    public void focusGained(FocusEvent e) {
                        checkState();
                    }

                    @Override
                    public void focusLost(FocusEvent e) {
                        checkState();
                    }
                });
            }
            return text;
        }
    }

    /**
     * A drop-down whose choices can be replaced while the page is open, which JFace's own
     * {@code ComboFieldEditor} cannot do: its entries are fixed when it is built. It has no
     * label of its own; the page puts the preference's name and description above it.
     */
    private static final class DropDownFieldEditor extends FieldEditor {

        private Combo combo;
        /** Each a {label, stored value} pair. */
        private String[][] choices;
        private String value = "";

        DropDownFieldEditor(String name, String[][] choices, Composite parent) {
            this.choices = choices;
            init(name, "");
            createControl(parent);
        }

        /**
         * Replaces the choices. A value that is no longer among them goes back to the
         * first, which is what the page then saves.
         */
        void setChoices(String[][] newChoices) {
            choices = newChoices;
            show(value);
        }

        /** Shows {@code wanted}, or the first choice when it is not one of them. */
        private void show(String wanted) {
            String[] labels = new String[choices.length];
            int at = 0;
            for (int i = 0; i < choices.length; i++) {
                labels[i] = choices[i][0];
                if (choices[i][1].equals(wanted)) at = i;
            }
            value = choices[at][1];
            if (combo != null && !combo.isDisposed()) {
                combo.setItems(labels);
                combo.select(at);
            }
        }

        @Override
        public int getNumberOfControls() {
            return 1;
        }

        @Override
        protected void adjustForNumColumns(int numColumns) {
            ((GridData) combo.getLayoutData()).horizontalSpan = numColumns;
        }

        @Override
        protected void doFillIntoGrid(Composite parent, int numColumns) {
            // The page calls this again once it knows its column count, so the combo is
            // made the first time and only placed after that.
            if (combo == null) {
                combo = new Combo(parent, SWT.READ_ONLY);
                combo.setFont(parent.getFont());
                combo.addListener(SWT.Selection, e -> {
                    int at = combo.getSelectionIndex();
                    if (at < 0) return;
                    String old = value;
                    value = choices[at][1];
                    setPresentsDefaultValue(false);
                    fireValueChanged(VALUE, old, value);
                });
                show(value);
            }
            combo.setLayoutData(new GridData(SWT.BEGINNING, SWT.CENTER, false, false, numColumns, 1));
        }

        @Override
        protected void doLoad() {
            show(getPreferenceStore().getString(getPreferenceName()));
        }

        @Override
        protected void doLoadDefault() {
            show(getPreferenceStore().getDefaultString(getPreferenceName()));
        }

        @Override
        protected void doStore() {
            getPreferenceStore().setValue(getPreferenceName(), value);
        }
    }

    /** One decision-card timeout's mode radio group + its dependent custom-seconds field. */
    private record TimeoutFieldPair(RadioGroupFieldEditor mode, IntegerFieldEditor seconds) {}
    private final List<TimeoutFieldPair> timeoutFields = new ArrayList<>();

    public ClaudePreferencePage() {
        super(GRID);
        setPreferenceStore(Activator.getDefault().getPreferenceStore());
        setDescription("Configuration for Claude Code integration.");
    }

    @Override
    protected void createFieldEditors() {
        addSectionHeading("General configuration:", false);

        addField(new BooleanFieldEditor(
                Constants.PREF_TRACK_SELECTION,
                "Track editor selection in real-time",
                getFieldEditorParent()));

        addField(new BooleanFieldEditor(
                Constants.PREF_SCROLL_LOCK_DEFAULT,
                "Scroll Lock enabled by default",
                getFieldEditorParent()));

        addField(new StringFieldEditor(
                Constants.PREF_CLAUDE_CMD,
                "Claude command:",
                getFieldEditorParent()));

        addField(new StringFieldEditor(
                Constants.PREF_CLAUDE_ARGS,
                "Arguments:",
                getFieldEditorParent()));

        portMinEditor = new PortRangeFieldEditor(
                Constants.PREF_PORT_MIN,
                "Port range (min):",
                getFieldEditorParent());
        portMinEditor.setValidRange(1024, 65535);

        addField(portMinEditor);

        portMaxEditor = new PortRangeFieldEditor(
                Constants.PREF_PORT_MAX,
                "Port range (max):",
                getFieldEditorParent());
        portMaxEditor.setValidRange(1024, 65535);

        addField(portMaxEditor);

        // The next three reach both views: a Claude Code conversation and a Claude
        // Terminal session are each started with them (see TerminalLaunchArgs).
        addField(new BooleanFieldEditor(
                Constants.PREF_REMOTE_CONTROL_STARTUP,
                "Enable Remote Control for all sessions",
                getFieldEditorParent()));

        addField(new BooleanFieldEditor(
                Constants.PREF_THINKING_DEFAULT,
                "Enable Thinking by default",
                getFieldEditorParent()));

        bypassAllowed = new BooleanFieldEditor(
                Constants.PREF_LIVE_AUTO_MODE,
                // The VS Code extension's own wording for this setting
                // (claudeCode.allowDangerouslySkipPermissions), verbatim.
                "Allow bypass permissions mode. Recommended only for sandboxes with no internet access.",
                getFieldEditorParent());
        addField(bypassAllowed);

        terminalOnly = new BooleanFieldEditor(
                Constants.PREF_TERMINAL_ONLY,
                "Exclusively use terminal. Launch Claude in the terminal instead of the native UI. "
                        + "Disables Claude Code view.",
                getFieldEditorParent());
        addField(terminalOnly);

        // Reaches both views as well. The name, the description and the values are the VS
        // Code extension's for this setting (claudeCode.initialPermissionMode), verbatim;
        // bypass permissions is taken off the list while the box above does not allow it —
        // see updateInitialPermissionModeChoices().
        initialPermissionMode = addDropDown(
                Constants.PREF_INITIAL_PERMISSION_MODE,
                "Initial Permission Mode",
                "Initial permission mode for new conversations. Unset defers to the Claude Code CLI's resolved\n"
                        + "default for the session. 'manual' is an alias for 'default', the mode labeled Manual in the UI;\n"
                        + "set either to always start in Manual.",
                modeChoices(true));
        addField(initialPermissionMode);

        // The VS Code extension's for this setting too (claudeCode.archiveInactiveSessions).
        // It reaches the Claude Code view's history; the Terminal's is the CLI's own.
        addField(addDropDown(
                Constants.PREF_ARCHIVE_INACTIVE_SESSIONS,
                "Archive Inactive Sessions",
                "Archive a session after this long with no activity. Sessions that are open, running, waiting for\n"
                        + "input, or unread are never archived automatically.",
                new String[][] { { "Never", "0" }, { "1 day", "1" }, { "2 days", "2" }, { "7 days", "7" },
                        { "14 days", "14" } }));

        addSectionHeading("Claude Terminal view configuration:", true);

        addField(new BooleanFieldEditor(
                Constants.PREF_AUTO_START,
                "Open new Claude Terminal automatically on Eclipse launch",
                getFieldEditorParent()));

        addField(new BooleanFieldEditor(
                Constants.PREF_TERMINAL_MCP_TOOLS,
                "Enable MCP tools integration.",
                getFieldEditorParent()));

        if (overlayScrollbarsInUse(getFieldEditorParent())) {
            addField(new BooleanFieldEditor(
                    Constants.PREF_CLI_PERSISTENT_SCROLLBAR,
                    "Persistent vertical scrollbar",
                    getFieldEditorParent()));
        }

        codeViewHeading = addSectionHeading("Claude Code view configuration:", true);

        dictationEnabled = new BooleanFieldEditor(
                Constants.PREF_DICTATION_ENABLED,
                "Enable Speech-to-text (STT) [Experimental]",
                getFieldEditorParent());
        addCodeViewOption(dictationEnabled);

        // macOS only. Shown while Debug mode is ticked, tickable while the option above
        // is; see updateDictationMacOSState().
        if (Activator.isMacOS()) {
            dictationMacOS = new BooleanFieldEditor(
                    Constants.PREF_DICTATION_MACOS,
                    "Enable Speech-to-text (STT) for Mac [Experimental]",
                    getFieldEditorParent());
            addField(dictationMacOS);
        }

        addCodeViewOption(new BooleanFieldEditor(
                Constants.PREF_EDITABLE_PERMISSION_CARDS,
                "Enable editable commands on permission card",
                getFieldEditorParent()));

        addCodeViewOption(new BooleanFieldEditor(
                Constants.PREF_SMART_SCROLL_LOCK,
                "Smart Scroll Lock: still jump to the bottom for your own actions (sending a "
                        + "message, answering a card) even while Scroll Lock is on",
                getFieldEditorParent()));

        addCodeViewOption(new BooleanFieldEditor(
                Constants.PREF_CONTINUE_AFTER_RELOAD,
                "Continue after reload",
                getFieldEditorParent()));

        addCodeViewOption(new RadioGroupFieldEditor(
                Constants.PREF_TOOL_CARD_MODE,
                "Start a tool's input, output and diff cards:",
                1,
                new String[][] {
                        { "Hidden (only the tool line; click its arrow to show them)", Constants.TOOL_CARDS_HIDDEN },
                        { "Collapsed to one line", Constants.TOOL_CARDS_COLLAPSED },
                        { "Showing a few lines", Constants.TOOL_CARDS_PREVIEW },
                        { "Showing everything", Constants.TOOL_CARDS_EXPANDED },
                },
                getFieldEditorParent(),
                true));

        // One choice per kind of tool, over the one above; "Same as above" leaves a tool on it.
        String[][] perTool = {
                { "Same as above", "" },
                { "Hidden", Constants.TOOL_CARDS_HIDDEN },
                { "Collapsed to one line", Constants.TOOL_CARDS_COLLAPSED },
                { "A few lines", Constants.TOOL_CARDS_PREVIEW },
                { "Everything", Constants.TOOL_CARDS_EXPANDED },
        };
        for (String[] type : Constants.TOOL_CARD_TYPES) {
            addCodeViewOption(new ComboFieldEditor(
                    Constants.PREF_TOOL_CARD_MODE_PREFIX + type[0],
                    "    " + type[1] + ":",
                    perTool,
                    getFieldEditorParent()));
        }

        addCodeViewOption(new BooleanFieldEditor(
                Constants.PREF_HISTORY_SHOW_TIMESTAMPS,
                "Show the time above your messages and Claude's replies",
                getFieldEditorParent()));

        addCodeViewOption(new BooleanFieldEditor(
                Constants.PREF_HIDE_ROOT_DIRECTORIES_ROW,
                "Hide the root directories row (for single-folder use)",
                getFieldEditorParent()));

        addCodeViewOption(new BooleanFieldEditor(
                Constants.PREF_HIDE_BEFORE_COMPACTION,
                "Hide messages from before a compaction",
                getFieldEditorParent()));

        Label statusSeparator = new Label(getFieldEditorParent(), SWT.SEPARATOR | SWT.HORIZONTAL);
        statusSeparator.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false, 3, 1));

        Label statusLabel = new Label(getFieldEditorParent(), SWT.NONE);
        statusLabel.setText("Claude status bar configuration:");
        statusLabel.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false, 3, 1));

        statuslineEnabled = new BooleanFieldEditor(
                Constants.PREF_STATUSLINE_ENABLED,
                "Show status bar (applies to newly launched sessions)",
                getFieldEditorParent());
        addField(statuslineEnabled);

        addStatuslineDependent(new BooleanFieldEditor(
                Constants.PREF_STATUSLINE_SHOW_MODEL,
                "Show model",
                getFieldEditorParent()));

        addStatuslineDependent(new BooleanFieldEditor(
                Constants.PREF_STATUSLINE_SHOW_EFFORT,
                "Show effort level",
                getFieldEditorParent()));

        addStatuslineDependent(new BooleanFieldEditor(
                Constants.PREF_STATUSLINE_SHOW_THINKING,
                "Show thinking indicator",
                getFieldEditorParent()));

        addStatuslineDependent(new BooleanFieldEditor(
                Constants.PREF_STATUSLINE_SHOW_CONTEXT,
                "Show context-window usage",
                getFieldEditorParent()));

        addStatuslineDependent(new BooleanFieldEditor(
                Constants.PREF_STATUSLINE_SHOW_COST,
                "Show session cost (USD)",
                getFieldEditorParent()));

        addStatuslineDependent(new BooleanFieldEditor(
                Constants.PREF_STATUSLINE_SHOW_SESSION_5H,
                "Show 5-hour (session) usage limit",
                getFieldEditorParent()));

        addStatuslineDependent(new BooleanFieldEditor(
                Constants.PREF_STATUSLINE_SHOW_SESSION_5H_RESET,
                "Show reset time for 5-hour (session) usage limit",
                getFieldEditorParent()));

        addStatuslineDependent(new BooleanFieldEditor(
                Constants.PREF_STATUSLINE_SHOW_WEEKLY,
                "Show weekly (7-day) usage limit",
                getFieldEditorParent()));

        addStatuslineDependent(new BooleanFieldEditor(
                Constants.PREF_STATUSLINE_SHOW_WEEKLY_RESET,
                "Show reset time for weekly (7-day) usage limit",
                getFieldEditorParent()));

        IntegerFieldEditor refreshSeconds = new IntegerFieldEditor(
                Constants.PREF_STATUSLINE_REFRESH_SECONDS,
                "Status refresh interval (seconds; Terminal applies on next launch):",
                getFieldEditorParent());
        refreshSeconds.setValidRange(1, 3600);
        addStatuslineDependent(refreshSeconds);

        Label separator = new Label(getFieldEditorParent(), SWT.SEPARATOR | SWT.HORIZONTAL);
        separator.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false, 3, 1));

        Label networkLabel = new Label(getFieldEditorParent(), SWT.NONE);
        networkLabel.setText("Network / Proxy (leave empty to auto-detect from shell):");
        networkLabel.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false, 3, 1));

        addField(new StringFieldEditor(
                Constants.PREF_HTTP_PROXY,
                "HTTP_PROXY:",
                getFieldEditorParent()));

        addField(new StringFieldEditor(
                Constants.PREF_HTTPS_PROXY,
                "HTTPS_PROXY:",
                getFieldEditorParent()));

        addField(new StringFieldEditor(
                Constants.PREF_NO_PROXY,
                "NO_PROXY:",
                getFieldEditorParent()));

        Label timeoutSeparator = new Label(getFieldEditorParent(), SWT.SEPARATOR | SWT.HORIZONTAL);
        timeoutSeparator.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false, 3, 1));

        Label timeoutLabel = new Label(getFieldEditorParent(), SWT.NONE);
        timeoutLabel.setText("Decision card timeouts: how long an unanswered card waits before Claude\n"
                + "Code assumes an answer (a denial, for approval/question cards) and continues\n"
                + "on its own. Applies to cards raised after the change; a card already waiting\n"
                + "keeps the timeout that was in effect when it appeared:");
        timeoutLabel.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false, 3, 1));

        addTimeoutFields("Permission approval:", Constants.PREF_APPROVAL_TIMEOUT_MODE,
                Constants.PREF_APPROVAL_TIMEOUT_SECONDS);
        addTimeoutFields("Ask-user question:", Constants.PREF_QUESTION_TIMEOUT_MODE,
                Constants.PREF_QUESTION_TIMEOUT_SECONDS);
        addTimeoutFields("Diff review:", Constants.PREF_DIFF_REVIEW_TIMEOUT_MODE,
                Constants.PREF_DIFF_REVIEW_TIMEOUT_SECONDS);

        Label miscSeparator = new Label(getFieldEditorParent(), SWT.SEPARATOR | SWT.HORIZONTAL);
        miscSeparator.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false, 3, 1));

        Label miscLabel = new Label(getFieldEditorParent(), SWT.NONE);
        miscLabel.setText("Miscellaneous Configuration");
        miscLabel.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false, 3, 1));

        addField(new BooleanFieldEditor(
                Constants.PREF_SPINNER_DEPRECATED,
                "Use deprecated spinner verbs",
                getFieldEditorParent()));

        addField(new BooleanFieldEditor(
                Constants.PREF_SPINNER_PACK_ONE,
                "Use expansion pack one spinner verbs",
                getFieldEditorParent()));

        addField(new BooleanFieldEditor(
                Constants.PREF_SPINNER_PACK_TWO,
                "Use expansion pack two spinner verbs",
                getFieldEditorParent()));

        addField(new BooleanFieldEditor(
                Constants.PREF_SPINNER_DANK,
                "Use dank spinner verbs",
                getFieldEditorParent()));

        addField(new BooleanFieldEditor(
                Constants.PREF_SPINNER_VIBECODER,
                "Assert being a vibecoder",
                getFieldEditorParent()));

        addField(new BooleanFieldEditor(
                Constants.PREF_SPINNER_CUSTOM,
                "Use custom spinner verbs",
                getFieldEditorParent()));

        debugMode = new BooleanFieldEditor(
                Constants.PREF_DEBUG_MODE,
                "Debug mode",
                getFieldEditorParent());
        addField(debugMode);
    }

    /**
     * A section heading, in the form the page's other sections use: a label across the
     * page, under a rule unless it is the first thing on it.
     */
    private Label addSectionHeading(String text, boolean ruleAbove) {
        if (ruleAbove) {
            Label rule = new Label(getFieldEditorParent(), SWT.SEPARATOR | SWT.HORIZONTAL);
            rule.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false, 3, 1));
        }
        Label heading = new Label(getFieldEditorParent(), SWT.NONE);
        heading.setText(text);
        heading.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false, 3, 1));
        return heading;
    }

    /**
     * A drop-down preference, laid out as the VS Code extension's settings page lays its own
     * out: the name, what it does, then the choices. The caller adds the editor to the page.
     */
    private DropDownFieldEditor addDropDown(String preference, String name, String description,
            String[][] choices) {
        Label nameLabel = new Label(getFieldEditorParent(), SWT.NONE);
        nameLabel.setText(name);
        nameLabel.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false, 3, 1));

        Label descriptionLabel = new Label(getFieldEditorParent(), SWT.NONE);
        descriptionLabel.setText(description);
        descriptionLabel.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false, 3, 1));

        return new DropDownFieldEditor(preference, choices, getFieldEditorParent());
    }

    /** The initial permission modes as the drop-down takes them: each is its own label. */
    private static String[][] modeChoices(boolean bypassAllowed) {
        List<String> modes = InitialPermissionMode.choices(bypassAllowed);
        String[][] choices = new String[modes.size()][];
        for (int i = 0; i < choices.length; i++) {
            choices[i] = new String[] { modes.get(i), modes.get(i) };
        }
        return choices;
    }

    /**
     * Lists bypass permissions among the initial permission modes only while "Allow bypass
     * permissions mode" is ticked. Read from the editor rather than the store, so it follows
     * unsaved clicks; unticked with that mode chosen, the choice goes back to unset.
     */
    private void updateInitialPermissionModeChoices() {
        if (bypassAllowed == null || initialPermissionMode == null) {
            return;
        }
        initialPermissionMode.setChoices(modeChoices(bypassAllowed.getBooleanValue()));
    }

    private void addCodeViewOption(FieldEditor editor) {
        codeViewOptions.add(editor);
        addField(editor);
    }

    /** Whether the page, as it stands unsaved, leaves the Claude Code view in use. */
    private boolean codeViewInUse() {
        return terminalOnly == null || !terminalOnly.getBooleanValue();
    }

    /**
     * Greys the Claude Code view's section out while "Exclusively use terminal" is ticked:
     * with the view gone there is nothing for those options to act on. Read from the editor
     * rather than the store, so it follows unsaved clicks. The values themselves are left
     * alone, and are in force again when the box is unticked.
     */
    private void updateCodeViewOptionsEnabled() {
        boolean inUse = codeViewInUse();
        Composite parent = getFieldEditorParent();
        if (codeViewHeading != null && !codeViewHeading.isDisposed()) {
            codeViewHeading.setEnabled(inUse);
        }
        for (FieldEditor editor : codeViewOptions) {
            editor.setEnabled(inUse, parent);
        }
        // The macOS dictation option answers to this and to two boxes of its own.
        updateDictationMacOSState();
    }

    /**
     * Asked when "Exclusively use terminal" is about to be switched on over an open Claude
     * Code view, since closing the view ends the conversations running in it.
     */
    private boolean confirmClosingCodeView() {
        // Constructed rather than MessageDialog.open(...): the overload that takes custom
        // button labels returns the button INDEX, and dismissing the dialog returns -1.
        MessageDialog dlg = new MessageDialog(getShell(),
                "Close the Claude Code view?", null,
                "You are about to use the terminal exclusively, this action will close the "
                        + "Claude Code view and end the conversations running in it. Do you wish to proceed?",
                MessageDialog.CONFIRM, new String[] { "Close the view", "Cancel" }, 1);
        // The user's own decision: eclipseDialog lists it and never answers it.
        dlg.create();
        EclipseDialogTool.forUserOnly(dlg.getShell());
        return dlg.open() == 0;
    }

    /**
     * Whether this platform paints overlay (auto-hiding) scrollbars <em>and</em> lets a widget
     * opt out of them — the only situation in which
     * {@link Constants#PREF_CLI_PERSISTENT_SCROLLBAR} changes anything, and so the only
     * situation in which the page offers it.
     *
     * <p>Both halves are answered by asking SWT rather than by testing the OS: a throwaway
     * scrollable reports the mode it would be given, and toggling it proves whether the switch
     * is honoured. That keeps macOS out (it reports overlay scrollbars, but
     * {@link org.eclipse.swt.widgets.Scrollable#setScrollbarsMode(int)} is a no-op there, as on
     * Windows) and equally keeps out a GTK session launched with {@code GTK_OVERLAY_SCROLLING=0},
     * where the artifact cannot occur in the first place.
     */
    private static boolean overlayScrollbarsInUse(Composite parent) {
        Composite probe = new Composite(parent, SWT.V_SCROLL);
        try {
            if (probe.getScrollbarsMode() != SWT.SCROLLBAR_OVERLAY) return false;
            probe.setScrollbarsMode(SWT.NONE);
            return probe.getScrollbarsMode() != SWT.SCROLLBAR_OVERLAY;
        } finally {
            probe.dispose();
        }
    }

    /**
     * Adds one decision-card timeout's mode radio group + custom-seconds field,
     * under a sub-heading naming which card it governs.
     */
    private void addTimeoutFields(String heading, String modeKey, String secondsKey) {
        Label heading_ = new Label(getFieldEditorParent(), SWT.NONE);
        heading_.setText(heading);
        heading_.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false, 3, 1));

        RadioGroupFieldEditor mode = new RadioGroupFieldEditor(
                modeKey,
                "",
                1,
                new String[][] {
                        { "Default (30 minutes)", Constants.TIMEOUT_MODE_DEFAULT },
                        { "Never — wait indefinitely for an answer", Constants.TIMEOUT_MODE_NEVER },
                        { "Custom:", Constants.TIMEOUT_MODE_CUSTOM },
                },
                getFieldEditorParent(),
                true);
        addField(mode);

        IntegerFieldEditor seconds = new IntegerFieldEditor(
                secondsKey,
                "Custom timeout (seconds):",
                getFieldEditorParent());
        seconds.setValidRange(1, Integer.MAX_VALUE / 2);
        addField(seconds);

        timeoutFields.add(new TimeoutFieldPair(mode, seconds));
    }

    private void addStatuslineDependent(FieldEditor editor) {
        statuslineDependents.add(editor);
        addField(editor);
    }

    private void updateStatuslineDependentsEnabled() {
        if (statuslineEnabled == null) {
            return;
        }
        boolean enabled = statuslineEnabled.getBooleanValue();
        Composite parent = getFieldEditorParent();
        for (FieldEditor editor : statuslineDependents) {
            editor.setEnabled(enabled, parent);
        }
    }

    /**
     * The macOS dictation option (created on macOS only) exists for someone debugging it:
     * shown while Debug mode is ticked and taken off the page while it is not; tickable
     * only while dictation itself is ticked, greyed otherwise. Whenever EITHER of those is
     * unticked, the option is unticked too, so it can never be left on behind a box that
     * no longer allows it — {@link #performOk()} enforces the same in the store. Read from
     * the editors rather than the store, so it follows unsaved clicks.
     */
    private void updateDictationMacOSState() {
        if (dictationMacOS == null || dictationEnabled == null || debugMode == null) {
            return;
        }
        boolean debug = debugMode.getBooleanValue();
        boolean dictation = dictationEnabled.getBooleanValue();
        Composite parent = getFieldEditorParent();
        Control box = dictationMacOS.getDescriptionControl(parent);
        // BooleanFieldEditor stores whatever the checkbox shows, so unticking the
        // checkbox is what the page saves.
        if (!(debug && dictation) && box instanceof Button check) {
            check.setSelection(false);
        }
        dictationMacOS.setEnabled(dictation && codeViewInUse(), parent);
        if (box.getLayoutData() instanceof GridData gd) {
            gd.exclude = !debug;
        }
        box.setVisible(debug);
        parent.layout(true, true);
    }

    private void updateTimeoutSecondsEnabled(TimeoutFieldPair pair) {
        boolean custom = Constants.TIMEOUT_MODE_CUSTOM.equals(pair.mode().getSelectionValue());
        pair.seconds().setEnabled(custom, getFieldEditorParent());
    }

    private void updateAllTimeoutSecondsEnabled() {
        for (TimeoutFieldPair pair : timeoutFields) {
            updateTimeoutSecondsEnabled(pair);
        }
    }

    @Override
    protected void initialize() {
        super.initialize();
        updateStatuslineDependentsEnabled();
        updateAllTimeoutSecondsEnabled();
        updateCodeViewOptionsEnabled();
        updateInitialPermissionModeChoices();
        // Loading values into the editors fires neither IS_VALID nor VALUE, so a
        // range already persisted as inverted (from a build before this check
        // existed) would otherwise open as valid with Apply enabled.
        checkState();
    }

    @Override
    protected void performDefaults() {
        super.performDefaults();
        updateStatuslineDependentsEnabled();
        updateAllTimeoutSecondsEnabled();
        updateCodeViewOptionsEnabled();
        updateInitialPermissionModeChoices();
    }

    @Override
    public void propertyChange(PropertyChangeEvent event) {
        super.propertyChange(event);

        // Restore Defaults reloads both port values without going through the text
        // listeners, so re-validate the pair from here too.
        if (event.getSource() == portMinEditor || event.getSource() == portMaxEditor) {
            checkState();
        }

        if (event.getSource() == statuslineEnabled
                && FieldEditor.VALUE.equals(event.getProperty())) {
            updateStatuslineDependentsEnabled();
        }
        if ((event.getSource() == debugMode || event.getSource() == dictationEnabled)
                && FieldEditor.VALUE.equals(event.getProperty())) {
            updateDictationMacOSState();
        }
        if (event.getSource() == terminalOnly && FieldEditor.VALUE.equals(event.getProperty())) {
            updateCodeViewOptionsEnabled();
        }
        if (event.getSource() == bypassAllowed && FieldEditor.VALUE.equals(event.getProperty())) {
            updateInitialPermissionModeChoices();
        }
        if (FieldEditor.VALUE.equals(event.getProperty())) {
            for (TimeoutFieldPair pair : timeoutFields) {
                if (event.getSource() == pair.mode()) {
                    updateTimeoutSecondsEnabled(pair);
                }
            }
        }
    }

    @Override
    public void init(IWorkbench workbench) {
        // Nothing to initialize
    }

    /**
     * Cross-field check on the port range. The individual editors only bound each
     * value to 1024-65535, which lets min exceed max — an empty range the server
     * cannot bind anywhere in, leaving it unable to start at all.
     */
    /**
     * The cross-field verdict on the port pair, or {@code null} when it is fine.
     * Returns null while either field is mid-edit — the editors' own validation
     * reports that, and its message is the more specific one.
     */
    /**
     * Single chokepoint for the page's error text. Every writer — each field editor
     * clearing or showing its own message, and the page itself — passes through
     * here, so recomputing the port verdict on each call makes it impossible for
     * the cross-field message to be cleared while the range is still wrong, or to
     * linger once it is fixed. A caller's own non-null message wins, because it is
     * about a specific field and is the more precise complaint.
     */
    @Override
    public void setErrorMessage(String newMessage) {
        super.setErrorMessage(newMessage != null ? newMessage : outstandingProblem());
    }

    /**
     * The complaint that should still be on screen when a field clears its own
     * message. Clicking the *valid* field of an invalid pair makes it call
     * {@code clearErrorMessage()}, which would otherwise wipe the other field's
     * still-current error. An individual field's message comes first because it is
     * the more specific one; the cross-field verdict applies only when both values
     * are individually fine.
     */
    private String outstandingProblem() {
        if (portMinEditor instanceof PortRangeFieldEditor min) {
            String pending = min.pendingErrorMessage();
            if (pending != null) {
                return pending;
            }
        }
        if (portMaxEditor instanceof PortRangeFieldEditor max) {
            String pending = max.pendingErrorMessage();
            if (pending != null) {
                return pending;
            }
        }
        return portRangeProblem();
    }

    @Override
    protected void checkState() {
        super.checkState();
        // super only consults each editor's own validity; neither knows about the
        // other. Veto the pair here.
        if (isValid()) {
            String problem = portRangeProblem();
            if (problem != null) {
                setValid(false);
                setErrorMessage(problem);
            } else {
                setErrorMessage(null);
            }
        }
    }

    private String portRangeProblem() {
        if (portMinEditor == null || portMaxEditor == null) {
            return null;
        }
        int min;
        int max;
        try {
            min = portMinEditor.getIntValue();
            max = portMaxEditor.getIntValue();
        } catch (NumberFormatException e) {
            return null;
        }
        if (min > max) {
            return "Port range (min) must not be greater than port range (max).";
        }
        if (max - min + 1 < MIN_PORT_SPAN) {
            return "Port range must span at least " + MIN_PORT_SPAN
                    + " ports (one for the server, two for the bridge relay).";
        }
        return null;
    }

    @Override
    public boolean performOk() {
        IPreferenceStore store = getPreferenceStore();
        Activator activator = Activator.getDefault();

        // Read the old value BEFORE super.performOk(): the field editors write the
        // new values into the store inside that call, so reading afterwards would
        // compare a value against itself and never detect the change.
        boolean trackedBefore = store.getBoolean(Constants.PREF_TRACK_SELECTION);

        // Asked BEFORE super.performOk() for the same reason: that call writes the
        // preference, and writing it is what closes the view (DebugModeSourceProvider).
        // Declined, nothing on the page is saved and it stays open as it is.
        if (terminalOnly != null && terminalOnly.getBooleanValue()
                && !store.getBoolean(Constants.PREF_TERMINAL_ONLY)
                && TerminalOnlyUi.isCodeViewOpen() && !confirmClosingCodeView()) {
            return false;
        }

        boolean result = super.performOk();
        if (!result) {
            return result;
        }

        // Backs up the page's own unticking: the macOS dictation option is never stored
        // as on unless both Debug mode and dictation are. This page is the only writer of
        // either, so enforcing it here covers every way they can be turned off.
        if (!(store.getBoolean(Constants.PREF_DEBUG_MODE)
                && store.getBoolean(Constants.PREF_DICTATION_ENABLED))) {
            store.setValue(Constants.PREF_DICTATION_MACOS, false);
        }

        NativeCore.setProxyOverrides(
            store.getString(Constants.PREF_HTTP_PROXY),
            store.getString(Constants.PREF_HTTPS_PROXY),
            store.getString(Constants.PREF_NO_PROXY)
        );
        try {
            NativeCore.setDebugMode(store.getBoolean(Constants.PREF_DEBUG_MODE));
        } catch (UnsatisfiedLinkError ignored) {
            // Native library doesn't have setDebugMode — older build, skip silently.
        }
        try {
            // Conversations already running keep what they launched with; this takes
            // effect on the next one each tab starts.
            NativeCore.setLiveAutoMode(store.getBoolean(Constants.PREF_LIVE_AUTO_MODE));
        } catch (UnsatisfiedLinkError ignored) {
            // Older build without setLiveAutoMode — skip silently.
        }
        // The debug-only UI (Claude IDE Server view + its menu item) reacts to
        // this preference change via DebugModeSourceProvider — no call needed here.
        // So does "Exclusively use terminal", which hides or restores the Claude Code
        // view and everything that opens it.

        if (!activator.isServerRunning()) {
            return result;
        }

        // Selection tracking toggles the tracker directly against the running
        // server; it never needed a restart to take effect.
        boolean trackedNow = store.getBoolean(Constants.PREF_TRACK_SELECTION);
        if (trackedNow != trackedBefore) {
            SelectionTracker tracker = activator.getSelectionTracker();
            if (tracker != null) {
                if (trackedNow) {
                    tracker.start(activator.getHttpSseServer());
                } else {
                    tracker.stop();
                }
            }
        }

        // Everything else on this page is applied live above, or is read where it
        // is used, so the ONLY change that forces a rebind is a port range that no
        // longer contains the port we are already serving on. Widening the range,
        // or editing any of the other preferences, leaves the running server valid
        // — and a needless restart is not free: it moves the port, mints a new auth
        // token, rewrites the lock file, and strands every live conversation on the
        // old server. The bound port is runtime state, so the field editors above
        // cannot have altered it.
        int boundPort = activator.getHttpSseServer().getPort();
        int portMin = store.getInt(Constants.PREF_PORT_MIN);
        int portMax = store.getInt(Constants.PREF_PORT_MAX);
        if (boundPort > 0 && (boundPort < portMin || boundPort > portMax)) {
            activator.restart();
            // Mirror ClaudeCodeView/RestartServerHandler: a restart moves the port
            // and the auth token, so sessions must reconnect rather than be left
            // talking to the server we just replaced.
            restartCliSessions();
        }
        return result;
    }

    /** Reconnects open Claude Terminal sessions after the server has been replaced. */
    private void restartCliSessions() {
        try {
            IWorkbenchPage page = UiHelper.getActivePage();
            if (page == null) {
                return;
            }
            IViewPart view = page.findView(ClaudeCliView.VIEW_ID);
            if (view instanceof ClaudeCliView cliView) {
                cliView.restartAllSessions();
            }
        } catch (Exception e) {
            Activator.logError("Failed to restart CLI sessions after preference change", e);
        }
    }
}
