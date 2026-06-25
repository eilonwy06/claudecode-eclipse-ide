package com.anthropic.claudecode.eclipse.ui;

import java.io.IOException;
import java.net.URL;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.FileLocator;
import org.eclipse.swt.SWT;
import org.eclipse.swt.browser.Browser;
import org.eclipse.swt.browser.BrowserFunction;
import org.eclipse.swt.layout.FillLayout;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Display;
import org.eclipse.ui.part.ViewPart;

import com.anthropic.claudecode.eclipse.Activator;
import com.anthropic.claudecode.eclipse.NativeCore;
import com.anthropic.claudecode.eclipse.bridge.PhpHistory;
import com.anthropic.claudecode.eclipse.chat.ChatProcessManager;

/**
 * "Claude GUI" view — a faithful replica of the VSCode Claude Code panel, wired
 * to a working local chat.
 *
 * <p>The UI is an Edge/WebView2 {@link Browser} loading a bundled HTML page. Chat
 * flows through the Rust {@link ChatProcessManager} (streaming text + tool events),
 * past conversations come from the local session-history backend
 * ({@link NativeCore#sessionList}/{@link NativeCore#sessionLoad}), and dictation
 * uses the native {@code /stt/stream} relay. JS↔Java is bridged with
 * {@link BrowserFunction}s; Java→JS uses {@code browser.execute}.
 */
public class ClaudeGuiView extends ViewPart {

    public static final String VIEW_ID = "com.anthropic.claudecode.eclipse.ui.ClaudeGuiView";

    private Browser browser;
    private long browserHwnd = 0;
    private boolean pageLoaded = false;

    private ChatProcessManager processManager;

    // Strong references — prevent GC from unregistering the BrowserFunctions.
    @SuppressWarnings("unused") private BrowserFunction sendFn;
    @SuppressWarnings("unused") private BrowserFunction cancelFn;
    @SuppressWarnings("unused") private BrowserFunction newSessionFn;
    @SuppressWarnings("unused") private BrowserFunction listSessionsFn;
    @SuppressWarnings("unused") private BrowserFunction listSessionsAsyncFn;
    @SuppressWarnings("unused") private BrowserFunction loadSessionFn;
    @SuppressWarnings("unused") private BrowserFunction deleteSessionFn;
    @SuppressWarnings("unused") private BrowserFunction currentContextFn;
    @SuppressWarnings("unused") private BrowserFunction decideFn;
    @SuppressWarnings("unused") private BrowserFunction answerQuestionFn;
    @SuppressWarnings("unused") private BrowserFunction modelConfigFn;
    @SuppressWarnings("unused") private BrowserFunction accountInfoFn;

    private volatile boolean contextPolling;
    private String lastContextJson = "";

    // --- permission decision bridge (claude --permission-prompt-tool) ---
    private static volatile ClaudeGuiView active;
    private static final ConcurrentHashMap<String, CompletableFuture<String>> PENDING = new ConcurrentHashMap<>();
    private static volatile boolean allowAllSession = false;
    // --- AskUserQuestion bridge (claude mcp__eclipse__askUserQuestion) ---
    private static final ConcurrentHashMap<String, CompletableFuture<String>> QPENDING = new ConcurrentHashMap<>();

