package com.anthropic.claudecode.eclipse.bridge;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

import com.anthropic.claudecode.eclipse.Activator;

/**
 * Runs the bundled {@code scripts/history.php} to read local session history
 * (list / load / delete). Pure local file + JSON work, kept as a PHP script so it
 * can be iterated without rebuilding the native core.
 *
 * <p>The php binary and script are extracted once and cached. Each call spawns a
 * short-lived php process and returns its stdout (JSON). On any failure it returns
 * an empty string so callers can fall back to the Rust implementation.
 */
public final class PhpHistory {

    private PhpHistory() {}

    private static volatile Path php;
    private static volatile Path script;

    private static synchronized void ensureExtracted() throws Exception {
        if (php == null)    php = PhpRuntime.resolveBinary();
        if (script == null) script = PhpRuntime.extractScript("/scripts/history.php");
    }

    /**
     * Pre-extract the php binary + script off the hot path. Safe to call from a
     * background thread at startup so the first history-button click doesn't pay the
     * (one-time, multi-MB) extraction cost synchronously.
     */
    public static void warmUp() {
        try { ensureExtracted(); }
        catch (Exception e) { Activator.logError("PhpHistory warm-up failed", e); }
    }

    /** Run an action ("list"|"load"|"delete"); returns stdout JSON, or "" on failure. */
    public static String run(String action, String workspaceRoot, String sessionId) {
        try {
            ensureExtracted();
            ProcessBuilder pb = new ProcessBuilder(
                    php.toAbsolutePath().toString(),
                    script.toAbsolutePath().toString(),
                    action,
                    workspaceRoot == null ? "" : workspaceRoot,
                    sessionId == null ? "" : sessionId);
            pb.redirectError(ProcessBuilder.Redirect.DISCARD); // keep stdout clean for JSON

            Process p = pb.start();
            byte[] out = p.getInputStream().readAllBytes();
            if (!p.waitFor(10, TimeUnit.SECONDS)) {
                p.destroyForcibly();
                return "";
            }
            return new String(out, StandardCharsets.UTF_8);
        } catch (Exception e) {
            Activator.logError("PhpHistory '" + action + "' failed", e);
            return "";
        }
    }
}
