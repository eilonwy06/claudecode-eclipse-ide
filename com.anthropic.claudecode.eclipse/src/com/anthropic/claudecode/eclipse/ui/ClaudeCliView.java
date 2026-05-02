package com.anthropic.claudecode.eclipse.ui;

import java.util.ArrayList;
import java.util.List;

import org.eclipse.core.resources.IContainer;
import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.IAdaptable;
import org.eclipse.jface.resource.JFaceResources;
import org.eclipse.jface.util.IPropertyChangeListener;
import org.eclipse.jface.viewers.ISelection;
import org.eclipse.jface.viewers.IStructuredSelection;
import org.eclipse.swt.SWT;
import org.eclipse.swt.custom.CTabFolder;
import org.eclipse.swt.custom.CTabFolder2Adapter;
import org.eclipse.swt.custom.CTabFolderEvent;
import org.eclipse.swt.custom.CTabItem;
import org.eclipse.swt.custom.StyleRange;
import org.eclipse.swt.custom.StyledText;
import org.eclipse.swt.events.SelectionListener;
import org.eclipse.swt.dnd.Clipboard;
import org.eclipse.swt.dnd.TextTransfer;
import org.eclipse.swt.graphics.Color;
import org.eclipse.swt.graphics.Font;
import org.eclipse.swt.graphics.FontData;
import org.eclipse.swt.graphics.GC;
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
import org.eclipse.ui.part.IShowInTarget;
import org.eclipse.ui.part.ShowInContext;
import org.eclipse.ui.part.ViewPart;

import com.anthropic.claudecode.eclipse.Activator;
import com.anthropic.claudecode.eclipse.Constants;
import com.anthropic.claudecode.eclipse.NativeCore;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

public class ClaudeCliView extends ViewPart implements IShowInTarget {

    public static final String VIEW_ID = "com.anthropic.claudecode.eclipse.ui.ClaudeCliView";

    private static final boolean IS_WINDOWS =
            System.getProperty("os.name", "").toLowerCase().contains("win");

    /** Font definition ID from plugin.xml (Colors and Fonts preference). */
    private static final String FONT_ID = "com.anthropic.claudecode.eclipse.font.console";

    private static final int BG_R = 0x12, BG_G = 0x13, BG_B = 0x14; // #121314

    private CTabFolder tabFolder;
    private int sessionCounter = 0;
    private volatile boolean viewDisposed = false;
    private boolean launching = false;
    private Color bgColor;
    private IPropertyChangeListener fontChangeListener;

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
        newBtn.addListener(SWT.Selection, e -> openNewSession(null, null));
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
                    // Hide overlays for all OTHER sessions — prevents ghost
                    // overlays from blocking input on the newly selected tab.
                    for (CTabItem other : tabFolder.getItems()) {
                        if (other != item) {
                            TerminalSession otherSession = (TerminalSession) other.getData();
                            if (otherSession != null) otherSession.hideOverlay();
                        }
                    }
                    // Activate view (needed if switching tabs via keyboard while
                    // another view is active), then directly focus the console.
                    getSite().getPage().activate(ClaudeCliView.this);
                    session.focus();
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

        // Timer: show/hide overlay based on whether this view is visible
        // but not active.  The overlay catches clicks on the embedded console
        // so Eclipse can activate ClaudeCliView; once active, hide it so the
        // console receives real input.  Critically, the overlay must be hidden
        // whenever another view occupies the same view group area — otherwise
        // the floating Shell steals clicks from Terminal/Console/etc.
        final Runnable[] checker = new Runnable[1];
        checker[0] = () -> {
            if (viewDisposed) return;
            CTabItem item = tabFolder.getSelection();
            if (item != null && !item.isDisposed()) {
                TerminalSession session = (TerminalSession) item.getData();
                if (session != null && session.embedded) {
                    IWorkbenchPage page = getSite().getPage();
                    boolean isActivePart = page.getActivePart() == ClaudeCliView.this;
                    boolean isViewVisible = page.isPartVisible(ClaudeCliView.this);
                    if (isViewVisible && !isActivePart) {
                        session.showOverlay();
                    } else {
                        session.hideOverlay();
                    }
                }
            }
            display.timerExec(100, checker[0]);
        };
        display.timerExec(100, checker[0]);

