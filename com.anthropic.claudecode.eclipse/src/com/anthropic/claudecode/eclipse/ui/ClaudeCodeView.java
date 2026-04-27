package com.anthropic.claudecode.eclipse.ui;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import org.eclipse.jface.preference.IPreferenceStore;
import org.eclipse.swt.SWT;
import org.eclipse.swt.custom.StyledText;
import org.eclipse.swt.graphics.Color;
import org.eclipse.swt.graphics.Font;
import org.eclipse.swt.graphics.GC;
import org.eclipse.swt.graphics.Image;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Label;
import org.eclipse.ui.IWorkbenchPage;
import org.eclipse.ui.PartInitException;
import org.eclipse.ui.part.ViewPart;

import com.anthropic.claudecode.eclipse.Activator;
import com.anthropic.claudecode.eclipse.Constants;
import com.anthropic.claudecode.eclipse.NativeCore;
import com.anthropic.claudecode.eclipse.bridge.PhpBridge;
import com.anthropic.claudecode.eclipse.editor.UiHelper;

public class ClaudeCodeView extends ViewPart {

    public static final String VIEW_ID = "com.anthropic.claudecode.eclipse.ui.ClaudeCodeView";

    private static final int INDICATOR_SIZE = 12;

    private StyledText logArea;
    private Label serverIndicator;
    private Label serverLabel;
    private Label bridgeIndicator;
    private Label bridgeLabel;
    private Button launchButton;
    private ScheduledExecutorService statusPoller;
    private volatile boolean launching = false;
    private PhpBridge phpBridge;

    private Image greenLight;
    private Image yellowLight;
    private Image redLight;
    private Image blueLight;

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

        appendLog("Claude Code for Eclipse v2.3.12\n");
        appendLog("─────────────────────────────────\n\n");

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

        startPhpBridge();

        // Show bridge info for Windows/Linux, or override message for macOS
        if (phpBridge != null && phpBridge.isOverridden()) {
            appendLog("macOS detected, direct protocol active.\n\n");
        } else if (phpBridge != null && phpBridge.isRunning()) {
            String phpMsg = phpBridge.getPhpMessage();
            if (phpMsg != null && !phpMsg.isEmpty()) {
                appendLog(phpMsg + "\n");
            }
            appendLog("Bridge relay ports: " + phpBridge.getPortA() + " ↔ " + phpBridge.getPortB() + "\n\n");
        }
        appendLog("Click 'Launch Claude Terminal' to open the Claude CLI.\n\n");

        updateStatus();
        startStatusPoller();

