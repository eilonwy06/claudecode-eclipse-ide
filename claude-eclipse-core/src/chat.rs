use std::io::{BufRead, BufReader, Write};
use std::process::Stdio;
use std::sync::{Arc, Mutex};
use std::sync::atomic::{AtomicBool, AtomicU64, Ordering};

#[cfg(windows)]
use std::os::windows::process::CommandExt;

use jni::objects::{JObject, JString, JValue};

/// Materializes the `--mcp-config` value for the `claude` command line.
///
/// **Windows:** inline JSON (`{"mcpServers":…}`) is mangled when the Claude
/// command is a `.cmd`/`.bat` shim — cmd.exe plus the shim's `%*` re-quoting
/// strip the JSON's quotes, so the CLI reads the value as a bogus file path
/// ("MCP config file not found: C:\ws\{mcpServers:…"). That is the BatBadBut
/// class of bug and is why the GUI chat broke with a `claude.cmd` command
/// (issue #64) while a full `.exe` path worked — no arg-quoting scheme survives
/// cmd.exe + `%*` reliably. So we write the JSON to a temp file (keyed by the
/// server port, so concurrent tabs share one identical file) and pass its path;
/// a plain path has no shell-special characters and passes through intact. If
/// the temp write fails we fall back to the inline JSON (the macOS/Linux form,
/// still correct for a `.exe` target).
#[cfg(windows)]
fn mcp_config_value(mcp_port: u16, cfg: String) -> String {
    let path = std::env::temp_dir().join(format!("claude-eclipse-mcp-{mcp_port}.json"));
    match std::fs::write(&path, &cfg) {
        Ok(()) => path.to_string_lossy().into_owned(),
        Err(_) => cfg,
    }
}

/// **macOS and Linux:** unchanged. `Command` hands argv straight to `execvp`
/// with no shell in between, so inline `--mcp-config` JSON has always been
/// passed verbatim and never had the Windows mangling problem — keep it exactly
/// as before.
#[cfg(not(windows))]
fn mcp_config_value(_mcp_port: u16, cfg: String) -> String {
    cfg
}

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
    /// The conversation of a process retired with the browser on, until the next spawn:
    /// a process for that same conversation switches it back on (see [`retire_proc`]).
    chrome_carry: Option<String>,
    /// Remote Control a retired process had on, owed to the process replacing it
    /// (see [`restore_remote_control`]). Taken by the next spawn.
    rc_carry: Option<RcCarry>,
    /// The bridge session this conversation is published as, once Remote Control
    /// is on. Needed to look up the text of a message that arrived from another
    /// device: stdout announces those as `command_lifecycle` and carries only a
    /// uuid, so the words have to be fetched from the session's own event log.
    bridge_session_id: Option<String>,
    /// The workspace the live process was spawned in. Kept because the CLI's
    /// transcript lives under a hash of it, and that transcript is where an
    /// inbound bridge message is read from (`session::message_text_by_uuid`).
    workspace_root: String,
    /// CLI `request_id`s of `can_use_tool` requests we have a card up for.
    ///
    /// A card is a promise to answer exactly one request, and something other
    /// than the user can end that request first — the phone answering it, or the
    /// turn being torn down. Tracking which are outstanding is what lets those
    /// cards be taken down *individually*; without it the only options are
    /// leaving every stale card on screen or clearing all of them, and a turn
    /// with parallel tool calls has several open at once.
    open_cards: std::collections::HashSet<String>,
    /// Requests withdrawn by the CLI while their card was still up. The waiting
    /// thread checks this before writing its `control_response`: the CLI has
    /// stopped listening for that id, so sending one is noise at best.
    cancelled_cards: std::collections::HashSet<String>,
}

/// A live persistent claude process. Stdin writes are serialized through the
/// mutex (user messages, control_responses and interrupts come from different
/// threads); the reader thread owns stdout for the process lifetime.
struct ProcHandle {
    /// `None` once the process is killed: dropping it closes the pipe, and a
    /// stream-json CLI ends when its input does.
    stdin: Mutex<Option<std::process::ChildStdin>>,
    child: Mutex<std::process::Child>,
    /// Session id from the latest init event. Compared against the resume id the
    /// GUI sends with each message to detect tab switches (respawn with --resume).
    session_id: Mutex<Option<String>>,
    /// What the process cannot change about itself once running ([`spawn_signature`]).
    /// A mismatch on the next send replaces it; every other setting is sent to it live.
    spawn_sig: String,
    alive: AtomicBool,
    /// Whether `claude-in-chrome` has been added to this process over
    /// `mcp_set_servers`. Per process, but a process replacing it on the same
    /// conversation gets it back (see [`retire_proc`]).
    chrome_enabled: AtomicBool,
    /// The session this process was spawned to resume, `""` for a new one. What it
    /// is judged by until the CLI's init event supplies `session_id`.
    spawn_resume: String,
    /// The bridge session while this process has Remote Control on (`""` when the
    /// CLI's reply left the id out), `None` while it is off.
    rc_session: Mutex<Option<String>>,
    /// The launch settings in effect on the process now — its spawn values, then
    /// whatever [`apply_live_settings`] has sent it since.
    live: Mutex<LiveSettings>,
    /// Whether [`start_settings_poll`] is already running for this process.
    settings_poll: AtomicBool,
    /// FreeBSD: whether the setup guide has been queued to this process after a
    /// tool hit the fdescfs failure ([`queue_fdescfs_diagnosis`]). Once per process,
    /// so Claude's answer failing the same way cannot set off another.
    fdescfs_diagnosed: AtomicBool,
    /// The model and effort the CLI last reported, so a change made somewhere else
    /// — the phone, claude.ai — can be told from the settings already known here.
    last_applied: Mutex<Option<(String, String)>>,
    /// Whether going back to a Default model can be done on this process.
    ///
    /// The CLI's model reset returns to the model it would pick with no setting at
    /// all, which is the same thing a fresh Default process launches on ONLY when
    /// there is no model in the CLI's own settings (verified both ways: with
    /// `model: opus` set, a bare launch ran opus-5 while the reset gave sonnet-5;
    /// with none set, both gave sonnet-5). So it is allowed exactly when the CLI
    /// reports no model setting, and a respawn covers the other case rather than
    /// leaving the tab saying Default over a different model.
    default_model_live: AtomicBool,
}

impl ProcHandle {
    fn write_line(&self, line: &str) -> std::io::Result<()> {
        let mut guard = self.stdin.lock().unwrap();
        let stdin = guard
            .as_mut()
            .ok_or_else(|| std::io::Error::new(std::io::ErrorKind::BrokenPipe, "claude's input is closed"))?;
        stdin.write_all(line.as_bytes())?;
        stdin.write_all(b"\n")?;
        stdin.flush()
    }

    /// Ends the process for good. Its input is closed first, since nothing else
    /// would close it: the reader thread keeps this handle, and with it the pipe,
    /// alive until the process exits. Then the whole tree is ended, so a process
    /// started through a wrapper is not left running without a parent.
    fn kill(&self) {
        drop(self.stdin.lock().unwrap().take());
        crate::launch::kill_process_tree(&mut self.child.lock().unwrap());
    }

    fn serves(&self, resume_id: &str) -> bool {
        serves_conversation(self.session_id.lock().unwrap().as_deref(), &self.spawn_resume, resume_id)
    }

    /// The conversation this process is on: its session, or before init the one it resumes.
    fn conversation(&self) -> String {
        self.session_id.lock().unwrap().clone().unwrap_or_else(|| self.spawn_resume.clone())
    }

    fn is_dead(&self) -> bool {
        if !self.alive.load(Ordering::Relaxed) {
            return true;
        }
        // try_wait also catches a child that exited before the reader saw EOF.
        self.child.lock().unwrap().try_wait().map(|s| s.is_some()).unwrap_or(true)
    }
}

/// Launch settings the CLI can change on a running process — so a change to one is
/// sent to the process instead of replacing it, the VS Code extension's way.
/// Replacing the process took everything it held down with it: the Remote Control
/// bridge above all. Each control was verified live against CLI 2.1.266:
/// `set_model` (the next turn runs on the new model), `apply_flag_settings`
/// `effortLevel` (low through max all apply), `set_max_thinking_tokens` (0 = no
/// thinking; null = back to the effort-driven default, with `thinking_display`),
/// and `set_permission_mode`.
#[derive(Clone, Debug, PartialEq)]
struct LiveSettings {
    perm_mode: String,
    effort: String,
    model: String,
    thinking: String,
}

impl LiveSettings {
    fn new(perm_mode: &str, effort: &str, model: &str, thinking: &str) -> Self {
        LiveSettings {
            perm_mode: perm_mode.to_string(),
            effort: effort.to_string(),
            model: model.to_string(),
            thinking: thinking.to_string(),
        }
    }

    /// Whether a process now running with `live` can be brought to these settings
    /// without replacing it. Going back to an empty ("Default") model is allowed only
    /// when the CLI has no model setting of its own (`model_reset_live` — see
    /// [`ProcHandle::default_model_live`]); back to a Default effort never is, since
    /// the CLI's reset returns to its settings value rather than to having none
    /// (verified). Taking a concrete model or effort is always fine, and routine — a
    /// Default tab adopts the id of the model its first turn ran on.
    fn reachable_from(&self, live: &LiveSettings, model_reset_live: bool) -> bool {
        !(self.model.is_empty() && !live.model.is_empty() && !model_reset_live)
            && !(self.effort.is_empty() && !live.effort.is_empty())
    }
}

/// The flag that lets the permission mode be switched to `bypassPermissions` ("Auto")
/// on a running process. See where it is passed, in `spawn_persistent`.
const ALLOW_SKIP_FLAG: &str = "--allow-dangerously-skip-permissions";

/// Whether a process for `claude_cmd` is launched able to enter Auto mode: the user's
/// preference, and a CLI new enough to take the flag.
fn allow_skip_flag(claude_cmd: &str) -> bool {
    crate::live_auto_mode() && crate::launch::cli_supports_flag(claude_cmd, ALLOW_SKIP_FLAG)
}

/// What a running process cannot change about itself, so what replaces it when it
/// differs: where it runs and what it talks to. Every launch setting is sent to the
/// running process instead ([`apply_live_settings`]) — Auto included, since the
/// process is launched able to take it. On a CLI too old for [`ALLOW_SKIP_FLAG`],
/// Auto stays a launch-time choice and going into or out of it replaces the process.
/// The one other exception, going back to a Default model or effort, is one-way, so
/// it is [`LiveSettings::reachable_from`]'s to decide.
/// `auto_live` is [`allow_skip_flag`] for this command, passed in rather than looked
/// up here so the rule is a function of its inputs — and so a test does not depend
/// on what happens to be installed on the machine running it.
fn spawn_signature(
    claude_cmd: &str,
    workspace_root: &str,
    mcp_port: u16,
    mcp_auth_token: &str,
    perm_mode: &str,
    auto_live: bool,
) -> String {
    let auto_at_launch = perm_mode == "bypassPermissions" && !auto_live;
    format!("{}|{}|{}|{}|auto_at_launch={}",
            claude_cmd, workspace_root, mcp_port, mcp_auth_token, auto_at_launch)
}

/// The `set_max_thinking_tokens` request for a GUI thinking value: "0" turns thinking
/// off; on returns it to the session default, which leaves thinking to effort (as a
/// spawn without a budget does), with readable summaries when the CLI has them ("2").
fn thinking_request(thinking: &str) -> serde_json::Value {
    match thinking {
        "0" => serde_json::json!({ "subtype": "set_max_thinking_tokens", "max_thinking_tokens": 0 }),
        "2" => serde_json::json!({
            "subtype": "set_max_thinking_tokens",
            "max_thinking_tokens": serde_json::Value::Null,
            "thinking_display": "summarized"
        }),
        _ => serde_json::json!({ "subtype": "set_max_thinking_tokens", "max_thinking_tokens": serde_json::Value::Null }),
    }
}

/// Wraps a live-settings request as a control-request line.
fn live_request_line(request: serde_json::Value) -> String {
    static LIVE_SEQ: AtomicU64 = AtomicU64::new(1);
    if crate::is_debug() {
        eprintln!("[live-settings] {}", request);
    }
    serde_json::json!({
        "type": "control_request",
        "request_id": format!("eclipse-live-{}", LIVE_SEQ.fetch_add(1, Ordering::Relaxed)),
        "request": request
    })
    .to_string()
}

/// Brings a running process's settings to `want` by sending it the control requests
/// for whatever differs, and records them. Called straight ahead of the message the
/// settings are for: the CLI takes its input in order, so that turn already runs
/// with them (verified). Err, with nothing sent, when the settings cannot be reached
/// on this process ([`LiveSettings::reachable_from`]); Err when it no longer takes
/// input. Either way the caller replaces the process.
fn apply_live_settings(p: &ProcHandle, want: &LiveSettings) -> std::io::Result<()> {
    let mut live = p.live.lock().unwrap();
    if *live == *want {
        return Ok(());
    }
    if !want.reachable_from(&live, p.default_model_live.load(Ordering::SeqCst)) {
        return Err(std::io::Error::new(
            std::io::ErrorKind::Unsupported,
            "a running process cannot go back to a Default model or effort",
        ));
    }
    if live.model != want.model {
        // Empty is "Default": null resets the process to the model it would pick with
        // no setting, which is what a fresh Default process launches on.
        let model = if want.model.is_empty() {
            serde_json::Value::Null
        } else {
            serde_json::json!(want.model)
        };
        p.write_line(&live_request_line(serde_json::json!({ "subtype": "set_model", "model": model })))?;
        live.model = want.model.clone();
    }
    if live.effort != want.effort && !want.effort.is_empty() {
        p.write_line(&live_request_line(serde_json::json!({
            "subtype": "apply_flag_settings",
            "settings": { "effortLevel": want.effort }
        })))?;
        live.effort = want.effort.clone();
    }
    if live.thinking != want.thinking {
        p.write_line(&live_request_line(thinking_request(&want.thinking)))?;
        live.thinking = want.thinking.clone();
    }
    if live.perm_mode != want.perm_mode && !want.perm_mode.is_empty() {
        p.write_line(&live_request_line(serde_json::json!({
            "subtype": "set_permission_mode",
            "mode": want.perm_mode
        })))?;
        live.perm_mode = want.perm_mode.clone();
    }
    Ok(())
}

