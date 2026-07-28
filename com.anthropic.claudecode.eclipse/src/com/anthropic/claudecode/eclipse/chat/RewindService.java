package com.anthropic.claudecode.eclipse.chat;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * Checkpoint rewind for the Claude GUI — the Eclipse counterpart of VSCode's
 * "Rewind to…" dialog.
 *
 * <p>The CLI checkpoints files per user message: each session transcript
 * ({@code ~/.claude/projects/<hash>/<sessionId>.jsonl}) carries
 * {@code file-history-snapshot} entries whose {@code trackedFileBackups} map a
 * workspace-relative path to a backup blob in
 * {@code ~/.claude/file-history/<sessionId>/}. The snapshot recorded for user
 * message M holds each tracked file's content <em>as of M</em> (a
 * {@code backupFileName} of {@code null} means the file did not exist yet), so
 * rewinding to M is: write every tracked backup back, delete null-backup files.
 *
 * <p>Forking mirrors the CLI's own approach: copy the transcript lines that
 * precede M into a fresh session id (rewriting the {@code sessionId} field) so
 * the forked conversation can be resumed with {@code --resume <newId>}, and
 * carry the referenced backup blobs over so the forked session can itself be
 * rewound later.
 */
public final class RewindService {

    private RewindService() {}

    // ── Public API (returns JSON strings for the JS bridge) ─────────────────

    /** Typed user messages of a session, in order: {@code [{id,text,ts}]}. */
    public static String list(String workspaceRoot, String sessionId) {
        JsonArray out = new JsonArray();
        try {
            for (JsonObject line : readSession(workspaceRoot, sessionId)) {
                JsonObject m = typedUserMessage(line);
                if (m != null) out.add(m);
            }
        } catch (Throwable ignored) {}
        return out.toString();
    }

    /**
     * What rewinding to {@code messageId} would change on disk:
     * {@code {files:[{path,adds,dels}]}} — {@code dels} lines vanish from the
     * current file, {@code adds} lines come back from the checkpoint.
     */
    public static String preview(String workspaceRoot, String sessionId, String messageId) {
        JsonObject out = new JsonObject();
        JsonArray files = new JsonArray();
        out.add("files", files);
        try {
            if (snapshotFor(workspaceRoot, sessionId, messageId) == null) {
                // The CLI never checkpointed this message (it predates checkpointing
                // being enabled) — distinct from "checkpoint matches disk".
                out.addProperty("noCheckpoint", true);
                return out.toString();
            }
            for (Restore r : restorePlan(workspaceRoot, sessionId, messageId)) {
                JsonObject f = new JsonObject();
                f.addProperty("path", r.relPath);
                f.addProperty("adds", r.adds);
                f.addProperty("dels", r.dels);
                files.add(f);
            }
        } catch (Throwable t) {
            out.addProperty("error", String.valueOf(t.getMessage()));
        }
        return out.toString();
    }

    /**
     * Restores the checkpointed files and forks the conversation before
     * {@code messageId}. Returns {@code {sessionId:<forked id or "">, prompt:<the
     * selected message's text>}} — an empty forked id means the rewind point was
     * the first message (nothing to fork; the caller starts a fresh tab).
     */
    public static String apply(String workspaceRoot, String sessionId, String messageId) {
        JsonObject out = new JsonObject();
        try {
            // 1. Put the code back the way it was at the selected message.
            for (Restore r : restorePlan(workspaceRoot, sessionId, messageId)) {
                if (r.backup == null) {
                    Files.deleteIfExists(r.absPath);
                } else {
                    if (r.absPath.getParent() != null) Files.createDirectories(r.absPath.getParent());
                    Files.copy(r.backup, r.absPath, StandardCopyOption.REPLACE_EXISTING);
                }
                refreshWorkspaceFile(r.absPath);
            }

            // 2. Fork: transcript lines BEFORE the selected message become a new
            //    session; the message itself goes back to the composer instead.
            List<String> raw = readSessionRaw(workspaceRoot, sessionId);
            int cut = -1;
            String prompt = "";
            List<String> kept = new ArrayList<>();
            List<String> blobs = new ArrayList<>();
            for (int i = 0; i < raw.size(); i++) {
                JsonObject line = parse(raw.get(i));
                if (line == null) { if (cut < 0) kept.add(raw.get(i)); continue; }
                if (cut < 0 && isCutPoint(line, messageId)) cut = i;
                if (cut < 0) {
                    kept.add(raw.get(i));
                    collectBlobs(line, blobs);
                }
                if (prompt.isEmpty()) {
                    JsonObject m = typedUserMessage(line);
                    if (m != null && messageId.equals(m.get("id").getAsString()))
                        prompt = m.get("text").getAsString();
                }
            }
            String newId = "";
            if (cut > 0) {
                newId = UUID.randomUUID().toString();
                Path forked = sessionFile(workspaceRoot, newId);
                List<String> rewritten = new ArrayList<>(kept.size());
                for (String l : kept)
                    rewritten.add(l.replace("\"sessionId\":\"" + sessionId + "\"",
                                            "\"sessionId\":\"" + newId + "\""));
                Files.write(forked, rewritten, StandardCharsets.UTF_8);
                copyBackupBlobs(sessionId, newId, blobs);
                copySessionPrefs(sessionId, newId);
            }
            out.addProperty("sessionId", newId);
            out.addProperty("prompt", prompt);
        } catch (Throwable t) {
            out.addProperty("error", String.valueOf(t.getMessage()));
        }
        return out.toString();
    }

