package com.anthropic.claudecode.eclipse.ui;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import com.google.gson.JsonObject;

/**
 * Runs the CLI's own updater ({@code claude update}) on the user's behalf.
 *
 * <p>Deliberately delegates to the CLI rather than picking a package manager: the
 * binary ships via npm, the native installer, and Homebrew, and {@code claude
 * update} already knows which one installed <em>it</em>. Running e.g.
 * {@code npm i -g @anthropic-ai/claude-code} against a native install would leave
 * two different binaries on PATH.
 *
 * <p>This mutates the user's system, so it is only ever invoked from an explicit
 * user action (the update button in the Claude Code view) — never automatically,
 * and never as a side effect of the version check.
 */
public final class CliUpdateService {

    private CliUpdateService() {}

    /** An update run is slow (download + install); don't hang forever either. */
    private static final int TIMEOUT_MINUTES = 10;

    /** Result of an update attempt: {@code {ok, output}}. */
    public static final class Result {
        public final boolean ok;
        /** Combined stdout+stderr, trimmed — shown back to the user verbatim. */
        public final String output;

        Result(boolean ok, String output) {
            this.ok = ok;
            this.output = output;
        }

        public String toJson() {
            JsonObject o = new JsonObject();
            o.addProperty("ok", ok);
            o.addProperty("output", output);
            return o.toString();
        }
    }

    /**
     * Runs {@code claude update} on a background thread and reports the outcome.
     * On success the cached version info is invalidated so the next check reflects
     * the new build.
     *
     * @param claudeCmd the configured CLI command (falls back to {@code claude})
     */
    public static void updateAsync(String claudeCmd, Consumer<Result> cb) {
        String raw = (claudeCmd == null || claudeCmd.isBlank())
                ? com.anthropic.claudecode.eclipse.Constants.DEFAULT_CLAUDE_CMD : claudeCmd;
        // Same PATHEXT caveat as the version check: CreateProcess won't find
        // `claude.cmd` from the bare name "claude".
        final String cmd = CliVersionService.resolveOnPath(raw);
        Thread t = new Thread(() -> {
            Result r;
            try {
                ProcessBuilder pb = new ProcessBuilder(cmd, "update");
                pb.redirectErrorStream(true);
                Process p = pb.start();
                String out;
                try (InputStream in = p.getInputStream()) {
                    out = new String(in.readAllBytes(), StandardCharsets.UTF_8);
                }
                boolean done = p.waitFor(TIMEOUT_MINUTES, TimeUnit.MINUTES);
                if (!done) {
                    p.destroyForcibly();
                    r = new Result(false, "Update timed out after " + TIMEOUT_MINUTES + " minutes.");
                } else {
                    r = new Result(p.exitValue() == 0, out.trim());
                }
            } catch (Throwable ex) {
                r = new Result(false, String.valueOf(ex.getMessage()));
            }
            if (r.ok) CliVersionService.invalidate();
            try { cb.accept(r); } catch (Throwable ignored) {}
        }, "claude-cli-update");
        t.setDaemon(true);
        t.start();
    }
}