    @Override
    public void createPartControl(Composite parent) {
        parent.setLayout(new FillLayout());
        browser = new Browser(parent, SWT.NONE);

        // Extract HWND so WebView2 keyboard input can be activated.
        try {
            java.lang.reflect.Field f =
                org.eclipse.swt.widgets.Control.class.getDeclaredField("handle");
            f.setAccessible(true);
            Object val = f.get(browser);
            if (val instanceof Long)         browserHwnd = (Long) val;
            else if (val instanceof Integer) browserHwnd = (long) (int) (Integer) val;
        } catch (Exception ignored) {}

        active = this;
        processManager = new ChatProcessManager();
        wireChatCallbacks();

        // JS → Java bridge.
        sendFn         = new SimpleFunction(browser, "_sendToJava", a -> {
            if (a.length > 0 && a[0] instanceof String s) {
                boolean withCtx = a.length > 1 && a[1] instanceof Boolean b && b;
                String resumeId = (a.length > 2 && a[2] instanceof String r) ? r : "";
                String permMode = (a.length > 3 && a[3] instanceof String m) ? m : "";
                String effort   = (a.length > 4 && a[4] instanceof String e) ? e : "";
                String model    = (a.length > 5 && a[5] instanceof String md) ? md : "";
                String thinking = (a.length > 6 && a[6] instanceof String th) ? th : "";
                processManager.sendMessage(withCtx ? buildContextPreamble() + s : s, resumeId, permMode, effort, model, thinking);
            }
            return null;
        });
        cancelFn       = new SimpleFunction(browser, "_cancelRequest", a -> { processManager.cancel(); return null; });
        newSessionFn   = new SimpleFunction(browser, "_newSession", a -> { processManager.resetSession(); return null; });
        listSessionsFn = new SimpleFunction(browser, "_listSessions", a -> safeSessionList());
        // Async variant: compute the list off the UI thread (first call extracts the
        // bundled PHP runtime + spawns php), then push it back to JS. Keeps the
        // history button click from freezing the UI.
        listSessionsAsyncFn = new SimpleFunction(browser, "_listSessionsAsync", a -> {
            final Browser b = browser;
            new Thread(() -> {
                String json = safeSessionList();
                Display.getDefault().asyncExec(() -> {
                    if (b != null && !b.isDisposed() && pageLoaded) {
                        b.execute("window.onHistoryLoaded && window.onHistoryLoaded('" + esc(json) + "')");
                    }
                });
            }, "claude-history-load").start();
            return null;
        });
        loadSessionFn  = new SimpleFunction(browser, "_loadSession", a ->
            (a.length > 0 && a[0] instanceof String id) ? safeSessionLoad(id) : "[]");
        deleteSessionFn = new SimpleFunction(browser, "_deleteSession", a -> {
            if (a.length > 0 && a[0] instanceof String id) deleteSessionFile(id);
            return null;
        });
        currentContextFn = new SimpleFunction(browser, "_currentContext", a -> currentContextJson());
        modelConfigFn  = new SimpleFunction(browser, "_modelConfig", a -> modelConfigJson());
        accountInfoFn  = new SimpleFunction(browser, "_accountInfo", a -> accountInfoJson());
        decideFn = new SimpleFunction(browser, "_decide", a -> {
            if (a.length >= 2 && a[0] instanceof String reqId && a[1] instanceof String dec) {
                String msg = (a.length >= 3 && a[2] instanceof String m) ? m : "";
                CompletableFuture<String> f = PENDING.get(reqId);
                // Encode an optional "do this instead" message after a separator so the
                // approval tool can pass it back to claude as the deny reason.
                if (f != null) f.complete(msg.isEmpty() ? dec : (dec + msg));
            }
            return null;
        });
        answerQuestionFn = new SimpleFunction(browser, "_answerQuestion", a -> {
            if (a.length >= 2 && a[0] instanceof String reqId && a[1] instanceof String ans) {
                CompletableFuture<String> f = QPENDING.get(reqId);
                if (f != null) f.complete(ans);
            }
            return null;
        });

        browser.addProgressListener(org.eclipse.swt.browser.ProgressListener.completedAdapter(e -> {
            pageLoaded = true;
            for (int ms : new int[]{50, 200, 500, 1000, 1500}) {
                Display.getCurrent().timerExec(ms, this::activateInput);
            }
        }));

        loadPage();
        ensureServerAsync();
        startContextPolling();
        // Pre-extract the bundled PHP runtime in the background so the first
        // session-history click doesn't pay the one-time extraction cost.
        new Thread(com.anthropic.claudecode.eclipse.bridge.PhpHistory::warmUp, "claude-history-warm").start();
    }

