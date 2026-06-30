package com.anthropic.claudecode.eclipse.ui;

import java.util.Optional;
import java.util.OptionalDouble;
import java.util.OptionalLong;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * Immutable snapshot of the Claude Code statusLine JSON
 * (<a href="https://code.claude.com/docs/en/statusline">schema</a>), holding only the
 * fields the status bar renders. Every field is optional and degrades gracefully: a
 * missing or {@code null} value yields an empty {@link Optional}/{@link OptionalDouble},
 * so the corresponding segment is simply hidden — never rendered as {@code null}/{@code NaN}.
 */
public final class ClaudeStatus {

    /** A single rate-limit window (5-hour or 7-day). */
    public static final class RateLimit {
        private final OptionalDouble usedPercentage;
        private final OptionalLong resetsAt; // Unix epoch seconds

        RateLimit(OptionalDouble usedPercentage, OptionalLong resetsAt) {
            this.usedPercentage = usedPercentage;
            this.resetsAt = resetsAt;
        }

        public OptionalDouble usedPercentage() { return usedPercentage; }
        public OptionalLong resetsAt() { return resetsAt; }
    }

    private final Optional<String> modelDisplayName;
    private final Optional<String> effortLevel;
    private final Optional<Boolean> thinkingEnabled;
    private final OptionalDouble totalCostUsd;
    private final OptionalDouble contextUsedPercentage;
    private final OptionalLong contextWindowSize;
    private final OptionalLong inputTokens;
    private final OptionalLong outputTokens;
    private final OptionalLong cacheCreationTokens;
    private final OptionalLong cacheReadTokens;
    private final Optional<RateLimit> fiveHour;
    private final Optional<RateLimit> sevenDay;

    private ClaudeStatus(Optional<String> modelDisplayName, Optional<String> effortLevel,
                         Optional<Boolean> thinkingEnabled, OptionalDouble totalCostUsd,
                         OptionalDouble contextUsedPercentage, OptionalLong contextWindowSize,
                         OptionalLong inputTokens, OptionalLong outputTokens,
                         OptionalLong cacheCreationTokens, OptionalLong cacheReadTokens,
                         Optional<RateLimit> fiveHour, Optional<RateLimit> sevenDay) {
        this.modelDisplayName = modelDisplayName;
        this.effortLevel = effortLevel;
        this.thinkingEnabled = thinkingEnabled;
        this.totalCostUsd = totalCostUsd;
        this.contextUsedPercentage = contextUsedPercentage;
        this.contextWindowSize = contextWindowSize;
        this.inputTokens = inputTokens;
        this.outputTokens = outputTokens;
        this.cacheCreationTokens = cacheCreationTokens;
        this.cacheReadTokens = cacheReadTokens;
        this.fiveHour = fiveHour;
        this.sevenDay = sevenDay;
    }

    public Optional<String> modelDisplayName() { return modelDisplayName; }
    public Optional<String> effortLevel() { return effortLevel; }

    /** {@code thinking.enabled}; empty when the field is absent. */
    public Optional<Boolean> thinkingEnabled() { return thinkingEnabled; }

    /** {@code cost.total_cost_usd} — accumulated session cost in USD; empty when absent. */
    public OptionalDouble totalCostUsd() { return totalCostUsd; }
    public OptionalDouble contextUsedPercentage() { return contextUsedPercentage; }
    public OptionalLong contextWindowSize() { return contextWindowSize; }

    /** Raw token breakdown from {@code context_window.current_usage}; empty before the first API call. */
    public OptionalLong inputTokens() { return inputTokens; }
    public OptionalLong outputTokens() { return outputTokens; }
    public OptionalLong cacheCreationTokens() { return cacheCreationTokens; }
    public OptionalLong cacheReadTokens() { return cacheReadTokens; }

    public Optional<RateLimit> fiveHour() { return fiveHour; }
    public Optional<RateLimit> sevenDay() { return sevenDay; }

