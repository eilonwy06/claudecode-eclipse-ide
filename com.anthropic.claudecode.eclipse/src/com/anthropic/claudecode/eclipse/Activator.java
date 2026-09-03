package com.anthropic.claudecode.eclipse;

import java.net.URL;
import java.util.Locale;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import org.eclipse.core.runtime.ILog;
import org.eclipse.core.runtime.Platform;
import org.eclipse.jface.preference.IPreferenceStore;
import org.eclipse.jface.resource.ImageDescriptor;
import org.eclipse.jface.resource.ImageRegistry;
import org.eclipse.ui.IViewPart;
import org.eclipse.ui.IWorkbench;
import org.eclipse.ui.IWorkbenchListener;
import org.eclipse.ui.IWorkbenchPage;
import org.eclipse.ui.IWorkbenchWindow;
import org.eclipse.ui.PlatformUI;
import org.eclipse.ui.plugin.AbstractUIPlugin;
import org.osgi.framework.Bundle;
import org.osgi.framework.BundleContext;

import com.anthropic.claudecode.eclipse.bridge.PhpBridge;
import com.anthropic.claudecode.eclipse.editor.SelectionTracker;
import com.anthropic.claudecode.eclipse.mcp.McpToolRegistry;
import com.anthropic.claudecode.eclipse.server.HttpSseServer;
import com.anthropic.claudecode.eclipse.server.LockFileManager;
import com.anthropic.claudecode.eclipse.ui.ClaudeCliView;
import com.anthropic.claudecode.eclipse.ui.ClaudeCodeView;
import com.anthropic.claudecode.eclipse.ui.DebugModeUi;

public class Activator extends AbstractUIPlugin {

    private static Activator instance;
    private static final ILog LOG = Platform.getLog(Activator.class);
    private static final String IMAGE_DIR_ROOT = "icons/";

    private HttpSseServer httpSseServer;
    private McpToolRegistry toolRegistry;
    private SelectionTracker selectionTracker;
    private LockFileManager lockFileManager;
    private IWorkbenchListener workbenchShutdownListener;

    /**
     * The bridge relay, owned here rather than by the IDE Server view so its two ports
     * come up with the MCP server at launch and stay up for the life of the IDE, whether
     * or not that (debug-gated, hidden by default) view is ever opened.
     */
    private volatile PhpBridge bridge;

    /**
     * Re-establishes the relay if it dies. The relay script now keeps its listeners bound
     * across a peer hanging up, but this side does not: a disconnect leaves the Java half
     * dead and the bridge marked down, and nothing else would ever wire it back. Gated on
     * the server running, which is what couples the two lifecycles: stopping the server
     * deliberately must not have the watchdog resurrect the relay behind it.
     *
     * <p>The first tick fires immediately rather than after a delay, because it is also
     * what brings the relay up in the first place — see {@link #initializeWithConfig}.
     */
    private ScheduledExecutorService bridgeWatchdog;
    private static final long WATCHDOG_INITIAL_DELAY_SEC = 0;
    private static final long WATCHDOG_PERIOD_SEC = 3;

    /**
     * Whether a start attempt is in flight. Set under the lock and cleared under it again,
     * so the seconds spent spawning the interpreter happen with the monitor free while the
     * decision to make the attempt stays serialized. A candidate is not published on
     * {@link #bridge} until it is adopted, so this flag is the only thing that marks it.
     */
    private boolean bridgeStarting;

    /**
     * Whether the relay is meant to be up. Set when the plug-in is brought up and cleared
     * on shutdown, and checked inside the lock by both the watchdog and the starter — so a
     * tick that began before a shutdown cannot re-establish the relay after the teardown
     * has run. Deliberately not inferred from the server's state: a server that fails to
     * bind must not stop the relay from coming up (the two are independent in health),
     * while a deliberate stop must take the relay down with it (coupled in lifecycle).
     */
    private boolean bridgeEnabled;

    /**
     * Consecutive failed relay starts, used to back the watchdog off. A relay that cannot
     * bind at all (no free pair in the range, or the macOS direct-protocol path) would
     * otherwise have every tick rescan the range — and the scan probes each port with a
     * connect timeout, so a failing scan is not cheap. Retries stay frequent while the
     * cause looks transient and stretch out to a minute or so when it does not.
     */
    private int bridgeFailureStreak;
    private int bridgeTicksToSkip;
    private static final int MAX_BACKOFF_TICKS = 20;

