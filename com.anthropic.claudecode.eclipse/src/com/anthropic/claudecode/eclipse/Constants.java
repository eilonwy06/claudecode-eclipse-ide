package com.anthropic.claudecode.eclipse;

import java.util.concurrent.TimeUnit;

import org.eclipse.jface.preference.IPreferenceStore;

public final class Constants {

    public static final String PLUGIN_ID = "com.anthropic.claudecode.eclipse";
    public static final String MCP_VERSION = "2024-11-05";
    public static final String LOCK_FILE_VERSION = "0.2.0";
    public static final String IDE_NAME = "Eclipse";

    public static final int PORT_RANGE_MIN = 10000;
    public static final int PORT_RANGE_MAX = 65535;

    // Legacy key "autoStart": now controls auto-opening a Claude Terminal on Eclipse launch.
    // (The MCP server always starts unconditionally, so this no longer gates the server.)
    public static final String PREF_AUTO_START = "autoStart";
    public static final String PREF_PORT_MIN = "portMin";
    public static final String PREF_PORT_MAX = "portMax";
    public static final String PREF_CLAUDE_CMD = "claudeCommand";
    public static final String PREF_CLAUDE_ARGS = "claudeArguments";
    public static final String PREF_LOG_LEVEL = "logLevel";
    public static final String PREF_TRACK_SELECTION = "trackSelection";
    public static final String PREF_TERMINAL_POSITION = "terminalPosition";
    public static final String PREF_DEBUG_MODE = "debugMode";

    /** Initial state of the Scroll Lock toolbar toggle for a newly created view instance —
     *  a configured default, not a remembered last state. Shared by both the Claude Code
     *  (GUI) view (ClaudeGuiView#createToolBar, one view-wide toggle) and the Claude
     *  Terminal view (ClaudeCliView#configureActionBars, applied to each new tab's own
     *  TerminalSession — see the per-tab isScrollLock/setScrollLock there). */
    public static final String PREF_SCROLL_LOCK_DEFAULT = "scrollLockDefault";

    /** In the Claude Code (GUI) view, while Scroll Lock is armed, still jump to the bottom
     *  for the user's OWN deliberate actions (sending a message, answering an approval or
     *  question card) and when Claude raises a NEW approval or question card, rather than
     *  holding through those too. A card timing out on its own is NOT one of these — that
     *  was never forced even before Scroll Lock existed. Off matches the plugin's current
     *  upstream behavior.
     *
     *  <p>GUI view only by design — Terminal's Scroll Lock is TM Terminal's own viewport
     *  freeze, with no "user sent input" seam to hook a bypass onto. */
    public static final String PREF_SMART_SCROLL_LOCK = "smartScrollLock";

    /** Set once the user dismisses the Ctrl+Click hint bar in the Claude Terminal view (per-workspace). */
    public static final String PREF_CLI_CTRLCLICK_HINT_DISMISSED = "cliCtrlClickHintDismissed";

    public static final String PREF_HTTP_PROXY = "httpProxy";
    public static final String PREF_HTTPS_PROXY = "httpsProxy";
    public static final String PREF_NO_PROXY = "noProxy";

    // ── Decision-card timeouts ───────────────────────────────────────────────
    // How long each kind of blocking "waiting on the user" card (permission
    // approval, AskUserQuestion, diff review) waits before giving up and
    // resolving on its own. Each is one of TIMEOUT_MODE_DEFAULT/_NEVER/_CUSTOM
    // plus a *_SECONDS value; TIMEOUT_MODE_DEFAULT reproduces the plugin's
    // longstanding hardcoded 30-minute wait for that card.
    public static final String PREF_APPROVAL_TIMEOUT_MODE = "approvalTimeoutMode";
    public static final String PREF_APPROVAL_TIMEOUT_SECONDS = "approvalTimeoutSeconds";
    public static final String PREF_QUESTION_TIMEOUT_MODE = "questionTimeoutMode";
    public static final String PREF_QUESTION_TIMEOUT_SECONDS = "questionTimeoutSeconds";
    public static final String PREF_DIFF_REVIEW_TIMEOUT_MODE = "diffReviewTimeoutMode";
    public static final String PREF_DIFF_REVIEW_TIMEOUT_SECONDS = "diffReviewTimeoutSeconds";

    /** Use the plugin's longstanding default wait (currently 30 minutes) for this card. */
    public static final String TIMEOUT_MODE_DEFAULT = "default";
    /** Wait indefinitely (in practice: a very long ceiling — see resolveTimeoutSeconds). */
    public static final String TIMEOUT_MODE_NEVER = "never";
    /** Wait exactly the paired *_SECONDS preference. */
    public static final String TIMEOUT_MODE_CUSTOM = "custom";

    /** The wait every decision-card timeout has always used, preserved as the default. */
    public static final int DEFAULT_DECISION_TIMEOUT_SECONDS = 30 * 60;

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
    /** Show "(resets in ...)" next to the Session meter — the reset epoch lands in the
     *  same process-wide {@code ClaudeStatusStore} regardless of which view's data
     *  channel supplied it, so this applies in both views. */
    public static final String PREF_STATUSLINE_SHOW_SESSION_5H_RESET = "statuslineShowSession5hReset";
    public static final String PREF_STATUSLINE_SHOW_WEEKLY = "statuslineShowWeekly";
    /** Show "(resets in ...)" next to the Weekly meter (see PREF_STATUSLINE_SHOW_SESSION_5H_RESET). */
    public static final String PREF_STATUSLINE_SHOW_WEEKLY_RESET = "statuslineShowWeeklyReset";
    /** Claude's idle re-run timer for the statusLine command, in seconds. */
    public static final String PREF_STATUSLINE_REFRESH_SECONDS = "statuslineRefreshSeconds";

