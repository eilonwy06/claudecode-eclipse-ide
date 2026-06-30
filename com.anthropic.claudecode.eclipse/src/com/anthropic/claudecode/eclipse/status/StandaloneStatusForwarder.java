package com.anthropic.claudecode.eclipse.status;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

/**
 * Tiny forwarder spawned by Claude as its configured {@code statusLine.command}.
 *
 * <p>Claude runs this once per assistant message (plus idle-timer ticks) and pipes a
 * JSON status document to its stdin. This class reads that JSON verbatim and POSTs it
 * back to the plugin's local axum server, which routes it to the right terminal tab.
 *
 * <p><b>Hard constraint: {@code java.base} only.</b> It runs in a bare JVM launched with
 * just {@code -cp <bundle>} and no OSGi runtime — no {@code Activator}, no gson, no
 * {@code org.eclipse.*}. Keep it a single self-contained class. (It still compiles with
 * the normal PDE build and ships inside the plugin jar.)
 *
 * <p>It takes no arguments and reads everything it needs from the environment it inherits
 * from Claude (its parent):
 * <ul>
 *   <li>{@code CLAUDE_TAB_TOKEN} — per-tab routing token (echoed back as the {@code tab} param)</li>
 *   <li>{@code CLAUDE_CODE_SSE_PORT} — local server port</li>
 *   <li>{@code CLAUDE_IDE_AUTH_TOKEN} — the workspace server's shared secret</li>
 * </ul>
 *
 * <p>It prints nothing to stdout (Claude would render it) and always exits 0 so a
 * transient server hiccup never spams the TUI.
 */
public final class StandaloneStatusForwarder {

    private StandaloneStatusForwarder() {}

    public static void main(String[] args) {
        try {
            String tabToken = System.getenv("CLAUDE_TAB_TOKEN");
            String port = System.getenv("CLAUDE_CODE_SSE_PORT");
            String authToken = System.getenv("CLAUDE_IDE_AUTH_TOKEN");
            if (tabToken == null || tabToken.isEmpty() || port == null || port.isEmpty()) {
                return; // nothing we can do without routing/target info
            }
            byte[] body = readAll(System.in);

            String query = "tab=" + enc(tabToken)
                    + "&authToken=" + enc(authToken == null ? "" : authToken);
            URL url = new URL("http://127.0.0.1:" + port + "/statusline?" + query);

            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("POST");
            conn.setConnectTimeout(2000);
            conn.setReadTimeout(2000);
            conn.setDoOutput(true);
            conn.setFixedLengthStreamingMode(body.length);
            conn.setRequestProperty("Content-Type", "application/json");
            try (OutputStream out = conn.getOutputStream()) {
                out.write(body);
            }
            // Read (and discard) the response so the connection completes cleanly.
            conn.getResponseCode();
            conn.disconnect();
        } catch (Throwable ignored) {
            // Never surface anything to Claude's TUI; exit 0 regardless.
        }
    }

    private static String enc(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }

    private static byte[] readAll(InputStream in) throws java.io.IOException {
        ByteArrayOutputStream buf = new ByteArrayOutputStream(4096);
        byte[] chunk = new byte[4096];
        int n;
        while ((n = in.read(chunk)) != -1) {
            buf.write(chunk, 0, n);
        }
        return buf.toByteArray();
    }
}
