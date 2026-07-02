use std::io::{BufRead, BufReader, Write};
use std::process::{Command, Stdio};
use std::sync::{Arc, Mutex};
use std::sync::atomic::{AtomicBool, AtomicU64, Ordering};

#[cfg(windows)]
use std::os::windows::process::CommandExt;

use jni::objects::{JObject, JString, JValue};

// ---------------------------------------------------------------------------
// Shared mutable state (Arc'd into spawned threads — no raw pointers)
// ---------------------------------------------------------------------------

struct ChatState {
    has_session: bool,
    awaiting: bool,
    cancel: Arc<AtomicBool>,
    /// Opt-in per manager: true = one long-lived `claude --input-format stream-json`
    /// process per conversation (Claude GUI); false = legacy spawn-per-message
    /// (deprecated Claude Chat view — keep that path byte-for-byte unchanged).
    persistent: bool,
    proc: Option<Arc<ProcHandle>>,
}

/// A live persistent claude process. Stdin writes are serialized through the
/// mutex (user messages, control_responses and interrupts come from different
/// threads); the reader thread owns stdout for the process lifetime.
struct ProcHandle {
    stdin: Mutex<std::process::ChildStdin>,
    child: Mutex<std::process::Child>,
    /// Session id from the latest init event. Compared against the resume id the
    /// GUI sends with each message to detect tab switches (respawn with --resume).
    session_id: Mutex<Option<String>>,
    /// Settings the process was spawned with (model/effort/mode/…). A mismatch on
    /// the next send forces a respawn so mid-chat dropdown changes keep their
    /// legacy per-message semantics.
    spawn_sig: String,
    alive: AtomicBool,
}

impl ProcHandle {
    fn write_line(&self, line: &str) -> std::io::Result<()> {
        let mut stdin = self.stdin.lock().unwrap();
        stdin.write_all(line.as_bytes())?;
        stdin.write_all(b"\n")?;
        stdin.flush()
    }

    fn kill(&self) {
        let _ = self.child.lock().unwrap().kill();
    }

    fn is_dead(&self) -> bool {
        if !self.alive.load(Ordering::Relaxed) {
            return true;
        }
        // try_wait also catches a child that exited before the reader saw EOF.
        self.child.lock().unwrap().try_wait().map(|s| s.is_some()).unwrap_or(true)
    }
}

struct CallbacksRef {
    java_vm: Arc<jni::JavaVM>,
    obj: Arc<jni::objects::GlobalRef>, // Arc so we can share without cloning GlobalRef
}

// ---------------------------------------------------------------------------
// Public ChatManager
// ---------------------------------------------------------------------------

pub struct ChatManager {
    state: Arc<Mutex<ChatState>>,
    callbacks: Arc<Mutex<Option<CallbacksRef>>>,
}

impl ChatManager {
    pub fn new() -> Self {
        ChatManager {
            state: Arc::new(Mutex::new(ChatState {
                has_session: false,
                awaiting: false,
                cancel: Arc::new(AtomicBool::new(false)),
                persistent: false,
                proc: None,
            })),
            callbacks: Arc::new(Mutex::new(None)),
        }
    }

    pub fn set_persistent(&self, on: bool) {
        self.state.lock().unwrap().persistent = on;
    }

    pub fn register_callbacks(&self, vm: Arc<jni::JavaVM>, obj: jni::objects::GlobalRef) {
        *self.callbacks.lock().unwrap() = Some(CallbacksRef {
            java_vm: vm,
            obj: Arc::new(obj),
        });
    }

    pub fn send_message(
        &self,
        message: String,
        claude_cmd: String,
        workspace_root: String,
        mcp_port: u16,
        mcp_auth_token: String,
        resume_id: String,
        perm_mode: String,
        effort: String,
        model: String,
        thinking: String,
    ) {
        // Mid-turn sends: legacy drops them; persistent mode QUEUES them onto the
        // live process's stdin (VSCode behavior) — the CLI answers in succession.
        // Only the same conversation may queue; a tab switch mid-stream is dropped
        // exactly like before.
        let queue_target = {
            let s = self.state.lock().unwrap();
            if !s.awaiting {
                None
            } else if s.persistent {
                Some(s.proc.clone())
            } else {
                return;
            }
        };
        if let Some(proc_opt) = queue_target {
            if let Some(p) = proc_opt {
                let same_conversation = resume_id.is_empty()
                    || p.session_id.lock().unwrap().as_deref() == Some(resume_id.as_str());
                if same_conversation && !p.is_dead() {
                    let msg_json = serde_json::json!({
                        "type": "user",
                        "message": { "role": "user", "content": message }
                    });
                    if p.write_line(&msg_json.to_string()).is_err() {
                        // Reader's EOF path surfaces the failure.
                        p.alive.store(false, Ordering::Relaxed);
                    }
                }
            }
            return;
        }

        let (java_vm, callbacks_obj) = match self.callbacks.lock().unwrap().as_ref() {
            Some(cb) => (Arc::clone(&cb.java_vm), Arc::clone(&cb.obj)),
            None => return,
        };

        if self.state.lock().unwrap().persistent {
            self.send_persistent(
                message, claude_cmd, workspace_root, mcp_port, mcp_auth_token,
                resume_id, perm_mode, effort, model, thinking, java_vm, callbacks_obj,
            );
            return;
        }

        // Fresh cancel token for this turn.
        let cancel = Arc::new(AtomicBool::new(false));
        {
            let mut s = self.state.lock().unwrap();
            s.cancel = Arc::clone(&cancel);
            s.awaiting = true;
        }

        // Arc the shared state so the thread can update it when done.
        let state_arc = Arc::clone(&self.state);

        std::thread::Builder::new()
            .name("claude-chat-turn".into())
            .spawn(move || {
                let success = run_turn(
                    &message,
                    &claude_cmd,
                    &workspace_root,
                    mcp_port,
                    &mcp_auth_token,
                    &resume_id,
                    &perm_mode,
                    &effort,
                    &model,
                    &thinking,
                    &cancel,
                    &java_vm,
                    &callbacks_obj,
                );
                let mut s = state_arc.lock().unwrap();
                s.awaiting = false;
                if success {
                    s.has_session = true;
                }
            })
            .expect("Failed to spawn chat thread");
    }

