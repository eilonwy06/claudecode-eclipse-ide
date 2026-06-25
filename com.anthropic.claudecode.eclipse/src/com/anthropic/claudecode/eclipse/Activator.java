package com.anthropic.claudecode.eclipse;

import java.net.URL;
import java.util.Locale;

import org.eclipse.core.runtime.ILog;
import org.eclipse.core.runtime.Platform;
import org.eclipse.jface.preference.IPreferenceStore;
import org.eclipse.jface.resource.ImageDescriptor;
import org.eclipse.jface.resource.ImageRegistry;
import org.eclipse.ui.IViewPart;
import org.eclipse.ui.IWorkbench;
import org.eclipse.ui.IWorkbenchListener;
import org.eclipse.ui.IWorkbenchPage;
import org.eclipse.ui.IWorkbenchWindow;
import org.eclipse.ui.PlatformUI;
import org.eclipse.ui.plugin.AbstractUIPlugin;
import org.osgi.framework.Bundle;
import org.osgi.framework.BundleContext;

import com.anthropic.claudecode.eclipse.editor.SelectionTracker;
import com.anthropic.claudecode.eclipse.mcp.McpToolRegistry;
import com.anthropic.claudecode.eclipse.server.HttpSseServer;
import com.anthropic.claudecode.eclipse.server.LockFileManager;
import com.anthropic.claudecode.eclipse.ui.ClaudeCliView;

public class Activator extends AbstractUIPlugin {

    private static Activator instance;
    private static final ILog LOG = Platform.getLog(Activator.class);
    private static final String IMAGE_DIR_ROOT = "icons/";

    private HttpSseServer httpSseServer;
    private McpToolRegistry toolRegistry;
    private SelectionTracker selectionTracker;
    private LockFileManager lockFileManager;
    private IWorkbenchListener workbenchShutdownListener;

    @Override
    public void start(BundleContext context) throws Exception {
        super.start(context);
        instance = this;
        LOG.info("Claude Code for Eclipse starting...");
        // Note: dictation captures the mic natively (cpal) in the core, so there
        // is no WebView2 getUserMedia permission to grant here.
    }

    @Override
    public void stop(BundleContext context) throws Exception {
        LOG.info("Claude Code for Eclipse stopping...");
        shutdown();
        instance = null;
        super.stop(context);
    }

    public void initialize() {
        initializeWithConfig(0, null);
    }

    private void initializeWithConfig(int preferredPort, String authToken) {
        if (httpSseServer != null && httpSseServer.isRunning()) {
            return;
        }

        toolRegistry = new McpToolRegistry();
        lockFileManager = new LockFileManager();
        selectionTracker = new SelectionTracker();

        IPreferenceStore prefs = getPreferenceStore();

        NativeCore.setProxyOverrides(
            prefs.getString(Constants.PREF_HTTP_PROXY),
            prefs.getString(Constants.PREF_HTTPS_PROXY),
            prefs.getString(Constants.PREF_NO_PROXY)
        );

        try {
            NativeCore.setDebugMode(prefs.getBoolean(Constants.PREF_DEBUG_MODE));
        } catch (UnsatisfiedLinkError ignored) {
            // Native library doesn't have setDebugMode — older build, skip silently.
        }

        int portMin = prefs.getInt(Constants.PREF_PORT_MIN);
        int portMax = prefs.getInt(Constants.PREF_PORT_MAX);

        httpSseServer = new HttpSseServer(toolRegistry, portMin, portMax, preferredPort, authToken);
        httpSseServer.start();

        int port = httpSseServer.getPort();
        String token = httpSseServer.getAuthToken();
        lockFileManager.writeLockFile(port, token);

        if (prefs.getBoolean(Constants.PREF_TRACK_SELECTION)) {
            selectionTracker.start(httpSseServer);
        }

        LOG.info("Claude Code server started on port " + port);

        if (workbenchShutdownListener == null && PlatformUI.isWorkbenchRunning()) {
            workbenchShutdownListener = new IWorkbenchListener() {
                @Override
                public boolean preShutdown(IWorkbench workbench, boolean forced) {
                    disconnectAllTerminals(workbench);
                    return true;
                }
                @Override
                public void postShutdown(IWorkbench workbench) {}
            };
            PlatformUI.getWorkbench().addWorkbenchListener(workbenchShutdownListener);
        }
    }

    private void disconnectAllTerminals(IWorkbench workbench) {
        for (IWorkbenchWindow window : workbench.getWorkbenchWindows()) {
            for (IWorkbenchPage page : window.getPages()) {
                IViewPart view = page.findView(ClaudeCliView.VIEW_ID);
                if (view instanceof ClaudeCliView) {
                    ((ClaudeCliView) view).disconnectAllSessions();
                }
            }
        }
    }

    public void shutdown() {
        if (workbenchShutdownListener != null) {
            if (PlatformUI.isWorkbenchRunning()) {
                PlatformUI.getWorkbench().removeWorkbenchListener(workbenchShutdownListener);
            }
            workbenchShutdownListener = null;
        }
        if (selectionTracker != null) {
            selectionTracker.stop();
            selectionTracker = null;
        }
        if (lockFileManager != null) {
            lockFileManager.removeLockFile();
            lockFileManager = null;
        }
        if (httpSseServer != null) {
            httpSseServer.stop();
            httpSseServer = null;
        }
        toolRegistry = null;
    }

    public void restart() {
        int portToRestore = 0;
        String tokenToRestore = null;

        if (httpSseServer != null && httpSseServer.isRunning()) {
            portToRestore = httpSseServer.getPort();
            tokenToRestore = httpSseServer.getAuthToken();
        }

        shutdown();
        initializeWithConfig(portToRestore, tokenToRestore);
    }

    public boolean isServerRunning() {
        return httpSseServer != null && httpSseServer.isRunning();
    }

    public boolean hasConnectedClients() {
        return httpSseServer != null && httpSseServer.hasConnectedClients();
    }

    public HttpSseServer getHttpSseServer() {
        return httpSseServer;
    }

    public McpToolRegistry getToolRegistry() {
        return toolRegistry;
    }

    public SelectionTracker getSelectionTracker() {
        return selectionTracker;
    }

    public LockFileManager getLockFileManager() {
        return lockFileManager;
    }

    public static Activator getDefault() {
        return instance;
    }

    public static void log(String message) {
        LOG.info(message);
    }

    public static void logError(String message, Throwable t) {
        LOG.error(message, t);
    }

    public static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
    }

    public static boolean isMacOS() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("mac");
    }

    @Override
    protected void initializeImageRegistry(ImageRegistry registry) {
        putImage(registry, getBundle(), Constants.IMG_CLEAR_REFRESH, "clear_co.svg");
        putImage(registry, getBundle(), Constants.IMG_NEW_CLI_SESSION, "new_cli_session.svg");
        putImage(registry, getBundle(), Constants.IMG_SCROLL_LOCK, "scroll_lock.svg");
    }

    private static void putImage(ImageRegistry registry, Bundle bundle, String key, String fileName) {
        URL url = bundle.getEntry(IMAGE_DIR_ROOT + fileName);
        if (url != null) registry.put(key, ImageDescriptor.createFromURL(url));
    }

    public static ImageDescriptor getImageDescriptor(String key) {
        return getDefault().getImageRegistry().getDescriptor(key);
    }
}