    @Override
    public void start(BundleContext context) throws Exception {
        super.start(context);
        instance = this;
        LOG.info("Claude Code for Eclipse starting...");
        // Note: dictation captures the mic natively (cpal) in the core, so there
        // is no WebView2 getUserMedia permission to grant here.
    }

    @Override
    public void stop(BundleContext context) throws Exception {
        LOG.info("Claude Code for Eclipse stopping...");
        shutdown();
        instance = null;
        super.stop(context);
    }

    public void initialize() {
        initializeWithConfig(0, null);
    }

    /**
     * Brings up the MCP server and the bridge relay. Each subsystem is guarded
     * independently and neither gates the other: a server that is already running is
     * left alone while a dead relay is still restarted, and a relay that fails to bind
     * never prevents the server from serving. Safe to call from any of the entry points
     * that need the plug-in live (early startup, the views, the command handlers).
     */
    private void initializeWithConfig(int preferredPort, String authToken) {
        if (httpSseServer == null || !httpSseServer.isRunning()) {
            startServer(preferredPort, authToken);
        }
        // Independent of the server's state above, and deliberately after it: the relay
        // scans for free ports in the same configured range, so the server must already
        // hold (or have reclaimed) its own port before the relay looks for the rest.
        //
        // The relay is not started inline. Bringing it up means extracting a PHP runtime
        // and waiting on the interpreter's READY line, and this method runs on the UI
        // thread from the view and the preference page — so the watchdog's first tick,
        // scheduled with no initial delay, does it on its own thread instead.
        setBridgeEnabled(true);
        startBridgeWatchdog();
    }

    private synchronized void setBridgeEnabled(boolean enabled) {
        bridgeEnabled = enabled;
        // A deliberate start (launch, Restart Server, the view's toggle) is a fresh
        // attempt and must not sit behind a backoff left over from an earlier failure.
        if (enabled) {
            bridgeFailureStreak = 0;
            bridgeTicksToSkip = 0;
        }
    }

    private void startServer(int preferredPort, String authToken) {
        toolRegistry = new McpToolRegistry();
        lockFileManager = new LockFileManager();
        selectionTracker = new SelectionTracker();

        IPreferenceStore prefs = getPreferenceStore();

        NativeCore.setProxyOverrides(
            prefs.getString(Constants.PREF_HTTP_PROXY),
            prefs.getString(Constants.PREF_HTTPS_PROXY),
            prefs.getString(Constants.PREF_NO_PROXY)
        );

        try {
            NativeCore.setDebugMode(prefs.getBoolean(Constants.PREF_DEBUG_MODE));
        } catch (UnsatisfiedLinkError ignored) {
            // Native library doesn't have setDebugMode — older build, skip silently.
        }

        int portMin = prefs.getInt(Constants.PREF_PORT_MIN);
        int portMax = prefs.getInt(Constants.PREF_PORT_MAX);

        httpSseServer = new HttpSseServer(toolRegistry, portMin, portMax, preferredPort, authToken);
        httpSseServer.start();

        int port = httpSseServer.getPort();
        String token = httpSseServer.getAuthToken();
        lockFileManager.writeLockFile(port, token);

        if (prefs.getBoolean(Constants.PREF_TRACK_SELECTION)) {
            selectionTracker.start(httpSseServer);
        }

        LOG.info("Claude Code server started on port " + port);

        if (workbenchShutdownListener == null && PlatformUI.isWorkbenchRunning()) {
            workbenchShutdownListener = new IWorkbenchListener() {
                @Override
                public boolean preShutdown(IWorkbench workbench, boolean forced) {
                    disconnectAllTerminals(workbench);
                    return true;
                }
                @Override
                public void postShutdown(IWorkbench workbench) {}
            };
            PlatformUI.getWorkbench().addWorkbenchListener(workbenchShutdownListener);
        }
    }

    // ------------------------------------------------------------------
    // Bridge relay lifecycle
    // ------------------------------------------------------------------