    pub fn cancel(&self) {
        let (persistent, proc, awaiting) = {
            let s = self.state.lock().unwrap();
            s.cancel.store(true, Ordering::Relaxed);
            (s.persistent, s.proc.clone(), s.awaiting)
        };
        if !persistent {
            return; // legacy: run_turn's loop sees the flag and kills the child
        }
        // Persistent mode: interrupt the turn instead of killing the process —
        // the CLI acks, emits result(error_during_execution), and stays usable.
        if let Some(p) = proc {
            if awaiting && !p.is_dead() {
                static INT_SEQ: AtomicU64 = AtomicU64::new(1);
                let req_id = format!("eclipse-int-{}", INT_SEQ.fetch_add(1, Ordering::Relaxed));
                let msg = serde_json::json!({
                    "type": "control_request",
                    "request_id": req_id,
                    "request": { "subtype": "interrupt" }
                });
                if p.write_line(&msg.to_string()).is_err() {
                    // Stdin gone — fall back to a hard kill; reader EOF cleans up.
                    p.alive.store(false, Ordering::Relaxed);
                    p.kill();
                }
            }
        }
    }

    pub fn reset_session(&self) {
        let persistent = self.state.lock().unwrap().persistent;
        if persistent {
            // New Chat: drop the conversation process entirely. The next send
            // spawns fresh (no --resume), which is exactly the legacy semantic.
            let proc = {
                let mut s = self.state.lock().unwrap();
                s.cancel.store(true, Ordering::Relaxed);
                s.has_session = false;
                s.awaiting = false;
                s.proc.take()
            };
            if let Some(p) = proc {
                p.alive.store(false, Ordering::Relaxed);
                p.kill();
            }
            self.emit_system("Session reset.");
            return;
        }
        self.cancel();
        let mut s = self.state.lock().unwrap();
        s.has_session = false;
        s.awaiting = false;
        drop(s);
        self.emit_system("Session reset.");
    }

    /// Persistent-mode send: reuse the live process when the conversation and
    /// settings match, otherwise (re)spawn — then write the message as one
    /// NDJSON line. Runs on a short-lived thread so the SWT caller never waits
    /// on a process spawn.
    #[allow(clippy::too_many_arguments)]
    fn send_persistent(
        &self,
        message: String,
        claude_cmd: String,
        workspace_root: String,
        mcp_port: u16,
        mcp_auth_token: String,
        resume_id: String,
        perm_mode: String,
        effort: String,
        model: String,
        thinking: String,
        java_vm: Arc<jni::JavaVM>,
        callbacks: Arc<jni::objects::GlobalRef>,
    ) {
        let cancel = Arc::new(AtomicBool::new(false));
        {
            let mut s = self.state.lock().unwrap();
            s.cancel = Arc::clone(&cancel);
            s.awaiting = true;
        }
        let state = Arc::clone(&self.state);

        std::thread::Builder::new()
            .name("claude-chat-send".into())
            .spawn(move || {
                fire_void(&java_vm, &callbacks, "onStreamStart");

                let sig = format!(
                    "{}|{}|{}|{}|{}|{}|{}|{}",
                    claude_cmd, workspace_root, mcp_port, mcp_auth_token,
                    perm_mode, effort, model, thinking
                );

                // Reuse only when the process is alive, was spawned with the same
                // settings, and carries the conversation the GUI is addressing:
                //  - same tab      → resume_id == live session id
                //  - New Chat      → resume_id empty but a session exists → respawn fresh
                //  - tab switch    → different resume_id → respawn with --resume
                let mut proc_opt = { state.lock().unwrap().proc.clone() };
                let reusable = match &proc_opt {
                    Some(p) => {
                        let sid = p.session_id.lock().unwrap().clone();
                        let same_conversation = if resume_id.is_empty() {
                            sid.is_none()
                        } else {
                            sid.as_deref() == Some(resume_id.as_str())
                        };
                        if p.is_dead() || p.spawn_sig != sig || !same_conversation {
                            p.alive.store(false, Ordering::Relaxed);
                            p.kill();
                            false
                        } else {
                            true
                        }
                    }
                    None => false,
                };
                if !reusable {
                    proc_opt = None;
                }

                let proc = match proc_opt {
                    Some(p) => p,
                    None => {
                        match spawn_persistent(
                            &claude_cmd, &workspace_root, mcp_port, &mcp_auth_token,
                            &resume_id, &perm_mode, &effort, &model, &thinking, sig,
                            Arc::clone(&state), Arc::clone(&java_vm), Arc::clone(&callbacks),
                        ) {
                            Ok(p) => {
                                state.lock().unwrap().proc = Some(Arc::clone(&p));
                                p
                            }
                            Err(e) => {
                                fire_string(&java_vm, &callbacks, "onError",
                                            &format!("Failed to launch Claude: {}", e));
                                fire_void(&java_vm, &callbacks, "onStreamEnd");
                                state.lock().unwrap().awaiting = false;
                                return;
                            }
                        }
                    }
                };

                let msg_json = serde_json::json!({
                    "type": "user",
                    "message": { "role": "user", "content": message }
                });
                if let Err(e) = proc.write_line(&msg_json.to_string()) {
                    proc.alive.store(false, Ordering::Relaxed);
                    fire_string(&java_vm, &callbacks, "onError",
                                &format!("Claude stopped accepting input ({}). Please try again.", e));
                    fire_void(&java_vm, &callbacks, "onStreamEnd");
                    let mut s = state.lock().unwrap();
                    s.awaiting = false;
                    s.proc = None;
                }
                // Reader thread takes it from here (result event → onStreamEnd).
            })
            .expect("Failed to spawn chat send thread");
    }

    fn emit_system(&self, msg: &str) {
        let guard = self.callbacks.lock().unwrap();
        if let Some(cb) = guard.as_ref() {
            fire_string(&cb.java_vm, &cb.obj, "onSystem", msg);
        }
    }
}

