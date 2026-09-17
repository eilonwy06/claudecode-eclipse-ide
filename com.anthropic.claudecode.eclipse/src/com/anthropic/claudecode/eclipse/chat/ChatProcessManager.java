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
                                 String effort, String model, String thinking) {
        if (!ensureProcess(resumeId, permMode, effort, model, thinking)) return false;
        return NativeCore.chatRemoteControl(handle, enabled);
    }

    /** Starts this tab's process if it has none, sending nothing. Launch
     *  settings are gathered exactly as {@link #sendMessage} gathers them, so
     *  both agree on what the tab's process is instead of one respawning what
     *  the other just started. */
    public boolean ensureProcess(String resumeId, String permMode, String effort,
                                 String model, String thinking) {
        IPreferenceStore prefs = Activator.getDefault().getPreferenceStore();
        String claudeCmd = prefs.getString(Constants.PREF_CLAUDE_CMD);
        if (claudeCmd == null || claudeCmd.isBlank()) claudeCmd = Constants.DEFAULT_CLAUDE_CMD;

        String workspaceRoot = rootOverride.isEmpty()
                ? org.eclipse.core.resources.ResourcesPlugin.getWorkspace().getRoot().getLocation().toOSString()
                : rootOverride;

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
                thinking == null ? "" : thinking);
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
        sendMessage(message, "", "", "", "", "");
    }

    public void sendMessage(String message, String resumeId, String permMode, String effort,
                            String model, String thinking) {
        sendMessage(message, resumeId, permMode, effort, model, thinking, "");
    }

    /**
     * Send a message.
     * @param resumeId session id to resume (empty = fresh session)
     * @param permMode claude permission mode: default|acceptEdits|plan|bypassPermissions (empty = claude default)
     * @param effort   claude effort level: low|medium|high|xhigh|max (empty = claude default)
     * @param model    claude model alias (sonnet|opus|haiku|sonnet[1m]|<custom>); empty = default
     * @param thinking "0" disables extended thinking; anything else leaves it to effort
     * @param imagesJson JSON array of {@code {media_type,data}} (base64) pasted images, or "" for none
     */
    public void sendMessage(String message, String resumeId, String permMode, String effort,
                            String model, String thinking, String imagesJson) {
        IPreferenceStore prefs = Activator.getDefault().getPreferenceStore();
        String claudeCmd = prefs.getString(Constants.PREF_CLAUDE_CMD);
        if (claudeCmd == null || claudeCmd.isBlank()) claudeCmd = Constants.DEFAULT_CLAUDE_CMD;

        String workspaceRoot = rootOverride.isEmpty()
                ? org.eclipse.core.resources.ResourcesPlugin.getWorkspace().getRoot().getLocation().toOSString()
                : rootOverride;

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
                thinking == null ? "" : thinking, imagesJson == null ? "" : imagesJson);
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
        IPreferenceStore prefs = Activator.getDefault().getPreferenceStore();
        String claudeCmd = prefs.getString(Constants.PREF_CLAUDE_CMD);
        if (claudeCmd == null || claudeCmd.isBlank()) claudeCmd = Constants.DEFAULT_CLAUDE_CMD;
        String json = NativeCore.chatBrowserBlocks(handle, claudeCmd, message);
        return json == null ? "[]" : json;
    }

    /** Pushes the tab's launch settings to its live process now — see
     *  {@link NativeCore#chatApplySettings}. Non-blocking beyond a stdin write. */
    public boolean applySettings(String permMode, String effort, String model, String thinking) {
        return NativeCore.chatApplySettings(handle,
                permMode == null ? "" : permMode, effort == null ? "" : effort,
                model == null ? "" : model, thinking == null ? "" : thinking);
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