/// Request ids of the settings read-back, so the reader can tell its reply apart.
const SETTINGS_PREFIX: &str = "eclipse-settings-";

/// How often the settings are read back while Remote Control is on. A second, so a
/// model or effort picked on another device lands here about as fast as it would have
/// been typed here. It costs one line of JSON down a pipe to a process on this machine:
/// no network, no tokens, and answered even mid-turn (verified). Only while a bridge is
/// up — nothing else changes these behind the view's back.
const SETTINGS_POLL: std::time::Duration = std::time::Duration::from_secs(1);

/// One "what are your settings" control request.
fn settings_request_line() -> String {
    static SETTINGS_SEQ: AtomicU64 = AtomicU64::new(1);
    serde_json::json!({
        "type": "control_request",
        "request_id": format!("{}{}", SETTINGS_PREFIX, SETTINGS_SEQ.fetch_add(1, Ordering::Relaxed)),
        "request": { "subtype": "get_settings" }
    })
    .to_string()
}

/// Reads the CLI's settings back every couple of seconds while Remote Control is on,
/// so a model or effort change made on the phone or claude.ai shows up in this view
/// rather than only in the next turn's status bar.
///
/// A poll, because there is nothing to listen to: the CLI announces a permission-mode
/// change itself (`system/status`) but says nothing at all when the model or effort
/// changes, and writes nothing to disk either (verified). `get_settings` is a local
/// control request — no turn, no quota. The VS Code extension has no equivalent poll
/// because its own picker reads the user's settings file, which it writes and watches;
/// ours is per conversation, so the process is the only thing that knows.
fn start_settings_poll(proc: Arc<ProcHandle>) {
    if proc.settings_poll.swap(true, Ordering::SeqCst) {
        return;
    }
    std::thread::Builder::new()
        .name("claude-settings-poll".into())
        .spawn(move || {
            loop {
                std::thread::sleep(SETTINGS_POLL);
                let on = proc.rc_session.lock().unwrap().is_some();
                if !on || proc.is_dead() {
                    break;
                }
                if proc.write_line(&settings_request_line()).is_err() {
                    break;
                }
            }
            proc.last_applied.lock().unwrap().take();
            proc.settings_poll.store(false, Ordering::SeqCst);
        })
        .ok();
}

/// What moved in a `get_settings` reply since the last one, as the JSON the page
/// receives — `None` for the first reply (that one is the baseline) and when nothing
/// moved. The new values become this process's own, so the next send carries them
/// instead of putting the old ones back.
fn settings_change(proc: &ProcHandle, response: &serde_json::Value) -> Option<String> {
    // `effective` is what the CLI's own settings say; no model there means its reset
    // and a fresh launch agree, which is what makes a live switch back to Default safe
    // (see `ProcHandle::default_model_live`). Re-read every time: the file can change.
    proc.default_model_live.store(
        response["effective"]["model"].as_str().unwrap_or("").is_empty(),
        Ordering::SeqCst,
    );
    let applied = &response["applied"];
    let model = applied["model"].as_str().unwrap_or("").to_string();
    // Absent on a model with no effort ladder (haiku), which is not a change to report.
    let effort = applied["effort"].as_str().unwrap_or("").to_string();
    if model.is_empty() {
        return None;
    }
    let previous = proc.last_applied.lock().unwrap().replace((model.clone(), effort.clone()))?;
    if previous.0 == model && previous.1 == effort {
        return None;
    }
    {
        let mut live = proc.live.lock().unwrap();
        live.model = model.clone();
        if !effort.is_empty() {
            live.effort = effort.clone();
        }
    }
    if crate::is_debug() {
        eprintln!("[live-settings] changed elsewhere: model={:?} effort={:?}", model, effort);
    }
    Some(serde_json::json!({ "model": model, "effort": effort }).to_string())
}

struct CallbacksRef {
    java_vm: Arc<jni::JavaVM>,
    obj: Arc<jni::objects::GlobalRef>, // Arc so we can share without cloning GlobalRef
}

/// Request ids of the `mcp_set_servers` that adds `claude-in-chrome`, so the reader
/// can tell its reply from every other control request's.
const CHROME_ON_PREFIX: &str = "eclipse-chrome-on-";

/// Whether a process serves the conversation `resume_id` names. A process has no
/// session id until the CLI's init event arrives — one `ensure_process` has only
/// just started, when the send right behind it checks — so until then it is judged
/// by the session it was spawned to resume. Judging it by the missing id replaced
/// that process on the spot, and with it the browser the send had just switched on.
fn serves_conversation(session_id: Option<&str>, spawn_resume: &str, resume_id: &str) -> bool {
    match session_id {
        Some(sid) => !resume_id.is_empty() && sid == resume_id,
        None => spawn_resume == resume_id,
    }
}

/// Takes a process out of service. The browser belongs to the conversation, not to
/// the process: a respawn for a model or effort change, or after a deleted message,
/// must not quietly take it away while the banner still says connected. So when it
/// was on, the conversation is remembered for [`restore_chrome`]. Clearing the flag
/// first keeps the reader's EOF from reporting the browser gone in the meantime.
///
/// Remote Control is carried the same way, and for the same reason: killing the
/// process that holds the bridge leaves the phone and claude.ai on a session nothing
/// answers, while the tab still says Remote Control is active.
fn retire_proc(state: &Mutex<ChatState>, p: &ProcHandle) {
    if p.chrome_enabled.swap(false, Ordering::SeqCst) {
        state.lock().unwrap().chrome_carry = Some(p.conversation());
    }
    if let Some(bridge_session_id) = p.rc_session.lock().unwrap().take() {
        let conversation = p.conversation();
        let mut s = state.lock().unwrap();
        s.rc_carry = Some(RcCarry { conversation, bridge_session_id });
        // Nothing holds that bridge until the replacement takes it back over.
        s.bridge_session_id = None;
    }
    p.alive.store(false, Ordering::Relaxed);
    p.kill();
}

/// Remote Control owed to the process replacing a retired one.
struct RcCarry {
    /// The conversation the bridge belongs to.
    conversation: String,
    /// The bridge session to take back over.
    bridge_session_id: String,
}

/// Gives a just-spawned process the Remote Control a retired one had, when it
/// carries the same conversation: it takes over the same bridge session, so
/// whoever is on the phone or claude.ai stays on it. The reply arrives on the
/// reader like any switch-on's. Any other conversation starts without it, and the
/// page is told the bridge is gone.
fn restore_remote_control(
    state: &Mutex<ChatState>,
    p: &ProcHandle,
    resume_id: &str,
    java_vm: &Arc<jni::JavaVM>,
    callbacks: &Arc<jni::objects::GlobalRef>,
) {
    let Some(carry) = state.lock().unwrap().rc_carry.take() else { return };
    let gone = || {
        fire_string(java_vm, callbacks, "onRemoteControl", &crate::bridge::rc_state_json("failed"));
    };
    if carry.conversation != resume_id {
        if crate::is_debug() {
            eprintln!("[remote-control] not carried: bridge was on {:?}, new process is on {:?}",
                      carry.conversation, resume_id);
        }
        gone();
        return;
    }
    static REATTACH_SEQ: AtomicU64 = AtomicU64::new(1);
    let (_, line) = crate::bridge::rc_reattach_line(
        REATTACH_SEQ.fetch_add(1, Ordering::Relaxed),
        &carry.bridge_session_id,
    );
    if crate::is_debug() {
        eprintln!("[remote-control] carrying bridge {:?} to the replacement process", carry.bridge_session_id);
    }
    if p.write_line(&line).is_err() {
        gone();
    }
}

