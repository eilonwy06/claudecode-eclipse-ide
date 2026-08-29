package com.anthropic.claudecode.eclipse.ui;

import java.io.IOException;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;

import org.eclipse.core.resources.IContainer;
import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.IAdaptable;
import org.eclipse.jface.dialogs.MessageDialog;
import org.eclipse.jface.viewers.ISelection;
import org.eclipse.jface.viewers.IStructuredSelection;
import org.eclipse.core.runtime.FileLocator;
import org.eclipse.jface.action.Action;
import org.eclipse.jface.action.IToolBarManager;
import org.eclipse.swt.SWT;
import org.eclipse.swt.browser.Browser;
import org.eclipse.swt.browser.BrowserFunction;
import org.eclipse.swt.dnd.Clipboard;
import org.eclipse.swt.dnd.FileTransfer;
import org.eclipse.swt.dnd.HTMLTransfer;
import org.eclipse.swt.dnd.ImageTransfer;
import org.eclipse.swt.dnd.TextTransfer;
import org.eclipse.swt.dnd.Transfer;
import org.eclipse.swt.graphics.ImageData;
import org.eclipse.swt.graphics.ImageLoader;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.DirectoryDialog;
import org.eclipse.swt.widgets.Display;
import org.eclipse.ui.part.IShowInTarget;
import org.eclipse.ui.part.ShowInContext;
import org.eclipse.ui.part.ViewPart;

import com.anthropic.claudecode.eclipse.Activator;
import com.anthropic.claudecode.eclipse.NativeCore;
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
public class ClaudeGuiView extends ViewPart implements IShowInTarget {

    public static final String VIEW_ID = "com.anthropic.claudecode.eclipse.ui.ClaudeGuiView";

    private Browser browser;
    private Composite root;   // the view's root composite — its themed background drives light/dark detection
    private long browserHwnd = 0;
    private boolean pageLoaded = false;
    /** View-toolbar Scroll Lock toggle; see {@link #createToolBar()}. */
    private Action scrollLockAction;

    // One claude process per conversation/tab, so tabs never block each other.
    private final java.util.Map<String, ChatProcessManager> managers = new java.util.concurrent.ConcurrentHashMap<>();
    // Which tab is active (for gating the shared status bar) + its last status JSON.
    private volatile String activeTabId = "";
    private final java.util.Map<String, String> statusByTab = new java.util.concurrent.ConcurrentHashMap<>();
    /**
     * The ACTIVE conversation's working root ("supertab") — the folder its claude runs
     * in. Session history, rewind and the per-session sidecars are all keyed by
     * directory, so they must follow the conversation's own root rather than the
     * workspace root. Pushed by {@code _activeTab}; empty means the workspace root.
     */
    private volatile String activeRootPath = "";
    /** A folder to open as a root once the page is up (an "Open Claude Code Here"
     *  that arrived while the view was still loading). */
    private volatile String pendingRootPath = null;

    // Strong references — prevent GC from unregistering the BrowserFunctions.
    @SuppressWarnings("unused") private BrowserFunction sendFn;
    @SuppressWarnings("unused") private BrowserFunction cancelFn;
    @SuppressWarnings("unused") private BrowserFunction disposeTabFn;
    @SuppressWarnings("unused") private BrowserFunction activeTabFn;
    @SuppressWarnings("unused") private BrowserFunction newSessionFn;
    @SuppressWarnings("unused") private BrowserFunction listSessionsFn;
    @SuppressWarnings("unused") private BrowserFunction listSessionsAsyncFn;
    @SuppressWarnings("unused") private BrowserFunction loadSessionFn;
    @SuppressWarnings("unused") private BrowserFunction deleteSessionFn;
    @SuppressWarnings("unused") private BrowserFunction renameSessionFn;
    @SuppressWarnings("unused") private BrowserFunction currentContextFn;
    @SuppressWarnings("unused") private BrowserFunction decideFn;
    @SuppressWarnings("unused") private BrowserFunction answerQuestionFn;
    @SuppressWarnings("unused") private BrowserFunction overlayOpenFn;
    @SuppressWarnings("unused") private BrowserFunction modelConfigFn;
    @SuppressWarnings("unused") private BrowserFunction accountInfoFn;
    @SuppressWarnings("unused") private BrowserFunction defaultRootFn;
    @SuppressWarnings("unused") private BrowserFunction pickDirectoryFn;
    @SuppressWarnings("unused") private BrowserFunction folderInfoFn;
    @SuppressWarnings("unused") private BrowserFunction trustFolderFn;
    @SuppressWarnings("unused") private BrowserFunction confirmCloseViewFn;
    @SuppressWarnings("unused") private BrowserFunction saveSessionPrefsFn;
    @SuppressWarnings("unused") private BrowserFunction loadSessionPrefsFn;
    @SuppressWarnings("unused") private BrowserFunction setPermissionModeFn;
    @SuppressWarnings("unused") private BrowserFunction updateCliFn;
    @SuppressWarnings("unused") private BrowserFunction rewindListFn;
    @SuppressWarnings("unused") private BrowserFunction rewindPreviewFn;
    @SuppressWarnings("unused") private BrowserFunction rewindApplyFn;
    @SuppressWarnings("unused") private BrowserFunction rewindForkOnlyFn;
    @SuppressWarnings("unused") private BrowserFunction rewindCodeOnlyFn;
    @SuppressWarnings("unused") private BrowserFunction messageIdsFn;
    @SuppressWarnings("unused") private BrowserFunction deleteMessageFn;
    @SuppressWarnings("unused") private BrowserFunction advisorGetFn;
    @SuppressWarnings("unused") private BrowserFunction advisorSetFn;
    @SuppressWarnings("unused") private BrowserFunction openExternalFn;
    @SuppressWarnings("unused") private BrowserFunction clipGetFn;
    @SuppressWarnings("unused") private BrowserFunction clipSetFn;
    @SuppressWarnings("unused") private BrowserFunction clipImagesFn;
    @SuppressWarnings("unused") private BrowserFunction drainImagesFn;
    /** Images fetched for a pasted fragment, waiting for the webview to collect them. */
    private final java.util.concurrent.ConcurrentLinkedQueue<Map<String, String>> fetchedImages =
            new java.util.concurrent.ConcurrentLinkedQueue<>();
    @SuppressWarnings("unused") private BrowserFunction editOpsReadyFn;
    @SuppressWarnings("unused") private BrowserFunction debugLogFn;
    /** Set by the page once the __cc* editing entry points exist — see registerEditHandlers. */
    private volatile boolean editOpsReady = false;

    // org.eclipse.ui.edit.* handlers, activated on this view's site (see registerEditHandlers).
    private final java.util.List<org.eclipse.ui.handlers.IHandlerActivation> editHandlers =
            new java.util.ArrayList<>();

    // The (time, keyCode, stateMask) of the last SWT.KeyDown an edit handler acted on — see
    // activateEditHandler. On GTK the same physical keystroke can reach Eclipse's key-binding
    // dispatcher more than once (issue #97: Alt-combos deliver the character KeyDown three
    // times, same timestamp each time); this collapses those repeats into one execution.
    private String lastHandledKeyEvent = null;

    // Status bar (the shared SWT ClaudeStatusBar widget, reused from the CLI view).
    private ClaudeStatusBar statusBar;
    // Live-applies PREF_STATUSLINE_* changes (enable/toggles/refresh) without a restart.
    private org.eclipse.jface.util.IPropertyChangeListener statusPrefListener;
    // Re-pushes light/dark to the webview when the Eclipse workbench theme changes.
    private org.eclipse.jface.util.IPropertyChangeListener themeChangeListener;
    // Re-pushes the right-click menu's key hints when the user's bindings change.
    private org.eclipse.jface.bindings.IBindingManagerListener bindingChangeListener;
    @SuppressWarnings("unused") private BrowserFunction statusSelectionFn;
    private volatile String availableModelsJson;   // curated model list from /v1/models, pushed to the webview
    private volatile String cliVersionJson;        // {installed,latest,updateAvailable} for the update banner
    private volatile String cliModelsJson;         // newest model per family the INSTALLED binary can run
    private volatile String lastRustStatusJson;   // latest onStatus payload (context %, cost, tokens)

    // --- account-global usage probe (see fetchUsageAsync) ---------------------
    /** Floor between {@code /usage} probes — the windows are 5-hour/7-day. */
    private static final long USAGE_FETCH_MIN_INTERVAL_MS = 60_000L;
    /** One probe in flight at a time. */
    private final java.util.concurrent.atomic.AtomicBoolean usageFetchInFlight =
            new java.util.concurrent.atomic.AtomicBoolean();
    /** Epoch ms of the last completed probe; throttles {@link #fetchUsageAsync()}. */
    private volatile long lastUsageFetchMs;
    /** Set when the native lib has no {@code fetchUsage} export — stop probing. */
    private volatile boolean usageProbeUnavailable;
    private volatile String displayModel = "";     // shown model — live GUI selection OR last actual (whichever changed last)
    private volatile String lastEffort = "";       // current GUI selection
    private volatile boolean lastThinking = true;

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
        // Browser fills the view; the shared ClaudeStatusBar sits under it as a
        // fixed-height strip (same widget the CLI uses — one status bar, not two).
        org.eclipse.swt.layout.GridLayout gl = new org.eclipse.swt.layout.GridLayout(1, false);
        gl.marginWidth = 0; gl.marginHeight = 0; gl.verticalSpacing = 0; gl.horizontalSpacing = 0;
        parent.setLayout(gl);
        root = parent;
        browser = new Browser(parent, SWT.NONE);
        browser.setLayoutData(new org.eclipse.swt.layout.GridData(SWT.FILL, SWT.FILL, true, true));

        // Extract HWND so WebView2 keyboard input can be activated.
        try {
            java.lang.reflect.Field f =
                org.eclipse.swt.widgets.Control.class.getDeclaredField("handle");
            f.setAccessible(true);
            Object val = f.get(browser);
            if (val instanceof Long)         browserHwnd = (Long) val;
            else if (val instanceof Integer) browserHwnd = (long) (int) (Integer) val;
        } catch (Exception ignored) {}

        disableDevTools();
        disableZoom();

        // The SAME status bar widget the CLI view uses — reused here so the two
        // are literally identical (one implementation). It reads the shared
        // PREF_STATUSLINE_* toggles itself; we feed it a ClaudeStatus assembled
        // from this GUI's own stream plus the account-global limits in the store.
        statusBar = new ClaudeStatusBar(parent);
        statusBar.setLayoutData(new org.eclipse.swt.layout.GridData(SWT.FILL, SWT.CENTER, true, false));
        applyStatusBarEnabled();
        startStatusTimer();
        // Seed the Session/Weekly percentages now, so a user who only ever opens
        // this view sees them without having to send a turn first (issue #99).
        fetchUsageAsync();
        registerStatusPrefListener();
        registerThemeListener();
        registerBindingListener();
        registerEditHandlers();
        createToolBar();

        active = this;
        // Persistent protocol, ONE manager per tab (created on first send) so
        // conversations run concurrently and independently.