    // ── Restore plan ─────────────────────────────────────────────────────────

    private static final class Restore {
        final String relPath;
        final Path absPath;
        final Path backup;    // null = file did not exist at the checkpoint → delete
        final int adds, dels;
        Restore(String relPath, Path absPath, Path backup, int adds, int dels) {
            this.relPath = relPath; this.absPath = absPath; this.backup = backup;
            this.adds = adds; this.dels = dels;
        }
    }

    /**
     * Every file's backup representing its content at {@code messageId}, or null
     * if that message was never checkpointed.
     *
     * <p>A file enters the CLI's registry at its FIRST edit: the pre-edit backup
     * is back-filled into the snapshot of the message whose turn edited it, and
     * carried into every later snapshot (version-rolled when it changes again).
     * So the state at M is M's own snapshot PLUS, for files not tracked there
     * yet, the entry from the first later snapshot that tracks them — that
     * backup is pre-first-edit content, i.e. still what the file held at M.
     * Without the forward merge, rewinding to the message that ASKED for an
     * edit (rather than the one whose turn performed it) restores nothing.
     */
    private static JsonObject snapshotFor(String workspaceRoot, String sessionId,
                                          String messageId) throws Exception {
        // First-appearance order == message order (the initial snapshot line
        // precedes its user message); the LAST registry per message wins
        // (mid-turn updates rewrite the same snapshot with more files).
        java.util.LinkedHashMap<String, JsonObject> snaps = new java.util.LinkedHashMap<>();
        for (JsonObject line : readSession(workspaceRoot, sessionId)) {
            if (!"file-history-snapshot".equals(str(line, "type"))) continue;
            JsonObject snap = obj(line, "snapshot");
            if (snap == null) continue;
            String mid = str(snap, "messageId");
            JsonObject tb = obj(snap, "trackedFileBackups");
            if (!mid.isEmpty() && tb != null) snaps.put(mid, tb);
        }
        if (!snaps.containsKey(messageId)) return null;
        JsonObject merged = new JsonObject();
        boolean reached = false;
        for (Map.Entry<String, JsonObject> s : snaps.entrySet()) {
            if (!reached) {
                if (!s.getKey().equals(messageId)) continue;
                reached = true;
            }
            for (Map.Entry<String, JsonElement> f : s.getValue().entrySet()) {
                if (!merged.has(f.getKey())) merged.add(f.getKey(), f.getValue());
            }
        }
        return merged;
    }

    /** Files whose on-disk content differs from the checkpoint at {@code messageId}. */
    private static List<Restore> restorePlan(String workspaceRoot, String sessionId,
                                             String messageId) throws Exception {
        List<Restore> plan = new ArrayList<>();
        JsonObject registry = snapshotFor(workspaceRoot, sessionId, messageId);
        if (registry == null) return plan;

        Path historyDir = claudeHome().resolve("file-history").resolve(sessionId);
        for (Map.Entry<String, JsonElement> e : registry.entrySet()) {
            if (!e.getValue().isJsonObject()) continue;
            JsonObject entry = e.getValue().getAsJsonObject();
            String rel = e.getKey();
            Path abs = resolvePath(workspaceRoot, rel);

            Path backup = null;
            byte[] target = null;   // null = file should not exist at the checkpoint
            JsonElement bf = entry.get("backupFileName");
            if (bf != null && !bf.isJsonNull()) {
                String name = bf.getAsString();
                if (name.contains("/") || name.contains("\\") || name.contains("..")) continue;
                backup = historyDir.resolve(name);
                if (!Files.isRegularFile(backup)) continue;   // blob missing — leave the file alone
                target = Files.readAllBytes(backup);
            }
            byte[] current = Files.isRegularFile(abs) ? Files.readAllBytes(abs) : null;

            if (java.util.Arrays.equals(current, target)) continue;              // already at checkpoint
            if (current == null && target == null) continue;
            int[] counts = diffCounts(text(current), text(target));
            plan.add(new Restore(rel, abs, backup, counts[0], counts[1]));
        }
        return plan;
    }

