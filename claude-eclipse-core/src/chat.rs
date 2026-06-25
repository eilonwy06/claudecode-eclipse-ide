use std::io::{BufRead, BufReader};
use std::process::{Command, Stdio};
use std::sync::{Arc, Mutex};
use std::sync::atomic::{AtomicBool, Ordering};

#[cfg(windows)]
use std::os::windows::process::CommandExt;

use jni::objects::{JObject, JValue};

// ---------------------------------------------------------------------------
// Shared mutable state (Arc'd into spawned threads — no raw pointers)
// ---------------------------------------------------------------------------

struct ChatState {
    has_session: bool,
    awaiting: bool,
    cancel: Arc<AtomicBool>,
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
            })),
            callbacks: Arc::new(Mutex::new(None)),
        }
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
        {
            let s = self.state.lock().unwrap();
            if s.awaiting {
                return;
            }
        }

        let (java_vm, callbacks_obj) = match self.callbacks.lock().unwrap().as_ref() {
            Some(cb) => (Arc::clone(&cb.java_vm), Arc::clone(&cb.obj)),
            None => return,
        };

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
        let s = self.state.lock().unwrap();
        s.cancel.store(true, Ordering::Relaxed);
    }

    pub fn reset_session(&self) {
        self.cancel();
        let mut s = self.state.lock().unwrap();
        s.has_session = false;
        s.awaiting = false;
        drop(s);
        self.emit_system("Session reset.");
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
        cmd_args.push("openDiff".into());
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
