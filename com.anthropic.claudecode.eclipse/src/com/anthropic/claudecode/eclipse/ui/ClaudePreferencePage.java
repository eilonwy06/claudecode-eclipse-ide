package com.anthropic.claudecode.eclipse.ui;

import org.eclipse.jface.preference.BooleanFieldEditor;
import org.eclipse.jface.preference.FieldEditorPreferencePage;
import org.eclipse.jface.preference.IntegerFieldEditor;
import org.eclipse.jface.preference.StringFieldEditor;
import org.eclipse.ui.IWorkbench;
import org.eclipse.ui.IWorkbenchPreferencePage;

import com.anthropic.claudecode.eclipse.Activator;
import com.anthropic.claudecode.eclipse.Constants;

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
    }

    @Override
    public void init(IWorkbench workbench) {
        // Nothing to initialize
    }

    @Override
    public boolean performOk() {
        boolean result = super.performOk();
        if (result) {
            // Restart server if settings changed and server is running
            Activator activator = Activator.getDefault();
            if (activator.isServerRunning()) {
                activator.restart();
            }
        }
        return result;
    }
}