    /** Push the current editor file/selection to the webview when it changes. */
    private void startContextPolling() {
        contextPolling = true;
        Display display = Display.getDefault();
        final Runnable[] tick = new Runnable[1];
        tick[0] = () -> {
            if (!contextPolling || browser == null || browser.isDisposed()) return;
            if (pageLoaded) {
                String json = currentContextJson();
                if (!json.equals(lastContextJson)) {
                    lastContextJson = json;
                    browser.execute("window.onContextChanged && window.onContextChanged(" + json + ");");
                }
            }
            display.timerExec(700, tick[0]);
        };
        display.timerExec(900, tick[0]);
    }

    /**
     * Build the editor-context preamble injected ahead of the user's message so
     * Claude actually receives the current selection/file. The interactive CLI
     * gets this via the live MCP {@code selection_changed} push, but {@code claude -p}
     * (what the chat uses) doesn't hold that subscription — so we inline it, the
     * same way the VSCode plugin does (an {@code ide_selection} block).
     */
    private String buildContextPreamble() {
        try {
            var st = Activator.getDefault().getSelectionTracker();
            if (st == null) return "";
            com.google.gson.JsonObject sel = st.getLatestSelection();
            if (sel == null) return "";
            String filePath = (sel.has("filePath") && !sel.get("filePath").isJsonNull())
                    ? sel.get("filePath").getAsString() : null;
            if (filePath == null || filePath.isEmpty()) return "";
            boolean isEmpty = sel.has("isEmpty") && sel.get("isEmpty").getAsBoolean();
            int sl = sel.has("startLine") ? sel.get("startLine").getAsInt() : 0;
            int el = sel.has("endLine") ? sel.get("endLine").getAsInt() : 0;
            String text = (sel.has("text") && !sel.get("text").isJsonNull()) ? sel.get("text").getAsString() : "";
            if (!isEmpty && text != null && !text.isBlank()) {
                if (text.length() > 16000) text = text.substring(0, 16000) + "\n…(truncated)";
                return "<ide_selection file=\"" + filePath + "\" startLine=\"" + sl + "\" endLine=\"" + el + "\">\n"
                        + text + "\n</ide_selection>\n\n";
            }
            return "<ide_context openFile=\"" + filePath + "\" />\n\n";
        } catch (Throwable t) {
            return "";
        }
    }

    /** Current open file + selection as a JSON object literal (basename only; path stays in Eclipse). */
    private String currentContextJson() {
        try {
            var st = Activator.getDefault().getSelectionTracker();
            if (st == null) return "{\"fileName\":null}";
            com.google.gson.JsonObject sel = st.getLatestSelection();
            if (sel == null) return "{\"fileName\":null}";
            String filePath = (sel.has("filePath") && !sel.get("filePath").isJsonNull())
                    ? sel.get("filePath").getAsString() : null;
            if (filePath == null || filePath.isEmpty()) return "{\"fileName\":null}";
            String fileName = new java.io.File(filePath).getName();
            boolean isEmpty = sel.has("isEmpty") && sel.get("isEmpty").getAsBoolean();
            int sl = sel.has("startLine") ? sel.get("startLine").getAsInt() : 0;
            int el = sel.has("endLine") ? sel.get("endLine").getAsInt() : 0;
            return "{\"fileName\":\"" + esc(fileName) + "\",\"hasSelection\":" + (!isEmpty)
                    + ",\"startLine\":" + sl + ",\"endLine\":" + el + "}";
        } catch (Throwable t) {
            return "{\"fileName\":null}";
        }
    }

    /** Detect a {@code --model <x>} the user set in their preference args, so the model
     *  chooser can offer it as an extra option. Returns {@code {"customModel":"..."}}. */
    private String modelConfigJson() {
        String custom = "";
        try {
            String args = Activator.getDefault().getPreferenceStore()
                    .getString(com.anthropic.claudecode.eclipse.Constants.PREF_CLAUDE_ARGS);
            if (args != null && !args.isBlank()) {
                java.util.regex.Matcher m = java.util.regex.Pattern
                        .compile("--model[=\\s]+(\"[^\"]+\"|\\S+)").matcher(args);
                if (m.find()) custom = m.group(1).replace("\"", "");
            }
        } catch (Throwable ignored) {}
        return "{\"customModel\":\"" + esc(custom) + "\"}";
    }

