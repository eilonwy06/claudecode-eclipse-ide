package com.anthropic.claudecode.eclipse.ui;

import java.util.ArrayList;
import java.util.List;

import org.eclipse.jface.preference.BooleanFieldEditor;
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
import org.eclipse.swt.widgets.Composite;
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

public class ClaudePreferencePage extends FieldEditorPreferencePage implements IWorkbenchPreferencePage {

    private BooleanFieldEditor statuslineEnabled;
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
        addField(new BooleanFieldEditor(
                Constants.PREF_AUTO_START,
                "Open new Claude Terminal automatically on Eclipse launch",
                getFieldEditorParent()));

        addField(new BooleanFieldEditor(
                Constants.PREF_TRACK_SELECTION,
                "Track editor selection in real-time",
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

        addField(new BooleanFieldEditor(
                Constants.PREF_DEBUG_MODE,
                "Debug mode",
                getFieldEditorParent()));

        addField(new BooleanFieldEditor(
                Constants.PREF_SCROLL_LOCK_DEFAULT,
                "Scroll Lock enabled by default (Claude Code and Claude Terminal views)",
                getFieldEditorParent()));

        addField(new BooleanFieldEditor(
                Constants.PREF_SMART_SCROLL_LOCK,
                "Smart Scroll Lock: in the Claude Code view, still jump to the bottom for "
                        + "your own actions (sending a message, answering a card) even while "
                        + "Scroll Lock is on",
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
                Constants.PREF_HISTORY_SHOW_TIMESTAMPS,
                "Show a timestamp above your own messages, in the Claude Code view",
                getFieldEditorParent()));

        addField(new BooleanFieldEditor(
                Constants.PREF_HIDE_ROOT_DIRECTORIES_ROW,
                "Hide the root directories row, in the Claude Code view (for single-folder use)",
                getFieldEditorParent()));

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

        boolean result = super.performOk();
        if (!result) {
            return result;
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
        // The debug-only UI (Claude IDE Server view + its menu item) reacts to
        // this preference change via DebugModeSourceProvider — no call needed here.

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
