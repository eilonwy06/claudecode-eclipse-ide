package com.anthropic.claudecode.eclipse.ui;

import org.eclipse.core.runtime.preferences.AbstractPreferenceInitializer;
import org.eclipse.jface.preference.IPreferenceStore;

import com.anthropic.claudecode.eclipse.Activator;
import com.anthropic.claudecode.eclipse.Constants;

public class ClaudePreferenceInitializer extends AbstractPreferenceInitializer {

    @Override
    public void initializeDefaultPreferences() {
        IPreferenceStore store = Activator.getDefault().getPreferenceStore();
        // "autoStart" now = auto-open a Claude Terminal on launch; opt-in (server always starts).
        store.setDefault(Constants.PREF_AUTO_START, false);
        store.setDefault(Constants.PREF_PORT_MIN, Constants.PORT_RANGE_MIN);
        store.setDefault(Constants.PREF_PORT_MAX, Constants.PORT_RANGE_MAX);
        store.setDefault(Constants.PREF_CLAUDE_CMD, Constants.DEFAULT_CLAUDE_CMD);
        store.setDefault(Constants.PREF_CLAUDE_ARGS, "");
        store.setDefault(Constants.PREF_LOG_LEVEL, "info");
        store.setDefault(Constants.PREF_TRACK_SELECTION, true);
        store.setDefault(Constants.PREF_TERMINAL_POSITION, "bottom");
        store.setDefault(Constants.PREF_DEBUG_MODE, false);
        store.setDefault(Constants.PREF_CLI_CTRLCLICK_HINT_DISMISSED, false);

        store.setDefault(Constants.PREF_HTTP_PROXY, "");
        store.setDefault(Constants.PREF_HTTPS_PROXY, "");
        store.setDefault(Constants.PREF_NO_PROXY, "");

        store.setDefault(Constants.PREF_APPROVAL_TIMEOUT_MODE, Constants.TIMEOUT_MODE_DEFAULT);
        store.setDefault(Constants.PREF_APPROVAL_TIMEOUT_SECONDS, Constants.DEFAULT_DECISION_TIMEOUT_SECONDS);
        store.setDefault(Constants.PREF_QUESTION_TIMEOUT_MODE, Constants.TIMEOUT_MODE_DEFAULT);
        store.setDefault(Constants.PREF_QUESTION_TIMEOUT_SECONDS, Constants.DEFAULT_DECISION_TIMEOUT_SECONDS);
        store.setDefault(Constants.PREF_DIFF_REVIEW_TIMEOUT_MODE, Constants.TIMEOUT_MODE_DEFAULT);
        store.setDefault(Constants.PREF_DIFF_REVIEW_TIMEOUT_SECONDS, Constants.DEFAULT_DECISION_TIMEOUT_SECONDS);

        // Claude Terminal status bar (statusLine) — on by default, except the thinking segment.
        store.setDefault(Constants.PREF_STATUSLINE_ENABLED, true);
        store.setDefault(Constants.PREF_STATUSLINE_SHOW_MODEL, true);
        store.setDefault(Constants.PREF_STATUSLINE_SHOW_EFFORT, true);
        store.setDefault(Constants.PREF_STATUSLINE_SHOW_THINKING, false);
        store.setDefault(Constants.PREF_STATUSLINE_SHOW_CONTEXT, true);
        store.setDefault(Constants.PREF_STATUSLINE_SHOW_COST, false);
        store.setDefault(Constants.PREF_STATUSLINE_SHOW_SESSION_5H, true);
        store.setDefault(Constants.PREF_STATUSLINE_SHOW_WEEKLY, true);
        store.setDefault(Constants.PREF_STATUSLINE_REFRESH_SECONDS, 60);

        // Claude Code view spinner verbs — the three that were already in rotation
        // stay on; dank and the vibecoder claim are opt-in.
        store.setDefault(Constants.PREF_SPINNER_DEPRECATED, true);
        store.setDefault(Constants.PREF_SPINNER_PACK_ONE, true);
        store.setDefault(Constants.PREF_SPINNER_PACK_TWO, true);
        store.setDefault(Constants.PREF_SPINNER_DANK, false);
        store.setDefault(Constants.PREF_SPINNER_VIBECODER, false);
    }
}