    /** Account info read from {@code ~/.claude.json} (oauthAccount). Usage % isn't exposed
     *  by the CLI, so only account fields are returned here. */
    private String accountInfoJson() {
        try {
            String home = System.getProperty("user.home");
            java.nio.file.Path p = java.nio.file.Paths.get(home, ".claude.json");
            if (!java.nio.file.Files.exists(p)) return "{}";
            String raw = java.nio.file.Files.readString(p);
            com.google.gson.JsonObject root = com.google.gson.JsonParser.parseString(raw).getAsJsonObject();
            if (!root.has("oauthAccount") || !root.get("oauthAccount").isJsonObject()) return "{}";
            com.google.gson.JsonObject acc = root.getAsJsonObject("oauthAccount");
            com.google.gson.JsonObject out = new com.google.gson.JsonObject();
            out.addProperty("email", str(acc, "emailAddress"));
            out.addProperty("name", str(acc, "displayName"));
            out.addProperty("organization", str(acc, "organizationName"));
            String orgType = str(acc, "organizationType");      // e.g. claude_pro -> Claude Pro
            String plan = orgType.isEmpty() ? "" : capitalizeWords(orgType.replace('_', ' '));
            out.addProperty("plan", plan);
            String billing = str(acc, "billingType");           // stripe_subscription -> Claude account
            out.addProperty("authMethod", billing.contains("subscription") ? "Claude account (OAuth)"
                    : (billing.isEmpty() ? "OAuth" : capitalizeWords(billing.replace('_', ' '))));
            out.addProperty("role", str(acc, "organizationRole"));
            return out.toString();
        } catch (Throwable t) {
            return "{}";
        }
    }

    private static String str(com.google.gson.JsonObject o, String k) {
        return (o.has(k) && o.get(k).isJsonPrimitive()) ? o.get(k).getAsString() : "";
    }
    private static String capitalizeWords(String s) {
        StringBuilder b = new StringBuilder();
        for (String w : s.trim().split("\\s+")) {
            if (w.isEmpty()) continue;
            b.append(Character.toUpperCase(w.charAt(0))).append(w.substring(1)).append(' ');
        }
        return b.toString().trim();
    }

    private void wireChatCallbacks() {
        Display display = Display.getDefault();
        processManager.setOnStreamStart(() -> display.asyncExec(() -> executeJS("window.onStreamStart && window.onStreamStart()")));
        processManager.setOnText(t -> display.asyncExec(() -> executeJS("window.onStreamText && window.onStreamText('" + esc(t) + "')")));
        processManager.setOnStreamEnd(() -> display.asyncExec(() -> executeJS("window.onStreamEnd && window.onStreamEnd()")));
        processManager.setOnToolStart(n -> display.asyncExec(() -> executeJS("window.onToolStart && window.onToolStart('" + esc(n) + "')")));
        processManager.setOnToolEnd(n -> display.asyncExec(() -> executeJS("window.onToolEnd && window.onToolEnd('" + esc(n) + "')")));
        processManager.setOnThinking(t -> display.asyncExec(() -> executeJS("window.onThinking && window.onThinking('" + esc(t) + "')")));
        processManager.setOnTokens(n -> display.asyncExec(() -> executeJS("window.onTokens && window.onTokens('" + esc(n) + "')")));
        processManager.setOnRateLimit(j -> display.asyncExec(() -> executeJS("window.onRateLimit && window.onRateLimit('" + esc(j) + "')")));
        processManager.setOnSessionId(id -> display.asyncExec(() -> executeJS("window.onSessionId && window.onSessionId('" + esc(id) + "')")));
        processManager.setOnError(m -> display.asyncExec(() -> executeJS("window.onError && window.onError('" + esc(m) + "')")));
        // Backend "system"/init events (e.g. "Connected") are noise in the GUI — not wired.
    }

