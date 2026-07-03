package com.anthropic.claudecode.eclipse.ui;

import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Consumer;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * Fetches the account's actually-available models from the Anthropic API
 * ({@code GET /v1/models}) so the Claude GUI model chooser is dynamic rather than
 * a hardcoded list. Curated to the latest of each family (Fable / Opus / Sonnet /
 * Haiku) — the CLI aliases (e.g. {@code opus}) already resolve to the latest, so
 * those are used as the {@code --model} values.
 *
 * <p>Auth is the OAuth token the CLI already stores in
 * {@code ~/.claude/.credentials.json}; the call is read-only. Everything runs off
 * the UI thread and degrades to a sensible built-in list on any failure (offline,
 * expired token, etc.), so the chooser always works.
 */
public final class ModelCatalog {

    private ModelCatalog() {}

    private static final String MODELS_URL = "https://api.anthropic.com/v1/models?limit=100";

    /** Families we surface, in display order. */
    private static final String[] FAMILY_ORDER = { "fable", "opus", "sonnet", "haiku" };

    private static String descFor(String family) {
        switch (family) {
            case "fable":  return "Latest flagship model";
            case "opus":   return "Most capable — best for complex tasks";
            case "sonnet": return "Balanced capability and speed";
            case "haiku":  return "Fastest for quick answers";
            default:        return "";
        }
    }

    /** Runs the fetch+curation on a background thread, then delivers the model-list
     *  JSON (never null) to {@code cb}. */
    public static void fetchCuratedAsync(Consumer<String> cb) {
        Thread t = new Thread(() -> {
            String json;
            try { json = fetchAndCurate(); }
            catch (Throwable ex) { json = fallbackJson(); }
            if (json == null || json.isBlank()) json = fallbackJson();
            try { cb.accept(json); } catch (Throwable ignored) {}
        }, "claude-models-fetch");
        t.setDaemon(true);
        t.start();
    }

