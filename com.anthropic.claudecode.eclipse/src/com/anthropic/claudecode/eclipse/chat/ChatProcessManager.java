package com.anthropic.claudecode.eclipse.chat;

import java.util.function.Consumer;

import org.eclipse.jface.preference.IPreferenceStore;

import com.anthropic.claudecode.eclipse.Activator;
import com.anthropic.claudecode.eclipse.Constants;
import com.anthropic.claudecode.eclipse.NativeCore;

/**
 * Thin Java wrapper over the Rust ChatManager.
 *
 * Streaming events (text, tool use, errors) arrive via JNI callbacks from
 * Rust worker threads and are forwarded to the Java Consumer callbacks that
 * {@link com.anthropic.claudecode.eclipse.ui.ClaudeChatView} registers.
 */
public class ChatProcessManager {

    private final long handle;

    private Consumer<String> onText;
    private Consumer<String> onToolStart;
    private Consumer<String> onToolEnd;
    private Runnable onStreamStart;
    private Runnable onStreamEnd;
    private Consumer<String> onError;
    private Consumer<String> onSystem;
    private Consumer<String> onThinking;
    private Consumer<String> onSessionId;
    private Consumer<String> onTokens;
    private Consumer<String> onRateLimit;
    // Persistent mode only (Claude GUI). Both may block until the user decides;
    // Rust calls them on dedicated threads. Defaults when unset match headless
    // claude -p: permissions denied, questions dismissed.
    private PermissionHandler onPermissionRequest;
    private java.util.function.BiFunction<String, String, String> onQuestionRequest;
    private Consumer<String> onCardCancel;
    private Consumer<String> onStatus;
    private Consumer<String> onCompact;
    private Consumer<String> onRemoteControl;
    private Consumer<String> onRemoteMessage;
    private Consumer<String> onBrowserState;
    private Consumer<String> onSettingsChanged;
    private Consumer<String> onAgentActivity;
    private Consumer<String> onNotice;
    private Consumer<String> onMcp;
    private Consumer<String> onCliReply;
    private Consumer<String> onFastMode;

    /** (requestId, toolName, inputJson, rememberLabel) → decision string. See {@link NativeCore.ChatCallbacks#onPermissionRequest}. */
    public interface PermissionHandler {
        String handle(String requestId, String toolName, String inputJson, String rememberLabel);
    }

    public ChatProcessManager() {
        this.handle = NativeCore.chatCreate();
        NativeCore.chatRegisterCallbacks(handle, new NativeCore.ChatCallbacks() {
            @Override public void onStreamStart()          { emit(ChatProcessManager.this.onStreamStart); }
            @Override public void onText(String t)         { emit(ChatProcessManager.this.onText, t); }
            @Override public void onToolStart(String name) { emit(ChatProcessManager.this.onToolStart, name); }
            @Override public void onStreamEnd()            { emit(ChatProcessManager.this.onStreamEnd); }
            @Override public void onError(String msg)      { emit(ChatProcessManager.this.onError, msg); }
            @Override public void onSystem(String msg)     { emit(ChatProcessManager.this.onSystem, msg); }
            @Override public void onThinking(String t)     { emit(ChatProcessManager.this.onThinking, t); }
            @Override public void onSessionId(String id)   { emit(ChatProcessManager.this.onSessionId, id); }
            @Override public void onTokens(String n)       { emit(ChatProcessManager.this.onTokens, n); }
            @Override public void onRateLimit(String j)    { emit(ChatProcessManager.this.onRateLimit, j); }
            @Override public String onPermissionRequest(String requestId, String toolName,
                                                        String inputJson, String rememberLabel) {
                var h = ChatProcessManager.this.onPermissionRequest;
                if (h == null) return "deny";
                try { return h.handle(requestId, toolName, inputJson, rememberLabel); } catch (Exception e) { return "deny"; }
            }
            @Override public String onQuestionRequest(String requestId, String questionsJson) {
                var h = ChatProcessManager.this.onQuestionRequest;
                if (h == null) return "[]";
                try { return h.apply(requestId, questionsJson); } catch (Exception e) { return "[]"; }
            }
            // Straight through rather than via emit(): the card it takes down is
            // holding a Rust thread hostage, and emit() defers to the UI thread,
            // which is exactly where a modal-ish card is least likely to be idle.
            @Override public void onCardCancel(String requestId) {
                var h = ChatProcessManager.this.onCardCancel;
                if (h == null) return;
                try { h.accept(requestId); } catch (Exception ignored) {}
            }
            @Override public void onStatus(String json) { emit(ChatProcessManager.this.onStatus, json); }
            @Override public void onCompact(String json) { emit(ChatProcessManager.this.onCompact, json); }
            @Override public void onToolEnd(String json) { emit(ChatProcessManager.this.onToolEnd, json); }
            @Override public void onRemoteControl(String json) { emit(ChatProcessManager.this.onRemoteControl, json); }
            @Override public void onRemoteMessage(String text) { emit(ChatProcessManager.this.onRemoteMessage, text); }
            @Override public void onBrowserState(String json) { emit(ChatProcessManager.this.onBrowserState, json); }
            @Override public void onSettingsChanged(String json) { emit(ChatProcessManager.this.onSettingsChanged, json); }
            @Override public void onAgentActivity(String json) { emit(ChatProcessManager.this.onAgentActivity, json); }
            @Override public void onNotice(String text) { emit(ChatProcessManager.this.onNotice, text); }
            @Override public void onMcp(String json) { emit(ChatProcessManager.this.onMcp, json); }
            @Override public void onCliReply(String json) { emit(ChatProcessManager.this.onCliReply, json); }
            @Override public void onFastMode(String json) { emit(ChatProcessManager.this.onFastMode, json); }
        });
    }

