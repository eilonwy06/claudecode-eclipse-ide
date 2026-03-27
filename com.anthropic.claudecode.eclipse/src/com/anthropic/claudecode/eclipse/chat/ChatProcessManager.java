package com.anthropic.claudecode.eclipse.chat;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.function.Consumer;

import org.eclipse.jface.preference.IPreferenceStore;

import com.anthropic.claudecode.eclipse.Activator;
import com.anthropic.claudecode.eclipse.Constants;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * Manages Claude CLI for the chat panel. Launches one process per user message
 * using -p (print mode) with --output-format stream-json for structured output.
 * Session continuity is maintained via -c (continue last session).
 *
 * This matches the documented CLI behavior exactly — no undocumented stdin formats.
 */
public class ChatProcessManager {

    private Process currentProcess;
    private volatile boolean cancelled = false;
    private volatile boolean awaitingResponse = false;
    private boolean hasSession = false; // true after first message completes

    private Consumer<String> onText;
    private Consumer<String> onToolStart;
    private Consumer<String> onToolEnd;
    private Runnable onStreamStart;
    private Runnable onStreamEnd;
    private Consumer<String> onError;
    private Consumer<String> onSystem;

    public void setOnText(Consumer<String> cb) { this.onText = cb; }
    public void setOnToolStart(Consumer<String> cb) { this.onToolStart = cb; }
    public void setOnToolEnd(Consumer<String> cb) { this.onToolEnd = cb; }
    public void setOnStreamStart(Runnable cb) { this.onStreamStart = cb; }
    public void setOnStreamEnd(Runnable cb) { this.onStreamEnd = cb; }
    public void setOnError(Consumer<String> cb) { this.onError = cb; }
    public void setOnSystem(Consumer<String> cb) { this.onSystem = cb; }

    public boolean isAwaitingResponse() { return awaitingResponse; }

    public void sendMessage(String message) {
        if (awaitingResponse) return;
        cancelled = false;
        awaitingResponse = true;

        Thread worker = new Thread(() -> runTurn(message), "claude-chat-turn");
        worker.setDaemon(true);
        worker.start();
    }

    public void cancel() {
        cancelled = true;
        awaitingResponse = false;
        if (currentProcess != null && currentProcess.isAlive()) {
            currentProcess.destroyForcibly();
        }
    }

    public void resetSession() {
        cancel();
        hasSession = false;
        emit(onSystem, "Session reset.");
    }

    public void stop() {
        cancel();
        hasSession = false;
    }

