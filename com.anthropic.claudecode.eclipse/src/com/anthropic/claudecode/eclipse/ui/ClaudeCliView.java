package com.anthropic.claudecode.eclipse.ui;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

import org.eclipse.core.resources.IContainer;
import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.FileLocator;
import org.eclipse.core.runtime.IAdaptable;
import org.eclipse.jface.action.Action;
import org.eclipse.jface.action.IToolBarManager;
import org.eclipse.jface.action.MenuManager;
import org.eclipse.jface.action.Separator;
import org.eclipse.jface.preference.IPreferenceStore;
import org.eclipse.jface.preference.PreferenceConverter;
import org.eclipse.jface.preference.PreferenceStore;
import org.eclipse.jface.resource.FontRegistry;
import org.eclipse.jface.resource.JFaceResources;
import org.eclipse.jface.util.IPropertyChangeListener;
import org.eclipse.jface.viewers.ISelection;
import org.eclipse.jface.viewers.IStructuredSelection;
import org.eclipse.swt.SWT;
import org.eclipse.swt.dnd.Clipboard;
import org.eclipse.swt.dnd.TextTransfer;
import org.eclipse.swt.custom.CTabFolder;
import org.eclipse.swt.custom.CTabFolder2Adapter;
import org.eclipse.swt.custom.CTabFolderEvent;
import org.eclipse.swt.custom.CTabItem;
import org.eclipse.swt.events.SelectionListener;
import org.eclipse.swt.graphics.Color;
import org.eclipse.swt.graphics.FontData;
import org.eclipse.swt.graphics.RGB;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.Listener;
import org.eclipse.swt.widgets.ToolBar;
import org.eclipse.swt.widgets.ToolItem;
import org.eclipse.ui.ISharedImages;
import org.eclipse.ui.PlatformUI;
import org.eclipse.ui.part.IShowInTarget;
import org.eclipse.ui.part.ShowInContext;
import org.eclipse.ui.part.ViewPart;
import org.osgi.framework.Bundle;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;

import org.eclipse.terminal.connector.ISettingsStore;
import org.eclipse.terminal.connector.ITerminalConnector;
import org.eclipse.terminal.connector.InMemorySettingsStore;
import org.eclipse.terminal.connector.TerminalConnectorExtension;
import org.eclipse.terminal.connector.TerminalState;
import org.eclipse.terminal.connector.process.ProcessSettings;
import org.eclipse.terminal.control.ITerminalListener;
import org.eclipse.terminal.control.ITerminalViewControl;
import org.eclipse.terminal.control.TerminalTitleRequestor;
import org.eclipse.terminal.internal.emulator.VT100TerminalControl;
import org.eclipse.terminal.internal.preferences.ITerminalConstants;
import org.eclipse.terminal.model.TerminalColor;

import com.anthropic.claudecode.eclipse.Activator;
import com.anthropic.claudecode.eclipse.Constants;
import com.anthropic.claudecode.eclipse.NativeCore;
import com.anthropic.claudecode.eclipse.resolvers.EntitiesRegistry;
import com.anthropic.claudecode.eclipse.status.StandaloneStatusForwarder;