    /**
     * Starts the relay and completes the handshake, unless it is already up. The relay
     * picks its own two free ports out of the configured range (the preference page
     * enforces room for three), so which ports it lands on is incidental — an
     * established connection is the point.
     *
     * <p>The start itself runs <em>outside</em> the lock. Standing this relay up means
     * spawning the PHP interpreter and waiting on its READY line — seconds, not the
     * microseconds a port scan costs — and holding the monitor across that would park
     * every caller of {@link #shutdown()} behind it. So the attempt is claimed under the
     * lock, run unlocked, and adopted under the lock again only if the relay is still
     * wanted by then; one that lost that race tears down its own candidate. That is what
     * lets a concurrent shutdown return without ever waiting for a start in flight.
     */
    private void startBridgeIfNeeded() {
        PhpBridge candidate;
        synchronized (this) {
            // Checked inside the lock: a watchdog tick that passed its own check before a
            // shutdown began would otherwise re-establish the relay behind the teardown.
            if (!bridgeEnabled) {
                return;
            }
            // The relay is subordinate to the MCP server — it exists to serve the server's
            // IDE instance, so it never runs beside one that is not up (failed to bind, or
            // died). The watchdog brings it in as soon as the server is back. The dependency
            // is one-way: see the tick, where a dead relay never disturbs the server.
            if (!isServerRunning()) {
                return;
            }
            // An attempt already owns this. Its candidate is not on the field yet, so none
            // of the checks below can see it — this flag is what stands in for it.
            if (bridgeStarting) {
                return;
            }
            if (bridge != null && bridge.isRunning()) {
                return;
            }
            // The macOS direct-protocol fallback is a terminal state, not a failure: keep the
            // bridge that reported it so the view still shows "Overridden" rather than
            // churning a fresh relay attempt every tick that can only fail the same way.
            if (bridge != null && bridge.isOverridden()) {
                return;
            }
            // A bridge whose relay has died still reports running==true internally, and
            // start() early-returns on that. Tear the old one down before replacing it so
            // the native client half and the interpreter process are released too.
            if (bridge != null) {
                stopBridge();
            }
            bridgeStarting = true;
            candidate = new PhpBridge();
        }

        boolean started;
        try {
            started = candidate.start(data -> {
                // Checked before decoding: this runs once per inbound relay message, and the
                // relay is now always connected, so building a string the log would only throw
                // away would put allocation on a path that carries every mirrored chat event.
                if (!DebugModeUi.isDebugEnabled()) {
                    return;
                }
                ClaudeCodeView.debug("[BRIDGE] " + new String(data, java.nio.charset.StandardCharsets.UTF_8));
            });
        } catch (Throwable t) {
            // start() is not meant to throw, but it spawns a process and touches the
            // filesystem. Letting one escape would take the watchdog thread with it.
            started = false;
            logError("Bridge relay failed to start", t);
        }

        if (!started) {
            boolean overridden = candidate.isOverridden();
            synchronized (this) {
                bridgeStarting = false;
                if (overridden) {
                    // Published anyway: "Overridden" is a state the view reports, and the
                    // guard above reads it off the field to stop retrying what cannot work.
                    bridge = candidate;
                } else {
                    noteBridgeFailure();
                }
            }
            ClaudeCodeView.debug(overridden
                    ? "[Bridge] Direct protocol active; relay not used."
                    : "[Bridge] Relay failed to start; watchdog will retry.");
            return;
        }

        boolean wanted;
        synchronized (this) {
            bridgeStarting = false;
            wanted = bridgeEnabled && isServerRunning();
            if (wanted) {
                bridge = candidate;
            }
        }
        if (!wanted) {
            // Shut down while this was starting. The teardown already ran and returned
            // without waiting, and nothing else holds this candidate, so releasing the
            // interpreter is this thread's job.
            try { candidate.stop(); } catch (Throwable ignored) {}
            ClaudeCodeView.debug("[Bridge] Relay came up after a shutdown; taking it back down.");
            return;
        }

        boolean connected = NativeCore.bridgeConnect(candidate.getPortA(), candidate.getToken());
        ClaudeCodeView.debug(connected
                ? "[Bridge] Relay up on " + candidate.getPortA() + " and " + candidate.getPortB()
                        + "; handshake complete."
                : "[Bridge] Relay up on " + candidate.getPortA() + " and " + candidate.getPortB()
                        + " but the handshake failed; watchdog will retry.");
        synchronized (this) {
            if (connected) {
                bridgeFailureStreak = 0;
                bridgeTicksToSkip = 0;
            } else {
                // Half-open is not a usable relay — drop it so the watchdog rebuilds a clean
                // pair rather than leaving a listener nobody is wired through. Guarded on
                // identity: a shutdown may already have cleared or replaced the field.
                if (bridge == candidate) {
                    stopBridge();
                } else {
                    try { candidate.stop(); } catch (Throwable ignored) {}
                }
                noteBridgeFailure();
            }
        }
    }

