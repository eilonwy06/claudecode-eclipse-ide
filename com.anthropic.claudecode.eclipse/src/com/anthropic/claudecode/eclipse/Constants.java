package com.anthropic.claudecode.eclipse;

public final class Constants {

    public static final String PLUGIN_ID = "com.anthropic.claudecode.eclipse";
    public static final String MCP_VERSION = "2024-11-05";
    public static final String LOCK_FILE_VERSION = "0.2.0";
    public static final String IDE_NAME = "Eclipse";

    public static final int PORT_RANGE_MIN = 10000;
    public static final int PORT_RANGE_MAX = 65535;

    public static final String PREF_AUTO_START = "autoStart";
    public static final String PREF_PORT_MIN = "portMin";
    public static final String PREF_PORT_MAX = "portMax";
    public static final String PREF_CLAUDE_CMD = "claudeCommand";
    public static final String PREF_CLAUDE_ARGS = "claudeArguments";
    public static final String PREF_LOG_LEVEL = "logLevel";
    public static final String PREF_TRACK_SELECTION = "trackSelection";
    public static final String PREF_TERMINAL_POSITION = "terminalPosition";
    public static final String PREF_AUTO_LAUNCH_CLI = "autoLaunchCli";
    public static final String PREF_DEBUG_MODE = "debugMode";

    /** Set once the user dismisses the Ctrl+Click hint bar in the Claude Terminal view (per-workspace). */
    public static final String PREF_CLI_CTRLCLICK_HINT_DISMISSED = "cliCtrlClickHintDismissed";

    public static final String PREF_HTTP_PROXY = "httpProxy";
    public static final String PREF_HTTPS_PROXY = "httpsProxy";
    public static final String PREF_NO_PROXY = "noProxy";

    // ── Claude Terminal status bar (statusLine) ─────────────────────────────
    /** Master switch: inject the statusLine and show the per-tab status bar. */
    public static final String PREF_STATUSLINE_ENABLED = "statuslineEnabled";
    /** Per-element visibility toggles (apply live in the bar render path). */
    public static final String PREF_STATUSLINE_SHOW_MODEL = "statuslineShowModel";
    public static final String PREF_STATUSLINE_SHOW_EFFORT = "statuslineShowEffort";
    public static final String PREF_STATUSLINE_SHOW_THINKING = "statuslineShowThinking";
    public static final String PREF_STATUSLINE_SHOW_CONTEXT = "statuslineShowContext";
    public static final String PREF_STATUSLINE_SHOW_COST = "statuslineShowCost";
    public static final String PREF_STATUSLINE_SHOW_SESSION_5H = "statuslineShowSession5h";
    public static final String PREF_STATUSLINE_SHOW_WEEKLY = "statuslineShowWeekly";
    /** Claude's idle re-run timer for the statusLine command, in seconds. */
    public static final String PREF_STATUSLINE_REFRESH_SECONDS = "statuslineRefreshSeconds";

    public static final String DEFAULT_CLAUDE_CMD = "claude";

    public static final String IMG_CLEAR_REFRESH = "clear_co";
    public static final String IMG_NEW_CLI_SESSION = "new_cli_session";
    public static final String IMG_SCROLL_LOCK = "scroll_lock";

    private Constants() {}
}
