package com.anthropic.claudecode.eclipse;

/**
 * JNI bridge to the Rust native core library (claude_eclipse_core).
 *
 * The library handles:
 *   - HTTP + SSE server (tokio + axum)
 *   - MCP / JSON-RPC 2.0 protocol
 *   - Lock-file management
 *   - Chat process manager
 *
 * Every heavy computation lives in Rust.  Java is responsible only for:
 *   - Eclipse API calls (editors, workspace, resources, SWT)
 *   - Loading the native library and wiring up callbacks
 */
public final class NativeCore {

    private NativeCore() {}

    static {
        System.loadLibrary("claude_eclipse_core");
    }

    // ── Server lifecycle ──────────────────────────────────────────────────────

    /** Allocates a new Server. Returns an opaque native handle. */
    public static native long serverCreate(int portMin, int portMax);

    /** Starts the server. Returns the bound port, or 0 on failure. */
    public static native int serverStart(long handle);

    /**
     * Stops the server and frees its native memory.
     * The handle MUST NOT be used after this call.
     */
    public static native void serverStop(long handle);

    /** Returns the port the server is listening on (0 if not started). */
    public static native int serverGetPort(long handle);

    /** Returns the auth token for this server instance. */
    public static native String serverGetAuthToken(long handle);

    /** Broadcasts a JSON string to every connected SSE client. */
    public static native void serverBroadcast(long handle, String json);

    /** Returns true if the server is running. */
    public static native boolean serverIsRunning(long handle);

    /** Returns the number of currently connected SSE clients. */
    public static native int serverGetClientCount(long handle);

    /**
     * Notifies the native server of a selection change.
     * Rust debounces 50 ms then broadcasts notifications/selectionChanged to all SSE clients.
     */
    public static native void serverNotifySelection(long handle, String filePath, String text,
                                                    int startLine, int endLine, boolean isEmpty);

    /**
     * Registers the Java object that handles MCP tool calls.
     * Rust will call {@link ToolCallback#executeEclipseTool} on every tools/call request.
     */
    public static native void registerToolCallback(long serverHandle, ToolCallback callback);

    /** Callback invoked from a Rust worker thread for every MCP tool call. */
    public interface ToolCallback {
        /**
         * Execute an Eclipse MCP tool and return the JSON result.
         *
         * @param toolName  e.g. "openFile"
         * @param argsJson  JSON object string of the tool arguments
         * @return          JSON string matching McpToolResult.toJson()
         */
        String executeEclipseTool(String toolName, String argsJson);
    }

    // ── Lock file ─────────────────────────────────────────────────────────────

    /**
     * Writes ~/.claude/ide/{port}.lock.
     *
     * @param projectPathsJson  JSON array string of workspace/project paths
     */
    public static native void lockFileWrite(int port, String authToken,
                                            String workspaceRoot, String projectPathsJson);

    /** Removes the lock file created by the most recent {@link #lockFileWrite} call. */
    public static native void lockFileRemove();

    // ── Chat process manager ──────────────────────────────────────────────────

    /** Creates a new ChatManager. Returns an opaque native handle. */
    public static native long chatCreate();

    /**
     * Registers streaming event callbacks on this chat manager.
     * Must be called before the first {@link #chatSendMessage}.
     */
    public static native void chatRegisterCallbacks(long handle, ChatCallbacks callbacks);

    /**
     * Sends a user message.  Returns immediately; events arrive via {@link ChatCallbacks}.
     *
     * @param claudeCmd      path / name of the claude executable
     * @param workspaceRoot  working directory for the process
     * @param mcpPort        port of the local MCP server
     * @param mcpAuthToken   auth token for the local MCP server
     */
    public static native void chatSendMessage(long handle, String message,
                                              String claudeCmd, String workspaceRoot,
                                              int mcpPort, String mcpAuthToken);

    /** Cancels the current turn (kills the claude process). */
    public static native void chatCancel(long handle);