    /** True while this tick is being skipped for backoff, decrementing the remaining count. */
    private synchronized boolean consumeBackoffTick() {
        if (bridgeTicksToSkip > 0) {
            bridgeTicksToSkip--;
            return true;
        }
        return false;
    }

    /** Stretches the watchdog's retry interval after a failed start, up to about a minute. */
    private void noteBridgeFailure() {
        if (bridgeFailureStreak < MAX_BACKOFF_TICKS) {
            bridgeFailureStreak++;
        }
        bridgeTicksToSkip = bridgeFailureStreak;
    }

    /**
     * Takes the relay down because the MCP server it belongs to is no longer running.
     * Leaves {@code bridgeEnabled} set, so the watchdog brings the relay straight back
     * once something restarts the server — the pair goes down together and comes up
     * together without either needing to know who stopped the other.
     */
    private synchronized void stopBridgeForLostServer() {
        if (bridge == null) {
            return;
        }
        ClaudeCodeView.debug("[Bridge] MCP server is no longer running; stopping the relay with it.");
        stopBridge();
    }

    /**
     * Tears the relay down and releases both halves — the native client and the
     * interpreter process hosting the listeners. Safe to call when nothing is up.
     */
    private synchronized void stopBridge() {
        if (bridge == null) {
            return;
        }
        try { NativeCore.bridgeDisconnect(); } catch (Throwable ignored) {}
        try { bridge.stop(); } catch (Throwable ignored) {}
        bridge = null;
    }

