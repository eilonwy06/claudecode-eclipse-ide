package com.anthropic.claudecode.eclipse.ui;

import com.google.gson.JsonObject;

/**
 * Process-wide cache of the <em>account-global</em> status data — the 5-hour and
 * weekly subscription usage limits — so the Claude GUI can show the exact same
 * numbers as the CLI status bar. There is one account, so these limits are
 * identical everywhere; caching the latest observed value here is the single
 * source of truth both surfaces read from.
 *
 * <p>The only producer is the CLI statusLine (Claude does not emit usage
 * percentages in the GUI's headless {@code -p} stream). The data is fed in via a
 * non-invasive decorator around the status-callback registration, so the
 * contributor's {@code StatusBridge}/{@code ClaudeStatusBar}/{@code ClaudeStatus}
 * classes are never touched. It reuses {@link ClaudeStatus#parse} read-only.
 *
 * <p>Thread-safe: a single {@code volatile} reference to an immutable snapshot.
 */
public final class ClaudeStatusStore {

    private ClaudeStatusStore() {}

    private static volatile ClaudeStatus latest;

    /**
     * Accepts a raw statusLine JSON document (from the CLI channel), parses it,
     * and retains it if it carries usable rate-limit data. Never throws — a bad
     * document is ignored so it can never disturb the CLI status routing that
     * runs alongside this.
     */
    public static void acceptStatusLine(String statusJson) {
        try {
            ClaudeStatus s = ClaudeStatus.parse(statusJson);
            if (s == null) return;
            // Only keep snapshots that actually advance the account-global data;
            // otherwise a context-only update would wipe the last known limits.
            if (s.fiveHour().isPresent() || s.sevenDay().isPresent()) {
                latest = merge(latest, s);
            }
        } catch (Exception ignored) {}
    }

    /**
     * Folds a new snapshot over the previous one <em>per window</em>, carrying a
     * previously known {@code resets_at} forward when the incoming window omits
     * it.
     *
     * <p>The two producers carry different halves of the data. The CLI
     * statusLine supplies percentage <em>and</em> reset epoch; the Claude GUI's
     * {@code /usage} probe supplies percentage only (that command reports reset
     * times as localized prose, which is deliberately not parsed). A plain
     * last-writer-wins would make the countdowns of a user running both views
     * flicker away every time the GUI refreshed. Percentages always come from
     * the newer snapshot — only a missing reset time is inherited.
     */
    private static ClaudeStatus merge(ClaudeStatus prev, ClaudeStatus next) {
        if (prev == null) return next;
        JsonObject root = new JsonObject();
        JsonObject limits = new JsonObject();
        mergeWindow(limits, "five_hour", prev.fiveHour(), next.fiveHour());
        mergeWindow(limits, "seven_day", prev.sevenDay(), next.sevenDay());
        root.add("rate_limits", limits);
        ClaudeStatus merged = ClaudeStatus.parse(root.toString());
        return merged != null ? merged : next;
    }

    private static void mergeWindow(JsonObject out, String key,
                                    java.util.Optional<ClaudeStatus.RateLimit> prev,
                                    java.util.Optional<ClaudeStatus.RateLimit> next) {
        // Nothing new for this window: keep whatever we already knew.
        if (next.isEmpty() || next.get().usedPercentage().isEmpty()) {
            addWindow(out, key, prev);
            return;
        }
        ClaudeStatus.RateLimit n = next.get();
        JsonObject w = new JsonObject();
        w.addProperty("used_percentage", n.usedPercentage().getAsDouble());
        if (n.resetsAt().isPresent()) {
            w.addProperty("resets_at", n.resetsAt().getAsLong());
        } else if (prev.isPresent() && prev.get().resetsAt().isPresent()) {
            // Inherit the epoch the statusLine gave us so the countdown survives.
            w.addProperty("resets_at", prev.get().resetsAt().getAsLong());
        }
        out.add(key, w);
    }

    /**
     * The latest account-global rate limits as a statusLine-schema fragment
     * ({@code {"five_hour":{"used_percentage":..,"resets_at":..},"seven_day":{..}}}),
     * ready to drop into the synthetic status document the Claude GUI feeds to the
     * shared {@link ClaudeStatusBar}. Absent windows are omitted; empty object when
     * nothing is known yet.
     */
    public static JsonObject rateLimitsSchema() {
        ClaudeStatus s = latest;
        JsonObject out = new JsonObject();
        if (s != null) {
            addWindow(out, "five_hour", s.fiveHour());
            addWindow(out, "seven_day", s.sevenDay());
        }
        return out;
    }

    private static void addWindow(JsonObject out, String key,
                                  java.util.Optional<ClaudeStatus.RateLimit> window) {
        if (window.isEmpty()) return;
        ClaudeStatus.RateLimit rl = window.get();
        if (rl.usedPercentage().isEmpty()) return;
        JsonObject w = new JsonObject();
        w.addProperty("used_percentage", rl.usedPercentage().getAsDouble());
        if (rl.resetsAt().isPresent()) w.addProperty("resets_at", rl.resetsAt().getAsLong());
        out.add(key, w);
    }
}