    /** Cancels the current turn and clears session state (disables -c flag). */
    public static native void chatResetSession(long handle);

    /** Frees the native memory for this chat manager. */
    public static native void chatDestroy(long handle);

    /** Streaming event callbacks fired from Rust worker threads. */
    public interface ChatCallbacks {
        void onStreamStart();
        void onText(String text);
        void onToolStart(String toolName);
        void onStreamEnd();
        void onError(String message);
        void onSystem(String message);
    }

    // ── PTY process manager ───────────────────────────────────────────────────

    /** Creates a new PtySession. Returns an opaque native handle. */
    public static native long ptyCreate();

    /**
     * Registers PTY event callbacks. Must be called before {@link #ptyStart}.
     */
    public static native void ptyRegisterCallbacks(long handle, PtyCallbacks callbacks);

    /**
     * Spawns the PTY process.
     *
     * @param cmd          executable (e.g. "cmd.exe" on Windows, "claude" on Unix)
     * @param argsJson     JSON array of arguments, e.g. {@code ["/c","claude"]}
     * @param extraEnvJson JSON array of [key,value] pairs
     * @param cwd          working directory
     * @param cols         initial terminal width
     * @param rows         initial terminal height
     */
    public static native void ptyStart(long handle, String cmd, String argsJson,
                                       String extraEnvJson, String cwd,
                                       int cols, int rows);

    /** Writes raw keyboard input to the PTY stdin. */
    public static native void ptyWriteInput(long handle, String input);

    /** Notifies the PTY of a terminal resize. */
    public static native void ptyResize(long handle, int cols, int rows);

    /**
     * Kills the process, closes the PTY, and frees native memory.
     * The handle MUST NOT be used after this call.
     */
    public static native void ptyDestroy(long handle);

    // ── Embedded console (replaces PTY + xterm.js for the CLI view) ─────────

    /**
     * Creates a child process with its own console window (initially hidden).
     * Call {@link #consoleEmbed} to find and reparent the console into an
     * SWT Composite.
     *
     * @return opaque native handle, or 0 on failure
     */
    public static native long consoleCreate(String cmd, String argsJson,
                                            String extraEnvJson, String cwd);

    /**
     * Tries to find the console window and embed it in {@code parentHwnd}.
     * Returns {@code true} if the console is now embedded, {@code false} if
     * the console window hasn't appeared yet (caller should retry).
     */
    public static native boolean consoleEmbed(long handle, long parentHwnd,
                                              int width, int height);

    /** Resizes the embedded console window to fill its parent. */
    public static native void consoleResize(long handle, int width, int height);

    /** Gives Win32 keyboard focus to the embedded console window. */
    public static native void consoleFocus(long handle);

    /** Returns true if the console HWND currently has Win32 keyboard focus. */
    public static native boolean consoleIsFocused(long handle);

    /**
     * Posts a Win32 message to the console HWND.
     * Used to forward keyboard events (WM_CHAR, WM_KEYDOWN, WM_KEYUP)
     * when the console doesn't have real keyboard focus.
     */
    public static native void consolePostMessage(long handle, int msg, long wParam, long lParam);

    /**
     * Terminates the process and frees native memory.
     * The handle MUST NOT be used after this call.
     */
    public static native void consoleDestroy(long handle);

    // ── Browser input activation (Chat view only) ────────────────────────────

    /**
     * Activates WebView2's keyboard pipeline by finding the deepest child
     * window of the given HWND and calling SetFocus + PostMessage(WM_KEYDOWN).
     * No-op on non-Windows platforms.
     */
    public static native void browserActivateInput(long hwnd);

    /** Callbacks fired from Rust reader thread. */
    public interface PtyCallbacks {
        /** JSON-encoded screen state from the vt100 parser in Rust. */
        void onScreenUpdate(String screenJson);
        /** Called when the child process has exited. */
        void onExit();
    }
}
