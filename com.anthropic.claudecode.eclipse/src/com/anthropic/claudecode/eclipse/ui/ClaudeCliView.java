package com.anthropic.claudecode.eclipse.ui;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.Platform;
import org.eclipse.swt.SWT;
import org.eclipse.swt.browser.Browser;
import org.eclipse.swt.browser.BrowserFunction;
import org.eclipse.swt.browser.ProgressAdapter;
import org.eclipse.swt.browser.ProgressEvent;
import org.eclipse.swt.browser.ProgressListener;
import org.eclipse.swt.layout.FillLayout;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Display;
import org.eclipse.ui.part.ViewPart;

import com.anthropic.claudecode.eclipse.Activator;
import com.anthropic.claudecode.eclipse.Constants;
import com.pty4j.PtyProcess;
import com.pty4j.PtyProcessBuilder;
import com.pty4j.WinSize;

public class ClaudeCliView extends ViewPart {

    public static final String VIEW_ID = "com.anthropic.claudecode.eclipse.ui.ClaudeCliView";

    private Browser browser;
    private PtyProcess ptyProcess;
    private Thread readerThread;
    private OutputStream ptyStdin;
    private final ExecutorService stdinWriter = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "claude-pty-stdin");
        t.setDaemon(true);
        return t;
    });
    private volatile boolean viewDisposed = false;
    private String[] pendingArgs = null;

    @Override
    public void createPartControl(Composite parent) {
        parent.setLayout(new FillLayout());
        browser = new Browser(parent, SWT.NONE);

        // JS → Java: keyboard input from xterm.js
        new BrowserFunction(browser, "sendInput") {
            @Override
            public Object function(Object[] args) {
                if (args.length > 0 && ptyStdin != null) {
                    final byte[] data = ((String) args[0]).getBytes(StandardCharsets.UTF_8);
                    stdinWriter.submit(() -> {
                        try {
                            ptyStdin.write(data);
                            ptyStdin.flush();
                        } catch (IOException ignored) {}
                    });
                }
                return null;
            }
        };

        // JS → Java: terminal resized (cols, rows)
        new BrowserFunction(browser, "notifyResize") {
            @Override
            public Object function(Object[] args) {
                if (args.length >= 2 && ptyProcess != null) {
                    int cols = ((Number) args[0]).intValue();
                    int rows = ((Number) args[1]).intValue();
                    try {
                        ptyProcess.setWinSize(new WinSize(cols, rows));
                    } catch (Exception ignored) {}
                }
                return null;
            }
        };

        // ProgressListener fires after the page is fully loaded and
        // BrowserFunctions are injected into window — safe to start the PTY here.
        browser.addProgressListener(new ProgressAdapter() {
            @Override
            public void completed(ProgressEvent event) {
                browser.removeProgressListener(this); // only fire once
                String[] args = pendingArgs != null ? pendingArgs : new String[0];
                pendingArgs = null;
                doLaunchProcess(args);
            }
        });

        browser.setText(buildHtml());
    }

    /**
     * (Re)starts the Claude CLI process with the given extra args.
     * Safe to call from the SWT UI thread.
     */
    public void launchProcess(String... extraArgs) {
        doLaunchProcess(extraArgs);
    }

    private void doLaunchProcess(String[] extraArgs) {
        if (viewDisposed) return;

        // Kill existing process
        if (ptyProcess != null) {
            try { ptyProcess.destroy(); } catch (Exception ignored) {}
            ptyProcess = null;
            ptyStdin = null;
        }
        if (readerThread != null) {
            readerThread.interrupt();
            readerThread = null;
        }

        // Clear terminal display
        safeExecute("term.clear()");

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

        // On Windows, wrap .cmd/.bat in cmd.exe so winpty has a real exe entry point
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
                    .setInitialColumns(120)
                    .setInitialRows(30)
                    .setConsole(false)
                    .setWindowsAnsiColorEnabled(true)
                    .start();

            ptyStdin = ptyProcess.getOutputStream();
            startReaderThread();
        } catch (IOException e) {
            String msg = "Failed to start Claude CLI: " + e.getMessage() + "\\r\\n"
                    + "Check Preferences > Claude Code for the correct path.\\r\\n";
            safeExecute("term.write('" + escapeJs(msg) + "')");
            Activator.logError("Failed to start Claude CLI process", e);
        }
    }

    private void startReaderThread() {
        readerThread = new Thread(() -> {
            byte[] buf = new byte[4096];
            try (InputStream in = ptyProcess.getInputStream()) {
                int n;
                while (!viewDisposed && (n = in.read(buf)) != -1) {
                    final String b64 = Base64.getEncoder()
                            .encodeToString(Arrays.copyOf(buf, n));
                    Display.getDefault().asyncExec(() -> {
                        if (browser != null && !browser.isDisposed()) {
                            browser.execute("writeOutput('" + b64 + "')");
                        }
                    });
                }
            } catch (IOException ignored) {}
        }, "claude-pty-reader");
        readerThread.setDaemon(true);
        readerThread.start();
    }

    private void safeExecute(String js) {
        Display d = Display.getDefault();
        if (d == null || d.isDisposed()) return;
        d.asyncExec(() -> {
            if (browser != null && !browser.isDisposed()) {
                browser.execute(js);
            }
        });
    }

    private static String escapeJs(String s) {
        return s.replace("\\", "\\\\").replace("'", "\\'").replace("\r", "\\r").replace("\n", "\\n");
    }

    @Override
    public void setFocus() {
        if (browser != null && !browser.isDisposed()) {
            browser.setFocus();
        }
    }

    @Override
    public void dispose() {
        viewDisposed = true;
        stdinWriter.shutdownNow();
        if (ptyProcess != null) {
            try { ptyProcess.destroy(); } catch (Exception ignored) {}
        }
        if (readerThread != null) {
            readerThread.interrupt();
        }
        super.dispose();
    }

    // ─── HTML builder ────────────────────────────────────────────────────────

    private String buildHtml() {
        String xtermJs  = readResource("resources/terminal/xterm.js");
        String xtermCss = readResource("resources/terminal/xterm.css");
        String fitJs    = readResource("resources/terminal/addon-fit.js");

        return "<!DOCTYPE html>\n"
            + "<html>\n<head>\n<meta charset='UTF-8'>\n"
            + "<style>\n"
            + xtermCss + "\n"
            + "html,body{margin:0;padding:0;background:#1e1e1e;overflow:hidden;height:100%;width:100%;}\n"
            + "#terminal{height:100%;}\n"
            + "</style>\n"
            + "</head>\n<body>\n"
            + "<div id='terminal'></div>\n"
            + "<script>" + xtermJs + "</script>\n"
            + "<script>" + fitJs  + "</script>\n"
            + "<script>\n"
            + "var term = new Terminal({\n"
            + "  theme:{background:'#1e1e1e',foreground:'#dcdcdc'},\n"
            + "  fontFamily:'Consolas,\\'Courier New\\',monospace',\n"
            + "  fontSize:13,\n"
            + "  cursorBlink:true,\n"
            + "  allowProposedApi:true\n"
            + "});\n"
            + "var fitAddon = new FitAddon.FitAddon();\n"
            + "term.loadAddon(fitAddon);\n"
            + "term.open(document.getElementById('terminal'));\n"
            + "fitAddon.fit();\n"
            + "\n"
            + "// Write base64-encoded PTY output\n"
            + "function writeOutput(b64) {\n"
            + "  term.write(Uint8Array.from(atob(b64), function(c){return c.charCodeAt(0)}));\n"
            + "}\n"
            + "\n"
            + "// Forward keyboard input to Java\n"
            + "term.onData(function(data) {\n"
            + "  if (window.sendInput) window.sendInput(data);\n"
            + "});\n"
            + "\n"
            + "// Notify Java when terminal is resized\n"
            + "window.addEventListener('resize', function() {\n"
            + "  fitAddon.fit();\n"
            + "  if (window.notifyResize) window.notifyResize(term.cols, term.rows);\n"
            + "});\n"
            + "</script>\n"
            + "</body></html>\n";
    }

    private String readResource(String path) {
        try {
            URL url = Platform.getBundle(Constants.PLUGIN_ID).getEntry(path);
            if (url == null) {
                Activator.logError("Bundle resource not found: " + path, null);
                return "";
            }
            try (InputStream in = url.openStream()) {
                return new String(in.readAllBytes(), StandardCharsets.UTF_8);
            }
        } catch (Exception e) {
            Activator.logError("Failed to read bundle resource: " + path, e);
            return "";
        }
    }
}
