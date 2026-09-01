package com.anthropic.claudecode.eclipse.ui;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;

import org.eclipse.jface.preference.IPreferenceStore;

import com.anthropic.claudecode.eclipse.Activator;
import com.anthropic.claudecode.eclipse.Constants;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * Which folders Claude Code may be started in, for the GUI's working-root ("supertab")
 * feature.
 *
 * <p>The CLI asks "do you trust the files in this folder?" only in its interactive
 * TUI; a {@code --print}/stream-json run in a folder it has never seen starts with no
 * prompt at all (verified). So the gate has to be the plugin's own — which is the point
 * of the trust window: opening a Claude Code tab in a new folder no longer means
 * opening a terminal first just to answer the CLI's question.
 *
 * <p>The answer is recorded in TWO places, and either one is enough to count as
 * trusted:
 * <ul>
 *   <li>the plugin preference {@link Constants#PREF_TRUSTED_ROOTS} — our own record,
 *       which always succeeds and does not depend on the CLI's config schema; and</li>
 *   <li>the CLI's {@code ~/.claude.json → projects[path].hasTrustDialogAccepted}, so
 *       one grant covers the Claude Terminal too and neither surface asks twice.</li>
 * </ul>
 *
 * <p>The mirror is best-effort and never blocks the grant. Two things about it are
 * worth knowing:
 * <ul>
 *   <li>It is a whole-file read-modify-write, because JSON has no partial update. The
 *       CLI rewrites the same file after every turn ({@code lastCost},
 *       {@code lastSessionId}, {@code lastDuration}, …), so a CLI write landing between
 *       our read and our write is lost. The write happens once per folder, on a button
 *       click, so the window is milliseconds and the worst case is one turn's stats.</li>
 *   <li>The Gson settings are not cosmetic. Default Gson drops null-valued keys — 43 of
 *       them in a real config, including {@code oauthAccount.seatTier} and
 *       {@code workspaceRole} — and escapes {@code '}, {@code &}, {@code <}, {@code >}.
 *       With {@code serializeNulls} + {@code setPrettyPrinting} +
 *       {@code disableHtmlEscaping} the rewrite is byte-identical to the original apart
 *       from the key we add (verified against an 89KB config, 27 projects).</li>
 * </ul>
 */
public final class TrustStore {

    private TrustStore() {}

    /** True once the user has agreed to run Claude in {@code path} — here or in the CLI. */
    public static boolean isTrusted(String path) {
        if (path == null || path.isBlank()) return false;
        String want = normalize(path);
        for (String p : trustedRoots()) {
            if (normalize(p).equals(want)) return true;
        }
        return cliTrusts(want);
    }

    /**
     * Records the user's "yes" from the trust window, in our preference and — best
     * effort — in the CLI's own config so the Claude Terminal doesn't ask again.
     * Idempotent.
     */
    public static void trust(String path) {
        if (path == null || path.isBlank()) return;
        String want = normalize(path);
        List<String> roots = trustedRoots();
        boolean known = false;
        for (String p : roots) {
            if (normalize(p).equals(want)) { known = true; break; }
        }
        if (!known) {
            roots.add(path);
            store().setValue(Constants.PREF_TRUSTED_ROOTS, String.join("\n", roots));
        }
        mirrorToCliConfig(path);
    }

    private static IPreferenceStore store() {
        return Activator.getDefault().getPreferenceStore();
    }

    private static List<String> trustedRoots() {
        List<String> out = new ArrayList<>();
        String raw = store().getString(Constants.PREF_TRUSTED_ROOTS);
        if (raw == null || raw.isEmpty()) return out;
        for (String line : raw.split("\n")) {
            if (!line.isBlank()) out.add(line.trim());
        }
        return out;
    }

    /**
     * The CLI's answer for this folder, read out of {@code ~/.claude.json}. Its
     * {@code projects} keys are absolute paths written with forward slashes, which
     * {@link #normalize} already produces on both sides.
     */
    private static boolean cliTrusts(String normalizedPath) {
        try {
            Path cfg = claudeJson();
            if (cfg == null || !Files.exists(cfg)) return false;
            JsonObject root = JsonParser.parseString(Files.readString(cfg)).getAsJsonObject();
            JsonObject entry = findProject(root, normalizedPath);
            return entry != null && entry.has("hasTrustDialogAccepted")
                    && entry.get("hasTrustDialogAccepted").getAsBoolean();
        } catch (Exception ignored) {
            // Unreadable or unexpected config just means "no answer on file" — the
            // trust window asks, which is the safe direction to fail in.
        }
        return false;
    }

    /**
     * Sets {@code hasTrustDialogAccepted} on the CLI's entry for this folder, creating
     * the entry if the CLI has never seen it. Skipped entirely when the config does not
     * exist (the CLI has never run — inventing one on its behalf would be presumptuous)
     * or when the flag is already set (no write, no race).
     */
    private static void mirrorToCliConfig(String path) {
        try {
            Path cfg = claudeJson();
            if (cfg == null || !Files.exists(cfg)) return;

            // Read as late as possible: every millisecond between this and the write
            // below is a window in which a running claude's own write would be lost.
            JsonObject root = JsonParser.parseString(Files.readString(cfg)).getAsJsonObject();
            if (!root.has("projects") || !root.get("projects").isJsonObject()) return;
            JsonObject projects = root.getAsJsonObject("projects");

            JsonObject entry = findProject(root, normalize(path));
            if (entry != null) {
                if (entry.has("hasTrustDialogAccepted")
                        && entry.get("hasTrustDialogAccepted").getAsBoolean()) return;  // already trusted
                // Mutated in place, so the entry keeps its position and its other keys.
                entry.addProperty("hasTrustDialogAccepted", true);
            } else {
                JsonObject fresh = new JsonObject();
                fresh.addProperty("hasTrustDialogAccepted", true);
                projects.add(cliKey(path), fresh);
            }

            String out = cliGson().toJson(root);
            Path tmp = cfg.resolveSibling(cfg.getFileName() + ".claude-eclipse.tmp");
            Files.writeString(tmp, out);
            try {
                Files.move(tmp, cfg, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (Exception atomicUnsupported) {
                Files.move(tmp, cfg, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (Exception e) {
            // Best effort by design: the preference already records the grant, so the
            // GUI honours it either way. Only the Terminal's own prompt is affected.
            Activator.logError("Could not mirror folder trust into ~/.claude.json", e);
        }
    }

    /** The {@code projects} entry whose key matches, or {@code null}. */
    private static JsonObject findProject(JsonObject root, String normalizedPath) {
        if (!root.has("projects") || !root.get("projects").isJsonObject()) return null;
        JsonObject projects = root.getAsJsonObject("projects");
        for (String key : projects.keySet()) {
            if (!normalize(key).equals(normalizedPath)) continue;
            return projects.get(key).isJsonObject() ? projects.getAsJsonObject(key) : null;
        }
        return null;
    }

    /**
     * Gson configured to leave everything it did not come to change alone.
     * {@code serializeNulls} keeps null-valued keys the default silently drops;
     * {@code disableHtmlEscaping} keeps apostrophes and angle brackets as themselves;
     * {@code setPrettyPrinting} matches the CLI's own 2-space layout.
     */
    private static Gson cliGson() {
        return new GsonBuilder().serializeNulls().setPrettyPrinting().disableHtmlEscaping().create();
    }

    private static Path claudeJson() {
        String home = Activator.isWindows() ? System.getenv("USERPROFILE") : System.getenv("HOME");
        if (home == null || home.isEmpty()) home = System.getProperty("user.home");
        return home == null ? null : Paths.get(home, ".claude.json");
    }

    /** A new key in the CLI's own style: absolute, forward slashes, original case. */
    static String cliKey(String path) {
        String s = path.replace('\\', '/').trim();
        while (s.length() > 1 && s.endsWith("/")) s = s.substring(0, s.length() - 1);
        return s;
    }

    /**
     * One normal form for every comparison: forward slashes, no trailing separator,
     * lower-cased on Windows (where paths are case-insensitive and the same folder
     * reaches us as {@code C:\} from Eclipse and {@code C:/} from the CLI's config).
     */
    static String normalize(String path) {
        if (path == null) return "";
        String s = cliKey(path);
        return Activator.isWindows() ? s.toLowerCase() : s;
    }
}
