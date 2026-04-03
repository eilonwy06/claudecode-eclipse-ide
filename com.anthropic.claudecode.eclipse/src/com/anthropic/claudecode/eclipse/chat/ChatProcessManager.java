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

    public ChatProcessManager() {
        this.handle = NativeCore.chatCreate();
        NativeCore.chatRegisterCallbacks(handle, new NativeCore.ChatCallbacks() {
            @Override public void onStreamStart()          { emit(ChatProcessManager.this.onStreamStart); }
            @Override public void onText(String t)         { emit(ChatProcessManager.this.onText, t); }
            @Override public void onToolStart(String name) { emit(ChatProcessManager.this.onToolStart, name); }
            @Override public void onStreamEnd()            { emit(ChatProcessManager.this.onStreamEnd); }
            @Override public void onError(String msg)      { emit(ChatProcessManager.this.onError, msg); }
            @Override public void onSystem(String msg)     { emit(ChatProcessManager.this.onSystem, msg); }
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

    // ── Operations ────────────────────────────────────────────────────────────

    public void sendMessage(String message) {
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

        NativeCore.chatSendMessage(handle, message, claudeCmd, workspaceRoot, mcpPort, mcpAuthToken);
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