/**
 * Claude Terminal view: a tabbed view ("Claude 1", "Claude 2", …) where each tab
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

    private static final boolean IS_WINDOWS = Activator.isWindows();

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

    // COLORFGBG hint for Claude's "/theme auto", derived from the terminal background luminance.
    private static final String DARK_COLORFGBG_ENV_VAL = "15;0";
    private static final String LIGHT_COLORFGBG_ENV_VAL = "0;15";
    // Perceived-luminance cutoff (of 255) below which the terminal background counts as dark.
    private static final double DARK_BG_LUMINANCE_THRESHOLD = 128;

    // Active terminal colors, read from the PREF_CONSOLE_BG/FG_COLOR preferences
    // (user-configurable, independent of Eclipse's shared Terminal colors).
    private int bgR, bgG, bgB;
    private int fgR, fgG, fgB;
    private String colorFgBgEnvVal;

    private CTabFolder tabFolder;
    private int sessionCounter = 0;
    private volatile boolean viewDisposed = false;
    private boolean launching = false;
    private Color bgColor;
    private IPropertyChangeListener fontChangeListener;
    private IPropertyChangeListener themeChangeListener;
    private Action scrollLockAction;

    /** Shared entity resolver registry for Ctrl-click navigation, one per view. */
    private final EntitiesRegistry entitiesRegistry = new EntitiesRegistry();

    @Override
    public void createPartControl(Composite parent) {
        Display display = parent.getDisplay();

        setThemeColors(display);

        Composite container = new Composite(parent, SWT.NONE);
        GridLayout layout = new GridLayout(1, false);
        layout.marginWidth = 0;
        layout.marginHeight = 0;
        layout.verticalSpacing = 0;
        container.setLayout(layout);

        tabFolder = new CTabFolder(container, SWT.BORDER | SWT.CLOSE);
        tabFolder.setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, true));
        tabFolder.setTabHeight(24);

        // Bottom hint bar (shown once per workspace) advertising Ctrl+Click navigation.
        showCtrlClickHintIfNeeded(container);

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
                    if (scrollLockAction != null)
                        scrollLockAction.setChecked(session.isScrollLock());
                    getSite().getPage().activate(ClaudeCliView.this);
                    session.focus();
                }
            }
        }));

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
            String p = event.getProperty();
            if (Constants.PREF_CONSOLE_BG_COLOR.equals(p)
                    || Constants.PREF_CONSOLE_FG_COLOR.equals(p)) {
                display.asyncExec(() -> {
                    if (viewDisposed || tabFolder == null || tabFolder.isDisposed()) return;
                    applyTheme();
                });
            }
        };
        Activator.getDefault().getPreferenceStore().addPropertyChangeListener(themeChangeListener);
    }

    private void applyTheme() {
        Display display = Display.getCurrent();
        if (display == null) return;
        Color oldBg = bgColor;
        setThemeColors(display);
        for (CTabItem item : tabFolder.getItems()) {
            TerminalSession session = (TerminalSession) item.getData();
            if (session != null) session.updateTheme();
        }
        if (oldBg != null && !oldBg.isDisposed()) oldBg.dispose();
    }

    private static String getModKey() {
        return Activator.isMacOS() ? "\u2318" : "Ctrl";
    }

    /**
     * Builds the one-time hint bar at the bottom of {@code container}, advertising the
     * (otherwise invisible) Ctrl+Click (Cmd+Click on macOS) - entity navigation and enumerating the
     * recognizable entity kinds. Does nothing once the user has dismissed it in this workspace
     * ({@link Constants#PREF_CLI_CTRLCLICK_HINT_DISMISSED}).
     */
    private void showCtrlClickHintIfNeeded(Composite container) {
        if (Activator.getDefault().getPreferenceStore().getBoolean(Constants.PREF_CLI_CTRLCLICK_HINT_DISMISSED)) {
            return;
        }
        Display display = container.getDisplay();
        Color infoBg = display.getSystemColor(SWT.COLOR_INFO_BACKGROUND);
        Color infoFg = display.getSystemColor(SWT.COLOR_INFO_FOREGROUND);

        final Composite hintBanner = new Composite(container, SWT.NONE);
        hintBanner.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        hintBanner.setBackground(infoBg);
        GridLayout bannerLayout = new GridLayout(3, false);
        bannerLayout.marginHeight = 3;
        bannerLayout.marginWidth = 5;
        hintBanner.setLayout(bannerLayout);

        ISharedImages sharedImages = PlatformUI.getWorkbench().getSharedImages();

        Label message = new Label(hintBanner, SWT.WRAP);
        message.setBackground(infoBg);
        message.setForeground(infoFg);
        message.setText(buildCtrlClickHintText());
        message.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));

        ToolBar closeBar = new ToolBar(hintBanner, SWT.FLAT);
        closeBar.setBackground(infoBg);
        closeBar.setLayoutData(new GridData(SWT.END, SWT.CENTER, false, false));
        ToolItem closeItem = new ToolItem(closeBar, SWT.PUSH);
        closeItem.setImage(sharedImages.getImage(ISharedImages.IMG_ELCL_REMOVE));
        closeItem.setToolTipText("Dismiss");
        closeItem.addSelectionListener(SelectionListener.widgetSelectedAdapter(e -> {
			Activator.getDefault().getPreferenceStore()
			        .setValue(Constants.PREF_CLI_CTRLCLICK_HINT_DISMISSED, true);
			if (hintBanner != null && !hintBanner.isDisposed()) {
			    Composite parent = hintBanner.getParent();
			    hintBanner.dispose();
			    if (parent != null && !parent.isDisposed()) parent.layout(true, true);
			}
		}));
    }

    /**
     * Renders the Ctrl+Click hint sentence from the live {@link EntitiesRegistry}, so it names exactly the
     * kinds the registry can resolve (the language resolvers are gated on JDT/PyDev/CDT being installed).
     * The resolver display names are partitioned: the language ones — by convention named {@code "<Lang>
     * Identifier"} — are collapsed into a single {@code "identifier (Java, Python, …)"} phrase to avoid
     * repeating the word, while every other name is lowercased as-is (so {@code "Web Link"} stays the
     * precise "web link", not a vague "link"). The phrases are joined with an Oxford "or".
     */
    private String buildCtrlClickHintText() {
        List<String> kinds = new ArrayList<>();   // non-identifier kinds, lowercased, in registration order
        List<String> langs = new ArrayList<>();   // languages stripped from the "<Lang> Identifier" resolvers
        for (String name : entitiesRegistry.getResolverNames()) {
            if (name.endsWith(" Identifier")) {
                langs.add(name.substring(0, name.length() - " Identifier".length()));
            } else {
                kinds.add(name.toLowerCase(Locale.ROOT));
            }
        }
        if (!langs.isEmpty()) {
            kinds.add("code identifier (" + String.join(", ", langs) + ")");
        }
        return "✨ Tip: Hold " + getModKey() + " and click on a " + joinWithOr(kinds) + " in the output to open it";
    }

    /** Joins items into an Oxford-comma list ending in "or": {@code [a]}→"a", {@code [a,b]}→"a or b",
     *  {@code [a,b,c]}→"a, b, or c". */
    private static String joinWithOr(List<String> items) {
        int n = items.size();
        if (n == 0) return "";
        if (n == 1) return items.get(0);
        if (n == 2) return items.get(0) + " or " + items.get(1);
        return String.join(", ", items.subList(0, n - 1)) + ", or " + items.get(n - 1);
    }

    private void configureActionBars() {
        IToolBarManager toolBar = getViewSite().getActionBars().getToolBarManager();
        Action newSession = new Action("New Claude Terminal Session") {
            @Override
            public void run() {
                openNewSession(null, null);
            }
        };
        newSession.setToolTipText("New Claude Terminal Session");
        newSession.setImageDescriptor(Activator.getImageDescriptor(Constants.IMG_NEW_CLI_SESSION));
        toolBar.add(newSession);
        toolBar.add(new Separator());

        scrollLockAction = new Action("Scroll Lock", Action.AS_CHECK_BOX) {
            @Override
            public void run() {
                CTabItem item = tabFolder.getSelection();
                if (item != null) {
                    TerminalSession session = (TerminalSession) item.getData();
                    if (session != null) session.setScrollLock(isChecked());
                }
            }
        };
        scrollLockAction.setToolTipText("Scroll Lock");
        scrollLockAction.setImageDescriptor(Activator.getImageDescriptor(Constants.IMG_SCROLL_LOCK));
        toolBar.add(scrollLockAction);
    }

    private void setThemeColors(Display display) {
        // Background/foreground come from the user-configurable preferences.
        IPreferenceStore store = Activator.getDefault().getPreferenceStore();
        RGB bg = PreferenceConverter.getColor(store, Constants.PREF_CONSOLE_BG_COLOR);
        RGB fg = PreferenceConverter.getColor(store, Constants.PREF_CONSOLE_FG_COLOR);
        bgR = bg.red; bgG = bg.green; bgB = bg.blue;
        fgR = fg.red; fgG = fg.green; fgB = fg.blue;
        bgColor = new Color(display, bgR, bgG, bgB);

        // COLORFGBG tells Claude's "/theme auto" whether its background is dark or light. We derive it
        // from the actual configured terminal background (PREF_CONSOLE_BG_COLOR), not Eclipse's
        // General > Appearance theme: that color is what is genuinely painted behind the terminal (and
        // can differ from the SWT widget/theme color), so its luminance is the ground truth for the hint.
        // This keeps it correct for any custom color and avoids the discouraged x-friends IThemeEngine
        // (and its fragile theme-id matching, which mislabels light themes like "Classic").
        colorFgBgEnvVal = ColorUtils.luminance(bg) < DARK_BG_LUMINANCE_THRESHOLD
                ? DARK_COLORFGBG_ENV_VAL : LIGHT_COLORFGBG_ENV_VAL;
    }

    /**
     * Builds a private preference store for one terminal control so the Claude
     * CLI gets its OWN colors (custom background/foreground) independent of
     * Eclipse's shared Terminal preferences. The control reads every
     * {@link TerminalColor} from this store, so all of them must be set.
     */
    private PreferenceStore buildTerminalPrefs() {
        PreferenceStore s = new PreferenceStore();
        // Standard ANSI palette (so Claude's colored output renders correctly).
        setPrefColor(s, TerminalColor.BLACK, 0, 0, 0);
        setPrefColor(s, TerminalColor.RED, 205, 0, 0);
        setPrefColor(s, TerminalColor.GREEN, 0, 205, 0);
        setPrefColor(s, TerminalColor.YELLOW, 205, 205, 0);
        setPrefColor(s, TerminalColor.BLUE, 0, 0, 238);
        setPrefColor(s, TerminalColor.MAGENTA, 205, 0, 205);
        setPrefColor(s, TerminalColor.CYAN, 0, 205, 205);
        setPrefColor(s, TerminalColor.WHITE, 229, 229, 229);
        setPrefColor(s, TerminalColor.BRIGHT_BLACK, 127, 127, 127);
        setPrefColor(s, TerminalColor.BRIGHT_RED, 255, 0, 0);
        setPrefColor(s, TerminalColor.BRIGHT_GREEN, 0, 255, 0);
        setPrefColor(s, TerminalColor.BRIGHT_YELLOW, 255, 255, 0);
        setPrefColor(s, TerminalColor.BRIGHT_BLUE, 92, 92, 255);
        setPrefColor(s, TerminalColor.BRIGHT_MAGENTA, 255, 0, 255);
        setPrefColor(s, TerminalColor.BRIGHT_CYAN, 0, 255, 255);
        setPrefColor(s, TerminalColor.BRIGHT_WHITE, 255, 255, 255);
        // Our custom background/foreground (and a sensible selection).
        setPrefColor(s, TerminalColor.FOREGROUND, fgR, fgG, fgB);
        setPrefColor(s, TerminalColor.BACKGROUND, bgR, bgG, bgB);
        // Use the platform selection colors so it stays legible in any theme.
        RGB selBg = Display.getDefault().getSystemColor(SWT.COLOR_LIST_SELECTION).getRGB();
        RGB selFg = Display.getDefault().getSystemColor(SWT.COLOR_LIST_SELECTION_TEXT).getRGB();
        setPrefColor(s, TerminalColor.SELECTION_FOREGROUND, selFg.red, selFg.green, selFg.blue);
        setPrefColor(s, TerminalColor.SELECTION_BACKGROUND, selBg.red, selBg.green, selBg.blue);
        s.setValue(ITerminalConstants.PREF_BUFFERLINES, ITerminalConstants.DEFAULT_BUFFERLINES);
        s.setValue(ITerminalConstants.PREF_INVERT_COLORS, false);
        return s;
    }

    private static void setPrefColor(PreferenceStore s, TerminalColor c, int r, int g, int b) {
        PreferenceConverter.setValue(s, ITerminalConstants.getPrefForTerminalColor(c), new RGB(r, g, b));
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
            // GridLayout (not FillLayout) so the terminal grabs the free space and the
            // per-tab status bar can sit as a fixed-height strip beneath it.
            GridLayout contentLayout = new GridLayout(1, false);
            contentLayout.marginWidth = 0;
            contentLayout.marginHeight = 0;
            contentLayout.verticalSpacing = 0;
            content.setLayout(contentLayout);
            content.setBackground(bgColor);
            tabItem.setControl(content);

            TerminalSession session = new TerminalSession(tabItem, content, cwd, extraArgs);
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

    public void ensureAtLeastOneTab() {
        if (tabFolder == null || tabFolder.isDisposed()) return;
        if (tabFolder.getItemCount() == 0) openNewSession(null, null);
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

    /**
     * Types {@code text} into the active terminal session as if pasted, so it
     * lands at Claude's prompt. Brings the view forward and focuses the session.
     * Returns {@code false} if there is no live session to receive the text.
     */
    public boolean sendTextToActiveSession(String text) {
        if (text == null || text.isEmpty()) return false;
        if (tabFolder == null || tabFolder.isDisposed()) return false;
        CTabItem item = tabFolder.getSelection();
        if (item == null) return false;
        TerminalSession session = (TerminalSession) item.getData();
        if (session == null) return false;
        getSite().getPage().activate(this);
        return session.sendText(text);
    }

    /** Working directory of the active session, or {@code null} if none. */
    public String getActiveSessionCwd() {
        if (tabFolder == null || tabFolder.isDisposed()) return null;
        CTabItem item = tabFolder.getSelection();
        if (item == null) return null;
        TerminalSession session = (TerminalSession) item.getData();
        return session != null ? session.getCwd() : null;
    }

    public void disconnectAllSessions() {
        if (tabFolder == null || tabFolder.isDisposed()) return;
        for (CTabItem item : tabFolder.getItems()) {
            TerminalSession session = (TerminalSession) item.getData();
            if (session != null) session.disconnect();
        }
    }

    /**
     * Routes a status update to the tab whose routing token matches {@code tabToken}, using
     * the existing {@code CTabItem.setData(session)} idiom. Returns {@code true} if a live
     * session matched (token is globally unique, so at most one). Called on the UI thread by
     * {@code StatusBridge}.
     */
    public boolean deliverStatus(String tabToken, ClaudeStatus status) {
        if (tabFolder == null || tabFolder.isDisposed()) return false;
        for (CTabItem item : tabFolder.getItems()) {
            TerminalSession session = (TerminalSession) item.getData();
            if (session != null && tabToken.equals(session.tabToken())) {
                session.setStatus(status);
                return true;
            }
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

        private final CTabItem tabItem;
        private final Composite content;
        private final String customCwd;
        /**
         * Per-tab routing token for the status line. A UUID is globally unique across views
         * AND across JVM runs, so a stale forwarder from a previous launch/run can never
         * collide with a current tab. Minted once per session — there is nothing to register
         * and nothing to remove; it dies with the session.
         */
        private final String tabToken = UUID.randomUUID().toString();
        private volatile boolean disposed = false;
        private ITerminalViewControl termControl;
        private ClaudeStatusBar statusBar;
        private PreferenceStore prefStore;
        private Listener ctrlCFilter;

        String tabToken() { return tabToken; }

        /** Pushes a status snapshot into this tab's status bar (UI thread). */
        void setStatus(ClaudeStatus status) {
            if (!disposed && statusBar != null && !statusBar.isDisposed()) {
                statusBar.setStatus(status);
            }
        }

        /** Pastes text into the terminal (lands at Claude's prompt). False if not live. */
        boolean sendText(String text) {
            if (disposed || termControl == null || termControl.isDisposed()) return false;
            termControl.pasteString(text);
            focus();
            return true;
        }

        /** This session's working directory (custom cwd, else the workspace root). */
        String getCwd() {
            return (customCwd != null && !customCwd.isEmpty())
                    ? customCwd
                    : ResourcesPlugin.getWorkspace().getRoot().getLocation().toOSString();
        }

        TerminalSession(CTabItem tabItem, Composite content, String cwd, String[] extraArgs) {
            this.tabItem = tabItem;
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

            // Do not delete other instances' lock files here: the CLI pins
            // selection to CLAUDE_CODE_SSE_PORT, and live locks belong to
            // other running IDEs. writeLockFile prunes dead-PID locks.
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
            // Claude CLI auto-connects to the IDE's MCP server when CLAUDE_CODE_SSE_PORT
            // is set; it then reads the auth token + workspace folders from the lock file
            // (~/.claude/ide/<port>.lock) and connects to http://127.0.0.1:<port>/sse.
            // The auth-token/name env vars below are ignored by current CLI builds (auth
            // comes from the lock file) but kept for older releases that used CLAUDE_IDE_*.
            env.add("CLAUDE_CODE_SSE_PORT=" + port);
            env.add("CLAUDE_IDE_PORT=" + port);
            env.add("CLAUDE_IDE_AUTH_TOKEN=" + authToken);
            env.add("CLAUDE_IDE_NAME=" + Constants.IDE_NAME);
            env.add("COLORFGBG=" + colorFgBgEnvVal);
            // Tell Claude the terminal supports 24-bit color so it emits RGB
            // (the Eclipse terminal renders truecolor). Inlines xgsa's PR #26.
            env.add("COLORTERM=truecolor");

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

            // When enabled, injects the per-tab CLAUDE_TAB_TOKEN into `env` and appends
            // --settings <shared file> to `argTokens` (no-op + untouched user statusLine if off).
            configureStatusLine(env, argTokens);

            String[] environment = env.toArray(new String[0]);
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
                public void setTerminalTitle(String title, TerminalTitleRequestor requestor) {
                    // Claude Code sets the title to the current task — show it on the tab.
                    if (title == null || title.isBlank()) return;
                    Display.getDefault().asyncExec(() -> {
                        if (!disposed && tabItem != null && !tabItem.isDisposed()) {
                            tabItem.setText(title);
                        }
                    });
                }
            };

            // Use a private preference store so this terminal has its OWN
            // colors (custom bg/fg) instead of Eclipse's shared Terminal prefs.
            prefStore = buildTerminalPrefs();
            termControl = new VT100TerminalControl(
                    listener, content, new ITerminalConnector[] { connector }, prefStore);
            // content uses a GridLayout: the terminal grabs the free space; the status bar
            // (created below, if enabled) is a fixed-height strip beneath it. The layout data
            // must go on the terminal's ROOT control (the direct child of content,
            // VT100TerminalControl's fWndParent) — getControl() returns a nested canvas, so
            // setting it there leaves the real child at its tiny preferred size.
            Control termRoot = termControl.getRootControl();
            if (termRoot != null && !termRoot.isDisposed()) {
                termRoot.setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, true));
            }
            if (Activator.getDefault().getPreferenceStore()
                    .getBoolean(Constants.PREF_STATUSLINE_ENABLED)) {
                statusBar = new ClaudeStatusBar(content);
                statusBar.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
            }
            termControl.setCharset(java.nio.charset.StandardCharsets.UTF_8);
            applyControlFont();
            termControl.setConnector(connector);
            termControl.connectTerminal();
            installCopyPaste();
            OpenEntityHandler openEntityHandler = new OpenEntityHandler(
                    termControl, entitiesRegistry,
                    getViewSite().getActionBars().getStatusLineManager());
            termControl.addMouseListener(openEntityHandler);
            createPopupMenu(openEntityHandler);

            content.layout();
            if (scrollLockAction != null && scrollLockAction.isChecked())
                termControl.setScrollLock(true);
            focus();
        }

        /**
         * When the status-line preference is enabled, injects the per-tab CLAUDE_TAB_TOKEN
         * into {@code env} and appends {@code --settings <shared file>} to {@code argTokens}.
         * On any failure (no java.home, bundle/state-location error, read-only FS) it logs
         * (debug-gated) and leaves both lists untouched, so the terminal still launches —
         * just without the status line. When the preference is off it injects nothing, leaving
         * the user's own statusLine (if any) untouched.
         */
        private void configureStatusLine(List<String> env, List<String> argTokens) {
            IPreferenceStore prefs = Activator.getDefault().getPreferenceStore();
            if (!prefs.getBoolean(Constants.PREF_STATUSLINE_ENABLED)) return;
            try {
                String javaHome = System.getProperty("java.home");
                if (javaHome == null || javaHome.isEmpty()) return;
                // Forward slashes throughout: Java accepts them in paths/classpaths on every
                // OS and they keep backslashes out of the JSON / off the command line.
                String javaBin = (javaHome + (IS_WINDOWS ? "/bin/java.exe" : "/bin/java"))
                        .replace('\\', '/');

                // Derive the FQN (compile-time reference) so a rename/move needs no edit here.
                String fqn = StandaloneStatusForwarder.class.getName();

                Bundle bundle = Activator.getDefault().getBundle();
                File bundleFile = FileLocator.getBundleFile(bundle);
                File cpRoot = forwarderClasspathRoot(bundleFile, fqn);
                if (cpRoot == null) return; // class not found on disk — skip rather than misfire
                String cp = cpRoot.getAbsolutePath().replace('\\', '/');

                String command = "\"" + javaBin + "\" -Xmx24m -Xms8m -Xss512k -cp \""
                        + cp + "\" " + fqn;

                int refresh = prefs.getInt(Constants.PREF_STATUSLINE_REFRESH_SECONDS);
                if (refresh < 1) refresh = 1;

                File settingsFile = writeSharedSettings(command, refresh);
                if (settingsFile == null) return;

                // Mutate the lists only after the fallible work succeeded, so a partial
                // failure never leaves a half-configured launch.
                env.add("CLAUDE_TAB_TOKEN=" + tabToken);
                argTokens.add("--settings");
                argTokens.add(quoteArg(settingsFile.getAbsolutePath()));
            } catch (Exception e) {
                Activator.logError("Status line setup failed; launching without it", e);
            }
        }

        /**
         * Resolves the {@code -cp} entry from which a bare JVM can load {@link StandaloneStatusForwarder}.
         *
         * <p>{@link FileLocator#getBundleFile} returns the bundle <em>root</em>, which differs by
         * runtime: an installed product is a jar with classes at its root, but a PDE/dev launch is
         * the project directory whose classes live in the build-output folder ({@code bin/}), not
         * the root. We therefore probe for the actual {@code .class} file: jar → the jar; root has
         * it → the root; otherwise the dev output dir. Returns {@code null} if it's nowhere found.
         */
        private File forwarderClasspathRoot(File bundleFile, String fqn) {
            if (bundleFile == null) return null;
            if (!bundleFile.isDirectory()) return bundleFile; // jar (or other archive): classes at root
            String classRelPath = fqn.replace('.', '/') + ".class";
            if (new File(bundleFile, classRelPath).isFile()) {
                return bundleFile; // classes at the bundle root
            }
            File binDir = new File(bundleFile, "bin"); // PDE/dev output folder (build.properties output..)
            if (new File(binDir, classRelPath).isFile()) {
                return binDir;
            }
            return null;
        }

        /**
         * (Over)writes the single shared {@code statusline/settings.json} in the bundle state
         * location and returns it. The content depends only on {@code command} and
         * {@code refreshSeconds} (install-/preference-scoped, identical across tabs), so every
         * launch rewrites byte-identical bytes except when the refresh preference changed —
         * which is exactly how a refresh-interval change takes effect on the next launch.
         *
         * <p>No locking: {@code launch()} is confined to the SWT UI thread (the sole caller
         * defers it via {@code Display.asyncExec}), so writes never overlap. Returns
         * {@code null} on I/O failure (caller then launches without the status line).
         */
        private File writeSharedSettings(String command, int refreshSeconds) {
            try {
                File dir = Activator.getDefault().getStateLocation().append("statusline").toFile();
                dir.mkdirs();
                File file = new File(dir, "settings.json");

                JsonObject statusLine = new JsonObject();
                statusLine.addProperty("type", "command");
                statusLine.addProperty("command", command);
                statusLine.addProperty("padding", 0);
                statusLine.addProperty("refreshInterval", refreshSeconds);
                JsonObject root = new JsonObject();
                root.add("statusLine", statusLine);

                // disableHtmlEscaping so <, >, &, =, ' survive verbatim (paths/FQN may contain them).
                String json = new GsonBuilder().disableHtmlEscaping().create().toJson(root);
                Files.writeString(file.toPath(), json, StandardCharsets.UTF_8);
                file.deleteOnExit(); // best-effort cleanup; overwritten before use on every launch
                return file;
            } catch (IOException e) {
                Activator.logError("Failed to write statusline settings file", e);
                return null;
            }
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

        /**
         * Adds a right-click Copy/Paste menu and cross-platform copy/paste key
         * handling to the terminal canvas. The embedded control doesn't inherit
         * the stock Terminal view's edit actions, so we wire them ourselves via
         * the public copy()/paste()/selectAll() API.
         */
        private void installCopyPaste() {
            if (termControl == null || termControl.isDisposed()) return;
            final ITerminalViewControl control = termControl;
            Control canvas = control.getControl();
            if (canvas == null || canvas.isDisposed()) return;

            // MOD1 = Ctrl on Windows/Linux, Cmd on macOS.
            canvas.addListener(SWT.KeyDown, e -> {
                boolean mod = (e.stateMask & SWT.MOD1) != 0;
                boolean shift = (e.stateMask & SWT.SHIFT) != 0;
                if (mod && !shift && e.keyCode == 'v') {            // paste
                    control.paste(); e.doit = false;
                } else if (shift && e.keyCode == SWT.INSERT) {      // paste
                    control.paste(); e.doit = false;
                } else if (mod && shift && e.keyCode == 'c') {      // copy
                    control.copy(); e.doit = false;
                } else if (mod && e.keyCode == SWT.INSERT) {        // copy
                    control.copy(); e.doit = false;
                } else if (shift && !mod && e.keyCode == SWT.TAB) { // Shift+Tab → ESC[Z
                    control.pasteString("\033[Z"); e.doit = false;  // (claude auto-mode cycle)
                }
            });

            // Ctrl+C with selection must be intercepted in a Display filter, not a widget
            // listener. The terminal's TerminalKeyHandler is registered first (typed
            // addKeyListener during construction) and runs before our addListener handler,
            // so by the time we see the event the SIGINT has already been sent. A filter
            // fires before ALL widget listeners; setting e.type = SWT.None there causes
            // EventTable.sendEvent to exit before invoking any widget listener (it checks
            // event.type == 0 at the top of each iteration), so the terminal never sees it.
            ctrlCFilter = e -> {
                if (disposed || e.widget != canvas) return;
                boolean mod = (e.stateMask & SWT.MOD1) != 0;
                boolean shift = (e.stateMask & SWT.SHIFT) != 0;
                if (mod && !shift && e.keyCode == 'c') {
                    String sel = control.getSelection();
                    if (sel != null && !sel.isEmpty()) {
                        control.copy();
                        e.type = SWT.None; // stops EventTable iteration; terminal never sees this
                    }
                }
            };
            canvas.getDisplay().addFilter(SWT.KeyDown, ctrlCFilter);
        }

        private void createPopupMenu(OpenEntityHandler openEntityHandler) {
            if (termControl == null || termControl.isDisposed()) return;
            final ITerminalViewControl control = termControl;
            Control canvas = control.getControl();
            if (canvas == null || canvas.isDisposed()) return;

			String modKey = getModKey();
			MenuManager mgr = new MenuManager();
            ISharedImages sharedImages = PlatformUI.getWorkbench().getSharedImages();
            DisablingAction openAction = new DisablingAction("&Open", null, null) {
                @Override public void run() {
                    // Deliberate selection - don't strip edges.
                    openEntityHandler.openEntity(control.getSelection(), false);
                }
                @Override public void updateEnabled() {
                    String sel = control.getSelection();
                    setEnabled(sel != null && !sel.isEmpty());
                }
            };
            mgr.add(openAction);
            mgr.add(new Separator());
            DisablingAction copyAction = new DisablingAction("&Copy\t" + modKey + "+C",
                    sharedImages.getImageDescriptor(ISharedImages.IMG_TOOL_COPY),
                    sharedImages.getImageDescriptor(ISharedImages.IMG_TOOL_COPY_DISABLED)) {
                @Override public void run() { control.copy(); }
                @Override public void updateEnabled() {
                    String sel = control.getSelection();
                    setEnabled(sel != null && !sel.isEmpty());
                }
            };
            mgr.add(copyAction);
            DisablingAction pasteAction = new DisablingAction("&Paste\t" + modKey + "+V",
                    sharedImages.getImageDescriptor(ISharedImages.IMG_TOOL_PASTE),
                    sharedImages.getImageDescriptor(ISharedImages.IMG_TOOL_PASTE_DISABLED)) {
                @Override public void run() { control.paste(); }
                @Override public void updateEnabled() {
                    Clipboard cb = new Clipboard(Display.getDefault());
                    try {
                        String text = (String) cb.getContents(TextTransfer.getInstance());
                        setEnabled(text != null && !text.isEmpty());
                    } finally {
                        cb.dispose();
                    }
                }
            };
            mgr.add(pasteAction);
            mgr.addMenuListener(manager -> {
                openAction.updateEnabled();
                copyAction.updateEnabled();
                pasteAction.updateEnabled();
            });
            mgr.add(new Action("Select &All\t" + modKey + "+A") { @Override public void run() { control.selectAll(); } });
            mgr.add(new Separator());
            Action clearRefreshAction = new Action("Clear && &Refresh",
                    Activator.getImageDescriptor(Constants.IMG_CLEAR_REFRESH)) {
                @Override public void run() {
                    control.clearTerminal();
                    control.pasteString("\f"); // Ctrl+L → claude clears and redraws its UI
                }
            };
            mgr.add(clearRefreshAction);
            canvas.setMenu(mgr.createContextMenu(canvas));
        }

        void setScrollLock(boolean locked) {
            if (!disposed && termControl != null && !termControl.isDisposed())
                termControl.setScrollLock(locked);
        }

        boolean isScrollLock() {
            if (!disposed && termControl != null && !termControl.isDisposed())
                return termControl.isScrollLock();
            return false;
        }

        void updateFont() {
            if (!disposed) applyControlFont();
        }

        void updateTheme() {
            if (disposed) return;
            if (content != null && !content.isDisposed()) content.setBackground(bgColor);
            // Update the private store's bg/fg; the control listens to its own
            // store, so this recolors the live terminal.
            if (prefStore != null) {
                setPrefColor(prefStore, TerminalColor.FOREGROUND, fgR, fgG, fgB);
                setPrefColor(prefStore, TerminalColor.BACKGROUND, bgR, bgG, bgB);
            }
        }

        void disconnect() {
            if (termControl != null && !termControl.isDisposed()
                    && termControl.getState() != TerminalState.CLOSED) {
                termControl.disconnectTerminal();
            }
        }

        void dispose() {
            disposed = true;
            if (ctrlCFilter != null) {
                Display display = Display.getDefault();
                if (!display.isDisposed()) display.removeFilter(SWT.KeyDown, ctrlCFilter);
                ctrlCFilter = null;
            }
            if (termControl != null && !termControl.isDisposed()) {
                termControl.disposeTerminal();
            }
            termControl = null;
        }
    }
}
