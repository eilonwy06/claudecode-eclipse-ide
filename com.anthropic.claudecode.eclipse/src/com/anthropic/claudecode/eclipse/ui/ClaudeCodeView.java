package com.anthropic.claudecode.eclipse.ui;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import org.eclipse.swt.SWT;
import org.eclipse.swt.custom.StyledText;
import org.eclipse.swt.graphics.Color;
import org.eclipse.swt.graphics.Font;
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
import com.anthropic.claudecode.eclipse.editor.UiHelper;

public class ClaudeCodeView extends ViewPart {

    public static final String VIEW_ID = "com.anthropic.claudecode.eclipse.ui.ClaudeCodeView";

    private StyledText logArea;
    private Label statusLabel;
    private Button launchButton;
    private ScheduledExecutorService statusPoller;
    private volatile boolean launching = false;

    @Override
    public void createPartControl(Composite parent) {
        Composite container = new Composite(parent, SWT.NONE);
        GridLayout layout = new GridLayout(1, false);
        layout.marginWidth = 8;
        layout.marginHeight = 8;
        layout.verticalSpacing = 6;
        container.setLayout(layout);

        // Status bar
        statusLabel = new Label(container, SWT.NONE);
        statusLabel.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));

        // Button row
        Composite buttonRow = new Composite(container, SWT.NONE);
        buttonRow.setLayout(new GridLayout(3, false));
        buttonRow.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));

        launchButton = new Button(buttonRow, SWT.PUSH);
        launchButton.setText("Launch Claude Terminal");
        launchButton.addListener(SWT.Selection, e -> startClaude());

        Button restartBtn = new Button(buttonRow, SWT.PUSH);
        restartBtn.setText("Restart Server");
        restartBtn.addListener(SWT.Selection, e -> {
            Activator.getDefault().restart();
            appendLog("Server restarted.\n");
            updateStatus();
        });

        Button resumeBtn = new Button(buttonRow, SWT.PUSH);
        resumeBtn.setText("Resume Session");
        resumeBtn.addListener(SWT.Selection, e -> restartClaude("--resume"));

        // Log area
        logArea = new StyledText(container, SWT.MULTI | SWT.V_SCROLL | SWT.H_SCROLL | SWT.READ_ONLY);
        logArea.setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, true));
        logArea.setWordWrap(true);

        Display display = parent.getDisplay();
        Color bgColor = new Color(display, 30, 30, 30);
        Color fgColor = new Color(display, 220, 220, 220);
        Font monoFont = new Font(display, "Consolas", 10, SWT.NORMAL);
        logArea.setBackground(bgColor);
        logArea.setForeground(fgColor);
        logArea.setFont(monoFont);
        logArea.addDisposeListener(e -> {
            bgColor.dispose();
            fgColor.dispose();
            monoFont.dispose();
        });

        appendLog("Claude Code for Eclipse v2.1.0\n");
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
            appendLog("Click 'Launch Claude Terminal' to open the dedicated Claude CLI view.\n");
            appendLog("Claude will auto-connect to Eclipse via the HTTP+SSE server.\n\n");
        }

        updateStatus();
        startStatusPoller();
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

    private void appendLog(String text) {
        if (logArea != null && !logArea.isDisposed()) {
            logArea.append(text);
            logArea.setTopIndex(logArea.getLineCount() - 1);
        }
    }

    private void updateStatus() {
        if (statusLabel == null || statusLabel.isDisposed()) return;

        Activator activator = Activator.getDefault();
        StringBuilder status = new StringBuilder();

        if (activator.isServerRunning()) {
            var server = activator.getHttpSseServer();
            status.append("Server: port ").append(server.getPort());
            int clients = server.getClientCount();
            status.append("  |  Clients: ").append(clients);
            if (clients > 0) {
                status.append(" (connected)");
            }
        } else {
            status.append("Server: stopped");
        }

        statusLabel.setText(status.toString());
        statusLabel.getParent().layout(true);
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
        super.dispose();
    }
}