impl Drop for ChatManager {
    fn drop(&mut self) {
        self.cancel();
        // Persistent process must not outlive the view (chatDestroy → drop).
        if let Ok(mut s) = self.state.lock() {
            if let Some(p) = s.proc.take() {
                p.alive.store(false, Ordering::Relaxed);
                p.kill();
            }
        }
    }
}

// ---------------------------------------------------------------------------
// One conversation turn (runs on a dedicated thread)
// ---------------------------------------------------------------------------

fn run_turn(
    message: &str,
    claude_cmd: &str,
    workspace_root: &str,
    mcp_port: u16,
    mcp_auth_token: &str,
    resume_id: &str,
    perm_mode: &str,
    effort: &str,
    model: &str,
    thinking: &str,
    cancel: &Arc<AtomicBool>,
    java_vm: &Arc<jni::JavaVM>,
    callbacks: &Arc<jni::objects::GlobalRef>,
) -> bool {
    fire_void(java_vm, callbacks, "onStreamStart");

    let mut cmd_args: Vec<String> = vec![
        "-p".into(),
        message.into(),
        "--output-format".into(),
        "stream-json".into(),
        "--verbose".into(),
        // Stream fine-grained events so we can show a live output-token counter
        // (message_start / content_block_delta / message_delta usage).
        "--include-partial-messages".into(),
    ];
    // Effort level from the GUI meter (low | medium | high | xhigh | max). This
    // drives how much Claude reasons — it also governs whether thinking happens, so
    // we do NOT force MAX_THINKING_TOKENS (which --effort overrides anyway).
    if !effort.is_empty() {
        cmd_args.push("--effort".into());
        cmd_args.push(effort.to_string());
    }
    // Model from the GUI chooser (sonnet | sonnet[1m] | opus | haiku | <custom from
    // prefs args>). Empty = "Default", let claude pick. Appended last so it overrides
    // any --model the user put in their preference args.
    if !model.is_empty() {
        cmd_args.push("--model".into());
        cmd_args.push(model.to_string());
    }
    // Permission mode from the GUI dropdown (default | acceptEdits | plan |
    // bypassPermissions). Without this, claude -p denies edits → "no permission".
    if !perm_mode.is_empty() {
        cmd_args.push("--permission-mode".into());
        cmd_args.push(perm_mode.to_string());
    }
    // Expose our server as a named config server ("eclipse") so its tools become
    // referenceable. The IDE auto-connect (CLAUDE_CODE_SSE_PORT) does NOT make tools
    // eligible for --permission-prompt-tool or steerable by name, so we register the
    // same loopback SSE endpoint via --mcp-config too.
    if mcp_port > 0 {
        let cfg = format!(
            r#"{{"mcpServers":{{"eclipse":{{"type":"sse","url":"http://127.0.0.1:{}/sse"}}}}}}"#,
            mcp_port
        );
        cmd_args.push("--mcp-config".into());
        cmd_args.push(cfg);

        // The built-in AskUserQuestion auto-dismisses in headless -p mode (no
        // interactive surface), so disable it and steer claude to our MCP tool,
        // which renders the in-chat multiple-choice card and blocks for the answer.
        // The MCP tool is pre-approved (--allowed-tools) so it isn't gated by the
        // permission prompt — otherwise the Yes/No card would intercept it instead
        // of the question card rendering.
        cmd_args.push("--allowed-tools".into());
        cmd_args.push("mcp__eclipse__askUserQuestion".into());
        // Disallow the blocking IDE diff tool: in the GUI we want claude to use its
        // built-in Edit (gated by the approvalPrompt card → "Make this edit?" + our
        // non-blocking DiffPreview), NOT openDiff (which gates as "allow openDiff?" then
        // blocks until the user saves/closes the diff tab). Cover every name form.
        // Scoped to the GUI chat only — the terminal view's claude is unaffected.
        cmd_args.push("--disallowed-tools".into());
        cmd_args.push("AskUserQuestion".into());
        // Bare "openDiff" is not a known tool (CLI warns); the qualified MCP
        // names below are the real diff tools to block.
        cmd_args.push("mcp__ide__openDiff".into());
        cmd_args.push("mcp__eclipse__openDiff".into());
        cmd_args.push("--append-system-prompt".into());
        cmd_args.push(
            "To ask the user to choose between options, you MUST call the \
             mcp__eclipse__askUserQuestion tool — never the built-in AskUserQuestion, \
             and never just describe the options in prose. Pass a `questions` array; each \
             item has `question`, a short `header` (tab label), `multiSelect`, and `options` \
             (each with `label` and `description`). The tool returns the user's selections."
                .into(),
        );

        // "Ask before edits" (default mode): route each permission request to our
        // approvalPrompt tool so the GUI can show an in-chat Yes/No decision card.
        if perm_mode == "default" {
            cmd_args.push("--permission-prompt-tool".into());
            cmd_args.push("mcp__eclipse__approvalPrompt".into());
        }
    }
    // Per-tab continuity: resume the tab's own session if we have its id, else
    // start fresh (a new session id comes back via the init event → onSessionId).
    if !resume_id.is_empty() {
        cmd_args.push("--resume".into());
        cmd_args.push(resume_id.to_string());
    }

    // Rust 1.77+ properly handles .cmd/.bat files on Windows: it resolves
    // the full path, quotes it correctly (even with spaces), and escapes
    // special characters in arguments for cmd.exe.  No manual cmd.exe /c
    // wrapping needed — that actually broke special chars like " \ / '
    // because cmd.exe re-interprets the command line.
    let mut cmd = Command::new(claude_cmd);
    cmd.args(&cmd_args)
        .current_dir(workspace_root)
        .stdin(Stdio::null())
        .stdout(Stdio::piped())
        .stderr(Stdio::piped());

    // Hide the console window that cmd.exe briefly opens on Windows.
    #[cfg(windows)]
    cmd.creation_flags(0x08000000); // CREATE_NO_WINDOW

    // macOS/Linux: Eclipse launched from Finder (mac) or the GNOME/KDE
    // menu (linux) inherits a minimal env and misses anything set only in
    // the user's shell rc — PATH entries for nvm/asdf/Homebrew-installed
    // `claude` and any corporate proxy vars.  Inject whatever we captured
    // from the login shell; absolute paths are unaffected because the
    // kernel skips PATH lookup when the command contains /.
    for (k, v) in crate::shell_env::captured_env().to_inject() {
        cmd.env(k, v);
    }

    // Thinking toggle: "0" (off) disables extended thinking via MAX_THINKING_TOKENS=0,
    // which suppresses thinking even at high --effort (verified). On = leave it to effort.
    if thinking == "0" {
        cmd.env("MAX_THINKING_TOKENS", "0");
    }

    if mcp_port > 0 && !mcp_auth_token.is_empty() {
        // Connect Claude to this instance's MCP server. The CLI auto-connects when
        // CLAUDE_CODE_SSE_PORT is set, then reads the auth token from the lock file.
        // CLAUDE_IDE_* are ignored by current CLI builds but kept for older releases.
        cmd.env("CLAUDE_CODE_SSE_PORT", mcp_port.to_string())
           .env("CLAUDE_IDE_PORT", mcp_port.to_string())
           .env("CLAUDE_IDE_AUTH_TOKEN", mcp_auth_token)
           .env("CLAUDE_IDE_NAME", "Eclipse");
    } else {
        // No MCP server running — remove any inherited IDE env vars so Claude
        // does not try to connect to another instance's server and hang.
        cmd.env_remove("CLAUDE_CODE_SSE_PORT")
           .env_remove("CLAUDE_IDE_PORT")
           .env_remove("CLAUDE_IDE_AUTH_TOKEN")
           .env_remove("CLAUDE_IDE_NAME");
    }

    let mut child = match cmd.spawn() {
        Ok(c) => c,
        Err(e) => {
            fire_string(java_vm, callbacks, "onError", &format!("Failed to launch Claude: {}", e));
            fire_void(java_vm, callbacks, "onStreamEnd");
            return false;
        }
    };

    // Drain stderr on a background thread so writes never block the child.
    // The collected text is reported as a system message after the turn ends.
    let stderr_buf = Arc::new(Mutex::new(String::new()));
    {
        let stderr_stream = child.stderr.take().unwrap();
        let buf = Arc::clone(&stderr_buf);
        std::thread::Builder::new()
            .name("claude-chat-stderr".into())
            .spawn(move || {
                let mut reader = BufReader::new(stderr_stream);
                let mut line = String::new();
                while let Ok(n) = reader.read_line(&mut line) {
                    if n == 0 { break; }
                    buf.lock().unwrap().push_str(&line);
                    line.clear();
                }
            })
            .ok();
    }

    let stdout = child.stdout.take().unwrap();
    let reader = BufReader::new(stdout);

    // Tracks cumulative text already sent for the current assistant turn,
    // so we can compute deltas from partial assistant events.
    let mut last_text_len: usize = 0;
    let mut last_thinking_len: usize = 0;
    // Live output-token counter state (from --include-partial-messages):
    // base from message_start, +1/4 char estimate per text_delta, exact at message_delta.
    let mut tok_base: u64 = 0;
    let mut tok_chars: u64 = 0;

    for line in reader.lines() {
        if cancel.load(Ordering::Relaxed) {
            break;
        }
        let line = match line {
            Ok(l) if !l.is_empty() => l,
            _ => continue,
        };
        process_event(&line, java_vm, callbacks, &mut last_text_len, &mut last_thinking_len,
                      &mut tok_base, &mut tok_chars);
    }

    let exit_ok = if cancel.load(Ordering::Relaxed) {
        let _ = child.kill();
        false
    } else {
        child.wait().map(|s| s.success()).unwrap_or(false)
    };

    // Only surface stderr if the process exited with an error — avoids
    // noisy warnings that Claude CLI writes to stderr during normal operation.
    if !exit_ok {
        let stderr_text = stderr_buf.lock().unwrap().trim().to_string();
        if !stderr_text.is_empty() {
            fire_string(java_vm, callbacks, "onError", &stderr_text);
        }
    }

    fire_void(java_vm, callbacks, "onStreamEnd");
    exit_ok
}

