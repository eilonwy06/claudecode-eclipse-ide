package com.anthropic.claudecode.eclipse.server;

import com.anthropic.claudecode.eclipse.Activator;
import com.anthropic.claudecode.eclipse.NativeCore;
import com.anthropic.claudecode.eclipse.NativeToolBridge;
import com.anthropic.claudecode.eclipse.StatusBridge;
import com.anthropic.claudecode.eclipse.mcp.McpToolRegistry;

/**
 * Thin Java wrapper over the Rust HTTP+SSE server.
 *
 * All networking, SSE management, and MCP/JSON-RPC handling live in the
 * native library (claude_eclipse_core).  This class exposes the same
 * public API as the original Java implementation so the rest of the plugin
 * (views, handlers, SelectionTracker) requires no changes.
 */
public class HttpSseServer {

    private final long handle;
    private volatile boolean running;

    public HttpSseServer(McpToolRegistry toolRegistry, int portMin, int portMax) {
        this(toolRegistry, portMin, portMax, 0, null);
    }

    public HttpSseServer(McpToolRegistry toolRegistry, int portMin, int portMax,
                         int preferredPort, String authToken) {
        if (preferredPort > 0 && authToken != null) {
            this.handle = NativeCore.serverCreateWithConfig(portMin, portMax, preferredPort, authToken);
        } else {
            this.handle = NativeCore.serverCreate(portMin, portMax);
        }

        NativeCore.registerToolCallback(handle, new NativeToolBridge(toolRegistry));
        // Dedicated status channel, independent of the MCP tool callback above. Optional:
        // a not-yet-rebuilt native lib (e.g. another platform pending its build host) lacks
        // this export, so tolerate UnsatisfiedLinkError — the server still runs, just without
        // the status line.
        try {
            // Wrap StatusBridge (unchanged) so the same statusLine data also
            // feeds ClaudeStatusStore, letting the Claude GUI show the exact same
            // account-global usage limits. The bridge still runs identically —
            // the CLI status bar is unaffected.
            final StatusBridge bridge = new StatusBridge();
            NativeCore.registerStatusCallback(handle, (tabToken, statusJson) -> {
                com.anthropic.claudecode.eclipse.ui.ClaudeStatusStore.acceptStatusLine(statusJson);
                bridge.onStatusUpdate(tabToken, statusJson);
            });
        } catch (UnsatisfiedLinkError e) {
            Activator.log("Native library has no registerStatusCallback; status line disabled.");
        }
    }

    public void start() {
        int port = NativeCore.serverStart(handle);
        if (port > 0) {
            running = true;
            Activator.log("HTTP+SSE server (Rust) listening on 127.0.0.1:" + port);
        }
    }

    public void stop() {
        if (!running) return;
        running = false;
        // serverStop both stops and frees the native Server — handle is invalid after this.
        NativeCore.serverStop(handle);
    }

    public void broadcast(String message) {
        if (running) NativeCore.serverBroadcast(handle, message);
    }

    public void notifySelection(String filePath, String text, int startLine, int endLine,
                                int startColumn, int endColumn, boolean isEmpty) {
        if (running) NativeCore.serverNotifySelection(handle, filePath, text,
                startLine, endLine, startColumn, endColumn, isEmpty);
    }

    public boolean isRunning() {
        return running;
    }

    public boolean hasConnectedClients() {
        return running && NativeCore.serverGetClientCount(handle) > 0;
    }

    public int getPort() {
        return running ? NativeCore.serverGetPort(handle) : 0;
    }

    public String getAuthToken() {
        return NativeCore.serverGetAuthToken(handle);
    }

    public int getClientCount() {
        return running ? NativeCore.serverGetClientCount(handle) : 0;
    }
}
