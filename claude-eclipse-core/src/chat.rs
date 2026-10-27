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
    open_cards: std::collections::HashMap<String, CardOwner>,
    /// Requests withdrawn by the CLI while their card was still up. The waiting
    /// thread checks this before writing its `control_response`: the CLI has
    /// stopped listening for that id, so sending one is noise at best.
    cancelled_cards: std::collections::HashSet<String>,
    /// The agents the CLI has started, kept from its task events: whether one runs in the
    /// background decides whether its card outlives the turn (`CardsToCancel::TurnEnded`).
    agent_tasks: AgentTasks,
    /// FreeBSD: how many times in a row [`watch_for_hang`] has restarted this tab's CLI
    /// without the replacement getting anywhere before it froze too. Restarting is
    /// given up on once that keeps happening; any progress starts the count again.
    #[cfg(target_os = "freebsd")]
    hang_streak: u32,
}

/// FreeBSD: what [`watch_for_hang`] needs to start a replacement for a frozen CLI
/// on its own, with no message from the GUI to carry these.
#[cfg(target_os = "freebsd")]
struct Respawn {
    claude_cmd: String,
    workspace_root: String,
    mcp_port: u16,
    mcp_auth_token: String,
    java_vm: Arc<jni::JavaVM>,
    callbacks: Arc<jni::objects::GlobalRef>,
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
    /// The id of a model switch whose reply the Ultracode check is waiting for
    /// ([`CheckWhen::AfterModelReply`]); the reader sends the check when it arrives.
    ultracode_check_after: Mutex<Option<String>>,
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
    /// FreeBSD: when the CLI last wrote a line, in seconds since the epoch. Read by
    /// [`watch_for_hang`], which only looks closer after a long silence.
    #[cfg(target_os = "freebsd")]
    last_output: AtomicU64,
    /// FreeBSD: set when [`watch_for_hang`] ended this process because its CLI had
    /// frozen, so the reader's EOF says that instead of "exited unexpectedly".
    #[cfg(target_os = "freebsd")]
    frozen: AtomicBool,
    /// FreeBSD: how this process was started, for [`recover_from_hang`].
    #[cfg(target_os = "freebsd")]
    respawn: Respawn,
    /// FreeBSD: the user messages written to this process, as (stdin line, text), so
    /// the ones it never recorded can be sent to its replacement ([`unrecorded_messages`]).
    #[cfg(target_os = "freebsd")]
    sent: Mutex<Vec<(String, String)>>,
    /// FreeBSD: set while a Remote Control switch-on written to this process waits for
    /// its reply. A CLI that froze between turns is then still looked at by
    /// [`watch_for_hang`], before the page gives up, and its replacement is asked again.
    #[cfg(target_os = "freebsd")]
    rc_switching_on: AtomicBool,
    /// FreeBSD: whether Claude's latest reply on this process has ended (`end_turn`),
    /// so a freeze after it costs nothing but the turn's end ([`end_turn_quietly`]).
    #[cfg(target_os = "freebsd")]
    reply_done: AtomicBool,
    /// FreeBSD: whether Claude has streamed anything on this process, so a restart
    /// that led somewhere is told from one that froze straight away ([`watch_for_hang`]).
    #[cfg(target_os = "freebsd")]
    progressed: AtomicBool,
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
    ultracode: bool,
}

