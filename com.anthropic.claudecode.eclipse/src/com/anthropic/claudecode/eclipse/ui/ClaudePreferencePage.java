package com.anthropic.claudecode.eclipse.ui;

import org.eclipse.jface.preference.BooleanFieldEditor;
import org.eclipse.jface.preference.FieldEditorPreferencePage;
import org.eclipse.jface.preference.IPreferenceStore;
import org.eclipse.jface.preference.IntegerFieldEditor;
import org.eclipse.jface.preference.StringFieldEditor;
import org.eclipse.swt.SWT;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.widgets.Label;
import org.eclipse.ui.IWorkbench;
import org.eclipse.ui.IWorkbenchPreferencePage;

import com.anthropic.claudecode.eclipse.Activator;
import com.anthropic.claudecode.eclipse.Constants;
import com.anthropic.claudecode.eclipse.NativeCore;

public class ClaudePreferencePage extends FieldEditorPreferencePage implements IWorkbenchPreferencePage {

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