/// Gives a just-spawned process the browser a retired one had, when it carries the
/// same conversation. The model already has the browser instruction from the
/// transcript, so only the server is added. Any other conversation starts without
/// it, and the banner is told.
fn restore_chrome(
    state: &Mutex<ChatState>,
    p: &ProcHandle,
    claude_cmd: &str,
    resume_id: &str,
    java_vm: &Arc<jni::JavaVM>,
    callbacks: &Arc<jni::objects::GlobalRef>,
) {
    let Some(conversation) = state.lock().unwrap().chrome_carry.take() else { return };
    let emit = |json: &str| fire_string(java_vm, callbacks, "onBrowserState", json);
    if !conversation.is_empty() && conversation == resume_id {
        p.chrome_enabled.store(true, Ordering::SeqCst);
        write_chrome_on(p, &crate::chrome::server_config(claude_cmd), &emit);
    } else {
        emit(r#"{"status":"disconnected"}"#);
    }
}

/// Writes the `mcp_set_servers` that adds `claude-in-chrome` to `p`, whose flag the
/// caller has set. The banner reads "Connecting to browser…" until the CLI answers —
/// the reader turns the reply into connected or an error (see `chrome_set_error`).
/// False when the process no longer takes input.
fn write_chrome_on(p: &ProcHandle, server_config: &serde_json::Value, emit: &dyn Fn(&str)) -> bool {
    emit(r#"{"status":"connecting"}"#);
    static CHROME_SEQ: AtomicU64 = AtomicU64::new(1);
    let req_id = format!("{CHROME_ON_PREFIX}{}", CHROME_SEQ.fetch_add(1, Ordering::Relaxed));
    let msg = serde_json::json!({
        "type": "control_request",
        "request_id": req_id,
        "request": { "subtype": "mcp_set_servers", "servers": { "claude-in-chrome": server_config } }
    });
    if p.write_line(&msg.to_string()).is_err() {
        p.chrome_enabled.store(false, Ordering::SeqCst);
        emit(r#"{"status":"disconnected"}"#);
        return false;
    }
    true
}

/// What the extension tells the model when the browser is disconnected, verbatim.
const BROWSER_DISCONNECTED_NOTE: &str =
    "[Browser disconnected: The browser connection has been closed. Browser tools are no longer available.]";

/// The failure in a reply to adding `claude-in-chrome`, worded as the extension
/// words it (`name: reason`, comma-joined), or `None` when the server was added.
fn chrome_set_error(inner: &serde_json::Value) -> Option<String> {
    if inner["subtype"].as_str() == Some("error") {
        let e = inner["error"].as_str().unwrap_or("").trim();
        return Some(if e.is_empty() { "Unknown error".to_string() } else { e.to_string() });
    }
    let errors = inner["response"]["errors"].as_object()?;
    if errors.is_empty() {
        return None;
    }
    Some(
        errors
            .iter()
            .map(|(k, v)| format!("{k}: {}", v.as_str().map(str::to_string).unwrap_or_else(|| v.to_string())))
            .collect::<Vec<_>>()
            .join(", "),
    )
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
                chrome_carry: None,
                rc_carry: None,
                bridge_session_id: None,
                workspace_root: String::new(),
                open_cards: std::collections::HashSet::new(),
                cancelled_cards: std::collections::HashSet::new(),
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
        images_json: String,
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
                        "message": { "role": "user", "content": build_user_content(&message, &images_json) }
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
                resume_id, perm_mode, effort, model, thinking, images_json, java_vm, callbacks_obj,
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
                    &images_json,
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

    /// Renames the conversation this manager's live process is on, via the CLI's
    /// `rename_session` control request (same path the VSCode plugin uses). The CLI
    /// appends a `custom-title` event to the session's own jsonl, so /resume and
    /// every other Claude Code client see the new title too. Returns false when
    /// this manager has no live process on `session_id` — the caller then falls
    /// back to the offline rename (session::rename_session_offline).
    pub fn rename_session(&self, session_id: &str, title: &str) -> bool {
        if session_id.is_empty() || title.is_empty() {
            return false;
        }
        let proc = self.state.lock().unwrap().proc.clone();
        let Some(p) = proc else { return false };
        if p.is_dead() || p.session_id.lock().unwrap().as_deref() != Some(session_id) {
            return false;
        }
        static REN_SEQ: AtomicU64 = AtomicU64::new(1);
        let req_id = format!("eclipse-ren-{}", REN_SEQ.fetch_add(1, Ordering::Relaxed));
        let msg = serde_json::json!({
            "type": "control_request",
            "request_id": req_id,
            "request": { "subtype": "rename_session", "title": title }
        });
        p.write_line(&msg.to_string()).is_ok()
    }

    /// Stops one specific background agent (an Agent/Task tool call with
    /// run_in_background) via its own internal task id — NOT its tool_use id; those
    /// are two different ids for the same agent (see chat.js's agentLogs.taskId,
    /// captured from the "task_started" system event while it's still running,
    /// since "task_notification" only arrives on completion — too late to stop
    /// anything). Wire shape confirmed empirically, not documented in any public
    /// Claude Agent SDK reference at the time this was written:
    /// {"subtype":"stop_task","task_id":…}, same envelope as every other
    /// control_request here. Returns false when there's no live process.
    pub fn stop_task(&self, task_id: &str) -> bool {
        if task_id.is_empty() {
            return false;
        }
        let proc = self.state.lock().unwrap().proc.clone();
        let Some(p) = proc else { return false };
        if p.is_dead() {
            return false;
        }
        static STOP_SEQ: AtomicU64 = AtomicU64::new(1);
        let req_id = format!("eclipse-stop-{}", STOP_SEQ.fetch_add(1, Ordering::Relaxed));
        let msg = serde_json::json!({
            "type": "control_request",
            "request_id": req_id,
            "request": { "subtype": "stop_task", "task_id": task_id }
        });
        p.write_line(&msg.to_string()).is_ok()
    }

    /// Makes sure this tab has a live CLI process, spawning one if it does not,
    /// **without sending anything**.
    ///
    /// Our process starts lazily, on the first message — which is why Remote
    /// Control used to need a conversation before it could be switched on. It
    /// does not have to: the CLI answers a control request perfectly well before
    /// any turn has happened (verified against 2.1.251, which replies with the
    /// bridge session and no turn at all). Starting the process is the only
    /// missing piece, so this supplies it and nothing else — no user message, no
    /// `onStreamStart`, no turn.
    ///
    /// Reuse follows the same rule as [`send_message`]: same launch settings and
    /// the same conversation. A process that would be replaced by the next send
    /// is replaced here too, so both paths agree on what "the tab's process" is
    /// rather than drifting apart.
    ///
    /// **Blocking** — spawns a child process. Call it off the UI thread.
    /// Returns false when the spawn failed.
    #[allow(clippy::too_many_arguments)]
    pub fn ensure_process(
        &self,
        claude_cmd: String,
        workspace_root: String,
        mcp_port: u16,
        mcp_auth_token: String,
        resume_id: String,
        perm_mode: String,
        effort: String,
        model: String,
        thinking: String,
    ) -> bool {
        let (java_vm, callbacks) = {
            let guard = self.callbacks.lock().unwrap();
            match guard.as_ref() {
                Some(cb) => (Arc::clone(&cb.java_vm), Arc::clone(&cb.obj)),
                None => return false,
            }
        };

        let sig = spawn_signature(&claude_cmd, &workspace_root, mcp_port, &mcp_auth_token, &perm_mode,
                                  allow_skip_flag(&claude_cmd));
        let want = LiveSettings::new(&perm_mode, &effort, &model, &thinking);

        let mut proc_opt = { self.state.lock().unwrap().proc.clone() };
        let reusable = proc_opt.as_ref().is_some_and(|p| {
            !p.is_dead() && p.spawn_sig == sig && p.serves(&resume_id)
                && apply_live_settings(p, &want).is_ok()
        });
        if reusable {
            return true;
        }
        if let Some(p) = proc_opt.take() {
            retire_proc(&self.state, &p);
        }

        match spawn_persistent(
            &claude_cmd, &workspace_root, mcp_port, &mcp_auth_token,
            &resume_id, &perm_mode, &effort, &model, &thinking, sig,
            Arc::clone(&self.state), Arc::clone(&java_vm), Arc::clone(&callbacks),
        ) {
            Ok(p) => {
                self.state.lock().unwrap().proc = Some(Arc::clone(&p));
                restore_chrome(&self.state, &p, &claude_cmd, &resume_id, &java_vm, &callbacks);
                restore_remote_control(&self.state, &p, &resume_id, &java_vm, &callbacks);
                true
            }
            Err(e) => {
                fire_string(&java_vm, &callbacks, "onError",
                            &format!("Failed to launch Claude: {}", e));
                false
            }
        }
    }
    /// Turns Remote Control on or off for this manager's live process.
    ///
    /// Remote Control is what makes a conversation the *same* conversation
    /// everywhere — the CLI opens an outbound bridge, and anything typed here,
    /// on claude.ai, or on the phone lands in all of them. It is emphatically
    /// not teleport, which takes a one-way copy and then diverges.
    ///
    /// Reached by a control request over the stream-json connection this process
    /// already has, so there is no second process and no new transport. The
    /// reply arrives asynchronously on the event loop (see `bridge::rc_owns_response`),
    /// carrying `session_url` — the web address of this conversation, which is
    /// not derivable from anything we hold and must be read from that reply.
    ///
    /// Returns false only when there is no live process to ask.
    pub fn remote_control(&self, enabled: bool) -> bool {
        if !enabled {
            // Switched off before a replacement took it back over: nothing is owed.
            self.state.lock().unwrap().rc_carry = None;
        }
        let proc = self.state.lock().unwrap().proc.clone();
        let Some(p) = proc else { return false };
        if p.is_dead() {
            return false;
        }
        static RC_SEQ: AtomicU64 = AtomicU64::new(1);
        let (_req_id, line) =
            crate::bridge::rc_request_line(RC_SEQ.fetch_add(1, Ordering::Relaxed), enabled);
        p.write_line(&line).is_ok()
    }

    /// Applies this tab's launch settings to its live process NOW, instead of waiting
    /// for the next message. A model or effort change belongs to the conversation the
    /// moment it is made: the status bar shows it, and under Remote Control the phone
    /// and claude.ai are told by the CLI itself, which cannot happen while the change
    /// is still sitting in the view. The permission mode has always worked this way.
    ///
    /// False when there is no live process, or when the change needs a new one — the
    /// next message makes it, as before.
    pub fn apply_settings_now(&self, perm_mode: &str, effort: &str, model: &str, thinking: &str) -> bool {
        let proc = self.state.lock().unwrap().proc.clone();
        let Some(p) = proc else { return false };
        if p.is_dead() {
            return false;
        }
        apply_live_settings(&p, &LiveSettings::new(perm_mode, effort, model, thinking)).is_ok()
    }

    /// Whether switching this conversation back to a Default model would have to
    /// replace its process — which takes its Remote Control session down with it, so
    /// the view asks first. False when there is nothing running, and false when the
    /// switch can be made on the running process (see
    /// [`ProcHandle::default_model_live`]).
    pub fn default_model_restarts(&self) -> bool {
        let proc = self.state.lock().unwrap().proc.clone();
        let Some(p) = proc else { return false };
        if p.is_dead() {
            return false;
        }
        !p.live.lock().unwrap().model.is_empty() && !p.default_model_live.load(Ordering::SeqCst)
    }

    /// Switches the permission mode of this manager's live process via the CLI's
    /// `set_permission_mode` control request. The spawn-time `--permission-mode`
    /// flag only covers the first launch, so a mid-conversation change (the GUI's
    /// per-tab mode dropdown) has to be pushed here to take effect without a
    /// respawn. Returns false when there's no live process — the caller doesn't
    /// need to do anything in that case, since the next spawn passes the mode as
    /// the launch flag anyway.
    pub fn set_permission_mode(&self, mode: &str) -> bool {
        if mode.is_empty() {
            return false;
        }
        let proc = self.state.lock().unwrap().proc.clone();
        let Some(p) = proc else { return false };
        if p.is_dead() {
            return false;
        }
        static MODE_SEQ: AtomicU64 = AtomicU64::new(1);
        let req_id = format!("eclipse-mode-{}", MODE_SEQ.fetch_add(1, Ordering::Relaxed));
        let msg = serde_json::json!({
            "type": "control_request",
            "request_id": req_id,
            "request": { "subtype": "set_permission_mode", "mode": mode }
        });
        let ok = p.write_line(&msg.to_string()).is_ok();
        if ok {
            // So the next send does not send it again.
            p.live.lock().unwrap().perm_mode = mode.to_string();
        }
        ok
    }

    /// Adds the `claude-in-chrome` MCP server to this manager's live process via
    /// the CLI's `mcp_set_servers` control request — how the VS Code extension
    /// switches the browser on mid-conversation. The `eclipse` server from
    /// `--mcp-config` stays connected alongside it (verified against 2.1.266).
    /// Returns 1 when this call switched it on, 0 when it already was on, and -1
    /// when there is no live process.
    pub fn enable_chrome(&self, server_config: &serde_json::Value) -> i32 {
        let proc = self.state.lock().unwrap().proc.clone();
        let Some(p) = proc else { return -1 };
        if p.is_dead() {
            return -1;
        }
        if p.chrome_enabled.swap(true, Ordering::SeqCst) {
            return 0;
        }
        if write_chrome_on(&p, server_config, &|json| self.emit_browser_state(json)) {
            1
        } else {
            -1
        }
    }

    /// Takes `claude-in-chrome` back out of this manager's live process — the browser
    /// banner's ×, the extension's `disableChromeMcp`. An empty `mcp_set_servers`
    /// removes only the servers set that way; the `eclipse` server from `--mcp-config`
    /// stays connected (verified against 2.1.266).
    ///
    /// The model is told with the extension's own notice, sent the way the extension
    /// sends it: the CLI starts a turn on it and Claude acknowledges the disconnect
    /// right away, so the conversation records that the browser was closed on
    /// purpose (verified against 2.1.266). The reader opens that turn in the view
    /// like any queued one. Held back until the next message instead
    /// (`shouldQuery:false`), it arrived alongside that message and read as the
    /// browser having dropped on its own.
    ///
    /// Clearing `chrome_enabled` is what makes a later `@browser` switch the browser
    /// on again and resend the instruction. Returns whether the browser was on.
    pub fn disable_chrome(&self) -> bool {
        let proc = self.state.lock().unwrap().proc.clone();
        let was_on = proc
            .as_ref()
            .map(|p| p.chrome_enabled.swap(false, Ordering::SeqCst))
            .unwrap_or(false);
        // A respawn not yet followed by a spawn still owes its replacement the browser.
        let was_on = self.state.lock().unwrap().chrome_carry.take().is_some() || was_on;
        if let Some(p) = proc.filter(|p| was_on && !p.is_dead()) {
            static OFF_SEQ: AtomicU64 = AtomicU64::new(1);
            let off = serde_json::json!({
                "type": "control_request",
                "request_id": format!("eclipse-chrome-off-{}", OFF_SEQ.fetch_add(1, Ordering::Relaxed)),
                "request": { "subtype": "mcp_set_servers", "servers": {} }
            });
            let note = serde_json::json!({
                "type": "user",
                "session_id": "",
                "parent_tool_use_id": null,
                "isSynthetic": true,
                "message": { "role": "user", "content": BROWSER_DISCONNECTED_NOTE }
            });
            if p.write_line(&off.to_string()).is_ok() {
                let _ = p.write_line(&note.to_string());
            }
        }
        self.emit_browser_state(r#"{"status":"disconnected"}"#);
        was_on
    }

    /// Sends one of the MCP servers window's requests (`mcp_status`, `mcp_toggle`, …)
    /// to this manager's live process, under the page's `token`. The reply reaches
    /// Java as `onMcp`. False when there is no live process, or the request is not
    /// one the window may send ([`crate::mcp_servers::request_line`]).
    pub fn mcp_request(&self, token: &str, request: &str) -> bool {
        let Ok(request) = serde_json::from_str::<serde_json::Value>(request) else { return false };
        let Some(line) = crate::mcp_servers::request_line(token, &request) else { return false };
        let proc = self.state.lock().unwrap().proc.clone();
        let Some(p) = proc else { return false };
        if p.is_dead() {
            return false;
        }
        p.write_line(&line).is_ok()
    }

    fn emit_browser_state(&self, json: &str) {
        let guard = self.callbacks.lock().unwrap();
        if let Some(cb) = guard.as_ref() {
            fire_string(&cb.java_vm, &cb.obj, "onBrowserState", json);
        }
    }

    fn emit_remote_control(&self, json: &str) {
        let guard = self.callbacks.lock().unwrap();
        if let Some(cb) = guard.as_ref() {
            fire_string(&cb.java_vm, &cb.obj, "onRemoteControl", json);
        }
    }

    /// Drops the conversation process but KEEPS the conversation: `has_session`
    /// is left alone and no "Session reset." line is emitted, so the next send —
    /// which still carries the tab's session id as `resume_id` — re-spawns with
    /// `--resume` and rebuilds context from the transcript on disk.
    ///
    /// Needed after the transcript is edited (a message deleted): a live process
    /// keeps its own in-memory copy of the conversation, so without this the
    /// deleted text stays in context and can be written back the moment anything
    /// quotes it. `--resume` is driven purely by a non-empty `resume_id`, so
    /// dropping the process is enough to force the re-read.
    pub fn restart_process(&self) {
        let proc = {
            let mut s = self.state.lock().unwrap();
            if !s.persistent {
                return; // spawn-per-message path has nothing to drop
            }
            s.awaiting = false;
            s.proc.take()
        };
        // The browser stays: the next send resumes this conversation and switches it back on.
        if let Some(p) = proc {
            retire_proc(&self.state, &p);
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
                retire_proc(&self.state, &p);
            }
            // A new conversation starts without the browser.
            if self.state.lock().unwrap().chrome_carry.take().is_some() {
                self.emit_browser_state(r#"{"status":"disconnected"}"#);
            }
            // Nor with Remote Control: that bridge session is the old conversation's.
            if self.state.lock().unwrap().rc_carry.take().is_some() {
                self.emit_remote_control(&crate::bridge::rc_state_json("failed"));
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
        images_json: String,
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

                let sig = spawn_signature(&claude_cmd, &workspace_root, mcp_port, &mcp_auth_token, &perm_mode,
                                          allow_skip_flag(&claude_cmd));
                let want = LiveSettings::new(&perm_mode, &effort, &model, &thinking);

                // Reuse only when the process is alive, has nothing in its spawn
                // signature that differs (the rest is sent to it live), and carries
                // the conversation the GUI is addressing:
                //  - same tab      → resume_id == live session id
                //  - New Chat      → resume_id empty but a session exists → respawn fresh
                //  - tab switch    → different resume_id → respawn with --resume
                let mut proc_opt = { state.lock().unwrap().proc.clone() };
                let reusable = match &proc_opt {
                    Some(p) => {
                        if p.is_dead() || p.spawn_sig != sig || !p.serves(&resume_id)
                            || apply_live_settings(p, &want).is_err()
                        {
                            retire_proc(&state, p);
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
                                restore_chrome(&state, &p, &claude_cmd, &resume_id, &java_vm, &callbacks);
                                restore_remote_control(&state, &p, &resume_id, &java_vm, &callbacks);
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
                    "message": { "role": "user", "content": build_user_content(&message, &images_json) }
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

/// Builds the `message.content` for a user turn. With nothing attached it's a
/// plain string (unchanged wire format); otherwise it's the Anthropic
/// content-block array — a text block (omitted when empty) followed by each
/// attachment in order. `images_json` is a JSON array whose entries are either a
/// pasted image as `{"media_type","data"}` (data = raw base64), or a ready block:
/// `{"type":"document",…}` for an uploaded file, `{"type":"text","text"}` for
/// context such as the browser blocks. Malformed / empty input degrades to the
/// plain-string form.
fn build_user_content(message: &str, images_json: &str) -> serde_json::Value {
    let items: Vec<serde_json::Value> = if images_json.trim().is_empty() {
        Vec::new()
    } else {
        serde_json::from_str(images_json).unwrap_or_default()
    };
    let mut content: Vec<serde_json::Value> = Vec::new();
    if !message.is_empty() {
        content.push(serde_json::json!({ "type": "text", "text": message }));
    }
    let mut attached = 0;
    for item in &items {
        let block = match item.get("type").and_then(|v| v.as_str()) {
            Some("document") => {
                let src = &item["source"];
                let valid = matches!(src["type"].as_str(), Some("text") | Some("base64"))
                    && src["data"].as_str().is_some_and(|d| !d.is_empty());
                valid.then(|| item.clone())
            }
            Some("text") => item["text"]
                .as_str()
                .filter(|t| !t.is_empty())
                .map(|t| serde_json::json!({ "type": "text", "text": t })),
            Some(_) => None,
            None => {
                let data = item.get("data").and_then(|v| v.as_str()).unwrap_or("");
                let media_type = item.get("media_type").and_then(|v| v.as_str()).unwrap_or("image/png");
                (!data.is_empty()).then(|| serde_json::json!({
                    "type": "image",
                    "source": { "type": "base64", "media_type": media_type, "data": data }
                }))
            }
        };
        if let Some(b) = block {
            content.push(b);
            attached += 1;
        }
    }
    // Nothing valid attached → the plain string.
    if attached == 0 {
        return serde_json::Value::String(message.to_string());
    }
    serde_json::Value::Array(content)
}

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
    _images_json: &str,   // legacy spawn-per-message path passes the message as a -p arg; images are GUI-only (persistent path)
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
    // Effort level from the GUI meter (low | medium | high | xhigh | max).
    if !effort.is_empty() {
        cmd_args.push("--effort".into());
        cmd_args.push(effort.to_string());
    }
    // Ask for readable reasoning summaries. Since model generation 4.7 the CLI
    // defaults thinking.display to "omitted", which streams a thinking block whose
    // text is an empty string (only an encrypted signature) — that's why the GUI's
    // expandable "Thought for Ns" block went dead. Gated on thinking=="2", which
    // Java sets only when it has SEEN this flag in the installed binary: it is
    // undocumented (absent from --help) and an unknown option makes the CLI exit
    // immediately, which would break chat outright on an older CLI.
    if thinking == "2" {
        cmd_args.push("--thinking-display".into());
        cmd_args.push("summarized".into());
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
        cmd_args.push(mcp_config_value(mcp_port, cfg));

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

    // crate::launch resolves the command (PATH + PATHEXT for a bare `claude`)
    // and, for a `.cmd`/`.bat` shim, drives cmd.exe with a raw_arg command line
    // — Rust's own BatBadBut `"`-doubling would corrupt the --mcp-config JSON
    // (it arrives as {mcpServers:{…}} and the CLI reads it as a bogus file path).
    let mut cmd = crate::launch::claude_command(claude_cmd, &cmd_args);
    cmd.current_dir(workspace_root)
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
    // which suppresses thinking even at high --effort (verified). On ("1"/"2") = leave
    // it to effort, which DOES trigger thinking on its own (re-verified 2026-07-29 on
    // Opus 5: --effort high with MAX_THINKING_TOKENS unset yields a populated thinking
    // block). Don't set a positive budget here — it would override the effort ladder's
    // own allocation.
    if thinking == "0" {
        cmd.env("MAX_THINKING_TOKENS", "0");
    }

    // This path is `-p` too, so its sessions would be tagged `sdk-cli` and hidden
    // from `--resume`/`/resume` as well. Same reasoning as spawn_persistent.
    cmd.env("CLAUDE_CODE_ENTRYPOINT", "claude-eclipse-ide");

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

    // Tracks cumulative text already sent per conversation LINEAGE, so deltas from
    // partial assistant events are computed correctly — keyed by parent_tool_use_id
    // ("" for the top-level conversation). A subagent (Task/Agent tool) multiplexes its
    // OWN assistant events onto this same stream, each cumulative independently of the
    // top-level one; a single shared counter would corrupt delta math for whichever
    // lineage didn't own it at the moment (see process_event_value).
    let mut cursors: std::collections::HashMap<String, (usize, usize, u64)> = std::collections::HashMap::new();
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
        process_event(&line, java_vm, callbacks, &mut cursors, &mut tok_base, &mut tok_chars);
    }

    let exit_ok = if cancel.load(Ordering::Relaxed) {
        crate::launch::kill_process_tree(&mut child);
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
    // Lets the mode be switched to bypassPermissions ("Auto") on the running process,
    // which the CLI otherwise refuses — the VS Code SDK passes this for the same
    // reason. It ALLOWS that mode; it does not enter it, and it does not loosen the
    // others: verified, a Write in Manual mode still comes to the card, and only after
    // a switch to Auto does it run unasked. Gated twice: a CLI that does not know the
    // flag would abort on it, and the preference behind `live_auto_mode` exists because
    // this is also what lets another device put the conversation into Auto.
    if allow_skip_flag(claude_cmd) {
        cmd_args.push(ALLOW_SKIP_FLAG.into());
    }
    if !effort.is_empty() {
        cmd_args.push("--effort".into());
        cmd_args.push(effort.to_string());
    }
    // See run_turn: "2" = thinking on AND the installed binary advertises
    // --thinking-display, so summaries are safe to request. Without it the CLI
    // defaults to "omitted" and the thinking text arrives empty.
    if thinking == "2" {
        cmd_args.push("--thinking-display".into());
        cmd_args.push("summarized".into());
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
        cmd_args.push(mcp_config_value(mcp_port, cfg));

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

    // See crate::launch: PATH/PATHEXT resolution + cmd.exe raw_arg for `.cmd`
    // shims so the --mcp-config JSON isn't mangled by Rust's BatBadBut escaping.
    let mut cmd = crate::launch::claude_command(claude_cmd, &cmd_args);
    cmd.current_dir(workspace_root)
        .stdin(Stdio::piped())
        .stdout(Stdio::piped())
        .stderr(Stdio::piped());

    #[cfg(windows)]
    cmd.creation_flags(0x08000000); // CREATE_NO_WINDOW

    for (k, v) in crate::shell_env::captured_env().to_inject() {
        cmd.env(k, v);
    }

    // Thinking off is not MAX_THINKING_TOKENS=0 here, unlike run_turn: the CLI keeps
    // thinking off for a session launched with it disabled, so it could never be
    // switched back on live. It is switched off over stdin instead, the moment the
    // process is up (below), which lands before any turn.

    // File checkpointing is off by default in -p/SDK mode; without this the CLI
    // writes no file-history-snapshot entries and the GUI's Rewind cannot
    // restore code (it can still fork the conversation).
    cmd.env("CLAUDE_CODE_ENABLE_SDK_FILE_CHECKPOINTING", "1");

    // Make these conversations visible to the CLI's own history (`claude --resume`
    // and `/resume` inside a Terminal session).
    //
    // The CLI's resume picker drops any session whose first recorded `entrypoint`
    // is one of {sdk-cli, sdk-ts, sdk-py}. `-p --input-format stream-json` is the
    // SDK invocation, so our sessions are stamped `sdk-cli` and vanish, while
    // Terminal sessions (`cli`) stay listed — that is the whole asymmetry.
    //
    // Setting this to `"cli"` does NOT work, and that is why the earlier attempt
    // failed: the CLI normalizes the variable at startup and specifically rewrites
    // the pair (`cli` + SDK invocation) back to `sdk-cli`. Every OTHER value is
    // passed through verbatim, and an entrypoint the CLI does not recognize simply
    // falls through to its default branches — no validation rejects it, and the
    // one sanitizer it passes through accepts `[A-Za-z0-9_.-]{1,63}`. So we brand
    // ourselves rather than impersonating another IDE: `claude-vscode` would work
    // too, but it flips the CLI's publish context to "interactive UI available",
    // which is not true of a `-p` session. Confirmed against the CLI bundle.
    cmd.env("CLAUDE_CODE_ENTRYPOINT", "claude-eclipse-ide");

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
        stdin: Mutex::new(Some(stdin)),
        child: Mutex::new(child),
        session_id: Mutex::new(None),
        spawn_sig,
        alive: AtomicBool::new(true),
        chrome_enabled: AtomicBool::new(false),
        spawn_resume: resume_id.to_string(),
        rc_session: Mutex::new(None),
        live: Mutex::new(LiveSettings::new(perm_mode, effort, model, thinking)),
        settings_poll: AtomicBool::new(false),
        fdescfs_diagnosed: AtomicBool::new(false),
        last_applied: Mutex::new(None),
        default_model_live: AtomicBool::new(false),
    });
    if thinking == "0" {
        // A failed write surfaces as the reader's EOF, like any dead process.
        let _ = proc.write_line(&live_request_line(thinking_request("0")));
    }
    // Asked once at startup so the answer — which decides whether a later switch back
    // to Default can be done on this process — is in before the user can pick it.
    let _ = proc.write_line(&settings_request_line());

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

    // The reader resolves inbound bridge messages against the CLI's own
    // transcript, which is keyed by a hash of this directory — so it has to be
    // remembered here, where it is known, rather than guessed there.
    state.lock().unwrap().workspace_root = workspace_root.to_string();

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
    // See run_turn's own declaration of this for why it's keyed per lineage, not a bare pair.
    let mut cursors: std::collections::HashMap<String, (usize, usize, u64)> = std::collections::HashMap::new();
    let mut tok_base: u64 = 0;
    let mut tok_chars: u64 = 0;
    // Raw model id from the init event (e.g. "claude-opus-4-8") — reported to the
    // GUI status bar, which maps it to a display name.
    let mut current_model = String::new();
    // Set by a compact_boundary event: the compact summary is echoed right after it
    // as a synthetic "user" event (string content, isSynthetic:true) — forward that
    // one message to the GUI as the expandable "Compacted chat" body.
    let mut awaiting_compact_summary = false;
    // command_uuids already rendered. Each inbound message is announced three
    // times (queued, started, completed) and must produce exactly one bubble.
    let mut seen_commands: std::collections::HashSet<String> = std::collections::HashSet::new();

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
                handle_control_request(&event, &proc, &state, &java_vm, &callbacks);
                continue;
            }
            // The CLI withdrawing a request it already sent us. For a
            // `can_use_tool` that means the decision was made somewhere else —
            // on the phone or on claude.ai, where Remote Control puts the same
            // prompt — or the turn it belonged to was torn down.
            //
            // Not handling this is what left a card on screen after it had been
            // answered elsewhere: the CLI moved on, the card did not, and every
            // later prompt in the same turn arrived behind a card that could no
            // longer be answered. Take it down here, and let the waiting thread
            // know not to bother replying.
            "control_cancel_request" => {
                if let Some(rid) = event["request_id"].as_str() {
                    cancel_card(rid, &state, &java_vm, &callbacks);
                }
                continue;
            }
            // Most acks need nothing (interrupt, rename, permission mode). The
            // one exception is remote_control, whose reply carries the bridge
            // session — including `session_url`, the only place the web address
            // of this conversation ever appears. Matched on our own request id
            // so another subtype's ack can never be mistaken for it.
            "control_response" => {
                let inner = &event["response"];
                let rid = inner["request_id"].as_str().unwrap_or("");
                if crate::bridge::rc_owns_response(rid) {
                    // The raw body, before we pick fields out of it. Kept because
                    // `bridge_session_id` has been observed coming back empty while
                    // the reply is otherwise a success -- which silently disables the
                    // API half of the inbound lookup (rc_lookup_message returns None
                    // on an empty id before it ever makes a call). This is the only
                    // place that says what the CLI actually sends.
                    if crate::is_debug() {
                        eprintln!("[remote-control] raw control_response: {}", inner);
                    }
                    let reply = crate::bridge::rc_parse_reply(inner);
                    let json = crate::bridge::rc_reply_json(&reply);
                    if crate::is_debug() {
                        eprintln!("[remote-control] {}", json);
                    }
                    // What retire_proc hands on if this process is replaced.
                    *proc.rc_session.lock().unwrap() =
                        reply.enabled.then(|| reply.bridge_session_id.clone());
                    // Remembered because an inbound message names only a uuid;
                    // this is the log that uuid has to be looked up in.
                    state.lock().unwrap().bridge_session_id = if reply.enabled {
                        Some(reply.bridge_session_id.clone())
                    } else {
                        None
                    };
                    if reply.enabled {
                        // Someone on the other device can change the model or effort now.
                        start_settings_poll(Arc::clone(&proc));
                    }
                    if crate::bridge::rc_is_reattach(rid) && !reply.enabled {
                        // A carried bridge that could not be taken back over. The page
                        // asked for nothing, so it would discard a reply; what it has to
                        // hear is that the bridge it shows as active is gone.
                        fire_string(&java_vm, &callbacks, "onRemoteControl",
                                    &crate::bridge::rc_state_json("failed"));
                    } else {
                        fire_string(&java_vm, &callbacks, "onRemoteControl", &json);
                    }
                } else if rid.starts_with(SETTINGS_PREFIX) {
                    if let Some(json) = settings_change(&proc, &inner["response"]) {
                        fire_string(&java_vm, &callbacks, "onSettingsChanged", &json);
                    }
                } else if rid.starts_with(CHROME_ON_PREFIX) {
                    // The browser was switched off while this was in flight: the
                    // banner is already gone, so a late "connected" must not bring
                    // it back.
                    if proc.chrome_enabled.load(Ordering::SeqCst) {
                        let json = match chrome_set_error(inner) {
                            Some(error) => {
                                proc.chrome_enabled.store(false, Ordering::SeqCst);
                                serde_json::json!({ "status": "error", "error": error })
                            }
                            None => serde_json::json!({ "status": "connected" }),
                        };
                        fire_string(&java_vm, &callbacks, "onBrowserState", &json.to_string());
                    }
                } else if rid.starts_with(crate::mcp_servers::REQUEST_PREFIX) {
                    if let Some(json) = crate::mcp_servers::reply_json(inner) {
                        fire_string(&java_vm, &callbacks, "onMcp", &json);
                    }
                }
                continue;
            }
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
                // A card that is still up when the turn ends is moot by
                // definition: a turn cannot finish while it is waiting on one,
                // so this card is being waited on by nobody. The backstop to
                // control_cancel_request above — the CLI does not promise a
                // withdrawal for every ending (a turn that dies on a hard
                // failure takes its prompts with it), and a card nothing can
                // answer must not outlive the turn that raised it.
                cancel_open_cards(&state, &java_vm, &callbacks);
                // Derive the session-specific status-bar data (model, context %,
                // cost) from this turn's usage and fire it to the GUI status bar.
                // Account-global rate limits come from the shared store, not here.
                if let Some(status) = build_status_json(&event, &current_model) {
                    fire_string(&java_vm, &callbacks, "onStatus", &status);
                }
                fire_void(&java_vm, &callbacks, "onStreamEnd");
                cursors.clear();
                tok_base = 0;
                tok_chars = 0;
                continue;
            }
            "system" => {
                // The CLI re-emits init every turn; keep the live session id
                // current for the reuse check, then let process_event_value fire
                // onSessionId/onSystem exactly as the legacy path does.
                match event["subtype"].as_str().unwrap_or("") {
                    "init" => {
                        if let Some(sid) = event["session_id"].as_str() {
                            *proc.session_id.lock().unwrap() = Some(sid.to_string());
                        }
                        if let Some(m) = event["model"].as_str() {
                            current_model = m.to_string();
                        }
                    }
                    // The bridge's own connection signal, emitted once Remote
                    // Control is enabled: "ready" then "connected". The status
                    // indicator follows THIS rather than the control response,
                    // so it reflects the live link rather than the fact that we
                    // once asked for one.
                    "bridge_state" => {
                        let state = event["state"].as_str().unwrap_or("");
                        if crate::is_debug() {
                            eprintln!("[remote-control] bridge {}", state);
                        }
                        // Only "failed" is a bridge gone for good — the VS Code extension's
                        // rule; the CLI passes through other states on its way back up. A
                        // gone bridge is not one to carry to a replacement process.
                        if state == "failed" {
                            *proc.rc_session.lock().unwrap() = None;
                        }
                        let json = crate::bridge::rc_bridge_state_json(state, event["bridge_epoch"].as_i64());
                        fire_string(&java_vm, &callbacks, "onRemoteControl", &json);
                        continue;
                    }
                    // Compaction lifecycle (/compact or auto-compact), verified against
                    // CLI 2.1.177: status "compacting" while it runs; then either
                    // status+compact_result:"failed" (+compact_error — the CLI also
                    // answers the turn with that text) or a compact_boundary carrying
                    // compact_metadata {trigger, pre_tokens, post_tokens}.
                    "status" => {
                        // The one launch setting the CLI announces by itself, the moment
                        // it changes and wherever it was changed — here, the phone, or
                        // claude.ai (verified). Model and effort are polled instead.
                        if let Some(mode) = event["permissionMode"].as_str() {
                            let changed = {
                                let mut live = proc.live.lock().unwrap();
                                let differs = live.perm_mode != mode;
                                if differs {
                                    live.perm_mode = mode.to_string();
                                }
                                differs
                            };
                            if changed {
                                fire_string(&java_vm, &callbacks, "onSettingsChanged",
                                            &serde_json::json!({ "permMode": mode }).to_string());
                            }
                        }
                        if event["status"].as_str() == Some("compacting") {
                            fire_string(&java_vm, &callbacks, "onCompact",
                                        "{\"phase\":\"compacting\"}");
                        } else if event["compact_result"].as_str() == Some("failed") {
                            let payload = serde_json::json!({
                                "phase": "failed",
                                "error": event["compact_error"].as_str().unwrap_or(""),
                            });
                            fire_string(&java_vm, &callbacks, "onCompact", &payload.to_string());
                        }
                    }
                    "compact_boundary" => {
                        let md = &event["compact_metadata"];
                        let payload = serde_json::json!({
                            "phase": "boundary",
                            "trigger": md["trigger"].as_str().unwrap_or("manual"),
                            "preTokens": md["pre_tokens"].as_u64().unwrap_or(0),
                            "postTokens": md["post_tokens"].as_u64().unwrap_or(0),
                        });
                        fire_string(&java_vm, &callbacks, "onCompact", &payload.to_string());
                        awaiting_compact_summary = true;
                    }
                    _ => {}
                }
            }
            "user" => {
                // The one synthetic user echo right after a compact_boundary is the
                // compact summary. Command echoes stay ignored; tool results fall
                // through to process_event_value, which turns them into onToolEnd.
                if awaiting_compact_summary
                    && event["isSynthetic"].as_bool().unwrap_or(false)
                {
                    if let Some(txt) = event["message"]["content"].as_str() {
                        let payload = serde_json::json!({ "phase": "summary", "text": txt });
                        fire_string(&java_vm, &callbacks, "onCompact", &payload.to_string());
                        awaiting_compact_summary = false;
                    }
                }
            }
            // A message that arrived over the bridge. The CLI announces the WORK
            // here — uuid and state — and never puts the message itself on
            // stdout, so the text is fetched by uuid (rc_lookup_message).
            //
            // Bridge-only by construction: a message sent from this editor goes
            // in over stdin and produces no lifecycle events at all, so nothing
            // here can double a bubble the page already drew.
            "command_lifecycle" => {
                if event["state"].as_str() != Some("queued") {
                    continue;
                }
                let Some(uuid) = event["command_uuid"].as_str().map(|s| s.to_string()) else {
                    continue;
                };
                let Some(bridge_id) = state.lock().unwrap().bridge_session_id.clone() else {
                    continue;
                };
                let workspace = state.lock().unwrap().workspace_root.clone();
                // Once per uuid: the same command is announced again as it
                // starts and completes, and a message is not said three times.
                if !seen_commands.insert(uuid.clone()) {
                    continue;
                }
                if crate::is_debug() {
                    eprintln!("[remote-control] inbound command {}", uuid);
                }
                // Off this thread — it is a network round trip, and this thread
                // is the only reader of the CLI's stdout.
                let vm = Arc::clone(&java_vm);
                let cb = Arc::clone(&callbacks);
                // The handle, not a copy of the session id: the id is only known once
                // the CLI's system/init has landed, which can be after this event.
                let lookup_proc = Arc::clone(&proc);
                std::thread::Builder::new()
                    .name("claude-rc-inbound".into())
                    .spawn(move || {
                        if let Some(text) = inbound_message_text(&workspace, &lookup_proc,
                                                                &bridge_id, &uuid) {
                            if crate::is_debug() {
                                eprintln!("[remote-control] inbound message ({} chars)", text.len());
                            }
                            fire_string(&vm, &cb, "onRemoteMessage", &text);
                        } else if crate::is_debug() {
                            eprintln!("[remote-control] no text found for {}", uuid);
                        }
                    })
                    .ok();
                continue;
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

        process_event_value(&event, &java_vm, &callbacks, &mut cursors, &mut tok_base, &mut tok_chars);
        queue_fdescfs_diagnosis(&event, &proc, &java_vm, &callbacks);
    }

    // EOF. Distinguish an INTENTIONAL kill (respawn on settings/tab change, reset,
    // dispose — all set `alive=false` *before* killing) from a genuine CRASH
    // (alive still true). swap returns the previous value: true = was alive = crash.
    let crashed = proc.alive.swap(false, Ordering::Relaxed);
    // The browser went with the process. Unless a replacement process has already
    // switched it back on — its banner is the live one and must stay.
    if proc.chrome_enabled.swap(false, Ordering::SeqCst) {
        let replacement_on = state
            .lock()
            .unwrap()
            .proc
            .as_ref()
            .is_some_and(|cur| !Arc::ptr_eq(cur, &proc) && cur.chrome_enabled.load(Ordering::SeqCst));
        if !replacement_on {
            fire_string(&java_vm, &callbacks, "onBrowserState", r#"{"status":"disconnected"}"#);
        }
    }
    // So did its bridge. An intentional kill has handed it on already (retire_proc);
    // a crash leaves the page showing Remote Control as active unless it is told.
    let rc_was_on = proc.rc_session.lock().unwrap().take().is_some();
    if crashed && rc_was_on {
        fire_string(&java_vm, &callbacks, "onRemoteControl", &crate::bridge::rc_state_json("failed"));
    }
    // Either way the process is gone, so any card still up is unanswerable —
    // its control_response has nowhere to go. Do this before the early return:
    // an intentional kill (respawn, reset, dispose) leaves cards behind just as
    // readily as a crash does, and the replacement process will not adopt them.
    cancel_open_cards(&state, &java_vm, &callbacks);
    if !crashed {
        // Intentional teardown: the initiator already updated state.proc/awaiting,
        // and a replacement turn (if any) owns the stream. Stay silent — do NOT
        // report an error or fire onStreamEnd (that would abort the new turn and
        // show "Claude process exited unexpectedly" on every model switch).
        return;
    }
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

/// Builds the GUI status-bar JSON for a completed turn from its `result` event.
/// Context % = (input + cache_read + cache_creation) / contextWindow · 100; the
/// window size and cost come straight from the result. Returns None when there's
/// no usable usage data. Only session-specific fields — rate limits are shared
/// from the CLI statusLine via ClaudeStatusStore, never derived here.
fn build_status_json(event: &serde_json::Value, model: &str) -> Option<String> {
    let usage = &event["usage"];
    let input = usage["input_tokens"].as_u64().unwrap_or(0);
    let cache_read = usage["cache_read_input_tokens"].as_u64().unwrap_or(0);
    let cache_create = usage["cache_creation_input_tokens"].as_u64().unwrap_or(0);
    let output = usage["output_tokens"].as_u64().unwrap_or(0);
    let context_tokens = input + cache_read + cache_create;

    // Context window size from modelUsage (per-model), falling back to the
    // largest reported window across models in this result.
    let mut window: u64 = 0;
    if let Some(mu) = event["modelUsage"].as_object() {
        if let Some(m) = mu.get(model).and_then(|v| v["contextWindow"].as_u64()) {
            window = m;
        }
        if window == 0 {
            for v in mu.values() {
                if let Some(w) = v["contextWindow"].as_u64() {
                    window = window.max(w);
                }
            }
        }
    }
    let cost = event["total_cost_usd"].as_f64().unwrap_or(0.0);

    // Nothing meaningful to show yet.
    if context_tokens == 0 && window == 0 && cost == 0.0 {
        return None;
    }

    // Clamped to [0, 100]: a ratio above 100% is never legitimate for display (it means
    // `window` resolved to the wrong, too-small model entry — e.g. modelUsage briefly
    // keyed by a small-context helper/sub-model instead of `model` — not that usage
    // actually exceeds the window), and nothing downstream (ClaudeStatusBar's text label)
    // clamped it either, so a bad ratio here used to surface as literal text like "1097%".
    let context_pct = if window > 0 {
        ((context_tokens as f64 / window as f64) * 100.0).clamp(0.0, 100.0)
    } else {
        0.0
    };

    let payload = serde_json::json!({
        "model": model,
        "contextPct": context_pct,
        "contextWindow": window,
        "inputTokens": input,
        "outputTokens": output,
        "cacheCreationTokens": cache_create,
        "cacheReadTokens": cache_read,
        "costUsd": cost,
    });
    Some(payload.to_string())
}

/// The words of a message that arrived over the Remote Control bridge, given the
/// uuid `command_lifecycle` announced it under.
///
/// **Local first.** The CLI writes the message to its own transcript on this
/// machine, so that is where it is read from — no network, no OAuth credential,
/// and no way for a credential store that will not open to turn someone's
/// message into silence. That was the macOS failure exactly: `read_credential`
/// goes to the login Keychain there, and every way that can fail arrived here as
/// "no text found", so nothing was ever drawn.
///
/// **The wait.** `queued` fires when the message enters the command queue, which
/// can be marginally before the line is on disk. Rather than guess a delay, poll
/// briefly — the common case returns on the first read.
///
/// **The session id is re-read on every attempt, deliberately.** It is only set
/// when the CLI's `system`/`init` event arrives, and a message sent from a phone
/// the moment the bridge comes up can be announced *before* that. Reading it once
/// up front meant an empty id skipped the whole loop — not a slow read but no read
/// at all — and the message was lost with nothing in the log to say why. That is
/// exactly how the first inbound message of a fresh bridge session went missing
/// while every later one in the same session was found on the first try.
///
/// **Then the API.** Kept as the fallback for the cases the file cannot cover: a
/// session whose transcript this workspace hash does not point at, or a first
/// message that arrives before the transcript exists at all.
fn inbound_message_text(
    workspace_root: &str,
    proc: &ProcHandle,
    bridge_session_id: &str,
    uuid: &str,
) -> Option<String> {
    const TRIES: u32 = 10;
    const WAIT_MS: u64 = 150;
    if !workspace_root.is_empty() {
        for attempt in 0..TRIES {
            let session_id = proc.session_id.lock().unwrap().clone().unwrap_or_default();
            if !session_id.is_empty() {
                if let Some(text) =
                    crate::session::message_text_by_uuid(workspace_root, &session_id, uuid)
                {
                    if crate::is_debug() {
                        eprintln!("[remote-control] {} read from the transcript", uuid);
                    }
                    return Some(text);
                }
            } else if crate::is_debug() && attempt == 0 {
                eprintln!(
                    "[remote-control] {} arrived before the session id was known, waiting",
                    uuid
                );
            }
            if attempt + 1 < TRIES {
                std::thread::sleep(std::time::Duration::from_millis(WAIT_MS));
            }
        }
    }
    if crate::is_debug() {
        eprintln!("[remote-control] {} not in the transcript, asking the API", uuid);
    }
    crate::bridge::rc_lookup_message("", bridge_session_id, uuid)
}

/// Takes down the card for one CLI request id, if we still have one up.
///
/// Two halves, and both are needed. The Java side is told to tear the card off
/// the screen and stop waiting on it; and the id is remembered as cancelled so
/// the thread blocked in that card does not then write a `control_response` for
/// a request the CLI has already stopped listening for.
///
/// Silent for an id we never raised a card for — the CLI cancels its own
/// requests for reasons that have nothing to do with us.
fn cancel_card(
    request_id: &str,
    state: &Arc<Mutex<ChatState>>,
    java_vm: &Arc<jni::JavaVM>,
    callbacks: &Arc<jni::objects::GlobalRef>,
) {
    {
        let mut s = state.lock().unwrap();
        if !s.open_cards.remove(request_id) {
            return;
        }
        s.cancelled_cards.insert(request_id.to_string());
    }
    if crate::is_debug() {
        eprintln!("[chat] card {} cancelled (answered elsewhere or turn ended)", request_id);
    }
    fire_string(java_vm, callbacks, "onCardCancel", request_id);
}

/// Cancels every card still up for this conversation. See the call sites for
/// when that is the right thing to do — both are moments after which no card
/// can be answered any more.
fn cancel_open_cards(
    state: &Arc<Mutex<ChatState>>,
    java_vm: &Arc<jni::JavaVM>,
    callbacks: &Arc<jni::objects::GlobalRef>,
) {
    let ids: Vec<String> = {
        let s = state.lock().unwrap();
        if s.open_cards.is_empty() {
            return; // the overwhelmingly common case — don't touch anything
        }
        s.open_cards.iter().cloned().collect()
    };
    for id in ids {
        cancel_card(&id, state, java_vm, callbacks);
    }
}

/// can_use_tool: ask the user via the Java callbacks. Runs on its own thread so
/// the reader stays free — a Stop while the card is up still processes the
/// interrupt's result event immediately.
fn handle_control_request(
    event: &serde_json::Value,
    proc: &Arc<ProcHandle>,
    state: &Arc<Mutex<ChatState>>,
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

    // Registered BEFORE the card is raised: the withdrawal can arrive while the
    // card is still being drawn (the phone is quicker than a human), and a
    // cancel for an id not yet on the books would be dropped as unknown.
    state.lock().unwrap().open_cards.insert(request_id.clone());

    let proc = Arc::clone(proc);
    let state = Arc::clone(state);
    let vm = Arc::clone(java_vm);
    let cb = Arc::clone(callbacks);
    std::thread::Builder::new()
        .name("claude-chat-perm".into())
        .spawn(move || {
            let response = decide_can_use_tool(&request_id, &tool_name, &input,
                                               &suggestions, &vm, &cb);
            // Whoever ends this card first wins. If the request was withdrawn
            // while we waited, the CLI has already acted on somebody else's
            // answer — replying now would be answering a question nobody asked.
            let withdrawn = {
                let mut s = state.lock().unwrap();
                s.open_cards.remove(&request_id);
                s.cancelled_cards.remove(&request_id)
            };
            if withdrawn {
                return;
            }
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
    request_id: &str,
    tool_name: &str,
    input: &serde_json::Value,
    suggestions: &serde_json::Value,
    java_vm: &Arc<jni::JavaVM>,
    callbacks: &Arc<jni::objects::GlobalRef>,
) -> serde_json::Value {
    if tool_name == "AskUserQuestion" {
        let questions = input.get("questions").cloned().unwrap_or_else(|| serde_json::json!([]));
        let ans = fire_two_string_ret(java_vm, callbacks, "onQuestionRequest",
                                     request_id, &questions.to_string())
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

    let decision = fire_four_string_ret(java_vm, callbacks, "onPermissionRequest",
                                       request_id, tool_name, &input.to_string(),
                                       &remember_label)
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

/// FreeBSD: when a tool result in `event` failed with the fdescfs error
/// ([`crate::freebsd_guide::is_fdescfs_failure`]), queues the setup guide onto
/// `proc` as a user message, so Claude answers with the fix, and tells the page
/// it did. Once per process. A no-op everywhere else.
fn queue_fdescfs_diagnosis(
    event: &serde_json::Value,
    proc: &Arc<ProcHandle>,
    java_vm: &Arc<jni::JavaVM>,
    callbacks: &Arc<jni::objects::GlobalRef>,
) {
    if !cfg!(target_os = "freebsd") || event["type"].as_str() != Some("user") {
        return;
    }
    let Some(blocks) = event["message"]["content"].as_array() else { return };
    let failure = blocks.iter().find_map(|b| {
        if b["type"].as_str() != Some("tool_result") || !b["is_error"].as_bool().unwrap_or(false) {
            return None;
        }
        let text = crate::session::flatten_result_content(b);
        crate::freebsd_guide::is_fdescfs_failure(&text).then_some(text)
    });
    let Some(error_text) = failure else { return };
    let Some(message) = crate::freebsd_guide::diagnosis_message(&error_text) else { return };
    if proc.fdescfs_diagnosed.swap(true, Ordering::SeqCst) || proc.is_dead() {
        return;
    }
    let msg_json = serde_json::json!({
        "type": "user",
        "message": { "role": "user", "content": [{ "type": "text", "text": message }] }
    });
    if proc.write_line(&msg_json.to_string()).is_err() {
        return; // the reader's EOF path reports a dead process
    }
    fire_string(java_vm, callbacks, "onNotice",
        "A tool failed with the FreeBSD fdescfs error (ENOTDIR). The FreeBSD setup guide \
         was sent to Claude so it can explain the fix.");
}

fn process_event(
    line: &str,
    java_vm: &Arc<jni::JavaVM>,
    callbacks: &Arc<jni::objects::GlobalRef>,
    cursors: &mut std::collections::HashMap<String, (usize, usize, u64)>,
    tok_base: &mut u64,
    tok_chars: &mut u64,
) {
    let event: serde_json::Value = match serde_json::from_str(line) {
        Ok(v) => v,
        Err(_) => return,
    };
    process_event_value(&event, java_vm, callbacks, cursors, tok_base, tok_chars);
}

/// Pre-parsed variant shared by the legacy per-turn reader and the persistent
/// reader (which needs the Value first to route control/result events).
fn process_event_value(
    event: &serde_json::Value,
    java_vm: &Arc<jni::JavaVM>,
    callbacks: &Arc<jni::objects::GlobalRef>,
    cursors: &mut std::collections::HashMap<String, (usize, usize, u64)>,
    tok_base: &mut u64,
    tok_chars: &mut u64,
) {
    // A message belonging to a subagent's own nested conversation (Task/Agent tool),
    // multiplexed onto this SAME stream alongside the top-level conversation's own
    // events. Every "assistant"/"user" event carries this field; empty/absent means
    // top-level. Without this check, a subagent's own text/tool calls used to fire
    // through onText/onToolStart exactly like the top-level model's — rendering
    // inline in Claude's own message bubble, with whatever real text followed it
    // getting appended onto that same bubble (nothing had ever started a fresh one).
    let parent_id = event.get("parent_tool_use_id").and_then(|v| v.as_str()).unwrap_or("");
    let is_subagent = !parent_id.is_empty();
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
            } else if event["subtype"].as_str() == Some("task_notification") {
                // A background agent's REAL completion — confirmed against the actual
                // live stream (not just a saved-file guess, see the two wrong shapes
                // this replaced): {"tool_use_id":…,"status":"completed","summary":…,
                // "usage":{"total_tokens":…,"duration_ms":…}}. Its own top-level
                // tool_result fires almost immediately as a "kicked off" ack (see
                // chat.js's applyToolResult), so this is the only signal that it's
                // ACTUALLY done — tool_use_id is given directly, no id-resolution
                // needed at all. usage's totals are authoritative (summed server-side
                // over its whole run), more accurate than chat.js's own running total
                // from onAgentActivity's per-message tallying.
                if let Some(tool_use_id) = event["tool_use_id"].as_str() {
                    let payload = serde_json::json!({
                        "parentId": tool_use_id,
                        "kind": "finished",
                        "tokens": event["usage"]["total_tokens"].as_u64(),
                        "durationMs": event["usage"]["duration_ms"].as_u64(),
                        // The agent's own final answer, verbatim — never otherwise shown
                        // anywhere: the top-level model reads this as context and relays
                        // it in ITS OWN words in the main chat, but the agent's actual
                        // response text itself isn't displayed unless the Agents popup's
                        // detail view surfaces it directly (agents.js).
                        "summary": event["summary"].as_str(),
                        // "completed" vs "stopped" (the user hit Stop agent) — lets the
                        // detail view say which, instead of always "Finished".
                        "status": event["status"].as_str(),
                    });
                    fire_string(java_vm, callbacks, "onAgentActivity", &payload.to_string());
                }
            } else if event["subtype"].as_str() == Some("task_started") {
                // The agent's own internal task id — needed for the "Stop agent" button
                // (ChatManager::stop_task), which has to fire WHILE it's still running.
                // task_notification (above) only arrives on completion, too late to stop
                // anything; this is the only event carrying task_id this early, paired
                // with tool_use_id so it can be matched to the right agentLogs entry.
                if let (Some(tool_use_id), Some(task_id)) =
                    (event["tool_use_id"].as_str(), event["task_id"].as_str())
                {
                    let payload = serde_json::json!({
                        "parentId": tool_use_id,
                        "kind": "taskId",
                        "taskId": task_id,
                    });
                    fire_string(java_vm, callbacks, "onAgentActivity", &payload.to_string());
                }
            }
        }
        // Tool RESULTS come back on a "user" event (the CLI feeds them to the model
        // as the user turn). Without this branch the GUI only ever heard that a tool
        // STARTED, so markToolsDone greened every dot and a failed tool was rendered
        // as a success — the transcript said is_error, the screen said fine.
        "user" => {
            if let Some(blocks) = event["message"]["content"].as_array() {
                for b in blocks {
                    if b["type"].as_str() != Some("tool_result") {
                        continue;
                    }
                    let id = b["tool_use_id"].as_str().unwrap_or("");
                    if id.is_empty() {
                        continue; // nothing to match it to on the GUI side
                    }
                    if is_subagent {
                        // The subagent's OWN tool finishing — its tool_use never got a
                        // top-level onToolStart (see the tool_use branch below), so this
                        // is never a top-level onToolEnd either. Relayed the same shape
                        // (id/isError/text) as that callback, just wrapped with parentId
                        // and a kind, so the subagent's own nested-transcript log (the
                        // collapsible section under its tool line, and the Agents
                        // popup's "Open transcript") can resolve this step the same way
                        // applyToolResult resolves a top-level one.
                        let is_error = b["is_error"].as_bool().unwrap_or(false);
                        let text = if is_error {
                            crate::session::tool_error_summary(&crate::session::flatten_result_content(b))
                                .unwrap_or_default()
                        } else {
                            crate::session::flatten_result_content(b)
                        };
                        let activity = serde_json::json!({
                            "parentId": parent_id,
                            "kind": "tool_end",
                            "id": id,
                            "isError": is_error,
                            "text": text,
                        });
                        fire_string(java_vm, callbacks, "onAgentActivity", &activity.to_string());
                        continue;
                    }
                    let is_error = b["is_error"].as_bool().unwrap_or(false);
                    // Successes fire too: the dot is then set from what actually
                    // happened instead of inferred when the NEXT tool starts.
                    //
                    // On error, tool_error_summary condenses to one line (~160 chars) for
                    // the muted "⚠ …" note under the tool line — that's the only thing the
                    // GUI renders for a failure. On success, the GUI now actually renders
                    // the result (chat.js's applyToolResult/renderToolOutput — an "OUT" box,
                    // a checklist, a clickable result list), so it needs the FULL flattened
                    // content, not the empty string this used to send when nothing on the
                    // GUI side read it yet. No truncation here: the page caps/links out to a
                    // full view for long content on its own (capIfOverflowing in chat.js).
                    let text = if is_error {
                        crate::session::tool_error_summary(&crate::session::flatten_result_content(b))
                            .unwrap_or_default()
                    } else {
                        crate::session::flatten_result_content(b)
                    };
                    let payload = serde_json::json!({
                        "id": id,
                        "isError": is_error,
                        "text": text,
                    });
                    fire_string(java_vm, callbacks, "onToolEnd", &payload.to_string());
                }
            }
        }
        // Actual Claude CLI --output-format stream-json format.
        // Partial events have cumulative text; compute deltas to avoid duplicates.
        "assistant" => {
            let is_partial = event.get("partial").and_then(|v| v.as_bool()).unwrap_or(false);
            // A synthetic assistant message standing in for a backend error (rate
            // limit, 529 overload, …) — the CLI marks it isApiErrorMessage and also
            // ends the turn with a matching is_error result, which already fires
            // onError (below, on the "result" branch) and renders the single muted
            // line. Streaming this copy as ordinary text would show it twice.
            let is_api_error = event["isApiErrorMessage"].as_bool().unwrap_or(false);
            let cursor = cursors.entry(parent_id.to_string()).or_insert((0, 0, 0));
            if !is_api_error {
                if let Some(content) = event["message"]["content"].as_array() {
                    for block in content {
                        match block["type"].as_str().unwrap_or("") {
                            "text" => {
                                if let Some(text) = block["text"].as_str() {
                                    let start = cursor.0.min(text.len());
                                    let new_part = &text[start..];
                                    if !new_part.is_empty() {
                                        // A subagent's own words are relayed under a
                                        // distinct event ("kind":"text") rather than
                                        // onText, which chat.js reserves for the
                                        // top-level model — a subagent's OWN transcript
                                        // (the collapsible log under its tool line, and
                                        // the Agents popup's "Open transcript") renders
                                        // this separately instead of it appearing to be
                                        // Claude's own reply.
                                        if is_subagent {
                                            let activity = serde_json::json!({
                                                "parentId": parent_id, "kind": "text", "text": new_part,
                                            });
                                            fire_string(java_vm, callbacks, "onAgentActivity", &activity.to_string());
                                        } else {
                                            fire_string(java_vm, callbacks, "onText", new_part);
                                        }
                                    }
                                    cursor.0 = text.len();
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
                                let start = cursor.1.min(t.len());
                                let new_part = &t[start..];
                                if !new_part.is_empty() || cursor.1 == 0 {
                                    if is_subagent {
                                        let activity = serde_json::json!({
                                            "parentId": parent_id, "kind": "thinking", "text": new_part,
                                        });
                                        fire_string(java_vm, callbacks, "onAgentActivity", &activity.to_string());
                                    } else {
                                        fire_string(java_vm, callbacks, "onThinking", new_part);
                                    }
                                }
                                cursor.1 = t.len();
                            }
                            "tool_use" if !is_partial => {
                                if is_subagent {
                                    // A subagent's OWN tool call — never a top-level
                                    // onToolStart (that would render a bogus tool line
                                    // interleaved into the main transcript). Relayed with
                                    // its own tool_use id (like onToolStart's "id") so the
                                    // matching tool_end below can resolve THIS step, same
                                    // as the top-level onToolStart/onToolEnd pairing.
                                    let activity = serde_json::json!({
                                        "parentId": parent_id,
                                        "kind": "tool_start",
                                        "name": block["name"].as_str().unwrap_or("tool"),
                                        "input": block.get("input").cloned().unwrap_or(serde_json::json!({})),
                                        "id": block["id"].as_str().unwrap_or(""),
                                    });
                                    fire_string(java_vm, callbacks, "onAgentActivity", &activity.to_string());
                                } else {
                                    // Pass name + input so the GUI can show the target file/command
                                    // after the verb and render an inline diff for edits.
                                    let payload = serde_json::json!({
                                        "name": block["name"].as_str().unwrap_or("tool"),
                                        "input": block.get("input").cloned().unwrap_or(serde_json::json!({})),
                                        // Carried so the matching tool_result (onToolEnd)
                                        // can find THIS line again and resolve its dot.
                                        "id": block["id"].as_str().unwrap_or(""),
                                    });
                                    fire_string(java_vm, callbacks, "onToolStart", &payload.to_string());
                                }
                            }
                            _ => {}
                        }
                    }
                }
                // A subagent's own per-turn usage, summed across its whole run — the
                // Agents popup's duration+tokens line. Anthropic's `usage` is PER
                // MESSAGE, not cumulative, so this has to add rather than overwrite;
                // only counted once a message is actually final (never for a partial
                // delta still in flight, which would double-count once it completes).
                // Model rides along on the same message (also the detail popup's
                // "Sonnet 5"-style field) rather than its own separate event.
                if is_subagent && !is_partial {
                    let usage = &event["message"]["usage"];
                    let added = usage["input_tokens"].as_u64().unwrap_or(0)
                        + usage["output_tokens"].as_u64().unwrap_or(0)
                        + usage["cache_creation_input_tokens"].as_u64().unwrap_or(0)
                        + usage["cache_read_input_tokens"].as_u64().unwrap_or(0);
                    cursor.2 += added;
                    let model = event["message"]["model"].as_str().unwrap_or("");
                    if added > 0 || !model.is_empty() {
                        let activity = serde_json::json!({
                            "parentId": parent_id, "kind": "tokens", "tokens": cursor.2,
                            "model": if model.is_empty() { serde_json::Value::Null } else { serde_json::Value::from(model) },
                        });
                        fire_string(java_vm, callbacks, "onAgentActivity", &activity.to_string());
                    }
                }
            }
            if !is_partial {
                cursor.0 = 0;
                cursor.1 = 0;
            }
        }
        // Fine-grained streaming events (only with --include-partial-messages) —
        // used solely to drive the live output-token counter. Text/thinking/tools
        // still render from the complete "assistant" events above.
        // A concurrent background subagent's own token usage must not skew the
        // top-level turn's live counter — this bar reports the turn the user is
        // actually watching, not the sum of everything running underneath it.
        "stream_event" if !is_subagent => {
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

/// Calls a String-returning Java callback whose parameters are all Strings:
/// `String method(String, String, ...)`. Used for the persistent-mode cards,
/// which block the calling thread until the user decides — never call it from
/// the reader thread. Pending Java exceptions are cleared so they can't poison
/// later JNI calls on this thread.
///
/// The descriptor is built from the argument count rather than written out per
/// arity: the cards each grew one parameter (the CLI request id, so a card can
/// be taken back down again) and a per-arity copy of this is a copy of the
/// exception handling and the drop-order trap below along with it.
fn fire_strings_ret(
    java_vm: &Arc<jni::JavaVM>,
    callbacks: &Arc<jni::objects::GlobalRef>,
    method: &str,
    args: &[&str],
) -> Option<String> {
    let mut env = java_vm.attach_current_thread().ok()?;
    let mut objs: Vec<JObject> = Vec::with_capacity(args.len());
    for a in args {
        objs.push(JObject::from(env.new_string(a).ok()?));
    }
    let vals: Vec<JValue> = objs.iter().map(JValue::Object).collect();
    let sig = format!(
        "({})Ljava/lang/String;",
        "Ljava/lang/String;".repeat(args.len())
    );
    let result = env.call_method(callbacks.as_ref(), method, &sig, &vals);
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

/// `String onQuestionRequest(String requestId, String questionsJson)`.
fn fire_two_string_ret(
    java_vm: &Arc<jni::JavaVM>,
    callbacks: &Arc<jni::objects::GlobalRef>,
    method: &str,
    a: &str,
    b: &str,
) -> Option<String> {
    fire_strings_ret(java_vm, callbacks, method, &[a, b])
}

/// `String onPermissionRequest(String requestId, String toolName, String inputJson,
/// String rememberLabel)`.
fn fire_four_string_ret(
    java_vm: &Arc<jni::JavaVM>,
    callbacks: &Arc<jni::objects::GlobalRef>,
    method: &str,
    a: &str,
    b: &str,
    c: &str,
    d: &str,
) -> Option<String> {
    fire_strings_ret(java_vm, callbacks, method, &[a, b, c, d])
}

fn fire_string(
    java_vm: &Arc<jni::JavaVM>,
    callbacks: &Arc<jni::objects::GlobalRef>,
    method: &str,
    value: &str,
) {
    // Mirror through the bridge if connected
    if crate::bridge::is_connected() {
        let msg = format!("CHAT:{}:{}", method, value);
        crate::bridge::send_line(&msg);
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

/// Extracts the two subscription-window percentages from the CLI's `/usage`
/// text and renders them in the **statusLine schema**, so Java can hand the
/// result straight to `ClaudeStatusStore.acceptStatusLine` and reuse the
/// existing `ClaudeStatus.parse` — no new JSON shape, no new Java parser.
///
/// The text we parse looks like:
/// ```text
/// Current session: 44% used · resets Aug 29, 2:10am (Asia/Irkutsk)
/// Current week (all models): 64% used · resets Aug 31, 8am (Asia/Irkutsk)
/// ```
/// Only the integer before `%` is read. The reset timestamps in this text are
/// **localized prose** and deliberately not parsed — the structured epoch value
/// already arrives on the `rate_limit_event` stream (`onRateLimit`), which is a
/// far smaller thing to keep working across CLI versions.
///
/// `Current week` is anchored on `all models` because the CLI also compiles
/// per-model weekly variants; matching the bare prefix could pick up the wrong
/// line. Returns `None` when neither window is found, so a changed output
/// format degrades to "no data" rather than to wrong numbers.
fn usage_json_from_text(text: &str) -> Option<String> {
    let mut five_hour = None;
    let mut seven_day = None;

    for line in text.lines() {
        let t = line.trim();
        let lower = t.to_ascii_lowercase();
        if !lower.starts_with("current ") {
            continue;
        }
        let pct = match percent_used_in(t) {
            Some(p) => p,
            None => continue,
        };
        if lower.starts_with("current session") {
            five_hour.get_or_insert(pct);
        } else if lower.starts_with("current week") && lower.contains("all models") {
            seven_day.get_or_insert(pct);
        }
    }

    if five_hour.is_none() && seven_day.is_none() {
        return None;
    }

    let mut limits = serde_json::Map::new();
    if let Some(p) = five_hour {
        limits.insert("five_hour".into(), serde_json::json!({ "used_percentage": p }));
    }
    if let Some(p) = seven_day {
        limits.insert("seven_day".into(), serde_json::json!({ "used_percentage": p }));
    }
    Some(serde_json::json!({ "rate_limits": limits }).to_string())
}

/// Reads the integer percentage from a `… NN% used …` fragment. Anchors on the
/// `%` and walks back over the digits, so it is unaffected by whatever prose
/// precedes or follows it.
fn percent_used_in(line: &str) -> Option<u32> {
    let bytes = line.as_bytes();
    for (i, b) in bytes.iter().enumerate() {
        if *b != b'%' {
            continue;
        }
        let mut start = i;
        while start > 0 && bytes[start - 1].is_ascii_digit() {
            start -= 1;
        }
        if start == i {
            continue; // a '%' with no digits before it
        }
        if let Ok(p) = line[start..i].parse::<u32>() {
            return Some(p.min(100));
        }
    }
    None
}

/// Fetches the account-global subscription usage by running the CLI's own
/// `/usage` command in print mode, returning statusLine-schema JSON (see
/// [`usage_json_from_text`]) or `None`.
///
/// **This costs the user's quota nothing.** The CLI answers `/usage` locally:
/// the turn reports `model: "<synthetic>"`, `total_cost_usd: 0`,
/// `duration_api_ms: 0`, zero tokens and `num_turns: 0` — there is no API call.
///
/// It is not *free* in wall time, though: a full `claude` process start-up
/// measured **~7 s** here (cold ~8.4 s), which is why the caller must run this
/// off the UI thread and throttle it. (The CLI's own `duration_ms` reports
/// ~1.3 s — that measures only the work after start-up, so don't size the
/// caller's threading against it.) Throttling is safe: these are percentages of
/// 5-hour and 7-day windows and cannot move meaningfully faster.
///
/// **The probe deliberately does NOT run in the workspace.** `/usage` is
/// account-global, so the workspace buys nothing, and running there would cost
/// two things: every probe would drop a `/usage` transcript into the project's
/// session directory — which `session.rs` enumerates *without* filtering on
/// entrypoint, so it would surface in the GUI's own session picker — and each
/// probe would load the project's `CLAUDE.md`, hooks, plugins and skills,
/// firing any `SessionStart` hook the user has. Instead it runs in a dedicated
/// temp directory whose transcripts are purged after each run, so nothing
/// accumulates and the user's real session list is untouched.
///
/// The entrypoint is additionally pinned to `sdk-cli` (the chat sessions use
/// `claude-eclipse-ide`): the CLI's own `/resume` hides `sdk-cli` sessions, so
/// the probe stays out of that picker too.
pub fn fetch_usage(claude_cmd: &str, _workspace_root: &str) -> Option<String> {
    let probe_dir = std::env::temp_dir().join(USAGE_PROBE_DIR);
    std::fs::create_dir_all(&probe_dir).ok()?;

    let args: Vec<String> = vec!["-p".into(), "/usage".into()];
    let mut cmd = crate::launch::claude_command(claude_cmd, &args);
    cmd.current_dir(&probe_dir)
        .stdin(Stdio::null())
        .stdout(Stdio::piped())
        .stderr(Stdio::null());

    #[cfg(windows)]
    cmd.creation_flags(0x08000000); // CREATE_NO_WINDOW

    for (k, v) in crate::shell_env::captured_env().to_inject() {
        cmd.env(k, v);
    }
    // Keep these probe sessions out of `/resume` (see doc comment).
    cmd.env("CLAUDE_CODE_ENTRYPOINT", "sdk-cli");

    let out = cmd.output().ok();
    purge_probe_transcripts();
    let out = out?;
    let text = String::from_utf8_lossy(&out.stdout);
    usage_json_from_text(&text)
}

/// Directory name the probe runs in; also the suffix its project folder carries.
const USAGE_PROBE_DIR: &str = "claude-eclipse-usage";

/// Deletes the project folder the `/usage` probe just wrote to, so its
/// transcripts never accumulate and never reach any session list.
///
/// **Matched by suffix, not by an exact hash.** `session.rs`'s `workspace_hash`
/// maps every non-alphanumeric char to `-`, so the folder is the probe path
/// slugified — but Windows may hand back either the short (`WINDOW~1`) or long
/// (`Windows 10`) form of the temp path depending on how `%TEMP%` is set, and
/// the two slugify differently. Recomputing the hash from our own string would
/// silently miss the folder whenever the CLI saw the other form. Every project
/// folder ending in `-claude-eclipse-usage` is ours, so match that instead.
///
/// Best-effort: failures are ignored, since a leftover file is harmless and the
/// next probe retries the sweep.
fn purge_probe_transcripts() {
    let home = match std::env::var_os(if cfg!(windows) { "USERPROFILE" } else { "HOME" }) {
        Some(h) => std::path::PathBuf::from(h),
        None => return,
    };
    let projects = home.join(".claude").join("projects");
    let entries = match std::fs::read_dir(&projects) {
        Ok(e) => e,
        Err(_) => return,
    };
    let suffix: String = format!("-{USAGE_PROBE_DIR}")
        .chars()
        .map(|c| if c.is_ascii_alphanumeric() { c } else { '-' })
        .collect();
    for entry in entries.flatten() {
        let p = entry.path();
        if !p.is_dir() {
            continue;
        }
        if entry.file_name().to_string_lossy().ends_with(&suffix) {
            let _ = std::fs::remove_dir_all(&p);
        }
    }
}

#[cfg(test)]
mod tests {
    use super::build_user_content;
    use serde_json::json;

    #[test]
    fn no_images_is_plain_string() {
        // Unchanged wire format when there are no images.
        assert_eq!(build_user_content("hello", ""), json!("hello"));
        assert_eq!(build_user_content("hello", "  "), json!("hello"));
        assert_eq!(build_user_content("hello", "[]"), json!("hello"));
    }

    #[test]
    fn text_plus_image_becomes_content_blocks() {
        let imgs = r#"[{"media_type":"image/png","data":"QUJD"}]"#;
        assert_eq!(
            build_user_content("look", imgs),
            json!([
                { "type": "text", "text": "look" },
                { "type": "image", "source": { "type": "base64", "media_type": "image/png", "data": "QUJD" } }
            ])
        );
    }

    #[test]
    fn empty_message_omits_text_block() {
        let imgs = r#"[{"media_type":"image/jpeg","data":"eHl6"}]"#;
        assert_eq!(
            build_user_content("", imgs),
            json!([
                { "type": "image", "source": { "type": "base64", "media_type": "image/jpeg", "data": "eHl6" } }
            ])
        );
    }

    #[test]
    fn media_type_defaults_to_png() {
        let imgs = r#"[{"data":"QQ=="}]"#;
        let v = build_user_content("", imgs);
        assert_eq!(v[0]["source"]["media_type"], "image/png");
    }

    #[test]
    fn malformed_or_all_invalid_falls_back_to_string() {
        assert_eq!(build_user_content("hi", "not json"), json!("hi"));
        // images present but every one lacks data → plain string, not an empty array
        assert_eq!(build_user_content("hi", r#"[{"media_type":"image/png"}]"#), json!("hi"));
        // a document without data, an empty text block, an unknown type → nothing attached
        let junk = r#"[{"type":"document","source":{"type":"text","data":""}},{"type":"text","text":""},{"type":"video"}]"#;
        assert_eq!(build_user_content("hi", junk), json!("hi"));
    }

    #[test]
    fn only_what_a_live_process_cannot_change_replaces_it() {
        use super::{spawn_signature, thinking_request, LiveSettings};
        // On a CLI that takes the allow flag, NO launch setting is in the signature:
        // every one of them is sent live, Auto included.
        let live = |perm: &str| spawn_signature("claude", "C:\\ws", 1, "tok", perm, true);
        assert_eq!(live("default"), live("plan"));
        assert_eq!(live("acceptEdits"), live("bypassPermissions"));
        // On one too old for it (or with the preference off), Auto is a launch-time
        // choice again, and going into or out of it replaces the process.
        let old = |perm: &str| spawn_signature("claude", "C:\\ws", 1, "tok", perm, false);
        assert_eq!(old("default"), old("plan"));
        assert_ne!(old("default"), old("bypassPermissions"));
        // Where it runs and what it talks to always part them.
        assert_ne!(live("default"), spawn_signature("claude", "C:\\other", 1, "tok", "default", true));
        assert_ne!(live("default"), spawn_signature("claude", "C:\\ws", 2, "tok", "default", true));

        // Model and effort are sent live (max included).
        let live = |effort: &str, model: &str| LiveSettings::new("default", effort, model, "2");
        assert!(live("max", "opus[1m]").reachable_from(&live("high", "sonnet"), false));
        // A Default tab adopting the model its first turn ran on keeps its process.
        assert!(live("high", "claude-sonnet-5").reachable_from(&live("high", ""), false));
        assert!(live("high", "sonnet").reachable_from(&live("", "sonnet"), false));
        // Back to Default: only when the CLI has no model setting of its own.
        assert!(live("high", "").reachable_from(&live("high", "sonnet"), true));
        assert!(!live("high", "").reachable_from(&live("high", "sonnet"), false));
        // Effort has no such reset, either way.
        assert!(!live("", "sonnet").reachable_from(&live("high", "sonnet"), true));

        assert_eq!(thinking_request("0")["max_thinking_tokens"], 0);
        let on = thinking_request("2");
        assert!(on["max_thinking_tokens"].is_null());
        assert_eq!(on["thinking_display"], "summarized");
        assert!(thinking_request("1").get("thinking_display").is_none());
    }

    #[test]
    fn a_process_is_judged_by_the_session_it_resumes_until_init() {
        use super::serves_conversation as serves;
        // Before init (a process ensure_process has just started).
        assert!(serves(None, "s1", "s1"));
        assert!(serves(None, "", ""));
        assert!(!serves(None, "s1", ""));
        assert!(!serves(None, "", "s1"));
        // After init, by the session the CLI reported.
        assert!(serves(Some("s1"), "", "s1"));
        assert!(serves(Some("s1"), "s1", "s1"));
        assert!(!serves(Some("s1"), "s1", "s2"));
        assert!(!serves(Some("s1"), "", ""));
    }

    #[test]
    fn chrome_set_reply_errors_are_worded_like_the_extension() {
        assert_eq!(super::chrome_set_error(&json!({"subtype":"success","response":{"added":["claude-in-chrome"],"removed":[],"errors":{}}})), None);
        assert_eq!(
            super::chrome_set_error(&json!({"subtype":"success","response":{"errors":{"claude-in-chrome":"spawn ENOENT","x":{"code":1}}}})),
            Some("claude-in-chrome: spawn ENOENT, x: {\"code\":1}".to_string())
        );
        assert_eq!(super::chrome_set_error(&json!({"subtype":"error","error":"bad request"})), Some("bad request".to_string()));
        assert_eq!(super::chrome_set_error(&json!({"subtype":"error"})), Some("Unknown error".to_string()));
        // A reply with no errors object at all is not a failure.
        assert_eq!(super::chrome_set_error(&json!({"subtype":"success","response":{}})), None);
    }

    #[test]
    fn documents_and_text_blocks_pass_through_in_order() {
        let items = r#"[
            {"type":"document","source":{"type":"text","media_type":"text/plain","data":"abc"},"title":"a.txt"},
            {"media_type":"image/png","data":"QUJD"},
            {"type":"document","source":{"type":"base64","media_type":"application/pdf","data":"JVBE"},"title":"b.pdf"},
            {"type":"text","text":"<browser tabGroupId=\"1\" tabId=\"2\"></browser>"}
        ]"#;
        assert_eq!(
            build_user_content("look", items),
            json!([
                { "type": "text", "text": "look" },
                { "type": "document", "source": { "type": "text", "media_type": "text/plain", "data": "abc" }, "title": "a.txt" },
                { "type": "image", "source": { "type": "base64", "media_type": "image/png", "data": "QUJD" } },
                { "type": "document", "source": { "type": "base64", "media_type": "application/pdf", "data": "JVBE" }, "title": "b.pdf" },
                { "type": "text", "text": "<browser tabGroupId=\"1\" tabId=\"2\"></browser>" }
            ])
        );
    }

    // ---- /usage parsing -------------------------------------------------
    // The sample below is the VERBATIM stdout of `claude -p "/usage"` captured
    // from the CLI on 2026-08-28; keep it byte-exact so a format change is
    // caught here rather than in the status bar.
    use super::{usage_json_from_text, percent_used_in};

    const REAL_USAGE_OUTPUT: &str = "\
You are currently using your subscription to power your Claude Code usage

Current session: 44% used · resets Aug 29, 2:10am (Asia/Irkutsk)
Current week (all models): 64% used · resets Aug 31, 8am (Asia/Irkutsk)

What's contributing to your limits usage?
Approximate, based on local sessions on this machine — does not include other devices or claude.ai. Behaviors are independent characteristics, not a breakdown.

Last 24h · 485 requests · 16 sessions
  64% of your usage was at >150k context
  Top MCP servers: eclipse 2%

Last 7d · 1048 requests · 17 sessions
  87% of your usage was at >150k context
  75% of your usage came from sessions active for 8+ hours
  Top MCP servers: eclipse 1%";

    #[test]
    fn parses_both_windows_from_real_output() {
        let v: serde_json::Value =
            serde_json::from_str(&usage_json_from_text(REAL_USAGE_OUTPUT).unwrap()).unwrap();
        assert_eq!(v["rate_limits"]["five_hour"]["used_percentage"], 44);
        assert_eq!(v["rate_limits"]["seven_day"]["used_percentage"], 64);
    }

    #[test]
    fn ignores_the_contributing_breakdown_percentages() {
        // "64% of your usage was at >150k context" must never be read as a
        // window value, and "Top MCP servers: eclipse 2%" must not either.
        let v: serde_json::Value =
            serde_json::from_str(&usage_json_from_text(REAL_USAGE_OUTPUT).unwrap()).unwrap();
        assert_eq!(v["rate_limits"].as_object().unwrap().len(), 2);
    }

    #[test]
    fn weekly_requires_the_all_models_qualifier() {
        // A per-model weekly line must not be mistaken for the account weekly.
        let txt = "Current session: 10% used\nCurrent week (Opus): 90% used";
        let v: serde_json::Value = serde_json::from_str(&usage_json_from_text(txt).unwrap()).unwrap();
        assert_eq!(v["rate_limits"]["five_hour"]["used_percentage"], 10);
        assert!(v["rate_limits"].get("seven_day").is_none());
    }

    #[test]
    fn one_window_alone_still_reports() {
        let txt = "Current session: 7% used · resets later";
        let v: serde_json::Value = serde_json::from_str(&usage_json_from_text(txt).unwrap()).unwrap();
        assert_eq!(v["rate_limits"]["five_hour"]["used_percentage"], 7);
        assert!(v["rate_limits"].get("seven_day").is_none());
    }

    #[test]
    fn unrecognized_output_yields_none_not_wrong_numbers() {
        assert!(usage_json_from_text("").is_none());
        assert!(usage_json_from_text("Login required to view usage.").is_none());
        // Format drift: the labels changed → report nothing rather than guess.
        assert!(usage_json_from_text("5h window: 44% used\n7d window: 64% used").is_none());
    }

    #[test]
    fn percent_scanner_handles_edges() {
        assert_eq!(percent_used_in("Current session: 0% used"), Some(0));
        assert_eq!(percent_used_in("Current session: 100% used"), Some(100));
        assert_eq!(percent_used_in("no digits % here"), None);
        assert_eq!(percent_used_in("nothing at all"), None);
    }

    // ---- isApiErrorMessage detection (assistant-branch dedup) -----------
    // The object below is a VERBATIM capture of the synthetic "assistant" event
    // the CLI emits for a session-limit-hit error (2.1.220, 2026-08-26) — it
    // carries isApiErrorMessage:true so process_event_value can skip streaming
    // it as ordinary text (the turn's is_error result already renders it once,
    // via onError). If the CLI ever stops marking these, this test breaks
    // instead of the duplicate line silently coming back.
    const REAL_API_ERROR_EVENT: &str = r#"{
        "type": "assistant",
        "message": {
            "model": "<synthetic>",
            "role": "assistant",
            "content": [
                { "type": "text", "text": "You've hit your session limit · resets 2:10am (Asia/Irkutsk)" }
            ]
        },
        "error": "rate_limit",
        "isApiErrorMessage": true,
        "apiErrorStatus": 429
    }"#;

    #[test]
    fn is_api_error_flag_detected_on_real_event() {
        let v: serde_json::Value = serde_json::from_str(REAL_API_ERROR_EVENT).unwrap();
        assert_eq!(v["isApiErrorMessage"].as_bool().unwrap_or(false), true);
    }

    #[test]
    fn is_api_error_flag_absent_on_ordinary_assistant_text() {
        let v: serde_json::Value = serde_json::json!({
            "type": "assistant",
            "message": { "content": [{ "type": "text", "text": "Sure, here's the fix." }] }
        });
        assert_eq!(v["isApiErrorMessage"].as_bool().unwrap_or(false), false);
    }
}