    private void loadPage() {
        try {
            URL bundleUrl = Activator.getDefault().getBundle().getEntry("resources/claudegui/claudegui.html");
            if (bundleUrl != null) {
                URL fileUrl = FileLocator.toFileURL(bundleUrl);
                browser.setUrl(fileUrl.toString());
            } else {
                browser.setText("<html><body style='background:#1e1e1e;color:#d4d4d4;padding:20px;font-family:sans-serif;'>"
                        + "<h3>Claude GUI not found</h3><p>resources/claudegui/claudegui.html is missing from the bundle.</p></body></html>");
            }
        } catch (IOException e) {
            Activator.logError("Failed to load Claude GUI HTML", e);
            browser.setText("<html><body style='background:#1e1e1e;color:#d4d4d4;padding:20px;'>"
                    + "<h3>Error loading Claude GUI</h3><p>" + e.getMessage() + "</p></body></html>");
        }
    }

    /** Make sure the Rust MCP server (used by chat for editor tools) is up. */
    private void ensureServerAsync() {
        Thread t = new Thread(() -> {
            Activator activator = Activator.getDefault();
            if (!activator.isServerRunning()) {
                activator.initialize();
            }
        }, "claudegui-server-init");
        t.setDaemon(true);
        t.start();
    }

    private static String workspaceRoot() {
        return ResourcesPlugin.getWorkspace().getRoot().getLocation().toOSString();
    }

    // History is served by the bundled PHP (scripts/history.php) so it can be
    // iterated without a native rebuild; the Rust core stays as a fallback.
    private String safeSessionList() {
        try { String r = PhpHistory.run("list", workspaceRoot(), ""); if (r != null && !r.isBlank()) return r; }
        catch (Throwable ignored) {}
        try { return NativeCore.sessionList(workspaceRoot()); }
        catch (Throwable t) { return "[]"; }
    }

    private String safeSessionLoad(String id) {
        try { String r = PhpHistory.run("load", workspaceRoot(), id); if (r != null && !r.isBlank()) return r; }
        catch (Throwable ignored) {}
        try { return NativeCore.sessionLoad(workspaceRoot(), id); }
        catch (Throwable t) { return "[]"; }
    }