        // Listen for font changes in Colors and Fonts preferences.
        fontChangeListener = event -> {
            if (FONT_ID.equals(event.getProperty())) {
                display.asyncExec(() -> {
                    if (viewDisposed || tabFolder == null || tabFolder.isDisposed()) return;
                    for (CTabItem item : tabFolder.getItems()) {
                        TerminalSession session = (TerminalSession) item.getData();
                        if (session != null) session.updateFont();
                    }
                });
            }
        };
        JFaceResources.getFontRegistry().addListener(fontChangeListener);
    }

    private void openNewSession(String cwd, String scopeLabel, String... extraArgs) {
        if (launching) return;
        launching = true;
        try {
            sessionCounter++;
            CTabItem tabItem = new CTabItem(tabFolder, SWT.CLOSE);
            if (scopeLabel != null && !scopeLabel.isEmpty()) {
                tabItem.setText("Claude (" + scopeLabel + ")");
            } else {
                tabItem.setText("Claude " + sessionCounter);
            }

            Composite content = new Composite(tabFolder, SWT.NONE);
            content.setLayout(new FillLayout());
            content.setBackground(bgColor);
            content.setData("org.eclipse.e4.ui.css.disabled", Boolean.TRUE);
            tabItem.setControl(content);

            TerminalSession session = new TerminalSession(tabItem, content, cwd, extraArgs);
            tabItem.setData(session);
            tabFolder.setSelection(tabItem);
        } finally {
            Display.getCurrent().timerExec(500, () -> launching = false);
        }
    }

    public void launchProcess(String... extraArgs) {
        openNewSession(null, null, extraArgs);
    }

    public void launchProcessInDirectory(String cwd, String scopeLabel, String... extraArgs) {
        openNewSession(cwd, scopeLabel, extraArgs);
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

    @SuppressWarnings("unchecked")
    @Override
    public <T> T getAdapter(Class<T> adapter) {
        if (adapter == IShowInTarget.class) {
            return (T) this;
        }
        return super.getAdapter(adapter);
    }

    @Override
    public boolean show(ShowInContext context) {
        if (context == null) return false;

        ISelection selection = context.getSelection();
        if (!(selection instanceof IStructuredSelection)) return false;

        IStructuredSelection structured = (IStructuredSelection) selection;
        Object element = structured.getFirstElement();
        if (element == null) return false;

        IResource resource = null;
        if (element instanceof IResource) {
            resource = (IResource) element;
        } else if (element instanceof IAdaptable) {
            resource = ((IAdaptable) element).getAdapter(IResource.class);
        }

        if (resource == null) return false;

        String cwd;
        String scopeLabel;

        if (resource instanceof IFile) {
            IContainer parent = resource.getParent();
            cwd = parent.getLocation().toOSString();
            scopeLabel = parent.getName();
        } else if (resource instanceof IContainer) {
            cwd = resource.getLocation().toOSString();
            scopeLabel = resource.getName();
        } else {
            return false;
        }

        openNewSession(cwd, scopeLabel);
        return true;
    }

    public void restartAllSessions() {
        if (tabFolder == null || tabFolder.isDisposed()) return;
        int count = tabFolder.getItemCount();
        if (count == 0) return;

        for (CTabItem item : tabFolder.getItems()) {
            TerminalSession session = (TerminalSession) item.getData();
            if (session != null) session.dispose();
            item.dispose();
        }
        sessionCounter = 0;

        for (int i = 0; i < count; i++) {
            openNewSession(null, null);
        }
    }

    @Override
    public void dispose() {
        viewDisposed = true;
        if (fontChangeListener != null) {
            JFaceResources.getFontRegistry().removeListener(fontChangeListener);
            fontChangeListener = null;
        }
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
        private final String customCwd;
        private volatile boolean disposed = false;

        // ── Windows: embedded conhost ───────────────────────────────────
        private long consoleHandle = 0;
        private boolean embedded = false;
        private int embedRetries = 0;
        private static final int MAX_EMBED_RETRIES = 30;
        private Shell overlay;

        // ── Non-Windows: PTY + StyledText terminal renderer ────────────
        private long ptyHandle = 0;
        private StyledText termText;
        private Font termFont;
        private List<String> scrollbackLines = new ArrayList<>();
        /**
         * Color cache for the PTY terminal (Linux/macOS only).
         * Colors are keyed by packed RGB (r<<16|g<<8|b) and reused across renders.
         * SWT's StyledTextRenderer stores Color references from StyleRange objects,
         * so Colors must stay alive for the entire session — never disposed mid-session.
         */
        private final java.util.Map<Integer, Color> termColors = new java.util.HashMap<>();
        /** Visible screen rows from the last onScreenUpdate. */
        private int screenRows = 24;
        /** Whether the terminal is in alternate-screen mode (vi, less, etc.). */
        private boolean altScreen = false;
        /** Mouse protocol mode reported by the PTY — 0 = none. */
        private int mouseMode = 0;
        /** Mouse protocol encoding — 0 = default, 2 = SGR. */
        private int mouseEnc = 0;

        TerminalSession(CTabItem tabItem, Composite parent, String cwd, String[] extraArgs) {
            this.tabItem = tabItem;
            this.customCwd = cwd;

            // Plain SWT Composite — its Win32 HWND becomes the parent for conhost.
            consoleHost = new Composite(parent, SWT.NO_BACKGROUND);
            consoleHost.setBackground(bgColor);
            consoleHost.setData("org.eclipse.e4.ui.css.disabled", Boolean.TRUE);

            if (IS_WINDOWS) {
                // Resize the embedded console when the host composite resizes.
                consoleHost.addListener(SWT.Resize, e -> {
                    if (consoleHandle != 0 && embedded) {
                        var size = consoleHost.getSize();
                        if (size.x > 0 && size.y > 0) {
                            NativeCore.consoleResize(consoleHandle, size.x, size.y);
                        }
                    }
                });
                consoleHost.addListener(SWT.FocusIn, e -> focus());
                consoleHost.addListener(SWT.MouseDown, e -> focus());
            } else {
                initPtyTerminal();
            }

            // Defer launch so the widget has its final layout size.
            Display.getCurrent().asyncExec(() -> {
                if (!disposed && !viewDisposed) launch(extraArgs);
            });
        }

        // ── PTY terminal setup (Linux/macOS) ────────────────────────────

        private void initPtyTerminal() {
            consoleHost.setLayout(new FillLayout());

            termText = new StyledText(consoleHost, SWT.MULTI | SWT.V_SCROLL);
            termText.setBackground(bgColor);
            // Use cachedColor so the Color object stays alive for the session.
            // StyledTextRenderer stores the Color reference internally; disposing it
            // immediately (as was done before) causes a GC.setForeground crash on GTK.
            termText.setForeground(cachedColor(229, 229, 229));

            // Use font from Colors and Fonts preferences (defaults to Text Font).
            termFont = JFaceResources.getFont(FONT_ID);
            termText.setFont(termFont);
            termText.setWordWrap(false);
            termText.setAlwaysShowScrollBars(false);
            termText.setData("org.eclipse.e4.ui.css.disabled", Boolean.TRUE);
            termText.setCaret(null); // hide SWT caret; the PTY renderer shows its own

            // Forward keyboard input to the PTY.
            termText.addListener(SWT.KeyDown, e -> {
                if (ptyHandle == 0) return;
                String seq = keyEventToTerminal(e);
                if (seq != null) {
                    NativeCore.ptyWriteInput(ptyHandle, seq);
                }
                e.doit = false; // prevent StyledText from interpreting the key
            });

            // Handle resize: recalculate cols/rows and notify PTY.
            consoleHost.addListener(SWT.Resize, e -> {
                if (ptyHandle == 0) return;
                int[] cr = calcColsRows();
                if (cr[0] > 0 && cr[1] > 0) {
                    NativeCore.ptyResize(ptyHandle, cr[0], cr[1]);
                }
            });

            // Mouse wheel → scroll in alternate screen via escape sequences,
            // normal scroll via StyledText's built-in scrollbar.
            termText.addListener(SWT.MouseVerticalWheel, e -> {
                if (ptyHandle == 0 || mouseMode == 0) return;
                // In mouse-reporting mode, send scroll up/down escape sequences.
                int lines = e.count > 0 ? 3 : -3;
                int btn = lines > 0 ? 64 : 65; // 64 = scroll up, 65 = scroll down
                int count = Math.abs(lines);
                for (int i = 0; i < count; i++) {
                    if (mouseEnc == 2) { // SGR encoding
                        String seq = String.format("\033[<%d;%d;%dM", btn, 1, 1);
                        NativeCore.ptyWriteInput(ptyHandle, seq);
                    } else { // Default encoding
                        char cb = (char) (btn + 32);
                        char cx = (char) (1 + 32);
                        char cy = (char) (1 + 32);
                        String seq = "\033[M" + cb + cx + cy;
                        NativeCore.ptyWriteInput(ptyHandle, seq);
                    }
                }
                e.doit = false;
            });
        }

        /**
         * Returns a cached SWT Color for the given RGB values (Linux/macOS PTY path only).
         * Colors are created once per RGB value and reused across all renders in this session.
         * This avoids SWT OS resource leaks and ensures the Color objects stay alive
         * for as long as any StyleRange referencing them is applied to the StyledText.
         */
        private Color cachedColor(int r, int g, int b) {
            int key = (r << 16) | (g << 8) | b;
            return termColors.computeIfAbsent(key,
                    k -> new Color(termText.getDisplay(), r, g, b));
        }

        /** Calculates terminal columns and rows from the StyledText widget size. */
        private int[] calcColsRows() {
            if (termText == null || termText.isDisposed()) return new int[]{80, 24};
            GC gc = new GC(termText);
            gc.setFont(termText.getFont());
            int charW = (int) gc.getFontMetrics().getAverageCharacterWidth();
            int charH = gc.getFontMetrics().getHeight();
            gc.dispose();
            Point size = consoleHost.getSize();
            int cols = Math.max(1, size.x / Math.max(charW, 1));
            int rows = Math.max(1, size.y / Math.max(charH, 1));
            return new int[]{cols, rows};
        }

        /** Translates an SWT key event into a terminal input string. */
        private String keyEventToTerminal(org.eclipse.swt.widgets.Event e) {
            // Ctrl+key combinations
            if ((e.stateMask & SWT.CTRL) != 0 && e.keyCode >= 'a' && e.keyCode <= 'z') {
                return String.valueOf((char) (e.keyCode - 'a' + 1));
            }
            // Ctrl+Shift+key (uppercase)
            if ((e.stateMask & SWT.CTRL) != 0 && e.keyCode >= 'A' && e.keyCode <= 'Z') {
                return String.valueOf((char) (e.keyCode - 'A' + 1));
            }

            switch (e.keyCode) {
                case SWT.ARROW_UP:    return "\033[A";
                case SWT.ARROW_DOWN:  return "\033[B";
                case SWT.ARROW_RIGHT: return "\033[C";
                case SWT.ARROW_LEFT:  return "\033[D";
                case SWT.HOME:        return "\033[H";
                case SWT.END:         return "\033[F";
                case SWT.INSERT:      return "\033[2~";
                case SWT.DEL:         return "\033[3~";
                case SWT.PAGE_UP:     return "\033[5~";
                case SWT.PAGE_DOWN:   return "\033[6~";
                case SWT.F1:  return "\033OP";
                case SWT.F2:  return "\033OQ";
                case SWT.F3:  return "\033OR";
                case SWT.F4:  return "\033OS";
                case SWT.F5:  return "\033[15~";
                case SWT.F6:  return "\033[17~";
                case SWT.F7:  return "\033[18~";
                case SWT.F8:  return "\033[19~";
                case SWT.F9:  return "\033[20~";
                case SWT.F10: return "\033[21~";
                case SWT.F11: return "\033[23~";
                case SWT.F12: return "\033[24~";
                case SWT.BS:  return "\177";
                case SWT.ESC: return "\033";
                case SWT.TAB:
                    if ((e.stateMask & SWT.SHIFT) != 0) return "\033[Z";
                    return "\t";
                case SWT.CR:
                case SWT.LF:
                    return "\r";
                default:
                    if (e.character != 0 && e.character != SWT.DEL) {
                        return String.valueOf(e.character);
                    }
                    return null;
            }
        }

        /** Called from the Rust PTY reader thread via JNI callback. */
        private void onScreenUpdate(String screenJson) {
            Display display = Display.getDefault();
            if (display.isDisposed()) return;
            display.asyncExec(() -> {
                if (disposed || termText == null || termText.isDisposed()) return;
                renderScreen(screenJson);
            });
        }

        /** Called from the Rust PTY reader thread when the process exits. */
        private void onPtyExit() {
            Display display = Display.getDefault();
            if (display.isDisposed()) return;
            display.asyncExec(() -> {
                if (disposed || termText == null || termText.isDisposed()) return;
                termText.append("\r\n[Process exited]\r\n");
                termText.setTopIndex(termText.getLineCount() - 1);
            });
        }

        /** Parses the screen JSON from vterm and renders into the StyledText. */
        private void renderScreen(String screenJson) {
            JsonObject root;
            try {
                root = JsonParser.parseString(screenJson).getAsJsonObject();
            } catch (Exception ex) {
                return;
            }

            int rows = root.get("rows").getAsInt();
            int cols = root.get("cols").getAsInt();
            int cy = root.get("cy").getAsInt();
            int cx = root.get("cx").getAsInt();
            boolean cursorVisible = root.get("cv").getAsBoolean();
            this.altScreen = root.has("alt") && root.get("alt").getAsInt() == 1;
            this.mouseMode = root.has("mm") ? root.get("mm").getAsInt() : 0;
            this.mouseEnc = root.has("me") ? root.get("me").getAsInt() : 0;
            this.screenRows = rows;

            // Accumulate new scrollback lines.
            if (root.has("nsb")) {
                JsonArray nsb = root.getAsJsonArray("nsb");
                for (JsonElement el : nsb) {
                    scrollbackLines.add(el.getAsString());
                }
            }

            // Build the full text content: scrollback + visible screen.
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < scrollbackLines.size(); i++) {
                sb.append(scrollbackLines.get(i));
                sb.append('\n');
            }

            JsonArray lines = root.getAsJsonArray("lines");
            List<StyleRange> styles = new ArrayList<>();
            int scrollbackOffset = sb.length();

            for (int r = 0; r < lines.size(); r++) {
                JsonObject line = lines.get(r).getAsJsonObject();
                String text = line.get("t").getAsString();
                int lineStart = sb.length();
                sb.append(text);
                if (r < lines.size() - 1) sb.append('\n');

                // Apply style spans.
                JsonArray spans = line.getAsJsonArray("s");
                if (spans != null) {
                    for (JsonElement spanEl : spans) {
                        JsonObject span = spanEl.getAsJsonObject();
                        int offset = span.get("o").getAsInt();
                        int len = span.get("l").getAsInt();

                        StyleRange style = new StyleRange();
                        style.start = lineStart + offset;
                        style.length = Math.min(len, sb.length() - style.start);
                        if (style.length <= 0) continue;

                        if (span.has("fg")) {
                            JsonArray fg = span.getAsJsonArray("fg");
                            style.foreground = cachedColor(
                                    fg.get(0).getAsInt(), fg.get(1).getAsInt(), fg.get(2).getAsInt());
                        }
                        if (span.has("bg")) {
                            JsonArray bg = span.getAsJsonArray("bg");
                            style.background = cachedColor(
                                    bg.get(0).getAsInt(), bg.get(1).getAsInt(), bg.get(2).getAsInt());
                        }

                        int fontStyle = SWT.NORMAL;
                        if (span.has("b") && span.get("b").getAsInt() == 1) fontStyle |= SWT.BOLD;
                        if (span.has("i") && span.get("i").getAsInt() == 1) fontStyle |= SWT.ITALIC;
                        style.fontStyle = fontStyle;

                        if (span.has("u") && span.get("u").getAsInt() == 1) {
                            style.underline = true;
                        }

                        // Inverse: swap fg/bg.
                        if (span.has("v") && span.get("v").getAsInt() == 1) {
                            Color tmp = style.foreground;
                            style.foreground = style.background;
                            style.background = tmp;
                            if (style.foreground == null)
                                style.foreground = cachedColor(BG_R, BG_G, BG_B);
                            if (style.background == null)
                                style.background = cachedColor(229, 229, 229);
                        }

                        styles.add(style);
                    }
                }
            }

            String fullText = sb.toString();

            // Check if user is currently scrolled to (or near) the bottom.
            // If so, we'll auto-scroll after updating; otherwise, preserve their position.
            int oldTopIndex = termText.getTopIndex();
            int oldLineCount = termText.getLineCount();
            int visibleLines = termText.getClientArea().height / termText.getLineHeight();
            boolean wasAtBottom = (oldTopIndex + visibleLines >= oldLineCount - 1);

            termText.setText(fullText);

            // Apply styles.
            for (StyleRange style : styles) {
                termText.setStyleRange(style);
            }

            // Only auto-scroll if user was already at the bottom.
            if (wasAtBottom) {
                int cursorLine = scrollbackLines.size() + cy;
                int totalLines = termText.getLineCount();
                if (cursorLine >= 0 && cursorLine < totalLines) {
                    termText.setTopIndex(Math.max(0, cursorLine - (screenRows - 1)));
                }
            }
        }

        // ── Shared launch logic ─────────────────────────────────────────

        private void launch(String[] extraArgs) {
            if (disposed || viewDisposed) return;

            Activator activator = Activator.getDefault();
            if (!activator.isServerRunning()) activator.initialize();

            int port = activator.getHttpSseServer().getPort();
            String authToken = activator.getHttpSseServer().getAuthToken();

            // Remove other Eclipse instances' lock files so Claude CLI only finds ours.
            // Then rewrite ours to ensure it's present (in case another instance deleted it).
            NativeCore.lockFileRemoveOthers(port);
            activator.getLockFileManager().writeLockFile(port, authToken);

            String claudeCmd = activator.getPreferenceStore().getString(Constants.PREF_CLAUDE_CMD);
            if (claudeCmd == null || claudeCmd.isBlank()) claudeCmd = Constants.DEFAULT_CLAUDE_CMD;

            String claudeArgs = activator.getPreferenceStore().getString(Constants.PREF_CLAUDE_ARGS);

            String workingDir;
            if (customCwd != null && !customCwd.isEmpty()) {
                workingDir = customCwd;
            } else {
                workingDir = ResourcesPlugin.getWorkspace().getRoot()
                        .getLocation().toOSString();
            }

            String execCmd;
            List<String> argList = new ArrayList<>();
            if (IS_WINDOWS) {
                execCmd = "cmd.exe";
                argList.add("/D");
                argList.add("/c");
                argList.add(claudeCmd);
            } else {
                execCmd = claudeCmd;
            }
            if (claudeArgs != null && !claudeArgs.isBlank()) {
                for (String arg : claudeArgs.trim().split("\\s+")) {
                    argList.add(arg);
                }
            }
            for (String a : extraArgs) argList.add(a);

            List<String[]> envPairs = new ArrayList<>();
            envPairs.add(new String[]{"CLAUDE_IDE_PORT",       String.valueOf(port)});
            envPairs.add(new String[]{"CLAUDE_IDE_AUTH_TOKEN", authToken});
            envPairs.add(new String[]{"CLAUDE_IDE_NAME",       Constants.IDE_NAME});

            String argsJson     = toJsonStringArray(argList);
            String extraEnvJson = toJsonPairArray(envPairs);

            if (IS_WINDOWS) {
                launchConsole(execCmd, argsJson, extraEnvJson, workingDir);
            } else {
                launchPty(execCmd, argsJson, extraEnvJson, workingDir);
            }
        }

        // ── Windows console launch ──────────────────────────────────────

        private void launchConsole(String execCmd, String argsJson, String extraEnvJson, String workingDir) {
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
                updateFont(); // Apply font from Colors and Fonts preferences.
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

        // ── Non-Windows PTY launch ──────────────────────────────────────

        private void launchPty(String execCmd, String argsJson, String extraEnvJson, String workingDir) {
            ptyHandle = NativeCore.ptyCreate();
            if (ptyHandle == 0) {
                Activator.logError("ptyCreate failed", null);
                return;
            }

            NativeCore.ptyRegisterCallbacks(ptyHandle, new NativeCore.PtyCallbacks() {
                @Override
                public void onScreenUpdate(String screenJson) {
                    TerminalSession.this.onScreenUpdate(screenJson);
                }
                @Override
                public void onExit() {
                    TerminalSession.this.onPtyExit();
                }
            });

            int[] cr = calcColsRows();
            NativeCore.ptyStart(ptyHandle, execCmd, argsJson, extraEnvJson, workingDir, cr[0], cr[1]);
        }

        // ── Overlay (Windows only) ──────────────────────────────────────

        void showOverlay() {
            if (!IS_WINDOWS) return;
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

        // ── Paste ───────────────────────────────────────────────────────

        void paste() {
            Clipboard cb = new Clipboard(Display.getCurrent());
            try {
                String text = (String) cb.getContents(TextTransfer.getInstance());
                if (text == null) return;

                if (IS_WINDOWS) {
                    if (disposed || consoleHandle == 0 || !embedded) return;
                    for (int i = 0; i < text.length(); i++) {
                        char c = text.charAt(i);
                        if (c == '\n') c = '\r';
                        NativeCore.consolePostMessage(consoleHandle, 0x0102, c, 0); // WM_CHAR
                    }
                } else {
                    if (disposed || ptyHandle == 0) return;
                    // Replace \n with \r for the terminal.
                    NativeCore.ptyWriteInput(ptyHandle, text.replace('\n', '\r'));
                }
            } finally {
                cb.dispose();
            }
        }

        // ── Focus ───────────────────────────────────────────────────────

        void focus() {
            if (disposed) return;
            if (IS_WINDOWS) {
                if (consoleHandle == 0 || !embedded) return;
                NativeCore.consoleFocus(consoleHandle);
            } else {
                if (termText != null && !termText.isDisposed()) {
                    termText.setFocus();
                }
            }
        }

        // ── Dispose ─────────────────────────────────────────────────────

        void updateFont() {
            boolean debug = Activator.getDefault().getPreferenceStore().getBoolean(Constants.PREF_DEBUG_MODE);
            if (debug) System.out.println("[FONT DEBUG] updateFont() called, disposed=" + disposed);
            if (disposed) return;
            Font font = JFaceResources.getFont(FONT_ID);
            FontData[] fontData = font.getFontData();
            if (debug) System.out.println("[FONT DEBUG] fontData.length=" + fontData.length);
            if (fontData.length == 0) return;
            String fontName = fontData[0].getName();
            int fontSize = fontData[0].getHeight();
            if (debug) {
                System.out.println("[FONT DEBUG] fontName='" + fontName + "', fontSize=" + fontSize);
                System.out.println("[FONT DEBUG] IS_WINDOWS=" + IS_WINDOWS + ", consoleHandle=" + consoleHandle + ", embedded=" + embedded);
            }

            if (IS_WINDOWS) {
                if (consoleHandle != 0 && embedded) {
                    if (debug) System.out.println("[FONT DEBUG] Calling NativeCore.consoleSetFont()");
                    NativeCore.consoleSetFont(consoleHandle, fontName, fontSize);
                    if (debug) System.out.println("[FONT DEBUG] consoleSetFont() returned");
                } else {
                    if (debug) System.out.println("[FONT DEBUG] Skipped: consoleHandle=" + consoleHandle + ", embedded=" + embedded);
                }
            } else {
                if (termText != null && !termText.isDisposed()) {
                    termFont = font;
                    termText.setFont(termFont);
                    // Recalculate terminal size with new font metrics.
                    if (ptyHandle != 0) {
                        int[] cr = calcColsRows();
                        if (cr[0] > 0 && cr[1] > 0) {
                            NativeCore.ptyResize(ptyHandle, cr[0], cr[1]);
                        }
                    }
                }
            }
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
            if (ptyHandle != 0) {
                NativeCore.ptyDestroy(ptyHandle);
                ptyHandle = 0;
            }
            // termFont is managed by JFaceResources — do not dispose.
            termFont = null;
            // Dispose cached PTY terminal colors (Linux/macOS only — no-op on Windows).
            for (Color c : termColors.values()) {
                if (!c.isDisposed()) c.dispose();
            }
            termColors.clear();
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
