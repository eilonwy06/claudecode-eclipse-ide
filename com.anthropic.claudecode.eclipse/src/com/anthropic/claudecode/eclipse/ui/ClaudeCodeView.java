package com.anthropic.claudecode.eclipse.ui;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import org.eclipse.jface.action.Action;
import org.eclipse.jface.action.IToolBarManager;
import org.eclipse.jface.action.Separator;
import org.eclipse.jface.util.IPropertyChangeListener;
import org.eclipse.swt.SWT;
import org.eclipse.swt.custom.StyledText;
import org.eclipse.swt.dnd.Clipboard;
import org.eclipse.swt.dnd.TextTransfer;
import org.eclipse.swt.dnd.Transfer;
import org.eclipse.swt.graphics.Color;
import org.eclipse.swt.graphics.Font;
import org.eclipse.swt.graphics.GC;
import org.eclipse.swt.graphics.Image;
import org.eclipse.swt.graphics.RGB;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.layout.RowLayout;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Label;
import org.eclipse.ui.ISharedImages;
import org.eclipse.ui.IWorkbenchPage;
import org.eclipse.ui.PartInitException;
import org.eclipse.ui.PlatformUI;
import org.eclipse.ui.part.ViewPart;

import com.anthropic.claudecode.eclipse.Activator;
import com.anthropic.claudecode.eclipse.Constants;
import com.anthropic.claudecode.eclipse.NativeCore;
import com.anthropic.claudecode.eclipse.bridge.Bridge;
import com.anthropic.claudecode.eclipse.editor.UiHelper;

public class ClaudeCodeView extends ViewPart {

    public static final String VIEW_ID = "com.anthropic.claudecode.eclipse.ui.ClaudeCodeView";

    private static final int INDICATOR_SIZE = 12;

    /** The open instance, so {@link #debug} can reach this view's log from anywhere. Set once
     *  its banner is all in ({@link #goLive}); guarded by {@link #LOCK}. */
    private static ClaudeCodeView active;
    /** Guards the hand-over from {@link #PENDING} to {@link #active}, so no line is lost or shown twice. */
    private static final Object LOCK = new Object();
    /** Debug-mode lines that came while no view could show them: before it was first shown,
     *  while it was closed, or while its banner was still being written. */
    private static final PendingLog PENDING = new PendingLog(2_000, 512 * 1024);
    /** This view was closed: it never goes live after that. Guarded by {@link #LOCK}. */
    private boolean disposed;
    /** The view as created, live or still writing its banner: what {@link #toolLog} reads.
     *  UI thread only. */
    private static ClaudeCodeView created;
    private StyledText logArea;
    private Label serverIndicator;
    private Label serverLabel;
    private Label bridgeIndicator;
    private Label bridgeLabel;
    private Button launchButton;
    private Button serverToggleButton;
    /** Checked: a new log line leaves the scroll position where it is ({@link #appendLog}). */
    private Action scrollLockAction;
    private ScheduledExecutorService statusPoller;
    private volatile boolean launching = false;

    private Image greenLight;
    private Image yellowLight;
    private Image redLight;
    private Image blueLight;

    private Color logBg;
    private Color logFg;
    private IPropertyChangeListener themeChangeListener;

    // Log-area palettes. Dark is the original hardcoded look; light kicks in when
    // Eclipse is running a light theme (see applyLogTheme). Keep the dark values
    // exactly as they were so the dark experience is unchanged.
    private static final RGB LOG_BG_DARK = new RGB(30, 30, 30);
    private static final RGB LOG_FG_DARK = new RGB(220, 220, 220);
    private static final RGB LOG_BG_LIGHT = new RGB(252, 252, 252);
    private static final RGB LOG_FG_LIGHT = new RGB(38, 38, 38);

    // Below this perceived luminance the ambient UI is treated as dark.
    private static final double DARK_BG_LUMINANCE_THRESHOLD = 128.0;