    // ── Consumer registration (same API as original) ─────────────────────────

    public void setOnText(Consumer<String> cb)      { this.onText = cb; }
    public void setOnToolStart(Consumer<String> cb) { this.onToolStart = cb; }
    public void setOnToolEnd(Consumer<String> cb)   { this.onToolEnd = cb; }
    public void setOnStreamStart(Runnable cb)       { this.onStreamStart = cb; }
    public void setOnStreamEnd(Runnable cb)         { this.onStreamEnd = cb; }
    public void setOnError(Consumer<String> cb)     { this.onError = cb; }
    public void setOnSystem(Consumer<String> cb)    { this.onSystem = cb; }
    public void setOnThinking(Consumer<String> cb)  { this.onThinking = cb; }
    public void setOnSessionId(Consumer<String> cb) { this.onSessionId = cb; }
    public void setOnTokens(Consumer<String> cb)    { this.onTokens = cb; }
    public void setOnRateLimit(Consumer<String> cb) { this.onRateLimit = cb; }
    /** Bridge state and, on the reply to a toggle, the conversation's web url. */
    public void setOnRemoteControl(Consumer<String> cb) { this.onRemoteControl = cb; }
    /** A message typed on another device, arriving over the bridge. */
    public void setOnRemoteMessage(Consumer<String> cb) { this.onRemoteMessage = cb; }
    /** Claude in Chrome's state for this conversation (the browser banner). */
    public void setOnBrowserState(Consumer<String> cb) { this.onBrowserState = cb; }
    /** A launch setting that changed on the live process — including from another
     *  device over Remote Control. See {@link NativeCore.ChatCallbacks#onSettingsChanged}. */
    public void setOnSettingsChanged(Consumer<String> cb) { this.onSettingsChanged = cb; }

    /** Takes the browser back out of this conversation — the banner's ×. Blocking only
     *  as long as a stdin write; returns whether it was on. */
    public boolean disableBrowser() {
        return NativeCore.chatDisableChrome(handle);
    }
    /** A running subagent's current step. See {@link NativeCore.ChatCallbacks#onAgentActivity}. */
    public void setOnAgentActivity(Consumer<String> cb) { this.onAgentActivity = cb; }
    /** A display-only note for the conversation that did not come from the CLI. */
    public void setOnNotice(Consumer<String> cb) { this.onNotice = cb; }
    /** The reply to an MCP servers window request. See {@link NativeCore.ChatCallbacks#onMcp}. */
    public void setOnMcp(Consumer<String> cb) { this.onMcp = cb; }
    /** The reply to a {@link #cliRequest}. See {@link NativeCore.ChatCallbacks#onCliReply}. */
    public void setOnCliReply(Consumer<String> cb) { this.onCliReply = cb; }
    /** Whether fast mode is on. See {@link NativeCore.ChatCallbacks#onFastMode}. */
    public void setOnFastMode(Consumer<String> cb) { this.onFastMode = cb; }

