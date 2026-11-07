package com.anthropic.claudecode.eclipse.ui;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
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
import org.eclipse.jface.resource.ColorRegistry;
import org.eclipse.jface.resource.FontRegistry;
import org.eclipse.jface.resource.JFaceResources;
import org.eclipse.jface.util.IPropertyChangeListener;
import org.eclipse.jface.viewers.ISelection;
import org.eclipse.jface.viewers.IStructuredSelection;
import org.eclipse.swt.SWT;
import org.eclipse.swt.dnd.Clipboard;
import org.eclipse.swt.dnd.ImageTransfer;
import org.eclipse.swt.dnd.TextTransfer;
import org.eclipse.swt.dnd.TransferData;
import org.eclipse.swt.custom.CTabFolder;
import org.eclipse.swt.custom.CTabFolder2Adapter;
import org.eclipse.swt.custom.CTabFolderEvent;
import org.eclipse.swt.custom.CTabItem;
import org.eclipse.swt.events.FocusListener;
import org.eclipse.swt.events.KeyListener;
import org.eclipse.swt.events.MouseAdapter;
import org.eclipse.swt.events.MouseEvent;
import org.eclipse.swt.events.SelectionListener;
import org.eclipse.swt.graphics.Color;
import org.eclipse.swt.graphics.FontData;
import org.eclipse.swt.graphics.Point;
import org.eclipse.swt.graphics.RGB;
import org.eclipse.swt.graphics.Rectangle;
import org.eclipse.swt.layout.FillLayout;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Event;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.Listener;
import org.eclipse.swt.widgets.ScrollBar;
import org.eclipse.swt.widgets.Scrollable;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.swt.widgets.Text;
import org.eclipse.swt.widgets.ToolBar;
import org.eclipse.swt.widgets.ToolItem;
import org.eclipse.swt.widgets.ToolTip;
import org.eclipse.ui.ISharedImages;
import org.eclipse.ui.IWorkbenchPage;
import org.eclipse.ui.PartInitException;
import org.eclipse.ui.PlatformUI;
import org.eclipse.ui.part.IShowInTarget;
import org.eclipse.ui.part.ShowInContext;
import org.eclipse.ui.part.ViewPart;
import org.osgi.framework.Bundle;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import org.eclipse.terminal.connector.ISettingsStore;
import org.eclipse.terminal.connector.ITerminalConnector;
import org.eclipse.terminal.connector.ITerminalControl;
import org.eclipse.terminal.connector.InMemorySettingsStore;
import org.eclipse.terminal.connector.TerminalConnectorExtension;
import org.eclipse.terminal.connector.TerminalState;
import org.eclipse.terminal.connector.process.ProcessSettings;
import org.eclipse.terminal.control.ITerminalListener;
import org.eclipse.terminal.control.ITerminalViewControl;
import org.eclipse.terminal.control.TerminalTitleRequestor;
import org.eclipse.terminal.internal.emulator.VT100TerminalControl;
import org.eclipse.terminal.internal.preferences.ITerminalConstants;
import org.eclipse.terminal.model.ITerminalTextDataReadOnly;
import org.eclipse.terminal.model.TerminalColor;