    /** Show a small timestamp line above each of your own messages, in the Claude Code
     *  view — live sends and loaded history both, in local time (the record itself is
     *  UTC; see {@code load_session_history} in {@code session.rs}). Off by default:
     *  the same information is already one hover away in the History panel's own
     *  per-session time, so this trades a bit of vertical space for always-visible detail. */
    public static final String PREF_HISTORY_SHOW_TIMESTAMPS = "historyShowTimestamps";

    /** Hide the root ("supertab") directory row entirely, in the Claude Code view —
     *  no picker, no per-session show/hide toggle (supertabsVisible), nothing. For a
     *  user who only ever works in one folder, the row (plus the toggle's own collapsed
     *  #cwd-row stand-in) is pure vertical space spent on a feature they never use. Off
     *  by default: multi-root conversations are the new upstream behavior, and this is
     *  an opt-out, not the other way around. */
    public static final String PREF_HIDE_ROOT_DIRECTORIES_ROW = "hideRootDirectoriesRow";

    // ── Spinner verbs ───────────────────────────────────────────────────────
    // Which optional slices of the working-indicator gerund list are in rotation.
    // Each names a membership set over the single master list in working.js
    // (VERB_SETS there); a word rotates when it belongs to no set, or to at least
    // one enabled set. The Claude Code view applies them on page load and on view
    // activation; the Terminal mirrors the same choice into the CLI's own spinner
    // through the spinnerVerbs key of the injected --settings file (SpinnerVerbs),
    // which binds at launch, so a change there reaches the next tab.
    public static final String PREF_SPINNER_DEPRECATED = "spinnerVerbsDeprecated";
    public static final String PREF_SPINNER_PACK_ONE = "spinnerVerbsPackOne";
    public static final String PREF_SPINNER_PACK_TWO = "spinnerVerbsPackTwo";
    public static final String PREF_SPINNER_DANK = "spinnerVerbsDank";
    public static final String PREF_SPINNER_VIBECODER = "spinnerVerbsVibecoder";
    /**
     * Whether the user's own spinnerVerbs from ~/.claude/settings.json join the Claude Code
     * view's rotation. Defaults on, matching what the Terminal does with them anyway.
     * <p>
     * The Claude Code view is the only place this can act. The Terminal's spinner belongs to
     * the CLI, which unions the verbs array across every settings scope rather than letting
     * the highest one win, so the user's own words are in that rotation whether we name them
     * or not — verified in the CLI's merge customizer; see SpinnerVerbs#settingsJson.
     */
    public static final String PREF_SPINNER_CUSTOM = "spinnerVerbsCustom";

    public static final String DEFAULT_CLAUDE_CMD = "claude";

    public static final String IMG_CLEAR_REFRESH = "clear_co";
    public static final String IMG_NEW_CLI_SESSION = "new_cli_session";
    public static final String IMG_SCROLL_LOCK = "scroll_lock";
    public static final String IMG_SESSION_HISTORY = "session_history";

    /**
     * A ceiling used for {@link #TIMEOUT_MODE_NEVER}: none of the three decision-card
     * waits are guaranteed to be released early if their tab/view/editor closes while
     * the card is pending (the CompletableFuture just sits there), so a truly unbounded
     * {@code future.get()} could wedge that worker thread — and the CLI call it's
     * servicing — forever. 30 days is "never" for any human waiting on a prompt, while
     * still guaranteeing the thread eventually unblocks.
     */
    private static final long NEVER_TIMEOUT_SECONDS = TimeUnit.DAYS.toSeconds(30);

    /**
     * Resolves one of the three decision-card timeout preference pairs (mode + custom
     * seconds) to the number of seconds to actually wait.
     */
    public static long resolveTimeoutSeconds(IPreferenceStore store, String modeKey, String secondsKey) {
        String mode = store.getString(modeKey);
        if (TIMEOUT_MODE_NEVER.equals(mode)) {
            return NEVER_TIMEOUT_SECONDS;
        }
        if (TIMEOUT_MODE_CUSTOM.equals(mode)) {
            return store.getInt(secondsKey);
        }
        return DEFAULT_DECISION_TIMEOUT_SECONDS;   // TIMEOUT_MODE_DEFAULT, or unrecognized/unset
    }

    // ── Claude Code (GUI) working roots ─────────────────────────────────────
    /**
     * Folders the user has agreed to let Claude Code run in, from the GUI's trust
     * window. Stored as a newline-separated list of absolute paths.
     *
     * <p>The plugin's own record. The grant is ALSO mirrored into the CLI's
     * {@code ~/.claude.json} so the Claude Terminal doesn't ask again, but that mirror
     * is best effort — this preference is what the GUI actually honours, and it works
     * whether or not the CLI has ever run. See {@code TrustStore}.
     */
    public static final String PREF_TRUSTED_ROOTS = "trustedRoots";

    private Constants() {}
}
