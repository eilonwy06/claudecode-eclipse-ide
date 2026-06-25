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

    // ── Operations ────────────────────────────────────────────────────────────

    public void sendMessage(String message) {
        sendMessage(message, "", "", "", "", "");
    }

    /**
     * Send a message.
     * @param resumeId session id to resume (empty = fresh session)
     * @param permMode claude permission mode: default|acceptEdits|plan|bypassPermissions (empty = claude default)
     * @param effort   claude effort level: low|medium|high|xhigh|max (empty = claude default)
     * @param model    claude model alias (sonnet|opus|haiku|sonnet[1m]|<custom>); empty = default
     * @param thinking "0" disables extended thinking; anything else leaves it to effort
     */
    public void sendMessage(String message, String resumeId, String permMode, String effort,
                            String model, String thinking) {
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
                thinking == null ? "" : thinking);
    }

    public void cancel() {
        NativeCore.chatCancel(handle);
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
