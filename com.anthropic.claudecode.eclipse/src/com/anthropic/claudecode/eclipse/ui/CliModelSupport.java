package com.anthropic.claudecode.eclipse.ui;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Consumer;

import com.google.gson.JsonObject;

/**
 * Determines which models the INSTALLED Claude Code binary can actually run, by
 * reading the model ids compiled into it.
 *
 * <p>{@link ModelCatalog} asks the API what the <em>account</em> may use, which
 * says nothing about the binary: model aliases ({@code opus}, {@code sonnet}) are
 * resolved <em>inside</em> the CLI, so a binary older than a model release quietly
 * resolves the alias to the newest model it knows instead of erroring. The chooser
 * would then offer "Opus 5" while the CLI actually runs Opus 4.8.
 *
 * <p>The version number is NOT a reliable substitute. A failed self-update can
 * leave {@code claude --version} reporting a build whose model payload is older
 * (observed: {@code --version} said 2.1.220 while the binary contained no
 * {@code claude-opus-5} at all, after an {@code update_apply_restore_failed}).
 * The compiled-in ids are the ground truth.
 *
 * <p>Scanning is a plain byte search for {@code claude-} over a ~265 MB file —
 * measured at about a second, and cached on the binary's identity (path + size +
 * mtime) so it only re-runs when the CLI actually changes. Everything happens off
 * the UI thread and degrades to "unknown" (empty result) on any failure, in which
 * case the chooser keeps its existing account-derived behaviour.
 */
public final class CliModelSupport {

    private CliModelSupport() {}

    /** Families we care about; anything else in the binary is ignored. */
    private static final String[] FAMILIES = { "fable", "opus", "sonnet", "haiku" };

    /**
     * Undocumented CLI flags we probe for. These do NOT appear in {@code --help},
     * so the binary's own strings are the only way to know whether passing one is
     * safe: an unknown option makes the CLI exit immediately ("error: unknown
     * option"), which would take the whole chat down rather than degrade. Scanned
     * here so the check rides along with the model scan instead of re-reading a
     * ~265MB file.
     */
    private static final String[] PROBE_FLAGS = { "--thinking-display" };

    /** Below this a path is a launcher shim, not the real binary. */
    private static final long MIN_BINARY_BYTES = 10L * 1024 * 1024;

    private static final int CHUNK = 4 * 1024 * 1024;
    /** Longest id we'll accept, so chunk overlap can't split one. */
    private static final int MAX_ID = 64;

    private static volatile String cachedKey;
    private static volatile String cachedJson;

    /**
     * Resolves what the binary supports, then hands the result to {@code cb} as
     * <code>{"latest":{"opus":"claude-opus-4-8",…},"all":["claude-opus-4-5",…]}</code>.
     *
     * <p>{@code latest} drives the alias labels (what "opus" will really run);
     * {@code all} is every id in the binary, so a concrete model pinned via the
     * plugin's {@code --model} argument can be checked before it's used rather
     * than failing at send time.
     *
     * <p>Delivers {@code "{}"} when the binary can't be found or read — never throws.
     */
    public static void scanAsync(String claudeCmd, Consumer<String> cb) {
        Thread t = new Thread(() -> {
            String json = "{}";
            try { json = scan(claudeCmd); } catch (Throwable ignored) {}
            try { cb.accept(json); } catch (Throwable ignored) {}
        }, "claude-model-support");
        t.setDaemon(true);
        t.start();
    }

    private static String scan(String claudeCmd) {
        Path bin = locateBinary(claudeCmd);
        if (bin == null) return "{}";
        String key;
        try {
            key = bin.toString() + '|' + Files.size(bin) + '|' + Files.getLastModifiedTime(bin).toMillis();
        } catch (IOException e) {
            return "{}";
        }
        if (key.equals(cachedKey) && cachedJson != null) return cachedJson;

        Map<String, int[]> best = new LinkedHashMap<>();
        Map<String, String> bestId = new LinkedHashMap<>();
        java.util.Set<String> allIds = new java.util.TreeSet<>();
        java.util.Set<String> flags = new java.util.TreeSet<>();
        try (InputStream in = Files.newInputStream(bin)) {
            byte[] buf = new byte[CHUNK + MAX_ID];
            int carry = 0;
            int n;
            while ((n = in.read(buf, carry, CHUNK)) > 0) {
                int end = carry + n;
                collect(buf, end, best, bestId, allIds);
                collectFlags(buf, end, flags);
                // Keep the tail so an id straddling the boundary still matches.
                carry = Math.min(MAX_ID, end);
                System.arraycopy(buf, end - carry, buf, 0, carry);
            }
        } catch (IOException e) {
            return "{}";
        }
        if (bestId.isEmpty()) return "{}";

        JsonObject latest = new JsonObject();
        for (String fam : FAMILIES) {
            String id = bestId.get(fam);
            if (id != null) latest.addProperty(fam, id);
        }
        com.google.gson.JsonArray all = new com.google.gson.JsonArray();
        for (String id : allIds) all.add(id);
        com.google.gson.JsonArray flagArr = new com.google.gson.JsonArray();
        for (String f : flags) flagArr.add(f);
        JsonObject out = new JsonObject();
        out.add("latest", latest);
        out.add("all", all);
        out.add("flags", flagArr);
        String json = out.toString();
        cachedKey = key;
        cachedJson = json;
        return json;
    }