// ---------------------------------------------------------------------------
// Persistent mode (Claude GUI): one long-lived `claude` per conversation.
//
// claude -p --input-format stream-json --output-format stream-json --verbose
//        --include-partial-messages --permission-prompt-tool stdio
//
// User messages go in as NDJSON on stdin; permission requests come back as
// control_request/can_use_tool events which BLOCK the CLI until we write a
// control_response (allow/deny) — CLI-enforced approval, verified against
// claude 2.1.177. Cancel = control_request/interrupt; the process survives.
// ---------------------------------------------------------------------------

#[allow(clippy::too_many_arguments)]
fn spawn_persistent(
    claude_cmd: &str,
    workspace_root: &str,
    mcp_port: u16,
    mcp_auth_token: &str,
    resume_id: &str,
    perm_mode: &str,
    effort: &str,
    model: &str,
    thinking: &str,
    spawn_sig: String,
    state: Arc<Mutex<ChatState>>,
    java_vm: Arc<jni::JavaVM>,
    callbacks: Arc<jni::objects::GlobalRef>,
) -> std::io::Result<Arc<ProcHandle>> {
    let mut cmd_args: Vec<String> = vec![
        "-p".into(),
        "--input-format".into(),
        "stream-json".into(),
        "--output-format".into(),
        "stream-json".into(),
        "--verbose".into(),
        "--include-partial-messages".into(),
        // Route permission prompts over stdin/stdout as control_requests. The
        // CLI blocks each gated tool until our control_response — this replaces
        // the mcp__eclipse__approvalPrompt shim (which depended on the model
        // remembering to call it).
        "--permission-prompt-tool".into(),
        "stdio".into(),
    ];
    if !effort.is_empty() {
        cmd_args.push("--effort".into());
        cmd_args.push(effort.to_string());
    }
    if !model.is_empty() {
        cmd_args.push("--model".into());
        cmd_args.push(model.to_string());
    }
    if !perm_mode.is_empty() {
        cmd_args.push("--permission-mode".into());
        cmd_args.push(perm_mode.to_string());
    }
    if mcp_port > 0 {
        // Keep the eclipse MCP server registered so its IDE tools stay available
        // to the chat exactly as before.
        let cfg = format!(
            r#"{{"mcpServers":{{"eclipse":{{"type":"sse","url":"http://127.0.0.1:{}/sse"}}}}}}"#,
            mcp_port
        );
        cmd_args.push("--mcp-config".into());
        cmd_args.push(cfg);

        // Blocking diff tools stay disallowed (the approval card + DiffPreview
        // handle edits). The two legacy shim tools are superseded here: questions
        // now flow through the built-in AskUserQuestion via the control channel.
        // The real diff tools are the qualified MCP names; a bare "openDiff" is
        // not a known tool and the CLI warns on it ("matches no known tool").
        cmd_args.push("--disallowed-tools".into());
        cmd_args.push("mcp__ide__openDiff".into());
        cmd_args.push("mcp__eclipse__openDiff".into());
        cmd_args.push("mcp__eclipse__askUserQuestion".into());
        cmd_args.push("mcp__eclipse__approvalPrompt".into());
    }
    if !resume_id.is_empty() {
        cmd_args.push("--resume".into());
        cmd_args.push(resume_id.to_string());
    }

    let mut cmd = Command::new(claude_cmd);
    cmd.args(&cmd_args)
        .current_dir(workspace_root)
        .stdin(Stdio::piped())
        .stdout(Stdio::piped())
        .stderr(Stdio::piped());

    #[cfg(windows)]
    cmd.creation_flags(0x08000000); // CREATE_NO_WINDOW

    for (k, v) in crate::shell_env::captured_env().to_inject() {
        cmd.env(k, v);
    }

    if thinking == "0" {
        cmd.env("MAX_THINKING_TOKENS", "0");
    }

    if mcp_port > 0 && !mcp_auth_token.is_empty() {
        cmd.env("CLAUDE_CODE_SSE_PORT", mcp_port.to_string())
           .env("CLAUDE_IDE_PORT", mcp_port.to_string())
           .env("CLAUDE_IDE_AUTH_TOKEN", mcp_auth_token)
           .env("CLAUDE_IDE_NAME", "Eclipse");
    } else {
        cmd.env_remove("CLAUDE_CODE_SSE_PORT")
           .env_remove("CLAUDE_IDE_PORT")
           .env_remove("CLAUDE_IDE_AUTH_TOKEN")
           .env_remove("CLAUDE_IDE_NAME");
    }

    let mut child = cmd.spawn()?;
    let stdin = child.stdin.take().unwrap();
    let stdout = child.stdout.take().unwrap();
    let stderr_stream = child.stderr.take().unwrap();

    let proc = Arc::new(ProcHandle {
        stdin: Mutex::new(stdin),
        child: Mutex::new(child),
        session_id: Mutex::new(None),
        spawn_sig,
        alive: AtomicBool::new(true),
    });

    // Stderr drain (surfaced only if the process dies mid-turn).
    let stderr_buf = Arc::new(Mutex::new(String::new()));
    {
        let buf = Arc::clone(&stderr_buf);
        std::thread::Builder::new()
            .name("claude-chat-stderr".into())
            .spawn(move || {
                let mut reader = BufReader::new(stderr_stream);
                let mut line = String::new();
                while let Ok(n) = reader.read_line(&mut line) {
                    if n == 0 { break; }
                    buf.lock().unwrap().push_str(&line);
                    line.clear();
                }
            })
            .ok();
    }

    // Stdout reader lives as long as the process.
    {
        let proc = Arc::clone(&proc);
        std::thread::Builder::new()
            .name("claude-chat-reader".into())
            .spawn(move || reader_loop(proc, state, java_vm, callbacks, stderr_buf, stdout))
            .ok();
    }

    Ok(proc)
}

