package com.anthropic.claudecode.eclipse.ui;

import java.awt.BorderLayout;
import java.awt.Frame;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import javax.swing.JPanel;
import javax.swing.SwingUtilities;

import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.swt.SWT;
import org.eclipse.swt.awt.SWT_AWT;
import org.eclipse.swt.events.ControlListener;
import org.eclipse.swt.layout.FillLayout;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.ui.part.ViewPart;

import com.anthropic.claudecode.eclipse.Activator;
import com.anthropic.claudecode.eclipse.Constants;
import com.jediterm.pty.PtyProcessTtyConnector;
import com.jediterm.terminal.TerminalColor;
import com.jediterm.terminal.TextStyle;
import com.jediterm.terminal.TtyConnector;
import com.jediterm.terminal.ui.JediTermWidget;
import com.jediterm.terminal.ui.settings.DefaultSettingsProvider;
import com.pty4j.PtyProcess;
import com.pty4j.PtyProcessBuilder;
import com.pty4j.WinSize;

public class ClaudeCliView extends ViewPart {

    public static final String VIEW_ID = "com.anthropic.claudecode.eclipse.ui.ClaudeCliView";

    private Frame awtFrame;
    private JPanel terminalPanel;
    private JediTermWidget terminal;
    private PtyProcess ptyProcess;
    private volatile boolean viewDisposed = false;

    @Override
    public void createPartControl(Composite parent) {
        Composite embedded = new Composite(parent, SWT.EMBEDDED | SWT.NO_BACKGROUND);
        embedded.setLayout(new FillLayout());

        awtFrame = SWT_AWT.new_Frame(embedded);

        embedded.addControlListener(ControlListener.controlResizedAdapter(e -> {
            if (awtFrame == null || viewDisposed) return;
            org.eclipse.swt.graphics.Point size = embedded.getSize();
            SwingUtilities.invokeLater(() -> {
                awtFrame.setSize(size.x, size.y);
                awtFrame.validate();
                syncPtySize();
            });
        }));

        SwingUtilities.invokeLater(this::initTerminalUI);
    }

    private void initTerminalUI() {
        terminalPanel = new JPanel(new BorderLayout());
        awtFrame.add(terminalPanel);

        terminal = new JediTermWidget(80, 24, new ClaudeTerminalSettings());
        terminalPanel.add(terminal.getComponent(), BorderLayout.CENTER);

        awtFrame.pack();
        awtFrame.setVisible(true);

        // Auto-start Claude on first open
        doLaunchProcess(new String[0]);
    }

    /**
     * (Re)starts the Claude CLI process with the given extra args.
     * Safe to call from the SWT UI thread or AWT EDT.
     */
    public void launchProcess(String... extraArgs) {
        SwingUtilities.invokeLater(() -> doLaunchProcess(extraArgs));
    }

    private void doLaunchProcess(String[] extraArgs) {
        if (viewDisposed) return;

        // Terminate existing process
        if (ptyProcess != null) {
            try { ptyProcess.destroy(); } catch (Exception ignored) {}
            ptyProcess = null;
        }

        // Recreate the widget for a clean state
        if (terminalPanel != null && terminal != null) {
            terminal.close();
            terminalPanel.remove(terminal.getComponent());
            terminal = new JediTermWidget(80, 24, new ClaudeTerminalSettings());
            terminalPanel.add(terminal.getComponent(), BorderLayout.CENTER);
            terminalPanel.revalidate();
            terminalPanel.repaint();
        }

        if (terminal == null) return;

        // Ensure server is running
        Activator activator = Activator.getDefault();
        if (!activator.isServerRunning()) {
            activator.initialize();
        }

        int port = activator.getHttpSseServer().getPort();
        String authToken = activator.getHttpSseServer().getAuthToken();

        String claudeCmd = activator.getPreferenceStore().getString(Constants.PREF_CLAUDE_CMD);
        if (claudeCmd == null || claudeCmd.isBlank()) {
            claudeCmd = Constants.DEFAULT_CLAUDE_CMD;
        }

        Map<String, String> env = new HashMap<>(System.getenv());
        env.put("CLAUDE_IDE_PORT",       String.valueOf(port));
        env.put("CLAUDE_IDE_AUTH_TOKEN", authToken);
        env.put("CLAUDE_IDE_NAME",       Constants.IDE_NAME);
        env.put("TERM",                  "xterm-256color");
        env.put("COLORTERM",             "truecolor");

        String workingDir = ResourcesPlugin.getWorkspace().getRoot()
                .getLocation().toOSString();

        // Build the command array.
        // On Windows, wrap .cmd/.bat files in cmd.exe so winpty has a real exe entry point.
        List<String> cmdList = new ArrayList<>();
        String os = System.getProperty("os.name", "").toLowerCase();
        if (os.contains("win")) {
            String lower = claudeCmd.toLowerCase();
            if (lower.endsWith(".cmd") || lower.endsWith(".bat")) {
                cmdList.add("cmd.exe");
                cmdList.add("/q");
                cmdList.add("/c");
            }
        }
        cmdList.add(claudeCmd);
        for (String arg : extraArgs) {
            cmdList.add(arg);
        }

        try {
            ptyProcess = new PtyProcessBuilder(cmdList.toArray(new String[0]))
                    .setEnvironment(env)
                    .setDirectory(workingDir)
                    .setInitialColumns(terminal.getTerminalDisplay().getColumnCount())
                    .setInitialRows(terminal.getTerminalDisplay().getRowCount())
                    .setConsole(false)
                    .setWindowsAnsiColorEnabled(true)
                    .start();

            TtyConnector connector = new PtyProcessTtyConnector(ptyProcess, StandardCharsets.UTF_8);
            terminal.start(connector);
        } catch (IOException e) {
            Activator.logError("Failed to start Claude CLI process", e);
        }
    }

    private void syncPtySize() {
        if (ptyProcess == null || terminal == null || viewDisposed) return;
        try {
            ptyProcess.setWinSize(new WinSize(
                    terminal.getTerminalDisplay().getColumnCount(),
                    terminal.getTerminalDisplay().getRowCount()));
        } catch (Exception ignored) {}
    }

    @Override
    public void setFocus() {
        if (terminal != null) {
            SwingUtilities.invokeLater(() -> terminal.requestFocusInWindow());
        }
    }

    @Override
    public void dispose() {
        viewDisposed = true;
        SwingUtilities.invokeLater(() -> {
            if (ptyProcess != null) {
                try { ptyProcess.destroy(); } catch (Exception ignored) {}
                ptyProcess = null;
            }
            if (terminal != null) {
                terminal.close();
                terminal = null;
            }
        });
        super.dispose();
    }

    private static final class ClaudeTerminalSettings extends DefaultSettingsProvider {
        @Override public float getLineSpacing()            { return 1.0f; }
        @Override public boolean shouldCloseTabOnLogout()  { return false; }
        @Override public boolean audibleBell()             { return false; }
        @Override public int getBufferMaxLinesCount()      { return 10000; }

        @Override
        public TextStyle getDefaultStyle() {
            return new TextStyle(
                    TerminalColor.rgb(220, 220, 220),
                    TerminalColor.rgb(30, 30, 30));
        }
    }
}
