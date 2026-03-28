package com.anthropic.claudecode.eclipse;

import java.io.File;
import java.net.URL;

import org.eclipse.core.runtime.FileLocator;
import org.eclipse.core.runtime.ILog;
import org.eclipse.core.runtime.Platform;
import org.eclipse.jface.preference.IPreferenceStore;
import org.eclipse.ui.plugin.AbstractUIPlugin;
import org.osgi.framework.BundleContext;

import com.anthropic.claudecode.eclipse.editor.SelectionTracker;
import com.anthropic.claudecode.eclipse.mcp.McpToolRegistry;
import com.anthropic.claudecode.eclipse.server.LockFileManager;
import com.anthropic.claudecode.eclipse.server.HttpSseServer;

public class Activator extends AbstractUIPlugin {

    private static Activator instance;
    private static final ILog LOG = Platform.getLog(Activator.class);

    private HttpSseServer httpSseServer;
    private McpToolRegistry toolRegistry;
    private SelectionTracker selectionTracker;
    private LockFileManager lockFileManager;

    @Override
    public void start(BundleContext context) throws Exception {
        super.start(context);
        instance = this;
        setupPty4jNativePath();
        LOG.info("Claude Code for Eclipse starting...");
    }

    private void setupPty4jNativePath() {
        try {
            URL nativeUrl = getBundle().getEntry("native");
            if (nativeUrl == null) {
                LOG.warn("native/ folder not found in plugin — PTY4J will fall back to classpath extraction");
                return;
            }
            String rawPath = FileLocator.toFileURL(nativeUrl).getPath();
            // Strip leading slash on Windows: /C:/path -> C:/path
            String cleanPath = rawPath.replaceFirst("^/([A-Za-z]:)", "$1");

            String os   = System.getProperty("os.name", "").toLowerCase();
            String arch = System.getProperty("os.arch", "").toLowerCase();
            String sub;
            if (os.contains("win")) {
                sub = arch.contains("aarch64") ? "win/aarch64" : "win/x86-64";
            } else if (os.contains("mac")) {
                sub = "darwin";
            } else {
                sub = "linux/" + (arch.contains("aarch64") ? "aarch64" : "x86-64");
            }

            File nativeDir = new File(cleanPath, sub);
            System.setProperty("pty4j.preferred.native.folder", nativeDir.getAbsolutePath());
            LOG.info("PTY4J native dir: " + nativeDir.getAbsolutePath());
        } catch (Exception e) {
            LOG.error("Failed to configure PTY4J native path — will attempt classpath extraction", e);
        }
    }

    @Override
    public void stop(BundleContext context) throws Exception {
        LOG.info("Claude Code for Eclipse stopping...");
        shutdown();
        instance = null;
        super.stop(context);
    }

    public void initialize() {
        if (httpSseServer != null && httpSseServer.isRunning()) {
            return;
        }

        toolRegistry = new McpToolRegistry();
        lockFileManager = new LockFileManager();
        selectionTracker = new SelectionTracker();

        IPreferenceStore prefs = getPreferenceStore();
        int portMin = prefs.getInt(Constants.PREF_PORT_MIN);
        int portMax = prefs.getInt(Constants.PREF_PORT_MAX);

        httpSseServer = new HttpSseServer(toolRegistry, portMin, portMax);
        httpSseServer.start();

        int port = httpSseServer.getPort();
        String authToken = httpSseServer.getAuthToken();
        lockFileManager.writeLockFile(port, authToken);

        if (prefs.getBoolean(Constants.PREF_TRACK_SELECTION)) {
            selectionTracker.start(httpSseServer);
        }

        LOG.info("Claude Code server started on port " + port);
    }

    public void shutdown() {
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
        shutdown();
        initialize();
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

    public static Activator getDefault() {
        return instance;
    }

    public static void log(String message) {
        LOG.info(message);
    }

    public static void logError(String message, Throwable t) {
        LOG.error(message, t);
    }
}
