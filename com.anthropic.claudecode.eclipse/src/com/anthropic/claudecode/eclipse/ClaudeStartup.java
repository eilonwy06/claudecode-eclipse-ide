package com.anthropic.claudecode.eclipse;

import org.eclipse.ui.IStartup;

public class ClaudeStartup implements IStartup {

    @Override
    public void earlyStartup() {
        Activator activator = Activator.getDefault();
        if (activator == null) return;

        boolean autoStart = activator.getPreferenceStore().getBoolean(Constants.PREF_AUTO_START);
        if (autoStart) {
            activator.initialize();
        }
    }
}
