package com.anthropic.claudecode.eclipse.ui;

import java.util.ArrayList;
import java.util.List;

import org.eclipse.jface.preference.BooleanFieldEditor;
import org.eclipse.jface.preference.ColorFieldEditor;
import org.eclipse.jface.preference.FieldEditor;
import org.eclipse.jface.preference.FieldEditorPreferencePage;
import org.eclipse.jface.preference.IPreferenceStore;
import org.eclipse.jface.preference.IntegerFieldEditor;
import org.eclipse.jface.preference.StringFieldEditor;
import org.eclipse.jface.util.PropertyChangeEvent;
import org.eclipse.swt.SWT;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Label;
import org.eclipse.ui.IWorkbench;
import org.eclipse.ui.IWorkbenchPreferencePage;

import com.anthropic.claudecode.eclipse.Activator;
import com.anthropic.claudecode.eclipse.Constants;
import com.anthropic.claudecode.eclipse.NativeCore;

public class ClaudePreferencePage extends FieldEditorPreferencePage implements IWorkbenchPreferencePage {

    private BooleanFieldEditor statuslineEnabled;
    private final List<FieldEditor> statuslineDependents = new ArrayList<>();

    public ClaudePreferencePage() {
        super(GRID);
        setPreferenceStore(Activator.getDefault().getPreferenceStore());
        setDescription("Configuration for Claude Code integration.");
    }

    @Override
    protected void createFieldEditors() {
        addField(new BooleanFieldEditor(
                Constants.PREF_AUTO_START,
                "Start server automatically on Eclipse launch",
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

        IntegerFieldEditor portMin = new IntegerFieldEditor(
                Constants.PREF_PORT_MIN,
                "Port range (min):",
                getFieldEditorParent());
        portMin.setValidRange(1024, 65535);
        addField(portMin);

        IntegerFieldEditor portMax = new IntegerFieldEditor(
                Constants.PREF_PORT_MAX,
                "Port range (max):",
                getFieldEditorParent());
        portMax.setValidRange(1024, 65535);
        addField(portMax);

        addField(new BooleanFieldEditor(
                Constants.PREF_AUTO_LAUNCH_CLI,
                "Auto-launch Claude Terminal on workspace open",
                getFieldEditorParent()));

        addField(new BooleanFieldEditor(
                Constants.PREF_DEBUG_MODE,
                "Debug mode",
                getFieldEditorParent()));

        // Claude Terminal colors — independent of Eclipse's Terminal colors.
        addField(new ColorFieldEditor(
                Constants.PREF_CONSOLE_BG_COLOR,
                "Claude Terminal background:",
                getFieldEditorParent()));

        addField(new ColorFieldEditor(
                Constants.PREF_CONSOLE_FG_COLOR,
                "Claude Terminal foreground:",
                getFieldEditorParent()));

        Label statusSeparator = new Label(getFieldEditorParent(), SWT.SEPARATOR | SWT.HORIZONTAL);
        statusSeparator.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false, 3, 1));

        Label statusLabel = new Label(getFieldEditorParent(), SWT.NONE);
        statusLabel.setText("Claude Terminal status bar configuration:");
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
                Constants.PREF_STATUSLINE_SHOW_WEEKLY,
                "Show weekly (7-day) usage limit",
                getFieldEditorParent()));

        IntegerFieldEditor refreshSeconds = new IntegerFieldEditor(
                Constants.PREF_STATUSLINE_REFRESH_SECONDS,
                "Status refresh interval (seconds, applies on next launch):",
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

    @Override
    protected void initialize() {
        super.initialize();
        updateStatuslineDependentsEnabled();
    }

    @Override
    protected void performDefaults() {
        super.performDefaults();
        updateStatuslineDependentsEnabled();
    }

    @Override
    public void propertyChange(PropertyChangeEvent event) {
        super.propertyChange(event);
        if (event.getSource() == statuslineEnabled
                && FieldEditor.VALUE.equals(event.getProperty())) {
            updateStatuslineDependentsEnabled();
        }
    }

    @Override
    public void init(IWorkbench workbench) {
        // Nothing to initialize
    }

    @Override
    public boolean performOk() {
        boolean result = super.performOk();
        if (result) {
            IPreferenceStore store = getPreferenceStore();
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

            Activator activator = Activator.getDefault();
            if (activator.isServerRunning()) {
                activator.restart();
            }
        }
        return result;
    }
}
