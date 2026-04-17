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
    ) {
        {
            let s = self.state.lock().unwrap();
            if s.awaiting {
                return;
            }
        }

        // Snapshot what we need before spawning.
        let has_session = {
            let s = self.state.lock().unwrap();
            s.has_session
        };

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
                    has_session,
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
    has_session: bool,
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
    ];
    if has_session {
        cmd_args.push("-c".into());
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

    // macOS: Eclipse.app launched from Finder has a minimal PATH and can't
    // find `claude` installed under Homebrew/nvm/etc.  Inject the PATH from
    // the user's login shell so bare commands resolve.  Absolute paths are
    // unaffected — the kernel skips PATH lookup when the command contains /.
    if let Some(p) = crate::shell_env::login_shell_path() {
        cmd.env("PATH", p);
    }

    if mcp_port > 0 && !mcp_auth_token.is_empty() {
        // Connect Claude to this instance's MCP server.
        cmd.env("CLAUDE_IDE_PORT", mcp_port.to_string())
           .env("CLAUDE_IDE_AUTH_TOKEN", mcp_auth_token)
           .env("CLAUDE_IDE_NAME", "Eclipse");
    } else {
        // No MCP server running — remove any inherited IDE env vars so Claude
        // does not try to connect to another instance's server and hang.
        cmd.env_remove("CLAUDE_IDE_PORT")
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

    for line in reader.lines() {
        if cancel.load(Ordering::Relaxed) {
            break;
        }
        let line = match line {
            Ok(l) if !l.is_empty() => l,
            _ => continue,
        };
        process_event(&line, java_vm, callbacks, &mut last_text_len);
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
) {
    let event: serde_json::Value = match serde_json::from_str(line) {
        Ok(v) => v,
        Err(_) => return,
    };

    match event["type"].as_str().unwrap_or("") {
        "system" => {
            if event["subtype"].as_str() == Some("init") {
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
                        "tool_use" if !is_partial => {
                            let name = block["name"].as_str().unwrap_or("tool");
                            fire_string(java_vm, callbacks, "onToolStart", name);
                        }
                        _ => {}
                    }
                }
            }
            if !is_partial {
                *last_text_len = 0;
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