    /** Scans one buffer for {@code claude-<family>-<numeric-version>} ids. */
    private static void collect(byte[] buf, int end, Map<String, int[]> best, Map<String, String> bestId,
                                java.util.Set<String> allIds) {
        final byte[] needle = { 'c', 'l', 'a', 'u', 'd', 'e', '-' };
        outer:
        for (int i = 0; i + needle.length < end; i++) {
            for (int k = 0; k < needle.length; k++) {
                if (buf[i + k] != needle[k]) continue outer;
            }
            int p = i + needle.length;
            int start = p;
            // family: lowercase letters
            while (p < end && buf[p] >= 'a' && buf[p] <= 'z') p++;
            if (p == start || p >= end || buf[p] != '-') continue;
            String family = new String(buf, start, p - start, StandardCharsets.US_ASCII);
            if (!isFamily(family)) continue;
            // version: dash-separated numbers
            int[] ver = new int[8];
            int nv = 0, idEnd = p, fullEnd = 0;
            boolean dated = false;
            while (p < end && nv < ver.length) {
                if (buf[p] != '-') break;
                int ds = ++p;
                while (p < end && buf[p] >= '0' && buf[p] <= '9') p++;
                if (p == ds) break;                       // "-" not followed by digits
                long v = 0;
                for (int q = ds; q < p; q++) v = v * 10 + (buf[q] - '0');
                // An 8-digit tail is a release date (…-20251101), not a version:
                // it ends the id but must not dominate the version comparison.
                if (p - ds == 8) { idEnd = ds - 1; fullEnd = p; dated = true; break; }
                ver[nv++] = (int) Math.min(v, Integer.MAX_VALUE);
                idEnd = p;
            }
            if (nv == 0) continue;
            if (!dated) fullEnd = idEnd;
            // Reject ids that continue into letters (claude-opus-4-6-fast, …).
            if (fullEnd < end && (isAlpha(buf[fullEnd]) || (buf[fullEnd] == '-' && fullEnd + 1 < end && isAlpha(buf[fullEnd + 1])))) {
                continue;
            }
            String base = new String(buf, i, idEnd - i, StandardCharsets.US_ASCII);
            allIds.add(base);
            // Keep the dated spelling too — a pinned --model may name it exactly.
            if (dated) allIds.add(new String(buf, i, fullEnd - i, StandardCharsets.US_ASCII));
            int[] cur = best.get(family);
            if (cur == null || cmp(ver, cur) > 0) {
                best.put(family, ver.clone());
                bestId.put(family, base);
            }
        }
    }

    /**
     * Scans one buffer for the literal {@link #PROBE_FLAGS} strings. A flag is
     * only reported when the following byte can't extend it into a longer option
     * name, so {@code --thinking-display} isn't claimed by a hypothetical
     * {@code --thinking-display-mode}.
     */
    private static void collectFlags(byte[] buf, int end, java.util.Set<String> found) {
        for (String flag : PROBE_FLAGS) {
            if (found.contains(flag)) continue;               // already seen in an earlier chunk
            byte[] needle = flag.getBytes(StandardCharsets.US_ASCII);
            outer:
            for (int i = 0; i + needle.length <= end; i++) {
                for (int k = 0; k < needle.length; k++) {
                    if (buf[i + k] != needle[k]) continue outer;
                }
                int after = i + needle.length;
                if (after < end && (isAlpha(buf[after]) || buf[after] == '-')) continue;
                found.add(flag);
                break;
            }
        }
    }

    private static boolean isAlpha(byte b) { return (b >= 'a' && b <= 'z') || (b >= 'A' && b <= 'Z'); }

    private static boolean isFamily(String s) {
        for (String f : FAMILIES) if (f.equals(s)) return true;
        return false;
    }

    private static int cmp(int[] a, int[] b) {
        for (int i = 0; i < a.length; i++) {
            if (a[i] != b[i]) return Integer.compare(a[i], b[i]);
        }
        return 0;
    }

    /**
     * Finds the real executable. {@code claude} on PATH is usually a launcher shim
     * ({@code claude.cmd}), so the npm layout beside it is tried as well; anything
     * too small to be the binary is rejected.
     */
    private static Path locateBinary(String claudeCmd) {
        String resolved = CliVersionService.resolveOnPath(
                (claudeCmd == null || claudeCmd.isBlank())
                        ? com.anthropic.claudecode.eclipse.Constants.DEFAULT_CLAUDE_CMD : claudeCmd);
        Path direct = safePath(resolved);
        if (isBinary(direct)) return direct;
        if (direct == null) return null;
        Path dir = direct.getParent();
        if (dir == null) return null;
        // npm global: <dir>/node_modules/@anthropic-ai/claude-code/bin/claude[.exe]
        Path base = dir.resolve("node_modules").resolve("@anthropic-ai").resolve("claude-code").resolve("bin");
        for (String name : new String[] { "claude.exe", "claude" }) {
            Path p = base.resolve(name);
            if (isBinary(p)) return p;
        }
        return null;
    }

    private static Path safePath(String s) {
        try { return (s == null || s.isEmpty()) ? null : Paths.get(s); }
        catch (Throwable t) { return null; }
    }

    private static boolean isBinary(Path p) {
        try { return p != null && Files.isRegularFile(p) && Files.size(p) >= MIN_BINARY_BYTES; }
        catch (Throwable t) { return false; }
    }
}
