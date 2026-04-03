package com.anthropic.claudecode.eclipse.ui;

import java.util.ArrayList;
import java.util.List;

import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.swt.SWT;
import org.eclipse.swt.custom.CTabFolder;
import org.eclipse.swt.custom.CTabFolder2Adapter;
import org.eclipse.swt.custom.CTabFolderEvent;
import org.eclipse.swt.custom.CTabItem;
import org.eclipse.swt.events.SelectionListener;
import org.eclipse.swt.dnd.Clipboard;
import org.eclipse.swt.dnd.TextTransfer;
import org.eclipse.swt.graphics.Color;
import org.eclipse.swt.graphics.Point;
import org.eclipse.swt.layout.FillLayout;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.swt.widgets.ToolBar;
import org.eclipse.swt.widgets.ToolItem;
import org.eclipse.core.commands.AbstractHandler;
import org.eclipse.core.commands.ExecutionEvent;
import org.eclipse.core.commands.ExecutionException;
import org.eclipse.ui.IWorkbenchPage;
import org.eclipse.ui.handlers.IHandlerService;
import org.eclipse.ui.part.ViewPart;

import com.anthropic.claudecode.eclipse.Activator;
import com.anthropic.claudecode.eclipse.Constants;
import com.anthropic.claudecode.eclipse.NativeCore;

public class ClaudeCliView extends ViewPart {

    public static final String VIEW_ID = "com.anthropic.claudecode.eclipse.ui.ClaudeCliView";

    private static final int BG_R = 0x12, BG_G = 0x13, BG_B = 0x14; // #121314

    private CTabFolder tabFolder;
    private int sessionCounter = 0;
    private volatile boolean viewDisposed = false;
    private boolean launching = false;
    private Color bgColor;

