package com.anthropic.claudecode.eclipse.ui;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

import org.eclipse.core.commands.AbstractHandler;
import org.eclipse.core.commands.ExecutionEvent;
import org.eclipse.core.commands.ExecutionException;
import org.eclipse.core.resources.IContainer;
import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.IAdaptable;
import org.eclipse.jface.action.Action;
import org.eclipse.jface.action.IToolBarManager;
import org.eclipse.jface.resource.FontRegistry;
import org.eclipse.jface.resource.JFaceResources;
import org.eclipse.jface.util.IPropertyChangeListener;
import org.eclipse.jface.viewers.ISelection;
import org.eclipse.jface.viewers.IStructuredSelection;
import org.eclipse.swt.SWT;
import org.eclipse.swt.custom.CTabFolder;
import org.eclipse.swt.custom.CTabFolder2Adapter;
import org.eclipse.swt.custom.CTabFolderEvent;
import org.eclipse.swt.custom.CTabItem;
import org.eclipse.swt.events.SelectionListener;
import org.eclipse.swt.graphics.Color;
import org.eclipse.swt.graphics.FontData;
import org.eclipse.swt.layout.FillLayout;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Display;
import org.eclipse.ui.ISharedImages;
import org.eclipse.ui.PlatformUI;
import org.eclipse.ui.handlers.IHandlerService;
import org.eclipse.ui.part.IShowInTarget;
import org.eclipse.ui.part.ShowInContext;
import org.eclipse.ui.part.ViewPart;

import org.eclipse.terminal.connector.ISettingsStore;
import org.eclipse.terminal.connector.ITerminalConnector;
import org.eclipse.terminal.connector.InMemorySettingsStore;
import org.eclipse.terminal.connector.TerminalConnectorExtension;
import org.eclipse.terminal.connector.TerminalState;
import org.eclipse.terminal.connector.process.ProcessSettings;
import org.eclipse.terminal.control.ITerminalListener;
import org.eclipse.terminal.control.ITerminalViewControl;
import org.eclipse.terminal.control.TerminalTitleRequestor;
import org.eclipse.terminal.control.TerminalViewControlFactory;

import com.anthropic.claudecode.eclipse.Activator;
import com.anthropic.claudecode.eclipse.Constants;
import com.anthropic.claudecode.eclipse.NativeCore;

/**
 * Claude CLI view: a tabbed view ("Claude 1", "Claude 2", …) where each tab
 * embeds an Eclipse terminal control running the Claude CLI.
 *
 * <p>The terminal is the Eclipse terminal control ({@code org.eclipse.terminal})
 * embedded directly via {@link TerminalViewControlFactory}, launched through the
 * local-process connector. The view owns its own {@link CTabFolder} so it keeps
 * "Claude N" tab titles and uses the plugin's own console font.
 *
 * <p>IMPORTANT: the connector's {@code localEcho} MUST be false. {@code
 * ProcessSettings} defaults it to true, which double-echoes every keystroke and
 * desyncs Claude's cursor-driven rendering (garbled glyphs / doubled input).
 */
public class ClaudeCliView extends ViewPart implements IShowInTarget {

    public static final String VIEW_ID = "com.anthropic.claudecode.eclipse.ui.ClaudeCliView";

    private static final boolean IS_WINDOWS =
            System.getProperty("os.name", "").toLowerCase().contains("win");

    /** Font definition ID from plugin.xml (Colors and Fonts preference). */
    private static final String FONT_ID = "com.anthropic.claudecode.eclipse.font.console";

    /**
     * Dedicated JFaceResources key the terminal resolves for BOTH drawing and
     * cell-grid measurement. We mirror the user's console font ({@link #FONT_ID},
     * which lives in the workbench theme registry) into this key so the terminal
     * uses it consistently.
     */
    private static final String TERMINAL_FONT_KEY =
            "com.anthropic.claudecode.eclipse.terminalFont";

    /** Stable extension ID of the local-process terminal connector. */
    private static final String LOCAL_CONNECTOR_ID =
            "org.eclipse.terminal.connector.local.LocalConnector";

    // Dark theme background (tab content fill) + COLORFGBG hint for Claude.
    private static final int DARK_BG_R = 0x12, DARK_BG_G = 0x13, DARK_BG_B = 0x14; // #121314
    private static final String DARK_COLORFGBG_ENV_VAL = "15;0";