    /** Turns Remote Control on or off, starting this tab's process first if it
     *  has none.
     *
     *  <p>The CLI answers a control request before any turn has happened, so
     *  Remote Control does not need a conversation — only a process. Starting
     *  one here is what lets it be switched on in a tab nothing has been typed
     *  into yet.
     *
     *  <p><b>Blocking</b> — may spawn a child process. Call it off the UI
     *  thread. The reply, carrying the conversation's web url, arrives on the
     *  onRemoteControl callback rather than here.
     *
     *  @return false if no process could be started; nothing was sent. */
    public boolean remoteControl(boolean enabled, String resumeId, String permMode,
                                 String effort, String model, String thinking,
                                 boolean ultracode) {
        if (!ensureProcess(resumeId, permMode, effort, model, thinking, ultracode)) return false;
        return NativeCore.chatRemoteControl(handle, enabled);
    }

    /** Starts this tab's process if it has none, sending nothing. Launch
     *  settings are gathered exactly as {@link #sendMessage} gathers them, so
     *  both agree on what the tab's process is instead of one respawning what
     *  the other just started.
     *
     *  <p>Synchronized: two callers arriving while the process is still starting
     *  (the MCP servers window closed and reopened, Remote Control switched on
     *  meanwhile) would otherwise each spawn one, and the core keeps only the last. */
    public synchronized boolean ensureProcess(String resumeId, String permMode, String effort,
                                              String model, String thinking,
                                              boolean ultracode) {
        String claudeCmd = claudeCmd();
        String workspaceRoot = workspaceRoot();

        int mcpPort = 0;
        String mcpAuthToken = "";
        var server = Activator.getDefault().getHttpSseServer();
        if (server != null && server.isRunning()) {
            mcpPort = server.getPort();
            mcpAuthToken = server.getAuthToken();
        }

        return NativeCore.chatEnsureProcess(handle, claudeCmd, workspaceRoot, mcpPort, mcpAuthToken,
                resumeId == null ? "" : resumeId, permMode == null ? "" : permMode,
                effort == null ? "" : effort, model == null ? "" : model,
                thinking == null ? "" : thinking, ultracode);
    }
    /** (toolName, inputJson, rememberLabel) → "allow" | "allowRemember" | "deny" | "deny&lt;message&gt;". Persistent mode. */
    public void setOnPermissionRequest(PermissionHandler cb) {
        this.onPermissionRequest = cb;
    }
    /** (requestId, questionsJson) → answers array JSON ({@code [{header,question,answer}]}) or "[]". Persistent mode. */
    public void setOnQuestionRequest(java.util.function.BiFunction<String, String, String> cb) {
        this.onQuestionRequest = cb;
    }
    /** CLI request id of a card the CLI no longer wants an answer to. See
     *  {@link NativeCore.ChatCallbacks#onCardCancel}. Persistent mode. */
    public void setOnCardCancel(Consumer<String> cb) { this.onCardCancel = cb; }
    /** Per-turn GUI status snapshot JSON (model, context %, cost). Persistent mode. */
    public void setOnStatus(Consumer<String> cb) { this.onStatus = cb; }
    /** Compaction lifecycle JSON ({@code phase}: compacting/failed/boundary/summary). Persistent mode. */
    public void setOnCompact(Consumer<String> cb) { this.onCompact = cb; }

    /**
     * Opts this manager into the persistent-process protocol (one long-lived
     * claude per conversation, CLI-enforced permission cards). The deprecated
     * Claude Chat view never calls this and stays on spawn-per-message.
     */
    public void setPersistent(boolean persistent) {
        NativeCore.chatSetPersistent(handle, persistent);
    }

    /**
     * Overrides the directory claude is spawned in — the GUI's per-conversation
     * working root ("supertab"). Empty or null keeps the Eclipse workspace root.
     *
     * <p>Costs nothing below Java: the root has always been a parameter of
     * {@code chatSendMessage}, and the core does {@code cmd.current_dir(workspace_root)}
     * with whatever it is handed, so a per-tab root is just a different string.
     */
    public void setRoot(String root) {
        this.rootOverride = (root == null) ? "" : root.trim();
    }

    /** @see #setRoot(String) — empty until a caller sets one. */
    private volatile String rootOverride = "";