        if (isAutoLaunchEnabled()) {
            Display.getCurrent().asyncExec(this::startClaude);
        }
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
        GridLayout layout = new GridLayout(6, false);
        layout.marginWidth = 0;
        layout.marginHeight = 4;
        layout.horizontalSpacing = 6;
        statusBar.setLayout(layout);
        statusBar.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));

        serverIndicator = new Label(statusBar, SWT.NONE);
        serverIndicator.setImage(redLight);

        serverLabel = new Label(statusBar, SWT.NONE);
        serverLabel.setText("Server: --");
        serverLabel.setLayoutData(new GridData(SWT.LEFT, SWT.CENTER, false, false));

        Label sep1 = new Label(statusBar, SWT.SEPARATOR | SWT.VERTICAL);
        GridData sepData = new GridData(SWT.CENTER, SWT.FILL, false, false);
        sepData.heightHint = 16;
        sep1.setLayoutData(sepData);

        bridgeIndicator = new Label(statusBar, SWT.NONE);
        bridgeIndicator.setImage(redLight);

        bridgeLabel = new Label(statusBar, SWT.NONE);
        bridgeLabel.setText("Bridge: --");
        bridgeLabel.setLayoutData(new GridData(SWT.LEFT, SWT.CENTER, false, false));

        Label spacer = new Label(statusBar, SWT.NONE);
        spacer.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
    }

    private void createButtonRow(Composite parent) {
        Composite buttonRow = new Composite(parent, SWT.NONE);
        GridLayout layout = new GridLayout(3, true);
        layout.marginWidth = 0;
        layout.horizontalSpacing = 8;
        buttonRow.setLayout(layout);
        buttonRow.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));

        launchButton = new Button(buttonRow, SWT.PUSH);
        launchButton.setText("Launch Terminal");
        launchButton.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        launchButton.addListener(SWT.Selection, e -> startClaude());

        Button restartBtn = new Button(buttonRow, SWT.PUSH);
        restartBtn.setText("Restart Server");
        restartBtn.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        restartBtn.addListener(SWT.Selection, e -> restartServer());

        Button resumeBtn = new Button(buttonRow, SWT.PUSH);
        resumeBtn.setText("Resume Session");
        resumeBtn.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        resumeBtn.addListener(SWT.Selection, e -> restartClaude("--resume"));
    }

    private void createLogArea(Composite parent, Display display) {
        logArea = new StyledText(parent, SWT.MULTI | SWT.V_SCROLL | SWT.H_SCROLL | SWT.READ_ONLY | SWT.BORDER);
        logArea.setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, true));
        logArea.setWordWrap(true);

        Color bgColor = new Color(display, 30, 30, 30);
        Color fgColor = new Color(display, 220, 220, 220);
        Font monoFont = new Font(display, "Consolas", 10, SWT.NORMAL);
        logArea.setBackground(bgColor);
        logArea.setForeground(fgColor);
        logArea.setFont(monoFont);
        logArea.setLeftMargin(8);
        logArea.setTopMargin(8);
        logArea.addDisposeListener(e -> {
            bgColor.dispose();
            fgColor.dispose();
            monoFont.dispose();
        });
    }

    private void restartServer() {
        setServerStatus(Status.YELLOW, "Restarting...");
        setBridgeStatus(Status.YELLOW, "Reconnecting...");
        Display.getCurrent().update();

        Display.getCurrent().asyncExec(() -> {
            Activator.getDefault().restart();
            appendLog("Server restarted.\n");

            if (phpBridge != null) {
                NativeCore.bridgeDisconnect();
                phpBridge.stop();
            }
            startPhpBridge();
            updateStatus();
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
            appendLog("[ERROR] Could not open Claude CLI view: " + e.getMessage() + "\n");
            Activator.logError("Failed to open Claude CLI view", e);
        } finally {
            Display.getCurrent().timerExec(500, () -> launching = false);
        }
    }

    public void restartClaude(String... extraArgs) {
        startClaude(extraArgs);
    }

    private void startPhpBridge() {
        phpBridge = new PhpBridge();
        boolean started = phpBridge.start(data -> {
            if (isDebugMode()) {
                String msg = new String(data, java.nio.charset.StandardCharsets.UTF_8);
                Display.getDefault().asyncExec(() -> appendLog("[BRIDGE] " + msg));
            }
        });

        if (started) {
            if (isDebugMode()) {
                appendLog("Bridge started on port " + phpBridge.getPortA() + "\n");
            }
            boolean connected = NativeCore.bridgeConnect(phpBridge.getPortA());
            if (isDebugMode()) {
                if (connected) {
                    appendLog("Rust connected to Bridge.\n\n");
                } else {
                    appendLog("[WARN] Rust failed to connect to Bridge.\n\n");
                }
            }
        } else {
            if (isDebugMode()) {
                appendLog("[WARN] Bridge failed to start.\n\n");
            }
        }
    }

    private boolean isDebugMode() {
        IPreferenceStore store = Activator.getDefault().getPreferenceStore();
        return store.getBoolean(Constants.PREF_DEBUG_MODE);
    }

    private boolean isAutoLaunchEnabled() {
        IPreferenceStore store = Activator.getDefault().getPreferenceStore();
        return store.getBoolean(Constants.PREF_AUTO_LAUNCH_CLI);
    }

    private boolean isMacOS() {
        return System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT).contains("mac");
    }

    private void appendLog(String text) {
        if (logArea != null && !logArea.isDisposed()) {
            logArea.append(text);
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
            setServerStatus(Status.GREEN, "Port " + port);
        } else {
            setServerStatus(Status.RED, "Stopped");
        }

        if (phpBridge != null && phpBridge.isOverridden()) {
            setBridgeStatus(Status.BLUE, "Overridden");
        } else if (phpBridge != null && phpBridge.isRunning()) {
            if (NativeCore.bridgeIsConnected()) {
                setBridgeStatus(Status.GREEN, "Connected");
            } else {
                setBridgeStatus(Status.YELLOW, "Running");
            }
        } else {
            setBridgeStatus(Status.RED, "Off");
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

    @Override
    public void dispose() {
        if (statusPoller != null) {
            statusPoller.shutdownNow();
        }
        if (phpBridge != null) {
            NativeCore.bridgeDisconnect();
            phpBridge.stop();
        }
        if (greenLight != null && !greenLight.isDisposed()) greenLight.dispose();
        if (yellowLight != null && !yellowLight.isDisposed()) yellowLight.dispose();
        if (redLight != null && !redLight.isDisposed()) redLight.dispose();
        if (blueLight != null && !blueLight.isDisposed()) blueLight.dispose();
        super.dispose();
    }
}