impl LiveSettings {
    fn new(
        perm_mode: &str,
        effort: &str,
        model: &str,
        thinking: &str,
        ultracode: bool,
    ) -> Self {
        LiveSettings {
            perm_mode: perm_mode.to_string(),
            effort: effort.to_string(),
            model: model.to_string(),
            thinking: thinking.to_string(),
            ultracode,
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

/// The flag that has the CLI replay user messages on stdout, among them the ones it was
/// not sent on stdin. See where it is passed, in `spawn_persistent`.
const REPLAY_FLAG: &str = "--replay-user-messages";

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

/// The `--settings` JSON for Ultracode at launch. A plain boolean with no Default state
/// (unlike model/effort), so always passed explicitly — the GUI's per-tab preference
/// overrides the user's own settings.json, same as --effort/--model/--permission-mode
/// already do.
///
/// On, it takes dynamic workflows on with it ([`ultracode_settings`]). Off, it says
/// nothing of them: whether they are on is then the user's own setting, as it was.
fn flag_settings_arg(ultracode: bool) -> String {
    if ultracode {
        ultracode_settings(true).to_string()
    } else {
        serde_json::json!({ "ultracode": false }).to_string()
    }
}

/// The flag settings that switch Ultracode on or off on a running process.
///
/// Ultracode is the Workflow tool used on every substantive task, and the CLI keeps it
/// off while dynamic workflows are off (`applied.ultracodeAvailable`), which they are by
/// default: on its own the switch did nothing (verified against 2.1.292 on every model).
/// So switching it on switches them on with it, as a flag setting of this conversation;
/// a user who has `disableWorkflows` set keeps that (verified), and [`ultracode_in_effect`]
/// then says so. Switching it off clears the key again (`null`), which hands dynamic
/// workflows back to the user's own setting.
fn ultracode_settings(on: bool) -> serde_json::Value {
    if on {
        serde_json::json!({ "ultracode": true, "enableWorkflows": true })
    } else {
        serde_json::json!({ "ultracode": false, "enableWorkflows": null })
    }
}

/// Request ids of the "did Ultracode take effect" read-back. Not [`SETTINGS_PREFIX`]'s:
/// that reply is the Remote Control poll's, and is read for what moved since the last.
const ULTRACODE_PREFIX: &str = "eclipse-ultracode-";

/// One settings request asking whether the Ultracode just switched on took effect. The
/// CLI answers the switch itself with success either way; what it applied is only in
/// its settings ([`ultracode_in_effect`]).
fn ultracode_check_line() -> String {
    static ULTRACODE_SEQ: AtomicU64 = AtomicU64::new(1);
    serde_json::json!({
        "type": "control_request",
        "request_id": format!("{}{}", ULTRACODE_PREFIX, ULTRACODE_SEQ.fetch_add(1, Ordering::Relaxed)),
        "request": { "subtype": "get_settings" }
    })
    .to_string()
}

/// Whether Ultracode is in effect, from a `get_settings` reply: `applied.ultracode` is
/// what was asked for AND what the model and dynamic workflows allow. None from a CLI
/// that says nothing of it.
fn ultracode_in_effect(response: &serde_json::Value) -> Option<bool> {
    response["applied"]["ultracode"].as_bool()
}

/// What the reply to [`ultracode_check_line`] comes to, given the whole control response
/// (`inner`, a success with its `response` or an error): whether the CLI confirms that
/// Ultracode is running. The check is only sent with the switch on, so a CLI that says
/// nothing of Ultracode (one from before the setting) or refuses the request has not
/// confirmed it, and the page takes the switch back off rather than leave it on over
/// nothing.
fn ultracode_confirmed(inner: &serde_json::Value) -> bool {
    ultracode_in_effect(&inner["response"]).unwrap_or(false)
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

/// Request ids of the live-settings requests, so the reader can tell their replies apart.
const LIVE_PREFIX: &str = "eclipse-live-";

/// Wraps a live-settings request as a control-request line, with the id its reply will carry.
fn live_request(request: serde_json::Value) -> (String, String) {
    static LIVE_SEQ: AtomicU64 = AtomicU64::new(1);
    if crate::is_debug() {
        crate::elog!("[live-settings] {}", request);
    }
    let id = format!("{}{}", LIVE_PREFIX, LIVE_SEQ.fetch_add(1, Ordering::Relaxed));
    let line = serde_json::json!({
        "type": "control_request",
        "request_id": id,
        "request": request
    })
    .to_string();
    (id, line)
}

/// [`live_request`] for a request whose reply nothing waits on.
fn live_request_line(request: serde_json::Value) -> String {
    live_request(request).1
}

/// When to ask whether Ultracode took effect ([`ultracode_check_line`]) after live settings
/// were sent.
#[derive(Debug, PartialEq)]
enum CheckWhen {
    /// Straight behind what was sent: right after `apply_flag_settings` (verified).
    Now,
    /// Once the model switch has been answered. A settings request written right behind
    /// `set_model` is answered from the model the process was on (verified against 2.1.295),
    /// so Ultracode read as in effect on a model that cannot run it, and the switch stayed on.
    AfterModelReply,
    /// Ultracode is off: there is nothing to confirm.
    Never,
}

fn ultracode_check_when(model_changed: bool, ultracode: bool) -> CheckWhen {
    match (ultracode, model_changed) {
        (false, _) => CheckWhen::Never,
        (true, true) => CheckWhen::AfterModelReply,
        (true, false) => CheckWhen::Now,
    }
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
    // The id of the model switch, when one is sent: the Ultracode check waits on its reply.
    let mut model_request: Option<String> = None;
    if live.model != want.model {
        // Empty is "Default": null resets the process to the model it would pick with
        // no setting, which is what a fresh Default process launches on.
        let model = if want.model.is_empty() {
            serde_json::Value::Null
        } else {
            serde_json::json!(want.model)
        };
        let (id, line) = live_request(serde_json::json!({ "subtype": "set_model", "model": model }));
        p.write_line(&line)?;
        model_request = Some(id);
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
    if live.ultracode != want.ultracode {
        p.write_line(&live_request_line(serde_json::json!({
            "subtype": "apply_flag_settings",
            "settings": ultracode_settings(want.ultracode)
        })))?;
        live.ultracode = want.ultracode;
    }
    // Asked after whatever changed, a model as much as the switch: Ultracode that was
    // in effect on one model is not on another that cannot run it.
    match ultracode_check_when(model_request.is_some(), want.ultracode) {
        CheckWhen::Now => p.write_line(&ultracode_check_line())?,
        CheckWhen::AfterModelReply => *p.ultracode_check_after.lock().unwrap() = model_request,
        CheckWhen::Never => {}
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
        crate::elog!("[live-settings] changed elsewhere: model={:?} effort={:?}", model, effort);
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
            crate::elog!("[remote-control] not carried: bridge was on {:?}, new process is on {:?}",
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
        crate::elog!("[remote-control] carrying bridge {:?} to the replacement process", carry.bridge_session_id);
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
    /// Held from "is there a live process?" to "this is the tab's process now" by every
    /// path that may start one — a send, [`ChatManager::ensure_process`],
    /// [`ChatManager::continue_interrupted`]. Without it two of them could each find none
    /// and each start one, and the second would replace the first without stopping it:
    /// two processes on one conversation, one of them running unseen.
    spawn_lock: Arc<Mutex<()>>,
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
                open_cards: std::collections::HashMap::new(),
                cancelled_cards: std::collections::HashSet::new(),
                agent_tasks: AgentTasks::new(),
                #[cfg(target_os = "freebsd")]
                hang_streak: 0,
            })),
            callbacks: Arc::new(Mutex::new(None)),
            spawn_lock: Arc::new(Mutex::new(())),
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
        ultracode: bool,
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
                    #[cfg(target_os = "freebsd")]
                    note_sent(&p, msg_json.to_string(), &message);
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
                resume_id, perm_mode, effort, model, thinking, ultracode,
                images_json, java_vm, callbacks_obj,
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
                    ultracode,
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
        ultracode: bool,
    ) -> bool {
        let _spawning = self.spawn_lock.lock().unwrap();
        self.ensure_process_locked(claude_cmd, workspace_root, mcp_port, mcp_auth_token, resume_id,
                                   perm_mode, effort, model, thinking, ultracode, false)
    }

    /// Continue After Reload: starts this tab's process on the conversation `resume_id`
    /// asking the CLI to re-run its last turn if Eclipse's closing cut it off. The CLI
    /// decides whether it was (resume_turn.rs); its turn, when it runs one, comes in like a
    /// queued message's, opening the stream on its first frame. Nothing is sent.
    ///
    /// Only when the transcript is recent, no other live process holds the conversation,
    /// and this tab has no live process yet — a live one means nothing was cut off here.
    /// Returns what was decided, for the log: `continued`, `live`, `failed`, `no-session`,
    /// or one of [`resume_turn::Decision`]'s.
    ///
    /// **Blocking** — may spawn a child process. Call it off the UI thread.
    #[allow(clippy::too_many_arguments)]
    pub fn continue_interrupted(
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
        ultracode: bool,
    ) -> &'static str {
        if resume_id.is_empty() || !crate::bookmarks::plain_id(&resume_id) {
            return "no-session";
        }
        let Some(transcript) = crate::session::projects_dir(&workspace_root)
            .map(|d| d.join(format!("{resume_id}.jsonl")))
        else {
            return crate::resume_turn::Decision::NoTranscript.as_str();
        };
        let sessions = crate::session::config_dir().map(|d| d.join("sessions"));
        let decision = crate::resume_turn::decide(&transcript, sessions.as_deref(), &resume_id,
                                                  std::time::SystemTime::now(), &crate::resume_turn::pid_alive);
        if decision != crate::resume_turn::Decision::Continue {
            return decision.as_str();
        }
        // A conversation whose last turn finished, or was stopped, has nothing to continue:
        // no process is started for it just because it was used within the hour.
        if !crate::resume_turn::tail_unfinished(&transcript) {
            return "not-cut-off";
        }
        // Checked under the spawn lock: a send that got in first has started the process,
        // or is about to — it carries the conversation on, and a second start would replace it.
        let _spawning = self.spawn_lock.lock().unwrap();
        {
            let s = self.state.lock().unwrap();
            if s.awaiting || s.proc.as_ref().is_some_and(|p| !p.is_dead()) {
                return "live";
            }
        }
        if self.ensure_process_locked(claude_cmd, workspace_root, mcp_port, mcp_auth_token, resume_id,
                                      perm_mode, effort, model, thinking, ultracode, true) {
            "continued"
        } else {
            "failed"
        }
    }

    /// [`ChatManager::ensure_process`]'s work, for a caller holding `spawn_lock`.
    #[allow(clippy::too_many_arguments)]
    fn ensure_process_locked(
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
        ultracode: bool,
        continue_interrupted: bool,
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
        let want = LiveSettings::new(&perm_mode, &effort, &model, &thinking, ultracode);

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
            &resume_id, &perm_mode, &effort, &model, &thinking, ultracode, sig, continue_interrupted,
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
        #[cfg(target_os = "freebsd")]
        p.rc_switching_on.store(enabled, Ordering::Relaxed);
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
    pub fn apply_settings_now(
        &self,
        perm_mode: &str,
        effort: &str,
        model: &str,
        thinking: &str,
        ultracode: bool,
    ) -> bool {
        let proc = self.state.lock().unwrap().proc.clone();
        let Some(p) = proc else { return false };
        if p.is_dead() {
            return false;
        }
        apply_live_settings(&p, &LiveSettings::new(perm_mode, effort, model, thinking, ultracode)).is_ok()
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

    /// Asks this manager's live process for what one of the CLI's dialogs shows
    /// (`get_memory_dialog`, `get_status`, …), under the page's `token`. The reply
    /// reaches Java as `onCliReply`. False when there is no live process, or the
    /// request is not one the page may send ([`crate::cli_ask::request_line`]).
    pub fn cli_request(&self, token: &str, request: &str) -> bool {
        let Ok(request) = serde_json::from_str::<serde_json::Value>(request) else { return false };
        let Some(line) = crate::cli_ask::request_line(token, &request) else { return false };
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
        ultracode: bool,
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
        let spawn_lock = Arc::clone(&self.spawn_lock);

        std::thread::Builder::new()
            .name("claude-chat-send".into())
            .spawn(move || {
                fire_void(&java_vm, &callbacks, "onStreamStart");

                let sig = spawn_signature(&claude_cmd, &workspace_root, mcp_port, &mcp_auth_token, &perm_mode,
                                          allow_skip_flag(&claude_cmd));
                let want = LiveSettings::new(&perm_mode, &effort, &model, &thinking, ultracode);

                // From the look at the tab's process to installing a new one (ChatManager::spawn_lock).
                let spawning = spawn_lock.lock().unwrap();

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
                            &resume_id, &perm_mode, &effort, &model, &thinking,
                            ultracode, sig, false,
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
                drop(spawning);

                let msg_json = serde_json::json!({
                    "type": "user",
                    "message": { "role": "user", "content": build_user_content(&message, &images_json) }
                });
                #[cfg(target_os = "freebsd")]
                note_sent(&proc, msg_json.to_string(), &message);
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
        // Persistent process must not outlive the view (chatDestroy → drop). It is ended,
        // NOT interrupted first: an interrupt is the Stop button, and the CLI writes it into
        // the transcript as the user stopping the turn. Eclipse closing mid-turn would then
        // read as a stop, and Continue After Reload would rightly never re-run that turn
        // (seen 2026-10-27). A tab the user closes mid-turn has had its Stop sent by the
        // page before this (tabs.js closeTab → _cancelRequest).
        if let Ok(mut s) = self.state.lock() {
            // The per-message mode's loop ends its own child when it sees this.
            s.cancel.store(true, Ordering::Relaxed);
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
/// context such as the browser blocks. An `{"type":"ide_context",…}` entry is the
/// editor context: it becomes a text block AHEAD of the message, wherever it was
/// listed. Malformed / empty input degrades to the plain-string form.
fn build_user_content(message: &str, images_json: &str) -> serde_json::Value {
    let items: Vec<serde_json::Value> = if images_json.trim().is_empty() {
        Vec::new()
    } else {
        serde_json::from_str(images_json).unwrap_or_default()
    };
    let mut content: Vec<serde_json::Value> = Vec::new();
    let mut attached = 0;
    for item in &items {
        if item.get("type").and_then(|v| v.as_str()) == Some("ide_context") {
            if let Some(text) = editor_context_text(item) {
                content.push(serde_json::json!({ "type": "text", "text": text }));
                attached += 1;
            }
        }
    }
    if !message.is_empty() {
        content.push(serde_json::json!({ "type": "text", "text": message }));
    }
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

/// The editor context (`{filePath, startLine, endLine, text}`) in the wording the
/// CLI's other IDE clients send. Viewers of the conversation outside this plugin
/// (claude.ai, the mobile app) only recognise this exact form and show anything
/// else as part of what the user typed, so it must stay byte-exact.
fn editor_context_text(item: &serde_json::Value) -> Option<String> {
    let path = item["filePath"].as_str().filter(|p| !p.is_empty())?;
    let selected = item["text"].as_str().unwrap_or("");
    if selected.is_empty() {
        return Some(format!(
            "<ide_opened_file>The user opened the file {path} in the IDE. This may or may not be related to the current task.</ide_opened_file>"
        ));
    }
    let start = item["startLine"].as_i64().unwrap_or(0);
    let end = item["endLine"].as_i64().unwrap_or(0);
    Some(format!(
        "<ide_selection>The user selected the lines {start} to {end} from {path}:\n{selected}\n\nThis may or may not be related to the current task.</ide_selection>"
    ))
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
    ultracode: bool,
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
    cmd_args.push("--settings".into());
    cmd_args.push(flag_settings_arg(ultracode));
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
        // built-in Edit (gated by the permission card + our non-blocking DiffPreview),
        // NOT openDiff (which gates as "allow openDiff?" then
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
    // Never a continue here: none inherited (see spawn_persistent).
    for name in crate::resume_turn::inherited_resume_vars(std::env::vars_os().filter_map(|(k, _)| k.into_string().ok())) {
        cmd.env_remove(name);
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

    // Tracks cumulative text already sent per conversation LINEAGE, so deltas from
    // partial assistant events are computed correctly — keyed by parent_tool_use_id
    // ("" for the top-level conversation). A subagent (Task/Agent tool) multiplexes its
    // OWN assistant events onto this same stream, each cumulative independently of the
    // top-level one; a single shared counter would corrupt delta math for whichever
    // lineage didn't own it at the moment (see process_event_value).
    let mut cursors: std::collections::HashMap<String, (usize, usize, u64)> = std::collections::HashMap::new();
    // Live output-token counter state (from --include-partial-messages).
    let mut tokens = TurnUsage::default();

    for line in reader.lines() {
        if cancel.load(Ordering::Relaxed) {
            break;
        }
        let line = match line {
            Ok(l) if !l.is_empty() => l,
            _ => continue,
        };
        process_event(&line, java_vm, callbacks, &mut cursors, &mut tokens);
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
    ultracode: bool,
    spawn_sig: String,
    continue_interrupted: bool,
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
        // CLI blocks each gated tool until our control_response.
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
    // The CLI says back the user-side lines it did not get from us: a background task's
    // notification, a message from a subagent. VS Code launches it this way for the same.
    // A CLI that does not know the flag would abort on it.
    if crate::launch::cli_supports_flag(claude_cmd, REPLAY_FLAG) {
        cmd_args.push(REPLAY_FLAG.into());
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
    cmd_args.push("--settings".into());
    cmd_args.push(flag_settings_arg(ultracode));
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
        // handle edits). The legacy question shim is superseded here: questions
        // now flow through the built-in AskUserQuestion via the control channel.
        // The real diff tools are the qualified MCP names; a bare "openDiff" is
        // not a known tool and the CLI warns on it ("matches no known tool").
        cmd_args.push("--disallowed-tools".into());
        cmd_args.push("mcp__ide__openDiff".into());
        cmd_args.push("mcp__eclipse__openDiff".into());
        cmd_args.push("mcp__eclipse__askUserQuestion".into());
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

    // Continue After Reload (resume_turn.rs): only the spawn that asks for it may tell the
    // CLI to re-run an interrupted turn. Never one inherited from whatever started Eclipse —
    // every send, tab switch and respawn would re-run a turn.
    for name in crate::resume_turn::inherited_resume_vars(std::env::vars_os().filter_map(|(k, _)| k.into_string().ok())) {
        cmd.env_remove(name);
    }
    if continue_interrupted {
        for (k, v) in crate::resume_turn::resume_vars() {
            cmd.env(k, v);
        }
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
        stdin: Mutex::new(Some(stdin)),
        child: Mutex::new(child),
        session_id: Mutex::new(None),
        spawn_sig,
        alive: AtomicBool::new(true),
        chrome_enabled: AtomicBool::new(false),
        spawn_resume: resume_id.to_string(),
        rc_session: Mutex::new(None),
        live: Mutex::new(LiveSettings::new(perm_mode, effort, model, thinking, ultracode)),
        settings_poll: AtomicBool::new(false),
        fdescfs_diagnosed: AtomicBool::new(false),
        last_applied: Mutex::new(None),
        ultracode_check_after: Mutex::new(None),
        default_model_live: AtomicBool::new(false),
        #[cfg(target_os = "freebsd")]
        last_output: AtomicU64::new(epoch_secs()),
        #[cfg(target_os = "freebsd")]
        frozen: AtomicBool::new(false),
        #[cfg(target_os = "freebsd")]
        respawn: Respawn {
            claude_cmd: claude_cmd.to_string(),
            workspace_root: workspace_root.to_string(),
            mcp_port,
            mcp_auth_token: mcp_auth_token.to_string(),
            java_vm: Arc::clone(&java_vm),
            callbacks: Arc::clone(&callbacks),
        },
        #[cfg(target_os = "freebsd")]
        sent: Mutex::new(Vec::new()),
        #[cfg(target_os = "freebsd")]
        rc_switching_on: AtomicBool::new(false),
        #[cfg(target_os = "freebsd")]
        reply_done: AtomicBool::new(false),
        #[cfg(target_os = "freebsd")]
        progressed: AtomicBool::new(false),
    });
    if thinking == "0" {
        // A failed write surfaces as the reader's EOF, like any dead process.
        let _ = proc.write_line(&live_request_line(thinking_request("0")));
    }
    // Asked once at startup so the answer — which decides whether a later switch back
    // to Default can be done on this process — is in before the user can pick it.
    let _ = proc.write_line(&settings_request_line());
    // Launched with Ultracode on: whether this model and the user's settings let it be.
    if ultracode {
        let _ = proc.write_line(&ultracode_check_line());
    }

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

    #[cfg(target_os = "freebsd")]
    {
        let proc = Arc::clone(&proc);
        let state = Arc::clone(&state);
        std::thread::Builder::new()
            .name("claude-chat-hang-watch".into())
            .spawn(move || watch_for_hang(proc, state))
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

#[cfg(target_os = "freebsd")]
fn epoch_secs() -> u64 {
    std::time::SystemTime::now()
        .duration_since(std::time::UNIX_EPOCH)
        .map(|d| d.as_secs())
        .unwrap_or(0)
}

/// FreeBSD: replaces this process if its CLI has frozen for good (see
/// [`crate::bridge::main_thread_parked`]), so the turn carries on instead of spinning
/// until the tab is closed: [`recover_from_hang`] resumes the conversation in a new
/// process and tells it to continue; one that froze before naming its session starts
/// over with the messages sent to it. After Stop it is just ended, as Stop would have.
/// After three restarts in a row with no progress in between it stops trying: the
/// process is killed while still `alive`, the reader's EOF ends the turn with the
/// guide's `hang_recovery.gave_up` ([`crate::freebsd_guide::hang_gave_up`]), and the
/// next message resumes the conversation, as reopening the tab did.
///
/// Nothing is looked at unless a turn is open and the CLI has written nothing for
/// [`QUIET_SECS`]; then the main thread has to be found parked on two checks in a
/// row. A CLI that is thinking, streaming or running a long tool is never touched,
/// since its main thread waits in `epoll`, not in a futex.
#[cfg(target_os = "freebsd")]
fn watch_for_hang(proc: Arc<ProcHandle>, state: Arc<Mutex<ChatState>>) {
    const EVERY: std::time::Duration = std::time::Duration::from_secs(15);
    // Between turns under Remote Control: a message from the phone or claude.ai can
    // come at any moment, and a frozen CLI never passes it on, so nothing else would
    // notice. Looked at more often, and without waiting for a silence first.
    const RC_IDLE_EVERY: std::time::Duration = std::time::Duration::from_secs(10);
    const QUIET_SECS: u64 = 45;
    let pid = proc.child.lock().unwrap().id();
    let mut parked_before = false;
    let mut every = EVERY;
    loop {
        std::thread::sleep(every);
        if !proc.alive.load(Ordering::Relaxed) {
            return;
        }
        let quiet_for = epoch_secs().saturating_sub(proc.last_output.load(Ordering::Relaxed));
        // Read on its own: retire_proc takes this lock before the state's.
        let rc_on = proc.rc_session.lock().unwrap().is_some();
        let (in_turn, switching_on, current) = {
            let s = state.lock().unwrap();
            let current = s.proc.as_ref().is_some_and(|cur| Arc::ptr_eq(cur, &proc));
            (current && s.awaiting, current && proc.rc_switching_on.load(Ordering::Relaxed), current)
        };
        let rc_idle = current && rc_on && !in_turn;
        every = if rc_idle { RC_IDLE_EVERY } else { EVERY };
        if !(in_turn || switching_on || rc_idle) || (!rc_idle && quiet_for < QUIET_SECS) {
            parked_before = false;
            continue;
        }
        let parked = crate::bridge::main_thread_parked(pid);
        if parked && parked_before {
            const MAX_FRUITLESS_RESTARTS: u32 = 3;
            let resume = proc.conversation();
            // Between turns, with Remote Control on or a switch-on waiting, there is no
            // message to send again and nothing to carry on from: the replacement just
            // takes the bridge back over, or is asked for Remote Control again
            // ([`recover_from_hang`]).
            //
            // Before the CLI has named its session nothing is recorded anywhere, so a
            // new process starts the conversation over with everything that was sent.
            let unrecorded = if !in_turn {
                Vec::new()
            } else if resume.is_empty() {
                proc.sent.lock().unwrap().clone()
            } else {
                unrecorded_messages(&proc, &resume)
            };
            if in_turn && proc.reply_done.load(Ordering::Relaxed) && unrecorded.is_empty() {
                // Frozen between finishing its reply and saying the turn was over:
                // nothing is left to do, so the turn just ends.
                if crate::is_debug() {
                    crate::elog!("[hang-watch] pid {}: frozen after its reply was complete; ending the turn", pid);
                }
                end_turn_quietly(&proc, &state);
                return;
            }
            let (streak, cancelled) = {
                let mut s = state.lock().unwrap();
                if proc.progressed.load(Ordering::Relaxed) {
                    s.hang_streak = 0;
                }
                (s.hang_streak, s.cancel.load(Ordering::Relaxed))
            };
            if in_turn && cancelled {
                // Stop was pressed, and a frozen CLI never acts on its interrupt: end it
                // the way Stop would have, without a word.
                if crate::is_debug() {
                    crate::elog!("[hang-watch] pid {}: frozen after Stop; ending it", pid);
                }
                proc.kill();
                return;
            }
            // A conversation that was never recorded has nothing to carry on from.
            let carry_on = if !in_turn || resume.is_empty() { None } else { hang_carry_on_line() };
            if streak >= MAX_FRUITLESS_RESTARTS || (in_turn && unrecorded.is_empty() && carry_on.is_none()) {
                if crate::is_debug() {
                    crate::elog!("[hang-watch] pid {}: frozen, nothing written for {}s ({} restarts in a row \
                               got nowhere); ending it", pid, quiet_for, streak);
                }
                proc.frozen.store(true, Ordering::Relaxed);
                proc.kill();
            } else {
                if crate::is_debug() {
                    crate::elog!("[hang-watch] pid {}: frozen, nothing written for {}s; restarting it on {} \
                               ({} unrecorded message(s) sent again)", pid, quiet_for, resume, unrecorded.len());
                }
                state.lock().unwrap().hang_streak += 1;
                recover_from_hang(&proc, &state, &resume, unrecorded, carry_on);
            }
            return;
        }
        parked_before = parked;
    }
}

/// FreeBSD: swaps a frozen CLI for a new one on the same conversation, mid-turn and
/// without a word to the GUI. The frozen one is retired like any respawn (`alive`
/// cleared first, so its reader's EOF stays silent; its Remote Control and browser
/// carried over). The new one resumes the session with the old one's current
/// settings and is given what the old one never got to: the user's `unrecorded`
/// messages, sent again in order, or when there are none the guide's carry-on
/// ([`hang_carry_on_line`]), whose marker keeps the conversation's history from
/// showing it as the user's. The turn stays open throughout: the GUI keeps its
/// spinner and the partial reply, and the new process streams into the same callbacks.
#[cfg(target_os = "freebsd")]
fn recover_from_hang(
    old: &Arc<ProcHandle>,
    state: &Arc<Mutex<ChatState>>,
    resume: &str,
    unrecorded: Vec<(String, String)>,
    carry_on: Option<String>,
) {
    retire_proc(state, old);
    let r = &old.respawn;
    let live = old.live.lock().unwrap().clone();
    match spawn_persistent(
        &r.claude_cmd, &r.workspace_root, r.mcp_port, &r.mcp_auth_token, resume,
        &live.perm_mode, &live.effort, &live.model, &live.thinking, live.ultracode,
        old.spawn_sig.clone(), false, Arc::clone(state), Arc::clone(&r.java_vm), Arc::clone(&r.callbacks),
    ) {
        Ok(p) => {
            state.lock().unwrap().proc = Some(Arc::clone(&p));
            restore_chrome(state, &p, &r.claude_cmd, resume, &r.java_vm, &r.callbacks);
            restore_remote_control(state, &p, resume, &r.java_vm, &r.callbacks);
            if old.rc_switching_on.load(Ordering::Relaxed) {
                // The page still waits for the switch-on the frozen one never read; this
                // one's answer reaches it the same way.
                static HANG_RC_SEQ: AtomicU64 = AtomicU64::new(1 << 40);
                let (_, line) = crate::bridge::rc_request_line(HANG_RC_SEQ.fetch_add(1, Ordering::Relaxed), true);
                p.rc_switching_on.store(true, Ordering::Relaxed);
                let _ = p.write_line(&line);
            }
            // A failed write surfaces as the new reader's EOF, like any dead process.
            if unrecorded.is_empty() {
                if let Some(line) = carry_on {
                    let _ = p.write_line(&line);
                }
            } else {
                for (line, text) in unrecorded {
                    let _ = p.write_line(&line);
                    p.sent.lock().unwrap().push((line, text));
                }
            }
        }
        Err(e) => {
            fire_string(&r.java_vm, &r.callbacks, "onError",
                        &format!("Claude Code froze and could not be restarted: {}", e));
            fire_void(&r.java_vm, &r.callbacks, "onStreamEnd");
            let mut s = state.lock().unwrap();
            s.awaiting = false;
            s.proc = None;
        }
    }
}

/// FreeBSD: the stdin line telling a CLI restarted by [`recover_from_hang`] to carry
/// on: the guide's `hang_recovery.marker` and `carry_on`. `None` when the guide lacks
/// either, and then a frozen CLI is ended rather than restarted.
#[cfg(target_os = "freebsd")]
fn hang_carry_on_line() -> Option<String> {
    let marker = crate::freebsd_guide::hang_resume_marker()?;
    let carry_on = crate::freebsd_guide::hang_carry_on()?;
    Some(serde_json::json!({
        "type": "user",
        "message": { "role": "user", "content": format!("{} {}", marker, carry_on) }
    }).to_string())
}

/// FreeBSD: remembers a user message written to `p` ([`unrecorded_messages`]). A reply
/// to it is now due, so the last one being complete no longer means the turn is.
#[cfg(target_os = "freebsd")]
fn note_sent(p: &ProcHandle, line: String, text: &str) {
    p.sent.lock().unwrap().push((line, text.to_string()));
    p.reply_done.store(false, Ordering::Relaxed);
}

/// FreeBSD: ends the open turn of a CLI that froze after its reply was complete, with
/// nothing to show for it: the process is retired like any respawn, and the next
/// message resumes the conversation in a new one.
#[cfg(target_os = "freebsd")]
fn end_turn_quietly(proc: &Arc<ProcHandle>, state: &Arc<Mutex<ChatState>>) {
    retire_proc(state, proc);
    {
        let mut s = state.lock().unwrap();
        s.awaiting = false;
        if s.proc.as_ref().is_some_and(|cur| Arc::ptr_eq(cur, proc)) {
            s.proc = None;
        }
    }
    fire_void(&proc.respawn.java_vm, &proc.respawn.callbacks, "onStreamEnd");
}

/// FreeBSD: the user messages written to a frozen CLI that it never recorded, in the
/// order sent, as (stdin line, text). The CLI writes a message to the conversation's
/// transcript once it has read it, so whatever is missing from there died with it.
#[cfg(target_os = "freebsd")]
fn unrecorded_messages(p: &ProcHandle, resume: &str) -> Vec<(String, String)> {
    let sent = p.sent.lock().unwrap().clone();
    if sent.is_empty() {
        return sent;
    }
    let recorded = recent_user_texts(&p.respawn.workspace_root, resume);
    sent.into_iter().filter(|(_, text)| !recorded.iter().any(|t| t.contains(text.as_str()))).collect()
}

/// FreeBSD: the text of the user lines near the end of a conversation's transcript.
#[cfg(target_os = "freebsd")]
fn recent_user_texts(workspace_root: &str, session_id: &str) -> Vec<String> {
    const TAIL_BYTES: usize = 1 << 20;
    let Some(dir) = crate::session::projects_dir(workspace_root) else { return Vec::new() };
    let Ok(bytes) = std::fs::read(dir.join(format!("{}.jsonl", session_id))) else { return Vec::new() };
    let tail = String::from_utf8_lossy(&bytes[bytes.len().saturating_sub(TAIL_BYTES)..]).into_owned();
    tail.lines()
        .filter_map(|l| serde_json::from_str::<serde_json::Value>(l).ok())
        .filter(|v| v["type"] == "user")
        .map(|v| {
            let c = &v["message"]["content"];
            match c.as_str() {
                Some(s) => s.to_string(),
                None => c.as_array()
                    .map(|blocks| blocks.iter().filter_map(|b| b["text"].as_str()).collect::<Vec<_>>().join("\n"))
                    .unwrap_or_default(),
            }
        })
        .collect()
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
    let mut tokens = TurnUsage::default();
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
    // The hand-back lines already sent on, by task and ending: the CLI can replay one twice.
    let mut told_handbacks: std::collections::HashSet<String> = std::collections::HashSet::new();

    for line in reader.lines() {
        let line = match line {
            Ok(l) if !l.is_empty() => l,
            Ok(_) => continue,
            Err(_) => break,
        };
        #[cfg(target_os = "freebsd")]
        proc.last_output.store(epoch_secs(), Ordering::Relaxed);
        let mut event: serde_json::Value = match serde_json::from_str(&line) {
            Ok(v) => v,
            Err(_) => continue,
        };
        #[cfg(target_os = "freebsd")]
        if event["type"] == "stream_event" {
            proc.progressed.store(true, Ordering::Relaxed);
            match event["event"]["type"].as_str() {
                Some("message_start") => proc.reply_done.store(false, Ordering::Relaxed),
                Some("message_delta") => proc.reply_done.store(
                    event["event"]["delta"]["stop_reason"] == "end_turn", Ordering::Relaxed),
                _ => {}
            }
        }

        // Whether fast mode is on rides on the init event and on each turn's result,
        // both of which are also handled below.
        if let Some(json) = crate::cli_ask::fast_mode_json(&event) {
            fire_string(&java_vm, &callbacks, "onFastMode", &json);
        }
        // Which agents run in the background, read before this event is handled: a turn's
        // result may take down the cards of the others (CardsToCancel::TurnEnded).
        if event["type"].as_str() == Some("system")
            && event["subtype"].as_str().is_some_and(|s| s.starts_with("task_"))
        {
            let mut st = state.lock().unwrap();
            // Said on the event for the line a completed agent's hand-back leaves (the page's
            // applyAgentActivity): "finished", or still waiting on agents of its own.
            if event["subtype"].as_str() == Some("task_notification") {
                if let Some(id) = event["task_id"].as_str() {
                    let waiting = waits_on_its_own(&st.agent_tasks, id);
                    event["waiting_on_background"] = serde_json::Value::Bool(waiting);
                }
            }
            note_agent_task(&mut st.agent_tasks, &event);
        }

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
                        crate::elog!("[remote-control] raw control_response: {}", inner);
                    }
                    let reply = crate::bridge::rc_parse_reply(inner);
                    let json = crate::bridge::rc_reply_json(&reply);
                    #[cfg(target_os = "freebsd")]
                    proc.rc_switching_on.store(false, Ordering::Relaxed);
                    if crate::is_debug() {
                        crate::elog!("[remote-control] {}", json);
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
                } else if rid.starts_with(ULTRACODE_PREFIX) {
                    // The page shows the switch as it was set; it is told what the CLI
                    // made of it, and takes the switch back off when that is "not on".
                    let on = ultracode_confirmed(inner);
                    if crate::is_debug() {
                        crate::elog!("[live-settings] ultracode in effect: {on} ({:?})", inner["response"]["applied"]);
                    }
                    fire_string(&java_vm, &callbacks, "onSettingsChanged",
                                &serde_json::json!({ "ultracode": on }).to_string());
                } else if rid.starts_with(LIVE_PREFIX) {
                    // The model switch an Ultracode check was waiting on has been answered:
                    // the settings now say what that model made of it.
                    let due = {
                        let mut after = proc.ultracode_check_after.lock().unwrap();
                        let mine = after.as_deref() == Some(rid);
                        if mine {
                            after.take();
                        }
                        mine
                    };
                    if due {
                        let _ = proc.write_line(&ultracode_check_line());
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
                } else if rid.starts_with(crate::cli_ask::REQUEST_PREFIX) {
                    if let Some(json) = crate::cli_ask::reply_json(inner) {
                        fire_string(&java_vm, &callbacks, "onCliReply", &json);
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
                // answer must not outlive the turn that raised it. Not a background
                // agent's: it outlives the turn, and its request is still waited on.
                cancel_open_cards(&state, &java_vm, &callbacks, CardsToCancel::TurnEnded);
                // Derive the session-specific status-bar data (model, context %,
                // cost) from this turn's usage and fire it to the GUI status bar.
                // Account-global rate limits come from the shared store, not here.
                if let Some(status) = build_status_json(&event, &current_model, &tokens) {
                    fire_string(&java_vm, &callbacks, "onStatus", &status);
                }
                fire_void(&java_vm, &callbacks, "onStreamEnd");
                cursors.clear();
                tokens = tokens.for_next_turn();
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
                            crate::elog!("[remote-control] bridge {}", state);
                        }
                        // Only "failed" is a bridge gone for good — the VS Code extension's
                        // rule; the CLI passes through other states on its way back up. A
                        // gone bridge is not one to carry to a replacement process.
                        if state == "failed" {
                            *proc.rc_session.lock().unwrap() = None;
                        }
                        let json = crate::bridge::rc_bridge_state_json(
                            state,
                            event["bridge_epoch"].as_i64(),
                            event["detail"].as_str(),
                        );
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
                    // What the CLI says to the user in its own voice (a hook that blocked a
                    // turn, a restricted model, the safeguards stopping a reply and the one
                    // retry that follows): a muted line, as in VS Code. Level "info" is the
                    // CLI's bookkeeping and is not shown there either.
                    "informational" => {
                        if let Some(payload) = notice_json(&event) {
                            fire_string(&java_vm, &callbacks, "onCliNotice", &payload);
                        }
                    }
                    // The same reply run again on another model after a refusal, with the
                    // CLI's own explanation of it.
                    "model_refusal_fallback" => {
                        if let Some(payload) = notice_json(&event) {
                            fire_string(&java_vm, &callbacks, "onCliNotice", &payload);
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
                        tokens.on_compacted();
                        awaiting_compact_summary = true;
                    }
                    _ => {}
                }
            }
            "user" => {
                // What the CLI says back of a message it was not typed (`--replay-user-messages`
                // echoes the ones typed here too, which the page drew as they were sent): a
                // background task's notification and a message from another session or a
                // subagent, as the lines VS Code leaves for them.
                if event["isReplay"].as_bool().unwrap_or(false) {
                    if let Some(payload) = replayed_notice(&event, &mut told_handbacks) {
                        fire_string(&java_vm, &callbacks, "onCliNotice", &payload);
                    }
                }
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
                    crate::elog!("[remote-control] inbound command {}", uuid);
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
                                crate::elog!("[remote-control] inbound message ({} chars)", text.len());
                            }
                            fire_string(&vm, &cb, "onRemoteMessage", &text);
                        } else if crate::is_debug() {
                            crate::elog!("[remote-control] no text found for {}", uuid);
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
                if !s.awaiting {
                    s.awaiting = true;
                    // A turn the user did not ask to stop: the Stop that ended the one before it
                    // (a message queued behind it still runs) does not apply to this one.
                    s.cancel.store(false, Ordering::Relaxed);
                    true
                } else {
                    false
                }
            };
            if reopened {
                fire_void(&java_vm, &callbacks, "onStreamStart");
            }
        }

        process_event_value(&event, &java_vm, &callbacks, &mut cursors, &mut tokens);
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
    cancel_open_cards(&state, &java_vm, &callbacks, CardsToCancel::All);
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
            #[cfg(target_os = "freebsd")]
            let stderr_text = if proc.frozen.load(Ordering::Relaxed) {
                crate::freebsd_guide::hang_gave_up().unwrap_or_default()
            } else {
                stderr_text
            };
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

/// What the status bar takes from a turn, kept for its length: the last API call's usage, and
/// where the prompt cache stands after it.
///
/// A turn is several API calls when Claude uses tools, and the `result` event's `usage` is the
/// SUM of them, each call's cached prompt counted again: a turn of three calls over a 36k prompt
/// reads 109k there. What fills the context window is the last call's own usage, which is what
/// the VS Code extension and the CLI's status line show, so that is kept as the calls stream by.
#[derive(Default)]
struct TurnUsage {
    /// The latest call of the main conversation that reported any usage.
    last_call: Option<CallUsage>,
    /// When the call under way was sent, taken as when its message started.
    call_started_ms: Option<i64>,
    /// When the last call that reported usage was sent: the cache's lifetime runs from there.
    cache_anchor_ms: Option<i64>,
    /// How long the cache keeps what the latest call that wrote to it wrote.
    cache_ttl_ms: Option<i64>,
    /// The conversation was compacted during the turn.
    compacted: bool,
    /// This CLI has sent its own running estimate of the thinking (`system/thinking_tokens`).
    /// Kept from turn to turn: from then on the estimates on the thinking deltas are not used.
    sends_thinking_estimates: bool,
    /// What the thinking deltas of the block under way say it has come to so far.
    delta_thinking_estimate: u64,
}

#[derive(Clone, Copy)]
struct CallUsage {
    input: u64,
    cache_read: u64,
    cache_create: u64,
    output: u64,
}

impl CallUsage {
    /// What one call's `usage` object says, or None when it says nothing (a synthetic message
    /// reports all zeros).
    fn of(usage: &serde_json::Value) -> Option<CallUsage> {
        let n = |k: &str| usage[k].as_u64().unwrap_or(0);
        let call = CallUsage {
            input: n("input_tokens"),
            cache_read: n("cache_read_input_tokens"),
            cache_create: n("cache_creation_input_tokens"),
            output: n("output_tokens"),
        };
        (call.context() > 0).then_some(call)
    }

    /// The tokens the call held in the context window: what it sent, cached or not.
    fn context(&self) -> u64 {
        self.input + self.cache_read + self.cache_create
    }
}

impl TurnUsage {
    /// Takes one `stream_event` of the top-level conversation: a message starting is a request
    /// sent, and its `message_delta` carries the finished call's usage. Returns the thinking
    /// count to show when a thinking delta carries an estimate and the CLI sends none of its
    /// own, as the VS Code extension counts it: summed over the block, from nothing at each
    /// message and each block.
    fn on_stream_event(&mut self, ev: &serde_json::Value) -> Option<u64> {
        match ev["type"].as_str() {
            Some("message_start") => {
                self.call_started_ms = Some(crate::promptcache::now_ms());
                self.delta_thinking_estimate = 0;
            }
            Some("content_block_start") => self.delta_thinking_estimate = 0,
            Some("content_block_delta") if ev["delta"]["type"] == "thinking_delta" => {
                let n = ev["delta"]["estimated_tokens"].as_u64()?;
                if self.sends_thinking_estimates {
                    return None;
                }
                self.delta_thinking_estimate += n;
                return Some(self.delta_thinking_estimate);
            }
            Some("message_delta") => {
                if let Some(call) = CallUsage::of(&ev["usage"]) {
                    self.last_call = Some(call);
                    self.cache_anchor_ms = self.call_started_ms.or(self.cache_anchor_ms);
                }
            }
            _ => {}
        }
        None
    }

    /// Takes a `system/thinking_tokens` event, the CLI's own running estimate of the thinking
    /// under way: the count to show.
    fn on_thinking_tokens(&mut self, event: &serde_json::Value) -> Option<u64> {
        let n = event["estimated_tokens"].as_u64()?;
        self.sends_thinking_estimates = true;
        Some(n)
    }

    /// The record for the next turn: all of it starts over but what the CLI is known to send.
    fn for_next_turn(&self) -> TurnUsage {
        TurnUsage { sends_thinking_estimates: self.sends_thinking_estimates, ..TurnUsage::default() }
    }

    /// Takes the `usage` of a main-conversation `assistant` event, the one that says which
    /// lifetime the call cached under.
    fn on_assistant_usage(&mut self, usage: &serde_json::Value) {
        if let Some(ms) = crate::promptcache::lifetime_ms_of_usage(usage) {
            self.cache_ttl_ms = Some(ms);
        }
    }

    /// Takes a `compact_boundary`: what the calls before it held is gone from the context, and
    /// the prompt cache holds none of what replaced it. A call after it reports anew.
    fn on_compacted(&mut self) {
        self.last_call = None;
        self.compacted = true;
    }
}

/// Builds the GUI status-bar JSON for a completed turn from its `result` event and the usage of
/// its last API call ([`TurnUsage`]). Context % = (input + cache_read + cache_creation) of that
/// call / contextWindow · 100; the window size and cost come straight from the result. Returns
/// None when the turn made no call that reported usage, unless it compacted the conversation:
/// no call follows a `/compact`, and the context it leaves is reported as empty, as the VS Code
/// extension does (its `totalTokens` goes to 0 on the boundary), with `compacted` set. Only
/// session-specific fields — rate limits are shared from the CLI statusLine via
/// ClaudeStatusStore, never derived here.
fn build_status_json(event: &serde_json::Value, model: &str, turn: &TurnUsage) -> Option<String> {
    let compacted = turn.compacted && turn.last_call.is_none();
    let call = match turn.last_call {
        Some(call) => call,
        None if compacted => CallUsage { input: 0, cache_read: 0, cache_create: 0, output: 0 },
        None => return None,
    };
    let (input, cache_read, cache_create, output) = (call.input, call.cache_read, call.cache_create, call.output);
    let context_tokens = call.context();

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
        // Where the prompt cache stands: it was last used at `cacheAnchorAt` and keeps what it
        // holds for `cacheTtlMs`; `recacheTokens` is what the next message writes again if it
        // has run out. The window of a turn that wrote nothing to the cache is not known
        // (null), and the page keeps the one it had.
        "cacheAnchorAt": turn.cache_anchor_ms,
        "cacheTtlMs": turn.cache_ttl_ms,
        "recacheTokens": context_tokens,
        // Compacted, and no call since: the cache holds none of the conversation now.
        "compacted": compacted,
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
                        crate::elog!("[remote-control] {} read from the transcript", uuid);
                    }
                    return Some(text);
                }
            } else if crate::is_debug() && attempt == 0 {
                crate::elog!(
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
        crate::elog!("[remote-control] {} not in the transcript, asking the API", uuid);
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
///
/// A card is only taken down here, never answered: the page is told it is gone, and the
/// Agent map is told its agent is no longer held up on it (`released`).
fn cancel_card(
    request_id: &str,
    state: &Arc<Mutex<ChatState>>,
    java_vm: &Arc<jni::JavaVM>,
    callbacks: &Arc<jni::objects::GlobalRef>,
) {
    let owner = {
        let mut s = state.lock().unwrap();
        let Some(owner) = s.open_cards.remove(request_id) else {
            return;
        };
        s.cancelled_cards.insert(request_id.to_string());
        owner
    };
    if crate::is_debug() {
        crate::elog!("[chat] card {} cancelled (answered elsewhere or turn ended)", request_id);
    }
    fire_string(java_vm, callbacks, "onCardCancel", request_id);
    // The waiting thread is only let go once Java has answered for it, and it is the one that
    // would say the agent is no longer held up: said here as well, so a withdrawn card never
    // leaves its agent waiting on it.
    if owner.task_id.is_some() {
        let released = serde_json::json!({ "kind": "agentPermission", "requestId": request_id, "waiting": false });
        fire_string(java_vm, callbacks, "onAgentActivity", &released.to_string());
    }
}

/// Whose request a card is up for: a subagent's, or the conversation's own.
#[derive(Clone, Debug, PartialEq, Eq)]
struct CardOwner {
    /// The subagent that made it: the request's `agent_id`, which is that agent's task id.
    /// None for the conversation's own.
    task_id: Option<String>,
}

/// Which of the open cards a cancel takes down.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
enum CardsToCancel {
    /// All of them: the process they were raised by is gone, so none can be answered.
    All,
    /// What the turn that just ended raised. A turn's end moots the conversation's own cards
    /// and those of the agents that end with it, but a background agent goes on after it: its
    /// request is still the CLI's to wait on, and answering it late works (checked against a
    /// live CLI, which ran the command when it was allowed seconds after the turn's result).
    TurnEnded,
}
impl CardsToCancel {
    fn takes(self, owner: &CardOwner, tasks: &AgentTasks) -> bool {
        match self {
            CardsToCancel::All => true,
            CardsToCancel::TurnEnded => owner.task_id.as_deref().map_or(true, |id| !may_outlive_turn(tasks, id)),
        }
    }
}

/// The agents the CLI has started in this conversation, by task id: what decides whether a
/// subagent's card outlives the turn (`CardsToCancel::TurnEnded`).
type AgentTasks = std::collections::HashMap<String, AgentTask>;

#[derive(Clone, Debug, Default, PartialEq, Eq)]
struct AgentTask {
    /// `is_backgrounded` from its `task_started`, changed by a later `task_updated`. None when
    /// the CLI did not say (an older CLI).
    backgrounded: Option<bool>,
    /// The agent that started it (`parent_task_id`); None for one the conversation started.
    parent: Option<String>,
    /// `spawn_depth`: 1 for one the conversation started, N+1 for one a depth-N agent started.
    depth: Option<u64>,
    /// Its `task_notification` has come: it is over.
    ended: bool,
}

/// The `onCliNotice` payload for a user message the CLI replays that was not typed here:
/// a `task-notification` (its summary, and which Agent call it is for so the page can put
/// the exact wording where the one it made from the event's status is) or a `peer` message.
/// None for anything else, and for a notification already sent on (`told`).
fn replayed_notice(event: &serde_json::Value, told: &mut std::collections::HashSet<String>) -> Option<String> {
    let text = event["message"]["content"].as_str()?;
    match event["origin"]["kind"].as_str() {
        Some("task-notification") => {
            let n = crate::session::notification_parts(text)?;
            if !told.insert(format!("{}|{}|{}", n.task_id, n.status, n.summary)) {
                return None;
            }
            Some(serde_json::json!({ "kind": "handback", "text": n.summary, "toolUseId": n.tool_use_id }).to_string())
        }
        Some("peer") | None => {
            let note = crate::session::peer_notice(event, text, &std::collections::HashMap::new())?;
            Some(serde_json::json!({ "kind": note["kind"], "text": note["text"], "body": note["body"] }).to_string())
        }
        _ => None,
    }
}

/// The line for a `system/informational` or `system/model_refusal_fallback` event, as the
/// page's `onCliNotice` takes it; None for one with nothing to say or at level "info".
fn notice_json(event: &serde_json::Value) -> Option<String> {
    let text = event["content"].as_str()?.trim();
    let refusal = event["subtype"].as_str() == Some("model_refusal_fallback");
    if text.is_empty() || (!refusal && matches!(event["level"].as_str(), None | Some("info"))) {
        return None;
    }
    let kind = if refusal { "refusal" } else { "informational" };
    Some(serde_json::json!({ "kind": kind, "text": text }).to_string())
}

/// Keeps `tasks` up to date from a `system/task_started` or `system/task_updated` event; any
/// other event leaves it alone.
fn note_agent_task(tasks: &mut AgentTasks, event: &serde_json::Value) {
    if event["type"].as_str() != Some("system") {
        return;
    }
    let Some(task_id) = event["task_id"].as_str().filter(|id| !id.is_empty()) else {
        return;
    };
    match event["subtype"].as_str() {
        Some("task_started") => {
            tasks.insert(task_id.to_string(), AgentTask {
                backgrounded: event["is_backgrounded"].as_bool(),
                parent: event["parent_task_id"].as_str().filter(|id| !id.is_empty()).map(str::to_string),
                depth: event["spawn_depth"].as_u64(),
                ended: false,
            });
        }
        // A foreground agent sent to the background later says so here, and only here.
        Some("task_updated") => {
            if let Some(backgrounded) = event["patch"]["is_backgrounded"].as_bool() {
                tasks.entry(task_id.to_string()).or_default().backgrounded = Some(backgrounded);
            }
        }
        // Only one already known: an end nothing was told of the start of changes nothing.
        Some("task_notification") => {
            if let Some(task) = tasks.get_mut(task_id) {
                task.ended = true;
            }
        }
        _ => {}
    }
}

/// Whether an agent that started others has any of them still going: what the CLI's own line for
/// its completion calls "waiting on its own background work".
fn waits_on_its_own(tasks: &AgentTasks, task_id: &str) -> bool {
    tasks.values().any(|t| t.parent.as_deref() == Some(task_id) && !t.ended)
}

/// Whether the agent `task_id` can still be going after the turn that started it has ended:
/// it, or an agent above it, runs in the background. One the core cannot place — never seen
/// started, an older CLI that does not say, a parent it does not know — counts as able to:
/// taking down a card the CLI still waits on would hang that agent, while a card left up
/// that nobody waits on costs a click.
fn may_outlive_turn(tasks: &AgentTasks, task_id: &str) -> bool {
    let mut id = task_id;
    // Bounded: parents form a chain, but a loop in what the CLI said must not hang the reader.
    for _ in 0..32 {
        let Some(task) = tasks.get(id) else { return true };
        match task.backgrounded {
            Some(false) => {}
            _ => return true,
        }
        match task.parent.as_deref() {
            Some(parent) => id = parent,
            // Started by an agent it does not name: that agent may be in the background.
            None if task.depth.is_some_and(|d| d > 1) => return true,
            None => return false,
        }
    }
    true
}

/// Cancels every card still up for this conversation. See the call sites for
/// when that is the right thing to do — both are moments after which no card
/// can be answered any more.
fn cancel_open_cards(
    state: &Arc<Mutex<ChatState>>,
    java_vm: &Arc<jni::JavaVM>,
    callbacks: &Arc<jni::objects::GlobalRef>,
    which: CardsToCancel,
) {
    let ids: Vec<String> = {
        let s = state.lock().unwrap();
        if s.open_cards.is_empty() {
            return; // the overwhelmingly common case — don't touch anything
        }
        s.open_cards.iter().filter(|(_, owner)| which.takes(owner, &s.agent_tasks)).map(|(id, _)| id.clone()).collect()
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
    // What the card needs besides the input: the CLI's suggested "remember this
    // decision" updates (all of them), why it asks, and how it wants it asked.
    let meta = permission_meta(request);

    // Registered BEFORE the card is raised: the withdrawal can arrive while the
    // card is still being drawn (the phone is quicker than a human), and a
    // cancel for an id not yet on the books would be dropped as unknown.
    let owner = CardOwner { task_id: request["agent_id"].as_str().filter(|id| !id.is_empty()).map(str::to_string) };
    state.lock().unwrap().open_cards.insert(request_id.clone(), owner);
    // A subagent held up on this: the Agent map says so while the card is up.
    if let Some(waiting) = agent_permission_payload(&request_id, request, true) {
        fire_string(java_vm, callbacks, "onAgentActivity", &waiting.to_string());
    }
    let released = agent_permission_payload(&request_id, request, false);

    let proc = Arc::clone(proc);
    let state = Arc::clone(state);
    let vm = Arc::clone(java_vm);
    let cb = Arc::clone(callbacks);
    std::thread::Builder::new()
        .name("claude-chat-perm".into())
        .spawn(move || {
            let response = decide_can_use_tool(&request_id, &tool_name, &input,
                                               &meta, &vm, &cb);
            // Answered here or withdrawn, the agent is no longer waiting on it.
            if let Some(released) = released {
                fire_string(&vm, &cb, "onAgentActivity", &released.to_string());
            }
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
///  - everything else → onPermissionRequest (the request's meta as JSON), a
///    decision JSON back, see [`permission_response`]
fn decide_can_use_tool(
    request_id: &str,
    tool_name: &str,
    input: &serde_json::Value,
    meta: &serde_json::Value,
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

    let decision = fire_four_string_ret(java_vm, callbacks, "onPermissionRequest",
                                       request_id, tool_name, &input.to_string(),
                                       &meta.to_string())
        .unwrap_or_default();
    permission_response(&decision, input, &meta["suggestions"])
}

/// What the permission card is told about a `can_use_tool` request besides its input.
fn permission_meta(request: &serde_json::Value) -> serde_json::Value {
    serde_json::json!({
        "suggestions": request.get("permission_suggestions").filter(|s| s.is_array())
            .cloned().unwrap_or_else(|| serde_json::json!([])),
        "reason": request["decision_reason"].as_str().unwrap_or(""),
        "description": request["description"].as_str().unwrap_or(""),
        "defaultToNo": request["default_to_no"].as_bool().unwrap_or(false),
        "suppressAlwaysAllow": request["suppress_always_allow_rule"].as_bool().unwrap_or(false),
    })
}

/// The places a remembered rule can be saved to, as the CLI names them.
const PERMISSION_DESTINATIONS: [&str; 4] = ["localSettings", "userSettings", "projectSettings", "session"];

/// The CLI's suggestions with the reader's chosen destination, which an edit-mode
/// suggestion keeps its own of (it is the mode for the session, not a rule to save).
fn remembered_permissions(suggestions: &serde_json::Value, dest: Option<&str>) -> Vec<serde_json::Value> {
    let dest = dest.filter(|d| PERMISSION_DESTINATIONS.contains(d));
    suggestions.as_array().into_iter().flatten().filter(|s| s.is_object()).map(|s| {
        let mut s = s.clone();
        if let (Some(d), true) = (dest, s["type"].as_str() != Some("setMode")) {
            s["destination"] = serde_json::json!(d);
        }
        s
    }).collect()
}

/// The control_response body for a decision the card made. The decision is JSON:
/// `{"d":"allow"|"allowRemember"|"allowAuto"|"allowManual"|"deny", "input":{…}?, "dest":"…"?,
/// "msg":"…"?, "interrupt":bool?}`. `input` is the tool's input as the reader edited it;
/// `dest` is where a remembered rule is saved. Anything that is not such a decision
/// (the card was torn down, Java failed) is a rejection that lets Claude carry on.
fn permission_response(decision: &str, input: &serde_json::Value, suggestions: &serde_json::Value) -> serde_json::Value {
    let d: serde_json::Value = serde_json::from_str(decision).unwrap_or(serde_json::Value::Null);
    match d["d"].as_str() {
        Some(kind) if kind.starts_with("allow") => {
            let updated = d.get("input").filter(|i| i.is_object()).cloned().unwrap_or_else(|| input.clone());
            let mut r = serde_json::json!({ "behavior": "allow", "updatedInput": updated });
            if kind == "allowRemember" {
                let perms = remembered_permissions(suggestions, d["dest"].as_str());
                if !perms.is_empty() {
                    r["updatedPermissions"] = serde_json::Value::Array(perms);
                }
            }
            r
        }
        Some("deny") => {
            let msg = d["msg"].as_str().filter(|m| !m.is_empty())
                .unwrap_or("The user declined this action in Eclipse.");
            serde_json::json!({ "behavior": "deny", "message": msg,
                                "interrupt": d["interrupt"].as_bool().unwrap_or(false) })
        }
        _ => serde_json::json!({ "behavior": "deny", "message": "The user declined this action in Eclipse.",
                                 "interrupt": false }),
    }
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
    tokens: &mut TurnUsage,
) {
    let event: serde_json::Value = match serde_json::from_str(line) {
        Ok(v) => v,
        Err(_) => return,
    };
    process_event_value(&event, java_vm, callbacks, cursors, tokens);
}

/// What the page is told when a subagent is held up on a permission request, and when it no
/// longer is: `{"kind":"agentPermission","requestId","waiting":true,"taskId","toolName","toolUseId"}`, then
/// `{"kind":"agentPermission","requestId","waiting":false}`. The CLI names the agent a request
/// comes from in `agent_id`, which is that agent's `task_id`; a request of the main
/// conversation has none and says nothing here. The Agent map shows the agent as waiting
/// for as long as a request of its own is open.
fn agent_permission_payload(request_id: &str, request: &serde_json::Value, waiting: bool) -> Option<serde_json::Value> {
    let task_id = request["agent_id"].as_str().filter(|id| !id.is_empty())?;
    Some(if waiting {
        serde_json::json!({
            "kind": "agentPermission", "requestId": request_id, "waiting": true,
            "taskId": task_id, "toolName": request["tool_name"].as_str().unwrap_or(""),
            // The call it is held up on, so the agent's list of tool calls can mark that one.
            "toolUseId": request["tool_use_id"].as_str().unwrap_or(""),
        })
    } else {
        serde_json::json!({ "kind": "agentPermission", "requestId": request_id, "waiting": false })
    })
}

/// What the page is told when the CLI's list of background tasks changes
/// (`system/background_tasks_changed`): `{"kind":"backgroundTasks","tasks":[…]}`,
/// each task `{taskId, taskType, description}`. The whole list every time, so an
/// empty one means nothing runs any more. Subagents are left out: the Agent map
/// lists those from their own tool lines. `None` when the event carries no list.
fn background_tasks_payload(event: &serde_json::Value) -> Option<serde_json::Value> {
    let tasks: Vec<serde_json::Value> = event["tasks"]
        .as_array()?
        .iter()
        .filter_map(|task| {
            let id = task["task_id"].as_str().filter(|id| !id.is_empty())?;
            let kind = task["task_type"].as_str().unwrap_or("");
            (kind != "local_agent").then(|| {
                serde_json::json!({
                    "taskId": id,
                    "taskType": kind,
                    "description": task["description"].as_str().unwrap_or(""),
                })
            })
        })
        .collect();
    Some(serde_json::json!({ "kind": "backgroundTasks", "tasks": tasks }))
}

/// Pre-parsed variant shared by the legacy per-turn reader and the persistent
/// reader (which needs the Value first to route control/result events).
fn process_event_value(
    event: &serde_json::Value,
    java_vm: &Arc<jni::JavaVM>,
    callbacks: &Arc<jni::objects::GlobalRef>,
    cursors: &mut std::collections::HashMap<String, (usize, usize, u64)>,
    tokens: &mut TurnUsage,
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
    // Whatever text is being held back goes out before anything else does, so nothing
    // can overtake it. Only another text/thinking delta may leave it waiting.
    let is_text_delta = event["type"].as_str() == Some("stream_event")
        && event["event"]["type"].as_str() == Some("content_block_delta")
        && matches!(event["event"]["delta"]["type"].as_str(), Some("text_delta") | Some("thinking_delta"));
    if !is_text_delta {
        flush_held_piece(java_vm, callbacks);
    }
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
            } else if event["subtype"].as_str() == Some("thinking_tokens") {
                // The CLI's own running estimate of the thinking under way, as the VS Code
                // extension shows it. CLI 2.1.301 sends it whether the thinking is shown or left
                // out; one that does not is counted from the thinking deltas ([`TurnUsage`]).
                if let Some(n) = tokens.on_thinking_tokens(&event) {
                    fire_string(java_vm, callbacks, "onTokens", &n.to_string());
                }
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
                        // Set only for a task found orphaned after a restart ("worker_restart"),
                        // which has no hand-back line of its own.
                        "reason": event["reason"].as_str(),
                        "waiting": event["waiting_on_background"].as_bool(),
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
                        // What the CLI says this agent is, which the Agent map shows; absent
                        // from an older CLI's event, and then the page goes without.
                        "subagentType": event["subagent_type"].as_str(),
                        "isBackgrounded": event["is_backgrounded"].as_bool(),
                    });
                    fire_string(java_vm, callbacks, "onAgentActivity", &payload.to_string());
                }
            } else if event["subtype"].as_str() == Some("background_tasks_changed") {
                // What runs in the background — shells above all — for the Agent map's
                // list of background tasks. Rides the agents' relay; the page tells it
                // apart by its kind, since it belongs to no one agent.
                if let Some(payload) = background_tasks_payload(event) {
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
                            crate::session::tool_error_text(&crate::session::flatten_result_content(b))
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
                    // On error, the whole text, for the "ERR" box under the tool line
                    // (chat.js's setToolError). On success, the GUI renders the result
                    // (chat.js's applyToolResult/renderToolOutput — an "OUT" box,
                    // a checklist, a clickable result list), so it needs the FULL flattened
                    // content, not the empty string this used to send when nothing on the
                    // GUI side read it yet. No truncation here: the page caps/links out to a
                    // full view for long content on its own (capIfOverflowing in chat.js).
                    let text = if is_error {
                        crate::session::tool_error_text(&crate::session::flatten_result_content(b))
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
            if !is_subagent {
                tokens.on_assistant_usage(&event["message"]["usage"]);
            }
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
                                // The block is whole, with its signature, which says whether it is the model's
                                // narration: its commentary between steps, which the view shows as a line of text
                                // (the extension does) rather than a collapsed "Thinking" row. Not a subagent's.
                                if !is_partial
                                    && !is_subagent
                                    && !t.trim().is_empty()
                                    && block["signature"].as_str().is_some_and(crate::session::is_narration_signature)
                                {
                                    fire_string(java_vm, callbacks, "onThinkingBlock", "narration");
                                }
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
                // A subagent's token count so far, as the CLI's own figure for it is made:
                // the usage of its LATEST message, which already counts everything it has
                // read (cache reads are the whole context again on every call), not a sum of
                // them — a sum counted that context once per call (30M for an agent the CLI
                // reports at 296k). Only a message that is final counts, never a partial
                // delta still in flight. The CLI's total replaces it when the agent ends.
                // Model rides along on the same message (also the detail popup's
                // "Sonnet 5"-style field) rather than its own separate event.
                if is_subagent && !is_partial {
                    let usage = &event["message"]["usage"];
                    let added = usage["input_tokens"].as_u64().unwrap_or(0)
                        + usage["output_tokens"].as_u64().unwrap_or(0)
                        + usage["cache_creation_input_tokens"].as_u64().unwrap_or(0)
                        + usage["cache_read_input_tokens"].as_u64().unwrap_or(0);
                    if added > 0 {
                        cursor.2 = added;
                    }
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
        // Fine-grained streaming events (only with --include-partial-messages): each call's
        // usage ([`TurnUsage`]) and the text/thinking as it is written. Tools still render
        // from the complete "assistant" events above. A concurrent background subagent's own
        // usage must not stand in for the top-level turn's: the status bar reports the
        // conversation the user is watching.
        "stream_event" => {
            if !is_subagent {
                if let Some(n) = tokens.on_stream_event(&event["event"]) {
                    fire_string(java_vm, callbacks, "onTokens", &n.to_string());
                }
                // A new thinking block begins, so the one before it (if any) is over: what it said
                // and what this one says are rows of their own, as in the extension. The text held
                // back from the block before has already gone out, ahead of this event.
                if event["event"]["type"].as_str() == Some("content_block_start")
                    && event["event"]["content_block"]["type"].as_str() == Some("thinking")
                {
                    fire_string(java_vm, callbacks, "onThinkingBlock", "start");
                }
            }
            // Text and thinking flow as they are written. The cursor records how much of
            // the block went out, so the finished "assistant" event above only sends the
            // rest (all of it, when no deltas came). A new message starts from zero: a
            // turn stopped halfway through a block would otherwise leave its count behind
            // and swallow the start of the next reply. A subagent's pieces go to its own
            // transcript, as its finished blocks do.
            let cursor = cursors.entry(parent_id.to_string()).or_insert((0, 0, 0));
            if let Some((callback, piece)) = stream_piece(&event["event"], cursor) {
                let ready = HELD_PIECE.with(|h| h.borrow_mut().push(parent_id, callback, &piece, std::time::Instant::now()));
                for (p, cb, text) in ready {
                    emit_piece(java_vm, callbacks, &p, cb, &text);
                }
            }
        }
        _ => {}
    }
}

/// Text and thinking pieces arrive a few characters at a time, many times a second.
/// Each one used to be a call into Java and a script run in the browser, which is
/// more than the UI thread keeps up with on a long reply. Pieces that follow one
/// another within [`PIECE_GAP`] are joined and sent together; the first one after a
/// pause goes out at once, and anything held goes out ahead of the next event of any
/// other kind (see `process_event_value`).
const PIECE_GAP: std::time::Duration = std::time::Duration::from_millis(50);

#[derive(Default)]
struct PieceBatch {
    held: Option<(String, &'static str, String)>,
    last_sent: Option<std::time::Instant>,
}

impl PieceBatch {
    /// Takes in a piece; returns what is to be sent now (parent id, callback, text).
    fn push(&mut self, parent: &str, callback: &'static str, text: &str, now: std::time::Instant)
        -> Vec<(String, &'static str, String)> {
        let mut out = Vec::new();
        match &mut self.held {
            Some((p, cb, t)) if p == parent && *cb == callback => t.push_str(text),
            _ => {
                if let Some(old) = self.held.take() {
                    out.push(old);
                }
                self.held = Some((parent.to_string(), callback, text.to_string()));
            }
        }
        let due = self.last_sent.map_or(true, |l| now.duration_since(l) >= PIECE_GAP);
        if due {
            if let Some(h) = self.held.take() {
                out.push(h);
            }
            self.last_sent = Some(now);
        }
        out
    }

    fn take(&mut self) -> Option<(String, &'static str, String)> {
        self.held.take()
    }
}

thread_local! {
    // One reader thread per chat process, so what is held belongs to that thread alone.
    static HELD_PIECE: std::cell::RefCell<PieceBatch> = std::cell::RefCell::new(PieceBatch::default());
}

fn flush_held_piece(java_vm: &Arc<jni::JavaVM>, callbacks: &Arc<jni::objects::GlobalRef>) {
    if let Some((p, cb, text)) = HELD_PIECE.with(|h| h.borrow_mut().take()) {
        emit_piece(java_vm, callbacks, &p, cb, &text);
    }
}

/// Sends a piece of text/thinking: to the main reply, or to a subagent's transcript.
fn emit_piece(java_vm: &Arc<jni::JavaVM>, callbacks: &Arc<jni::objects::GlobalRef>, parent_id: &str, callback: &str, text: &str) {
    if parent_id.is_empty() {
        fire_string(java_vm, callbacks, callback, text);
    } else {
        let kind = if callback == "onText" { "text" } else { "thinking" };
        let activity = serde_json::json!({ "parentId": parent_id, "kind": kind, "text": text });
        fire_string(java_vm, callbacks, "onAgentActivity", &activity.to_string());
    }
}

#[cfg(test)]
mod piece_batch_tests {
    use super::{PieceBatch, PIECE_GAP};
    use std::time::{Duration, Instant};

    #[test]
    fn the_first_piece_goes_at_once_and_quick_followers_are_joined() {
        let t0 = Instant::now();
        let mut b = PieceBatch::default();
        assert_eq!(b.push("", "onText", "a", t0), vec![("".to_string(), "onText", "a".to_string())]);
        assert!(b.push("", "onText", "b", t0 + Duration::from_millis(5)).is_empty());
        assert!(b.push("", "onText", "c", t0 + Duration::from_millis(10)).is_empty());
        let out = b.push("", "onText", "d", t0 + PIECE_GAP + Duration::from_millis(1));
        assert_eq!(out, vec![("".to_string(), "onText", "bcd".to_string())]);
        assert_eq!(b.take(), None);
    }

    #[test]
    fn a_change_of_kind_or_owner_sends_what_was_held_first() {
        let t0 = Instant::now();
        let mut b = PieceBatch::default();
        b.push("", "onText", "a", t0);
        assert!(b.push("", "onText", "b", t0 + Duration::from_millis(1)).is_empty());
        let out = b.push("", "onThinking", "t", t0 + Duration::from_millis(2));
        assert_eq!(out, vec![("".to_string(), "onText", "b".to_string())]);
        assert_eq!(b.take(), Some(("".to_string(), "onThinking", "t".to_string())));
        b.push("x", "onText", "q", t0 + Duration::from_millis(3));
        assert_eq!(b.take().map(|h| h.0), Some("x".to_string()));
    }
}

/// What a streaming event adds to the transcript: the callback to fire and the new
/// piece of text, with the block's byte cursor advanced past it. A `message_start`
/// zeroes the cursor (see the `stream_event` arm).
fn stream_piece(ev: &serde_json::Value, cursor: &mut (usize, usize, u64)) -> Option<(&'static str, String)> {
    match ev["type"].as_str()? {
        "message_start" => {
            cursor.0 = 0;
            cursor.1 = 0;
            None
        }
        "content_block_delta" => {
            let delta = &ev["delta"];
            let (callback, t) = match delta["type"].as_str()? {
                "text_delta" => ("onText", delta["text"].as_str()?),
                "thinking_delta" => ("onThinking", delta["thinking"].as_str()?),
                _ => return None,
            };
            if t.is_empty() {
                return None;
            }
            if callback == "onText" { cursor.0 += t.len() } else { cursor.1 += t.len() }
            Some((callback, t.to_string()))
        }
        _ => None,
    }
}

#[cfg(test)]
mod stream_piece_tests {
    use super::stream_piece;
    use serde_json::json;

    #[test]
    fn deltas_are_relayed_and_counted_and_message_start_resets() {
        let mut c = (0usize, 0usize, 0u64);
        let t = json!({"type":"content_block_delta","delta":{"type":"text_delta","text":"héllo"}});
        assert_eq!(stream_piece(&t, &mut c), Some(("onText", "héllo".to_string())));
        assert_eq!(c.0, "héllo".len());
        let k = json!({"type":"content_block_delta","delta":{"type":"thinking_delta","thinking":"hm"}});
        assert_eq!(stream_piece(&k, &mut c), Some(("onThinking", "hm".to_string())));
        assert_eq!(c.1, 2);
        assert_eq!(stream_piece(&json!({"type":"message_start"}), &mut c), None);
        assert_eq!((c.0, c.1), (0, 0));
    }

    #[test]
    fn other_deltas_and_empty_pieces_say_nothing() {
        let mut c = (0usize, 0usize, 0u64);
        for d in [json!({"type":"input_json_delta","partial_json":"{"}),
                  json!({"type":"signature_delta","signature":"x"}),
                  json!({"type":"text_delta","text":""})] {
            let e = json!({"type":"content_block_delta","delta":d});
            assert_eq!(stream_piece(&e, &mut c), None);
        }
        assert_eq!((c.0, c.1), (0, 0));
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
    let Some(config) = crate::session::config_dir() else {
        return;
    };
    let projects = config.join("projects");
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

// ---------------------------------------------------------------------------
// Tests
// ---------------------------------------------------------------------------

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
        let live = |effort: &str, model: &str| LiveSettings::new("default", effort, model, "2", false);
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

    #[test]
    fn an_open_file_goes_first_as_its_own_block() {
        let items = r#"[{"type":"ide_context","filePath":"/C:/ws/My Project/A.java"}]"#;
        assert_eq!(
            build_user_content("hi", items),
            json!([
                { "type": "text", "text": "<ide_opened_file>The user opened the file /C:/ws/My Project/A.java in the IDE. This may or may not be related to the current task.</ide_opened_file>" },
                { "type": "text", "text": "hi" }
            ])
        );
    }

    #[test]
    fn a_selection_goes_first_with_its_lines_wherever_it_was_listed() {
        let items = r#"[
            {"media_type":"image/png","data":"QUJD"},
            {"type":"ide_context","filePath":"/C:/ws/A.java","startLine":3,"endLine":5,"text":"int x;\nint y;"}
        ]"#;
        assert_eq!(
            build_user_content("fix it", items),
            json!([
                { "type": "text", "text": "<ide_selection>The user selected the lines 3 to 5 from /C:/ws/A.java:\nint x;\nint y;\n\nThis may or may not be related to the current task.</ide_selection>" },
                { "type": "text", "text": "fix it" },
                { "type": "image", "source": { "type": "base64", "media_type": "image/png", "data": "QUJD" } }
            ])
        );
    }

    #[test]
    fn editor_context_without_a_file_is_dropped() {
        assert_eq!(build_user_content("hi", r#"[{"type":"ide_context"}]"#), json!("hi"));
        assert_eq!(build_user_content("hi", r#"[{"type":"ide_context","filePath":""}]"#), json!("hi"));
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

#[cfg(test)]
mod turn_usage_tests {
    use super::{build_status_json, CallUsage, TurnUsage};
    use serde_json::json;

    fn call_end(input: u64, read: u64, create: u64, out: u64) -> serde_json::Value {
        json!({"type":"message_delta","usage":{"input_tokens":input,"cache_read_input_tokens":read,
            "cache_creation_input_tokens":create,"output_tokens":out}})
    }

    /// The real turn of a probe: three calls over a 36k prompt, the first writing it to the cache.
    fn three_calls() -> TurnUsage {
        let mut u = TurnUsage::default();
        u.on_stream_event(&call_end(10, 0, 36309, 176));
        u.on_stream_event(&call_end(5, 36309, 222, 78));
        u.on_stream_event(&call_end(5, 36531, 119, 78));
        u
    }

    #[test]
    fn the_context_is_the_last_calls_not_the_sum_of_them() {
        let last = three_calls().last_call.unwrap();
        assert_eq!(last.context(), 36655);
        assert_eq!(last.output, 78);
    }

    #[test]
    fn a_call_that_reports_nothing_does_not_replace_the_last_one() {
        let mut u = three_calls();
        u.on_stream_event(&call_end(0, 0, 0, 0));
        u.on_stream_event(&json!({"type":"content_block_delta","delta":{"type":"text_delta","text":"x"}}));
        assert_eq!(u.last_call.unwrap().context(), 36655);
        assert!(CallUsage::of(&json!({})).is_none());
    }

    #[test]
    fn the_status_is_built_from_that_call_and_the_results_cost_and_window() {
        let result = json!({
            "total_cost_usd": 0.0557,
            // The result's own usage is the sum of the turn's calls and is not used for the context.
            "usage": {"input_tokens":20,"cache_read_input_tokens":72840,"cache_creation_input_tokens":36650,"output_tokens":332},
            "modelUsage": {"claude-haiku-4-5": {"contextWindow": 200000}},
        });
        let status: serde_json::Value = serde_json::from_str(
            &build_status_json(&result, "claude-haiku-4-5", &three_calls()).unwrap()).unwrap();
        assert_eq!(status["contextPct"].as_f64().unwrap().floor(), 18.0);
        assert_eq!(status["contextWindow"], 200000);
        assert_eq!(status["cacheReadTokens"], 36531);
        assert_eq!(status["costUsd"], 0.0557);
    }

    #[test]
    fn the_cache_window_runs_from_the_last_call_and_names_the_lifetime_it_wrote_under() {
        let mut u = TurnUsage::default();
        u.on_stream_event(&json!({"type":"message_start"}));
        u.on_stream_event(&call_end(5, 36531, 119, 78));
        u.on_assistant_usage(&json!({"cache_creation":{"ephemeral_5m_input_tokens":119,"ephemeral_1h_input_tokens":0}}));
        let result = json!({"total_cost_usd": 0.1, "modelUsage": {"m": {"contextWindow": 200000}}});
        let status: serde_json::Value = serde_json::from_str(&build_status_json(&result, "m", &u).unwrap()).unwrap();
        assert_eq!(status["cacheTtlMs"], 300_000);
        assert_eq!(status["recacheTokens"], 36655);
        let anchor = status["cacheAnchorAt"].as_i64().unwrap();
        assert!((crate::promptcache::now_ms() - anchor).abs() < 60_000, "it is the time the call was sent");

        // A call that wrote nothing names no lifetime: null, and the page keeps the one it had.
        let mut read_only = TurnUsage::default();
        read_only.on_stream_event(&json!({"type":"message_start"}));
        read_only.on_stream_event(&call_end(5, 36650, 0, 78));
        read_only.on_assistant_usage(&json!({"cache_creation":{"ephemeral_5m_input_tokens":0,"ephemeral_1h_input_tokens":0}}));
        let status: serde_json::Value = serde_json::from_str(&build_status_json(&result, "m", &read_only).unwrap()).unwrap();
        assert!(status["cacheTtlMs"].is_null());
        read_only.on_assistant_usage(&json!({"cache_creation":{"ephemeral_1h_input_tokens":50,"ephemeral_5m_input_tokens":0}}));
        assert_eq!(read_only.cache_ttl_ms, Some(3_600_000));
    }

    #[test]
    fn a_turn_with_no_call_that_reported_usage_leaves_the_status_alone() {
        let result = json!({"total_cost_usd": 0.0, "usage": {"input_tokens":0}});
        assert!(build_status_json(&result, "m", &TurnUsage::default()).is_none());
    }

    /// The `result` of a `/compact` as CLI 2.1.301 sends it: no call of its own, a usage of
    /// zeros, and the session's cost and window.
    fn compact_result() -> serde_json::Value {
        json!({
            "total_cost_usd": 0.0059,
            "usage": {"input_tokens":0,"cache_read_input_tokens":0,"cache_creation_input_tokens":0,"output_tokens":0},
            "modelUsage": {"claude-haiku-5-5": {"contextWindow": 1000000}},
        })
    }

    #[test]
    fn a_compaction_with_no_call_after_it_empties_the_context_and_says_so() {
        // A /compact is a turn of its own (a fresh TurnUsage); an automatic one comes after
        // the calls of the turn it interrupts.
        let mut manual = TurnUsage::default();
        manual.on_compacted();
        let mut automatic = three_calls();
        automatic.on_compacted();
        for u in [manual, automatic] {
            let status: serde_json::Value = serde_json::from_str(
                &build_status_json(&compact_result(), "claude-haiku-5-5", &u).expect("a status")).unwrap();
            assert_eq!(status["contextPct"].as_f64(), Some(0.0));
            assert_eq!(status["inputTokens"], 0);
            assert_eq!(status["contextWindow"], 1000000);
            assert_eq!(status["costUsd"], 0.0059);
            assert_eq!(status["compacted"], true);
        }
    }

    #[test]
    fn a_call_after_a_compaction_reports_its_own_context() {
        let mut u = three_calls();
        u.on_compacted();
        u.on_stream_event(&call_end(2, 38644, 2983, 6));
        let status: serde_json::Value = serde_json::from_str(
            &build_status_json(&compact_result(), "claude-haiku-5-5", &u).unwrap()).unwrap();
        assert_eq!(status["cacheReadTokens"], 38644);
        assert_eq!(status["recacheTokens"], 41629);
        assert_eq!(status["compacted"], false);
    }

    fn block_start() -> serde_json::Value {
        json!({"type":"content_block_start","index":0,"content_block":{"type":"thinking","thinking":""}})
    }

    /// A thinking delta as CLI 2.1.301 relays it: with the thinking shown, its text and a null
    /// estimate; with the thinking left out, no text and an estimate of the tokens it stands for.
    fn thinking_delta(text: &str, estimate: Option<u64>) -> serde_json::Value {
        json!({"type":"content_block_delta","index":0,"delta":{"type":"thinking_delta","thinking":text,"estimated_tokens":estimate}})
    }

    fn typed_estimate(n: u64) -> serde_json::Value {
        json!({"type":"system","subtype":"thinking_tokens","estimated_tokens":n,"estimated_tokens_delta":n})
    }

    #[test]
    fn the_clis_own_estimate_is_the_count_shown() {
        let mut u = TurnUsage::default();
        assert_eq!(u.on_thinking_tokens(&typed_estimate(50)), Some(50));
        assert_eq!(u.on_thinking_tokens(&typed_estimate(250)), Some(250));
        assert_eq!(u.on_thinking_tokens(&json!({"type":"system","subtype":"thinking_tokens"})), None);
    }

    #[test]
    fn a_cli_that_sends_no_estimate_of_its_own_is_counted_from_the_deltas() {
        // The deltas of the Sonnet 5.5 probe with the thinking left out: 50, 200 and 150.
        let mut u = TurnUsage::default();
        assert_eq!(u.on_stream_event(&block_start()), None);
        assert_eq!(u.on_stream_event(&thinking_delta("", Some(50))), Some(50));
        assert_eq!(u.on_stream_event(&thinking_delta("", Some(200))), Some(250));
        assert_eq!(u.on_stream_event(&thinking_delta("", Some(150))), Some(400));
    }

    #[test]
    fn each_thinking_block_and_each_message_counts_from_nothing() {
        let mut u = TurnUsage::default();
        u.on_stream_event(&thinking_delta("", Some(300)));
        u.on_stream_event(&block_start());
        assert_eq!(u.on_stream_event(&thinking_delta("", Some(40))), Some(40));
        u.on_stream_event(&json!({"type":"message_start","message":{"usage":{}}}));
        assert_eq!(u.on_stream_event(&thinking_delta("", Some(7))), Some(7));
    }

    #[test]
    fn a_delta_with_its_text_and_no_estimate_says_nothing() {
        let mut u = TurnUsage::default();
        assert_eq!(u.on_stream_event(&thinking_delta("I", None)), None);
        assert_eq!(u.on_stream_event(&json!({"type":"content_block_delta","index":1,"delta":{"type":"text_delta","text":"No"}})), None);
    }

    #[test]
    fn once_the_cli_sends_its_own_the_deltas_are_not_counted_in_this_turn_or_the_next() {
        let mut u = TurnUsage::default();
        assert_eq!(u.on_stream_event(&thinking_delta("", Some(50))), Some(50));
        assert_eq!(u.on_thinking_tokens(&typed_estimate(50)), Some(50));
        assert_eq!(u.on_stream_event(&thinking_delta("", Some(200))), None);

        let mut next = u.for_next_turn();
        assert_eq!(next.on_stream_event(&thinking_delta("", Some(90))), None);
        assert!(next.last_call.is_none() && next.cache_ttl_ms.is_none(), "the rest starts over");
    }
}

#[cfg(test)]
mod ultracode_tests {
    use super::{flag_settings_arg, live_request, ultracode_check_line, ultracode_check_when, ultracode_confirmed, ultracode_in_effect, ultracode_settings, CheckWhen, LIVE_PREFIX, ULTRACODE_PREFIX};
    use serde_json::json;

    #[test]
    fn switching_ultracode_on_enables_dynamic_workflows_with_it() {
        // Ultracode is the Workflow tool on every task, and the CLI keeps it off while
        // dynamic workflows are off, which they are by default: on its own the switch did
        // nothing (verified against 2.1.292, `ultracodeAvailable: false` on every model).
        assert_eq!(ultracode_settings(true), json!({ "ultracode": true, "enableWorkflows": true }));
        let launch: serde_json::Value = serde_json::from_str(&flag_settings_arg(true)).unwrap();
        assert_eq!(launch, json!({ "ultracode": true, "enableWorkflows": true }));
    }

    #[test]
    fn switching_ultracode_off_hands_dynamic_workflows_back_to_the_users_own_setting() {
        // Live, the key set when it went on is cleared; at launch it is never sent.
        assert_eq!(ultracode_settings(false), json!({ "ultracode": false, "enableWorkflows": null }));
        let launch: serde_json::Value = serde_json::from_str(&flag_settings_arg(false)).unwrap();
        assert_eq!(launch, json!({ "ultracode": false }));
    }

    #[test]
    fn whether_ultracode_took_effect_is_read_off_the_settings_the_cli_applied() {
        let on = json!({ "applied": { "model": "claude-opus-5-5", "ultracode": true, "ultracodeRequested": true, "ultracodeAvailable": true } });
        assert_eq!(ultracode_in_effect(&on), Some(true));
        // Asked for and not given: the model cannot, or the user has workflows disabled.
        let refused = json!({ "applied": { "model": "claude-haiku-4-5", "ultracode": false, "ultracodeRequested": true, "ultracodeAvailable": false } });
        assert_eq!(ultracode_in_effect(&refused), Some(false));
        // A CLI from before Ultracode says nothing about it: nothing is concluded.
        assert_eq!(ultracode_in_effect(&json!({ "applied": { "model": "claude-opus-4-8" } })), None);
        assert_eq!(ultracode_in_effect(&json!({})), None);
    }

    #[test]
    fn a_cli_that_cannot_confirm_ultracode_is_taken_as_not_running_it() {
        // The check is only sent with the switch on. A CLI from before the setting answers
        // with no word of it, or refuses the request outright: either way nothing says
        // Ultracode is running, and the switch must not go on saying it is.
        assert!(ultracode_confirmed(&json!({ "response": { "applied": { "ultracode": true } } })));
        assert!(!ultracode_confirmed(&json!({ "response": { "applied": { "ultracode": false } } })));
        assert!(!ultracode_confirmed(&json!({ "response": { "applied": { "model": "claude-opus-4-8" } } })));
        assert!(!ultracode_confirmed(&json!({ "subtype": "error", "error": "Unsupported control request subtype: get_settings" })));
    }

    #[test]
    fn after_a_model_switch_the_check_waits_for_the_switch_to_be_answered() {
        // Verified against 2.1.295: a settings request written right behind set_model is
        // answered from the model the process was on, so Ultracode still reads as in effect
        // on a model that cannot run it; one written after the reply to set_model is right.
        // Right behind apply_flag_settings it is right as it stands.
        assert_eq!(ultracode_check_when(true, true), CheckWhen::AfterModelReply);
        assert_eq!(ultracode_check_when(false, true), CheckWhen::Now);
        // Off, there is nothing to confirm.
        assert_eq!(ultracode_check_when(true, false), CheckWhen::Never);
        assert_eq!(ultracode_check_when(false, false), CheckWhen::Never);
    }

    #[test]
    fn a_live_request_says_which_reply_is_its_own() {
        let (id, line) = live_request(json!({ "subtype": "set_model", "model": "haiku" }));
        let v: serde_json::Value = serde_json::from_str(&line).unwrap();
        assert_eq!(v["request_id"], id.as_str());
        assert!(id.starts_with(LIVE_PREFIX), "{id}");
        assert_eq!(v["request"]["subtype"], "set_model");
    }

    #[test]
    fn the_check_is_a_settings_request_under_its_own_id() {
        let line: serde_json::Value = serde_json::from_str(&ultracode_check_line()).unwrap();
        assert_eq!(line["request"]["subtype"], "get_settings");
        let id = line["request_id"].as_str().unwrap();
        assert!(id.starts_with(ULTRACODE_PREFIX), "{id}");
        assert!(!id.starts_with(super::SETTINGS_PREFIX), "not taken for the Remote Control poll's: {id}");
    }
}

#[cfg(test)]
mod background_tasks_tests {
    use super::background_tasks_payload;
    use serde_json::json;

    /// The event as CLI 2.1.295 sent it for `ping` run with run_in_background, under
    /// the chat view's own flags (probed 2026-10-15).
    fn shell_started() -> serde_json::Value {
        json!({"type":"system","subtype":"background_tasks_changed","tasks":[{"task_id":"bmy09tvmj","run_id":"0mv99ls0g-775f6db5","task_type":"local_bash","description":"Run 30-count ping to localhost in background"}],"uuid":"8e8aa1ae","session_id":"c1fbb6f5"})
    }

    #[test]
    fn a_background_shell_reaches_the_page_with_its_id_kind_and_description() {
        assert_eq!(
            background_tasks_payload(&shell_started()),
            Some(json!({"kind":"backgroundTasks","tasks":[
                {"taskId":"bmy09tvmj","taskType":"local_bash","description":"Run 30-count ping to localhost in background"}
            ]}))
        );
    }

    #[test]
    fn an_empty_list_is_passed_on_so_the_page_clears_its_own() {
        let ended = json!({"type":"system","subtype":"background_tasks_changed","tasks":[]});
        assert_eq!(background_tasks_payload(&ended), Some(json!({"kind":"backgroundTasks","tasks":[]})));
    }

    #[test]
    fn subagents_are_left_to_the_agent_lines_and_other_kinds_are_kept() {
        let mixed = json!({"subtype":"background_tasks_changed","tasks":[
            {"task_id":"a1","task_type":"local_agent","description":"Explore"},
            {"task_id":"w1","task_type":"local_workflow","description":"Review"},
            {"task_id":"m1","task_type":"monitor_mcp"}
        ]});
        assert_eq!(
            background_tasks_payload(&mixed),
            Some(json!({"kind":"backgroundTasks","tasks":[
                {"taskId":"w1","taskType":"local_workflow","description":"Review"},
                {"taskId":"m1","taskType":"monitor_mcp","description":""}
            ]}))
        );
    }

    #[test]
    fn a_task_without_an_id_is_skipped_and_an_event_without_a_list_says_nothing() {
        let odd = json!({"subtype":"background_tasks_changed","tasks":[{"task_type":"local_bash"},{"task_id":"","task_type":"local_bash"},7]});
        assert_eq!(background_tasks_payload(&odd), Some(json!({"kind":"backgroundTasks","tasks":[]})));
        assert_eq!(background_tasks_payload(&json!({"subtype":"background_tasks_changed"})), None);
        assert_eq!(background_tasks_payload(&json!({"subtype":"background_tasks_changed","tasks":{}})), None);
    }
}

#[cfg(test)]
mod agent_permission_tests {
    use super::agent_permission_payload;
    use serde_json::json;

    // The request the CLI really sent for a Bash call made inside a subagent.
    fn subagent_request() -> serde_json::Value {
        json!({"subtype":"can_use_tool","tool_name":"Bash","display_name":"Bash",
               "input":{"command":"python3 -c \"print(1)\""},"tool_use_id":"toolu_01","agent_id":"af06e86ed6d68a279"})
    }

    #[test]
    fn a_request_made_inside_a_subagent_names_that_agent() {
        let p = agent_permission_payload("req-1", &subagent_request(), true).unwrap();
        assert_eq!(p, json!({"kind":"agentPermission","requestId":"req-1","waiting":true,
                              "taskId":"af06e86ed6d68a279","toolName":"Bash","toolUseId":"toolu_01"}));
    }

    #[test]
    fn the_end_of_the_request_is_told_by_its_id_alone() {
        let p = agent_permission_payload("req-1", &subagent_request(), false).unwrap();
        assert_eq!(p, json!({"kind":"agentPermission","requestId":"req-1","waiting":false}));
    }

    #[test]
    fn a_request_of_the_main_conversation_says_nothing() {
        let main = json!({"subtype":"can_use_tool","tool_name":"Bash","input":{},"tool_use_id":"toolu_02"});
        assert_eq!(agent_permission_payload("req-2", &main, true), None);
        assert_eq!(agent_permission_payload("req-2", &main, false), None);
        let blank = json!({"subtype":"can_use_tool","tool_name":"Bash","agent_id":""});
        assert_eq!(agent_permission_payload("req-3", &blank, true), None, "an empty id names no agent");
    }
}

#[cfg(test)]
mod card_cancel_tests {
    use super::{note_agent_task, AgentTasks, CardOwner, CardsToCancel};
    use serde_json::json;

    fn main_card() -> CardOwner {
        CardOwner { task_id: None }
    }
    fn agent_card(id: &str) -> CardOwner {
        CardOwner { task_id: Some(id.to_string()) }
    }
    /// A `task_started` as CLI 2.1.301 describes it; `parent` makes it one an agent started.
    fn started(tasks: &mut AgentTasks, id: &str, backgrounded: bool, parent: Option<&str>) {
        let mut event = json!({"type":"system","subtype":"task_started","task_id":id,"tool_use_id":"toolu_x",
                               "description":"d","task_type":"local_agent","is_backgrounded":backgrounded,
                               "spawn_depth":1,"uuid":"u","session_id":"s"});
        if let Some(p) = parent {
            event["parent_task_id"] = json!(p);
            event["spawn_depth"] = json!(2);
        }
        note_agent_task(tasks, &event);
    }

    #[test]
    fn a_turns_end_takes_down_the_conversations_own_cards_and_leaves_a_background_agents() {
        // The CLI sends the result of the turn that launched a background agent in the same
        // instant as that agent's request, and the request is answered fine after it.
        let mut tasks = AgentTasks::new();
        started(&mut tasks, "bg1", true, None);
        assert!(CardsToCancel::TurnEnded.takes(&main_card(), &tasks));
        assert!(!CardsToCancel::TurnEnded.takes(&agent_card("bg1"), &tasks));
    }

    #[test]
    fn a_process_that_is_gone_takes_every_card_with_it() {
        let mut tasks = AgentTasks::new();
        started(&mut tasks, "bg1", true, None);
        assert!(CardsToCancel::All.takes(&main_card(), &tasks));
        assert!(CardsToCancel::All.takes(&agent_card("bg1"), &tasks));
    }

    #[test]
    fn a_turns_end_takes_down_a_foreground_agents_card() {
        // A foreground agent ends with the turn that started it: a card of its own still up then
        // is one nothing will answer, as the conversation's own are.
        let mut tasks = AgentTasks::new();
        started(&mut tasks, "fg1", false, None);
        assert!(CardsToCancel::TurnEnded.takes(&agent_card("fg1"), &tasks));
    }

    #[test]
    fn an_agent_started_inside_a_background_agent_keeps_its_card() {
        let mut tasks = AgentTasks::new();
        started(&mut tasks, "bg1", true, None);
        started(&mut tasks, "nested", false, Some("bg1"));
        assert!(!CardsToCancel::TurnEnded.takes(&agent_card("nested"), &tasks));
    }

    #[test]
    fn an_agent_started_inside_a_foreground_agent_loses_its_card() {
        let mut tasks = AgentTasks::new();
        started(&mut tasks, "fg1", false, None);
        started(&mut tasks, "nested", false, Some("fg1"));
        assert!(CardsToCancel::TurnEnded.takes(&agent_card("nested"), &tasks));
    }

    #[test]
    fn an_agent_moved_to_the_background_keeps_its_card() {
        let mut tasks = AgentTasks::new();
        started(&mut tasks, "fg1", false, None);
        note_agent_task(&mut tasks, &json!({"type":"system","subtype":"task_updated","task_id":"fg1",
                                            "patch":{"is_backgrounded":true},"uuid":"u","session_id":"s"}));
        assert!(!CardsToCancel::TurnEnded.takes(&agent_card("fg1"), &tasks));
    }

    #[test]
    fn a_replayed_notification_reaches_the_page_as_its_summary_once() {
        let note = "<task-notification>\n<task-id>a1</task-id>\n<tool-use-id>toolu_1</tool-use-id>\n<status>completed</status>\n<summary>Agent \"Say pong\" finished</summary>\n<result>pong</result>\n</task-notification>";
        let ev = json!({"type":"user","isReplay":true,"origin":{"kind":"task-notification","producer":"session-task"},"message":{"role":"user","content":note}});
        let mut told = std::collections::HashSet::new();
        let got: serde_json::Value = serde_json::from_str(&super::replayed_notice(&ev, &mut told).unwrap()).unwrap();
        assert_eq!(got, json!({"kind":"handback","text":"Agent \"Say pong\" finished","toolUseId":"toolu_1"}));
        assert!(super::replayed_notice(&ev, &mut told).is_none(), "said once");
        // What was typed here, replayed too, is the page's own.
        let typed = json!({"type":"user","isReplay":true,"message":{"role":"user","content":"hi"}});
        assert!(super::replayed_notice(&typed, &mut told).is_none());
        let peer = json!({"type":"user","isReplay":true,"origin":{"kind":"peer"},"message":{"role":"user","content":
            "Another Claude session sent a message:\n<agent-message from=\"a1\">\n[Subagent hand-back] The report follows:\n  Done\n</agent-message>"}});
        let got: serde_json::Value = serde_json::from_str(&super::replayed_notice(&peer, &mut told).unwrap()).unwrap();
        assert_eq!(got, json!({"kind":"message","text":"Message from a subagent: Done","body":"Done"}));
    }

    #[test]
    fn what_the_cli_says_in_its_own_voice_reaches_the_page_as_a_notice() {
        let said = |v: serde_json::Value| super::notice_json(&v).map(|s| serde_json::from_str::<serde_json::Value>(&s).unwrap());
        assert_eq!(
            said(json!({"type":"system","subtype":"informational","level":"notice","content":" Sonnet 5.5's safeguards stopped the response above · continuing once with that noted "})),
            Some(json!({"kind":"informational","text":"Sonnet 5.5's safeguards stopped the response above · continuing once with that noted"})));
        assert_eq!(said(json!({"type":"system","subtype":"informational","level":"info","content":"x"})), None);
        assert_eq!(said(json!({"type":"system","subtype":"informational","content":"x"})), None);
        assert_eq!(said(json!({"type":"system","subtype":"informational","level":"warning","content":""})), None);
        // A refusal's fallback is shown whatever level it carries, as the warning it is.
        assert_eq!(
            said(json!({"type":"system","subtype":"model_refusal_fallback","direction":"retry","content":"Switched."})),
            Some(json!({"kind":"refusal","text":"Switched."})));
    }

    #[test]
    fn a_task_update_that_does_not_name_the_flag_changes_nothing() {
        let mut tasks = AgentTasks::new();
        started(&mut tasks, "fg1", false, None);
        note_agent_task(&mut tasks, &json!({"type":"system","subtype":"task_updated","task_id":"fg1",
                                            "patch":{"status":"running"},"uuid":"u","session_id":"s"}));
        assert!(CardsToCancel::TurnEnded.takes(&agent_card("fg1"), &tasks));
    }

    #[test]
    fn a_card_whose_agent_cannot_be_told_apart_is_kept() {
        // Taking down a card the CLI still waits on would hang that agent; leaving one up that
        // nobody waits on costs a click. So an agent the core cannot place keeps its card.
        let mut tasks = AgentTasks::new();
        assert!(!CardsToCancel::TurnEnded.takes(&agent_card("never-started"), &tasks), "no task_started seen");
        note_agent_task(&mut tasks, &json!({"type":"system","subtype":"task_started","task_id":"old","description":"d"}));
        assert!(!CardsToCancel::TurnEnded.takes(&agent_card("old"), &tasks), "an older CLI, no is_backgrounded");
        note_agent_task(&mut tasks, &json!({"type":"system","subtype":"task_started","task_id":"orphan","description":"d",
                                            "is_backgrounded":false,"spawn_depth":2}));
        assert!(!CardsToCancel::TurnEnded.takes(&agent_card("orphan"), &tasks), "started by an agent it does not name");
        started(&mut tasks, "child", false, Some("unknown-parent"));
        assert!(!CardsToCancel::TurnEnded.takes(&agent_card("child"), &tasks), "started by an agent never seen");
    }

    #[test]
    fn a_loop_of_parents_does_not_hang_and_keeps_the_card() {
        let mut tasks = AgentTasks::new();
        started(&mut tasks, "a", false, Some("b"));
        started(&mut tasks, "b", false, Some("a"));
        assert!(!CardsToCancel::TurnEnded.takes(&agent_card("a"), &tasks));
    }

    #[test]
    fn an_agent_waits_on_its_own_until_the_agents_it_started_are_over() {
        let mut tasks = AgentTasks::new();
        started(&mut tasks, "outer", true, None);
        started(&mut tasks, "inner", true, Some("outer"));
        assert!(super::waits_on_its_own(&tasks, "outer"));
        assert!(!super::waits_on_its_own(&tasks, "inner"));
        note_agent_task(&mut tasks, &json!({"type":"system","subtype":"task_notification","task_id":"inner","status":"completed"}));
        assert!(!super::waits_on_its_own(&tasks, "outer"));
    }

    #[test]
    fn other_events_are_left_alone() {
        let mut tasks = AgentTasks::new();
        note_agent_task(&mut tasks, &json!({"type":"system","subtype":"task_notification","task_id":"t","status":"completed"}));
        note_agent_task(&mut tasks, &json!({"type":"system","subtype":"task_started","description":"no id"}));
        note_agent_task(&mut tasks, &json!({"type":"assistant","task_id":"t"}));
        assert!(tasks.is_empty());
    }
}

#[cfg(test)]
mod permission_response_tests {
    use super::{permission_meta, permission_response};
    use serde_json::json;

    fn rule(dest: &str) -> serde_json::Value {
        json!({"type":"addRules","rules":[{"toolName":"Bash","ruleContent":"npm test *"}],"behavior":"allow","destination":dest})
    }

    #[test]
    fn a_request_gives_the_card_everything_it_asks_about() {
        let req = json!({"subtype":"can_use_tool","tool_name":"Bash","description":"Run it",
            "permission_suggestions":[rule("localSettings")],"decision_reason":"needs approval",
            "default_to_no":true,"suppress_always_allow_rule":true});
        assert_eq!(permission_meta(&req), json!({"suggestions":[rule("localSettings")],"reason":"needs approval",
            "description":"Run it","defaultToNo":true,"suppressAlwaysAllow":true}));
        let bare = permission_meta(&json!({"subtype":"can_use_tool"}));
        assert_eq!(bare, json!({"suggestions":[],"reason":"","description":"","defaultToNo":false,"suppressAlwaysAllow":false}));
    }

    #[test]
    fn allow_sends_the_input_as_edited_or_as_it_was() {
        let input = json!({"command":"npm test"});
        let plain = permission_response(r#"{"d":"allow"}"#, &input, &json!([]));
        assert_eq!(plain, json!({"behavior":"allow","updatedInput":{"command":"npm test"}}));
        let edited = permission_response(r#"{"d":"allow","input":{"command":"npm run lint"}}"#, &input, &json!([]));
        assert_eq!(edited["updatedInput"], json!({"command":"npm run lint"}));
        assert!(edited.get("updatedPermissions").is_none(), "no rule unless asked for");
    }

    #[test]
    fn remembering_sends_every_suggestion_to_the_chosen_destination() {
        let sugg = json!([rule("localSettings"), {"type":"setMode","mode":"acceptEdits","destination":"session"}]);
        let r = permission_response(r#"{"d":"allowRemember","dest":"projectSettings"}"#, &json!({}), &sugg);
        assert_eq!(r["updatedPermissions"][0]["destination"], "projectSettings");
        assert_eq!(r["updatedPermissions"][1]["destination"], "session", "a mode change keeps its own");
        assert_eq!(r["updatedPermissions"].as_array().unwrap().len(), 2);
        // No destination, or one the CLI does not know: the suggestion's own stands.
        let own = permission_response(r#"{"d":"allowRemember"}"#, &json!({}), &json!([rule("localSettings")]));
        assert_eq!(own["updatedPermissions"][0]["destination"], "localSettings");
        let odd = permission_response(r#"{"d":"allowRemember","dest":"policySettings"}"#, &json!({}), &json!([rule("localSettings")]));
        assert_eq!(odd["updatedPermissions"][0]["destination"], "localSettings");
        // Nothing suggested: allowed, nothing remembered.
        let none = permission_response(r#"{"d":"allowRemember","dest":"session"}"#, &json!({}), &json!([]));
        assert!(none.get("updatedPermissions").is_none());
    }

    #[test]
    fn a_rejection_carries_its_message_and_whether_it_stops_the_run() {
        let stop = permission_response(r#"{"d":"deny","msg":"STOP","interrupt":true}"#, &json!({}), &json!([]));
        assert_eq!(stop, json!({"behavior":"deny","message":"STOP","interrupt":true}));
        let say = permission_response(r#"{"d":"deny","msg":"use yarn"}"#, &json!({}), &json!([]));
        assert_eq!(say, json!({"behavior":"deny","message":"use yarn","interrupt":false}));
        // The CLI refuses a rejection with no message, so there is always one.
        let blank = permission_response(r#"{"d":"deny"}"#, &json!({}), &json!([]));
        assert!(!blank["message"].as_str().unwrap().is_empty());
    }

    #[test]
    fn what_is_not_a_decision_is_a_rejection_that_does_not_stop_the_run() {
        for bad in ["", "deny", "allow", "{}", "null", r#"{"d":7}"#] {
            let r = permission_response(bad, &json!({"command":"x"}), &json!([]));
            assert_eq!(r["behavior"], "deny", "{bad:?}");
            assert_eq!(r["interrupt"], false, "{bad:?}");
        }
    }
}
