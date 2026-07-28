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
    private java.util.function.Function<String, String> onQuestionRequest;
    private Consumer<String> onStatus;
    private Consumer<String> onCompact;

    /** (toolName, inputJson, rememberLabel) → decision string. See {@link NativeCore.ChatCallbacks#onPermissionRequest}. */
    public interface PermissionHandler {
        String handle(String toolName, String inputJson, String rememberLabel);
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
            @Override public String onPermissionRequest(String toolName, String inputJson, String rememberLabel) {
                var h = ChatProcessManager.this.onPermissionRequest;
                if (h == null) return "deny";
                try { return h.handle(toolName, inputJson, rememberLabel); } catch (Exception e) { return "deny"; }
            }
            @Override public String onQuestionRequest(String questionsJson) {
                var h = ChatProcessManager.this.onQuestionRequest;
                if (h == null) return "[]";
                try { return h.apply(questionsJson); } catch (Exception e) { return "[]"; }
            }
            @Override public void onStatus(String json) { emit(ChatProcessManager.this.onStatus, json); }
            @Override public void onCompact(String json) { emit(ChatProcessManager.this.onCompact, json); }
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
    /** (toolName, inputJson, rememberLabel) → "allow" | "allowRemember" | "deny" | "deny&lt;message&gt;". Persistent mode. */
    public void setOnPermissionRequest(PermissionHandler cb) {
        this.onPermissionRequest = cb;
    }
    /** questionsJson → answers array JSON ({@code [{header,question,answer}]}) or "[]". Persistent mode. */
    public void setOnQuestionRequest(java.util.function.Function<String, String> cb) {
        this.onQuestionRequest = cb;
    }
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

        String workspaceRoot = org.eclipse.core.resources.ResourcesPlugin
                .getWorkspace().getRoot().getLocation().toOSString();

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

    /**
     * Applies a permission-mode change to this manager's live process (the GUI's
     * per-tab mode dropdown). No-op when the conversation hasn't started yet — the
     * mode is passed as a launch flag on the next spawn.
     */
    public boolean setPermissionMode(String mode) {
        return NativeCore.chatSetPermissionMode(handle, mode);
    }

    public void resetSession() {
        NativeCore.chatResetSession(handle);
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