fn reader_loop(
    proc: Arc<ProcHandle>,
    state: Arc<Mutex<ChatState>>,
    java_vm: Arc<jni::JavaVM>,
    callbacks: Arc<jni::objects::GlobalRef>,
    stderr_buf: Arc<Mutex<String>>,
    stdout: std::process::ChildStdout,
) {
    let reader = BufReader::new(stdout);
    let mut last_text_len: usize = 0;
    let mut last_thinking_len: usize = 0;
    let mut tok_base: u64 = 0;
    let mut tok_chars: u64 = 0;

    for line in reader.lines() {
        let line = match line {
            Ok(l) if !l.is_empty() => l,
            Ok(_) => continue,
            Err(_) => break,
        };
        let event: serde_json::Value = match serde_json::from_str(&line) {
            Ok(v) => v,
            Err(_) => continue,
        };

        match event["type"].as_str().unwrap_or("") {
            "control_request" => {
                handle_control_request(&event, &proc, &java_vm, &callbacks);
                continue;
            }
            // Ack of an interrupt we sent — nothing to do.
            "control_response" => continue,
            "result" => {
                // Turn complete. An interrupted turn reports
                // is_error/error_during_execution; the cancel flag tells us the
                // user asked for it, so it renders as a quiet stop (legacy parity).
                let cancelled = state.lock().unwrap().cancel.load(Ordering::Relaxed);
                if event["is_error"].as_bool().unwrap_or(false) && !cancelled {
                    if let Some(txt) = event["result"].as_str() {
                        if !txt.is_empty() {
                            fire_string(&java_vm, &callbacks, "onError", txt);
                        }
                    }
                }
                {
                    let mut s = state.lock().unwrap();
                    s.awaiting = false;
                    s.has_session = true;
                }
                fire_void(&java_vm, &callbacks, "onStreamEnd");
                last_text_len = 0;
                last_thinking_len = 0;
                tok_base = 0;
                tok_chars = 0;
                continue;
            }
            "system" => {
                // The CLI re-emits init every turn; keep the live session id
                // current for the reuse check, then let process_event_value fire
                // onSessionId/onSystem exactly as the legacy path does.
                if event["subtype"].as_str() == Some("init") {
                    if let Some(sid) = event["session_id"].as_str() {
                        *proc.session_id.lock().unwrap() = Some(sid.to_string());
                    }
                }
            }
            _ => {}
        }

        // A QUEUED message's turn starts on the CLI's own initiative (no
        // send_message call precedes it) — when turn content arrives while we
        // think no turn is running, reopen the stream. Robust against the CLI
        // batching several queued messages into one turn. Restricted to
        // unambiguous turn-content events so a stray idle event can't open a
        // phantom turn that never gets a result.
        let is_turn_content = match event["type"].as_str().unwrap_or("") {
            "assistant" | "stream_event" => true,
            "system" => event["subtype"].as_str() == Some("init"),
            _ => false,
        };
        if is_turn_content {
            let reopened = {
                let mut s = state.lock().unwrap();
                if !s.awaiting { s.awaiting = true; true } else { false }
            };
            if reopened {
                fire_void(&java_vm, &callbacks, "onStreamStart");
            }
        }

        process_event_value(&event, &java_vm, &callbacks, &mut last_text_len,
                            &mut last_thinking_len, &mut tok_base, &mut tok_chars);
    }

    // EOF: crash, logout, kill (reset/dispose) — mark dead, free our state slot.
    proc.alive.store(false, Ordering::Relaxed);
    let was_awaiting = {
        let mut s = state.lock().unwrap();
        let w = s.awaiting;
        s.awaiting = false;
        // A respawn may already have replaced us; only clear our own slot.
        let is_ours = s.proc.as_ref().map(|cur| Arc::ptr_eq(cur, &proc)).unwrap_or(false);
        if is_ours {
            s.proc = None;
        }
        w
    };
    if was_awaiting {
        let cancelled = state.lock().unwrap().cancel.load(Ordering::Relaxed);
        if !cancelled {
            let stderr_text = stderr_buf.lock().unwrap().trim().to_string();
            let msg = if stderr_text.is_empty() {
                "Claude process exited unexpectedly."
            } else {
                stderr_text.as_str()
            };
            fire_string(&java_vm, &callbacks, "onError", msg);
        }
        fire_void(&java_vm, &callbacks, "onStreamEnd");
    }
}