    // Light theme background + COLORFGBG hint for Claude.
    private static final int LIGHT_BG_R = 0xF5, LIGHT_BG_G = 0xF5, LIGHT_BG_B = 0xF5; // #F5F5F5
    private static final String LIGHT_COLORFGBG_ENV_VAL = "0;15";

    private int bgR, bgG, bgB;
    private String colorFgBgEnvVal;

    private CTabFolder tabFolder;
    private int sessionCounter = 0;
    private volatile boolean viewDisposed = false;
    private boolean launching = false;
    private Color bgColor;
    private IPropertyChangeListener fontChangeListener;
    private IPropertyChangeListener themeChangeListener;

    @Override
    public void createPartControl(Composite parent) {
        Display display = parent.getDisplay();

        String theme = Activator.getDefault().getPreferenceStore()
                .getString(Constants.PREF_CONSOLE_THEME);
        setThemeColors(theme, display);

        Composite container = new Composite(parent, SWT.NONE);
        GridLayout layout = new GridLayout(1, false);
        layout.marginWidth = 0;
        layout.marginHeight = 0;
        layout.verticalSpacing = 0;
        container.setLayout(layout);

        tabFolder = new CTabFolder(container, SWT.BORDER | SWT.CLOSE);
        tabFolder.setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, true));
        tabFolder.setTabHeight(24);

        configureActionBars();

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
                    session.focus();
                }
            }
        }));

        // Forward Ctrl+V to the active terminal (Eclipse grabs it first).
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

        themeChangeListener = event -> {
            if (Constants.PREF_CONSOLE_THEME.equals(event.getProperty())) {
                display.asyncExec(() -> {
                    if (viewDisposed || tabFolder == null || tabFolder.isDisposed()) return;
                    applyTheme((String) event.getNewValue());
                });
            }
        };
        Activator.getDefault().getPreferenceStore().addPropertyChangeListener(themeChangeListener);
    }

    private void applyTheme(String theme) {
        Display display = Display.getCurrent();
        if (display == null) return;
        Color oldBg = bgColor;
        setThemeColors(theme, display);
        for (CTabItem item : tabFolder.getItems()) {
            TerminalSession session = (TerminalSession) item.getData();
            if (session != null) session.updateTheme();
        }
        if (oldBg != null && !oldBg.isDisposed()) oldBg.dispose();
    }

    private void configureActionBars() {
        IToolBarManager toolBar = getViewSite().getActionBars().getToolBarManager();
        Action newSession = new Action("New Claude CLI Session") {
            @Override
            public void run() {
                openNewSession(null, null);
            }
        };
        newSession.setToolTipText("New Claude CLI Session");
        newSession.setImageDescriptor(PlatformUI.getWorkbench().getSharedImages()
                .getImageDescriptor(ISharedImages.IMG_OBJ_ADD));
        toolBar.add(newSession);
    }

    private void setThemeColors(String theme, Display display) {
        if (Constants.CONSOLE_THEME_LIGHT.equals(theme)) {
            bgR = LIGHT_BG_R; bgG = LIGHT_BG_G; bgB = LIGHT_BG_B;
            colorFgBgEnvVal = LIGHT_COLORFGBG_ENV_VAL;
        } else {
            bgR = DARK_BG_R; bgG = DARK_BG_G; bgB = DARK_BG_B;
            colorFgBgEnvVal = DARK_COLORFGBG_ENV_VAL;
        }
        bgColor = new Color(display, bgR, bgG, bgB);
    }

    private void openNewSession(String cwd, String scopeLabel, String... extraArgs) {
        if (launching) return;
        launching = true;
        try {
            sessionCounter++;
            CTabItem tabItem = new CTabItem(tabFolder, SWT.CLOSE);
            tabItem.setText((scopeLabel != null && !scopeLabel.isEmpty())
                    ? "Claude (" + scopeLabel + ")"
                    : "Claude " + sessionCounter);

            Composite content = new Composite(tabFolder, SWT.NONE);
            content.setLayout(new FillLayout());
            content.setBackground(bgColor);
            tabItem.setControl(content);

            TerminalSession session = new TerminalSession(content, cwd, extraArgs);
            tabItem.setData(session);
            tabFolder.setSelection(tabItem);
            session.focus();
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
        for (int i = 0; i < count; i++) openNewSession(null, null);
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
        if (adapter == IShowInTarget.class) return (T) this;
        return super.getAdapter(adapter);
    }

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
        if (resource instanceof IFile) {
            IContainer parent = resource.getParent();
            openNewSession(parent.getLocation().toOSString(), parent.getName());
            return true;
        } else if (resource instanceof IContainer) {
            openNewSession(resource.getLocation().toOSString(), resource.getName());
            return true;
        }
        return false;
    }

    @Override
    public void dispose() {
        viewDisposed = true;
        if (fontChangeListener != null) {
            JFaceResources.getFontRegistry().removeListener(fontChangeListener);
            fontChangeListener = null;
        }
        if (themeChangeListener != null) {
            Activator.getDefault().getPreferenceStore().removePropertyChangeListener(themeChangeListener);
            themeChangeListener = null;
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

    // ─── Command-line / font helpers ─────────────────────────────────────────

    /**
     * Quotes a token for the connector's StreamTokenizer. Tokens without
     * whitespace are returned unchanged (unquoted backslashes survive). Tokens
     * with whitespace are double-quoted; on Windows their backslashes become
     * forward slashes (the tokenizer mangles backslashes inside quotes, and
     * Win32 CreateProcess accepts forward slashes in absolute paths).
     */
    private static String quoteArg(String token) {
        if (token == null || token.isEmpty()) return token;
        for (int i = 0; i < token.length(); i++) {
            if (Character.isWhitespace(token.charAt(i))) {
                String body = IS_WINDOWS ? token.replace('\\', '/') : token;
                return "\"" + body + "\"";
            }
        }
        return token;
    }

    private static String pathFrom(String[] shellEnv) {
        if (shellEnv != null) {
            for (String e : shellEnv) {
                if (e != null && e.startsWith("PATH=")) return e.substring("PATH=".length());
            }
        }
        return System.getenv("PATH");
    }

    /** Resolves a bare command to an absolute path against {@code pathValue}
     *  (non-Windows; no PATHEXT). Returns {@code cmd} unchanged if already a
     *  path, PATH empty, or no match. */
    private static String resolveExecutable(String cmd, String pathValue) {
        if (cmd == null || cmd.isEmpty()) return cmd;
        if (cmd.indexOf('/') >= 0 || cmd.indexOf('\\') >= 0) return cmd;
        if (pathValue == null || pathValue.isEmpty()) return cmd;
        for (String dir : pathValue.split(java.util.regex.Pattern.quote(File.pathSeparator))) {
            if (dir.isEmpty()) continue;
            File f = new File(dir, cmd);
            if (f.isFile() && f.canExecute()) return f.getAbsolutePath();
        }
        return cmd;
    }

    /** The user's console font ({@link #FONT_ID}) from the theme registry, with
     *  fallbacks, for mirroring into {@link #TERMINAL_FONT_KEY}. */
    private FontData[] consoleFontData() {
        try {
            FontRegistry themeReg = PlatformUI.getWorkbench().getThemeManager()
                    .getCurrentTheme().getFontRegistry();
            if (themeReg.hasValueFor(FONT_ID)) return themeReg.getFontData(FONT_ID);
        } catch (Exception ignore) {
            // Workbench/theme unavailable — fall through.
        }
        if (JFaceResources.getFontRegistry().hasValueFor(FONT_ID)) {
            return JFaceResources.getFontRegistry().getFontData(FONT_ID);
        }
        return JFaceResources.getTextFont().getFontData();
    }

    // ─── One terminal session per tab ────────────────────────────────────────

    private final class TerminalSession {

        private final Composite content;
        private final String customCwd;
        private volatile boolean disposed = false;
        private ITerminalViewControl termControl;

        TerminalSession(Composite content, String cwd, String[] extraArgs) {
            this.content = content;
            this.customCwd = cwd;
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

            NativeCore.lockFileRemoveOthers(port);
            activator.getLockFileManager().writeLockFile(port, authToken);

            String claudeCmd = activator.getPreferenceStore().getString(Constants.PREF_CLAUDE_CMD);
            if (claudeCmd == null || claudeCmd.isBlank()) claudeCmd = Constants.DEFAULT_CLAUDE_CMD;
            String claudeArgs = activator.getPreferenceStore().getString(Constants.PREF_CLAUDE_ARGS);

            String workingDir = (customCwd != null && !customCwd.isEmpty())
                    ? customCwd
                    : ResourcesPlugin.getWorkspace().getRoot().getLocation().toOSString();

            // Environment: captured login-shell PATH/proxy (macOS/Linux) + IDE
            // vars + COLORFGBG. Our entries win over the native env on collision.
            List<String> env = new ArrayList<>();
            String[] shellEnv = null;
            try {
                shellEnv = NativeCore.shellEnvInject();
            } catch (Throwable t) {
                if (Activator.getDefault().getPreferenceStore().getBoolean(Constants.PREF_DEBUG_MODE)) {
                    Activator.logError("shellEnvInject unavailable; launching without captured shell env", t);
                }
            }
            if (shellEnv != null) {
                for (String e : shellEnv) {
                    if (e != null && !e.isEmpty()) env.add(e);
                }
            }
            env.add("CLAUDE_IDE_PORT=" + port);
            env.add("CLAUDE_IDE_AUTH_TOKEN=" + authToken);
            env.add("CLAUDE_IDE_NAME=" + Constants.IDE_NAME);
            env.add("COLORFGBG=" + colorFgBgEnvVal);
            String[] environment = env.toArray(new String[0]);

            // macOS/Linux: resolve a bare command against the captured PATH.
            if (!IS_WINDOWS) {
                claudeCmd = resolveExecutable(claudeCmd, pathFrom(shellEnv));
            }

            String image = quoteArg(claudeCmd);
            List<String> argTokens = new ArrayList<>();
            if (claudeArgs != null && !claudeArgs.isBlank()) {
                for (String arg : claudeArgs.trim().split("\\s+")) argTokens.add(quoteArg(arg));
            }
            for (String a : extraArgs) argTokens.add(quoteArg(a));
            String arguments = String.join(" ", argTokens);

            ITerminalConnector connector;
            try {
                connector = TerminalConnectorExtension.makeTerminalConnector(LOCAL_CONNECTOR_ID);
            } catch (CoreException ex) {
                Activator.logError("Failed to create local terminal connector", ex);
                return;
            }
            if (connector == null) {
                Activator.logError("Local terminal connector '" + LOCAL_CONNECTOR_ID + "' not found", null);
                return;
            }

            ProcessSettings settings = new ProcessSettings();
            settings.setImage(image);
            settings.setArguments(arguments);
            settings.setWorkingDir(workingDir);
            settings.setEnvironment(environment);
            settings.setMergeWithNativeEnvironment(true);
            // MUST be false: claude echoes its own input. The default (true)
            // double-echoes every keystroke and desyncs claude's rendering.
            settings.setLocalEcho(false);

            ISettingsStore store = new InMemorySettingsStore();
            settings.save(store);
            connector.load(store);

            ITerminalListener listener = new ITerminalListener() {
                @Override
                public void setState(TerminalState state) { /* no-op */ }
                @Override
                public void setTerminalSelectionChanged() { /* no-op */ }
                @Override
                public void setTerminalTitle(String title, TerminalTitleRequestor requestor) { /* keep "Claude N" */ }
            };

            termControl = TerminalViewControlFactory.makeControl(
                    listener, content, new ITerminalConnector[] { connector }, true);
            termControl.setCharset(java.nio.charset.StandardCharsets.UTF_8);
            applyControlFont();
            termControl.setConnector(connector);
            termControl.connectTerminal();

            content.layout();
            focus();
        }

        private void applyControlFont() {
            if (termControl == null || termControl.isDisposed()) return;
            // Mirror the user's console FontData into a JFaceResources key the
            // terminal resolves for both drawing and cell measurement.
            FontData[] fd = consoleFontData();
            if (fd != null && fd.length > 0) {
                JFaceResources.getFontRegistry().put(TERMINAL_FONT_KEY, fd);
                termControl.setFont(TERMINAL_FONT_KEY);
            }
        }

        void focus() {
            if (!disposed && termControl != null && !termControl.isDisposed()) {
                termControl.setFocus();
            }
        }

        void paste() {
            if (!disposed && termControl != null && !termControl.isDisposed()) {
                termControl.paste();
            }
        }

        void updateFont() {
            if (!disposed) applyControlFont();
        }

        void updateTheme() {
            if (disposed) return;
            if (content != null && !content.isDisposed()) content.setBackground(bgColor);
        }

        void dispose() {
            disposed = true;
            if (termControl != null && !termControl.isDisposed()) {
                termControl.disposeTerminal();
            }
            termControl = null;
        }
    }
}