    /** {adds, dels} turning {@code current} into {@code target} (guarded line LCS). */
    private static int[] diffCounts(String current, String target) {
        String[] a = current.isEmpty() ? new String[0] : current.split("\n", -1);
        String[] b = target.isEmpty() ? new String[0] : target.split("\n", -1);
        int lo = 0, ahi = a.length, bhi = b.length;
        while (lo < ahi && lo < bhi && a[lo].equals(b[lo])) lo++;
        while (ahi > lo && bhi > lo && a[ahi - 1].equals(b[bhi - 1])) { ahi--; bhi--; }
        int n = ahi - lo, m = bhi - lo;
        if (n == 0 || m == 0 || (long) n * m > 1_000_000L)
            return new int[] { m, n };   // too big for LCS — count the whole middle
        int[] prev = new int[m + 1], cur = new int[m + 1];
        for (int i = n - 1; i >= 0; i--) {
            for (int j = m - 1; j >= 0; j--) {
                cur[j] = a[lo + i].equals(b[lo + j]) ? prev[j + 1] + 1
                                                     : Math.max(prev[j], cur[j + 1]);
            }
            int[] t = prev; prev = cur; cur = t;
            java.util.Arrays.fill(cur, 0);
        }
        int common = prev[0];
        return new int[] { m - common, n - common };
    }

    private static String text(byte[] bytes) {
        return bytes == null ? "" : new String(bytes, StandardCharsets.UTF_8).replace("\r\n", "\n");
    }

    // ── Fork helpers ─────────────────────────────────────────────────────────

    /** The fork keeps everything before the selected message — cutting at its
     *  own snapshot line (which the CLI writes just ahead of the message). */
    private static boolean isCutPoint(JsonObject line, String messageId) {
        String type = str(line, "type");
        if ("user".equals(type) && messageId.equals(str(line, "uuid"))) return true;
        if ("file-history-snapshot".equals(type) && messageId.equals(str(line, "messageId"))
                && !bool(line, "isSnapshotUpdate")) return true;
        return false;
    }

    private static void collectBlobs(JsonObject line, List<String> blobs) {
        if (!"file-history-snapshot".equals(str(line, "type"))) return;
        JsonObject snap = obj(line, "snapshot");
        JsonObject tb = snap == null ? null : obj(snap, "trackedFileBackups");
        if (tb == null) return;
        for (Map.Entry<String, JsonElement> e : tb.entrySet()) {
            if (!e.getValue().isJsonObject()) continue;
            JsonElement bf = e.getValue().getAsJsonObject().get("backupFileName");
            if (bf != null && !bf.isJsonNull()) blobs.add(bf.getAsString());
        }
    }