    /**
     * Parses a statusLine JSON document. Returns {@code null} only if the input is not a
     * JSON object at all; any individual missing/null field is tolerated.
     */
    public static ClaudeStatus parse(String json) {
        if (json == null || json.isBlank()) return null;
        JsonElement root;
        try {
            root = JsonParser.parseString(json);
        } catch (RuntimeException e) {
            return null;
        }
        if (root == null || !root.isJsonObject()) return null;
        JsonObject obj = root.getAsJsonObject();

        JsonObject model = optObject(obj, "model");
        Optional<String> modelName = model != null
                ? optString(model, "display_name") : Optional.empty();

        JsonObject effort = optObject(obj, "effort");
        Optional<String> effortLevel = effort != null
                ? optString(effort, "level") : Optional.empty();

        JsonObject thinking = optObject(obj, "thinking");
        Optional<Boolean> thinkingEnabled = thinking != null
                ? optBoolean(thinking, "enabled") : Optional.empty();

        JsonObject cost = optObject(obj, "cost");
        OptionalDouble totalCostUsd = cost != null
                ? optDouble(cost, "total_cost_usd") : OptionalDouble.empty();

        JsonObject ctx = optObject(obj, "context_window");
        OptionalDouble contextPct = ctx != null
                ? optDouble(ctx, "used_percentage") : OptionalDouble.empty();
        OptionalLong contextSize = ctx != null
                ? optLong(ctx, "context_window_size") : OptionalLong.empty();

        JsonObject usage = ctx != null ? optObject(ctx, "current_usage") : null;
        OptionalLong inputTokens = usage != null
                ? optLong(usage, "input_tokens") : OptionalLong.empty();
        OptionalLong outputTokens = usage != null
                ? optLong(usage, "output_tokens") : OptionalLong.empty();
        OptionalLong cacheCreationTokens = usage != null
                ? optLong(usage, "cache_creation_input_tokens") : OptionalLong.empty();
        OptionalLong cacheReadTokens = usage != null
                ? optLong(usage, "cache_read_input_tokens") : OptionalLong.empty();

        JsonObject limits = optObject(obj, "rate_limits");
        Optional<RateLimit> fiveHour = limits != null
                ? parseRateLimit(optObject(limits, "five_hour")) : Optional.empty();
        Optional<RateLimit> sevenDay = limits != null
                ? parseRateLimit(optObject(limits, "seven_day")) : Optional.empty();

        return new ClaudeStatus(modelName, effortLevel, thinkingEnabled, totalCostUsd, contextPct, contextSize,
                inputTokens, outputTokens, cacheCreationTokens, cacheReadTokens, fiveHour, sevenDay);
    }

    private static Optional<RateLimit> parseRateLimit(JsonObject window) {
        if (window == null) return Optional.empty();
        OptionalDouble pct = optDouble(window, "used_percentage");
        OptionalLong resetsAt = optLong(window, "resets_at");
        // If a window object is present but carries no usable data, treat it as absent.
        if (pct.isEmpty() && resetsAt.isEmpty()) return Optional.empty();
        return Optional.of(new RateLimit(pct, resetsAt));
    }

    private static JsonObject optObject(JsonObject parent, String key) {
        JsonElement e = parent.get(key);
        return (e != null && e.isJsonObject()) ? e.getAsJsonObject() : null;
    }

    private static Optional<String> optString(JsonObject parent, String key) {
        JsonElement e = parent.get(key);
        if (e == null || e.isJsonNull() || !e.isJsonPrimitive()) return Optional.empty();
        String s = e.getAsString();
        return (s == null || s.isEmpty()) ? Optional.empty() : Optional.of(s);
    }

    private static Optional<Boolean> optBoolean(JsonObject parent, String key) {
        JsonElement e = parent.get(key);
        if (e == null || e.isJsonNull() || !e.isJsonPrimitive()) return Optional.empty();
        return Optional.of(e.getAsBoolean());
    }

    private static OptionalDouble optDouble(JsonObject parent, String key) {
        JsonElement e = parent.get(key);
        if (e == null || e.isJsonNull() || !e.isJsonPrimitive()) return OptionalDouble.empty();
        try {
            return OptionalDouble.of(e.getAsDouble());
        } catch (NumberFormatException ex) {
            return OptionalDouble.empty();
        }
    }

    private static OptionalLong optLong(JsonObject parent, String key) {
        JsonElement e = parent.get(key);
        if (e == null || e.isJsonNull() || !e.isJsonPrimitive()) return OptionalLong.empty();
        try {
            return OptionalLong.of(e.getAsLong());
        } catch (NumberFormatException ex) {
            return OptionalLong.empty();
        }
    }
}