/// can_use_tool: ask the user via the Java callbacks. Runs on its own thread so
/// the reader stays free — a Stop while the card is up still processes the
/// interrupt's result event immediately.
fn handle_control_request(
    event: &serde_json::Value,
    proc: &Arc<ProcHandle>,
    java_vm: &Arc<jni::JavaVM>,
    callbacks: &Arc<jni::objects::GlobalRef>,
) {
    let request = &event["request"];
    if request["subtype"].as_str() != Some("can_use_tool") {
        return;
    }
    let request_id = event["request_id"].as_str().unwrap_or("").to_string();
    let tool_name = request["tool_name"].as_str().unwrap_or("tool").to_string();
    let input = request.get("input").cloned().unwrap_or_else(|| serde_json::json!({}));
    // The CLI's suggested "remember this decision" rules (setMode / addRules /
    // addDirectories). We surface the primary one as the card's middle option and
    // echo it back as updatedPermissions so the CLI enforces the scoped rule.
    let suggestions = request.get("permission_suggestions")
        .cloned()
        .unwrap_or_else(|| serde_json::json!([]));

    let proc = Arc::clone(proc);
    let vm = Arc::clone(java_vm);
    let cb = Arc::clone(callbacks);
    std::thread::Builder::new()
        .name("claude-chat-perm".into())
        .spawn(move || {
            let response = decide_can_use_tool(&tool_name, &input, &suggestions, &vm, &cb);
            let msg = serde_json::json!({
                "type": "control_response",
                "response": {
                    "subtype": "success",
                    "request_id": request_id,
                    "response": response
                }
            });
            if proc.write_line(&msg.to_string()).is_err() {
                proc.alive.store(false, Ordering::Relaxed);
            }
        })
        .ok();
}

/// Maps a can_use_tool request onto the GUI's existing cards and back:
///  - AskUserQuestion → onQuestionRequest, answers array → {questions, answers}
///  - everything else → onPermissionRequest, "allow*"/"deny[msg]" decision string
///    (same contract as ApprovalPromptTool so the Java side is shared code).
fn decide_can_use_tool(
    tool_name: &str,
    input: &serde_json::Value,
    suggestions: &serde_json::Value,
    java_vm: &Arc<jni::JavaVM>,
    callbacks: &Arc<jni::objects::GlobalRef>,
) -> serde_json::Value {
    if tool_name == "AskUserQuestion" {
        let questions = input.get("questions").cloned().unwrap_or_else(|| serde_json::json!([]));
        let ans = fire_string_ret(java_vm, callbacks, "onQuestionRequest", &questions.to_string())
            .unwrap_or_default();
        let parsed: serde_json::Value = serde_json::from_str(&ans)
            .unwrap_or_else(|_| serde_json::json!([]));
        let arr = parsed.as_array().cloned().unwrap_or_default();
        if arr.is_empty() {
            return serde_json::json!({
                "behavior": "deny",
                "message": "The user dismissed the questions without answering."
            });
        }
        // The card answers arrive positionally ([{header,question,answer}]);
        // the CLI wants a map keyed by the original question text.
        let mut answers = serde_json::Map::new();
        if let Some(qs) = questions.as_array() {
            for (i, q) in qs.iter().enumerate() {
                let qtext = q["question"].as_str().unwrap_or("");
                let a = arr.get(i).and_then(|e| e["answer"].as_str()).unwrap_or("");
                if !qtext.is_empty() && !a.is_empty() {
                    answers.insert(qtext.to_string(), serde_json::json!(a));
                }
            }
        }
        return serde_json::json!({
            "behavior": "allow",
            "updatedInput": { "questions": questions, "answers": answers }
        });
    }

    // Pick the primary suggestion + its human label for the card's middle option.
    // Empty label → the card shows no "remember" option (just Yes / No / instead).
    let (primary, remember_label) = primary_suggestion(suggestions);

    let decision = fire_three_string_ret(java_vm, callbacks, "onPermissionRequest",
                                         tool_name, &input.to_string(), &remember_label)
        .unwrap_or_else(|| "deny".into());

    if decision == "allowRemember" {
        // Allow AND echo the CLI's own suggestion so it enforces the scoped rule
        // (VSCode parity — replaces the old client-side "allow everything" flag).
        match primary {
            Some(s) => serde_json::json!({
                "behavior": "allow",
                "updatedInput": input,
                "updatedPermissions": [s]
            }),
            None => serde_json::json!({ "behavior": "allow", "updatedInput": input }),
        }
    } else if decision.starts_with("allow") {
        serde_json::json!({ "behavior": "allow", "updatedInput": input })
    } else {
        let msg = if decision.starts_with("deny") && decision.len() > 4 {
            decision[4..].to_string()
        } else {
            "The user declined this action in Eclipse.".to_string()
        };
        serde_json::json!({ "behavior": "deny", "message": msg })
    }
}