    /** Delete one local session file (PHP first; Java file-delete as fallback). */
    private void deleteSessionFile(String id) {
        try { String r = PhpHistory.run("delete", workspaceRoot(), id); if (r != null && !r.isBlank()) return; }
        catch (Throwable ignored) {}
        try {
            if (id == null || id.isEmpty() || id.contains("/") || id.contains("\\") || id.contains("..")) return;
            String home = Activator.isWindows() ? System.getenv("USERPROFILE") : System.getenv("HOME");
            if (home == null || home.isEmpty()) home = System.getProperty("user.home");
            if (home == null) return;
            java.nio.file.Path p = java.nio.file.Paths.get(
                    home, ".claude", "projects", projectHash(workspaceRoot()), id + ".jsonl");
            java.nio.file.Files.deleteIfExists(p);
        } catch (Exception ignored) {}
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

    private void executeJS(String script) {
        if (browser != null && !browser.isDisposed() && pageLoaded) {
            browser.execute(script);
        }
    }

    private static String esc(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("'", "\\'")
                .replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t");
    }

    @Override
    public void setFocus() {
        activateInput();
    }

    private void activateInput() {
        if (browser == null || browser.isDisposed()) return;
        browser.forceFocus();
        if (browserHwnd != 0) {
            NativeCore.browserActivateInput(browserHwnd);
        }
    }

    /**
     * Called (off the UI thread) by {@code ApprovalPromptTool} when claude requests
     * permission. Shows an in-chat decision card and blocks until the user chooses.
     * Returns "allow" or "deny" ("allow all this session" is remembered statically).
     */
    public static String requestApproval(String toolName, String detail,
                                         String filePath, String proposedContent) {
        if (allowAllSession) return "allow";
        ClaudeGuiView view = active;
        if (view == null || view.browser == null) return "deny";
        String reqId = UUID.randomUUID().toString();
        CompletableFuture<String> future = new CompletableFuture<>();
        PENDING.put(reqId, future);
        final String tn = esc(toolName == null ? "tool" : toolName);
        final String dt = esc(detail == null ? "" : detail);

        // Show the proposed change in an Eclipse diff editor while the card is up
        // (matches the VSCode panel: the diff opens at the decision point).
        final org.eclipse.compare.CompareEditorInput[] preview = { null };
        if (filePath != null && !filePath.isEmpty() && proposedContent != null) {
            try {
                preview[0] = com.anthropic.claudecode.eclipse.tools.DiffPreview.open(
                        filePath, proposedContent,
                        java.nio.file.Path.of(filePath).getFileName() + " (proposed)");
            } catch (Exception ignored) {}
        }

        Display.getDefault().asyncExec(() -> {
            if (view.browser != null && !view.browser.isDisposed() && view.pageLoaded) {
                view.browser.execute("window.onApprovalRequest && window.onApprovalRequest('"
                        + reqId + "','" + tn + "','" + dt + "')");
            } else {
                CompletableFuture<String> f = PENDING.remove(reqId);
                if (f != null) f.complete("deny");
            }
        });
        try {
            String decision = future.get(30, TimeUnit.MINUTES);
            if ("allowAll".equals(decision)) { allowAllSession = true; return "allow"; }
            return decision;
        } catch (Exception e) {
            return "deny";
        } finally {
            PENDING.remove(reqId);
            if (preview[0] != null) {
                try { com.anthropic.claudecode.eclipse.tools.DiffPreview.close(preview[0]); }
                catch (Exception ignored) {}
            }
        }
    }

    /**
     * Called (off the UI thread) by {@code AskUserQuestionTool}. Renders the
     * multiple-choice card in the GUI and blocks until the user submits, returning
     * the answers as a JSON array string ({@code [{header,question,answer}]}) or
     * {@code "[]"} if dismissed.
     */
    public static String requestQuestion(String questionsJson) {
        ClaudeGuiView view = active;
        if (view == null || view.browser == null) return "[]";
        String reqId = UUID.randomUUID().toString();
        CompletableFuture<String> future = new CompletableFuture<>();
        QPENDING.put(reqId, future);
        final String qjson = esc(questionsJson == null ? "[]" : questionsJson);
        Display.getDefault().asyncExec(() -> {
            if (view.browser != null && !view.browser.isDisposed() && view.pageLoaded) {
                view.browser.execute("window.onAskQuestion && window.onAskQuestion('"
                        + reqId + "','" + qjson + "')");
            } else {
                CompletableFuture<String> f = QPENDING.remove(reqId);
                if (f != null) f.complete("[]");
            }
        });
        try {
            return future.get(30, TimeUnit.MINUTES);
        } catch (Exception e) {
            return "[]";
        } finally {
            QPENDING.remove(reqId);
        }
    }

    @Override
    public void dispose() {
        if (active == this) active = null;
        contextPolling = false;
        try {
            if (processManager != null) processManager.stop();
        } catch (Exception ignored) {}
        super.dispose();
    }

    // --- BrowserFunction helpers ---------------------------------------------

    /** Generic BrowserFunction backed by a lambda. */
    private static class SimpleFunction extends BrowserFunction {
        private final java.util.function.Function<Object[], Object> impl;
        SimpleFunction(Browser browser, String name, java.util.function.Function<Object[], Object> impl) {
            super(browser, name);
            this.impl = impl;
        }
        @Override public Object function(Object[] arguments) {
            try { return impl.apply(arguments); } catch (Exception e) { return null; }
        }
    }
}