    private void runTurn(String message) {
        try {
            IPreferenceStore prefs = Activator.getDefault().getPreferenceStore();
            String claudeCmd = prefs.getString(Constants.PREF_CLAUDE_CMD);
            if (claudeCmd == null || claudeCmd.isBlank()) claudeCmd = Constants.DEFAULT_CLAUDE_CMD;

            ArrayList<String> cmd = new ArrayList<>();
            cmd.add(claudeCmd);
            cmd.add("-p");
            cmd.add(message);
            cmd.add("--output-format");
            cmd.add("stream-json");
            cmd.add("--verbose");
            cmd.add("--include-partial-messages");

            // Continue previous session for multi-turn conversation
            if (hasSession) {
                cmd.add("-c");
            }

            ProcessBuilder pb = new ProcessBuilder(cmd);
            pb.redirectErrorStream(false);

            // IDE env vars for MCP server
            var env = pb.environment();
            var server = Activator.getDefault().getHttpSseServer();
            if (server != null && server.isRunning()) {
                env.put("CLAUDE_IDE_PORT", String.valueOf(server.getPort()));
                env.put("CLAUDE_IDE_AUTH_TOKEN", server.getAuthToken());
                env.put("CLAUDE_IDE_NAME", Constants.IDE_NAME);
            }

            // Working directory = workspace root
            String workspaceRoot = org.eclipse.core.resources.ResourcesPlugin
                    .getWorkspace().getRoot().getLocation().toOSString();
            pb.directory(new java.io.File(workspaceRoot));

            Activator.log("Chat: launching turn: " + cmd.subList(0, Math.min(5, cmd.size())));
            if (onStreamStart != null) onStreamStart.run();

            currentProcess = pb.start();

            // Read stderr in background
            Thread stderrThread = new Thread(() -> drainStderr(), "claude-chat-stderr");
            stderrThread.setDaemon(true);
            stderrThread.start();

            // Read stdout NDJSON events
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(currentProcess.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while (!cancelled && (line = reader.readLine()) != null) {
                    if (line.isBlank()) continue;
                    try {
                        processEvent(line);
                    } catch (Exception e) {
                        Activator.logError("Chat: parse error: " + line, e);
                    }
                }
            }

            int exit = currentProcess.waitFor();
            if (exit == 0) {
                hasSession = true; // Mark session active for -c on next turn
            } else if (!cancelled) {
                Activator.log("Chat: claude exited with code " + exit);
                emit(onError, "Claude exited with code " + exit);
            }

        } catch (IOException e) {
            Activator.logError("Chat: failed to launch Claude", e);
            emit(onError, "Failed to launch Claude: " + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            currentProcess = null;
            awaitingResponse = false;
            if (onStreamEnd != null) onStreamEnd.run();
        }
    }

    private void drainStderr() {
        if (currentProcess == null) return;
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(currentProcess.getErrorStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (!line.isBlank()) {
                    Activator.log("Chat stderr: " + line);
                }
            }
        } catch (IOException ignored) {}
    }

    // --- Event processing ---
    private void processEvent(String jsonLine) {
        JsonObject event = JsonParser.parseString(jsonLine).getAsJsonObject();
        String type = event.has("type") ? event.get("type").getAsString() : "";

        switch (type) {
            case "system" -> handleSystemEvent(event);
            case "assistant" -> handleAssistantEvent(event);
            case "result" -> handleResultEvent(event);
            case "stream_event" -> handleStreamEvent(event);
        }
    }

    private void handleSystemEvent(JsonObject event) {
        if (event.has("subtype")) {
            String subtype = event.get("subtype").getAsString();
            if ("init".equals(subtype)) {
                String msg = event.has("message") ? event.get("message").getAsString() : "Connected";
                emit(onSystem, msg);
            }
        }
    }

    private void handleAssistantEvent(JsonObject event) {
        // This is a complete message snapshot — text is already delivered via stream_event/content_block_delta.
        // Do not emit text here to avoid doubling the response.
    }

    private void handleResultEvent(JsonObject event) {
        // Turn complete
    }

    private void handleStreamEvent(JsonObject event) {
        if (!event.has("event")) return;
        JsonObject inner = event.getAsJsonObject("event");
        String eventType = inner.has("type") ? inner.get("type").getAsString() : "";

        switch (eventType) {
            case "content_block_delta" -> {
                if (!inner.has("delta")) return;
                JsonObject delta = inner.getAsJsonObject("delta");
                String deltaType = delta.has("type") ? delta.get("type").getAsString() : "";
                if ("text_delta".equals(deltaType) && delta.has("text")) {
                    emit(onText, delta.get("text").getAsString());
                }
            }
            case "content_block_start" -> {
                if (!inner.has("content_block")) return;
                JsonObject block = inner.getAsJsonObject("content_block");
                String blockType = block.has("type") ? block.get("type").getAsString() : "";
                if ("tool_use".equals(blockType)) {
                    String toolName = block.has("name") ? block.get("name").getAsString() : "tool";
                    emit(onToolStart, toolName);
                }
            }
            case "content_block_stop" -> {
                // Tool done — could track by index
            }
        }
    }

    private void emit(Consumer<String> cb, String val) {
        if (cb != null) cb.accept(val);
    }
}