    private enum Status { GREEN, YELLOW, RED, BLUE }

    @Override
    public void createPartControl(Composite parent) {
        Display display = parent.getDisplay();
        createIndicatorImages(display);

        Composite container = new Composite(parent, SWT.NONE);
        GridLayout layout = new GridLayout(1, false);
        layout.marginWidth = 10;
        layout.marginHeight = 10;
        layout.verticalSpacing = 8;
        container.setLayout(layout);

        createStatusBar(container);
        createButtonRow(container);
        createLogArea(container, display);
        createToolBar();
        created = this;
        // Not live yet: Debug-mode lines wait in PENDING until the banner below is all in,
        // last line included (logEnvironmentThenHint), so the banner always comes first.

        appendLog("Claude Code for Eclipse IDE (Unofficial) v3.2.26\n");
        appendLog("──────────────────────────────────────────────────\n\n");

        if (!Activator.getDefault().isServerRunning()) {
            appendLog("Starting HTTP+SSE server...\n");
            Activator.getDefault().initialize();
        }

        if (Activator.getDefault().isServerRunning()) {
            int port = Activator.getDefault().getHttpSseServer().getPort();
            String token = Activator.getDefault().getHttpSseServer().getAuthToken();
            appendLog("HTTP+SSE server listening on 127.0.0.1:" + port + "\n");
            appendLog("Auth token: " + token.substring(0, 8) + "...\n");
            appendLog("Lock file: ~/.claude/ide/" + port + ".lock\n\n");
        }

        // The relay is owned by the Activator and comes up with the MCP server at launch,
        // so this view only reports on it — opening or closing the view leaves it alone.
        logBridgeInfo();
        logEnvironmentThenHint();

        updateStatus();
        startStatusPoller();
        // Auto-launch of the Claude Terminal is driven from ClaudeStartup.earlyStartup()
        // (PREF_AUTO_START), so it works even though this view is debug-gated and hidden
        // by default. Triggering it here too would open a duplicate tab.
    }

    private void createIndicatorImages(Display display) {
        greenLight = createBoxImage(display,
            new Color(display, 76, 175, 80),
            new Color(display, 56, 142, 60));
        yellowLight = createBoxImage(display,
            new Color(display, 255, 193, 7),
            new Color(display, 245, 160, 0));
        redLight = createBoxImage(display,
            new Color(display, 244, 67, 54),
            new Color(display, 198, 40, 40));
        blueLight = createBoxImage(display,
            new Color(display, 33, 150, 243),
            new Color(display, 25, 118, 210));
    }

    private Image createBoxImage(Display display, Color fill, Color border) {
        Image img = new Image(display, INDICATOR_SIZE, INDICATOR_SIZE);
        GC gc = new GC(img);
        gc.setBackground(fill);
        gc.fillRectangle(1, 1, INDICATOR_SIZE - 2, INDICATOR_SIZE - 2);
        gc.setForeground(border);
        gc.drawRectangle(0, 0, INDICATOR_SIZE - 1, INDICATOR_SIZE - 1);
        gc.dispose();
        fill.dispose();
        border.dispose();
        return img;
    }