        // JS → Java bridge. The last arg is the TAB ID so each conversation uses
        // its own process; that's what lets a new/other tab work while one streams.
        sendFn         = new SimpleFunction(browser, "_sendToJava", a -> {
            if (a.length > 0 && a[0] instanceof String s) {
                boolean withCtx = a.length > 1 && a[1] instanceof Boolean b && b;
                String resumeId = (a.length > 2 && a[2] instanceof String r) ? r : "";
                String permMode = (a.length > 3 && a[3] instanceof String m) ? m : "";
                String effort   = (a.length > 4 && a[4] instanceof String e) ? e : "";
                String model    = (a.length > 5 && a[5] instanceof String md) ? md : "";
                // "Default" (empty) → honor the user's configured --model from prefs,
                // like the Claude Terminal does. Without this, an empty model lets the
                // CLI pick the account default (e.g. Opus 4.8) instead of the user's.
                if (model.isEmpty()) model = prefClaudeModel();
                String thinking = (a.length > 6 && a[6] instanceof String th) ? th : "";
                // Upgrade "on" to "2" when the installed CLI advertises
                // --thinking-display, which makes the core request readable reasoning
                // summaries. Without the flag the CLI defaults to "omitted" and the
                // thinking text streams empty (the dead "Thought for Ns" chevron).
                // Kept behind the scan because the option is undocumented: passing it
                // to a CLI that lacks it aborts the process with "unknown option".
                if ("1".equals(thinking) && cliSupportsThinkingDisplay()) thinking = "2";
                String tabId    = (a.length > 7 && a[7] instanceof String ti) ? ti : "default";
                // Pasted images: JSON array of {media_type,data} (base64), built by the
                // webview from clipboard-image paste. "" when none.
                String imagesJson = (a.length > 8 && a[8] instanceof String ij) ? ij : "";
                // This conversation's working root — its claude's cwd. Empty (an older
                // page, or the workspace root itself) leaves the manager on the default.
                String root     = (a.length > 9 && a[9] instanceof String rp) ? rp : "";
                // Capture effort/thinking for the status bar (the stream reports
                // model + context + cost, but not these launch-time choices).
                this.lastEffort = effort;
                this.lastThinking = !"0".equals(thinking);
                ChatProcessManager mgr = managerFor(tabId);
                mgr.setRoot(root);
                mgr.sendMessage(withCtx ? buildContextPreamble() + s : s, resumeId, permMode, effort, model, thinking, imagesJson);
            }
            return null;
        });
        cancelFn       = new SimpleFunction(browser, "_cancelRequest", a -> {
            if (a.length > 0 && a[0] instanceof String ti) { ChatProcessManager m = managers.get(ti); if (m != null) m.cancel(); }
            return null;
        });
        // A tab was closed → free its process.
        disposeTabFn   = new SimpleFunction(browser, "_disposeTab", a -> {
            if (a.length > 0 && a[0] instanceof String ti) { ChatProcessManager m = managers.remove(ti); statusByTab.remove(ti); if (m != null) m.stop(); }
            return null;
        });
        // A tab became active → point the shared status bar at its last status.
        activeTabFn    = new SimpleFunction(browser, "_activeTab", a -> {
            if (a.length > 0 && a[0] instanceof String ti) { activeTabId = ti; lastRustStatusJson = statusByTab.get(ti); refreshStatusBar(); }
            // Second arg is that tab's working root, so everything below keyed by
            // directory (history, rewind, the title sidecar) follows the conversation.
            if (a.length > 1 && a[1] instanceof String rp) activeRootPath = rp == null ? "" : rp;
            return null;
        });
        newSessionFn   = new SimpleFunction(browser, "_newSession", a -> null);   // new tab = new manager (JS creates the tab)
        listSessionsFn = new SimpleFunction(browser, "_listSessions", a -> safeSessionList());
        // Async variant: compute the list off the UI thread (scanning many jsonl
        // files can take a moment), then push it back to JS. Keeps the history
        // button click from freezing the UI.
        listSessionsAsyncFn = new SimpleFunction(browser, "_listSessionsAsync", a -> {
            final Browser b = browser;
            // Captured HERE, on the UI thread: a tab switch during the scan would
            // otherwise move activeRootPath and hand back another folder's sessions.
            final String root = activeRoot();
            new Thread(() -> {
                String json = safeSessionList(root);
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
        renameSessionFn = new SimpleFunction(browser, "_renameSession", a -> {
            if (a.length > 1 && a[0] instanceof String id && a[1] instanceof String newTitle)
                renameSessionFile(id, newTitle);
            return null;
        });
        // ── Working roots ("supertabs") ────────────────────────────────────────
        defaultRootFn  = new SimpleFunction(browser, "_defaultRoot", a -> workspaceRoot());
        // The folder picker is ASYNC on purpose: opening a modal SWT dialog while
        // WebView2 is still inside the JS call that triggered it deadlocks on Windows.
        // Java answers by calling window.onDirectoryPicked once the dialog closes.
        pickDirectoryFn = new SimpleFunction(browser, "_pickDirectory", a -> {
            Display.getDefault().asyncExec(() -> {
                if (browser == null || browser.isDisposed()) return;
                DirectoryDialog dlg = new DirectoryDialog(browser.getShell());
                dlg.setText("New Claude root directory");
                dlg.setMessage("Select the folder Claude Code should run in.");
                dlg.setFilterPath(workspaceRoot());
                String picked = dlg.open();
                final String path = picked == null ? "" : picked;
                // Pushed from a SECOND asyncExec so the browser is never re-entered
                // from inside the modal's own dispatch.
                Display.getDefault().asyncExec(() ->
                    executeJS("window.onDirectoryPicked && window.onDirectoryPicked('" + esc(path) + "')"));
            });
            return null;
        });
        folderInfoFn   = new SimpleFunction(browser, "_folderInfo", a ->
            folderInfoJson(a.length > 0 && a[0] instanceof String p ? p : ""));
        trustFolderFn  = new SimpleFunction(browser, "_trustFolder", a -> {
            if (a.length > 0 && a[0] instanceof String p) TrustStore.trust(p);
            return null;
        });
        // Closing the last working root closes the view — there is no folder left to
        // run in. The question is asked HERE rather than in the page: this panel is
        // routinely docked narrower than an in-page modal can lay itself out in, and a
        // real dialog also gets the platform's own button order and Esc handling.
        //
        // asyncExec is not optional. Both the modal and the hideView that follows would
        // otherwise run inside a call this Browser is still executing — the first
        // deadlocks WebView2, the second disposes the widget out from under it.
        confirmCloseViewFn = new SimpleFunction(browser, "_confirmCloseView", a -> {
            Display.getDefault().asyncExec(() -> {
                try {
                    if (browser == null || browser.isDisposed()) return;
                    // Constructed rather than MessageDialog.open(...): the varargs
                    // overload that takes custom button labels returns the button INDEX,
                    // not a boolean. Index 0 is the first label; dismissing the dialog
                    // returns -1, which reads as cancel like every other non-zero answer.
                    MessageDialog dlg = new MessageDialog(browser.getShell(),
                            "Close the last directory?", null,
                            "You are about to close the last directory tab, this action will "
                                    + "close the Claude Code view. Do you wish to proceed?",
                            MessageDialog.CONFIRM, new String[] { "Close the view", "Cancel" }, 1);
                    if (dlg.open() != 0) return;
                    org.eclipse.ui.IWorkbenchPartSite site = getSite();
                    if (site != null && site.getPage() != null) site.getPage().hideView(ClaudeGuiView.this);
                } catch (Exception e) {
                    Activator.logError("Failed to close the Claude Code view", e);
                }
            });
            return null;
        });
        currentContextFn = new SimpleFunction(browser, "_currentContext", a -> currentContextJson());
        modelConfigFn  = new SimpleFunction(browser, "_modelConfig", a -> modelConfigJson());
        accountInfoFn  = new SimpleFunction(browser, "_accountInfo", a -> accountInfoJson());
        // Per-conversation composer prefs (effort/model/thinking/permission mode) — we
        // persist these ourselves so a resumed conversation restores them (esp. effort
        // and the permission mode, which the CLI transcript never records).
        saveSessionPrefsFn = new SimpleFunction(browser, "_saveSessionPrefs", a -> {
            if (a.length > 0 && a[0] instanceof String id) {
                String ef = (a.length > 1 && a[1] instanceof String s) ? s : "";
                String md = (a.length > 2 && a[2] instanceof String s) ? s : "";
                String th = (a.length > 3 && a[3] instanceof String s) ? s : "";
                String pm = (a.length > 4 && a[4] instanceof String s) ? s : "";
                SessionPrefsStore.save(id, ef, md, th, pm);
            }
            return null;
        });
        // Live permission-mode switch for an ALREADY-RUNNING conversation. The
        // spawn-time --permission-mode flag only covers the first launch, so a
        // mid-conversation change is pushed to that tab's process as a
        // set_permission_mode control request.
        setPermissionModeFn = new SimpleFunction(browser, "_setPermissionMode", a -> {
            if (a.length > 1 && a[0] instanceof String ti && a[1] instanceof String mode) {
                ChatProcessManager m = managers.get(ti);   // only a live process needs telling
                // An older DLL has no chatSetPermissionMode symbol (UnsatisfiedLinkError);
                // degrade to "applies at the next spawn" rather than failing the click.
                if (m != null) try { m.setPermissionMode(mode); } catch (Throwable ignored) {}
            }
            return null;
        });
        loadSessionPrefsFn = new SimpleFunction(browser, "_loadSessionPrefs", a ->
            (a.length > 0 && a[0] instanceof String id) ? SessionPrefsStore.load(id) : "{}");
        // Runs `claude update` — the CLI's own updater, so it works whichever way
        // the binary was installed (npm / native / Homebrew). USER-TRIGGERED ONLY,
        // from the update banner's button; the version check never calls it.
        updateCliFn = new SimpleFunction(browser, "_updateCli", a -> {
            CliUpdateService.updateAsync(configuredClaudeCmd(), res ->
                Display.getDefault().asyncExec(() -> {
                    if (browser == null || browser.isDisposed()) return;
                    browser.execute("window.onCliUpdateDone && window.onCliUpdateDone('" + esc(res.toJson()) + "')");
                    if (res.ok) {
                        // Refresh what we know about the CLI. The banner itself stays
                        // put — it now shows the result and is dismissed by the user,
                        // not by this re-check reporting "up to date".
                        checkCliVersionAsync();
                        scanCliModelsAsync();     // the new binary may support newer models
                    }
                }));
            return null;
        });
        // Checkpoint rewind (VSCode "Rewind to…"): list a session's user messages,
        // preview the file restore, then restore + fork into a new session.
        rewindListFn = new SimpleFunction(browser, "_rewindList", a ->
            (a.length > 0 && a[0] instanceof String sid)
                ? com.anthropic.claudecode.eclipse.chat.RewindService.list(activeRoot(), sid) : "[]");
        rewindPreviewFn = new SimpleFunction(browser, "_rewindPreview", a ->
            (a.length > 1 && a[0] instanceof String sid && a[1] instanceof String mid)
                ? com.anthropic.claudecode.eclipse.chat.RewindService.preview(activeRoot(), sid, mid) : "{}");
        rewindApplyFn = new SimpleFunction(browser, "_rewindApply", a ->
            (a.length > 1 && a[0] instanceof String sid && a[1] instanceof String mid)
                ? com.anthropic.claudecode.eclipse.chat.RewindService.apply(activeRoot(), sid, mid) : "{}");
        // The per-message menu (joebiden8) offers the two halves separately as well:
        // fork without touching the files, or restore the files in place.
        rewindForkOnlyFn = new SimpleFunction(browser, "_rewindForkOnly", a ->
            (a.length > 1 && a[0] instanceof String sid && a[1] instanceof String mid)
                ? com.anthropic.claudecode.eclipse.chat.RewindService.forkOnly(activeRoot(), sid, mid) : "{}");
        rewindCodeOnlyFn = new SimpleFunction(browser, "_rewindCodeOnly", a ->
            (a.length > 1 && a[0] instanceof String sid && a[1] instanceof String mid)
                ? com.anthropic.claudecode.eclipse.chat.RewindService.restoreOnly(activeRoot(), sid, mid) : "{}");
        // Message ids for a session, in render order — lets a bubble sent THIS run
        // (which has no id until the CLI has written it) find the line it owns.
        messageIdsFn = new SimpleFunction(browser, "_messageIds", a ->
            (a.length > 0 && a[0] instanceof String sid) ? safeMessageIds(sid) : "[]");
        // Permanent per-message delete. After the transcript is edited the tab's
        // live process is dropped (not reset — the conversation survives), so the
        // next send resumes from the edited file instead of the stale in-memory
        // context that still holds the deleted text.
        deleteMessageFn = new SimpleFunction(browser, "_deleteMessage", a -> {
            if (a.length < 3 || !(a[0] instanceof String ti)
                    || !(a[1] instanceof String sid) || !(a[2] instanceof String mid))
                return "{\"error\":\"bad arguments\"}";
            String res = safeDeleteMessage(sid, mid);
            if (res.contains("\"ok\"")) {
                ChatProcessManager m = managers.get(ti);
                if (m != null) try { m.restartProcess(); } catch (Throwable ignored) {}
            }
            return res;
        });
        // Advisor model (/advisor): the CLI persists it GLOBALLY as "advisorModel"
        // in ~/.claude/settings.json ("fable"|"opus"|"sonnet"; absent = disabled).
        // The GUI card reads/writes that same setting so it's real, shared with the
        // terminal, and picked up by every newly spawned claude process.
        advisorGetFn = new SimpleFunction(browser, "_advisorGet", a -> advisorModelJson());
        advisorSetFn = new SimpleFunction(browser, "_advisorSet", a -> {
            setAdvisorModel((a.length > 0 && a[0] instanceof String v) ? v : "");
            return null;
        });
        // Live model/effort/thinking selection → status bar updates immediately
        // (no waiting for the next response). Runs on the SWT UI thread.
        statusSelectionFn = new SimpleFunction(browser, "_statusSelection", a -> {
            if (a.length > 0 && a[0] instanceof String ml && !ml.isEmpty()) displayModel = ml;
            if (a.length > 1 && a[1] instanceof String ef) lastEffort = ef;
            if (a.length > 2 && a[2] instanceof String th) lastThinking = !"0".equals(th);
            refreshStatusBar();
            return null;
        });
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
        // Page-local overlays (advisor card, rewind picker, lightbox) announcing themselves,
        // so the dismiss-key context can be activated for them too — Java raises none of them
        // and would otherwise never know they are up. See overlaySetOpen.
        overlayOpenFn = new SimpleFunction(browser, "_overlayOpen", a -> {
            if (a.length >= 1 && a[0] instanceof Boolean open) overlaySetOpen(open);
            return null;
        });

        // A link in a response must not replace the conversation: this webview has no
        // back button, so navigating away loses the chat until the view is closed and
        // the session reloaded from history (issue #96). JS hands links here instead.
        openExternalFn = new SimpleFunction(browser, "_openExternal", a -> {
            if (a.length > 0 && a[0] instanceof String url) openExternal(url);
            return null;
        });

        // The right-click menu's clipboard, shared with the key-binding handlers below
        // so both paths use the SWT clipboard the rest of Eclipse uses.
        clipGetFn = new SimpleFunction(browser, "_clipGet", a -> clipboardText());
        clipImagesFn = new SimpleFunction(browser, "_clipImages",
                a -> clipboardImagesJson(a.length > 0 && a[0] instanceof String s ? s : ""));
        // Images downloaded for a pasted fragment, collected since the last call.
        drainImagesFn = new SimpleFunction(browser, "_drainImages", a -> {
            java.util.List<Map<String, String>> batch = new java.util.ArrayList<>();
            for (Map<String, String> item; (item = fetchedImages.poll()) != null; ) batch.add(item);
            return new Gson().toJson(batch);
        });
        clipSetFn = new SimpleFunction(browser, "_clipSet", a -> {
            if (a.length > 0 && a[0] instanceof String s) setClipboardText(s);
            return null;
        });
        // The page reports that its editing entry points are defined. Until it does, the
        // edit handlers stay unhandled, so a key is never swallowed with no JS to receive
        // it — a script that failed to load leaves the browser's own defaults in place
        // rather than making copy/paste do nothing at all.
        editOpsReadyFn = new SimpleFunction(browser, "_editOpsReady", a -> {
            editOpsReady = true;
            ClaudeCodeView.debug("[EDIT] _editOpsReady() from page");
            return null;
        });
        // What the page makes of a keystroke, logged next to the [EDIT] lines so the two
        // can be read in order. The page only calls this while Debug mode is on — see
        // pushDebugMode.
        debugLogFn = new SimpleFunction(browser, "_debugLog", a -> {
            if (a.length > 0 && a[0] instanceof String s) ClaudeCodeView.debug(s);
            return null;
        });

        // Backstop for what the JS handler can't cancel — a window.open/target=_blank
        // that WebView2 turns into a top-level load, or a meta refresh. (Keyboard
        // activation of a link fires a real click, so that path is already covered.)
        // Veto the navigation and route it out the same way. The page itself is a
        // file: URL, and loadPage() is the only setUrl, so this never fights our own load.
        // Matched on http(s) rather than "anything but our own page URL": WebView2
        // reports that file: URL back in a different normal form than FileLocator hands
        // us, and the mismatch would blank the view. Other schemes never get this far
        // anyway — the JS handler cancels them first.
        browser.addLocationListener(org.eclipse.swt.browser.LocationListener.changingAdapter(e -> {
            String url = e.location == null ? "" : e.location;
            if (!isHttpUrl(url)) return;
            e.doit = false;
            openExternal(url);
        }));

        browser.addProgressListener(org.eclipse.swt.browser.ProgressListener.completedAdapter(e -> {
            pageLoaded = true;
            // WebView2 init is async — retry here where the webview provably exists.
            disableDevTools();
            disableZoom();
            pushTheme();             // apply the current Eclipse light/dark theme
            pushCancelHint(this);    // …and the current dismiss key, before any card exists
            hookBindingChanges();    // keep it live if the user switches scheme later
            pushAvailableModels();   // in case the model list arrived before the page loaded
            pushCliVersion();        // ditto for the CLI update banner
            pushCliModels();         // ditto for the installed binary's model support
            pushEditKeyHints();      // label the right-click menu with the user's real keys
            pushDebugMode();         // let the page report its keys while Debug mode is on
            pushSpinnerVerbs();      // which gerund categories the working indicator cycles
            // An "Open Claude Code Here" that arrived while the view was still loading.
            String queuedRoot = pendingRootPath;
            if (queuedRoot != null) { pendingRootPath = null; openRootDirectory(queuedRoot); }
            pushScrollLock();        // the toolbar toggle outlives the page — re-apply it
            for (int ms : new int[]{50, 200, 500, 1000, 1500}) {
                Display.getCurrent().timerExec(ms, this::activateInput);
                // Re-push the theme too: the root composite's CSS-themed background may not
                // be resolved at the instant `completed` fires, so settle it a few times.
                Display.getCurrent().timerExec(ms, this::pushTheme);
                Display.getCurrent().timerExec(ms, this::verifyEditOps);
            }
        }));

        loadPage();
        ensureServerAsync();
        startContextPolling();
        // Fetch the account's actually-available models (dynamic chooser).
        ModelCatalog.fetchCuratedAsync(json -> {
            availableModelsJson = json;
            Display.getDefault().asyncExec(this::pushAvailableModels);
        });
        // The model list above says what the ACCOUNT may use; this says what the
        // INSTALLED CLI is. A CLI older than a model release can't run it (aliases
        // silently resolve to an older model), so surface the update instead.
        checkCliVersionAsync();
        // …and this says what the installed binary can ACTUALLY run, read from the
        // model ids compiled into it. More reliable than the version number, which
        // a failed self-update can leave misreporting.
        scanCliModelsAsync();
    }

    /** Reads the newest model per family out of the CLI binary, then pushes it. */
    private void scanCliModelsAsync() {
        CliModelSupport.scanAsync(configuredClaudeCmd(), json -> {
            cliModelsJson = json;
            cliThinkingDisplay = jsonFlagsContain(json, "--thinking-display");
            Display.getDefault().asyncExec(this::pushCliModels);
        });
    }

    /** True once the binary scan has SEEN --thinking-display in the installed CLI.
     *  Defaults to false, so a CLI we couldn't scan keeps the old (safe) behavior
     *  rather than risking an "unknown option" abort on every message. */
    private volatile boolean cliThinkingDisplay;

    private boolean cliSupportsThinkingDisplay() { return cliThinkingDisplay; }

    /** Looks for {@code flag} in the scan JSON's "flags" array. */
    private static boolean jsonFlagsContain(String json, String flag) {
        if (json == null || json.isEmpty()) return false;
        try {
            com.google.gson.JsonObject o =
                com.google.gson.JsonParser.parseString(json).getAsJsonObject();
            if (!o.has("flags")) return false;
            for (com.google.gson.JsonElement e : o.getAsJsonArray("flags")) {
                if (flag.equals(e.getAsString())) return true;
            }
        } catch (Exception ignored) {}
        return false;
    }

    private void pushCliModels() {
        if (cliModelsJson == null || browser == null || browser.isDisposed() || !pageLoaded) return;
        browser.execute("window.onCliModels && window.onCliModels('" + esc(cliModelsJson) + "')");
    }

    /**
     * Builds the view toolbar. Scroll Lock is deliberately the same {@code AS_CHECK_BOX}
     * action the Claude Terminal carries (see {@code ClaudeCliView#createToolBar}), down
     * to the shared {@link com.anthropic.claudecode.eclipse.Constants#IMG_SCROLL_LOCK}
     * icon.
     *
     * <p>Where the Terminal's freezes the viewport outright, this one <em>arms</em> the
     * webview's follow-tail behavior (see {@code scrollLocked} in {@code chat.js}):
     * checked, the transcript scrolls only while the user is already at the bottom and
     * holds still the moment they scroll up to read; unchecked, it follows every render
     * unconditionally, exactly as it did before the toggle existed.
     *
     * <p>View-wide rather than per-tab: the webview keeps a single scroll container
     * shared by every tab's pane, so one toggle governs every conversation in the view.
     */
    private void createToolBar() {
        IToolBarManager toolBar = getViewSite().getActionBars().getToolBarManager();

        // Same two actions, same icons, as the Claude Terminal's own toolbar (see
        // ClaudeCliView#configureActionBars) — moved out of the webview's own
        // #convo-header row for visual consistency between the two views. Unlike the
        // Terminal's (which spawn/resume a whole new CLI process directly), these call
        // back into the page: the GUI's "new session" and "history" are page-level UI
        // concepts (a new tab, an in-page searchable panel), not new OS processes.
        Action newSession = new Action("New Session") {
            @Override
            public void run() { pushToolbarAction("newSession"); }
        };
        newSession.setToolTipText("New Claude Session");
        newSession.setImageDescriptor(Activator.getImageDescriptor(
                com.anthropic.claudecode.eclipse.Constants.IMG_NEW_CLI_SESSION));
        toolBar.add(newSession);

        Action sessionHistory = new Action("Session history") {
            @Override
            public void run() { pushToolbarAction("openHistoryFromToolbar"); }
        };
        sessionHistory.setToolTipText("Session history");
        sessionHistory.setImageDescriptor(Activator.getImageDescriptor(
                com.anthropic.claudecode.eclipse.Constants.IMG_SESSION_HISTORY));
        toolBar.add(sessionHistory);
        toolBar.add(new org.eclipse.jface.action.Separator());

        scrollLockAction = new Action("Scroll Lock", Action.AS_CHECK_BOX) {
            @Override
            public void run() { pushScrollLock(); }
        };
        scrollLockAction.setToolTipText("Scroll Lock");
        scrollLockAction.setImageDescriptor(Activator.getImageDescriptor(
                com.anthropic.claudecode.eclipse.Constants.IMG_SCROLL_LOCK));
        toolBar.add(scrollLockAction);
    }

    /** Runs a no-argument page-side function by name, e.g. from a toolbar Action's
     *  run() — the action lives outside the webview, so it has no DOM element of its
     *  own to drive the call from the page side the way an in-page button would. */
    private void pushToolbarAction(String jsFunctionName) {
        if (browser == null || browser.isDisposed() || !pageLoaded) return;
        browser.execute("window." + jsFunctionName + " && window." + jsFunctionName + "()");
    }

    /**
     * Pushes the toolbar's Scroll Lock state into the webview. Called on every toggle
     * AND on every page load: the checkbox lives in Java and survives a reload, the
     * page's own {@code scrollLocked} does not, so without the reload re-push the
     * toolbar would read "locked" while the transcript happily scrolled.
     */
    private void pushScrollLock() {
        if (browser == null || browser.isDisposed() || !pageLoaded) return;
        boolean locked = scrollLockAction != null && scrollLockAction.isChecked();
        browser.execute("window.onScrollLock && window.onScrollLock(" + locked + ")");
    }

    /** Resolves installed-vs-latest CLI versions and pushes the result to the webview. */
    private void checkCliVersionAsync() {
        CliVersionService.checkAsync(configuredClaudeCmd(), info -> {
            cliVersionJson = info.toJson();
            Display.getDefault().asyncExec(this::pushCliVersion);
        });
    }

    private void pushCliVersion() {
        if (cliVersionJson == null || browser == null || browser.isDisposed() || !pageLoaded) return;
        browser.execute("window.onCliVersion && window.onCliVersion('" + esc(cliVersionJson) + "')");
    }

    /** The configured {@code claude} command, or the default when unset. */
    private static String configuredClaudeCmd() {
        try {
            String cmd = Activator.getDefault().getPreferenceStore()
                    .getString(com.anthropic.claudecode.eclipse.Constants.PREF_CLAUDE_CMD);
            if (cmd != null && !cmd.isBlank()) return cmd;
        } catch (Throwable ignored) {}
        return com.anthropic.claudecode.eclipse.Constants.DEFAULT_CLAUDE_CMD;
    }

    // Below this perceived luminance the ambient Eclipse UI is treated as dark.
    private static final double DARK_BG_LUMINANCE_THRESHOLD = 128.0;

    /**
     * Pushes the current Eclipse theme (light/dark) to the webview, which toggles the
     * :root.light CSS token overrides. The theme is derived from the perceived luminance
     * of the widget background — the actually-painted color, so it's correct for custom
     * themes too (issue #78). Dark is the default and stays visually unchanged.
     */
    private void pushTheme() {
        if (browser == null || browser.isDisposed() || !pageLoaded) return;
        String mode = isDarkTheme() ? "dark" : "light";
        browser.execute("window.onTheme && window.onTheme('" + mode + "')");
    }

    /**
     * Whether the ambient Eclipse UI is a dark theme, from the perceived luminance of the root
     * composite's <em>actual</em> themed background. We read the widget color (set per-theme by
     * the E4 CSS engine), NOT {@code Display.getSystemColor}: on Windows the display's system
     * colors stay at the OS (light) palette even under the Dark theme, which would wrongly pin
     * the view to light. Defaults to dark if nothing is readable.
     */
    private boolean isDarkTheme() {
        try {
            org.eclipse.swt.graphics.Color bg = null;
            if (root != null && !root.isDisposed())          bg = root.getBackground();
            else if (browser != null && !browser.isDisposed()) bg = browser.getBackground();
            if (bg != null && !bg.isDisposed())
                return ColorUtils.luminance(bg.getRGB()) < DARK_BG_LUMINANCE_THRESHOLD;
        } catch (Exception ignore) {
            // fall through to the safe default
        }
        return true;   // default to dark, preserving the original look
    }

    /** Pushes the fetched model list to the webview once both are ready. */
    private void pushAvailableModels() {
        if (availableModelsJson == null || browser == null || browser.isDisposed() || !pageLoaded) return;
        // Reliably (re)assert the user's configured --model first, so the chooser shows
        // it even if the page-load _modelConfig() extraction didn't land, then the list.
        String pref = prefClaudeModel();
        if (!pref.isEmpty())
            browser.execute("window.onCustomModel && window.onCustomModel('" + esc(pref) + "')");
        browser.execute("window.onAvailableModels && window.onAvailableModels('" + esc(availableModelsJson) + "')");
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

    /** The {@code --model} the user configured in the Claude args preference, or "".
     *  This is the same model the Claude Terminal launches with, so the GUI honors
     *  it too (rather than silently using the account default). Read live, so it is
     *  reliable at send time even if the page-load extraction hasn't run. */
    static String prefClaudeModel() {
        try {
            String args = Activator.getDefault().getPreferenceStore()
                    .getString(com.anthropic.claudecode.eclipse.Constants.PREF_CLAUDE_ARGS);
            if (args != null && !args.isBlank()) {
                java.util.regex.Matcher m = java.util.regex.Pattern
                        .compile("--model[=\\s]+(\"[^\"]+\"|\\S+)").matcher(args);
                if (m.find()) return m.group(1).replace("\"", "");
            }
        } catch (Throwable ignored) {}
        return "";
    }

    private String modelConfigJson() {
        return "{\"customModel\":\"" + esc(prefClaudeModel()) + "\"}";
    }

    // ── Status bar (shared ClaudeStatusBar widget) ───────────────────────────

    /** Rebuilds the {@link ClaudeStatus} from the latest stream data + captured
     *  effort/thinking + the shared account-global limits, and pushes it to the bar.
     *  Runs on the UI thread. */
    private void refreshStatusBar() {
        if (statusBar == null || statusBar.isDisposed()) return;
        applyStatusBarEnabled();
        // Keep the widget's "waiting for status…" placeholder until we have
        // something to show: a completed turn, a chosen model, or shared limits.
        boolean haveModel = displayModel != null && !displayModel.isEmpty();
        if (lastRustStatusJson == null && !haveModel && ClaudeStatusStore.rateLimitsSchema().size() == 0) return;
        statusBar.setStatus(buildStatus());
    }

    /** Assembles a statusLine-schema document from this GUI's own data so the shared
     *  {@link ClaudeStatus#parse}/{@link ClaudeStatusBar} render it exactly like the CLI. */
    private ClaudeStatus buildStatus() {
        com.google.gson.JsonObject root = new com.google.gson.JsonObject();
        // Model: the live GUI selection or the last actual model (whichever changed
        // most recently) — so switching models updates the bar immediately.
        if (displayModel != null && !displayModel.isEmpty()) {
            com.google.gson.JsonObject m = new com.google.gson.JsonObject();
            m.addProperty("display_name", displayModel);
            root.add("model", m);
        }
        // Session data derived from the stream (context %, cost, tokens).
        String rj = lastRustStatusJson;
        if (rj != null) {
            try {
                com.google.gson.JsonObject r = com.google.gson.JsonParser.parseString(rj).getAsJsonObject();
                com.google.gson.JsonObject ctx = new com.google.gson.JsonObject();
                if (r.has("contextPct")) ctx.addProperty("used_percentage", r.get("contextPct").getAsDouble());
                if (r.has("contextWindow") && r.get("contextWindow").getAsLong() > 0)
                    ctx.addProperty("context_window_size", r.get("contextWindow").getAsLong());
                com.google.gson.JsonObject cu = new com.google.gson.JsonObject();
                copyLong(r, "inputTokens", cu, "input_tokens");
                copyLong(r, "outputTokens", cu, "output_tokens");
                copyLong(r, "cacheCreationTokens", cu, "cache_creation_input_tokens");
                copyLong(r, "cacheReadTokens", cu, "cache_read_input_tokens");
                ctx.add("current_usage", cu);
                root.add("context_window", ctx);
                if (r.has("costUsd")) {
                    com.google.gson.JsonObject cost = new com.google.gson.JsonObject();
                    cost.addProperty("total_cost_usd", r.get("costUsd").getAsDouble());
                    root.add("cost", cost);
                }
            } catch (Exception ignored) {}
        }
        // Effort + thinking (launch-time choices captured on send).
        if (lastEffort != null && !lastEffort.isEmpty()) {
            com.google.gson.JsonObject e = new com.google.gson.JsonObject();
            e.addProperty("level", lastEffort);
            root.add("effort", e);
        }
        com.google.gson.JsonObject th = new com.google.gson.JsonObject();
        th.addProperty("enabled", lastThinking);
        root.add("thinking", th);
        // Account-global usage limits shared from the CLI statusLine — identical numbers.
        com.google.gson.JsonObject rl = ClaudeStatusStore.rateLimitsSchema();
        if (rl.size() > 0) root.add("rate_limits", rl);
        return ClaudeStatus.parse(root.toString());
    }

    private static void copyLong(com.google.gson.JsonObject src, String from,
                                 com.google.gson.JsonObject dst, String to) {
        if (src.has(from)) dst.addProperty(to, src.get(from).getAsLong());
    }

    /** {@code claude-opus-4-8} → {@code "Opus 4.8"} (date suffix stripped). Mirrors the CLI's display name. */
    private static String prettyModel(String id) {
        if (id == null || id.isEmpty()) return id;
        String s = id.replaceFirst("^claude-", "").replaceFirst("-\\d{8}$", "");
        String[] parts = s.split("-");
        if (parts.length == 0 || parts[0].isEmpty()) return id;
        String fam = Character.toUpperCase(parts[0].charAt(0)) + parts[0].substring(1);
        if (parts.length == 1) return fam;
        return fam + " " + String.join(".", java.util.Arrays.copyOfRange(parts, 1, parts.length));
    }

    /** Master switch: show/hide the bar per {@code PREF_STATUSLINE_ENABLED}. */
    private void applyStatusBarEnabled() {
        if (statusBar == null || statusBar.isDisposed()) return;
        boolean enabled = true;
        try {
            enabled = Activator.getDefault().getPreferenceStore()
                    .getBoolean(com.anthropic.claudecode.eclipse.Constants.PREF_STATUSLINE_ENABLED);
        } catch (Throwable ignored) {}
        Object ld = statusBar.getLayoutData();
        if (ld instanceof org.eclipse.swt.layout.GridData gd) {
            if (gd.exclude == enabled) {   // state changed → relayout
                gd.exclude = !enabled;
                statusBar.setVisible(enabled);
                if (statusBar.getParent() != null && !statusBar.getParent().isDisposed())
                    statusBar.getParent().layout(true, true);
            }
        }
    }

    /** Live-applies status-line preference changes: any {@code PREF_STATUSLINE_*} edit
     *  (enable, per-element toggle, or refresh interval) takes effect immediately — the bar
     *  is shown/hidden and repainted without waiting for the next stream event or a restart.
     *  Mirrors the Terminal view, which registers the same listener. */
    private void registerStatusPrefListener() {
        statusPrefListener = event -> {
            String p = event.getProperty();
            if (p == null || !p.startsWith("statusline")) return;
            Display.getDefault().asyncExec(() -> {
                if (statusBar == null || statusBar.isDisposed()) return;
                refreshStatusBar();   // applyStatusBarEnabled() + repaint (re-reads toggles)
            });
        };
        Activator.getDefault().getPreferenceStore().addPropertyChangeListener(statusPrefListener);
    }

    /**
     * Live theme refresh driven by the JFace {@link org.eclipse.jface.resource.ColorRegistry},
     * the SAME signal that recolors the shared status bar the instant the Eclipse theme changes
     * (the CLI/terminal views use it too). A theme switch fires many color-property changes; on
     * any of them we re-push the theme to the webview. The {@code asyncExec} runs after the
     * current event dispatch, by which point the widgets have been restyled, so the background
     * luminance we read for detection is already the new theme's.
     */
    private void registerThemeListener() {
        themeChangeListener = event -> Display.getDefault().asyncExec(() -> {
            if (browser != null && !browser.isDisposed()) pushTheme();
        });
        org.eclipse.jface.resource.JFaceResources.getColorRegistry().addListener(themeChangeListener);
    }

    /**
     * Live key-hint refresh, for the same reason as the theme listener above: the menu has
     * to name the keys the user has NOW. {@code setFocus()} re-pushes them, but it only runs
     * on part activation, and Preferences is a dialog rather than a part — switching scheme
     * leaves this view active throughout, so nothing re-pushed until the user happened to
     * activate another part and come back. The binding manager tells us directly instead.
     */
    private void registerBindingListener() {
        org.eclipse.ui.keys.IBindingService bs =
                getSite().getService(org.eclipse.ui.keys.IBindingService.class);
        if (bs == null) return;
        bindingChangeListener = event -> {
            if (!event.isActiveSchemeChanged() && !event.isActiveBindingsChanged()) return;
            Display.getDefault().asyncExec(this::pushEditKeyHints);
        };
        bs.addBindingManagerListener(bindingChangeListener);
    }

    // --- editing commands (copy/cut/paste/select-all) -------------------------

    /**
     * Routes Eclipse's own edit commands into the webview, so copy/cut/paste/select-all
     * obey whatever the user configured under General &gt; Keys — Emacs (Alt+W / Ctrl+W /
     * Ctrl+Y), the default scheme, or a hand-customized one (issue #97). Nothing is
     * parsed or re-declared here: we supply the missing <em>handlers</em> and Eclipse's
     * own dispatcher does the matching, including multi-stroke chords and per-platform
     * modifiers.
     *
     * <p>This works only where SWT forwards key presses made inside the {@link Browser} into
     * the SWT event stream, where Eclipse's key-binding dispatcher (a {@code Display} filter)
     * sees them. The dispatcher consumes a keystroke only when it finds a handler that
     * reports itself handled; consuming clears {@code event.doit}, which SWT reports back to
     * the browser as "handled" so the page never sees the key. With no handler the command
     * isn't consumed and the key falls through to the webview, which is exactly why these
     * keys did nothing in here before.
     *
     * <p>That forwarding is per-platform, and only Windows is confirmed:
     * <ul>
     * <li><b>Windows</b> — verified. {@code Edge.handleAcceleratorKeyPressed} fires for
     *     anything held with Ctrl or Alt and calls {@code sendKeyEvent}.</li>
     * <li><b>Linux/GTK</b> — reported not working (issue #97). Note SWT's WebKit hooks
     *     {@code key_press_event} only under {@code if (!GTK.GTK4)}, with no GTK4
     *     replacement, so on GTK4 the webview emits no SWT key events at all; on GTK3 the
     *     GDK event is re-dispatched to {@code browser.handle} and should arrive. Which of
     *     the two applies is still unconfirmed.</li>
     * <li><b>macOS</b> — untested.</li>
     * </ul>
     * Plain DEL is Windows-only by construction: JFace's bug-54654 branch exempts a
     * {@code Browser} when {@code event.character == SWT.DEL}, and only the WebView2 path
     * leaves {@code character} unset, so elsewhere the exemption applies and DEL keeps the
     * webview's own behaviour.
     *
     * <p>Activation is on the view's <em>site</em>, so the handlers exist only while this
     * view is the active part and every other part keeps its own copy/paste. Text moves
     * through the SWT {@link Clipboard} — the one the rest of Eclipse uses, and the only
     * way to read a paste at all, since Chromium blocks {@code execCommand('paste')}.
     */
    private void registerEditHandlers() {
        org.eclipse.ui.handlers.IHandlerService svc =
                getSite().getService(org.eclipse.ui.handlers.IHandlerService.class);
        if (svc == null) return;
        activateEditHandler(svc, "org.eclipse.ui.edit.copy",      this::doCopy);
        activateEditHandler(svc, "org.eclipse.ui.edit.cut",       this::doCut);
        activateEditHandler(svc, "org.eclipse.ui.edit.paste",     this::doPaste);
        activateEditHandler(svc, "org.eclipse.ui.edit.selectAll", this::doSelectAll);
        // Delete is here for the opposite reason to the other four. JFace exempts a Browser
        // from the plain-DEL binding on purpose (KeyBindingDispatcher's "bug 54654" branch:
        // "pressing a delete key in a text widget will never use key bindings"), but that
        // branch tests event.character == SWT.DEL and the WebView2 path fills in only
        // keyCode, so the exemption silently stops applying and DEL reaches the dispatcher
        // like any other binding. Supplying a handler is what makes the key do something
        // again — and it follows the user's binding for free, like the rest.
        activateEditHandler(svc, "org.eclipse.ui.edit.delete",    this::doDelete);
    }

    private void activateEditHandler(org.eclipse.ui.handlers.IHandlerService svc, String commandId,
                                     Runnable op) {
        editHandlers.add(svc.activateHandler(commandId, new org.eclipse.core.commands.AbstractHandler() {
            @Override public boolean isHandled() {
                return browser != null && !browser.isDisposed() && pageLoaded && editOpsReady;
            }
            @Override public Object execute(org.eclipse.core.commands.ExecutionEvent event) {
                // The keystroke's own timestamp, straight off the SWT event the dispatcher
                // matched. Two runs of one command carrying the SAME timestamp are one
                // press reported twice; auto-repeat carries a different one each time.
                Object trigger = event.getTrigger();
                org.eclipse.swt.widgets.Event swtEvent =
                        trigger instanceof org.eclipse.swt.widgets.Event e ? e : null;
                String when = swtEvent != null ? Integer.toString(swtEvent.time) : "n/a";
                // A right-click menu invocation carries no SWT Event trigger at all, so it
                // always runs. A key-binding invocation is deduped against the last one this
                // view acted on: same keyCode/stateMask/time is the SAME physical keystroke
                // delivered again, not a fresh press (see lastHandledKeyEvent, issue #97 —
                // GTK can hand Eclipse's dispatcher one Alt-combo keystroke three times).
                // Checked before logging so the log itself says which of several same-
                // timestamp dispatches actually ran, instead of showing "execute" three
                // times over with nothing to tell them apart.
                String key = swtEvent != null
                        ? swtEvent.keyCode + "/" + swtEvent.stateMask + "/" + swtEvent.time : null;
                boolean deduped = isHandled() && key != null && key.equals(lastHandledKeyEvent);
                ClaudeCodeView.debug("[EDIT] execute " + commandId + " (handled=" + isHandled()
                        + ", pageLoaded=" + pageLoaded + ", editOpsReady=" + editOpsReady
                        + ", time=" + when + (deduped ? ", deduped" : "") + ")");
                if (!isHandled() || deduped) return null;
                if (swtEvent != null) {
                    lastHandledKeyEvent = key;
                    // On GTK only, consuming this keystroke (doit=false, set by the
                    // dispatcher once isHandled() claimed it) doesn't stop WebKit from also
                    // inserting it as text -- confirmed for the plain, unmodified key that
                    // completes an Emacs chord (Ctrl+X H selects all AND types "h", issue
                    // #97). Windows/macOS honor the consume (see registerEditHandlers'
                    // per-platform notes above), so arming there could only ever eat a
                    // later, unrelated keystroke that happens to match -- gate on GTK.
                    // Guard only a trigger that could actually produce that stray insert:
                    // no Ctrl/Alt/Command, and a printable character (not e.g. plain DEL,
                    // which is 0x7F and inserts nothing -- arming for it would leave the
                    // guard armed for the NEXT keystroke instead, silently eating the
                    // following typed letter). The armed value is the character itself, so
                    // the page only drops an insert that actually matches this keystroke.
                    char ch = swtEvent.character;
                    if ("gtk".equals(SWT.getPlatform())
                            && (swtEvent.stateMask & (SWT.CTRL | SWT.ALT | SWT.COMMAND)) == 0
                            && ch >= 0x20 && ch != 0x7F) {
                        executeJS("window.__ccArmKeyGuard && __ccArmKeyGuard(" + (int) ch + ")");
                    }
                }
                op.run();
                return null;
            }
        }));
    }

    // Each of these runs the same operation the right-click menu runs, against whatever
    // has focus. The text crosses through the _clipGet/_clipSet bridge rather than being
    // interpolated into the injected script — nothing here has to be escaped, and a
    // pasted U+2028 can't break the script it would otherwise be embedded in.
    private void doCopy()      { executeJS("window.__ccCopy && __ccCopy()"); }
    private void doCut()       { executeJS("window.__ccCut && __ccCut()"); }
    private void doPaste()     { executeJS("window.__ccPaste && __ccPaste()"); }
    private void doSelectAll() { executeJS("window.__ccSelectAll && __ccSelectAll()"); }
    private void doDelete()    { executeJS("window.__ccDelete && __ccDelete()"); }

    private void setClipboardText(String txt) {
        if (txt == null || txt.isEmpty() || browser == null || browser.isDisposed()) return;
        Clipboard cb = new Clipboard(browser.getDisplay());
        try {
            cb.setContents(new Object[] { txt }, new Transfer[] { TextTransfer.getInstance() });
        } finally {
            cb.dispose();
        }
    }

    private String clipboardText() {
        if (browser == null || browser.isDisposed()) return "";
        Clipboard cb = new Clipboard(browser.getDisplay());
        try {
            Object o = cb.getContents(TextTransfer.getInstance());
            return o instanceof String s ? s : "";
        } finally {
            cb.dispose();
        }
    }

    /**
     * The clipboard's images as data URLs, as a JSON array — {@code []} when it holds none.
     *
     * <p>Pasting an image has to be read here rather than left to the browser. The composer's
     * own paste listener gets its bitmap from a DOM {@code paste} event's clipboardData, and
     * that event only fires for the keystroke WebView2 itself treats as paste — Ctrl+V. Once
     * the user's paste key is anything else (Emacs binds it to Ctrl+Y) no DOM paste event
     * exists to carry the image, so declining the keystroke and hoping the browser handles it
     * produces nothing at all. The SWT clipboard has the images whichever key got us here.
     *
     * <p>Both shapes a clipboard carries images in are covered: a bitmap (a screenshot), and
     * a list of file paths (images copied in the file manager, which arrive as CF_HDROP with
     * no bitmap attached — the browser used to read those off the DOM event for us).
     */
    private String clipboardImagesJson(String tabId) {
        Map<String, Object> result = new HashMap<>();
        java.util.List<String> urls = new java.util.ArrayList<>();
        java.util.List<String> remote = new java.util.ArrayList<>();
        result.put("images", urls);
        result.put("text", true);
        if (browser == null || browser.isDisposed()) return new Gson().toJson(result);
        Clipboard cb = new Clipboard(browser.getDisplay());
        try {
            String html = cb.getContents(HTMLTransfer.getInstance()) instanceof String s ? s : "";
            java.util.List<String> sources = ClipboardImages.imageSources(html);

            // Rich content — images AND words — is the one case that pastes both: the text
            // goes in the composer and the images become chips. Everything below is a copy
            // of an image on its own, where inserting the platform's text alternative (a URL,
            // a file name) instead of just attaching the image would be wrong.
            if (ClipboardImages.isRichContent(html)) {
                for (String src : sources) {
                    if (ClipboardImages.isLocal(src)) {
                        String url = ClipboardImages.toDataUrl(src);
                        if (!url.isEmpty()) urls.add(url);
                    } else {
                        remote.add(src);            // downloaded off the UI thread, below
                    }
                }
                fetchRemoteImagesAsync(tabId, remote);
                return new Gson().toJson(result);
            }
            result.put("text", false);
            if (cb.getContents(ImageTransfer.getInstance()) instanceof ImageData data) {
                // A copied image: the platform hands us the bitmap directly, so use that
                // rather than going back to the network for the <img> the fragment names.
                String url = pngDataUrl(data);
                if (!url.isEmpty()) urls.add(url);
            } else if (cb.getContents(FileTransfer.getInstance()) instanceof String[] paths) {
                for (String p : paths) {
                    String url = imageFileDataUrl(p);
                    if (!url.isEmpty()) urls.add(url);
                }
            } else if (!sources.isEmpty()) {
                for (String src : sources) {
                    if (ClipboardImages.isLocal(src)) {
                        String url = ClipboardImages.toDataUrl(src);
                        if (!url.isEmpty()) urls.add(url);
                    } else {
                        remote.add(src);
                    }
                }
                fetchRemoteImagesAsync(tabId, remote);
            }
            if (urls.isEmpty() && remote.isEmpty()) result.put("text", true);
        } catch (Exception e) {
            result.put("images", new java.util.ArrayList<String>());
            result.put("text", true);
        } finally {
            cb.dispose();
        }
        return new Gson().toJson(result);
    }

    /**
     * Downloads the images a pasted fragment referenced, then hands each one to the webview
     * as it lands. Off the UI thread because a paste must not block on the network, and one
     * at a time because a fragment is capped at a handful of images anyway.
     *
     * <p>The target tab travels with the request rather than being read when the download
     * finishes — a second paste into another tab while this one is still running must not
     * pull these images into it.
     */
    private void fetchRemoteImagesAsync(String tabId, java.util.List<String> sources) {
        if (sources.isEmpty()) return;
        java.util.List<String> batch = new java.util.ArrayList<>(sources);
        Thread t = new Thread(() -> {
            for (String src : batch) {
                String url = ClipboardImages.toDataUrl(src);
                if (url.isEmpty()) continue;
                Map<String, String> item = new HashMap<>();
                item.put("tab", tabId == null ? "" : tabId);
                item.put("url", url);
                fetchedImages.add(item);
                Display.getDefault().asyncExec(
                        () -> executeJS("window.__ccFetchedImages && __ccFetchedImages()"));
            }
        }, "claude-paste-images");
        t.setDaemon(true);
        t.start();
    }

    private static String pngDataUrl(ImageData data) {
        try {
            ImageLoader loader = new ImageLoader();
            loader.data = new ImageData[] { data };
            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
            loader.save(out, SWT.IMAGE_PNG);
            return "data:image/png;base64,"
                    + java.util.Base64.getEncoder().encodeToString(out.toByteArray());
        } catch (Exception e) {
            return "";
        }
    }

    /** Biggest image file we'll read off the clipboard — past this, reading it would stall
     *  the UI thread for longer than a paste is worth. */
    private static final long MAX_CLIPBOARD_IMAGE_BYTES = 32L * 1024 * 1024;

    /**
     * A copied image file as a data URL. The formats the API accepts are passed through
     * byte-for-byte; other formats SWT can decode are re-encoded as PNG. Anything that
     * isn't an image, or is too big to read inline, yields "" and is skipped.
     */
    private static String imageFileDataUrl(String path) {
        try {
            Path p = Paths.get(path);
            if (!Files.isRegularFile(p) || Files.size(p) > MAX_CLIPBOARD_IMAGE_BYTES) return "";
            String name = p.getFileName().toString().toLowerCase(java.util.Locale.ROOT);
            String mime = name.endsWith(".png")  ? "image/png"
                        : name.endsWith(".jpg") || name.endsWith(".jpeg") ? "image/jpeg"
                        : name.endsWith(".gif")  ? "image/gif"
                        : name.endsWith(".webp") ? "image/webp"
                        : "";
            if (!mime.isEmpty()) {
                return "data:" + mime + ";base64,"
                        + java.util.Base64.getEncoder().encodeToString(Files.readAllBytes(p));
            }
            if (!(name.endsWith(".bmp") || name.endsWith(".ico")
                    || name.endsWith(".tif") || name.endsWith(".tiff"))) return "";
            ImageData[] read = new ImageLoader().load(path);
            return read.length == 0 ? "" : pngDataUrl(read[0]);
        } catch (Exception e) {
            return "";
        }
    }

    /**
     * Labels the right-click menu with the user's ACTUAL keys instead of the hardcoded
     * Ctrl+X/C/V/A. It has to: under Emacs, cut is Ctrl+W and Ctrl+X is a chord prefix,
     * so the old hints named a keystroke that does something else. Re-pushed on focus,
     * so switching scheme takes effect without a restart.
     */
    /**
     * Tells the page whether Debug mode is on, which is the only thing that makes it report
     * the keys it sees. Re-pushed on activation, so ticking the box in Preferences takes
     * effect as soon as the user clicks back into the view rather than on the next reopen.
     */
    private void pushDebugMode() {
        if (browser == null || browser.isDisposed() || !pageLoaded) return;
        browser.execute("window.__ccDebug = " + DebugModeUi.isDebugEnabled() + ";");
    }

    /**
     * Tells the page which optional slices of the working-indicator gerund list are in
     * rotation (Preferences &gt; Claude Code &gt; Miscellaneous Configuration). Sent as one
     * JSON object rather than positional booleans so adding a category later doesn't
     * change the signature on either side — which is what let {@code custom} join as an
     * array of the user's own verbs from ~/.claude/settings.json. Re-pushed on activation,
     * like the theme and Debug mode, so ticking a box takes effect on the next click into
     * the view.
     */
    private void pushSpinnerVerbs() {
        if (browser == null || browser.isDisposed() || !pageLoaded) return;
        org.eclipse.jface.preference.IPreferenceStore store =
                Activator.getDefault().getPreferenceStore();
        com.google.gson.JsonObject sets = new com.google.gson.JsonObject();
        sets.addProperty("deprecated", store.getBoolean(com.anthropic.claudecode.eclipse.Constants.PREF_SPINNER_DEPRECATED));
        sets.addProperty("pack1", store.getBoolean(com.anthropic.claudecode.eclipse.Constants.PREF_SPINNER_PACK_ONE));
        sets.addProperty("pack2", store.getBoolean(com.anthropic.claudecode.eclipse.Constants.PREF_SPINNER_PACK_TWO));
        sets.addProperty("dank", store.getBoolean(com.anthropic.claudecode.eclipse.Constants.PREF_SPINNER_DANK));
        sets.addProperty("vibecoder", store.getBoolean(com.anthropic.claudecode.eclipse.Constants.PREF_SPINNER_VIBECODER));
        com.google.gson.JsonArray custom = new com.google.gson.JsonArray();
        for (String w : com.anthropic.claudecode.eclipse.SpinnerVerbs.customVerbs(store)) custom.add(w);
        sets.add("custom", custom);
        browser.execute("window.onSpinnerVerbs && window.onSpinnerVerbs('"
                + esc(new Gson().toJson(sets)) + "')");
    }

    private void pushEditKeyHints() {
        if (browser == null || browser.isDisposed() || !pageLoaded) return;
        org.eclipse.ui.keys.IBindingService bs =
                getSite().getService(org.eclipse.ui.keys.IBindingService.class);
        if (bs == null) return;
        Map<String, String> hints = new HashMap<>();
        putKeyHint(bs, hints, "cut",       "org.eclipse.ui.edit.cut");
        putKeyHint(bs, hints, "copy",      "org.eclipse.ui.edit.copy");
        putKeyHint(bs, hints, "paste",     "org.eclipse.ui.edit.paste");
        putKeyHint(bs, hints, "selectAll", "org.eclipse.ui.edit.selectAll");
        browser.execute("window.onEditKeyHints && onEditKeyHints('"
                + esc(new Gson().toJson(hints)) + "')");
    }

    /** Always writes the key — an unbound command sends "" so the menu blanks that hint
     *  rather than keeping a default that names a keystroke the user removed. */
    private static void putKeyHint(org.eclipse.ui.keys.IBindingService bs,
                                   Map<String, String> hints, String key, String commandId) {
        String formatted = bs.getBestActiveBindingFormattedFor(commandId);
        hints.put(key, formatted == null ? "" : formatted);
    }

    /** Periodic tick so reset countdowns and shared limits stay fresh while idle. The
     *  interval honors {@code PREF_STATUSLINE_REFRESH_SECONDS} (same preference the CLI
     *  status line uses), re-read on every tick so a preference change takes effect on the
     *  next tick without a restart. */
    private void startStatusTimer() {
        Display display = Display.getDefault();
        final Runnable[] tick = new Runnable[1];
        tick[0] = () -> {
            if (statusBar == null || statusBar.isDisposed()) return;
            refreshStatusBar();
            display.timerExec(statusRefreshMillis(), tick[0]);
        };
        display.timerExec(statusRefreshMillis(), tick[0]);
    }

    /** The idle-refresh interval in ms from {@code PREF_STATUSLINE_REFRESH_SECONDS}
     *  (clamped to a sane floor), defaulting to 30s if the preference is unreadable. */
    private static int statusRefreshMillis() {
        try {
            int secs = Activator.getDefault().getPreferenceStore()
                    .getInt(com.anthropic.claudecode.eclipse.Constants.PREF_STATUSLINE_REFRESH_SECONDS);
            if (secs < 1) secs = 1;
            return secs * 1000;
        } catch (Throwable ignored) {
            return 30000;
        }
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

    private static java.nio.file.Path claudeSettingsPath() {
        return java.nio.file.Paths.get(System.getProperty("user.home"), ".claude", "settings.json");
    }

    /** Current advisor as {@code {"advisorModel":"sonnet"}} (or {@code {}} when unset). */
    private String advisorModelJson() {
        try {
            java.nio.file.Path p = claudeSettingsPath();
            if (!java.nio.file.Files.exists(p)) return "{}";
            com.google.gson.JsonObject root =
                com.google.gson.JsonParser.parseString(java.nio.file.Files.readString(p)).getAsJsonObject();
            com.google.gson.JsonObject out = new com.google.gson.JsonObject();
            String v = str(root, "advisorModel");
            if (!v.isEmpty()) out.addProperty("advisorModel", v);
            return out.toString();
        } catch (Throwable t) {
            return "{}";
        }
    }

    /** Merge-writes {@code advisorModel} into ~/.claude/settings.json (empty = remove,
     *  i.e. "No advisor" — the CLI treats an absent key as disabled). All other
     *  settings are preserved as parsed. */
    private void setAdvisorModel(String value) {
        try {
            java.nio.file.Path p = claudeSettingsPath();
            com.google.gson.JsonObject root = new com.google.gson.JsonObject();
            if (java.nio.file.Files.exists(p)) {
                try {
                    root = com.google.gson.JsonParser.parseString(java.nio.file.Files.readString(p)).getAsJsonObject();
                } catch (Exception malformed) {
                    return;   // never clobber a file we couldn't parse
                }
            }
            if (value == null || value.isEmpty()) root.remove("advisorModel");
            else root.addProperty("advisorModel", value);
            String json = new com.google.gson.GsonBuilder().setPrettyPrinting().disableHtmlEscaping()
                    .create().toJson(root);
            java.nio.file.Files.createDirectories(p.getParent());
            java.nio.file.Files.writeString(p, json);
        } catch (Throwable ignored) {}
    }
    private static String capitalizeWords(String s) {
        StringBuilder b = new StringBuilder();
        for (String w : s.trim().split("\\s+")) {
            if (w.isEmpty()) continue;
            b.append(Character.toUpperCase(w.charAt(0))).append(w.substring(1)).append(' ');
        }
        return b.toString().trim();
    }

    /** Gets (or lazily creates + fully wires) the persistent-process manager for a tab. */
    private ChatProcessManager managerFor(String tabId) {
        return managers.computeIfAbsent(tabId, id -> {
            ChatProcessManager m = new ChatProcessManager();
            m.setPersistent(true);
            m.setOnPermissionRequest((tool, input, label) -> handleControlPermission(id, tool, input, label));
            m.setOnQuestionRequest(q -> requestQuestion(id, q));
            m.setOnStatus(json -> onStatusForTab(id, json));
            wireManager(m, id);
            return m;
        });
    }

    /** Wires a manager's streaming callbacks to the webview, tagged with its tab id
     *  so concurrent streams from different tabs render into the right conversation. */
    private void wireManager(ChatProcessManager m, String tabId) {
        Display display = Display.getDefault();
        final String tj = esc(tabId);
        m.setOnStreamStart(() -> display.asyncExec(() -> executeJS("window.onStreamStart && window.onStreamStart('" + tj + "')")));
        m.setOnText(t -> display.asyncExec(() -> executeJS("window.onStreamText && window.onStreamText('" + tj + "','" + esc(t) + "')")));
        m.setOnStreamEnd(() -> display.asyncExec(() -> executeJS("window.onStreamEnd && window.onStreamEnd('" + tj + "')")));
        m.setOnToolStart(n -> display.asyncExec(() -> executeJS("window.onToolStart && window.onToolStart('" + tj + "','" + esc(n) + "')")));
        m.setOnThinking(t -> display.asyncExec(() -> executeJS("window.onThinking && window.onThinking('" + tj + "','" + esc(t) + "')")));
        m.setOnTokens(n -> display.asyncExec(() -> executeJS("window.onTokens && window.onTokens('" + tj + "','" + esc(n) + "')")));
        m.setOnRateLimit(j -> display.asyncExec(() -> executeJS("window.onRateLimit && window.onRateLimit('" + tj + "','" + esc(j) + "')")));
        m.setOnSessionId(id -> display.asyncExec(() -> executeJS("window.onSessionId && window.onSessionId('" + tj + "','" + esc(id) + "')")));
        m.setOnError(msg -> display.asyncExec(() -> executeJS("window.onError && window.onError('" + tj + "','" + esc(msg) + "')")));
        m.setOnCompact(j -> display.asyncExec(() -> executeJS("window.onCompact && window.onCompact('" + tj + "','" + esc(j) + "')")));
        // Backend "system"/init events (e.g. "Connected") are noise in the GUI — not wired.
    }

    /** Per-tab status: always let the tab learn its resolved model; update the shared
     *  status bar only for the ACTIVE tab (the bar shows one conversation at a time). */
    private void onStatusForTab(String tabId, String json) {
        statusByTab.put(tabId, json);
        String actualId = "";
        try {
            com.google.gson.JsonObject r = com.google.gson.JsonParser.parseString(json).getAsJsonObject();
            String mid = str(r, "model");
            if (mid != null && !mid.isEmpty()) actualId = mid;
        } catch (Exception ignored) {}
        final String resolvedId = actualId;
        final boolean isActive = tabId.equals(activeTabId);
        if (isActive) { lastRustStatusJson = json; if (!resolvedId.isEmpty()) displayModel = prettyModel(resolvedId); }
        // A turn just finished, so the subscription windows have actually moved —
        // this is the one moment worth paying for a refresh (see fetchUsageAsync).
        fetchUsageAsync();
        Display.getDefault().asyncExec(() -> {
            if (isActive) refreshStatusBar();
            if (!resolvedId.isEmpty() && browser != null && !browser.isDisposed() && pageLoaded) {
                browser.execute("window.onResolvedModel && window.onResolvedModel('" + esc(tabId) + "','" + esc(resolvedId) + "')");
            }
        });
    }

    /**
     * Refreshes the account-global Session/Weekly percentages by asking the CLI's
     * own {@code /usage} command, then feeds the result into the shared
     * {@link ClaudeStatusStore} so {@link #buildStatus()} picks it up on the next
     * render — the same store the Terminal's statusLine writes to.
     *
     * <p>This exists because the CLI statusLine, the store's only other producer,
     * <b>never fires for this view</b>: it is injected by {@code ClaudeCliView}
     * and print mode ({@code -p}) does not run a status line at all. Without this,
     * a user who only ever opens Claude Code sees those two segments stay empty
     * forever (issue #99).
     *
     * <p><b>Threading and cost.</b> The probe spawns a whole {@code claude}
     * process, measured at <b>~7s</b> wall time, so it runs on a plain daemon
     * thread and never the UI thread; only the repaint hops back via
     * {@code asyncExec}. It costs the user's quota nothing (the CLI answers
     * {@code /usage} locally, with no API call), but 7s of process is not free,
     * so it is throttled to at most once a minute and to one probe in flight —
     * the percentages are of 5-hour / 7-day windows and cannot move meaningfully
     * faster than that. It runs in an isolated temp directory whose transcripts
     * are purged afterwards, so it neither pollutes the session list nor loads
     * the workspace's {@code CLAUDE.md}/hooks.
     */
    private void fetchUsageAsync() {
        if (usageProbeUnavailable) return;
        // Nothing renders these numbers while the bar is switched off — don't pay
        // for a process spawn (refreshStatusBar reads the same preference).
        try {
            if (!Activator.getDefault().getPreferenceStore()
                    .getBoolean(com.anthropic.claudecode.eclipse.Constants.PREF_STATUSLINE_ENABLED)) return;
        } catch (Throwable ignored) {}

        long now = System.currentTimeMillis();
        if (now - lastUsageFetchMs < USAGE_FETCH_MIN_INTERVAL_MS) return;
        if (!usageFetchInFlight.compareAndSet(false, true)) return;
        Thread t = new Thread(() -> {
            try {
                String cmd = Activator.getDefault().getPreferenceStore()
                        .getString(com.anthropic.claudecode.eclipse.Constants.PREF_CLAUDE_CMD);
                if (cmd == null || cmd.isBlank()) cmd = "claude";
                String json = NativeCore.fetchUsage(cmd, workspaceRoot());
                if (json != null && !json.isEmpty()) {
                    ClaudeStatusStore.acceptStatusLine(json);
                    Display.getDefault().asyncExec(this::refreshStatusBar);
                }
            } catch (UnsatisfiedLinkError e) {
                // Native lib predates fetchUsage (a platform still pending its
                // rebuild) — degrade to "no percentages", exactly as the status
                // callback registration does in HttpSseServer.
                usageProbeUnavailable = true;
            } catch (Throwable ignored) {
            } finally {
                lastUsageFetchMs = System.currentTimeMillis();
                usageFetchInFlight.set(false);
            }
        }, "claude-usage-probe");
        t.setDaemon(true);
        t.start();
    }

    private void loadPage() {
        try {
            // Resolve the whole claudegui/ DIRECTORY, not just the html: the page now
            // references sibling styles/*.css and scripts/*.js, and toFileURL on a
            // single entry of a jar'd bundle would extract only that one file.
            // Extracting the directory materialises all of them next to each other.
            URL bundleUrl = Activator.getDefault().getBundle().getEntry("resources/claudegui/");
            if (bundleUrl != null) {
                URL dirUrl = FileLocator.toFileURL(bundleUrl);
                browser.setUrl(dirUrl.toString() + "claudegui.html");
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

    /** True only for absolute http/https URLs — the one shape we hand to the OS. */
    private static boolean isHttpUrl(String url) {
        if (url == null) return false;
        String lower = url.toLowerCase(java.util.Locale.ROOT);
        return lower.startsWith("http://") || lower.startsWith("https://");
    }

    /**
     * Opens a link from the webview in the system browser, leaving the conversation
     * on screen.
     *
     * <p>The URL arrives from model-generated markdown, so this scheme check is the
     * trust boundary — not the one in JS. Anything that isn't http/https ({@code file:},
     * {@code javascript:}, a UNC path, …) is dropped rather than handed to a launcher.
     */
    private static void openExternal(String url) {
        if (!isHttpUrl(url)) return;
        try {
            URL u = new java.net.URI(url).toURL();
            org.eclipse.ui.PlatformUI.getWorkbench().getBrowserSupport().getExternalBrowser().openURL(u);
        } catch (Exception e) {
            // No external browser configured, or the URL isn't RFC-clean — let the OS pick.
            try { org.eclipse.swt.program.Program.launch(url); } catch (Exception ignored) {}
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

    /**
     * The directory the ACTIVE conversation runs in — its working root ("supertab"),
     * falling back to the workspace root.
     *
     * <p>Everything the CLI keys by directory has to use this rather than
     * {@link #workspaceRoot()}: sessions live in {@code ~/.claude/projects/<hash of
     * root>/}, so listing, loading, deleting, renaming or rewinding against the
     * workspace root while the user is in a module-rooted tab would read one folder's
     * history and resume it in another.
     */
    private String activeRoot() {
        String r = activeRootPath;
        return (r == null || r.isBlank()) ? workspaceRoot() : r;
    }

    /**
     * What the trust window needs to describe a folder before Claude is first run in
     * it: {@code {path,name,exists,trusted,hasClaudeMd,inWorkspace}}. {@code path} is
     * echoed back canonicalised, so the root the page stores and the cwd we later hand
     * the CLI are the same string.
     */
    private String folderInfoJson(String raw) {
        Map<String, Object> out = new HashMap<>();
        String path = raw == null ? "" : raw.trim();
        out.put("path", path);
        out.put("name", path);
        out.put("exists", false);
        out.put("trusted", false);
        out.put("hasClaudeMd", false);
        out.put("inWorkspace", false);
        if (path.isEmpty()) return new Gson().toJson(out);
        try {
            Path dir = Paths.get(path).toAbsolutePath().normalize();
            String canonical = dir.toString();
            out.put("path", canonical);
            out.put("name", dir.getFileName() == null ? canonical : dir.getFileName().toString());
            out.put("exists", Files.isDirectory(dir));
            out.put("trusted", TrustStore.isTrusted(canonical));
            out.put("hasClaudeMd", hasClaudeMd(dir));
            String ws = TrustStore.normalize(workspaceRoot());
            String me = TrustStore.normalize(canonical);
            out.put("inWorkspace", me.equals(ws) || me.startsWith(ws + "/"));
        } catch (Exception ignored) {
            // A malformed path stays "doesn't exist, not trusted" — the window then
            // asks, which is the safe direction to fail in.
        }
        return new Gson().toJson(out);
    }

    /** Whether a CLAUDE.md is in effect for this folder — here or anywhere above it,
     *  which is how the CLI resolves its config chain. */
    private static boolean hasClaudeMd(Path dir) {
        for (Path p = dir; p != null; p = p.getParent()) {
            try { if (Files.isRegularFile(p.resolve("CLAUDE.md"))) return true; }
            catch (Exception ignored) {}
        }
        return false;
    }

    /**
     * Opens {@code path} as a working root in the GUI — the "Open Claude Code Here"
     * entry point. Selects the root if it already exists, otherwise the page creates
     * it (asking for trust first when the folder is new to us) along with a
     * conversation under it. Queued when the webview is still loading.
     */
    public void openRootDirectory(String path) {
        if (path == null || path.isBlank()) return;
        if (!pageLoaded) { pendingRootPath = path; return; }
        executeJS("window.openRootDirectory && window.openRootDirectory('" + esc(path) + "')");
    }

    @SuppressWarnings("unchecked")
    @Override
    public <T> T getAdapter(Class<T> adapter) {
        if (adapter == IShowInTarget.class) return (T) this;
        return super.getAdapter(adapter);
    }

    /**
     * Show In ▸ Claude Code — same outcome as "Open Claude Here ▸ Claude Code": the
     * selected folder (a file's parent) becomes a working root, with a conversation in
     * it. Show In opens this view if it was closed, so the page may still be loading;
     * {@link #openRootDirectory} queues the folder for the load to flush.
     */
    @Override
    public boolean show(ShowInContext context) {
        if (context == null) return false;
        ISelection selection = context.getSelection();
        if (!(selection instanceof IStructuredSelection structured)) return false;
        Object element = structured.getFirstElement();
        IResource resource = null;
        if (element instanceof IResource r) {
            resource = r;
        } else if (element instanceof IAdaptable adaptable) {
            resource = adaptable.getAdapter(IResource.class);
        }
        IResource target = (resource instanceof IFile) ? resource.getParent()
                         : (resource instanceof IContainer) ? resource : null;
        if (target == null || target.getLocation() == null) return false;
        openRootDirectory(target.getLocation().toOSString());
        return true;
    }

    // History is served by the Rust core (session.rs), which reads the CLI's
    // per-project jsonl logs directly.
    private String safeSessionList() { return safeSessionList(activeRoot()); }

    /** @param root the working root to list — captured by the caller, since the
     *  async path scans off the UI thread while the user may switch tabs. */
    private String safeSessionList(String root) {
        String json = "[]";
        try { json = NativeCore.sessionList(root); } catch (Throwable t) {}
        return mergeCustomTitles(json, root);
    }

    /** @param root the working root whose title sidecar to merge — passed in for the
     *  same reason as {@link #safeSessionList(String)}: the scan is off the UI thread. */
    private String mergeCustomTitles(String sessionsJson, String root) {
        try {
            String home = Activator.isWindows() ? System.getenv("USERPROFILE") : System.getenv("HOME");
            if (home == null || home.isEmpty()) home = System.getProperty("user.home");
            if (home == null) return sessionsJson;
            Path titlesPath = Paths.get(home, ".claude", "projects", projectHash(root), "session-titles.json");
            if (!Files.exists(titlesPath)) return sessionsJson;
            Map<String, String> titles = new Gson().fromJson(Files.readString(titlesPath), new TypeToken<Map<String, String>>(){}.getType());
            if (titles == null || titles.isEmpty()) return sessionsJson;
            List<Map<String, Object>> sessions = new Gson().fromJson(sessionsJson, new TypeToken<List<Map<String, Object>>>(){}.getType());
            if (sessions == null) return sessionsJson;
            for (var s : sessions) {
                String id = (String) s.get("sessionId");
                if (id != null && titles.containsKey(id)) s.put("display", titles.get(id));
            }
            return new Gson().toJson(sessions);
        } catch (Exception e) { return sessionsJson; }
    }

    private String safeSessionLoad(String id) {
        try { return NativeCore.sessionLoad(activeRoot(), id); }
        catch (Throwable t) { return "[]"; }
    }

    /** Message ids in render order. An older DLL has no such symbol → "[]", which
     *  the GUI reads as "no per-message actions here" rather than failing a click. */
    private String safeMessageIds(String id) {
        try { return NativeCore.sessionMessageIds(activeRoot(), id); }
        catch (Throwable t) { return "[]"; }
    }

    /** Permanent per-message delete, guarded the same way (an older DLL reports an
     *  error the dialog can show instead of throwing into the browser callback). */
    private String safeDeleteMessage(String sessionId, String messageId) {
        try { return NativeCore.sessionDeleteMessage(activeRoot(), sessionId, messageId); }
        catch (Throwable t) { return "{\"error\":\"This build's native core has no message delete.\"}"; }
    }

    /** Delete one local session file (Rust core; Java file-delete as fallback). */
    private void deleteSessionFile(String id) {
        try { if (NativeCore.sessionDelete(activeRoot(), id)) return; }
        catch (Throwable ignored) {}
        try {
            if (id == null || id.isEmpty() || id.contains("/") || id.contains("\\") || id.contains("..")) return;
            String home = Activator.isWindows() ? System.getenv("USERPROFILE") : System.getenv("HOME");
            if (home == null || home.isEmpty()) home = System.getProperty("user.home");
            if (home == null) return;
            java.nio.file.Path p = java.nio.file.Paths.get(
                    home, ".claude", "projects", projectHash(activeRoot()), id + ".jsonl");
            java.nio.file.Files.deleteIfExists(p);
        } catch (Exception ignored) {}
    }

    /**
     * Renames a session the CLI-native way so the title is SHARED with /resume and
     * every other Claude Code client (the CLI appends a {@code custom-title} event
     * to the session's own jsonl). Preferred paths, in order:
     * <ol>
     *   <li>the conversation is live in some tab → rename over that process's
     *       control channel (no extra process);</li>
     *   <li>inactive → short-lived headless {@code claude -p --resume} rename
     *       (no model turn, no cost).</li>
     * </ol>
     * On native success the legacy sidecar entry for this id is removed so a stale
     * Eclipse-only title can't mask the shared one. If both native paths fail
     * (e.g. the DLL predates chatRenameSession/sessionRename), falls back to the
     * legacy sidecar write so renaming keeps working — just Eclipse-only.
     */
    private void renameSessionFile(String id, String newTitle) {
        if (id == null || id.isEmpty() || newTitle == null || newTitle.isBlank()) return;
        final String title = newTitle.trim();
        // Live-channel rename runs HERE on the UI thread: it is one stdin write, and
        // tab disposal (chatDestroy) also runs on this thread — scanning managers from
        // a worker would race a dispose and touch a freed native handle.
        boolean live = false;
        try {
            for (ChatProcessManager m : managers.values()) {
                if (m.renameSession(id, title)) { live = true; break; }
            }
        } catch (Throwable ignored) {} // old DLL: chatRenameSession missing
        final boolean liveOk = live;
        // The offline path spawns a headless claude (seconds) — keep it off the UI
        // thread. Sidecar file IO rides along on the same worker.
        new Thread(() -> {
            boolean ok = liveOk;
            if (!ok) {
                try {
                    String claudeCmd = Activator.getDefault().getPreferenceStore()
                            .getString(com.anthropic.claudecode.eclipse.Constants.PREF_CLAUDE_CMD);
                    if (claudeCmd == null || claudeCmd.isBlank())
                        claudeCmd = com.anthropic.claudecode.eclipse.Constants.DEFAULT_CLAUDE_CMD;
                    ok = NativeCore.sessionRename(claudeCmd, activeRoot(), id, title);
                } catch (Throwable ignored) {} // old DLL: sessionRename missing
            }
            if (ok) removeSidecarTitle(id);
            else writeSidecarTitle(id, title);
        }, "claude-session-rename").start();
    }

    /** Legacy Eclipse-only rename: store the title in session-titles.json. */
    private void writeSidecarTitle(String id, String newTitle) {
        try {
            String home = Activator.isWindows() ? System.getenv("USERPROFILE") : System.getenv("HOME");
            if (home == null || home.isEmpty()) home = System.getProperty("user.home");
            if (home == null) return;
            Path titlesPath = Paths.get(home, ".claude", "projects", projectHash(activeRoot()), "session-titles.json");
            Map<String, String> titles = new HashMap<>();
            if (Files.exists(titlesPath)) {
                try { titles = new Gson().fromJson(Files.readString(titlesPath), new TypeToken<Map<String, String>>(){}.getType()); }
                catch (Exception ignored) {}
            }
            if (titles == null) titles = new HashMap<>();
            titles.put(id, newTitle);
            Files.createDirectories(titlesPath.getParent());
            Files.writeString(titlesPath, new Gson().toJson(titles));
        } catch (Exception e) { Activator.logError("Failed to rename session " + id, e); }
    }

    /**
     * Drops a session's legacy sidecar title after a successful CLI-native rename;
     * mergeCustomTitles overrides unconditionally, so a stale entry would forever
     * mask the shared custom-title this and other clients now show.
     */
    private void removeSidecarTitle(String id) {
        try {
            String home = Activator.isWindows() ? System.getenv("USERPROFILE") : System.getenv("HOME");
            if (home == null || home.isEmpty()) home = System.getProperty("user.home");
            if (home == null) return;
            Path titlesPath = Paths.get(home, ".claude", "projects", projectHash(activeRoot()), "session-titles.json");
            if (!Files.exists(titlesPath)) return;
            Map<String, String> titles = new Gson().fromJson(Files.readString(titlesPath), new TypeToken<Map<String, String>>(){}.getType());
            if (titles == null || titles.remove(id) == null) return;
            Files.writeString(titlesPath, new Gson().toJson(titles));
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
        // Re-sync the theme on activation. Eclipse doesn't fully repaint this embedded webview
        // until it's refocused, and the IThemeManager event doesn't reliably fire for the
        // General > Appearance (E4 CSS) theme, so this is the reliable path to apply a switch.
        pushTheme();
        // Same idea for the key-binding scheme: cheap to re-read, and it means a scheme
        // change shows up in the right-click menu's hints on the next activation.
        pushEditKeyHints();
        pushDebugMode();
        pushSpinnerVerbs();
    }

    /**
     * Host-driven half of the {@link #editOpsReady} handshake, and the reliable half.
     *
     * <p>The page also reports in by calling {@code _editOpsReady()} — but it does that
     * while it is still parsing, and a JS&#8594;Java call is not the same thing on every
     * platform. On Windows it is a synchronous WebView2 host object and on macOS an
     * Objective-C selector on the script bridge, both direct in-process calls; on GTK it
     * is a synchronous {@code XMLHttpRequest} to a custom {@code swt://} scheme that has
     * to round-trip through SWT's request handler. Asking the page from here instead runs
     * after {@code completed} and only needs {@code evaluate}, which is an ordinary script
     * evaluation on all three. Retried on the same schedule as {@link #activateInput()},
     * because a webview can report complete a beat before the scripts have run.
     *
     * <p>The flag stays a gate rather than an assumption: until the entry points provably
     * exist, the edit handlers report themselves unhandled and the keys keep the webview's
     * own behaviour, instead of being swallowed with no JS behind them.
     */
    private void verifyEditOps() {
        if (editOpsReady || browser == null || browser.isDisposed() || !pageLoaded) return;
        try {
            Object r = browser.evaluate(
                    "return !!(window.__ccCopy && window.__ccCut && window.__ccPaste"
                  + " && window.__ccSelectAll && window.__ccDelete);");
            if (Boolean.TRUE.equals(r)) editOpsReady = true;
            ClaudeCodeView.debug("[EDIT] verifyEditOps -> " + r);
        } catch (Exception e) {
            // evaluate() throws while the page is mid-navigation; a later retry settles it.
            ClaudeCodeView.debug("[EDIT] verifyEditOps failed: " + e);
        }
    }

    private void activateInput() {
        if (browser == null || browser.isDisposed()) return;
        browser.forceFocus();
        if (browserHwnd != 0) {
            NativeCore.browserActivateInput(browserHwnd);
        }
    }

    /**
     * Removes "Inspect" from the WebView2 right-click menu by disabling dev tools
     * ({@code AreDevToolsEnabled=false}). The default context menu itself stays
     * enabled, so cut/copy/paste/select-all keep working. Reaches into SWT's Edge
     * internals reflectively — silently a no-op on other browser backends or if a
     * future SWT rearranges the fields.
     */
    private void disableDevTools() {
        try {
            Object edge = browser.getWebBrowser();
            if (edge == null || !edge.getClass().getName().endsWith(".Edge")) return;
            java.lang.reflect.Field f = edge.getClass().getDeclaredField("settings");
            f.setAccessible(true);
            Object settings = f.get(edge);
            if (settings == null) return;
            settings.getClass().getMethod("put_AreDevToolsEnabled", boolean.class)
                    .invoke(settings, false);
        } catch (Exception ignored) {}
    }

    /**
     * Disables WebView2 zoom control ({@code IsZoomControlEnabled=false}) so Ctrl+wheel
     * and Ctrl+[+/-/0] can't zoom the panel at the host level — a belt-and-suspenders to
     * the JS wheel/keydown guards in claudegui.html. Same reflective reach into SWT's Edge
     * internals as {@link #disableDevTools()}; silently a no-op on other backends.
     */
    private void disableZoom() {
        try {
            Object edge = browser.getWebBrowser();
            if (edge == null || !edge.getClass().getName().endsWith(".Edge")) return;
            java.lang.reflect.Field f = edge.getClass().getDeclaredField("settings");
            f.setAccessible(true);
            Object settings = f.get(edge);
            if (settings == null) return;
            settings.getClass().getMethod("put_IsZoomControlEnabled", boolean.class)
                    .invoke(settings, false);
        } catch (Exception ignored) {}
    }

    /**
     * Persistent-chat permission request (stdio control channel). Derives the
     * card detail and DiffPreview content exactly like {@code ApprovalPromptTool}
     * and funnels into the same {@link #requestApproval} card flow, so both
     * routes look identical to the user. Called on a dedicated Rust thread;
     * blocks until the user decides. {@code rememberLabel} is the CLI-derived
     * label for the middle "remember" option (empty = no such option). Returns
     * "allow", "allowRemember", "deny", or "deny&lt;message&gt;".
     */
    private static String handleControlPermission(String tabId, String toolName, String inputJson, String rememberLabel) {
        com.google.gson.JsonElement input;
        try {
            input = com.google.gson.JsonParser.parseString(inputJson);
        } catch (Exception e) {
            input = new com.google.gson.JsonObject();
        }
        String[] proposal = com.anthropic.claudecode.eclipse.tools.ApprovalPromptTool
                .proposedContentFor(toolName, input);
        String decision = requestApproval(toolName,
                com.anthropic.claudecode.eclipse.tools.ApprovalPromptTool.detailOf(input),
                proposal != null ? proposal[0] : null,
                proposal != null ? proposal[1] : null,
                rememberLabel, tabId);
        return decision == null ? "deny" : decision;
    }

    /** Legacy overload (MCP {@code ApprovalPromptTool}) — no CLI-derived remember
     *  label, so it shows the static "allow all edits this session" option; routes
     *  the card to the active tab. */
    public static String requestApproval(String toolName, String detail,
                                         String filePath, String proposedContent) {
        return requestApproval(toolName, detail, filePath, proposedContent,
                               "Yes, allow all edits this session",
                               active != null ? active.activeTabId : "");
    }

    /** Sets the legacy session-wide auto-allow flag (MCP path only). */
    public static void setAllowAllSession(boolean on) { allowAllSession = on; }

    /**
     * Shows an in-chat decision card and blocks until the user chooses.
     * {@code rememberLabel} sets the middle option's text (empty = no middle
     * option). Returns the raw decision: "allow", "allowRemember", "deny", or
     * "deny&lt;message&gt;" — the caller decides how to honor "allowRemember".
     */
    public static String requestApproval(String toolName, String detail,
                                         String filePath, String proposedContent,
                                         String rememberLabel, String tabId) {
        if (allowAllSession) return "allow";
        ClaudeGuiView view = active;
        if (view == null || view.browser == null) return "deny";
        String reqId = UUID.randomUUID().toString();
        CompletableFuture<String> future = new CompletableFuture<>();
        PENDING.put(reqId, future);
        final String tid = esc(tabId == null ? "" : tabId);
        final String tn = esc(toolName == null ? "tool" : toolName);
        final String dt = esc(detail == null ? "" : detail);
        final String rl = esc(rememberLabel == null ? "" : rememberLabel);

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

        cardOpened();
        Display.getDefault().asyncExec(() -> {
            if (view.browser != null && !view.browser.isDisposed() && view.pageLoaded) {
                pushCancelHint(view);
                view.browser.execute("window.onApprovalRequest && window.onApprovalRequest('"
                        + tid + "','" + reqId + "','" + tn + "','" + dt + "','" + rl + "')");
            } else {
                CompletableFuture<String> f = PENDING.remove(reqId);
                if (f != null) f.complete("deny");
            }
        });
        try {
            long seconds = com.anthropic.claudecode.eclipse.Constants.resolveTimeoutSeconds(
                    Activator.getDefault().getPreferenceStore(),
                    com.anthropic.claudecode.eclipse.Constants.PREF_APPROVAL_TIMEOUT_MODE,
                    com.anthropic.claudecode.eclipse.Constants.PREF_APPROVAL_TIMEOUT_SECONDS);
            return future.get(seconds, TimeUnit.SECONDS);
        } catch (TimeoutException e) {
            // The card is still on screen (the user never answered): the JS side has
            // no idea we're about to give up on its behalf, so tell it to tear the
            // card down and show what happened — otherwise it sits there forever
            // even though the CLI has already moved on with this "deny".
            dismissTimedOutCard(reqId);
            return "deny";
        } catch (Exception e) {
            return "deny";
        } finally {
            cardClosed();
            PENDING.remove(reqId);
            if (preview[0] != null) {
                try { com.anthropic.claudecode.eclipse.tools.DiffPreview.close(preview[0]); }
                catch (Exception ignored) {}
            }
        }
    }

    // ── Card key binding (Esc / Ctrl+G under Emacs) ─────────────────────────────────

    private static final String CARD_CONTEXT_ID =
            "com.anthropic.claudecode.eclipse.contexts.cardOpen";
    private static final String DISMISS_COMMAND_ID =
            "com.anthropic.claudecode.eclipse.commands.dismissCard";

    private static final Object CARD_LOCK = new Object();
    private static int cardDepth;
    private static org.eclipse.ui.contexts.IContextActivation cardActivation;

    /**
     * Activates the card key-binding context. Called by every card raiser before the card
     * goes up, and paired with {@link #cardClosed()} in a {@code finally} — a leaked
     * activation would leave Esc bound to "dismiss a card" with no card on screen.
     *
     * <p>Depth-counted rather than a boolean: the raisers block, but nothing structurally
     * prevents a second card while one is up, and an inner card's close must not deactivate
     * the outer one's binding.
     */
    static void cardOpened() {
        synchronized (CARD_LOCK) {
            if (++cardDepth != 1) return;
        }
        Display.getDefault().asyncExec(() -> {
            synchronized (CARD_LOCK) {
                // Re-check under the lock: a card that opened and closed before this ran
                // must not leave the context activated behind it.
                if (cardDepth == 0 || cardActivation != null) return;
                org.eclipse.ui.contexts.IContextService svc = contextService();
                if (svc != null) cardActivation = svc.activateContext(CARD_CONTEXT_ID);
            }
        });
    }

    /** Deactivates the card context once the last open card has resolved. */
    static void cardClosed() {
        synchronized (CARD_LOCK) {
            if (cardDepth > 0 && --cardDepth != 0) return;
        }
        Display.getDefault().asyncExec(() -> {
            synchronized (CARD_LOCK) {
                if (cardDepth != 0 || cardActivation == null) return;
                org.eclipse.ui.contexts.IContextService svc = contextService();
                if (svc != null) {
                    try { svc.deactivateContext(cardActivation); } catch (Exception ignored) {}
                }
                cardActivation = null;
            }
        });
    }

    /**
     * Page-local overlays (advisor card, rewind picker, lightbox) telling us they are open,
     * via the {@code _overlayOpen} browser function. Nothing on the Java side raises them, so
     * without this the key context never activates for them and their cancel key stays dead.
     *
     * <p>Edge-triggered on purpose: repeated {@code true}s (or {@code false}s) are ignored, so
     * an overlay that is torn down without unregistering — the advisor card can be replaced
     * mid-turn by a blocking card — can only leak one activation, which the next
     * open/close corrects. A counter would accumulate that leak instead.
     */
    static void overlaySetOpen(boolean open) {
        synchronized (CARD_LOCK) {
            if (open == overlayOpen) return;
            overlayOpen = open;
        }
        if (open) cardOpened(); else cardClosed();
    }

    private static boolean overlayOpen;

    private static org.eclipse.ui.contexts.IContextService contextService() {
        try {
            return org.eclipse.ui.PlatformUI.getWorkbench()
                    .getService(org.eclipse.ui.contexts.IContextService.class);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * The printable label of whatever key currently dismisses a card — "Esc" under the
     * default scheme, "Ctrl+G" under Emacs, or whatever the user rebound it to in
     * Preferences &gt; Keys. Empty when nothing is bound, which the page renders as no hint
     * at all rather than promising a key that does nothing.
     *
     * <p>Must be called on the UI thread. Reading it per card rather than caching it is
     * deliberate: the scheme can change while the view is open.
     */
    static String cancelKeyLabel() {
        try {
            org.eclipse.ui.keys.IBindingService bs = org.eclipse.ui.PlatformUI.getWorkbench()
                    .getService(org.eclipse.ui.keys.IBindingService.class);
            if (bs == null) return "";

            // Deliberately NOT getBestActiveBindingFormattedFor. That reports only bindings
            // whose context is currently ACTIVE, and ours is scoped to cardOpen — inactive
            // whenever no card is up, which is precisely when the hint has to be drawn. It
            // answers empty at page load and hides every hint. Walking the binding table is
            // context-independent and still honours the active scheme and user rebindings,
            // since getBindings() returns the live set.
            org.eclipse.jface.bindings.Scheme scheme = bs.getActiveScheme();
            String schemeId = scheme != null ? scheme.getId() : null;
            org.eclipse.jface.bindings.Binding[] all = bs.getBindings();
            if (all == null) return "";

            String fallback = "";
            for (org.eclipse.jface.bindings.Binding b : all) {
                if (b == null) continue;
                org.eclipse.core.commands.ParameterizedCommand pc = b.getParameterizedCommand();
                // A null command is a deletion marker — the user unbound the key. Skipping it
                // (rather than treating it as a match) is what makes an unbound command
                // report "", which the page renders as no hint at all.
                if (pc == null || pc.getCommand() == null) continue;
                if (!DISMISS_COMMAND_ID.equals(pc.getCommand().getId())) continue;
                org.eclipse.jface.bindings.TriggerSequence ts = b.getTriggerSequence();
                if (ts == null || ts.isEmpty()) continue;
                if (schemeId != null && schemeId.equals(b.getSchemeId())) return ts.format();
                // Another scheme's binding: remember it, but keep looking for the active
                // scheme's. Only used when the active scheme defines none.
                if (fallback.isEmpty()) fallback = ts.format();
            }
            return fallback;
        } catch (Exception ignored) {
            // No workbench (headless/shutdown) — fall through to "no hint".
        }
        return "";
    }

    /**
     * Invoked by {@link com.anthropic.claudecode.eclipse.ui.handlers.DismissCardHandler}
     * when the bound key is pressed. Routes to the page's own cancel path so the keyboard
     * and in-page routes stay identical.
     */
    public static void dismissActiveCard() {
        ClaudeGuiView view = active;
        if (view == null || view.browser == null || view.browser.isDisposed()
                || !view.pageLoaded) {
            return;
        }
        view.browser.execute("window.cancelActiveCard && window.cancelActiveCard()");
    }

    /** Pushes the current cancel-key label to the page. UI thread; call before raising a card. */
    private static void pushCancelHint(ClaudeGuiView view) {
        if (view.browser == null || view.browser.isDisposed() || !view.pageLoaded) return;
        view.browser.execute("window.setCancelHint && window.setCancelHint('"
                + esc(cancelKeyLabel()) + "')");
    }

    /**
     * Repaints every visible hint the moment Eclipse's bindings change — picking Emacs in
     * Preferences &gt; Keys and hitting Apply has to update the text there and then, not on
     * the next card.
     *
     * <p>The signal is the binding service's own listener, the same shape as taking live
     * theme changes off the JFace {@code ColorRegistry}: the component that owns the state
     * publishes the change, so nothing here has to poll or guess when to re-read.
     * {@code setCancelHint} is a no-op when the label is unchanged, so the noisier events
     * (locale, platform) cost nothing.
     */
    private org.eclipse.jface.bindings.IBindingManagerListener bindingListener;

    private void hookBindingChanges() {
        try {
            org.eclipse.ui.keys.IBindingService bs = org.eclipse.ui.PlatformUI.getWorkbench()
                    .getService(org.eclipse.ui.keys.IBindingService.class);
            if (bs == null) return;
            bindingListener = event -> {
                if (!event.isActiveSchemeChanged() && !event.isActiveBindingsChanged()) return;
                if (browser == null || browser.isDisposed() || !pageLoaded) return;
                pushCancelHint(this);
            };
            bs.addBindingManagerListener(bindingListener);
        } catch (Exception e) {
            // No binding service (headless/shutdown) — hints simply stay as last pushed.
            bindingListener = null;
        }
    }

    private void unhookBindingChanges() {
        if (bindingListener == null) return;
        try {
            org.eclipse.ui.keys.IBindingService bs = org.eclipse.ui.PlatformUI.getWorkbench()
                    .getService(org.eclipse.ui.keys.IBindingService.class);
            if (bs != null) bs.removeBindingManagerListener(bindingListener);
        } catch (Exception ignored) {
        } finally {
            bindingListener = null;
        }
    }

    /** Tells the page a card's Java-side wait timed out, so it can dismiss the
     *  card presentation-only — the CLI already has its answer (see the
     *  matching JS comment on registerCardTimeout in cards.js). Safe to call for
     *  a card that already resolved itself (window.dismissTimedOutCard no-ops). */
    private static void dismissTimedOutCard(String reqId) {
        ClaudeGuiView view = active;
        if (view == null || view.browser == null) return;
        final String rid = esc(reqId);
        Display.getDefault().asyncExec(() -> {
            if (view.browser != null && !view.browser.isDisposed() && view.pageLoaded) {
                view.browser.execute("window.dismissTimedOutCard && window.dismissTimedOutCard('" + rid + "')");
            }
        });
    }

    /**
     * Called (off the UI thread) by {@code AskUserQuestionTool}. Renders the
     * multiple-choice card in the GUI and blocks until the user submits, returning
     * the answers as a JSON array string ({@code [{header,question,answer}]}) or
     * {@code "[]"} if dismissed.
     */
    /** Legacy overload (MCP {@code AskUserQuestionTool}) — routes the card to the active tab. */
    public static String requestQuestion(String questionsJson) {
        return requestQuestion(active != null ? active.activeTabId : "", questionsJson);
    }

    /**
     * Whether an in-chat question card can actually be shown right now — i.e. whether a GUI
     * view is open with its page loaded.
     *
     * <p>For callers that have a text fallback and need to tell two cases apart:
     * {@link #requestQuestion} answers {@code "[]"} both when the user dismissed a card and
     * when no card was ever rendered (no GUI view — the call came from the Terminal view, or
     * during shutdown). Those mean opposite things. Check this first and the caller can say
     * "name one of these" instead of claiming the user cancelled something they never saw.
     */
    public static boolean canAskQuestion() {
        ClaudeGuiView view = active;
        return view != null && view.browser != null && !view.browser.isDisposed() && view.pageLoaded;
    }

    public static String requestQuestion(String tabId, String questionsJson) {
        ClaudeGuiView view = active;
        if (view == null || view.browser == null) return "[]";
        String reqId = UUID.randomUUID().toString();
        CompletableFuture<String> future = new CompletableFuture<>();
        QPENDING.put(reqId, future);
        final String tid = esc(tabId == null ? "" : tabId);
        final String qjson = esc(questionsJson == null ? "[]" : questionsJson);
        cardOpened();
        Display.getDefault().asyncExec(() -> {
            if (view.browser != null && !view.browser.isDisposed() && view.pageLoaded) {
                pushCancelHint(view);
                view.browser.execute("window.onAskQuestion && window.onAskQuestion('"
                        + tid + "','" + reqId + "','" + qjson + "')");
            } else {
                CompletableFuture<String> f = QPENDING.remove(reqId);
                if (f != null) f.complete("[]");
            }
        });
        try {
            long seconds = com.anthropic.claudecode.eclipse.Constants.resolveTimeoutSeconds(
                    Activator.getDefault().getPreferenceStore(),
                    com.anthropic.claudecode.eclipse.Constants.PREF_QUESTION_TIMEOUT_MODE,
                    com.anthropic.claudecode.eclipse.Constants.PREF_QUESTION_TIMEOUT_SECONDS);
            return future.get(seconds, TimeUnit.SECONDS);
        } catch (TimeoutException e) {
            dismissTimedOutCard(reqId);   // see the matching comment in requestApproval
            return "[]";
        } catch (Exception e) {
            return "[]";
        } finally {
            cardClosed();
            QPENDING.remove(reqId);
        }
    }

    @Override
    public void dispose() {
        if (active == this) active = null;
        unhookBindingChanges();
        contextPolling = false;
        if (statusPrefListener != null) {
            try { Activator.getDefault().getPreferenceStore().removePropertyChangeListener(statusPrefListener); }
            catch (Throwable ignored) {}
            statusPrefListener = null;
        }
        if (themeChangeListener != null) {
            try {
                org.eclipse.jface.resource.JFaceResources.getColorRegistry()
                        .removeListener(themeChangeListener);
            } catch (Throwable ignored) {}
            themeChangeListener = null;
        }
        if (bindingChangeListener != null) {
            try {
                org.eclipse.ui.keys.IBindingService bs =
                        getSite().getService(org.eclipse.ui.keys.IBindingService.class);
                if (bs != null) bs.removeBindingManagerListener(bindingChangeListener);
            } catch (Throwable ignored) {}
            bindingChangeListener = null;
        }
        if (!editHandlers.isEmpty()) {
            try {
                org.eclipse.ui.handlers.IHandlerService svc =
                        getSite().getService(org.eclipse.ui.handlers.IHandlerService.class);
                if (svc != null) editHandlers.forEach(svc::deactivateHandler);
            } catch (Throwable ignored) {}
            editHandlers.clear();
        }
        for (ChatProcessManager m : managers.values()) {
            try { m.stop(); } catch (Exception ignored) {}
        }
        managers.clear();
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
