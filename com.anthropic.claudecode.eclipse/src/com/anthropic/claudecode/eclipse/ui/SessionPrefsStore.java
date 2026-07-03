package com.anthropic.claudecode.eclipse.ui;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;

import com.anthropic.claudecode.eclipse.Activator;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * Per-conversation composer settings the Claude GUI persists itself — effort,
 * model, and thinking — keyed by CLI session id. This is the piece VSCode has and
 * the CLI transcript lacks: the transcript records the model and thinking blocks
 * but NEVER the effort level (it's a request parameter, not logged), so the only
 * way to restore effort for a resumed conversation is to save it ourselves here.
 *
 * <p>Stored as {@code {sessionId: {effort, model, thinking}}} in the plugin's
 * state location. Bounded (oldest entries pruned) and written atomically; reads
 * tolerate a missing/corrupt file. UI-thread only (called from BrowserFunctions),
 * but methods are synchronized as defense-in-depth.
 */
public final class SessionPrefsStore {

    private SessionPrefsStore() {}

    private static final int MAX_ENTRIES = 500;
    private static final String FILE = "gui-session-prefs.json";

    private static Path file() {
        return Activator.getDefault().getStateLocation().append(FILE).toFile().toPath();
    }

    /** Records the composer settings for {@code sessionId}. No-op on any error. */
    public static synchronized void save(String sessionId, String effort, String model, String thinking) {
        if (sessionId == null || sessionId.isEmpty()) return;
        try {
            JsonObject root = read();
            root.remove(sessionId);            // re-insert at the end (freshest last)
            JsonObject e = new JsonObject();
            e.addProperty("effort", effort == null ? "" : effort);
            e.addProperty("model", model == null ? "" : model);
            e.addProperty("thinking", thinking == null ? "" : thinking);
            root.add(sessionId, e);
            prune(root);
            write(root);
        } catch (Throwable ignored) {}
    }

    /** Returns the stored {@code {effort,model,thinking}} for {@code sessionId}, or {@code "{}"}. */
    public static synchronized String load(String sessionId) {
        if (sessionId == null || sessionId.isEmpty()) return "{}";
        try {
            JsonObject root = read();
            if (root.has(sessionId) && root.get(sessionId).isJsonObject()) {
                return root.getAsJsonObject(sessionId).toString();
            }
        } catch (Throwable ignored) {}
        return "{}";
    }

    private static JsonObject read() {
        try {
            Path p = file();
            if (!Files.exists(p)) return new JsonObject();
            var el = JsonParser.parseString(Files.readString(p));
            return el.isJsonObject() ? el.getAsJsonObject() : new JsonObject();
        } catch (Throwable t) {
            return new JsonObject();   // tolerate a corrupt/partial file
        }
    }

    private static void write(JsonObject root) throws Exception {
        Path p = file();
        Files.createDirectories(p.getParent());
        Path tmp = p.resolveSibling(FILE + ".tmp");
        Files.write(tmp, root.toString().getBytes(StandardCharsets.UTF_8));
        try {
            Files.move(tmp, p, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (Exception atomicUnsupported) {
            Files.move(tmp, p, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    /** Keeps the map bounded by dropping the oldest (first-inserted) entries. */
    private static void prune(JsonObject root) {
        int over = root.size() - MAX_ENTRIES;
        if (over <= 0) return;
        List<String> keys = new ArrayList<>(root.keySet());
        for (int i = 0; i < over && i < keys.size(); i++) root.remove(keys.get(i));
    }
}