    private void createStatusBar(Composite parent) {
        Composite statusBar = new Composite(parent, SWT.NONE);
        RowLayout layout = new RowLayout(SWT.HORIZONTAL);
        layout.wrap = true;
        layout.marginWidth = 0;
        layout.marginHeight = 4;
        layout.spacing = 6;
        layout.center = true;
        statusBar.setLayout(layout);
        statusBar.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));

        // Server status group
        Composite serverGroup = new Composite(statusBar, SWT.NONE);
        RowLayout serverLayout = new RowLayout(SWT.HORIZONTAL);
        serverLayout.marginWidth = 0;
        serverLayout.marginHeight = 0;
        serverLayout.spacing = 4;
        serverLayout.center = true;
        serverGroup.setLayout(serverLayout);

        serverIndicator = new Label(serverGroup, SWT.NONE);
        serverIndicator.setImage(redLight);

        serverLabel = new Label(serverGroup, SWT.NONE);
        serverLabel.setText("Server: --");

        // Bridge status group
        Composite bridgeGroup = new Composite(statusBar, SWT.NONE);
        RowLayout bridgeLayout = new RowLayout(SWT.HORIZONTAL);
        bridgeLayout.marginWidth = 0;
        bridgeLayout.marginHeight = 0;
        bridgeLayout.spacing = 4;
        bridgeLayout.center = true;
        bridgeGroup.setLayout(bridgeLayout);

        bridgeIndicator = new Label(bridgeGroup, SWT.NONE);
        bridgeIndicator.setImage(redLight);

        bridgeLabel = new Label(bridgeGroup, SWT.NONE);
        bridgeLabel.setText("Bridge: --");
    }

    private void createButtonRow(Composite parent) {
        Composite buttonRow = new Composite(parent, SWT.NONE);
        RowLayout layout = new RowLayout(SWT.HORIZONTAL);
        layout.wrap = true;
        layout.marginWidth = 0;
        layout.marginHeight = 0;
        layout.spacing = 8;
        layout.pack = false;
        buttonRow.setLayout(layout);
        buttonRow.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));

        launchButton = new Button(buttonRow, SWT.PUSH);
        launchButton.setText("Launch Terminal");
        launchButton.addListener(SWT.Selection, e -> startClaude());

        Button resumeBtn = new Button(buttonRow, SWT.PUSH);
        resumeBtn.setText("Resume Session");
        resumeBtn.addListener(SWT.Selection, e -> restartClaude("--resume"));

        Button restartBtn = new Button(buttonRow, SWT.PUSH);
        restartBtn.setText("Restart Server");
        restartBtn.addListener(SWT.Selection, e -> restartServer());

        serverToggleButton = new Button(buttonRow, SWT.PUSH);
        serverToggleButton.setText("Stop Server");
        serverToggleButton.addListener(SWT.Selection, e -> toggleServer());
    }

    /**
     * Stops or starts the MCP server and the bridge relay together, which is the coupling
     * this view exists to make visible. Start goes through the same entry point as launch,
     * so the port scan, lock file and relay handshake all follow their normal paths.
     */
    private void toggleServer() {
        Activator activator = Activator.getDefault();
        boolean running = activator.isServerRunning();
        setServerStatus(Status.YELLOW, running ? "Stopping..." : "Starting...");
        setBridgeStatus(Status.YELLOW, running ? "Stopping..." : "Starting...");
        Display.getCurrent().update();

        Display.getCurrent().asyncExec(() -> {
            if (running) {
                activator.shutdown();
                appendLog("Server stopped; bridge relay stopped with it.\n\n");
            } else {
                activator.initialize();
                if (activator.isServerRunning()) {
                    int port = activator.getHttpSseServer().getPort();
                    appendLog("Server started on port " + port + "\n");
                    appendLog("Lock file: ~/.claude/ide/" + port + ".lock\n");
                } else {
                    appendLog("[WARN] Server failed to start.\n");
                }
                logBridgeInfo();
            }
            updateStatus();
        });
    }

    /**
     * Copy Logs and Clear Logs, in the view's toolbar where the Claude Terminal and the Claude
     * Code view keep their session buttons, with the icons the Terminal's own Copy and Clear
     * use. Both act on the whole log as the view shows it. Scroll Lock follows them past a
     * separator, as in those views, and starts from the same preference.
     */
    private void createToolBar() {
        IToolBarManager toolBar = getViewSite().getActionBars().getToolBarManager();
        Action copyLogs = new Action("Copy Logs") {
            @Override
            public void run() { copyLogs(); }
        };
        copyLogs.setToolTipText("Copy Logs");
        copyLogs.setImageDescriptor(PlatformUI.getWorkbench().getSharedImages()
                .getImageDescriptor(ISharedImages.IMG_TOOL_COPY));
        Action clearLogs = new Action("Clear Logs") {
            @Override
            public void run() { clearLogs(); }
        };
        clearLogs.setToolTipText("Clear Logs");
        clearLogs.setImageDescriptor(Activator.getImageDescriptor(Constants.IMG_CLEAR_REFRESH));
        toolBar.add(copyLogs);
        toolBar.add(clearLogs);
        toolBar.add(new Separator());

        // Read by appendLog on each line, so nothing needs doing when it is toggled.
        scrollLockAction = new Action("Scroll Lock", Action.AS_CHECK_BOX) {};
        scrollLockAction.setToolTipText("Scroll Lock");
        scrollLockAction.setImageDescriptor(Activator.getImageDescriptor(Constants.IMG_SCROLL_LOCK));
        // A configured default for a newly created view instance (Preferences > Claude Code),
        // not a remembered last state, as in ClaudeGuiView and ClaudeCliView.
        scrollLockAction.setChecked(Activator.getDefault().getPreferenceStore()
                .getBoolean(Constants.PREF_SCROLL_LOCK_DEFAULT));
        toolBar.add(scrollLockAction);
    }

    /** Puts the whole log on the clipboard, as text. An empty log puts nothing there. */
    private void copyLogs() {
        if (logArea == null || logArea.isDisposed()) return;
        String text = logArea.getText();
        if (text.isEmpty()) return;
        Clipboard clipboard = new Clipboard(logArea.getDisplay());
        try {
            clipboard.setContents(new Object[] { text }, new Transfer[] { TextTransfer.getInstance() });
        } finally {
            clipboard.dispose();
        }
    }

    /** Empties the log. What comes after is written as before. */
    private void clearLogs() {
        if (logArea != null && !logArea.isDisposed()) logArea.setText("");
    }

    private void createLogArea(Composite parent, Display display) {
        logArea = new StyledText(parent, SWT.MULTI | SWT.V_SCROLL | SWT.H_SCROLL | SWT.READ_ONLY | SWT.BORDER);
        logArea.setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, true));
        logArea.setWordWrap(true);

        Font monoFont = new Font(display, "Consolas", 10, SWT.NORMAL);
        logArea.setFont(monoFont);
        logArea.setLeftMargin(8);
        logArea.setTopMargin(8);
        logArea.addDisposeListener(e -> monoFont.dispose());

        applyLogTheme(display);

        // Live refresh driven by the JFace ColorRegistry — the same signal that recolors the
        // shared status bar the instant the Eclipse theme changes (the terminal view uses it too).
        // A theme switch fires many color-property changes; on any of them we re-apply. The
        // asyncExec runs after the current dispatch, once the widgets have been restyled, so the
        // background luminance we read is already the new theme's.
        themeChangeListener = event -> display.asyncExec(() -> {
            if (logArea != null && !logArea.isDisposed()) applyLogTheme(logArea.getDisplay());
        });
        org.eclipse.jface.resource.JFaceResources.getColorRegistry().addListener(themeChangeListener);
    }

    /**
     * Applies the log-area colors for the current Eclipse theme. The dark palette is
     * the original look and stays unchanged; a light palette is used only when the
     * ambient UI is light (issue #78). Existing colors are disposed before replacing.
     */
    private void applyLogTheme(Display display) {
        if (logArea == null || logArea.isDisposed()) return;
        boolean dark = isDarkTheme(display);
        RGB bg = dark ? LOG_BG_DARK : LOG_BG_LIGHT;
        RGB fg = dark ? LOG_FG_DARK : LOG_FG_LIGHT;

        Color newBg = new Color(display, bg);
        Color newFg = new Color(display, fg);
        logArea.setBackground(newBg);
        logArea.setForeground(newFg);

        if (logBg != null && !logBg.isDisposed()) logBg.dispose();
        if (logFg != null && !logFg.isDisposed()) logFg.dispose();
        logBg = newBg;
        logFg = newFg;
    }

    /**
     * Whether the ambient Eclipse UI is a dark theme, from the perceived luminance of the log
     * area's <em>actual</em> themed background. We read the widget color (set per-theme by the
     * E4 CSS engine), NOT {@code Display.getSystemColor}: on Windows the display's system colors
     * stay at the OS (light) palette even under the Dark theme, which would wrongly pin the view
     * to light. Defaults to dark if nothing is readable.
     */
    private boolean isDarkTheme(Display display) {
        try {
            if (logArea != null && !logArea.isDisposed()) {
                Color bg = logArea.getBackground();
                if (bg != null && !bg.isDisposed())
                    return ColorUtils.luminance(bg.getRGB()) < DARK_BG_LUMINANCE_THRESHOLD;
            }
        } catch (Exception ignore) {
            // fall through to the safe default
        }
        return true;
    }

    private void restartServer() {
        setServerStatus(Status.YELLOW, "Restarting...");
        setBridgeStatus(Status.YELLOW, "Reconnecting...");
        Display.getCurrent().update();

        Display.getCurrent().asyncExec(() -> {
            Activator.getDefault().restart();
            int newPort = Activator.getDefault().getHttpSseServer().getPort();
            String newToken = Activator.getDefault().getHttpSseServer().getAuthToken();
            appendLog("Server restarted on port " + newPort + "\n");
            appendLog("New token: " + newToken.substring(0, 8) + "...\n");
            appendLog("Lock file updated: ~/.claude/ide/" + newPort + ".lock\n");

            // Activator.restart() cycles the relay along with the server (and in the
            // order that lets the server reclaim its port first), so this view must not
            // cycle it a second time here.
            logBridgeInfo();
            updateStatus();

            // Restart all CLI sessions so they reconnect with new MCP credentials
            try {
                IWorkbenchPage page = UiHelper.getActivePage();
                if (page != null) {
                    ClaudeCliView cliView = (ClaudeCliView) page.findView(ClaudeCliView.VIEW_ID);
                    if (cliView != null) {
                        appendLog("Restarting CLI sessions...\n");
                        cliView.restartAllSessions();
                        appendLog("CLI sessions restarted.\n\n");
                    }
                }
            } catch (Exception e) {
                appendLog("Could not restart CLI sessions: " + e.getMessage() + "\n\n");
            }
        });
    }

    public void startClaude(String... extraArgs) {
        if (launching) return;
        launching = true;
        try {
            IWorkbenchPage page = UiHelper.getActivePage();
            if (page == null) {
                appendLog("[ERROR] No active workbench page.\n");
                return;
            }
            ClaudeCliView cliView = (ClaudeCliView) page.showView(ClaudeCliView.VIEW_ID);
            cliView.launchProcess(extraArgs);
        } catch (PartInitException e) {
            appendLog("[ERROR] Could not open Claude Terminal view: " + e.getMessage() + "\n");
            Activator.logError("Failed to open Claude Terminal view", e);
        } finally {
            Display.getCurrent().timerExec(500, () -> launching = false);
        }
    }

    public void restartClaude(String... extraArgs) {
        startClaude(extraArgs);
    }

    // Show bridge info for Windows/Linux, or override message for macOS
    private void logBridgeInfo() {
        Bridge bridge = Activator.getDefault().getBridge();
        if (bridge != null && bridge.isOverridden()) {
            appendLog("macOS detected, direct protocol active.\n\n");
        } else if (bridge != null && bridge.isRunning()) {
            String msg = bridge.getMessage();
            if (msg != null && !msg.isEmpty()) {
                appendLog(msg + "\n");
            }
            appendLog("Bridge relay ports: " + bridge.getPortA() + " ↔ " + bridge.getPortB() + "\n\n");
        } else {
            appendLog("Bridge relay is not running.\n\n");
        }
    }

    /**
     * Logs the platform, Eclipse, Node and Claude Code versions, then the launch hint.
     * The Node and Claude versions each come from running a {@code --version}, so they
     * are read off the UI thread and both blocks are logged once they are in, keeping
     * the hint last. That ends the banner, and the view goes live ({@link #goLive}).
     */
    private void logEnvironmentThenHint() {
        String platform = platformName();
        String eclipse = joinKnown(eclipseVersion(), eclipseBuild());
        String claudeCmd = ClaudeGuiView.configuredClaudeCmd();
        Display display = Display.getCurrent();
        Thread t = new Thread(() -> {
            // A version that cannot be read shows as N/A: the banner must always end, or the
            // view would never go live.
            String node = null;
            String claude = null;
            try { node = CliVersionService.nodeVersion(); } catch (Throwable ignored) {}
            try { claude = CliVersionService.installedVersion(claudeCmd); } catch (Throwable ignored) {}
            String nodeVersion = node;
            String claudeVersion = claude;
            if (display.isDisposed()) return;
            display.asyncExec(() -> {
                appendLog("Platform: " + orNA(platform) + "\n");
                appendLog("Eclipse version: " + orNA(eclipse) + "\n");
                appendLog("Node version: " + orNA(nodeVersion) + "\n");
                appendLog("Claude Code version: " + orNA(claudeVersion) + "\n\n");
                appendLog("Click 'Launch Claude Terminal' to open the Claude Terminal.\n\n");
                goLive();
            });
        }, "claude-env-info");
        t.setDaemon(true);
        t.start();
    }

    /**
     * The banner is all in: the Debug-mode lines kept while nobody could see them follow it,
     * oldest first, and from here on {@link #debug} writes straight to this view. Taken and
     * switched over under {@link #LOCK}, so a line that comes meanwhile is either among those
     * taken or goes in after them. A view closed before its banner ended stays closed.
     */
    private void goLive() {
        String earlier;
        synchronized (LOCK) {
            if (disposed) return;
            earlier = PENDING.drainAll();
            active = this;
        }
        if (!earlier.isEmpty()) appendLog(earlier);
    }

    private static String orNA(String s) {
        return s == null || s.isBlank() ? "N/A" : s;
    }

    /** "Windows 11", "macOS 15.3", "Ubuntu 24.04.1 LTS", "FreeBSD 15.1-RELEASE", …. */
    private static String platformName() {
        String name = System.getProperty("os.name", "");
        String version = System.getProperty("os.version", "");
        if (Activator.isWindows()) return name;             // already "Windows 11"
        if (Activator.isMacOS()) return ("macOS " + version).trim();
        if (Activator.isLinux()) {
            String distro = osRelease("PRETTY_NAME");
            if (distro != null) return distro;
        }
        return (name + " " + version).trim();
    }

    /** A field of /etc/os-release (PRETTY_NAME "Ubuntu 24.04.1 LTS", NAME "Ubuntu"), or null. */
    private static String osRelease(String key) {
        String prefix = key + "=";
        try {
            for (String line : java.nio.file.Files.readAllLines(java.nio.file.Paths.get("/etc/os-release"))) {
                if (line.startsWith(prefix)) {
                    String v = line.substring(prefix.length()).trim();
                    if (v.length() >= 2 && (v.startsWith("\"") || v.startsWith("'"))) v = v.substring(1, v.length() - 1);
                    return v.isBlank() ? null : v;
                }
            }
        } catch (Exception ignore) {
            // no os-release: fall back to os.name
        }
        return null;
    }

    /** The Eclipse Platform version, e.g. "4.36.0", or null when it can't be told. */
    private static String eclipseVersion() {
        try {
            org.osgi.framework.Bundle platform = org.eclipse.core.runtime.Platform.getBundle("org.eclipse.platform");
            if (platform != null) {
                org.osgi.framework.Version v = platform.getVersion();
                return v.getMajor() + "." + v.getMinor() + "." + v.getMicro();
            }
        } catch (Throwable ignore) {
            // fall through to the build id
        }
        return System.getProperty("eclipse.buildId");
    }

    /**
     * The OS and architecture of the Eclipse build that is running, e.g. "Windows x86_64"
     * or "Ubuntu riscv64". This is the build's, not the machine's: an x86_64 Eclipse
     * running emulated on an arm64 machine says x86_64.
     */
    private static String eclipseBuild() {
        try {
            String os = org.eclipse.core.runtime.Platform.getOS();
            String osName;
            switch (os == null ? "" : os) {
                case "win32":   osName = "Windows"; break;
                case "macosx":  osName = "macOS"; break;
                case "linux":   osName = joinKnown(osRelease("NAME")); break;
                case "freebsd": osName = "FreeBSD"; break;
                default:        osName = os;
            }
            if (osName == null) osName = "Linux";     // no os-release NAME
            return joinKnown(osName, org.eclipse.core.runtime.Platform.getOSArch());
        } catch (Throwable ignore) {
            return null;
        }
    }

    /** The non-blank parts joined with spaces, or null when there are none. */
    private static String joinKnown(String... parts) {
        StringBuilder sb = new StringBuilder();
        for (String p : parts) {
            if (p == null || p.isBlank()) continue;
            if (sb.length() > 0) sb.append(' ');
            sb.append(p.trim());
        }
        return sb.length() == 0 ? null : sb.toString();
    }

    /** Diagnostic helper: native-side connection state without throwing. */
    private static boolean safeBridgeConnected() {
        try { return NativeCore.bridgeIsConnected(); } catch (Throwable t) { return false; }
    }

    private void appendLog(String text) {
        if (logArea != null && !logArea.isDisposed()) {
            logArea.append(text);
            if (scrollLockAction == null || !scrollLockAction.isChecked())
                logArea.setTopIndex(logArea.getLineCount() - 1);
        }
    }

    private void setServerStatus(Status status, String text) {
        if (serverIndicator == null || serverIndicator.isDisposed()) return;
        serverIndicator.setImage(getStatusImage(status));
        serverLabel.setText("Server: " + text);
    }

    private void setBridgeStatus(Status status, String text) {
        if (bridgeIndicator == null || bridgeIndicator.isDisposed()) return;
        bridgeIndicator.setImage(getStatusImage(status));
        bridgeLabel.setText("Bridge: " + text);
    }

    private Image getStatusImage(Status status) {
        switch (status) {
            case GREEN: return greenLight;
            case YELLOW: return yellowLight;
            case BLUE: return blueLight;
            default: return redLight;
        }
    }

    private void updateStatus() {
        if (serverLabel == null || serverLabel.isDisposed()) return;

        Activator activator = Activator.getDefault();

        if (activator.isServerRunning()) {
            int port = activator.getHttpSseServer().getPort();
            int clients = activator.getHttpSseServer().getClientCount();
            if (clients > 0) {
                setServerStatus(Status.GREEN, "Port " + port + " (" + clients + " connected)");
            } else {
                setServerStatus(Status.YELLOW, "Port " + port + " (no clients)");
            }
        } else {
            setServerStatus(Status.RED, "Stopped");
        }

        Bridge bridge = activator.getBridge();
        if (bridge != null && bridge.isOverridden()) {
            setBridgeStatus(Status.BLUE, "Overridden");
        } else if (bridge != null && bridge.isRunning()) {
            if (safeBridgeConnected()) {
                setBridgeStatus(Status.GREEN, "Connected " + bridge.getPortA() + " ↔ " + bridge.getPortB());
            } else {
                setBridgeStatus(Status.YELLOW, "Running");
            }
        } else {
            setBridgeStatus(Status.RED, "Off");
        }

        updateServerToggleLabel(activator.isServerRunning());
    }

    /** Keeps the toggle's label in step with the actual server state. */
    private void updateServerToggleLabel(boolean serverRunning) {
        if (serverToggleButton == null || serverToggleButton.isDisposed()) return;
        String want = serverRunning ? "Stop Server" : "Start Server";
        if (!want.equals(serverToggleButton.getText())) {
            serverToggleButton.setText(want);
            serverToggleButton.getParent().layout();
        }
    }

    private void startStatusPoller() {
        statusPoller = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "claude-status-poller");
            t.setDaemon(true);
            return t;
        });
        statusPoller.scheduleAtFixedRate(() -> {
            Display.getDefault().asyncExec(this::updateStatus);
        }, 2, 3, TimeUnit.SECONDS);
    }

    @Override
    public void setFocus() {
        if (launchButton != null && !launchButton.isDisposed()) {
            launchButton.setFocus();
        }
    }

    /**
     * Writes a diagnostic line to this view's log on behalf of code elsewhere in the
     * plug-in, on the same terms the bridge tracing already uses: only while Debug mode
     * is on, and only into the console the user turned Debug mode on to read. A line that
     * comes while the view cannot show it (not shown yet since startup, closed, or still
     * writing its banner) is kept in {@link #PENDING}, within its bounds, and appears below
     * the banner once the view goes live. Nothing is written to disk.
     */
    public static void debug(String message) {
        if (!DebugModeUi.isDebugEnabled()) return;
        ClaudeCodeView view;
        synchronized (LOCK) {
            view = active;
            if (view == null) {
                PENDING.add(message);
                return;
            }
        }
        Display.getDefault().asyncExec(() -> {
            if (active == view) view.appendLog(message + "\n");
        });
    }

    /** Forgets the lines kept for a view not shown yet: Debug mode was switched off. */
    public static void discardPending() {
        PENDING.clear();
    }

    /**
     * For the claudeCodeEclipse tool: what the view shows, then the lines kept for it while it
     * could not show them (not opened yet, closed, or its banner still being written), in the
     * order they would appear. "" when there is neither. UI thread.
     */
    public static String toolLog() {
        StringBuilder log = new StringBuilder();
        ClaudeCodeView view = created;
        if (view != null && view.logArea != null && !view.logArea.isDisposed()) {
            log.append(view.logArea.getText());
        }
        log.append(PENDING.snapshot());
        return log.toString();
    }

    /** For the claudeCodeEclipse tool: whether the view is open. UI thread. */
    public static boolean toolViewOpen() {
        return created != null;
    }

    @Override
    public void dispose() {
        synchronized (LOCK) {
            disposed = true;
            if (active == this) active = null;
        }
        if (created == this) created = null;
        if (themeChangeListener != null) {
            try {
                org.eclipse.jface.resource.JFaceResources.getColorRegistry()
                        .removeListener(themeChangeListener);
            } catch (Exception ignore) {
                // Workbench already gone during shutdown — nothing to remove.
            }
            themeChangeListener = null;
        }
        if (logBg != null && !logBg.isDisposed()) logBg.dispose();
        if (logFg != null && !logFg.isDisposed()) logFg.dispose();
        if (statusPoller != null) {
            statusPoller.shutdownNow();
        }
        // The relay outlives this view — it belongs to the Activator and dies with the
        // MCP server, not with the console that reports on it.
        if (greenLight != null && !greenLight.isDisposed()) greenLight.dispose();
        if (yellowLight != null && !yellowLight.isDisposed()) yellowLight.dispose();
        if (redLight != null && !redLight.isDisposed()) redLight.dispose();
        if (blueLight != null && !blueLight.isDisposed()) blueLight.dispose();
        super.dispose();
    }
}