    private synchronized void startBridgeWatchdog() {
        if (bridgeWatchdog != null) {
            return;
        }
        bridgeWatchdog = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "claude-bridge-watchdog");
            t.setDaemon(true);
            return t;
        });
        bridgeWatchdog.scheduleWithFixedDelay(() -> {
            try {
                // Crash coupling, one way only. If the server is gone without a
                // shutdown() having run — it died, or failed to rebind — take the relay
                // down with it and leave it down until the server returns. Nothing here
                // ever stops the SERVER: a relay that dies is rebuilt below and the
                // server never notices, which is what keeps live CLI sessions intact.
                if (!isServerRunning()) {
                    stopBridgeForLostServer();
                    return;
                }
                // Cheap pre-check only; startBridgeIfNeeded re-checks under the lock,
                // which is what actually makes a concurrent shutdown authoritative.
                if (isBridgeRunning() || consumeBackoffTick()) {
                    return;
                }
                startBridgeIfNeeded();
            } catch (Throwable t) {
                // A watchdog that throws is a watchdog that stops running.
                logError("Bridge watchdog tick failed", t);
            }
        }, WATCHDOG_INITIAL_DELAY_SEC, WATCHDOG_PERIOD_SEC, TimeUnit.SECONDS);
    }

    private synchronized void stopBridgeWatchdog() {
        if (bridgeWatchdog != null) {
            bridgeWatchdog.shutdownNow();
            bridgeWatchdog = null;
        }
    }

    /**
     * The live relay, or {@code null} when none is up. Read-only for the status UI, and
     * deliberately unsynchronized: the status poller reads this on the UI thread every
     * few seconds and must never block behind a watchdog restart holding the lock.
     */
    public PhpBridge getBridge() {
        PhpBridge b = bridge;
        return b;
    }

    public boolean isBridgeRunning() {
        PhpBridge b = bridge;
        return b != null && b.isRunning();
    }

    private void disconnectAllTerminals(IWorkbench workbench) {
        for (IWorkbenchWindow window : workbench.getWorkbenchWindows()) {
            for (IWorkbenchPage page : window.getPages()) {
                IViewPart view = page.findView(ClaudeCliView.VIEW_ID);
                if (view instanceof ClaudeCliView) {
                    ((ClaudeCliView) view).disconnectAllSessions();
                }
            }
        }
    }

    public void shutdown() {
        // Relay first, and before the server: the two draw from the same port range, and
        // a restart reclaims the server's previous port. Leaving the relay bound across
        // that window lets it squat the port the server is about to ask for. Clearing the
        // flag before stopping is what stops an in-flight watchdog tick from racing the
        // teardown and leaving an orphaned relay behind a stopped server.
        setBridgeEnabled(false);
        stopBridgeWatchdog();
        stopBridge();
        if (workbenchShutdownListener != null) {
            if (PlatformUI.isWorkbenchRunning()) {
                PlatformUI.getWorkbench().removeWorkbenchListener(workbenchShutdownListener);
            }
            workbenchShutdownListener = null;
        }
        if (selectionTracker != null) {
            selectionTracker.stop();
            selectionTracker = null;
        }
        if (lockFileManager != null) {
            lockFileManager.removeLockFile();
            lockFileManager = null;
        }
        if (httpSseServer != null) {
            httpSseServer.stop();
            httpSseServer = null;
        }
        toolRegistry = null;
    }

    public void restart() {
        int portToRestore = 0;
        String tokenToRestore = null;

        if (httpSseServer != null && httpSseServer.isRunning()) {
            // Keeping the same port across a restart avoids disturbing anything that
            // already knows it — but only while that port is still one we are allowed
            // to serve on. The native side tries the preferred port BEFORE it consults
            // the range, so handing it a port outside the configured range would rebind
            // there and silently ignore the range the user just set. Leaving it at 0
            // makes the scan pick a valid port instead.
            int current = httpSseServer.getPort();
            IPreferenceStore prefs = getPreferenceStore();
            int portMin = prefs.getInt(Constants.PREF_PORT_MIN);
            int portMax = prefs.getInt(Constants.PREF_PORT_MAX);
            if (current >= portMin && current <= portMax) {
                portToRestore = current;
                tokenToRestore = httpSseServer.getAuthToken();
            }
        }

        shutdown();
        initializeWithConfig(portToRestore, tokenToRestore);
    }

    public boolean isServerRunning() {
        // Read once into a local: the watchdog thread calls this while shutdown() may be
        // clearing the field, and a re-read between the null check and the call would be
        // an NPE.
        HttpSseServer server = httpSseServer;
        return server != null && server.isRunning();
    }

    public boolean hasConnectedClients() {
        return httpSseServer != null && httpSseServer.hasConnectedClients();
    }

    public HttpSseServer getHttpSseServer() {
        return httpSseServer;
    }

    public McpToolRegistry getToolRegistry() {
        return toolRegistry;
    }

    public SelectionTracker getSelectionTracker() {
        return selectionTracker;
    }

    public LockFileManager getLockFileManager() {
        return lockFileManager;
    }

    public static Activator getDefault() {
        return instance;
    }

    public static void log(String message) {
        LOG.info(message);
    }

    public static void logError(String message, Throwable t) {
        LOG.error(message, t);
    }

    public static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
    }

    public static boolean isMacOS() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("mac");
    }

    @Override
    protected void initializeImageRegistry(ImageRegistry registry) {
        putImage(registry, getBundle(), Constants.IMG_CLEAR_REFRESH, "clear_co.svg");
        putImage(registry, getBundle(), Constants.IMG_NEW_CLI_SESSION, "new_cli_session.svg");
        putImage(registry, getBundle(), Constants.IMG_SCROLL_LOCK, "scroll_lock.svg");
        putImage(registry, getBundle(), Constants.IMG_SESSION_HISTORY, "session_history.svg");
    }

    private static void putImage(ImageRegistry registry, Bundle bundle, String key, String fileName) {
        URL url = bundle.getEntry(IMAGE_DIR_ROOT + fileName);
        if (url != null) registry.put(key, ImageDescriptor.createFromURL(url));
    }

    public static ImageDescriptor getImageDescriptor(String key) {
        return getDefault().getImageRegistry().getDescriptor(key);
    }
}