    /** Backup blobs referenced by the kept transcript move with the fork so the
     *  forked session can be rewound too. */
    private static void copyBackupBlobs(String oldId, String newId, List<String> blobs) {
        try {
            Path oldDir = claudeHome().resolve("file-history").resolve(oldId);
            Path newDir = claudeHome().resolve("file-history").resolve(newId);
            boolean made = false;
            for (String name : new java.util.LinkedHashSet<>(blobs)) {
                if (name.contains("/") || name.contains("\\") || name.contains("..")) continue;
                Path src = oldDir.resolve(name);
                if (!Files.isRegularFile(src)) continue;
                if (!made) { Files.createDirectories(newDir); made = true; }
                Files.copy(src, newDir.resolve(name), StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (Throwable ignored) {}
    }

    /** Carry the composer sidecar (effort/model/thinking/permission mode) to the
     *  forked session. */
    private static void copySessionPrefs(String oldId, String newId) {
        try {
            JsonObject p = JsonParser.parseString(
                    com.anthropic.claudecode.eclipse.ui.SessionPrefsStore.load(oldId)).getAsJsonObject();
            if (p.size() == 0) return;
            com.anthropic.claudecode.eclipse.ui.SessionPrefsStore.save(newId,
                    str(p, "effort"), str(p, "model"), str(p, "thinking"), str(p, "permMode"));
        } catch (Throwable ignored) {}
    }

    // ── Transcript parsing ───────────────────────────────────────────────────

    /** A typed user message as {@code {id,text,ts}}, or null for anything else
     *  (tool results, system-injected notifications, meta, sidechains). */
    private static JsonObject typedUserMessage(JsonObject line) {
        if (!"user".equals(str(line, "type"))) return null;
        if (bool(line, "isMeta") || bool(line, "isSidechain") || bool(line, "isCompactSummary")) return null;
        if (line.has("origin") && line.get("origin").isJsonObject()) return null;   // e.g. task notifications
        if ("system".equals(str(line, "promptSource"))) return null;
        JsonObject msg = obj(line, "message");
        if (msg == null || !"user".equals(str(msg, "role"))) return null;
        JsonElement content = msg.get("content");
        if (content == null || !content.isJsonPrimitive()) return null;             // arrays = tool_result turns
        String uuid = str(line, "uuid");
        if (uuid.isEmpty()) return null;
        JsonObject out = new JsonObject();
        out.addProperty("id", uuid);
        out.addProperty("text", content.getAsString());
        out.addProperty("ts", str(line, "timestamp"));
        return out;
    }

    private static List<JsonObject> readSession(String workspaceRoot, String sessionId) throws Exception {
        List<JsonObject> out = new ArrayList<>();
        for (String raw : readSessionRaw(workspaceRoot, sessionId)) {
            JsonObject o = parse(raw);
            if (o != null) out.add(o);
        }
        return out;
    }

    private static List<String> readSessionRaw(String workspaceRoot, String sessionId) throws Exception {
        Path p = sessionFile(workspaceRoot, sessionId);
        if (!Files.isRegularFile(p)) throw new Exception("Session transcript not found.");
        return Files.readAllLines(p, StandardCharsets.UTF_8);
    }

    private static Path sessionFile(String workspaceRoot, String sessionId) throws Exception {
        if (sessionId == null || sessionId.isEmpty() || sessionId.contains("/")
                || sessionId.contains("\\") || sessionId.contains(".."))
            throw new Exception("Bad session id.");
        return claudeHome().resolve("projects").resolve(projectHash(workspaceRoot))
                .resolve(sessionId + ".jsonl");
    }

    private static Path claudeHome() {
        String home = System.getenv(isWindows() ? "USERPROFILE" : "HOME");
        if (home == null || home.isEmpty()) home = System.getProperty("user.home");
        return Paths.get(home, ".claude");
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT).contains("win");
    }

    /** Same algorithm as session.rs: every non-ASCII-alphanumeric char becomes '-'. */
    private static String projectHash(String root) {
        StringBuilder sb = new StringBuilder(root.length());
        for (int i = 0; i < root.length(); i++) {
            char c = root.charAt(i);
            boolean alnum = (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9');
            sb.append(alnum ? c : '-');
        }
        return sb.toString();
    }

    /** Tracked-backup keys are relative to the session cwd (the workspace root). */
    private static Path resolvePath(String workspaceRoot, String rel) {
        Path p = Paths.get(rel);
        return p.isAbsolute() ? p : Paths.get(workspaceRoot).resolve(p);
    }

    private static void refreshWorkspaceFile(Path abs) {
        try {
            var file = org.eclipse.core.resources.ResourcesPlugin.getWorkspace().getRoot()
                    .getFileForLocation(org.eclipse.core.runtime.Path.fromOSString(abs.toString()));
            if (file != null)
                file.refreshLocal(org.eclipse.core.resources.IResource.DEPTH_ZERO, null);
        } catch (Throwable ignored) {}
    }

    private static JsonObject parse(String raw) {
        try {
            JsonElement e = JsonParser.parseString(raw);
            return e.isJsonObject() ? e.getAsJsonObject() : null;
        } catch (Throwable t) {
            return null;
        }
    }

    private static String str(JsonObject o, String k) {
        JsonElement e = o.get(k);
        return e != null && e.isJsonPrimitive() ? e.getAsString() : "";
    }

    private static boolean bool(JsonObject o, String k) {
        JsonElement e = o.get(k);
        return e != null && e.isJsonPrimitive() && e.getAsJsonPrimitive().isBoolean() && e.getAsBoolean();
    }

    private static JsonObject obj(JsonObject o, String k) {
        JsonElement e = o.get(k);
        return e != null && e.isJsonObject() ? e.getAsJsonObject() : null;
    }
}
