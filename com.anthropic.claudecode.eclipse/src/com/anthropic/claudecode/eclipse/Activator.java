package com.anthropic.claudecode.eclipse;

import org.eclipse.core.runtime.ILog;
import org.eclipse.core.runtime.Platform;
import org.eclipse.jface.preference.IPreferenceStore;
import org.eclipse.ui.plugin.AbstractUIPlugin;
import org.osgi.framework.BundleContext;

import com.anthropic.claudecode.eclipse.editor.SelectionTracker;
import com.anthropic.claudecode.eclipse.mcp.McpToolRegistry;
import com.anthropic.claudecode.eclipse.server.HttpSseServer;
import com.anthropic.claudecode.eclipse.server.LockFileManager;

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
        LOG.info("Claude Code for Eclipse starting...");
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
