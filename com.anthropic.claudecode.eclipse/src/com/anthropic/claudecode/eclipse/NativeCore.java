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
        loadNativeLibrary();
    }

    /**
     * Loads the native library.  Tries OSGi's Bundle-NativeCode resolution first
     * (which requires java.library.path to be set correctly), then falls back to
     * extracting the library from the bundle's classpath resources to a temp file.
     * The fallback is the reliable path on Linux/macOS where Bundle-NativeCode
     * resolution may fail when the class is initialized on a non-OSGi worker thread.
     */
    private static void loadNativeLibrary() {
        try {
            System.loadLibrary("claude_eclipse_core");
            return;
        } catch (UnsatisfiedLinkError ignored) {}

        String resourcePath = nativeResourcePath();
        if (resourcePath == null) {
            throw new UnsatisfiedLinkError(
                "Unsupported platform for native library: "
                + System.getProperty("os.name") + "/" + System.getProperty("os.arch"));
        }
        try (java.io.InputStream in = NativeCore.class.getResourceAsStream(resourcePath)) {
            if (in == null) {
                throw new UnsatisfiedLinkError(
                    "Native library not found in bundle resources: " + resourcePath);
            }
            String suffix = resourcePath.substring(resourcePath.lastIndexOf('.'));
            java.nio.file.Path tmp =
                java.nio.file.Files.createTempFile("claude_eclipse_core", suffix);
            tmp.toFile().deleteOnExit();
            java.nio.file.Files.copy(in, tmp,
                java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            System.load(tmp.toAbsolutePath().toString());
        } catch (java.io.IOException e) {
            throw new UnsatisfiedLinkError(
                "Failed to extract native library from bundle: " + e.getMessage());
        }
    }

    private static String nativeResourcePath() {
        String os   = System.getProperty("os.name",  "").toLowerCase(java.util.Locale.ROOT);
        String arch = System.getProperty("os.arch",  "").toLowerCase(java.util.Locale.ROOT);
        String dir  = nativeArchDir(arch);
        if (dir == null) return null;
        if (os.contains("win"))   return "/native/windows/" + dir + "/claude_eclipse_core.dll";
        if (os.contains("linux")) return "/native/linux/"   + dir + "/libclaude_eclipse_core.so";
        if (os.contains("mac"))   return "/native/macos/"   + dir + "/libclaude_eclipse_core.dylib";
        if (os.contains("freebsd")) return "/native/freebsd/" + dir + "/libclaude_eclipse_core.so";
        return null;
    }

    /**
     * Maps {@code os.arch} onto a bundled native directory, or null when no
     * build exists for that architecture.
     *
     * Returning null matters.  Defaulting an unrecognized architecture to
     * x86_64 hands a ppc64le or riscv64 JVM an x86-64 binary that extracts
     * successfully and then fails inside {@code System.load()}, instead of
     * reporting the platform as unsupported.
     */
    private static String nativeArchDir(String arch) {
        switch (arch) {
            case "aarch64": case "arm64":  return "aarch64";
            case "amd64":   case "x86_64": return "x86_64";
            default:                       return null;
        }
    }

    // ── Server lifecycle ──────────────────────────────────────────────────────

    /** Allocates a new Server. Returns an opaque native handle. */
    public static native long serverCreate(int portMin, int portMax);

    /** Allocates a new Server with preferred port and auth token for restart. */
    public static native long serverCreateWithConfig(int portMin, int portMax,
                                                      int preferredPort, String authToken);

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

    /** Returns the number of currently connected SSE clients. */
    public static native int serverGetClientCount(long handle);

    /**
     * Notifies the native server of a selection change. Lines are 1-based editor labels and
     * columns are 0-based offsets within their line (the CLI treats an end column of 0 as
     * "selection stops before this line"). Rust debounces 50 ms then broadcasts a
     * selection_changed notification to all SSE clients.
     */
    public static native void serverNotifySelection(long handle, String filePath, String text,
                                                    int startLine, int endLine,
                                                    int startColumn, int endColumn, boolean isEmpty);

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

    /**
     * Registers the Java object that handles Claude statusLine updates.
     * Rust calls {@link StatusCallback#onStatusUpdate} for every POST to /statusline.
     * This is a dedicated channel, independent of the MCP {@link ToolCallback} above.
     */
    public static native void registerStatusCallback(long serverHandle, StatusCallback callback);

    /** Callback invoked from a Rust worker/OS thread for every statusLine update. */
    public interface StatusCallback {
        /**
         * Deliver a Claude statusLine JSON document to the tab identified by {@code tabToken}.
         * Called from a Rust worker/OS thread (NOT the UI thread).
         *
         * @param tabToken   the per-tab routing token minted at launch
         * @param statusJson the raw statusLine JSON Claude piped to the forwarder
         */
        void onStatusUpdate(String tabToken, String statusJson);
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
                                              int mcpPort, String mcpAuthToken,
                                              String resumeId, String permMode, String effort,
                                              String model, String thinking, String imagesJson);

    /**
     * Cancels the current turn. Legacy mode kills the claude process; persistent
     * mode sends an interrupt over the control channel and the process survives.
     */
    public static native void chatCancel(long handle);

    /** Cancels the current turn and clears session state (disables -c flag). */
    public static native void chatResetSession(long handle);

    /**
     * Drops the live conversation process but KEEPS the conversation, so the next
     * send re-spawns with {@code --resume} and rebuilds context from the
     * transcript on disk. Call after editing that transcript (see
     * {@link #sessionDeleteMessage}): a live process holds its own copy of the
     * conversation, so a deleted message would otherwise stay in context.
     */
    public static native void chatRestartProcess(long handle);

    /** Frees the native memory for this chat manager. */
    public static native void chatDestroy(long handle);

    /**
     * Runs the CLI's own {@code /usage} command and returns the account-global
     * subscription limits as statusLine-schema JSON
     * ({@code {"rate_limits":{"five_hour":{"used_percentage":44},…}}}), or an
     * empty string when they can't be determined. Feed the result straight to
     * {@code ClaudeStatusStore.acceptStatusLine}.
     *
     * <p><b>Blocking</b> — spawns a whole {@code claude} process, measured at
     * <b>~7s</b>, so never call it on the UI thread. It costs the user's quota
     * nothing: the CLI answers {@code /usage} locally with no API call
     * ({@code <synthetic>} model, $0, zero tokens). It runs in an isolated temp
     * directory whose transcripts are purged afterwards, and the session is
     * stamped {@code sdk-cli}, so it reaches neither the plugin's session list
     * nor the CLI's {@code /resume} picker.
     *
     * <p>{@code workspaceRoot} is accepted for signature symmetry with the other
     * native calls but is deliberately unused — {@code /usage} is account-global,
     * and running in the workspace is exactly what would pollute its sessions.
     *
     * <p>This is the Claude GUI's own source for the Session/Weekly segments —
     * the CLI statusLine (the other producer, via {@code ClaudeStatusStore})
     * only ever fires for Terminal tabs.
     */
    public static native String fetchUsage(String claudeCmd, String workspaceRoot);

    /**
     * Renames the live conversation on this manager's persistent process via the
     * CLI's {@code rename_session} control request (writes a shared
     * {@code custom-title} event, same as VSCode). Returns false when the manager
     * has no live process on {@code sessionId} — fall back to
     * {@link #sessionRename(String, String, String, String)}.
     */
    public static native boolean chatRenameSession(long handle, String sessionId, String title);

    /**
     * Switches the permission mode of this manager's live process via the CLI's
     * {@code set_permission_mode} control request, so a mid-conversation change
     * applies without respawning. Returns false when there's no live process — the
     * next spawn passes the mode as {@code --permission-mode} anyway.
     */
    public static native boolean chatSetPermissionMode(long handle, String mode);

    /**
     * Switches this manager to persistent mode: one long-lived
     * {@code claude --input-format stream-json} process per conversation, with
     * CLI-enforced permission prompts delivered via
     * {@link ChatCallbacks#onPermissionRequest} / {@link ChatCallbacks#onQuestionRequest}.
     * Default (never called) is the legacy spawn-per-message behavior.
     */
    public static native void chatSetPersistent(long handle, boolean persistent);

    /** Streaming event callbacks fired from Rust worker threads. */
    public interface ChatCallbacks {
        void onStreamStart();
        void onText(String text);
        void onToolStart(String toolName);
        void onStreamEnd();
        void onError(String message);
        void onSystem(String message);
        void onThinking(String text);
        void onSessionId(String sessionId);
        /** Live output-token count during a turn (from the partial-message stream). */
        default void onTokens(String count) {}
        /** Rate-limit / usage info (JSON) for the usage warning banner. */
        default void onRateLimit(String json) {}
        /**
         * Persistent mode only: claude is blocked waiting for a permission
         * decision. Called on a dedicated Rust thread — may block until the user
         * decides. {@code rememberLabel} is the CLI-derived label for the middle
         * "remember this decision" option (empty = no such option). Return
         * "allow" (once), "allowRemember" (allow + echo the CLI's scoped rule),
         * "deny", or "deny&lt;message&gt;" (reject with a "do this instead" note).
         */
        default String onPermissionRequest(String toolName, String inputJson, String rememberLabel) { return "deny"; }
        /**
         * Persistent mode only: claude asked a multiple-choice question
         * (built-in AskUserQuestion). Receives the questions array JSON; returns
         * the answers as {@code [{header,question,answer}]} or {@code "[]"} if
         * dismissed. May block until the user answers.
         */
        default String onQuestionRequest(String questionsJson) { return "[]"; }
        /**
         * Session status snapshot for the GUI status bar (fired after each turn):
         * JSON with model, context %, context window, token breakdown and cost.
         * Non-blocking. Account-global rate limits are shared separately.
         */
        default void onStatus(String statusJson) {}
        /**
         * Compaction lifecycle (persistent mode; /compact or auto-compact). JSON
         * phases in order: {@code {"phase":"compacting"}}, then either
         * {@code {"phase":"failed","error":…}} or
         * {@code {"phase":"boundary","trigger":"manual|auto","preTokens":N,"postTokens":N}}
         * followed by {@code {"phase":"summary","text":…}}. Non-blocking.
         */
        default void onCompact(String json) {}
        /**
         * A tool finished: {@code {"id":…,"isError":bool,"text":…}}. {@code id} is
         * the {@code tool_use_id} that {@link #onToolStart} carried, so the GUI can
         * resolve THAT tool's dot; {@code text} is the one-line reason a failure
         * gave (empty for successes, and for failures the user themselves caused —
         * a declined tool keeps its red dot and says nothing). Non-blocking.
         */
        default void onToolEnd(String json) {}
    }

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
     * Sets the console font. Only effective on Windows.
     *
     * @param handle    console session handle
     * @param fontName  font face name (e.g. "Consolas", "Cascadia Mono")
     * @param fontSize  font height in pixels
     */
    public static native void consoleSetFont(long handle, String fontName, int fontSize);

    /**
     * Sets the console colors (background and foreground). Only effective on Windows.
     *
     * @param handle  console session handle
     * @param bgR     background red (0-255)
     * @param bgG     background green (0-255)
     * @param bgB     background blue (0-255)
     * @param fgR     foreground red (0-255)
     * @param fgG     foreground green (0-255)
     * @param fgB     foreground blue (0-255)
     */
    public static native void consoleSetColors(long handle, int bgR, int bgG, int bgB,
                                               int fgR, int fgG, int fgB);

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

    /** Generates a fresh random handshake token for one relay session. */
    public static native String bridgeGenerateToken();

    /**
     * Starts the in-process relay: binds the first two free ports in
     * {@code [portMin, portMax]} and returns {@code "portA portB"}, or {@code ""}
     * when no pair is free. Every peer must present {@code token} on its first line.
     */
    public static native String bridgeStartRelay(int portMin, int portMax, String token);
    public static native void bridgeStopRelay();
    public static native boolean bridgeRelayIsRunning();

    public static native boolean bridgeConnect(int port, String token);
    public static native void bridgeDisconnect();
    public static native boolean bridgeIsConnected();

    // ── Proxy configuration ──────────────────────────────────────────────────

    /**
     * Sets proxy overrides from Eclipse preferences.
     * Pass null or empty string to clear an override (fall back to env/shell).
     */
    public static native void setProxyOverrides(String httpProxy, String httpsProxy, String noProxy);

    // ── Session history (local) ──────────────────────────────────────────────

    /**
     * Lists past local Claude conversations for the given workspace, newest first,
     * as a JSON array of {@code {sessionId, display, timestamp}}. Reads
     * {@code ~/.claude/projects/<hash>/*.jsonl}; returns {@code "[]"} if none.
     */
    public static native String sessionList(String workspaceRoot);

    /**
     * Loads one past conversation as an ordered JSON array of render items —
     * {@code {t:user|thinking|tool|answered|text, ...}}, assistant items carrying
     * the model that turn ran on — so the GUI can reconstruct the session exactly
     * as it looked live.
     */
    public static native String sessionLoad(String workspaceRoot, String sessionId);

    /**
     * Deletes one local session jsonl (id guarded against escaping the projects
     * directory). Returns true when the file was actually removed.
     */
    public static native boolean sessionDelete(String workspaceRoot, String sessionId);

    /**
     * Ordered transcript uuids of a session's user messages, as a JSON array of
     * strings, matching the bubbles {@link #sessionLoad} renders — the ids the
     * GUI's per-message actions (rewind / fork / delete) target.
     */
    public static native String sessionMessageIds(String workspaceRoot, String sessionId);

    /**
     * Permanently removes one user message from a session transcript: the chained
     * line, plus the unchained {@code queue-operation} / {@code last-prompt}
     * copies that also hold the raw prompt. Re-links the removed line's children
     * onto its own parent (the CLI walks that chain on {@code --resume}) and
     * leaves {@code file-history-snapshot} lines alone (rewind forward-merges
     * them). Writes nothing unless the text is provably gone.
     *
     * @return {@code {"ok":true,"stripped":N}} or {@code {"error":"…"}}
     */
    public static native String sessionDeleteMessage(String workspaceRoot, String sessionId,
                                                     String messageId);

    /**
     * Renames an inactive session the CLI-native way: resumes it headless and sends
     * the {@code rename_session} control request, which appends a {@code custom-title}
     * event to the session's own jsonl — so the title is shared with {@code /resume}
     * and every other Claude Code client. No model turn, no cost. Blocks up to ~15s;
     * call from a background thread.
     *
     * @return true when the CLI confirmed the rename
     */
    public static native boolean sessionRename(String claudeCmd, String workspaceRoot,
            String sessionId, String title);

    /**
     * Returns the login-shell environment to inject into a spawned terminal
     * process, as {@code KEY=VALUE} entries (e.g. {@code PATH=...},
     * {@code HTTPS_PROXY=...}).
     *
     * <p>GUI-launched Eclipse on macOS/Linux inherits a sparse environment, so
     * without these entries {@code claude} installed via nvm/asdf/Homebrew/
     * {@code npm -g} is invisible on PATH and shell-rc proxy vars are missing.
     * On Windows this returns an empty array (the full user environment is
     * already inherited). May return {@code null} if the native call fails.
     */
    public static native String[] shellEnvInject();

    /**
     * Enables or disables debug logging in native code.
     */
    public static native void setDebugMode(boolean enabled);
}