    private static String fetchAndCurate() throws Exception {
        String token = readOAuthToken();
        if (token == null || token.isEmpty()) return fallbackJson();

        HttpURLConnection conn = (HttpURLConnection) URI.create(MODELS_URL).toURL().openConnection();
        conn.setRequestMethod("GET");
        conn.setConnectTimeout(4000);
        conn.setReadTimeout(4000);
        conn.setRequestProperty("Authorization", "Bearer " + token);
        conn.setRequestProperty("anthropic-version", "2023-06-01");
        int code = conn.getResponseCode();
        if (code != 200) return fallbackJson();

        String body;
        try (InputStream in = conn.getInputStream()) {
            body = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
        JsonObject root = JsonParser.parseString(body).getAsJsonObject();
        JsonArray data = root.getAsJsonArray("data");
        if (data == null) return fallbackJson();

        // Pick the latest model per family by numeric version comparison.
        Map<String, Best> best = new LinkedHashMap<>();
        for (var el : data) {
            if (!el.isJsonObject()) continue;
            JsonObject m = el.getAsJsonObject();
            String id = str(m, "id");
            if (id == null) continue;
            String display = str(m, "display_name");
            String core = id.replaceFirst("^claude-", "").replaceFirst("-\\d{8}$", "");
            String[] parts = core.split("-");
            if (parts.length == 0) continue;
            String family = parts[0];
            int[] ver = new int[Math.max(0, parts.length - 1)];
            for (int i = 1; i < parts.length; i++) ver[i - 1] = parseIntSafe(parts[i]);
            Best cur = best.get(family);
            if (cur == null || cmpVer(ver, cur.ver) > 0) {
                best.put(family, new Best(id, display != null ? display : id, ver));
            }
        }

        // Display order: the known families first (in our preferred order), then
        // any OTHER families the API returns — so a brand-new family name that
        // Anthropic ships later still appears automatically (fully dynamic), not
        // just new versions of existing families.
        java.util.List<String> ordered = new java.util.ArrayList<>();
        for (String fam : FAMILY_ORDER) if (best.containsKey(fam)) ordered.add(fam);
        best.keySet().stream()
            .filter(f -> !contains(FAMILY_ORDER, f))
            .sorted()
            .forEach(ordered::add);

        JsonArray out = new JsonArray();
        out.add(model("", "Default (recommended)", "Latest model · efficient for routine tasks"));
        for (String fam : ordered) {
            Best b = best.get(fam);
            boolean known = contains(FAMILY_ORDER, fam);
            // Known family → the stable CLI alias (auto-resolves to the latest, so
            // it self-updates). New/unknown family → the full model id, which the
            // CLI always accepts as --model even without a documented alias.
            String modelId = known ? fam : b.id;
            out.add(model(modelId, shortLabel(b.display), known ? descFor(fam) : "", b.id));
            if (fam.equals("sonnet")) {
                out.add(model("sonnet[1m]", shortLabel(b.display) + " (1M context)",
                        "1M-token context · draws from usage credits", b.id));
            }
        }
        return ordered.isEmpty() ? fallbackJson() : out.toString();
    }

    /** "Claude Opus 4.8" → "Opus 4.8". */
    private static String shortLabel(String display) {
        if (display == null) return "";
        return display.replaceFirst("^Claude ", "");
    }

    private static JsonObject model(String id, String label, String desc) {
        return model(id, label, desc, "");
    }

    /** {@code fullId} is the concrete model id (e.g. claude-sonnet-5) behind an alias,
     *  used for the "Switched to …" divider. */
    private static JsonObject model(String id, String label, String desc, String fullId) {
        JsonObject o = new JsonObject();
        o.addProperty("id", id);
        o.addProperty("label", label);
        o.addProperty("desc", desc);
        o.addProperty("fullId", fullId == null ? "" : fullId);
        return o;
    }

    /** Built-in list used when the API is unreachable — Fable enabled (it is available). */
    private static String fallbackJson() {
        JsonArray out = new JsonArray();
        out.add(model("", "Default (recommended)", "Latest model · efficient for routine tasks"));
        out.add(model("fable", "Fable", descFor("fable")));
        out.add(model("opus", "Opus", descFor("opus")));
        out.add(model("sonnet", "Sonnet", descFor("sonnet")));
        out.add(model("sonnet[1m]", "Sonnet (1M context)", "1M-token context · draws from usage credits"));
        out.add(model("haiku", "Haiku", descFor("haiku")));
        return out.toString();
    }

    private static String readOAuthToken() {
        try {
            Path p = Paths.get(System.getProperty("user.home"), ".claude", ".credentials.json");
            if (!Files.exists(p)) return null;
            JsonObject root = JsonParser.parseString(Files.readString(p)).getAsJsonObject();
            if (!root.has("claudeAiOauth") || !root.get("claudeAiOauth").isJsonObject()) return null;
            return str(root.getAsJsonObject("claudeAiOauth"), "accessToken");
        } catch (Throwable t) {
            return null;
        }
    }

    private static String str(JsonObject o, String k) {
        return (o.has(k) && o.get(k).isJsonPrimitive()) ? o.get(k).getAsString() : null;
    }

    private static int parseIntSafe(String s) {
        try { return Integer.parseInt(s); } catch (NumberFormatException e) { return 0; }
    }

    private static boolean contains(String[] arr, String v) {
        for (String s : arr) if (s.equals(v)) return true;
        return false;
    }

    private static int cmpVer(int[] a, int[] b) {
        int n = Math.max(a.length, b.length);
        for (int i = 0; i < n; i++) {
            int x = i < a.length ? a[i] : 0, y = i < b.length ? b[i] : 0;
            if (x != y) return Integer.compare(x, y);
        }
        return 0;
    }

    private static final class Best {
        final String id; final String display; final int[] ver;
        Best(String id, String display, int[] ver) { this.id = id; this.display = display; this.ver = ver; }
    }
}
