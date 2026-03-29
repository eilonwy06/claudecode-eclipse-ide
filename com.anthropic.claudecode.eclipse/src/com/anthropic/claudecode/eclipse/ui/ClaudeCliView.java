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
import org.eclipse.swt.custom.CTabFolder;
import org.eclipse.swt.custom.CTabFolder2Adapter;
import org.eclipse.swt.custom.CTabFolderEvent;
import org.eclipse.swt.custom.CTabItem;
import org.eclipse.swt.events.SelectionListener;
import org.eclipse.swt.layout.FillLayout;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.ToolBar;
import org.eclipse.swt.widgets.ToolItem;
import org.eclipse.ui.part.ViewPart;

import com.anthropic.claudecode.eclipse.Activator;
import com.anthropic.claudecode.eclipse.Constants;
import com.pty4j.PtyProcess;
import com.pty4j.PtyProcessBuilder;
import com.pty4j.WinSize;

public class ClaudeCliView extends ViewPart {

    public static final String VIEW_ID = "com.anthropic.claudecode.eclipse.ui.ClaudeCliView";

    private CTabFolder tabFolder;
    private int sessionCounter = 0;
    private volatile boolean viewDisposed = false;

    @Override
    public void createPartControl(Composite parent) {
        Composite container = new Composite(parent, SWT.NONE);
        GridLayout layout = new GridLayout(1, false);
        layout.marginWidth = 0;
        layout.marginHeight = 0;
        layout.verticalSpacing = 0;
        container.setLayout(layout);

        tabFolder = new CTabFolder(container, SWT.BORDER | SWT.CLOSE);
        tabFolder.setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, true));
        tabFolder.setSimple(false);
        tabFolder.setTabHeight(24);

        // "+" button — opens a new session tab
        ToolBar toolbar = new ToolBar(tabFolder, SWT.FLAT);
        ToolItem newBtn = new ToolItem(toolbar, SWT.PUSH);
        newBtn.setText("+");
        newBtn.setToolTipText("New Claude CLI Session");
        newBtn.addListener(SWT.Selection, e -> openNewSession());
        tabFolder.setTopRight(toolbar);

        // Kill session when its tab is closed
        tabFolder.addCTabFolder2Listener(new CTabFolder2Adapter() {
            @Override
            public void close(CTabFolderEvent event) {
                TerminalSession session = (TerminalSession) event.item.getData();
                if (session != null) session.dispose();
            }
        });

        // Focus the browser when switching tabs
        tabFolder.addSelectionListener(SelectionListener.widgetSelectedAdapter(e -> {
            CTabItem item = tabFolder.getSelection();
            if (item != null) {
                TerminalSession session = (TerminalSession) item.getData();
                if (session != null) session.focus();
            }
        }));

        openNewSession();
    }

    private void openNewSession(String... extraArgs) {
        sessionCounter++;
        CTabItem tabItem = new CTabItem(tabFolder, SWT.CLOSE);
        tabItem.setText("Claude " + sessionCounter);

        Composite content = new Composite(tabFolder, SWT.NONE);
        content.setLayout(new FillLayout());
        tabItem.setControl(content);

        TerminalSession session = new TerminalSession(tabItem, content, extraArgs);
        tabItem.setData(session);
        tabFolder.setSelection(tabItem);
    }

    /** Opens a new tab. Called from ClaudeCodeView for Launch / Resume Session. */
    public void launchProcess(String... extraArgs) {
        openNewSession(extraArgs);
    }

    @Override
    public void setFocus() {
        if (tabFolder == null || tabFolder.isDisposed()) return;
        CTabItem item = tabFolder.getSelection();
        if (item != null) {
            TerminalSession session = (TerminalSession) item.getData();
            if (session != null) session.focus();
        }
    }

    @Override
    public void dispose() {
        viewDisposed = true;
        if (tabFolder != null && !tabFolder.isDisposed()) {
            for (CTabItem item : tabFolder.getItems()) {
                TerminalSession session = (TerminalSession) item.getData();
                if (session != null) session.dispose();
            }
        }
        super.dispose();
    }

    // ─── One terminal session per tab ────────────────────────────────────────

    private final class TerminalSession {

        private final CTabItem tabItem;
        private Browser browser;
        private PtyProcess ptyProcess;
        private Thread readerThread;
        private OutputStream ptyStdin;
        private volatile boolean disposed = false;

        private final ExecutorService stdinWriter = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "claude-pty-stdin");
            t.setDaemon(true);
            return t;
        });

        TerminalSession(CTabItem tabItem, Composite parent, String[] extraArgs) {
            this.tabItem = tabItem;
            browser = new Browser(parent, SWT.NONE);

            new BrowserFunction(browser, "sendInput") {
                @Override
                public Object function(Object[] args) {
                    if (args.length > 0 && ptyStdin != null) {
                        byte[] data = ((String) args[0]).getBytes(StandardCharsets.UTF_8);
                        stdinWriter.submit(() -> {
                            try { ptyStdin.write(data); ptyStdin.flush(); }
                            catch (IOException ignored) {}
                        });
                    }
                    return null;
                }
            };

            new BrowserFunction(browser, "notifyResize") {
                @Override
                public Object function(Object[] args) {
                    if (args.length >= 2 && ptyProcess != null) {
                        int cols = ((Number) args[0]).intValue();
                        int rows = ((Number) args[1]).intValue();
                        try { ptyProcess.setWinSize(new WinSize(cols, rows)); }
                        catch (Exception ignored) {}
                    }
                    return null;
                }
            };

            browser.addProgressListener(new ProgressAdapter() {
                @Override
                public void completed(ProgressEvent event) {
                    browser.removeProgressListener(this);
                    launch(extraArgs);
                    // Delay focus so SWT finishes settling the new tab's layout
                    // and focus chain before we request it — fixes the intermittent
                    // "can't type" issue on Windows WebView2.
                    Display.getCurrent().timerExec(200, () -> {
                        if (!disposed && !viewDisposed) focus();
                    });
                }
            });

            browser.setText(buildHtml());
        }

        private void launch(String[] extraArgs) {
            if (disposed || viewDisposed) return;

            Activator activator = Activator.getDefault();
            if (!activator.isServerRunning()) activator.initialize();

            int port = activator.getHttpSseServer().getPort();
            String authToken = activator.getHttpSseServer().getAuthToken();

            String claudeCmd = activator.getPreferenceStore().getString(Constants.PREF_CLAUDE_CMD);
            if (claudeCmd == null || claudeCmd.isBlank()) claudeCmd = Constants.DEFAULT_CLAUDE_CMD;

            Map<String, String> env = new HashMap<>(System.getenv());
            env.put("CLAUDE_IDE_PORT",       String.valueOf(port));
            env.put("CLAUDE_IDE_AUTH_TOKEN", authToken);
            env.put("CLAUDE_IDE_NAME",       Constants.IDE_NAME);
            env.put("TERM",                  "xterm-256color");
            env.put("COLORTERM",             "truecolor");

            String workingDir = ResourcesPlugin.getWorkspace().getRoot()
                    .getLocation().toOSString();

            List<String> cmdList = new ArrayList<>();
            if (System.getProperty("os.name", "").toLowerCase().contains("win")) {
                cmdList.add("cmd.exe");
                cmdList.add("/c");
            }
            cmdList.add(claudeCmd);
            for (String arg : extraArgs) cmdList.add(arg);

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
                startReader();
            } catch (Throwable e) {
                String msg = "Error (" + e.getClass().getSimpleName() + "): " + e.getMessage() + "\\r\\n";
                safeExecute("term.write('" + escapeJs(msg) + "')");
                Activator.logError("Failed to start Claude CLI", e);
            }
        }

        private void startReader() {
            readerThread = new Thread(() -> {
                byte[] buf = new byte[4096];
                try (InputStream in = ptyProcess.getInputStream()) {
                    int n;
                    while (!disposed && !viewDisposed && (n = in.read(buf)) != -1) {
                        final String b64 = Base64.getEncoder().encodeToString(Arrays.copyOf(buf, n));
                        Display.getDefault().asyncExec(() -> {
                            if (browser != null && !browser.isDisposed())
                                browser.execute("writeOutput('" + b64 + "')");
                        });
                    }
                } catch (IOException ignored) {}
                // Mark tab as finished when process exits
                Display.getDefault().asyncExec(() -> {
                    if (!tabItem.isDisposed())
                        tabItem.setText(tabItem.getText() + " [done]");
                });
            }, "claude-pty-reader-" + tabItem.getText());
            readerThread.setDaemon(true);
            readerThread.start();
        }

        private void safeExecute(String js) {
            Display.getDefault().asyncExec(() -> {
                if (browser != null && !browser.isDisposed()) browser.execute(js);
            });
        }

        void focus() {
            if (browser != null && !browser.isDisposed()) {
                browser.setFocus();
                browser.execute("if (typeof term !== 'undefined') term.focus();");
            }
        }

        void dispose() {
            disposed = true;
            stdinWriter.shutdownNow();
            if (ptyProcess != null) {
                try { ptyProcess.destroy(); } catch (Exception ignored) {}
                ptyProcess = null;
            }
            if (readerThread != null) {
                readerThread.interrupt();
                readerThread = null;
            }
        }
    }

    // ─── HTML / resource helpers ──────────────────────────────────────────────

    private static String escapeJs(String s) {
        return s.replace("\\", "\\\\").replace("'", "\\'")
                .replace("\r", "\\r").replace("\n", "\\n");
    }

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
            + "  cursorStyle:'block',\n"
            + "  scrollback:5000,\n"
            + "  allowProposedApi:true\n"
            + "});\n"
            + "var fitAddon = new FitAddon.FitAddon();\n"
            + "term.loadAddon(fitAddon);\n"
            + "term.open(document.getElementById('terminal'));\n"
            + "fitAddon.fit();\n"
            + "setTimeout(function() { term.focus(); }, 0);\n"
            // Batch output per animation frame so rapid ANSI sequences
            // (cursor-move + rewrite) are processed together before repaint,
            // giving smooth streaming instead of flickering/regenerating lines.
            + "var _pending = [];\n"
            + "function writeOutput(b64) {\n"
            + "  _pending.push(b64);\n"
            + "  if (_pending.length === 1) {\n"
            + "    requestAnimationFrame(function() {\n"
            + "      var chunks = _pending.splice(0);\n"
            + "      for (var i = 0; i < chunks.length; i++) {\n"
            + "        term.write(Uint8Array.from(atob(chunks[i]), function(c){return c.charCodeAt(0)}));\n"
            + "      }\n"
            + "    });\n"
            + "  }\n"
            + "}\n"
            + "term.onData(function(data) {\n"
            + "  if (window.sendInput) window.sendInput(data);\n"
            + "});\n"
            // Re-focus xterm on any click so cursor blink is never lost
            + "document.addEventListener('click', function() { term.focus(); });\n"
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
            if (url == null) { Activator.logError("Resource not found: " + path, null); return ""; }
            try (InputStream in = url.openStream()) {
                return new String(in.readAllBytes(), StandardCharsets.UTF_8);
            }
        } catch (Exception e) {
            Activator.logError("Failed to read resource: " + path, e);
            return "";
        }
    }
}
