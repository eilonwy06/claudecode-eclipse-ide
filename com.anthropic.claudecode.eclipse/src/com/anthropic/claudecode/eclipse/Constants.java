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

    /** Set once the user dismisses the Ctrl+Click hint bar in the Claude CLI view (per-workspace). */
    public static final String PREF_CLI_CTRLCLICK_HINT_DISMISSED = "cliCtrlClickHintDismissed";

    public static final String PREF_HTTP_PROXY = "httpProxy";
    public static final String PREF_HTTPS_PROXY = "httpsProxy";
    public static final String PREF_NO_PROXY = "noProxy";

    public static final String PREF_CONSOLE_THEME = "consoleTheme";
    public static final String CONSOLE_THEME_DARK = "dark";
    public static final String CONSOLE_THEME_LIGHT = "light";

    /** Claude CLI terminal colors (independent of Eclipse's Terminal prefs).
     *  Stored as PreferenceConverter RGB strings ("r,g,b"). */
    public static final String PREF_CONSOLE_BG_COLOR = "consoleBgColor";
    public static final String PREF_CONSOLE_FG_COLOR = "consoleFgColor";

    public static final String DEFAULT_CLAUDE_CMD = "claude";

    public static final String IMG_CLEAR_REFRESH = "clear_co";
    public static final String IMG_NEW_CLI_SESSION = "new_cli_session";
    public static final String IMG_SCROLL_LOCK = "scroll_lock";

    private Constants() {}
}