    // ── Operations ────────────────────────────────────────────────────────────

    public void sendMessage(String message) {
        sendMessage(message, "", "", "", "", "", false, "");
    }

    public void sendMessage(String message, String resumeId, String permMode, String effort,
                            String model, String thinking, boolean ultracode) {
        sendMessage(message, resumeId, permMode, effort, model, thinking, ultracode, "");
    }

    /**
     * Send a message.
     * @param resumeId session id to resume (empty = fresh session)
     * @param permMode claude permission mode: default|acceptEdits|plan|bypassPermissions (empty = claude default)
     * @param effort   claude effort level: low|medium|high|xhigh|max (empty = claude default)
     * @param model    claude model alias (sonnet|opus|haiku|sonnet[1m]|<custom>); empty = default
     * @param thinking "0" disables extended thinking; anything else leaves it to effort
     * @param ultracode orchestrates background subagents more aggressively (a preference)
     * @param imagesJson JSON array of {@code {media_type,data}} (base64) pasted images, or "" for none
     */
    public void sendMessage(String message, String resumeId, String permMode, String effort,
                            String model, String thinking, boolean ultracode,
                            String imagesJson) {
        String claudeCmd = claudeCmd();
        String workspaceRoot = workspaceRoot();

        int mcpPort = 0;
        String mcpAuthToken = "";
        var server = Activator.getDefault().getHttpSseServer();
        if (server != null && server.isRunning()) {
            mcpPort = server.getPort();
            mcpAuthToken = server.getAuthToken();
        }

        NativeCore.chatSendMessage(handle, message, claudeCmd, workspaceRoot, mcpPort, mcpAuthToken,
                resumeId == null ? "" : resumeId, permMode == null ? "" : permMode,
                effort == null ? "" : effort, model == null ? "" : model,
                thinking == null ? "" : thinking, ultracode,
                imagesJson == null ? "" : imagesJson);
    }

    public void cancel() {
        NativeCore.chatCancel(handle);
    }

    /**
     * Renames the conversation this manager's live process is on, via the CLI's
     * control channel (shared {@code custom-title}, visible to /resume and VSCode).
     * Returns false when this manager isn't live on {@code sessionId}.
     */
    public boolean renameSession(String sessionId, String title) {
        return NativeCore.chatRenameSession(handle, sessionId, title);
    }

    /** Stops one background agent by its own internal task id. See {@link NativeCore#chatStopTask}. */
    public boolean stopTask(String taskId) {
        return NativeCore.chatStopTask(handle, taskId);
    }

    /**
     * Applies a permission-mode change to this manager's live process (the GUI's
     * per-tab mode dropdown). No-op when the conversation hasn't started yet — the
     * mode is passed as a launch flag on the next spawn.
     */
    public boolean setPermissionMode(String mode) {
        return NativeCore.chatSetPermissionMode(handle, mode);
    }

    /**
     * The browser text blocks for {@code message}, as a JSON array — {@code []} when it
     * mentions no browser. Call after {@link #ensureProcess}, with the same launch
     * settings the send will carry. Blocking.
     */
    public String browserBlocks(String message) {
        String json = NativeCore.chatBrowserBlocks(handle, claudeCmd(), message);
        return json == null ? "[]" : json;
    }

    /** Sends one of the MCP servers window's requests to this tab's process,
     *  starting it first if it has none — the window lists what the conversation
     *  sees, so it needs a process, but never a conversation.
     *
     *  <p><b>Blocking</b> — may spawn a child process. Call it off the UI thread.
     *  The reply arrives on the onMcp callback under the same token.
     *
     *  @return false if no process could be started or the request was refused. */
    public boolean mcpRequest(String token, String requestJson, String resumeId, String permMode,
                              String effort, String model, String thinking,
                              boolean ultracode) {
        if (!ensureProcess(resumeId, permMode, effort, model, thinking, ultracode)) return false;
        return NativeCore.chatMcpRequest(handle, token, requestJson);
    }

    /** Adds or removes an MCP server via {@code claude mcp add|remove}, run in this
     *  tab's folder — the same one its process runs in, since a Local server is
     *  keyed on it. <b>Blocking</b>, up to 30s. See {@link NativeCore#mcpEditConfig}. */
    public String mcpEditConfig(String token, String opJson) {
        return NativeCore.mcpEditConfig(claudeCmd(), workspaceRoot(), token, opJson);
    }