/// Chooses the suggestion to surface as the approval card's middle "remember"
/// option and builds its human label. Returns (suggestion, label); an empty
/// label means no remember option should be shown. Uses the CLI's own ordering
/// (first = most relevant) and labels the scope truthfully from `destination`
/// ("this session" vs "always").
fn primary_suggestion(suggestions: &serde_json::Value) -> (Option<serde_json::Value>, String) {
    let arr = match suggestions.as_array() {
        Some(a) if !a.is_empty() => a,
        _ => return (None, String::new()),
    };
    let s = arr[0].clone();
    let scope = match s["destination"].as_str() {
        Some("session") => "this session",
        _ => "always",
    };
    let label = match s["type"].as_str() {
        Some("setMode") => match s["mode"].as_str() {
            Some("acceptEdits") => format!("Yes, allow all edits {}", scope),
            Some(m) => format!("Yes, switch to {} mode {}", m, scope),
            None => return (None, String::new()),
        },
        Some("addRules") => {
            let rule0 = s["rules"].as_array().and_then(|r| r.first());
            let content = rule0.and_then(|r| r["ruleContent"].as_str()).unwrap_or("");
            let tname = rule0.and_then(|r| r["toolName"].as_str()).unwrap_or("this");
            if content.is_empty() {
                format!("Yes, allow all {} {}", tname, scope)
            } else {
                format!("Yes, allow '{}' {}", content, scope)
            }
        }
        Some("addDirectories") => {
            let dir = s["directories"].as_array()
                .and_then(|d| d.first())
                .and_then(|d| d.as_str())
                .unwrap_or("");
            format!("Yes, allow edits in {} {}", dir, scope)
        }
        _ => return (None, String::new()),
    };
    (Some(s), label)
}

// ---------------------------------------------------------------------------
// NDJSON event processing (mirrors Java ChatProcessManager.processEvent)
// ---------------------------------------------------------------------------

fn process_event(
    line: &str,
    java_vm: &Arc<jni::JavaVM>,
    callbacks: &Arc<jni::objects::GlobalRef>,
    last_text_len: &mut usize,
    last_thinking_len: &mut usize,
    tok_base: &mut u64,
    tok_chars: &mut u64,
) {
    let event: serde_json::Value = match serde_json::from_str(line) {
        Ok(v) => v,
        Err(_) => return,
    };
    process_event_value(&event, java_vm, callbacks, last_text_len, last_thinking_len,
                        tok_base, tok_chars);
}

/// Pre-parsed variant shared by the legacy per-turn reader and the persistent
/// reader (which needs the Value first to route control/result events).
fn process_event_value(
    event: &serde_json::Value,
    java_vm: &Arc<jni::JavaVM>,
    callbacks: &Arc<jni::objects::GlobalRef>,
    last_text_len: &mut usize,
    last_thinking_len: &mut usize,
    tok_base: &mut u64,
    tok_chars: &mut u64,
) {
    match event["type"].as_str().unwrap_or("") {
        // Usage/rate-limit signal — forwarded so the GUI can show a warning banner.
        "rate_limit_event" => {
            if let Some(info) = event.get("rate_limit_info") {
                fire_string(java_vm, callbacks, "onRateLimit", &info.to_string());
            }
        }
        "system" => {
            if event["subtype"].as_str() == Some("init") {
                if let Some(sid) = event["session_id"].as_str() {
                    fire_string(java_vm, callbacks, "onSessionId", sid);
                }
                let msg = event["message"].as_str().unwrap_or("Connected");
                fire_string(java_vm, callbacks, "onSystem", msg);
            }
        }
        // Actual Claude CLI --output-format stream-json format.
        // Partial events have cumulative text; compute deltas to avoid duplicates.
        "assistant" => {
            let is_partial = event.get("partial").and_then(|v| v.as_bool()).unwrap_or(false);
            if let Some(content) = event["message"]["content"].as_array() {
                for block in content {
                    match block["type"].as_str().unwrap_or("") {
                        "text" => {
                            if let Some(text) = block["text"].as_str() {
                                let start = (*last_text_len).min(text.len());
                                let new_part = &text[start..];
                                if !new_part.is_empty() {
                                    fire_string(java_vm, callbacks, "onText", new_part);
                                }
                                *last_text_len = text.len();
                            }
                        }
                        "thinking" => {
                            // The CLI strips the reasoning text from stream-json output
                            // (only an encrypted `signature` remains), so `thinking` is
                            // usually an empty string. We still fire onThinking — even
                            // empty — so the GUI shows a "Thought for Ns" marker for the
                            // reasoning that happened (matches the VSCode panel). When the
                            // text IS present we stream the delta as before.
                            let t = block["thinking"].as_str().unwrap_or("");
                            let start = (*last_thinking_len).min(t.len());
                            let new_part = &t[start..];
                            if !new_part.is_empty() || *last_thinking_len == 0 {
                                fire_string(java_vm, callbacks, "onThinking", new_part);
                            }
                            *last_thinking_len = t.len();
                        }
                        "tool_use" if !is_partial => {
                            // Pass name + input so the GUI can show the target file/command
                            // after the verb and render an inline diff for edits.
                            let payload = serde_json::json!({
                                "name": block["name"].as_str().unwrap_or("tool"),
                                "input": block.get("input").cloned().unwrap_or(serde_json::json!({})),
                            });
                            fire_string(java_vm, callbacks, "onToolStart", &payload.to_string());
                        }
                        _ => {}
                    }
                }
            }
            if !is_partial {
                *last_text_len = 0;
                *last_thinking_len = 0;
            }
        }
        // Fine-grained streaming events (only with --include-partial-messages) —
        // used solely to drive the live output-token counter. Text/thinking/tools
        // still render from the complete "assistant" events above.
        "stream_event" => {
            let ev = &event["event"];
            match ev["type"].as_str().unwrap_or("") {
                "message_start" => {
                    *tok_chars = 0;
                    *tok_base = ev["message"]["usage"]["output_tokens"].as_u64().unwrap_or(0);
                    fire_string(java_vm, callbacks, "onTokens", &tok_base.to_string());
                }
                "content_block_delta" => {
                    if ev["delta"]["type"].as_str() == Some("text_delta") {
                        if let Some(txt) = ev["delta"]["text"].as_str() {
                            *tok_chars += txt.chars().count() as u64;
                            let est = *tok_base + *tok_chars / 4;
                            fire_string(java_vm, callbacks, "onTokens", &est.to_string());
                        }
                    }
                }
                "message_delta" => {
                    if let Some(n) = ev["usage"]["output_tokens"].as_u64() {
                        fire_string(java_vm, callbacks, "onTokens", &n.to_string());
                    }
                }
                _ => {}
            }
        }
        _ => {}
    }
}