    @Override
    public void createPartControl(Composite parent) {
        Display display = parent.getDisplay();
        bgColor = new Color(display, BG_R, BG_G, BG_B);

        parent.setBackground(bgColor);
        parent.setData("org.eclipse.e4.ui.css.disabled", Boolean.TRUE);

        Composite container = new Composite(parent, SWT.NONE);
        GridLayout layout = new GridLayout(1, false);
        layout.marginWidth = 0;
        layout.marginHeight = 0;
        layout.verticalSpacing = 0;
        container.setLayout(layout);
        container.setBackground(bgColor);
        container.setData("org.eclipse.e4.ui.css.disabled", Boolean.TRUE);

        tabFolder = new CTabFolder(container, SWT.BORDER | SWT.CLOSE);
        tabFolder.setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, true));
        tabFolder.setSimple(false);
        tabFolder.setTabHeight(24);

        ToolBar toolbar = new ToolBar(tabFolder, SWT.FLAT);
        ToolItem newBtn = new ToolItem(toolbar, SWT.PUSH);
        newBtn.setText("+");
        newBtn.setToolTipText("New Claude CLI Session");
        newBtn.addListener(SWT.Selection, e -> openNewSession());
        tabFolder.setTopRight(toolbar);

        tabFolder.addCTabFolder2Listener(new CTabFolder2Adapter() {
            @Override
            public void close(CTabFolderEvent event) {
                TerminalSession session = (TerminalSession) event.item.getData();
                if (session != null) session.dispose();
            }
        });

        tabFolder.addSelectionListener(SelectionListener.widgetSelectedAdapter(e -> {
            CTabItem item = tabFolder.getSelection();
            if (item != null) {
                TerminalSession session = (TerminalSession) item.getData();
                if (session != null) {
                    getSite().getPage().activate(ClaudeCliView.this);
                }
            }
        }));

        // Intercept Ctrl+V: Eclipse's accelerator table grabs it before the
        // console sees it.  Forward as WM_PASTE to the console HWND.
        IHandlerService hs = getSite().getService(IHandlerService.class);
        if (hs != null) {
            hs.activateHandler("org.eclipse.ui.edit.paste", new AbstractHandler() {
                @Override
                public Object execute(ExecutionEvent event) throws ExecutionException {
                    CTabItem item = tabFolder.getSelection();
                    if (item != null && !item.isDisposed()) {
                        TerminalSession session = (TerminalSession) item.getData();
                        if (session != null) session.paste();
                    }
                    return null;
                }
            });
        }

        // Timer: show/hide overlay based on whether the console has focus.
        final Runnable[] checker = new Runnable[1];
        checker[0] = () -> {
            if (viewDisposed) return;
            CTabItem item = tabFolder.getSelection();
            if (item != null && !item.isDisposed()) {
                TerminalSession session = (TerminalSession) item.getData();
                if (session != null && session.embedded) {
                    boolean swtHasFocus = display.getFocusControl() != null;
                    if (swtHasFocus) {
                        session.showOverlay();
                    } else {
                        session.hideOverlay();
                    }
                }
            }
            display.timerExec(100, checker[0]);
        };
        display.timerExec(100, checker[0]);
    }

    private void openNewSession(String... extraArgs) {
        if (launching) return;
        launching = true;
        try {
            sessionCounter++;
            CTabItem tabItem = new CTabItem(tabFolder, SWT.CLOSE);
            tabItem.setText("Claude " + sessionCounter);

            Composite content = new Composite(tabFolder, SWT.NONE);
            content.setLayout(new FillLayout());
            content.setBackground(bgColor);
            content.setData("org.eclipse.e4.ui.css.disabled", Boolean.TRUE);
            tabItem.setControl(content);

            TerminalSession session = new TerminalSession(tabItem, content, extraArgs);
            tabItem.setData(session);
            tabFolder.setSelection(tabItem);
        } finally {
            Display.getCurrent().timerExec(500, () -> launching = false);
        }
    }

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
        if (bgColor != null && !bgColor.isDisposed()) bgColor.dispose();
        super.dispose();
    }

    // ─── One terminal session per tab ────────────────────────────────────────

    private final class TerminalSession {

        private final CTabItem tabItem;
        private final Composite consoleHost;
        private long consoleHandle = 0;
        private volatile boolean disposed = false;
        private boolean embedded = false;
        private int embedRetries = 0;
        private static final int MAX_EMBED_RETRIES = 30;
        private Shell overlay;

        TerminalSession(CTabItem tabItem, Composite parent, String[] extraArgs) {
            this.tabItem = tabItem;

            // Plain SWT Composite — its Win32 HWND becomes the parent for conhost.
            consoleHost = new Composite(parent, SWT.NO_BACKGROUND);
            consoleHost.setBackground(bgColor);
            consoleHost.setData("org.eclipse.e4.ui.css.disabled", Boolean.TRUE);

            // Resize the embedded console when the host composite resizes.
            consoleHost.addListener(SWT.Resize, e -> {
                if (consoleHandle != 0 && embedded) {
                    var size = consoleHost.getSize();
                    if (size.x > 0 && size.y > 0) {
                        NativeCore.consoleResize(consoleHandle, size.x, size.y);
                    }
                }
            });

            // Any interaction with the host composite → focus the console.
            consoleHost.addListener(SWT.FocusIn, e -> focus());
            consoleHost.addListener(SWT.MouseDown, e -> focus());

            // Defer launch so the widget has its final layout size.
            Display.getCurrent().asyncExec(() -> {
                if (!disposed && !viewDisposed) launch(extraArgs);
            });
        }

        private void launch(String[] extraArgs) {
            if (disposed || viewDisposed) return;

            Activator activator = Activator.getDefault();
            if (!activator.isServerRunning()) activator.initialize();

            int port = activator.getHttpSseServer().getPort();
            String authToken = activator.getHttpSseServer().getAuthToken();

            String claudeCmd = activator.getPreferenceStore().getString(Constants.PREF_CLAUDE_CMD);
            if (claudeCmd == null || claudeCmd.isBlank()) claudeCmd = Constants.DEFAULT_CLAUDE_CMD;

            String workingDir = ResourcesPlugin.getWorkspace().getRoot()
                    .getLocation().toOSString();

            String execCmd;
            List<String> argList = new ArrayList<>();
            if (System.getProperty("os.name", "").toLowerCase().contains("win")) {
                execCmd = "cmd.exe";
                argList.add("/c");
                argList.add(claudeCmd);
            } else {
                execCmd = claudeCmd;
            }
            for (String a : extraArgs) argList.add(a);

            List<String[]> envPairs = new ArrayList<>();
            envPairs.add(new String[]{"CLAUDE_IDE_PORT",       String.valueOf(port)});
            envPairs.add(new String[]{"CLAUDE_IDE_AUTH_TOKEN", authToken});
            envPairs.add(new String[]{"CLAUDE_IDE_NAME",       Constants.IDE_NAME});

            String argsJson     = toJsonStringArray(argList);
            String extraEnvJson = toJsonPairArray(envPairs);

            consoleHandle = NativeCore.consoleCreate(execCmd, argsJson, extraEnvJson, workingDir);
            if (consoleHandle == 0) {
                Activator.logError("consoleCreate failed", null);
                return;
            }

            tryEmbed();
        }

        private void tryEmbed() {
            if (disposed || consoleHandle == 0) return;
            var size = consoleHost.getSize();
            int w = Math.max(size.x, 100);
            int h = Math.max(size.y, 100);

            boolean ok = NativeCore.consoleEmbed(consoleHandle, consoleHost.handle, w, h);
            if (ok) {
                embedded = true;
                Display.getCurrent().timerExec(50, this::focus);
            } else {
                embedRetries++;
                if (embedRetries < MAX_EMBED_RETRIES) {
                    Display.getCurrent().timerExec(100, this::tryEmbed);
                } else {
                    Activator.logError("Failed to embed console after " + MAX_EMBED_RETRIES + " retries", null);
                }
            }
        }

        void showOverlay() {
            if (disposed || consoleHost.isDisposed()) return;
            if (overlay == null || overlay.isDisposed()) {
                overlay = new Shell(consoleHost.getShell(), SWT.NO_TRIM | SWT.TOOL);
                overlay.setAlpha(1); // nearly invisible but catches clicks
                overlay.addListener(SWT.MouseDown, e -> {
                    overlay.setVisible(false);
                    IWorkbenchPage page = getSite().getPage();
                    page.activate(ClaudeCliView.this);
                });
            }
            // Reposition to match consoleHost's screen location
            Point loc = consoleHost.toDisplay(0, 0);
            Point size = consoleHost.getSize();
            overlay.setBounds(loc.x, loc.y, size.x, size.y);
            if (!overlay.isVisible()) {
                overlay.setVisible(true);
            }
        }

        void hideOverlay() {
            if (overlay != null && !overlay.isDisposed() && overlay.isVisible()) {
                overlay.setVisible(false);
            }
        }

        void paste() {
            if (disposed || consoleHandle == 0 || !embedded) return;
            Clipboard cb = new Clipboard(Display.getCurrent());
            try {
                String text = (String) cb.getContents(TextTransfer.getInstance());
                if (text != null) {
                    for (int i = 0; i < text.length(); i++) {
                        char c = text.charAt(i);
                        if (c == '\n') c = '\r'; // console expects CR for Enter
                        NativeCore.consolePostMessage(consoleHandle, 0x0102, c, 0); // WM_CHAR
                    }
                }
            } finally {
                cb.dispose();
            }
        }

        void focus() {
            if (disposed || consoleHandle == 0 || !embedded) return;
            NativeCore.consoleFocus(consoleHandle);
        }

        void dispose() {
            disposed = true;
            if (overlay != null && !overlay.isDisposed()) {
                overlay.dispose();
                overlay = null;
            }
            if (consoleHandle != 0) {
                NativeCore.consoleDestroy(consoleHandle);
                consoleHandle = 0;
            }
        }
    }

    // ─── JSON helpers ────────────────────────────────────────────────────────

    private static String toJsonStringArray(List<String> items) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < items.size(); i++) {
            if (i > 0) sb.append(',');
            sb.append('"').append(items.get(i)
                .replace("\\", "\\\\").replace("\"", "\\\"")).append('"');
        }
        return sb.append(']').toString();
    }

    private static String toJsonPairArray(List<String[]> pairs) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < pairs.size(); i++) {
            if (i > 0) sb.append(',');
            sb.append('[')
              .append('"').append(pairs.get(i)[0].replace("\"", "\\\"")).append("\",")
              .append('"').append(pairs.get(i)[1]
                  .replace("\\", "\\\\").replace("\"", "\\\"")).append('"')
              .append(']');
        }
        return sb.append(']').toString();
    }
}