    /** Asks this tab's process for what one of the CLI's dialogs shows, starting the
     *  process first if the tab has none, as {@link #mcpRequest} does. The reply
     *  arrives on the onCliReply callback under the same token.
     *
     *  @return false if no process could be started or the request was refused. */
    public boolean cliRequest(String token, String requestJson, String resumeId, String permMode,
                              String effort, String model, String thinking, boolean ultracode) {
        if (!ensureProcess(resumeId, permMode, effort, model, thinking, ultracode)) return false;
        return NativeCore.chatCliRequest(handle, token, requestJson);
    }

    /** What this tab's folder offers (slash commands, models, output styles), asked of
     *  a short-lived CLI rather than the tab's own process, so it is there before the
     *  tab has one. <b>Blocking</b>, up to 20s. See {@link NativeCore#cliFetchCommands}. */
    public String fetchCommands() {
        return NativeCore.cliFetchCommands(claudeCmd(), workspaceRoot());
    }

    /** Saves one change a dialog made with the CLI's own edit subcommand, run in this
     *  tab's folder. <b>Blocking</b>, up to 30s. See {@link NativeCore#cliEdit}. */
    public String cliEdit(String subcommand, String inputJson) {
        return NativeCore.cliEdit(claudeCmd(), workspaceRoot(), subcommand, inputJson);
    }

    /** One step of the Claude Design sign-in, run in this tab's folder. <b>Blocking</b>,
     *  up to six minutes for {@code wait}. See {@link NativeCore#cliDesignLogin}. */
    public String designLogin(String op, String arg) {
        return NativeCore.cliDesignLogin(claudeCmd(), workspaceRoot(), op, arg);
    }

    /** Sends this tab's process one of the dialogs' requests if it has a process, and
     *  starts none if it has not: for telling a running session of a setting that a
     *  session started later reads for itself.
     *
     *  @return false if there is no live process or the request was refused. */
    public boolean cliRequestIfRunning(String token, String requestJson) {
        return NativeCore.chatCliRequest(handle, token, requestJson);
    }

    /** Pushes the tab's launch settings to its live process now — see
     *  {@link NativeCore#chatApplySettings}. Non-blocking beyond a stdin write. */
    public boolean applySettings(String permMode, String effort, String model, String thinking,
                                 boolean ultracode) {
        return NativeCore.chatApplySettings(handle,
                permMode == null ? "" : permMode, effort == null ? "" : effort,
                model == null ? "" : model, thinking == null ? "" : thinking,
                ultracode);
    }

    /** Whether switching this conversation back to the default model would restart
     *  its process. See {@link NativeCore#chatDefaultModelRestarts}. */
    public boolean defaultModelRestarts() {
        return NativeCore.chatDefaultModelRestarts(handle);
    }

    public void resetSession() {
        NativeCore.chatResetSession(handle);
    }

    /**
     * Drops the live process without ending the conversation, so the next send
     * resumes it from the transcript on disk. Used after a message is deleted —
     * the running process still holds the deleted text in its own context.
     */
    public void restartProcess() {
        NativeCore.chatRestartProcess(handle);
    }

    public void stop() {
        cancel();
        NativeCore.chatDestroy(handle);
    }

    // ── Internal helpers ──────────────────────────────────────────────────────

    /** The configured CLI command, or the default when the preference is blank. */
    private static String claudeCmd() {
        IPreferenceStore prefs = Activator.getDefault().getPreferenceStore();
        String claudeCmd = prefs.getString(Constants.PREF_CLAUDE_CMD);
        return (claudeCmd == null || claudeCmd.isBlank()) ? Constants.DEFAULT_CLAUDE_CMD : claudeCmd;
    }

    /** The folder this tab's claude runs in: its root, or the Eclipse workspace root. */
    private String workspaceRoot() {
        return rootOverride.isEmpty()
                ? org.eclipse.core.resources.ResourcesPlugin.getWorkspace().getRoot().getLocation().toOSString()
                : rootOverride;
    }

    private static void emit(Runnable cb) {
        if (cb != null) {
            try { cb.run(); } catch (Exception ignored) {}
        }
    }

    private static void emit(Consumer<String> cb, String value) {
        if (cb != null) {
            try { cb.accept(value); } catch (Exception ignored) {}
        }
    }
}
