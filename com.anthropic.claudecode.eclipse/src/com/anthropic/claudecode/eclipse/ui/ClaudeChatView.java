package com.anthropic.claudecode.eclipse.ui;

import java.io.IOException;
import java.net.URL;

import org.eclipse.core.runtime.FileLocator;
import org.eclipse.swt.SWT;
import org.eclipse.swt.browser.Browser;
import org.eclipse.swt.browser.BrowserFunction;
import org.eclipse.swt.browser.ProgressAdapter;
import org.eclipse.swt.browser.ProgressEvent;
import org.eclipse.swt.layout.FillLayout;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Display;
import org.eclipse.ui.part.ViewPart;

import com.anthropic.claudecode.eclipse.Activator;
import com.anthropic.claudecode.eclipse.NativeCore;
import com.anthropic.claudecode.eclipse.chat.ChatProcessManager;

public class ClaudeChatView extends ViewPart {

    public static final String VIEW_ID = "com.anthropic.claudecode.eclipse.ui.ClaudeChatView";

    private Browser browser;
    private long browserHwnd = 0;
    private ChatProcessManager processManager;
    private boolean pageLoaded = false;

    // Strong references — prevents GC from unregistering the BrowserFunctions.
    @SuppressWarnings("unused")
    private BrowserFunction sendToJavaFn;
    @SuppressWarnings("unused")
    private BrowserFunction newSessionFn;

    @Override
    public void createPartControl(Composite parent) {
        parent.setLayout(new FillLayout());
        browser = new Browser(parent, SWT.NONE);

        // Extract HWND for keyboard activation.
        try {
            java.lang.reflect.Field f =
                org.eclipse.swt.widgets.Control.class.getDeclaredField("handle");
            f.setAccessible(true);
            Object val = f.get(browser);
            if (val instanceof Long)    browserHwnd = (Long) val;
            else if (val instanceof Integer) browserHwnd = (long)(int)(Integer) val;
        } catch (Exception ignored) {}

        processManager = new ChatProcessManager();
        wireProcessCallbacks();

        // Register JS→Java bridge functions — stored in fields to prevent GC.
        sendToJavaFn = new SendMessageFunction(browser, "_sendToJava");
        newSessionFn = new NewSessionFunction(browser, "_newSession");

        // Load the chat HTML once browser is ready
        browser.addProgressListener(new ProgressAdapter() {
            @Override
            public void completed(ProgressEvent event) {
                pageLoaded = true;
                // Start MCP server if not running
                if (!Activator.getDefault().isServerRunning()) {
                    Activator.getDefault().initialize();
                }
                if (Activator.getDefault().isServerRunning()) {
                    updateStatus("MCP server on port " + Activator.getDefault().getHttpSseServer().getPort());
                } else {
                    updateStatus("Ready");
                }
                // Activate WebView2 keyboard input — same issue as CLI terminal.
                for (int ms : new int[]{50, 200, 500, 1000, 1500}) {
                    Display.getCurrent().timerExec(ms, () -> activateInput());
                }
            }
        });

        loadChatPage();
    }

    private void loadChatPage() {
        try {
            URL bundleUrl = Activator.getDefault().getBundle().getEntry("resources/chat/chat.html");
            if (bundleUrl != null) {
                URL fileUrl = FileLocator.toFileURL(bundleUrl);
                browser.setUrl(fileUrl.toString());
            } else {
                browser.setText("<html><body style='background:#1e1e1e;color:#d4d4d4;padding:20px;font-family:sans-serif;'>"
                        + "<h3>Chat UI not found</h3><p>resources/chat/chat.html is missing from the bundle.</p></body></html>");
            }
        } catch (IOException e) {
            Activator.logError("Failed to load chat HTML", e);
            browser.setText("<html><body style='background:#1e1e1e;color:#d4d4d4;padding:20px;'>"
                    + "<h3>Error loading chat</h3><p>" + e.getMessage() + "</p></body></html>");
        }
    }

    private void wireProcessCallbacks() {
        Display display = Display.getDefault();

        processManager.setOnStreamStart(() -> display.asyncExec(() -> executeJS("window.onStreamStart()")));

        processManager.setOnText(text -> display.asyncExec(() -> {
            String escaped = escapeForJS(text);
            executeJS("window.onStreamText('" + escaped + "')");
        }));

        processManager.setOnStreamEnd(() -> display.asyncExec(() -> executeJS("window.onStreamEnd()")));

        processManager.setOnToolStart(name -> display.asyncExec(() ->
                executeJS("window.onToolStart('" + escapeForJS(name) + "')")));

        processManager.setOnToolEnd(name -> display.asyncExec(() ->
                executeJS("window.onToolEnd('" + escapeForJS(name) + "')")));

        processManager.setOnError(msg -> display.asyncExec(() ->
                executeJS("window.onError('" + escapeForJS(msg) + "')")));

        processManager.setOnSystem(msg -> display.asyncExec(() ->
                executeJS("window.onSystemMessage('" + escapeForJS(msg) + "')")));
    }

    private void executeJS(String script) {
        if (browser != null && !browser.isDisposed() && pageLoaded) {
            browser.execute(script);
        }
    }

    private void updateStatus(String text) {
        executeJS("window.onStatusUpdate('" + escapeForJS(text) + "')");
    }

    private String escapeForJS(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\")
                .replace("'", "\\'")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t");
    }

    @Override
    public void setFocus() {
        activateInput();
    }

    private void activateInput() {
        if (browser == null || browser.isDisposed()) return;
        browser.forceFocus();
        if (browserHwnd != 0) {
            NativeCore.browserActivateInput(browserHwnd);
        }
    }

    @Override
    public void dispose() {
        if (processManager != null) {
            processManager.stop();
        }
        super.dispose();
    }

    // --- BrowserFunction: JS calls Java to send a message ---
    private class SendMessageFunction extends BrowserFunction {
        SendMessageFunction(Browser browser, String name) {
            super(browser, name);
        }

        @Override
        public Object function(Object[] arguments) {
            if (arguments.length > 0 && arguments[0] instanceof String text) {
                Activator.log("Chat send: " + text.substring(0, Math.min(80, text.length())));
                processManager.sendMessage(text);
            }
            return null;
        }
    }

    // --- BrowserFunction: JS calls Java to reset session ---
    private class NewSessionFunction extends BrowserFunction {
        NewSessionFunction(Browser browser, String name) {
            super(browser, name);
        }

        @Override
        public Object function(Object[] arguments) {
            processManager.resetSession();
            Activator.log("Chat session reset");
            return null;
        }
    }
}