import com.anthropic.claudecode.eclipse.Activator;
import com.anthropic.claudecode.eclipse.Constants;
import com.anthropic.claudecode.eclipse.NativeCore;
import com.anthropic.claudecode.eclipse.SpinnerVerbs;
import com.anthropic.claudecode.eclipse.editor.UiHelper;
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

    /** Color definition IDs from plugin.xml (Colors and Fonts > Claude Code). */
    private static final String COLOR_BG_ID = "com.anthropic.claudecode.eclipse.color.terminalBackground";
    private static final String COLOR_FG_ID = "com.anthropic.claudecode.eclipse.color.terminalForeground";
    // Fallbacks matching the plugin.xml defaults, used only if the theme registry is unavailable.
    private static final RGB DEFAULT_BG = new RGB(0x12, 0x13, 0x14);
    private static final RGB DEFAULT_FG = new RGB(0xE5, 0xE5, 0xE5);

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

    /** Suffix appended to the tab title when the CLI process has ended. */
    private static final String TERMINATED_TAB_SUFFIX = " (terminated)";
    /** Notice appended to the terminal buffer when the CLI process has ended (bold red). */
    private static final String TERMINATED_TERMINAL_MSG =
            "\033[1;31m[Claude process terminated]\033[0m";

    // COLORFGBG hint for Claude's "/theme auto", derived from the terminal background luminance.
    private static final String DARK_COLORFGBG_ENV_VAL = "15;0";
    private static final String LIGHT_COLORFGBG_ENV_VAL = "0;15";
    // Perceived-luminance cutoff (of 255) below which the terminal background counts as dark.
    private static final double DARK_BG_LUMINANCE_THRESHOLD = 128;

    // Active terminal colors, read from the COLOR_BG_ID/COLOR_FG_ID theme colors
    // (user-configurable, independent of Eclipse's shared Terminal colors).
    private int bgR, bgG, bgB;
    private int fgR, fgG, fgB;
    private String colorFgBgEnvVal;

    /** How long the claudeCodeEclipse tool leaves the terminal program between two things it
     *  types into a tab: it reads keys as they come, and a line typed while it is still drawing
     *  the answer to the last one can land in that answer instead of at the prompt. */
    private static final int TYPING_PAUSE_MS = 700;
    /** How long it gives /remote-control to put its menu up before answering it. */
    private static final int MENU_PAUSE_MS = 1500;

    /** FreeBSD: the CLI runs as the Linux binary under the Linux ABI, where its main thread
     *  can go to sleep for good (see NativeCore#freebsdCliParked). Each tab watches its CLI
     *  for that and, when it happens, resumes the conversation in a new one (see
     *  TerminalSession#watchForHang). Nowhere else is anything watched. */
    private static final boolean IS_FREEBSD = Activator.isFreeBSD();
    /** FreeBSD: marks the CLI a tab starts, so the tab can find it among the IDE's processes. */
    private static final String HANG_TAB_ENV = "CLAUDE_ECLIPSE_TERMINAL_TAB";
    /** FreeBSD: how often a tab looks at its CLI. Far more often than the chat view: a frozen
     *  terminal is right in front of the user, and every key typed into it is lost. */
    private static final long HANG_CHECK_MS = 3_000;
    /** FreeBSD: checks in a row a busy CLI's main thread has to be found asleep on before it
     *  is taken to have frozen: 6 s of it at the least. */
    private static final int HANG_BUSY_CHECKS = 3;
    /** FreeBSD: the same for a CLI between turns, over one check more: 9 s at the least. */
    private static final int HANG_IDLE_CHECKS = 4;
    /** FreeBSD: how long a CLI has been busy before its main thread is looked at. */
    private static final long HANG_QUIET_MS = 45_000;
    /** FreeBSD: a restarted CLI that runs this long before freezing again got somewhere. */
    private static final long HANG_PROGRESS_MS = 180_000;
    /** FreeBSD: restarts in a row that got nowhere, after which a frozen CLI is just ended. */
    private static final int HANG_MAX_FRUITLESS_RESTARTS = 3;

    /** How long it leaves between two keys it presses one after the other: a program reading
     *  keys takes what arrives together as one key. */
    private static final int KEY_PAUSE_MS = 150;

    /** Something the tool types into a tab: the text, then Enter unless {@code enter} is off,
     *  then {@code then} if there is one, then a pause before whatever it types next. No text
     *  is a bare Enter. */
    private record Typed(String text, int pauseMs, Runnable then, boolean enter) {
        Typed(String text, int pauseMs, Runnable then) {
            this(text, pauseMs, then, true);
        }
    }

    /** The view while it exists, in whichever perspective it is open: a page finds only the
     *  views of the perspective in front. Null once it is disposed. */
    private static volatile ClaudeCliView live;

    private CTabFolder tabFolder;
    private int sessionCounter = 0;
    /** Numbers the tabs for the claudeCodeEclipse tool. Unlike {@link #sessionCounter} it is
     *  never set back, so an id is not handed out twice in one view. */
    private int toolTabSeq = 0;
    private volatile boolean viewDisposed = false;
    private boolean launching = false;
    private Color bgColor;
    private IPropertyChangeListener fontChangeListener;
    private IPropertyChangeListener colorChangeListener;
    private IPropertyChangeListener prefListener;
    private Action scrollLockAction;

    /** Shared entity resolver registry for Ctrl-click navigation, one per view. */
    private final EntitiesRegistry entitiesRegistry = new EntitiesRegistry();

    @Override
    public void createPartControl(Composite parent) {
        Display display = parent.getDisplay();
        live = this;

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
        ViewToolbarFit.install(this, container);

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

        // Double-click a tab's title to rename its session via /rename — mirrors the Claude
        // Code (GUI) view's own double-click-to-rename on its tab strip. Unlike the GUI
        // view, there is no out-of-band control channel here: renaming actually pastes
        // "/rename <title>" into the terminal's own prompt and submits it (see
        // TerminalSession#sendCommand), same as if the user had typed it. The visible tab
        // label updates on its own afterward via the EXISTING setTerminalTitle callback
        // below (the CLI already pushes its title, /rename included, over the terminal's
        // OSC title sequence) — nothing here sets the tab text directly.
        tabFolder.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseDoubleClick(MouseEvent e) {
                CTabItem item = tabFolder.getItem(new Point(e.x, e.y));
                if (item == null) return;
                TerminalSession session = (TerminalSession) item.getData();
                if (session == null) return;
                openRenameEditor(item, session);
            }
        });

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

        colorChangeListener = event -> {
            String p = event.getProperty();
            if (COLOR_BG_ID.equals(p) || COLOR_FG_ID.equals(p)) {
                display.asyncExec(() -> {
                    if (viewDisposed || tabFolder == null || tabFolder.isDisposed()) return;
                    applyTheme();
                });
            }
        };
        JFaceResources.getColorRegistry().addListener(colorChangeListener);

        // Live-apply preference changes to already-running sessions, so an edit in Preferences
        // takes effect without relaunching the terminal — mirroring the Claude Code (GUI) view.
        // Status line: the enable toggle + the per-element toggles; the refresh interval still
        // binds on next launch (it's the external CLI's re-invocation cadence). Scrollbar mode:
        // a plain SWT switch on the canvas, so it flips either way at any time.
        prefListener = event -> {
            String p = event.getProperty();
            if (p == null) return;
            boolean statusline = p.startsWith("statusline");
            boolean scrollbar = Constants.PREF_CLI_PERSISTENT_SCROLLBAR.equals(p);
            if (!statusline && !scrollbar) return;
            display.asyncExec(() -> {
                if (viewDisposed || tabFolder == null || tabFolder.isDisposed()) return;
                for (CTabItem item : tabFolder.getItems()) {
                    TerminalSession session = (TerminalSession) item.getData();
                    if (session == null) continue;
                    if (statusline) session.applyStatusPrefsLive();
                    else session.applyScrollbarMode();
                }
            });
        };
        Activator.getDefault().getPreferenceStore().addPropertyChangeListener(prefListener);
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

    /**
     * Opens an in-place editor over {@code item}'s label (double-click target) so the user
     * can type a new title without leaving the tab strip. On Enter with non-blank text,
     * sends {@code /rename <title>} to {@code session}'s own prompt and submits it — see
     * {@link TerminalSession#sendCommand}. The visible tab label is NOT set here; it
     * updates on its own once the CLI's OSC title update round-trips through the existing
     * {@code setTerminalTitle} callback, same as any other terminal title change.
     *
     * <p>CTabFolder lays out its children as tab CONTENT below the strip, clipped to that
     * area — a Text parented to the folder (or the view's own container) would be clipped
     * or have its bounds overwritten by the next layout pass. Instead this uses a borderless,
     * always-on-top Shell positioned over the tab's own screen bounds, exactly the standard
     * SWT pattern for editing something a widget doesn't natively support inline. Rather than
     * tracking the tab folder through scrolling/resizing, the editor just disposes itself on
     * any event that would invalidate its position (folder resize, tab scroll, selection
     * change, or losing focus) — it reappears on the next double-click, which is simpler and
     * safer than re-anchoring a live overlay.
     */
    /** The tab-title glyphs the CLI itself pushes over the terminal's OSC title sequence
     *  while a turn is in flight (REPL.tsx's TITLE_ANIMATION_FRAMES) or idle
     *  (TITLE_STATIC_PREFIX), each followed by a space before the actual title. */
    private static final String[] CLI_TITLE_PREFIXES = {"⠂ ", "⠐ ", "✳ "};

    /** Strips what the CLI itself may have decorated the live tab text with, so the rename
     *  editor seeds from the actual session title rather than round-tripping a stray prefix
     *  or the terminated-tab suffix back into a NEW /rename command — e.g. without this, a
     *  user who double-clicks and hits Enter without editing anything would rename the
     *  session to "✳ my-session" or "my-session (terminated)" instead of leaving it alone. */
    private static String sanitizeTabTitleForEdit(String tabText) {
        String s = tabText;
        if (s.endsWith(TERMINATED_TAB_SUFFIX)) {
            s = s.substring(0, s.length() - TERMINATED_TAB_SUFFIX.length());
        }
        for (String prefix : CLI_TITLE_PREFIXES) {
            if (s.startsWith(prefix)) { s = s.substring(prefix.length()); break; }
        }
        return s;
    }

    private void openRenameEditor(CTabItem item, TerminalSession session) {
        // A terminated (but not yet disposed) session's PTY is dead — sendCommand's paste
        // would "succeed" (the control isn't disposed) into a process that can never act on
        // it, so the rename silently does nothing. Simplest to just not offer it.
        if (session.disposed || session.terminatedShown) return;

        Rectangle bounds = item.getBounds();
        // Empty when the item is scrolled out of view (CTabFolder returns a zero rect rather
        // than null) — nowhere sane to draw the editor, so just don't.
        if (bounds.isEmpty()) return;

        Shell editorShell = new Shell(getSite().getShell(), SWT.NO_TRIM | SWT.ON_TOP);
        editorShell.setLayout(new FillLayout());
        Text editor = new Text(editorShell, SWT.BORDER);
        editor.setFont(item.getFont() != null ? item.getFont() : tabFolder.getFont());
        editor.setText(sanitizeTabTitleForEdit(item.getText()));

        Point topLeft = tabFolder.toDisplay(bounds.x, bounds.y);
        editorShell.setBounds(topLeft.x, topLeft.y, bounds.width, bounds.height);

        // Registered on tabFolder itself (not the short-lived editorShell), so they MUST be
        // removed explicitly when the editor closes — otherwise every rename leaves a dead
        // listener behind, growing unbounded over the life of the view. Declared via a
        // one-element holder so the teardown closure below can reference (and remove) the
        // exact listener instances created after it, in the single place both need them.
        final Listener[] resizeListener = new Listener[1];
        final SelectionListener[] selectionListener = new SelectionListener[1];
        final boolean[] closed = {false};
        // Tears the editor down exactly once, however it ends (Enter, Escape, focus loss,
        // or the tab strip changing under it) — callers decide separately whether to also
        // send the rename command, always AFTER this has run.
        Runnable close = () -> {
            if (closed[0]) return; closed[0] = true;
            tabFolder.removeListener(SWT.Resize, resizeListener[0]);
            tabFolder.removeSelectionListener(selectionListener[0]);
            if (!editorShell.isDisposed()) editorShell.dispose();
        };
        editor.addKeyListener(KeyListener.keyPressedAdapter(e -> {
            if (e.keyCode == SWT.CR || e.keyCode == SWT.KEYPAD_CR) {
                String title = editor.getText().trim();
                close.run();
                if (title.isEmpty()) return;   // empty → cancel, NOT a bare "/rename " (that
                                                // asks the CLI to invent a name instead)
                session.sendCommand("/rename " + title);
            } else if (e.keyCode == SWT.ESC) {
                close.run();
            }
        }));
        // Losing focus (click elsewhere, Alt-Tab, …) cancels rather than commits — matches
        // Escape, not Enter: an accidental focus loss mid-edit shouldn't fire a rename the
        // user never confirmed.
        editor.addFocusListener(FocusListener.focusLostAdapter(e -> close.run()));
        // Any of these invalidate the editor's screen position — see the method doc.
        resizeListener[0] = e -> close.run();
        selectionListener[0] = SelectionListener.widgetSelectedAdapter(e -> close.run());
        tabFolder.addListener(SWT.Resize, resizeListener[0]);
        tabFolder.addSelectionListener(selectionListener[0]);

        editorShell.open();
        editor.selectAll();
        editor.setFocus();
        // GTK can realize a brand-new Shell's window-manager focus grab a beat after this
        // call returns (a window-level race distinct from ordinary same-shell widget focus
        // moves, which are synchronous) — reassert once more after the current dispatch so
        // the editor doesn't lose focus to whatever the tab click focused a moment earlier.
        editorShell.getDisplay().asyncExec(() -> {
            if (!editor.isDisposed()) editor.setFocus();
        });

        showRenameHintOnce(editorShell.getBounds());
    }

    /** Shows the one-time "this sends /rename to the prompt" tooltip just under a just-opened
     *  rename editor's screen position — see {@link Constants#PREF_CLI_RENAME_HINT_SHOWN}.
     *  Parented to the VIEW's own shell, not the short-lived editor shell: the editor is
     *  disposed as soon as the user presses Enter (often within the tooltip's own display
     *  window on a fast first rename), and a ToolTip is a child Widget of its parent Shell —
     *  parenting it to the editor would cascade-dispose the tooltip right along with it,
     *  which is exactly the one time this hint (skipping idle detection entirely) most needs
     *  to actually be seen. Auto-dismisses like any native tooltip; never shown again after
     *  this either way. */
    private void showRenameHintOnce(Rectangle editorBounds) {
        IPreferenceStore prefs = Activator.getDefault().getPreferenceStore();
        if (prefs.getBoolean(Constants.PREF_CLI_RENAME_HINT_SHOWN)) return;
        prefs.setValue(Constants.PREF_CLI_RENAME_HINT_SHOWN, true);

        ToolTip tip = new ToolTip(getSite().getShell(), SWT.BALLOON | SWT.ICON_INFORMATION);
        tip.setText("Renaming");
        tip.setMessage("Sends \"/rename <title>\" to the prompt below — works best when "
                + "Claude is idle and the prompt is empty.");
        tip.setAutoHide(true);
        // ToolTip doesn't self-position — anchor it just under where the editor appeared
        // rather than defaulting to (0,0).
        tip.setLocation(editorBounds.x, editorBounds.y + editorBounds.height);
        tip.setVisible(true);
    }

    private void configureActionBars() {
        IToolBarManager toolBar = getViewSite().getActionBars().getToolBarManager();
        Action newSession = new Action("New Session") {
            @Override
            public void run() {
                openNewSession(null, null);
            }
        };
        newSession.setToolTipText("New Claude Session");
        newSession.setImageDescriptor(Activator.getImageDescriptor(Constants.IMG_NEW_CLI_SESSION));
        Action sessionHistory = new Action("Session history") {
            @Override
            public void run() {
                // New tab running `claude --resume`, i.e. the interactive session-history picker.
                openNewSession(null, null, "--resume");
            }
        };
        sessionHistory.setToolTipText("Session history");
        sessionHistory.setImageDescriptor(Activator.getImageDescriptor(Constants.IMG_SESSION_HISTORY));
        toolBar.add(sessionHistory);
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
        // A configured default for a newly created view instance (Preferences > Claude
        // Code; shared with ClaudeGuiView#createToolBar), not a remembered last state.
        // Every session created afterwards (openNewSession) reads THIS action's checked
        // state, not the preference directly, so setting it here alone covers them all.
        scrollLockAction.setChecked(Activator.getDefault().getPreferenceStore()
                .getBoolean(Constants.PREF_SCROLL_LOCK_DEFAULT));
        toolBar.add(scrollLockAction);
    }

    private void setThemeColors(Display display) {
        // Background/foreground come from the user-configurable Colors and Fonts entries
        // (General > Appearance > Colors and Fonts > Claude Code).
        RGB bg = themeColor(COLOR_BG_ID, DEFAULT_BG);
        RGB fg = themeColor(COLOR_FG_ID, DEFAULT_FG);
        bgR = bg.red; bgG = bg.green; bgB = bg.blue;
        fgR = fg.red; fgG = fg.green; fgB = fg.blue;
        bgColor = new Color(display, bgR, bgG, bgB);

        // COLORFGBG tells Claude's "/theme auto" whether its background is dark or light. We derive it
        // from the actual configured terminal background (the COLOR_BG_ID color), not Eclipse's
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

    /**
     * Opens a tab that resumes a saved session, in the workspace folder.
     *
     * @return whether the tab was opened
     */
    public boolean openSession(String sessionId) {
        if (tabFolder == null || tabFolder.isDisposed()) return false;
        // openNewSession lets a call inside half a second of the last one fall through
        // unopened; this one is not a second click, and must not be lost to that.
        launching = false;
        int before = tabFolder.getItemCount();
        openNewSession(null, null, "--resume", sessionId);
        return tabFolder.getItemCount() > before;
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

    // ─── The claudeCodeEclipse tool (module claudeTerminal). UI thread. ──────────────

    /** This view: the one in the perspective in front when it is open there, else wherever
     *  it is open. Null when it is not open at all. */
    private static ClaudeCliView openView() {
        IWorkbenchPage page = UiHelper.getActivePage();
        if (page != null && page.findView(VIEW_ID) instanceof ClaudeCliView view) return view;
        ClaudeCliView elsewhere = live;
        return elsewhere != null && !elsewhere.viewDisposed ? elsewhere : null;
    }

    /** Every tab of the view, in the order they stand; none when the view is closed. */
    public static List<TerminalTab> toolTabs() {
        ClaudeCliView view = openView();
        if (view == null || view.tabFolder == null || view.tabFolder.isDisposed()) return List.of();
        List<TerminalTab> tabs = new ArrayList<>();
        CTabItem front = view.tabFolder.getSelection();
        for (CTabItem item : view.tabFolder.getItems()) {
            if (item.getData() instanceof TerminalSession session) {
                tabs.add(new TerminalTab(session.toolId, item.getText(), item == front,
                        session.wasConnected, session.terminatedShown,
                        session.remoteControlLeaving ? "disconnecting" : session.remoteControlOn ? "on" : "off"));
            }
        }
        return tabs;
    }

    /**
     * Opens a tab in the workspace folder and brings it to the front, opening the view first
     * when it is closed.
     *
     * @return the new tab's id, or null when none was opened
     */
    public static String toolOpenTab() {
        IWorkbenchPage page = UiHelper.getActivePage();
        if (page == null) return null;
        ClaudeCliView view;
        try {
            view = (ClaudeCliView) page.showView(VIEW_ID);
        } catch (PartInitException e) {
            Activator.logError("Could not open the Claude Terminal view", e);
            return null;
        }
        if (view == null || view.tabFolder == null || view.tabFolder.isDisposed()) return null;
        int before = view.tabFolder.getItemCount();
        view.openNewSession(null, null);
        // openNewSession lets a call inside half a second of the last one fall through unopened.
        if (view.tabFolder.getItemCount() == before) return null;
        CTabItem opened = view.tabFolder.getSelection();
        return opened != null && opened.getData() instanceof TerminalSession session ? session.toolId : null;
    }

    /** Types {@code text} into the tab with that id and presses Enter. False when there is no
     *  such tab, or nothing running in it to take the text. */
    public static boolean toolSubmit(String tabId, String text) {
        CTabItem item = toolTab(tabId);
        return item != null && ((TerminalSession) item.getData()).submit(text);
    }

    /** Switches Remote Control in the tab with that id, by what is typed there to switch it.
     *  False when there is no such tab, or it cannot take the keys now. */
    public static boolean toolRemoteControl(String tabId, boolean on) {
        CTabItem item = toolTab(tabId);
        return item != null && ((TerminalSession) item.getData()).switchRemoteControl(on);
    }

    /** Presses {@code keys} in the tab with that id, each given as the characters a terminal
     *  sends for it. False when there is no such tab, or nothing running in it to take them. */
    public static boolean toolPress(String tabId, List<String> keys) {
        CTabItem item = toolTab(tabId);
        return item != null && ((TerminalSession) item.getData()).press(keys);
    }

    /** The last {@code lines} lines of the tab with that id, as text. Null when there is no
     *  such tab, or its text cannot be read. */
    public static String toolScreen(String tabId, int lines) {
        CTabItem item = toolTab(tabId);
        return item == null ? null : ((TerminalSession) item.getData()).screen(lines);
    }

    /** Closes the tab with that id, ending Claude in it, as its close button does. False when
     *  there is no such tab. */
    public static boolean toolClose(String tabId) {
        CTabItem item = toolTab(tabId);
        if (item == null) return false;
        ((TerminalSession) item.getData()).dispose();
        item.dispose();
        return true;
    }

    /** The tab the tool knows by that id, or null. */
    private static CTabItem toolTab(String tabId) {
        ClaudeCliView view = openView();
        if (view == null || view.tabFolder == null || view.tabFolder.isDisposed()) return null;
        for (CTabItem item : view.tabFolder.getItems()) {
            if (item.getData() instanceof TerminalSession session && session.toolId.equals(tabId)) return item;
        }
        return null;
    }

    @Override
    public void dispose() {
        viewDisposed = true;
        if (live == this) live = null;
        if (fontChangeListener != null) {
            JFaceResources.getFontRegistry().removeListener(fontChangeListener);
            fontChangeListener = null;
        }
        if (colorChangeListener != null) {
            JFaceResources.getColorRegistry().removeListener(colorChangeListener);
            colorChangeListener = null;
        }
        if (prefListener != null) {
            try { Activator.getDefault().getPreferenceStore().removePropertyChangeListener(prefListener); }
            catch (Throwable ignored) {}
            prefListener = null;
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

    /**
     * The arguments the preferences add to a session's command line, for the command the
     * session is about to run. Empty when none of them applies, which leaves the launch
     * exactly as it was before those preferences reached the Terminal.
     */
    private static List<String> preferenceArgs(String claudeCmd, List<String> userArgs, int port) {
        try {
            IPreferenceStore prefs = Activator.getDefault().getPreferenceStore();
            TerminalLaunchArgs.Options options = new TerminalLaunchArgs.Options(
                    prefs.getBoolean(Constants.PREF_TERMINAL_MCP_TOOLS),
                    prefs.getBoolean(Constants.PREF_LIVE_AUTO_MODE),
                    prefs.getBoolean(Constants.PREF_REMOTE_CONTROL_STARTUP),
                    InitialPermissionMode.current());
            if (!options.mcpTools() && !options.bypass() && !options.remoteControl()
                    && options.permissionMode().isEmpty()) {
                return List.of();
            }

            long started = System.nanoTime();
            List<String> added = TerminalLaunchArgs.build(userArgs, options,
                    CliFlagSupport.knownTo(claudeCmd, TerminalLaunchArgs.PROBED),
                    options.mcpTools() ? writeMcpConfig(port) : null);
            ClaudeCodeView.debug("[terminal-launch] " + options + " adds " + added + " (worked out in "
                    + (System.nanoTime() - started) / 1_000_000 + " ms)");
            return added;
        } catch (RuntimeException e) {
            // Whatever went wrong, a session without the extras beats no session.
            Activator.logError("Failed to work out the Claude Terminal's extra arguments", e);
            return List.of();
        }
    }

    /**
     * Writes the file that names the plug-in's server for {@code --mcp-config} and returns
     * its path, or null when it cannot be written. A file, not the JSON itself, on every
     * platform: the argument goes through the terminal connector's tokenizer and, on
     * Windows, usually an npm {@code .cmd} shim, neither of which hands a quoted JSON
     * string on intact (the reason the Claude Code view's launch does the same there —
     * see mcp_config_value in chat.rs). Named by port, so a session started before a
     * server restart keeps the file it was started with.
     */
    private static String writeMcpConfig(int port) {
        try {
            File dir = Activator.getDefault().getStateLocation().append("terminal").toFile();
            dir.mkdirs();
            File file = new File(dir, "mcp-" + port + ".json");
            Files.writeString(file.toPath(), TerminalLaunchArgs.mcpConfigJson(port), StandardCharsets.UTF_8);
            file.deleteOnExit(); // best-effort cleanup; rewritten before use on every launch
            return file.getAbsolutePath();
        } catch (IOException | RuntimeException e) {
            Activator.logError("Failed to write the Claude Terminal's MCP config file", e);
            return null;
        }
    }

    /** Whether the installed CLI's flags have been read once in this Eclipse. */
    private static volatile boolean launchChecksDone;

    /**
     * Finds out, off the UI thread, which of the flags above the installed CLI knows.
     * The answer is read from the CLI's program, which takes about a second the first
     * time; done here at startup, a session's launch — which runs on the UI thread —
     * finds it already worked out.
     */
    public static void warmLaunchChecks() {
        Thread warm = new Thread(ClaudeCliView::runLaunchChecks, "claude-terminal-flags");
        warm.setDaemon(true);
        warm.start();
    }

    /**
     * Runs {@code launch} on the UI thread once the installed CLI's flags have been read.
     * Straight away when they have been, which is every session but the first one or two
     * of an Eclipse run: a Claude Terminal left open is restored, and launches, before the
     * plug-in's startup has had the chance to do the reading ahead.
     */
    private static void afterLaunchChecks(Display display, Runnable launch) {
        if (launchChecksDone) {
            display.asyncExec(launch);
            return;
        }
        Thread wait = new Thread(() -> {
            runLaunchChecks();
            try {
                display.asyncExec(launch);
            } catch (RuntimeException displayGone) {
                // Eclipse is closing; there is nothing left to launch into.
            }
        }, "claude-terminal-flags");
        wait.setDaemon(true);
        wait.start();
    }

    /** One at a time: a second caller waits for the first and finds the work done. */
    private static synchronized void runLaunchChecks() {
        if (launchChecksDone) return;
        try {
            String claudeCmd = Activator.getDefault().getPreferenceStore().getString(Constants.PREF_CLAUDE_CMD);
            if (claudeCmd == null || claudeCmd.isBlank()) claudeCmd = Constants.DEFAULT_CLAUDE_CMD;
            String[] shellEnv = null;
            try {
                shellEnv = NativeCore.shellEnvInject();
            } catch (Throwable ignored) {
                // Same as launch(): resolve against the JVM's own PATH.
            }
            claudeCmd = IS_WINDOWS
                    ? resolveExecutableWindows(claudeCmd, pathFrom(shellEnv))
                    : resolveExecutable(claudeCmd, pathFrom(shellEnv));
            CliFlagSupport.knownTo(claudeCmd, TerminalLaunchArgs.PROBED);
        } catch (Throwable t) {
            ClaudeCodeView.debug("[terminal-launch] could not check the CLI's flags ahead of time: " + t);
        } finally {
            // Done even when it failed: the launch then asks for itself, as it would have.
            launchChecksDone = true;
        }
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

    /** Resolves a bare command to an absolute path on Windows, probing PATHEXT
     *  in each PATH directory so "claude" finds npm's "claude.cmd". Returns
     *  {@code cmd} unchanged if it is already a path, PATH is empty, or nothing
     *  matches (CreateProcess then reports the original error). Mirrors the Rust
     *  side's resolve_windows so the terminal and chat views behave the same. */
    private static String resolveExecutableWindows(String cmd, String pathValue) {
        if (cmd == null || cmd.isEmpty()) return cmd;
        // Already a path: if it exists as given, use it; if it lacks an
        // extension, still try PATHEXT next to it below.
        boolean hasSep = cmd.indexOf('\\') >= 0 || cmd.indexOf('/') >= 0;
        if (hasSep && new File(cmd).isFile()) return cmd;
        if (pathValue == null || pathValue.isEmpty()) pathValue = System.getenv("PATH");
        if (pathValue == null) pathValue = "";

        String pathext = System.getenv("PATHEXT");
        if (pathext == null || pathext.isBlank()) pathext = ".COM;.EXE;.BAT;.CMD";
        String[] exts = pathext.split(";");

        // Does cmd already end in a known executable extension? (e.g. claude.cmd)
        boolean alreadyHasExt = false;
        for (String ext : exts) {
            if (!ext.isEmpty() && cmd.toLowerCase().endsWith(ext.toLowerCase())) {
                alreadyHasExt = true;
                break;
            }
        }

        // A path with a separator: probe extensions next to it, don't walk PATH.
        if (hasSep) {
            if (alreadyHasExt && new File(cmd).isFile()) return cmd;
            for (String ext : exts) {
                if (ext.isEmpty()) continue;
                File f = new File(cmd + ext);
                if (f.isFile()) return f.getAbsolutePath();
            }
            return cmd;
        }

        // Bare name: walk PATH, probing PATHEXT in each directory.
        for (String dir : pathValue.split(java.util.regex.Pattern.quote(File.pathSeparator))) {
            if (dir.isEmpty()) continue;
            if (alreadyHasExt) {
                File f = new File(dir, cmd);
                if (f.isFile()) return f.getAbsolutePath();
            }
            for (String ext : exts) {
                if (ext.isEmpty()) continue;
                File f = new File(dir, cmd + ext);
                if (f.isFile()) return f.getAbsolutePath();
            }
        }
        return cmd;
    }

    /** A Claude Terminal color ({@link #COLOR_BG_ID}/{@link #COLOR_FG_ID}) from the
     *  workbench theme registry, falling back to {@code fallback} if unavailable. */
    private RGB themeColor(String id, RGB fallback) {
        try {
            ColorRegistry themeReg = PlatformUI.getWorkbench().getThemeManager()
                    .getCurrentTheme().getColorRegistry();
            RGB rgb = themeReg.getRGB(id);
            if (rgb != null) return rgb;
        } catch (Exception ignore) {
            // Workbench/theme unavailable — fall through.
        }
        return fallback;
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
        /** Names this tab to the claudeCodeEclipse tool. Not the routing token above, which
         *  goes to the CLI's status line and is nobody else's to see. */
        private final String toolId = "term" + (++toolTabSeq);
        /** What the tool takes this tab's Remote Control to be. Claude does not report it to
         *  the terminal it runs in, so this is how the tab was started and what the tool has
         *  switched since; a switch made by hand here goes unseen. */
        private boolean remoteControlOn = false;
        /** Set while the tool waits to answer the menu that switches Remote Control off. */
        private boolean remoteControlLeaving = false;
        /** What the tool has asked to be typed here and is not typed yet. */
        private final java.util.ArrayDeque<Typed> toType = new java.util.ArrayDeque<>();
        private boolean typing = false;
        private volatile boolean disposed = false;
        private volatile boolean wasConnected = false;
        private volatile boolean terminatedShown = false;
        private ITerminalViewControl termControl;
        private ClaudeStatusBar statusBar;
        private PreferenceStore prefStore;
        private Listener keyFilter;
        /** Counts this tab's launches. A listener only acts for the launch it came with, so
         *  the CLI a freeze replaced cannot mark the tab ended when it goes. */
        private volatile int launches = 0;
        /** FreeBSD: the thread running {@link #watchForHang}, started with the first launch. */
        private Thread hangWatch;
        /** FreeBSD: the CLI the current launch started, once found. */
        private volatile ProcessHandle cli;
        /** FreeBSD: when the current launch started. */
        private volatile long launchedAt;
        /** FreeBSD: restarts after a freeze in a row that got nowhere. */
        private int hangStreak = 0;

        String tabToken() { return tabToken; }

        /** Pushes a status snapshot into this tab's status bar (UI thread). */
        void setStatus(ClaudeStatus status) {
            if (!disposed && statusBar != null && !statusBar.isDisposed()) {
                statusBar.setStatus(status);
            }
        }

        /**
         * Live-applies a status-line preference change to this already-running session, so an
         * edit in Preferences takes effect without relaunching the terminal (mirrors the GUI
         * view). The per-element toggles are re-read on each paint, so a repaint applies them;
         * the enable toggle creates the bar on-enable and hides it on-disable. The refresh
         * <em>interval</em> is not touched here — it is the external CLI's re-invocation cadence,
         * fixed for the life of the process and rebound only on the next launch.
         */
        void applyStatusPrefsLive() {
            if (disposed || content == null || content.isDisposed()) return;
            boolean enabled = Activator.getDefault().getPreferenceStore()
                    .getBoolean(Constants.PREF_STATUSLINE_ENABLED);
            if (enabled) {
                if (statusBar == null || statusBar.isDisposed()) {
                    statusBar = new ClaudeStatusBar(content);
                    statusBar.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
                    content.layout(true, true);
                } else {
                    statusBar.redraw();   // re-reads the per-element toggles
                }
            } else if (statusBar != null && !statusBar.isDisposed()) {
                statusBar.dispose();
                statusBar = null;
                content.layout(true, true);
            }
        }

        /** Pastes text into the terminal (lands at Claude's prompt). False if not live. */
        boolean sendText(String text) {
            if (disposed || termControl == null || termControl.isDisposed()) return false;
            termControl.pasteString(text);
            focus();
            return true;
        }

        /** Like {@link #sendText}, but also submits it with a real Enter (bare CR) — unlike
         *  a plain paste, which deliberately lands at the prompt for the user to review/edit/
         *  send themselves (see sendTextToActiveSession's callers). Used for double-click tab
         *  rename ({@link #renameFromTabDoubleClick}), where the whole point is a fire-and-
         *  forget slash command, not something left sitting in the prompt. Whatever else was
         *  at the prompt when this runs gets submitted right along with it — same as a user
         *  manually pasting a slash command over unrelated text, not a new risk this adds. */
        boolean sendCommand(String text) {
            if (!sendText(text)) return false;
            termControl.sendKey('\r');
            return true;
        }

        /** {@link #sendCommand} for something that did not come from this view's own controls
         *  (the claudeCodeEclipse tool): the keyboard focus stays where the user has it, and
         *  it waits its turn behind whatever the tool typed here just before. */
        boolean submit(String text) {
            return type(new Typed(text, TYPING_PAUSE_MS, null));
        }

        /**
         * Switches Remote Control the way it is switched by hand here. On is the CLI's own
         * command. Off is an answer in the menu that command shows while Remote Control is on,
         * whose first entry — the one Enter takes — is "Disconnect this session"; until that
         * answer is given the tab is on its way off, and nothing else is typed into it.
         */
        boolean switchRemoteControl(boolean on) {
            if (remoteControlLeaving) return false;
            if (on) {
                if (!type(new Typed("/remote-control", TYPING_PAUSE_MS, null))) return false;
                remoteControlOn = true;
                return true;
            }
            if (!type(new Typed("/remote-control", MENU_PAUSE_MS, null),
                    new Typed("", TYPING_PAUSE_MS, () -> {
                        remoteControlOn = false;
                        remoteControlLeaving = false;
                    }))) {
                return false;
            }
            remoteControlLeaving = true;
            return true;
        }

        /** Presses keys for the claudeCodeEclipse tool: each as the characters a terminal
         *  sends for it, one after the other with no Enter of its own, behind whatever the
         *  tool typed here just before. */
        boolean press(List<String> keys) {
            Typed[] entries = new Typed[keys.size()];
            for (int i = 0; i < entries.length; i++) {
                entries[i] = new Typed(keys.get(i), i == entries.length - 1 ? TYPING_PAUSE_MS : KEY_PAUSE_MS, null, false);
            }
            return type(entries);
        }

        /**
         * The last {@code lines} lines the terminal holds, scrollback included, as text; the
         * blank lines below the last one with anything on it are left off. Null when it
         * cannot be read: the control keeps what it shows to itself (no API reaches it), so
         * it is taken from the control's own field, which another version may not have.
         */
        String screen(int lines) {
            if (disposed || termControl == null || termControl.isDisposed()) return null;
            try {
                java.lang.reflect.Field model = VT100TerminalControl.class.getDeclaredField("fTerminalModel");
                model.setAccessible(true);
                if (!(model.get(termControl) instanceof ITerminalTextDataReadOnly text)) return null;
                int last = text.getHeight() - 1;
                while (last >= 0 && lineOf(text, last).isEmpty()) last--;
                StringBuilder out = new StringBuilder();
                for (int i = Math.max(0, last - lines + 1); i <= last; i++) {
                    out.append(lineOf(text, i)).append('\n');
                }
                return out.toString();
            } catch (ReflectiveOperationException | RuntimeException e) {
                ClaudeCodeView.debug("[claudeCodeEclipse] the terminal's text could not be read: " + e);
                return null;
            }
        }

        private static String lineOf(ITerminalTextDataReadOnly text, int line) {
            char[] chars = text.getChars(line);
            if (chars == null) return "";
            // A cell nothing was written to holds a NUL.
            return new String(chars).replace('\0', ' ').stripTrailing();
        }

        private boolean type(Typed... entries) {
            if (disposed || termControl == null || termControl.isDisposed()
                    || termControl.getState() != TerminalState.CONNECTED) {
                return false;
            }
            for (Typed entry : entries) toType.add(entry);
            if (!typing) typeNext();
            return true;
        }

        private void typeNext() {
            Typed next = toType.poll();
            if (next == null || disposed || termControl == null || termControl.isDisposed()) {
                toType.clear();
                typing = false;
                return;
            }
            typing = true;
            if (!next.text().isEmpty()) termControl.pasteString(next.text());
            if (next.enter()) termControl.sendKey('\r');
            if (next.then() != null) next.then().run();
            Display.getCurrent().timerExec(next.pauseMs(), this::typeNext);
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
            // Defer launch so the widget has its final layout size — and, the first time,
            // until it is known which launch flags the installed CLI takes, which is read
            // from its program on another thread (see afterLaunchChecks).
            afterLaunchChecks(Display.getCurrent(), () -> {
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
            if (IS_FREEBSD) env.add(HANG_TAB_ENV + "=" + tabToken);

            // Resolve a bare command (e.g. the default "claude") to a concrete
            // file. macOS/Linux search the captured PATH; Windows must also probe
            // PATHEXT so a bare "claude" finds npm's "claude.cmd" — the Eclipse
            // terminal connector spawns via CreateProcess, which (unlike a shell)
            // does NOT consult PATHEXT and otherwise fails with "error=2".
            claudeCmd = IS_WINDOWS
                    ? resolveExecutableWindows(claudeCmd, pathFrom(shellEnv))
                    : resolveExecutable(claudeCmd, pathFrom(shellEnv));

            String image = quoteArg(claudeCmd);
            List<String> argTokens = new ArrayList<>();
            List<String> userArgs = new ArrayList<>();
            if (claudeArgs != null && !claudeArgs.isBlank()) {
                for (String arg : claudeArgs.trim().split("\\s+")) userArgs.add(arg);
            }
            for (String a : extraArgs) userArgs.add(a);
            for (String a : userArgs) argTokens.add(quoteArg(a));

            // What the preferences add: the plug-in's tools, bypass permissions mode, the
            // initial permission mode and Remote Control on startup. After the user's own
            // arguments, and only what the installed CLI is known to take — see
            // TerminalLaunchArgs.
            for (String a : preferenceArgs(claudeCmd, userArgs, port)) argTokens.add(quoteArg(a));
            // Where the claudeCodeEclipse tool takes this tab's Remote Control to start from.
            remoteControlOn = argTokens.contains(TerminalLaunchArgs.REMOTE_CONTROL)
                    || argTokens.contains(TerminalLaunchArgs.REMOTE_CONTROL_SHORT);

            // Writes the shared settings file — status line when enabled, spinner verbs
            // always — and appends --settings <file> to `argTokens`, injecting the per-tab
            // CLAUDE_TAB_TOKEN into `env` only when the status line made it in.
            configureSettingsFile(env, argTokens);

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

            final int thisLaunch = ++launches;
            ITerminalListener listener = new ITerminalListener() {
                @Override
                public void setState(TerminalState state) {
                    if (thisLaunch != launches) return;
                    if (state == TerminalState.CONNECTED) {
                        wasConnected = true;
                    } else if (state == TerminalState.CLOSED && wasConnected) {
                        // CLOSED without a prior CONNECTED is a spawn failure (bad command,
                        // IOException) — the connector already shows an error dialog for it.
                        onProcessTerminated();
                    }
                }
                @Override
                public void setTerminalSelectionChanged() { /* no-op */ }
                @Override
                public void setTerminalTitle(String title, TerminalTitleRequestor requestor) {
                    // Claude Code sets the title to the current task — show it on the tab.
                    if (title == null || title.isBlank() || thisLaunch != launches) return;
                    Display.getDefault().asyncExec(() -> {
                        if (!disposed && thisLaunch == launches && tabItem != null && !tabItem.isDisposed()) {
                            // Buffered output can still deliver a title after the process
                            // ended — keep the terminated marker in that case.
                            tabItem.setText(terminatedShown
                                    ? title + TERMINATED_TAB_SUFFIX : title);
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
            applyScrollbarMode();
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

            if (IS_FREEBSD) {
                cli = null;
                launchedAt = System.currentTimeMillis();
                if (hangWatch == null) {
                    hangWatch = new Thread(this::watchForHang, "claude-terminal-hang-watch");
                    hangWatch.setDaemon(true);
                    hangWatch.start();
                }
            }
        }

        /**
         * FreeBSD: replaces this tab's CLI if it has frozen for good, so the conversation
         * goes on by itself instead of the tab sitting there until it is closed. There is no
         * FreeBSD build of Claude Code; under the Linux ABI its main thread can go to sleep in
         * a futex wait that is never woken, and from then on it draws nothing and reads
         * nothing.
         *
         * <p>A busy CLI is looked at once it has been busy for {@link #HANG_QUIET_MS} (its own
         * record in {@code ~/.claude/sessions} says so), and then its main thread has to be
         * found asleep on {@link #HANG_BUSY_CHECKS} checks in a row. One between turns stays
         * "idle" when it freezes and never reads the next prompt, so it is looked at too, and
         * has to be found asleep on {@link #HANG_IDLE_CHECKS}. A CLI that is thinking, streaming, running a long tool or
         * waiting for the user waits in {@code epoll} and is never touched. A frozen one is
         * killed and the tab resumes its conversation, with the carry-on from the FreeBSD
         * container file as the first prompt when a turn was cut off
         * ({@link #recoverFromHang}).
         */
        private void watchForHang() {
            String mark = HANG_TAB_ENV + "=" + tabToken;
            int parkedChecks = 0;
            while (!disposed && !viewDisposed) {
                try {
                    Thread.sleep(HANG_CHECK_MS);
                } catch (InterruptedException e) {
                    return;
                }
                try {
                    if (!wasConnected || terminatedShown) {
                        parkedChecks = 0;
                        continue;
                    }
                    ProcessHandle p = cli;
                    if (p == null || !p.isAlive()) {
                        parkedChecks = 0;
                        p = ProcessHandle.current().descendants()
                                .filter(d -> Files.isRegularFile(cliSessionFile(d.pid()))
                                        && NativeCore.freebsdProcessHasEnv(d.pid(), mark))
                                .findFirst().orElse(null);
                        cli = p;
                        if (p == null) continue;
                    }
                    JsonObject record = readCliSession(p.pid());
                    String status = record != null && record.has("status")
                            ? record.get("status").getAsString() : "";
                    boolean busy = "busy".equals(status);
                    long since = record != null && record.has("statusUpdatedAt")
                            ? record.get("statusUpdatedAt").getAsLong() : 0;
                    if (record == null || (busy && System.currentTimeMillis() - since < HANG_QUIET_MS)) {
                        parkedChecks = 0;
                        continue;
                    }
                    boolean parked = NativeCore.freebsdCliParked(p.pid());
                    if (!parked) {
                        // A turn that ended is progress: whatever froze before is behind it.
                        if ("idle".equals(status)) hangStreak = 0;
                        parkedChecks = 0;
                        continue;
                    }
                    // Waiting for the user, once more than busy, since nothing in it is due
                    // to happen soon.
                    if (++parkedChecks >= (busy ? HANG_BUSY_CHECKS : HANG_IDLE_CHECKS)) {
                        parkedChecks = 0;
                        String sessionId = record.has("sessionId")
                                ? record.get("sessionId").getAsString() : "";
                        recoverFromHang(p, sessionId, busy);
                    }
                } catch (RuntimeException | UnsatisfiedLinkError e) {
                    if (Activator.getDefault().getPreferenceStore().getBoolean(Constants.PREF_DEBUG_MODE)) {
                        Activator.logError("[hang-watch] terminal check failed", e);
                    }
                }
            }
        }

        /**
         * FreeBSD: kills this tab's frozen CLI and, unless restarting has stopped helping,
         * resumes its conversation in the same tab: told to carry on when it froze in the
         * middle of a turn ({@code busy}), and just reopened when it froze between turns.
         * After {@link #HANG_MAX_FRUITLESS_RESTARTS} restarts in a row that each froze again
         * within {@link #HANG_PROGRESS_MS} without finishing a turn, or with no conversation
         * to resume, the CLI is just ended and the tab shows that as it does for any exit.
         */
        private void recoverFromHang(ProcessHandle frozen, String sessionId, boolean busy) {
            if (System.currentTimeMillis() - launchedAt >= HANG_PROGRESS_MS) hangStreak = 0;
            String carryOn = busy ? NativeCore.freebsdHangCarryOn() : "";
            boolean debug = Activator.getDefault().getPreferenceStore().getBoolean(Constants.PREF_DEBUG_MODE);
            if (sessionId.isEmpty() || (busy && (carryOn == null || carryOn.isEmpty()))
                    || hangStreak >= HANG_MAX_FRUITLESS_RESTARTS) {
                if (debug) {
                    Activator.log("[hang-watch] terminal CLI " + frozen.pid() + " frozen ("
                            + hangStreak + " restarts in a row got nowhere); ending it");
                }
                frozen.destroyForcibly();
                return;
            }
            hangStreak++;
            if (debug) {
                Activator.log("[hang-watch] terminal CLI " + frozen.pid() + " frozen "
                        + (busy ? "in a turn" : "between turns") + "; resuming " + sessionId + " in a new one");
            }
            String[] args = busy ? new String[] { "--resume", sessionId, carryOn }
                    : new String[] { "--resume", sessionId };
            // Counted before the kill, so the old launch's listener no longer acts for it.
            launches++;
            cli = null;
            frozen.destroyForcibly();
            Display display = Display.getDefault();
            if (display.isDisposed()) return;
            display.asyncExec(() -> relaunch(args));
        }

        /** FreeBSD: swaps this tab's terminal for a new one running {@code args} (UI thread). */
        private void relaunch(String[] args) {
            if (disposed || viewDisposed || content == null || content.isDisposed()) return;
            if (keyFilter != null) {
                content.getDisplay().removeFilter(SWT.KeyDown, keyFilter);
                keyFilter = null;
            }
            if (termControl != null && !termControl.isDisposed()) {
                Control root = termControl.getRootControl();
                termControl.disposeTerminal();
                if (root != null && !root.isDisposed()) root.dispose();
            }
            termControl = null;
            if (statusBar != null && !statusBar.isDisposed()) statusBar.dispose();
            statusBar = null;
            wasConnected = false;
            terminatedShown = false;
            launch(args);
        }

        /** FreeBSD: where the CLI {@code pid} keeps its own record of what it is doing. */
        private Path cliSessionFile(long pid) {
            return Paths.get(System.getProperty("user.home"), ".claude", "sessions", pid + ".json");
        }

        /** FreeBSD: the CLI {@code pid}'s record ({@link #cliSessionFile}), or null. */
        private JsonObject readCliSession(long pid) {
            try {
                String json = Files.readString(cliSessionFile(pid), StandardCharsets.UTF_8);
                return JsonParser.parseString(json).getAsJsonObject();
            } catch (IOException | RuntimeException e) {
                return null;
            }
        }

        /**
         * Writes the shared settings file and appends {@code --settings <file>} to
         * {@code argTokens}, injecting the per-tab CLAUDE_TAB_TOKEN into {@code env} when the
         * status line is part of it.
         *
         * <p>Two independent passengers ride that file: the status line, only when its
         * preference is on and its command could be resolved, and the spinner verbs, always.
         * The verbs are therefore built first — every failure path in
         * {@link #statusLineCommand} means "no status line", and none of them may take the
         * verbs down on the way out. Since the CLI merges settings per top-level key, a file
         * carrying only {@code spinnerVerbs} still leaves the user's own {@code statusLine}
         * untouched.
         *
         * <p>If the file itself can't be written, both lists are left alone and the terminal
         * launches with neither feature.
         */
        private void configureSettingsFile(List<String> env, List<String> argTokens) {
            IPreferenceStore prefs = Activator.getDefault().getPreferenceStore();
            JsonObject spinnerVerbs = SpinnerVerbs.settingsJson(prefs);

            String command = prefs.getBoolean(Constants.PREF_STATUSLINE_ENABLED)
                    ? statusLineCommand() : null;
            int refresh = prefs.getInt(Constants.PREF_STATUSLINE_REFRESH_SECONDS);
            if (refresh < 1) refresh = 1;

            File settingsFile = writeSharedSettings(command, refresh, spinnerVerbs,
                    prefs.getBoolean(Constants.PREF_THINKING_DEFAULT));
            if (settingsFile == null) return;

            // Mutate the lists only after the fallible work succeeded, so a partial
            // failure never leaves a half-configured launch.
            if (command != null) env.add("CLAUDE_TAB_TOKEN=" + tabToken);
            argTokens.add("--settings");
            argTokens.add(quoteArg(settingsFile.getAbsolutePath()));
        }

        /**
         * The {@code statusLine.command} — a bare-JVM invocation of
         * {@link StandaloneStatusForwarder} — or {@code null} when anything it needs is
         * missing (no java.home, class not found on disk, bundle/state-location error).
         * Returning null rather than throwing is what keeps the caller's other passenger,
         * the spinner verbs, on board.
         */
        private String statusLineCommand() {
            try {
                String javaHome = System.getProperty("java.home");
                if (javaHome == null || javaHome.isEmpty()) return null;
                // Forward slashes throughout: Java accepts them in paths/classpaths on every
                // OS and they keep backslashes out of the JSON / off the command line.
                String javaBin = (javaHome + (IS_WINDOWS ? "/bin/java.exe" : "/bin/java"))
                        .replace('\\', '/');

                // Derive the FQN (compile-time reference) so a rename/move needs no edit here.
                String fqn = StandaloneStatusForwarder.class.getName();

                Bundle bundle = Activator.getDefault().getBundle();
                File bundleFile = FileLocator.getBundleFile(bundle);
                File cpRoot = forwarderClasspathRoot(bundleFile, fqn);
                if (cpRoot == null) return null; // class not found on disk — skip rather than misfire
                String cp = cpRoot.getAbsolutePath().replace('\\', '/');

                return "\"" + javaBin + "\" -Xmx24m -Xms8m -Xss512k -cp \""
                        + cp + "\" " + fqn;
            } catch (Exception e) {
                Activator.logError("Status line setup failed; launching without it", e);
                return null;
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
         * location and returns it. The content depends only on {@code command},
         * {@code refreshSeconds}, {@code spinnerVerbs} and {@code thinkingByDefault} (the
         * "Enable Thinking by default" preference) — all install-/preference-scoped and
         * identical across tabs (the verbs additionally fold in the user's own settings.json,
         * which is likewise per-user) — so every launch rewrites byte-identical bytes except
         * when one of those changed, which is exactly how such a change takes effect on the
         * next launch.
         *
         * <p>A {@code null} {@code command} omits the {@code statusLine} key entirely rather
         * than writing a broken one; the CLI merges per top-level key, so the user's own
         * statusLine then stands.
         *
         * <p>No locking: {@code launch()} is confined to the SWT UI thread (the sole caller
         * defers it via {@code Display.asyncExec}), so writes never overlap. Returns
         * {@code null} on I/O failure (caller then launches without either feature).
         */
        private File writeSharedSettings(String command, int refreshSeconds, JsonObject spinnerVerbs,
                boolean thinkingByDefault) {
            try {
                File dir = Activator.getDefault().getStateLocation().append("statusline").toFile();
                dir.mkdirs();
                File file = new File(dir, "settings.json");

                JsonObject root = new JsonObject();
                if (command != null) {
                    JsonObject statusLine = new JsonObject();
                    statusLine.addProperty("type", "command");
                    statusLine.addProperty("command", command);
                    statusLine.addProperty("padding", 0);
                    statusLine.addProperty("refreshInterval", refreshSeconds);
                    root.add("statusLine", statusLine);
                }
                if (spinnerVerbs != null) root.add("spinnerVerbs", spinnerVerbs);
                TerminalLaunchArgs.applyThinking(root, thinkingByDefault);

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
         * Hands this session's terminal canvas and the current colors to
         * {@link TerminalScrollbar}, which owns how the scrollbar is presented — see
         * {@link Constants#PREF_CLI_PERSISTENT_SCROLLBAR} for what the preference buys.
         */
        void applyScrollbarMode() {
            if (termControl == null || termControl.isDisposed()) return;
            if (termControl.getControl() instanceof Scrollable canvas && !canvas.isDisposed()) {
                TerminalScrollbar.apply(canvas, Activator.getDefault().getPreferenceStore()
                        .getBoolean(Constants.PREF_CLI_PERSISTENT_SCROLLBAR), new RGB(bgR, bgG, bgB));
            }
        }

        /**
         * Scrolls the terminal canvas back to the first line.
         *
         * <p>Callers want this for its side effect on the embedded widget's bookkeeping, not
         * for the movement. The renderer holds only a window of the buffer
         * ({@code TerminalTextDataWindow}); lines outside it read back as NULs and their
         * change marks are discarded. That window is repositioned only when the canvas
         * publishes a new view rectangle, so parking it at line 0 is the way to guarantee it
         * cannot be left pointing past the end of a buffer that is about to shrink.
         *
         * <p>The synthetic event is required: {@code ScrollBar.setSelection} does not notify,
         * and it is {@code VirtualCanvas}'s own {@code SWT.Selection} listener that recomputes
         * the window. This is the same sequence a mouse-wheel notch produces.
         */
        void scrollToTop() {
            if (termControl == null || termControl.isDisposed()) return;
            if (termControl.getControl() instanceof Scrollable canvas && !canvas.isDisposed()) {
                ScrollBar vBar = canvas.getVerticalBar();
                if (vBar == null || vBar.isDisposed()) return;
                vBar.setSelection(0);
                // Fired unconditionally: the bar's selection and the canvas's scroll origin can
                // disagree (updating the bar's maximum clamps its selection without moving the
                // origin), so a selection of 0 is not proof the canvas is at the top. The
                // canvas no-ops when the delta really is zero.
                vBar.notifyListeners(SWT.Selection, new Event());
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

            // MOD1 = Ctrl on Windows/Linux, Cmd on macOS. Copy/paste combos are all handled
            // in the keyFilter Display filter below (see there); this widget listener only
            // carries the keys that don't need to pre-empt the terminal's own handler.
            canvas.addListener(SWT.KeyDown, e -> {
                boolean mod = (e.stateMask & SWT.MOD1) != 0;
                boolean shift = (e.stateMask & SWT.SHIFT) != 0;
                if (shift && !mod && e.keyCode == SWT.TAB) {        // Shift+Tab → ESC[Z
                    control.pasteString("\033[Z"); e.doit = false;  // (claude auto-mode cycle)
                }
            });

            // All copy/paste combos are intercepted here, in a Display filter, rather than in
            // the widget listener above. A filter fires before ALL widget listeners (including
            // the terminal's own TerminalKeyHandler, registered during construction); setting
            // e.type = SWT.None causes EventTable.sendEvent to exit before invoking any widget
            // listener (it checks event.type == 0 at the top of each iteration), so the terminal
            // never sees the key and can't double-act. Doing it all here also makes every
            // paste/copy combo behave identically regardless of which native terminal bindings
            // (if any) are active in this directly-embedded VT100TerminalControl.
            keyFilter = e -> {
                if (disposed || e.widget != canvas) return;
                boolean mod = (e.stateMask & SWT.MOD1) != 0;
                boolean shift = (e.stateMask & SWT.SHIFT) != 0;
                boolean alt = (e.stateMask & SWT.ALT) != 0;
                // Shift+Enter → insert a newline, mirroring the terminal's native Alt+Enter
                // (ESC+CR). Must swallow here so the terminal never sends the bare CR that
                // would submit the input to Claude. !alt leaves Alt+Enter to the terminal.
                if (shift && !mod && !alt
                        && (e.keyCode == SWT.CR || e.keyCode == SWT.KEYPAD_CR)) {
                    control.pasteString("\033\r");
                    e.type = SWT.None; // stops EventTable iteration; terminal never sees this
                    return;
                }
                // Unified paste — text or image, identical for every combo.
                if ((mod && !shift && e.keyCode == 'v')                  // Ctrl+V
                        || (mod && shift && e.keyCode == 'v')           // Ctrl+Shift+V
                        || (shift && !mod && e.keyCode == SWT.INSERT)) { // Shift+Insert
                    pasteClipboard(control);
                    e.type = SWT.None; // swallow so the terminal/CLI never double-acts
                    return;
                }
                // Copy — Ctrl+Shift+C and Ctrl+Insert always copy the selection.
                if ((mod && shift && e.keyCode == 'c')                  // Ctrl+Shift+C
                        || (mod && !shift && e.keyCode == SWT.INSERT)) { // Ctrl+Insert
                    control.copy();
                    e.type = SWT.None;
                    return;
                }
                // Ctrl+C — copy only when there is a selection, otherwise leave it as SIGINT.
                if (mod && !shift && e.keyCode == 'c') {
                    String sel = control.getSelection();
                    if (sel != null && !sel.isEmpty()) {
                        control.copy();
                        e.type = SWT.None; // stops EventTable iteration; terminal never sees this
                    }
                    return;
                }
            };
            canvas.getDisplay().addFilter(SWT.KeyDown, keyFilter);
        }

        /**
         * Paste the clipboard into the terminal. Images are pasted by asking the Claude CLI to
         * read the OS clipboard via a synthetic Ctrl+V ({@link ITerminalViewControl#sendKey}
         * sends a raw, un-bracketed 0x16 to the PTY); text is pasted directly. Shared by every
         * paste trigger (Ctrl+V / Ctrl+Shift+V / Shift+Insert / context-menu Paste) so they all
         * behave identically. The terminal's own paste() is text-only, so images must go through
         * the CLI.
         */
        private void pasteClipboard(ITerminalViewControl control) {
            if (clipboardHasImage(control.getControl().getDisplay())) {
                control.sendKey('\026'); // 0x16 = Ctrl+V → CLI reads the image off the clipboard
            } else {
                control.paste();         // text (bracketed paste)
            }
        }

        /** @return whether the system clipboard currently holds an image (without materializing it). */
        private static boolean clipboardHasImage(Display display) {
            Clipboard cb = new Clipboard(display);
            try {
                ImageTransfer it = ImageTransfer.getInstance();
                for (TransferData td : cb.getAvailableTypes()) {
                    if (it.isSupportedType(td)) return true;
                }
                return false;
            } finally {
                cb.dispose();
            }
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
                @Override public void run() { pasteClipboard(control); }
                @Override public void updateEnabled() {
                    Clipboard cb = new Clipboard(Display.getDefault());
                    try {
                        String text = (String) cb.getContents(TextTransfer.getInstance());
                        boolean hasText = text != null && !text.isEmpty();
                        setEnabled(hasText || clipboardHasImage(Display.getDefault()));
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
                    // Must precede the clear. clearTerminal() truncates the buffer to a single
                    // screen; if the viewport is sitting in scrollback at that moment, the
                    // renderer's line window is left addressing lines that no longer exist and
                    // it silently stops copying and repainting — the view goes blank until the
                    // user happens to scroll. Parking at line 0 first keeps the window valid.
                    scrollToTop();
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
            applyScrollbarMode();   // the scrollbar is tinted from those same colors
            // Update the private store's bg/fg; the control listens to its own
            // store, so this recolors the live terminal.
            if (prefStore != null) {
                setPrefColor(prefStore, TerminalColor.FOREGROUND, fgR, fgG, fgB);
                setPrefColor(prefStore, TerminalColor.BACKGROUND, bgR, bgG, bgB);
            }
        }

        /**
         * Marks this tab once the CLI process has ended: appends a notice to the
         * terminal buffer and suffixes the tab title. Called from the connector's
         * reader thread (never the UI thread) via {@link ITerminalListener#setState},
         * only for a CLOSED transition after a successful CONNECTED. Tab closing
         * also passes through CLOSED, but {@link #dispose()} sets {@code disposed}
         * first, so teardown never shows the marker.
         */
        private void onProcessTerminated() {
            if (disposed || terminatedShown) return;
            terminatedShown = true;
            remoteControlOn = false;
            remoteControlLeaving = false;
            // displayTextInTerminal feeds the emulator's screen buffer directly (not
            // the dead process), so the notice renders even after the child exited.
            if (termControl instanceof ITerminalControl control && !termControl.isDisposed()) {
                control.displayTextInTerminal(TERMINATED_TERMINAL_MSG);
            }
            Display.getDefault().asyncExec(() -> {
                if (!disposed && tabItem != null && !tabItem.isDisposed()
                        && !tabItem.getText().endsWith(TERMINATED_TAB_SUFFIX)) {
                    tabItem.setText(tabItem.getText() + TERMINATED_TAB_SUFFIX);
                }
            });
        }

        void disconnect() {
            if (termControl != null && !termControl.isDisposed()
                    && termControl.getState() != TerminalState.CLOSED) {
                termControl.disconnectTerminal();
            }
        }

        void dispose() {
            disposed = true;
            if (keyFilter != null) {
                Display display = Display.getDefault();
                if (!display.isDisposed()) display.removeFilter(SWT.KeyDown, keyFilter);
                keyFilter = null;
            }
            if (termControl != null && !termControl.isDisposed()) {
                termControl.disposeTerminal();
            }
            termControl = null;
        }
    }
}
