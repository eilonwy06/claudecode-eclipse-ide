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
     * Loads the native library.  Tries OSGi's Bundle-NativeCode resolution
     * first, then falls back to extracting the library out of the bundle's
     * classpath resources into a temp file.
     *
     * This comment used to say the fallback was the reliable path on Linux and
     * macOS because resolution "may fail when the class is initialized on a
     * non-OSGi worker thread".  That was the wrong diagnosis of a real symptom.
     * Every Bundle-NativeCode clause ended in a \ borrowed from Java source
     * style; manifests continue on a newline plus one space and treat no
     * character as an escape, so unfolding left the \ inside the value.  The
     * osname attribute therefore parsed under the name "\ osname" and came back
     * null on all nine clauses, matching fell to processor alone, the first
     * x86-64 clause won on every OS, and a Linux or macOS JVM was handed the
     * Windows .dll -- which failed here, silently, leaving the fallback to do
     * the real work.  The header was corrected 2026-09-05.
     *
     * The fallback stays, and is still load-bearing: it is what serves any
     * caller outside a running framework, such as a test harness loading this
     * class straight off the classpath.
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
        if (os.contains("linux")) return "/native/linux/" + dir + "/libclaude_eclipse_core.so";

        // Linux is the only riscv64 build, because Eclipse itself publishes a
        // riscv64 IDE for Linux and for nothing else.  Without this guard a
        // riscv64 JVM on any other OS resolves to a path that cannot exist --
        // native/windows/riscv64/ and friends -- and fails in System.load()
        // rather than reporting the platform as unsupported.
        if ("riscv64".equals(dir)) return null;

        if (os.contains("win"))     return "/native/windows/" + dir + "/claude_eclipse_core.dll";
        if (os.contains("mac"))     return "/native/macos/"   + dir + "/libclaude_eclipse_core.dylib";
        if (os.contains("freebsd")) return "/native/freebsd/" + dir + "/libclaude_eclipse_core.so";
        return null;
    }

    /**
     * Maps {@code os.arch} onto a bundled native directory, or null when no
     * build exists for that architecture.
     *
     * Returning null matters.  Defaulting an unrecognized architecture to
     * x86_64 hands a ppc64le or s390x JVM an x86-64 binary that extracts
     * successfully and then fails inside {@code System.load()}, instead of
     * reporting the platform as unsupported.
     *
     * This maps the architecture alone; which OS/arch pairs actually ship is
     * decided by the caller, which is where riscv64 is confined to Linux.
     */
    private static String nativeArchDir(String arch) {
        switch (arch) {
            case "aarch64": case "arm64":  return "aarch64";
            case "amd64":   case "x86_64": return "x86_64";
            case "riscv64":                return "riscv64";
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
     * Stops one specific background agent (an Agent/Task tool call with
     * run_in_background) via its own internal task id — a DIFFERENT id from the
     * tool_use id that identifies its top-level tool line ({@link ChatCallbacks#onToolStart}'s
     * own "id"). The wire shape of this control request was confirmed empirically,
     * not documented in any public Claude Agent SDK reference at the time this was
     * written. Returns false when there's no live process to send it to.
     */
    public static native boolean chatStopTask(long handle, String taskId);

    /**
     * Switches the permission mode of this manager's live process via the CLI's
     * {@code set_permission_mode} control request, so a mid-conversation change
     * applies without respawning. Returns false when there's no live process — the
     * next spawn passes the mode as {@code --permission-mode} anyway.
     */
    public static native boolean chatSetPermissionMode(long handle, String mode);

    /**
     * Writes one uploaded document out of a transcript to a file and returns its path,
     * or {@code ""} when it isn't there — what a reloaded conversation's attachment chip
     * opens. {@code index} counts the document blocks of message {@code messageUuid}, as
     * {@link #sessionLoad} numbered them. Blocking, and the file can be large: call it
     * off the UI thread.
     *
     * <p>The contents deliberately do not travel with the loaded conversation: a
     * transcript keeps every uploaded file in full, and carrying them into the webview
     * would freeze the view on a conversation holding a big one.
     */
    public static native String sessionDocumentFile(String workspaceRoot, String sessionId,
                                                    String messageUuid, int index);

    /**
     * The composer's {@code @} list for {@code query} under {@code root}: a JSON array of
     * {@code {path,name,type}} rows, {@code type} being {@code file}, {@code directory} or
     * {@code browser}. An empty query lists everything. The files come first and the
     * browser tabs (only when {@code withBrowser}) after them, except while the typed word
     * could still become {@code browser:}, when the tabs lead. The tabs come from the last
     * lookup, so this never waits on Chrome, except for a word starting {@code browser:},
     * which asks it (the first time, that starts the CLI's Chrome MCP server). Blocking
     * (a folder walk): call off the UI thread.
     */
    public static native String listMentions(String root, String claudeCmd, String query, boolean withBrowser);

    /**
     * Takes the browser back out of this manager's conversation, telling the model it is
     * gone; {@link ChatCallbacks#onBrowserState} reports it disconnected. Returns whether it
     * was on.
     */
    public static native boolean chatDisableChrome(long handle);

    /**
     * Whether the CLI is signed in with a claude.ai account, which Claude in Chrome needs.
     * Cached for a minute in the core; on macOS an uncached read queries the keychain.
     */
    public static native boolean hasClaudeAiLogin();

    /**
     * The browser text blocks (a JSON array; {@code []} when there are none) for a message
     * about to be sent on this manager, switching the browser on for its live process when
     * it isn't yet. Blocking — may open a Chrome tab. Call once the process is ensured.
     */
    public static native String chatBrowserBlocks(long handle, String claudeCmd, String message);


    /**
     * Starts this tab's CLI process if it has none, sending nothing.
     *
     * <p>Our process starts lazily, on the first message. The CLI, however,
     * answers a control request before any turn has happened — so Remote
     * Control never needed a conversation, only a process. This supplies one
     * and nothing else: no message, no turn.
     *
     * <p>Reuse follows the same rule as {@link #chatSendMessage}: same launch
     * settings and same conversation, so the two paths agree on what the tab's
     * process is instead of respawning each other's.
     *
     * <p><b>Blocking</b> — spawns a child process. Off the UI thread.
     *
     * @return false when no process could be started.
     */
    public static native boolean chatEnsureProcess(long handle, String claudeCmd, String workspaceRoot,
            int mcpPort, String mcpAuthToken, String resumeId, String permMode,
            String effort, String model, String thinking);
    /**
     * Turns Remote Control on or off for this tab's live process.
     *
     * <p>Remote Control is what makes a conversation the <i>same</i> conversation
     * everywhere: the CLI opens an outbound bridge, and anything typed here, on
     * claude.ai, or on the phone lands in all of them. It is not teleport, which
     * takes a one-way copy and then diverges.
     *
     * <p>Fire-and-forget — the CLI answers asynchronously, and that answer
     * arrives as {@link ChatCallbacks#onRemoteControl} carrying the session url.
     *
     * @return false only when the tab has no live process to ask.
     */
    public static native boolean chatRemoteControl(long handle, boolean enabled);

    /**
     * Sends one of the MCP servers window's control requests ({@code mcp_status},
     * {@code mcp_toggle}, {@code mcp_reconnect}, {@code mcp_authenticate},
     * {@code mcp_clear_auth}) to this tab's live process, under the page's
     * {@code token}. Any other subtype is refused.
     *
     * <p>Fire-and-forget — the reply arrives as {@link ChatCallbacks#onMcp}
     * carrying the same token.
     *
     * @return false when the tab has no live process, or the request was refused.
     */
    public static native boolean chatMcpRequest(long handle, String token, String requestJson);

    /**
     * Adds or removes an MCP server with the CLI's own {@code claude mcp add|remove},
     * run in {@code cwd} — the conversation's folder, which a Local server is keyed on
     * and a Project server's {@code .mcp.json} is written to.
     *
     * <p><b>Blocking</b> — runs the CLI, up to 30s. Off the UI thread.
     *
     * @return {@code {"token","ok":true}} or {@code {"token","ok":false,"error"}}.
     */
    public static native String mcpEditConfig(String claudeCmd, String cwd, String token, String opJson);

    /**
     * Whether switching this tab back to the default model would restart its
     * process. Every other launch setting is applied to the running process; this
     * one can be too, but only when the CLI has no model setting of its own — its
     * reset returns to the model it would pick with no setting, which is a different
     * model from the one a fresh process would launch on when a setting exists.
     *
     * <p>The view asks before restarting, since the restart archives the
     * conversation's Remote Control session on the user's other devices.
     */
    public static native boolean chatDefaultModelRestarts(long handle);

    /**
     * Applies a tab's launch settings to its live process at once, rather than
     * leaving them for the next message: what the view shows and what the process is
     * running stay the same thing, and under Remote Control the CLI tells the phone
     * and claude.ai about the change as it happens.
     *
     * @return false when the tab has no live process, or when the change needs a new
     *         one (the next message makes it, as before).
     */
    public static native boolean chatApplySettings(long handle, String permMode, String effort,
                                                   String model, String thinking);

    /**
     * Renders a Remote Control session url as a scannable QR code, as SVG.
     *
     * <p>SVG so it stays crisp at any size — a resampled QR is one that will not
     * scan. Deliberately black-on-white with the spec's quiet zone regardless of
     * theme, because decoders rely on that contrast and margin.
     *
     * <p>Generated on demand: it is a few KB of markup, wanted only when the
     * code is actually revealed. Returns {@code ""} if the url cannot be encoded.
     */
    public static native String remoteControlQr(String url);

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
         *
         * <p>{@code requestId} is the CLI's own id for this request. Keep it: it
         * is the only handle on a card that has to be taken back down again, and
         * {@link #onCardCancel} names the card that way.
         */
        default String onPermissionRequest(String requestId, String toolName,
                                           String inputJson, String rememberLabel) { return "deny"; }
        /**
         * Persistent mode only: claude asked a multiple-choice question
         * (built-in AskUserQuestion). Receives the questions array JSON; returns
         * the answers as {@code [{header,question,answer}]} or {@code "[]"} if
         * dismissed. May block until the user answers. {@code requestId} is the
         * CLI's id for the request — see {@link #onPermissionRequest}.
         */
        default String onQuestionRequest(String requestId, String questionsJson) { return "[]"; }
        /**
         * The CLI withdrew a request one of the two calls above is still blocked
         * on: its {@code requestId} is no longer wanted, and no answer will be
         * sent for it. Take the card off the screen.
         *
         * <p>Under Remote Control this is how the same decision made on the phone
         * or on claude.ai reaches this view — the CLI puts the prompt in front of
         * every surface and withdraws it from the rest the moment one of them
         * answers. It also fires when a turn ends with a prompt still open
         * (interrupt, or a hard failure), which is the other way a card can
         * outlive the thing it was asking about. Non-blocking.
         */
        default void onCardCancel(String requestId) {}
        /**
         * Session status snapshot for the GUI status bar (fired after each turn):
         * JSON with model, context %, context window, token breakdown and cost.
         * Non-blocking. Account-global rate limits are shared separately.
         */
        default void onStatus(String statusJson) {}
        /**
         * A launch setting now in effect on the live process, as JSON with any of
         * {@code permMode}, {@code model}, {@code effort}. Fired only when one
         * actually changed — including when it was changed on another device over
         * Remote Control, which is what this exists for. The process is already in
         * this state, so the view follows it rather than pushing anything back.
         * Non-blocking.
         */
        default void onSettingsChanged(String json) {}
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
        /**
         * A running subagent's (Task/Agent tool) own current step — its OWN tool
         * calls are never surfaced as top-level {@link #onToolStart}/{@link #onToolEnd}
         * (that would interleave a bogus tool line into the main transcript), so this
         * is the only signal of what it's doing while it runs. {@code json} is
         * {@code {"parentId":…,"name":…,"input":…}} when it starts a step, or
         * {@code {"parentId":…,"name":null}} once that step finishes. {@code parentId}
         * is the Agent tool_use id {@link #onToolStart} carried for the agent itself.
         * Non-blocking.
         */
        default void onAgentActivity(String json) {}
        /**
         * A one-line note for the conversation that did not come from the CLI, for
         * example the FreeBSD setup guide having been sent to Claude after a tool hit
         * the fdescfs error. Display only. Non-blocking.
         */
        default void onNotice(String text) {}
        /**
         * Remote Control state changed. Two shapes reach this:
         *
         * <ul>
         *   <li>the reply to a toggle —
         *       {@code {"enabled":bool,"url":…,"bridgeSessionId":…,"error":…}}.
         *       {@code url} is the conversation's address on claude.ai and is
         *       <b>only</b> available here: it is minted by the bridge, not
         *       derivable from anything the plugin holds.</li>
         *   <li>the bridge's own signal — {@code {"bridgeState":"ready|connected"}},
         *       which reflects the live link rather than our having asked for one.</li>
         * </ul>
         *
         * Non-blocking.
         */
        default void onRemoteControl(String json) {}
        /**
         * A message typed on another device reached this conversation over the
         * Remote Control bridge. Carries the message text.
         *
         * <p>Only ever fires for messages from elsewhere: a message sent from
         * here is not echoed back by the CLI, so the GUI can render this
         * directly without doubling a bubble it already drew. Non-blocking.
         */
        default void onRemoteMessage(String text) {}
        /**
         * Claude in Chrome changed state for this conversation:
         * {@code {"status":"connecting"|"connected"|"disconnected"}}, or
         * {@code {"status":"error","error":…}} when the CLI could not add the server.
         * Non-blocking.
         */
        default void onBrowserState(String json) {}
        /**
         * The reply to an MCP servers window request:
         * {@code {"token","ok":true,"response":…}} or {@code {"token","ok":false,"error":…}}.
         * Non-blocking.
         */
        default void onMcp(String json) {}
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
     * Greps the given sessions' message text (not titles) for {@code query}, first
     * match per session wins. Returns a JSON array of {@code {sessionId, snippet}}
     * for sessions that matched. Meant to run only over sessions whose title didn't
     * already match — the caller filters those out first.
     *
     * @param ownMessagesOnly restrict the scan to the user's own messages, skipping
     *     assistant turns — a narrower scope than the full conversation.
     * @param generation this search's ordinal in the caller's own sequence (bump on
     *     every new query). Lets an in-progress scan notice a newer one has since
     *     started and stop early instead of finishing a scan the UI will discard.
     */
    public static native String sessionSearchContent(String workspaceRoot, String sessionIdsJson, String query, boolean ownMessagesOnly, long generation);

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

    // ── Session history (web — claude.ai) ────────────────────────────────────

    /**
     * Returns the last web session list this Eclipse rendered, or {@code ""} if
     * there is none yet. Cache only — no network, no file scan — so the Web tab
     * can paint immediately while {@link #webSessionList} runs behind it.
     *
     * <p>On Windows the cache survives a restart (encrypted at rest with the
     * logged-in user's DPAPI key); elsewhere it lives only for this process.
     */
    public static native String webSessionCached();

    /**
     * Lists this account's claude.ai conversations as
     * {@code {state, sessions:[{id,title,status,repo,timestamp}]}}, where
     * {@code state} is {@code ok}, {@code signed-out}, {@code expired} or
     * {@code error} (the last carrying a short {@code message}).
     *
     * <p><b>The OAuth token never crosses this boundary.</b> Rust reads it at
     * call time, spends it on one request and zeroizes it; only the display
     * fields above come back. Nothing here can leak a credential into an SWT
     * string, the Eclipse error log or a JVM heap dump.
     *
     * <p><b>Blocking</b> — file I/O plus one HTTPS round trip, and on a stale
     * credential a short-lived {@code claude} process to let the CLI refresh
     * its own token (it stays the only writer of the credentials file, since
     * refresh tokens rotate). Call it off the UI thread.
     *
     * @param claudeCmd the configured {@code claude} command, used only if a
     *     refresh is needed.
     * @param forceRefresh skip the freshness window and re-fetch now.
     */
    public static native String webSessionList(String claudeCmd, boolean forceRefresh);

    // ── Teleport (continuing a claude.ai session here) ───────────────────────

    /**
     * Classifies a web session against this workspace, for the "Different
     * repository" prompt. Returns
     * {@code {status, proceed, sessionOwner, sessionName, sessionDisplay, currentDisplay}}
     * where {@code status} is {@code match}, {@code no_repo_required},
     * {@code host_unverified}, {@code not_in_repo} or {@code mismatch}.
     *
     * <p>{@code proceed} is true when teleport may start without asking. Only a
     * genuine mismatch, or a folder that is not a checkout at all, interrupt —
     * a session that names no repository (every Remote Control session) simply
     * goes through.
     *
     * <p><b>Blocking</b> — one HTTPS call plus git. Off the UI thread.
     */
    public static native String teleportRepoCheck(String claudeCmd, String sessionId, String workspaceRoot);

    /**
     * Pulls a web session down into this workspace as a local conversation,
     * returning {@code {ok:true, localSessionId, title, branch, messageCount}}
     * or {@code {ok:false, error, message}}.
     *
     * <p>Afterwards {@code localSessionId} is an ordinary local session — the
     * transcript is written to {@code ~/.claude/projects/<hash>/<uuid>.jsonl}
     * in the CLI's own layout, so {@code /resume}, the History panel and every
     * other Claude Code client treat it as a past conversation.
     *
     * <p><b>Nothing here touches the working tree.</b> {@code branch} is only
     * non-empty when the session names one and it actually exists locally or on
     * {@code origin}; acting on it is a separate, explicit call — see
     * {@link #teleportCheckoutBranch}.
     *
     * <p><b>Blocking</b> — several HTTPS round trips and a file write. Off the
     * UI thread.
     */
    public static native String teleportRun(String claudeCmd, String sessionId, String workspaceRoot);

    /**
     * Switches the working tree to a teleported session's branch, returning
     * {@code {ok, branch}} or {@code {ok:false, message}}.
     *
     * <p><b>The only teleport call that writes to the working tree</b>, and it
     * runs solely once the user has answered the branch prompt.
     */
    public static native String teleportCheckoutBranch(String workspaceRoot, String branch);

    /**
     * {@code {clean, changedFiles, currentBranch}} for this workspace — what the
     * branch prompt needs to warn before switching over uncommitted work.
     */
    public static native String teleportGitStatus(String workspaceRoot);

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

    /**
     * Whether chat processes are launched able to enter Auto mode
     * ({@code bypassPermissions}) without being restarted — the
     * {@link Constants#PREF_LIVE_AUTO_MODE} preference. Applies to conversations
     * started after the call; the ones already running keep what they launched with.
     */
    public static native void setLiveAutoMode(boolean enabled);

    // ---- Dictation -------------------------------------------------------

    /**
     * Transcript events for one dictation take. Every one arrives on a native
     * worker thread, never the UI thread.
     */
    public interface SttCallbacks {
        /** Revised guess at the current utterance; supersedes the previous one. */
        void onSttPartial(String text);
        /** The settled transcript for the take. */
        void onSttFinal(String text);
        /** The tail has been flushed; no further events for this take. */
        void onSttDone();
        void onSttError(String message);
        /** Input loudness, "0.0".."1.0", roughly every 100ms while recording. */
        default void onSttLevel(String rms) {}
        /**
         * Diagnostic line for the server view. Only emitted while native debug
         * logging is on, so this stays silent unless Debug mode is enabled.
         */
        default void onSttLog(String line) {}
    }

    /** Registers dictation callbacks. Process-wide: there is one capture at a time. */
    public static native void sttRegisterCallbacks(SttCallbacks callbacks);

    /**
     * Opens the default input device and streams it to the transcription
     * service. Returns as soon as the worker is spawned; results arrive via
     * {@link SttCallbacks}.
     *
     * <p>Requires a signed-in Claude.ai account: the stream authenticates with
     * the OAuth token, which is read and zeroized natively and never crosses
     * into Java or the webview.
     *
     * @param keyterms comma-joined recognition hints (project name, git branch,
     *                 …), or empty for none
     */
    public static native void sttStart(String keyterms);

    /** Ends the take. The final transcript follows on {@link SttCallbacks}. */
    public static native void sttStop();

    public static native boolean sttIsRecording();

    /**
     * Why dictation cannot capture on this machine, or an empty string when it can.
     * Linux and FreeBSD open ALSA at runtime, so the library loads without it and this
     * names what is missing; Windows and macOS always return empty.
     */
    public static native String sttUnavailableReason();

    /**
     * FreeBSD only: true when alsa-plugins, ALSA's bridge to OSS, is not installed.
     * ALSA itself loads without it, but no take can open the microphone. Always false
     * on other platforms.
     */
    public static native boolean sttNeedsAlsaPlugins();

    /**
     * FreeBSD only: the setup guide, as Markdown, that the Claude GUI view shows when
     * the {@code claude} CLI is missing. Compiled into the library from
     * {@code claude-eclipse-core/.settings/org.eclipse.core.freebsd.container}, so it
     * changes only when the natives are rebuilt. Empty on other platforms.
     */
    public static native String freebsdSetupGuide();

    /**
     * Linux only: true when there is nothing to record from — ALSA finds no sound card,
     * and no default capture device (a sound server's, say) opens without one. Always
     * false on other platforms.
     */
    public static native boolean sttNoCaptureDevice();
}