// ---------------------------------------------------------------------------
// JNI helpers for callbacks
// ---------------------------------------------------------------------------

fn fire_void(
    java_vm: &Arc<jni::JavaVM>,
    callbacks: &Arc<jni::objects::GlobalRef>,
    method: &str,
) {
    let mut env = match java_vm.attach_current_thread() {
        Ok(e) => e,
        Err(_) => return,
    };
    let _ = env.call_method(callbacks.as_ref(), method, "()V", &[]);
}

/// Calls a String-returning Java callback: `String method(String)`. Used for the
/// persistent-mode question card (blocks the calling thread until the user
/// answers — never call from the reader thread). Pending Java exceptions are
/// cleared so they can't poison later JNI calls on this thread.
fn fire_string_ret(
    java_vm: &Arc<jni::JavaVM>,
    callbacks: &Arc<jni::objects::GlobalRef>,
    method: &str,
    value: &str,
) -> Option<String> {
    let mut env = java_vm.attach_current_thread().ok()?;
    let jstr = env.new_string(value).ok()?;
    let jobj = JObject::from(jstr);
    let result = env.call_method(
        callbacks.as_ref(),
        method,
        "(Ljava/lang/String;)Ljava/lang/String;",
        &[JValue::Object(&jobj)],
    );
    let val = match result {
        Ok(v) => v,
        Err(_) => {
            let _ = env.exception_clear();
            return None;
        }
    };
    let obj = val.l().ok()?;
    if obj.is_null() {
        return None;
    }
    let js = JString::from(obj);
    // Bind before returning: the JavaStr temporary borrows `js` and must drop
    // before `js` does (tail-expression drop order would outlive it).
    let out = match env.get_string(&js) {
        Ok(s) => Some(s.into()),
        Err(_) => {
            let _ = env.exception_clear();
            None
        }
    };
    out
}

/// Same as fire_string_ret but `String method(String, String)` — the
/// persistent-mode permission card (toolName, inputJson) → decision string.
fn fire_three_string_ret(
    java_vm: &Arc<jni::JavaVM>,
    callbacks: &Arc<jni::objects::GlobalRef>,
    method: &str,
    a: &str,
    b: &str,
    c: &str,
) -> Option<String> {
    let mut env = java_vm.attach_current_thread().ok()?;
    let ja = env.new_string(a).ok()?;
    let jb = env.new_string(b).ok()?;
    let jc = env.new_string(c).ok()?;
    let joa = JObject::from(ja);
    let job = JObject::from(jb);
    let joc = JObject::from(jc);
    let result = env.call_method(
        callbacks.as_ref(),
        method,
        "(Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;)Ljava/lang/String;",
        &[JValue::Object(&joa), JValue::Object(&job), JValue::Object(&joc)],
    );
    let val = match result {
        Ok(v) => v,
        Err(_) => {
            let _ = env.exception_clear();
            return None;
        }
    };
    let obj = val.l().ok()?;
    if obj.is_null() {
        return None;
    }
    let js = JString::from(obj);
    // Bind before returning: the JavaStr temporary borrows `js` and must drop
    // before `js` does (tail-expression drop order would outlive it).
    let out = match env.get_string(&js) {
        Ok(s) => Some(s.into()),
        Err(_) => {
            let _ = env.exception_clear();
            None
        }
    };
    out
}

fn fire_string(
    java_vm: &Arc<jni::JavaVM>,
    callbacks: &Arc<jni::objects::GlobalRef>,
    method: &str,
    value: &str,
) {
    // Mirror through PHP bridge if connected
    if crate::php_bridge::is_connected() {
        let msg = format!("CHAT:{}:{}", method, value);
        crate::php_bridge::send_line(&msg);
    }
    // JNI callback
    let mut env = match java_vm.attach_current_thread() {
        Ok(e) => e,
        Err(_) => return,
    };
    let jstr = match env.new_string(value) {
        Ok(s) => s,
        Err(_) => return,
    };
    let jobj = JObject::from(jstr);
    let _ = env.call_method(
        callbacks.as_ref(),
        method,
        "(Ljava/lang/String;)V",
        &[JValue::Object(&jobj)],
    );
}
