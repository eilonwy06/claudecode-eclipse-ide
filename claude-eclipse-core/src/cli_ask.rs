//! Asking the CLI for what its own dialogs show.
//!
//! The VS Code panel draws its Memory, Instructions, Status and Slash commands
//! dialogs from data the CLI hands it over the control channel; the words in them
//! are the CLI's, not the panel's. This is that channel for the Claude GUI, used
//! the two ways the extension uses it:
//!
//! * **What a conversation can show** is asked of the tab's live process
//!   (`get_memory_dialog`, `get_status`, …). Replies come back on the reader
//!   thread and reach the page as `onCliReply`, matched by a token the page chose,
//!   as the MCP servers window's do ([`crate::mcp_servers`]). The few requests that
//!   save or send something go the same way, each in one fixed shape ([`allowed`]).
//! * **What a folder offers before any conversation has a process**, the command
//!   list above all, is asked of a short-lived process that is sent `initialize`
//!   and nothing else: no message, so no turn ([`fetch_commands`]).

use std::io::{BufRead, BufReader, Read, Write};
use std::process::{Child, Stdio};
use std::sync::mpsc;
use std::time::{Duration, Instant};

#[cfg(windows)]
use std::os::windows::process::CommandExt;

/// Request ids of the page's requests to a live process: this prefix, then its token.
pub(crate) const REQUEST_PREFIX: &str = "eclipse-ask-";

/// The control requests the page may send to a live process that only read: what the
/// dialogs show. Not `get_settings`: the settings in effect can hold what `env` holds,
/// and the one setting the page shows is picked out of them here ([`flagged_message_switch`]).
const READING_SUBTYPES: [&str; 12] = [
    "get_memory_dialog",
    "get_skills_dialog",
    "get_status",
    "get_chrome_dialog",
    "get_chrome_browsers",
    "get_hooks_listing",
    "list_permission_rules",
    "get_sandbox_dialog",
    "get_plan",
    "export_conversation",
    "get_context_usage",
    "get_usage",
];

/// Whether `request` is its subtype and exactly the fields named, each as its check
/// wants it.
fn shaped(request: &serde_json::Value, fields: &[(&str, fn(&serde_json::Value) -> bool)]) -> bool {
    request.as_object().is_some_and(|said| {
        said.len() == fields.len() + 1 && fields.iter().all(|(name, fits)| said.get(*name).is_some_and(fits))
    })
}

/// Whether `settings` holds the one setting `key`, as `fits` wants it, and nothing else.
fn one_setting(settings: &serde_json::Value, key: &str, fits: fn(&serde_json::Value) -> bool) -> bool {
    settings.as_object().is_some_and(|set| set.len() == 1 && set.get(key).is_some_and(fits))
}

fn only_the_output_style(settings: &serde_json::Value) -> bool {
    one_setting(settings, "outputStyle", serde_json::Value::is_string)
}

fn only_the_flagged_message_switch(settings: &serde_json::Value) -> bool {
    one_setting(settings, "switchModelsOnFlag", serde_json::Value::is_boolean)
}

fn is_the_local_layer(source: &serde_json::Value) -> bool {
    source.as_str() == Some("localSettings")
}

fn is_this_surface(surface: &serde_json::Value) -> bool {
    surface.as_str() == Some("ide")
}

/// Whether the page may send `request` to a live process. One that only reads is let
/// through by name. One that saves or sends something is let through only in the one
/// shape its dialog sends it in, so nothing else can ride on it: the two settings
/// requests can change any setting there is, and from here each changes one.
fn allowed(request: &serde_json::Value) -> bool {
    use serde_json::Value;
    let Some(subtype) = request["subtype"].as_str() else { return false };
    if READING_SUBTYPES.contains(&subtype) {
        return true;
    }
    match subtype {
        // Output styles: the style in use, saved for this folder and this user, where
        // the extension saves it.
        "update_settings" => shaped(request, &[("source", is_the_local_layer), ("settings", only_the_output_style)]),
        // "Switch models when a message is flagged": the running session is told once
        // the setting is in the user's settings file ([`crate::user_settings`]).
        "apply_flag_settings" => shaped(request, &[("settings", only_the_flagged_message_switch)]),
        // Report a problem.
        "submit_feedback" => shaped(
            request,
            &[("description", Value::is_string), ("surface", is_this_surface), ("save_locally", Value::is_boolean)],
        ),
        // Switch account: the address to sign in at, the wait for the browser, and a
        // code pasted back from the page that shows one.
        "claude_authenticate" => shaped(request, &[("loginWithClaudeAi", Value::is_boolean)]),
        "claude_oauth_wait_for_completion" => shaped(request, &[]),
        "claude_oauth_callback" => shaped(request, &[("authorizationCode", Value::is_string), ("state", Value::is_string)]),
        _ => false,
    }
}

/// The request id of the first request a short-lived process is sent.
const INIT_REQUEST_ID: &str = "eclipse-once-init";

/// The request id of its second: the settings in effect in the folder.
const SETTINGS_REQUEST_ID: &str = "eclipse-once-settings";

/// How long a short-lived process may take to answer. It answered in 2 to 4s when
/// measured (CLI 2.1.291); the rest is room for a cold disk.
const FETCH_TIMEOUT: Duration = Duration::from_secs(20);

/// How long a short-lived process may take over its settings once it has answered
/// `initialize`. It answered in under a tenth of a second when measured; a CLI that
/// does not know the request says so at once.
const SETTINGS_TIMEOUT: Duration = Duration::from_secs(5);

/// How long a short-lived process is given to leave by itself once it has answered
/// and its stdin is closed. It took about a second when measured.
const EXIT_GRACE: Duration = Duration::from_secs(5);

/// What of the CLI's `initialize` answer is handed on: what the menus are drawn
/// from. Not `account` (who is signed in) and not `pid`. The last is not the CLI's
/// own key: it is the one setting kept of its settings answer ([`flagged_message_switch`]).
const KEPT_KEYS: [&str; 8] = [
    "commands",
    "models",
    "output_style",
    "available_output_styles",
    "fast_mode_state",
    "fast_mode_disabled_reason",
    "feedback_mode",
    "switch_models_on_flag",
];

/// The control-request line for one of the page's requests to a live process, or
/// `None` when the token or the request is not one it may send ([`allowed`]).
pub(crate) fn request_line(token: &str, request: &serde_json::Value) -> Option<String> {
    if !crate::mcp_servers::valid_token(token) {
        return None;
    }
    if !allowed(request) {
        return None;
    }
    Some(
        serde_json::json!({
            "type": "control_request",
            "request_id": format!("{REQUEST_PREFIX}{token}"),
            "request": request
        })
        .to_string(),
    )
}

/// What the page is told about a reply to one of its requests:
/// `{"token","ok":true,"response"}` or `{"token","ok":false,"error"}`.
/// `None` when the reply is to someone else's request.
pub(crate) fn reply_json(inner: &serde_json::Value) -> Option<String> {
    let token = inner["request_id"].as_str()?.strip_prefix(REQUEST_PREFIX)?;
    let json = if inner["subtype"].as_str() == Some("error") {
        serde_json::json!({ "token": token, "ok": false, "error": said(inner) })
    } else {
        serde_json::json!({ "token": token, "ok": true, "response": inner["response"] })
    };
    Some(json.to_string())
}

/// What the CLI said went wrong in an error reply.
fn said(inner: &serde_json::Value) -> String {
    let e = inner["error"].as_str().unwrap_or("").trim();
    if e.is_empty() { "Unknown error".to_string() } else { e.to_string() }
}

/// Whether fast mode is on, as a live process reports it: `{"state","disabledReason"}`.
/// The CLI says so on its `system`/`init` event and on a turn's `result` (both seen
/// on 2.1.291, the result being that of a `/cost`), which is how a `/fast` typed
/// here, or on another device, would be seen to have taken effect. `None` for every
/// other event, and for a CLI that does not say.
pub(crate) fn fast_mode_json(event: &serde_json::Value) -> Option<String> {
    let says = match event["type"].as_str()? {
        "result" => true,
        "system" => event["subtype"].as_str() == Some("init"),
        _ => false,
    };
    if !says {
        return None;
    }
    let state = event["fast_mode_state"].as_str()?;
    Some(serde_json::json!({ "state": state, "disabledReason": event["fast_mode_disabled_reason"] }).to_string())
}

/// The arguments of a short-lived process: the stream-json pair a tab's process
/// uses, and a settings file ([`no_hooks_settings`]).
fn once_args(settings_file: &str) -> Vec<String> {
    ["-p", "--input-format", "stream-json", "--output-format", "stream-json", "--verbose", "--settings", settings_file]
        .map(String::from)
        .to_vec()
}

/// Writes the settings file a short-lived process is started with and returns its
/// path. It switches hooks off for that process: measured on CLI 2.1.291, a process
/// that is only asked to `initialize` still runs the folder's SessionStart hook, and
/// SessionEnd when it closes, and a session-setup hook has no business running for a
/// process that never becomes a session. A file, as `--mcp-config` is on Windows, so
/// the JSON never crosses a `.cmd` shim's command line.
fn no_hooks_settings() -> std::io::Result<String> {
    const NO_HOOKS: &str = r#"{"disableAllHooks":true}"#;
    let path = std::env::temp_dir().join("claude-eclipse-ask-settings.json");
    // Left alone when it already says so: rewriting empties it for a moment, and a
    // process started by another ask could be reading it just then.
    if !std::fs::read_to_string(&path).is_ok_and(|text| text == NO_HOOKS) {
        std::fs::write(&path, NO_HOOKS)?;
    }
    Ok(path.to_string_lossy().into_owned())
}

/// The `initialize` request line.
fn init_line() -> String {
    serde_json::json!({
        "type": "control_request",
        "request_id": INIT_REQUEST_ID,
        "request": { "subtype": "initialize" }
    })
    .to_string()
}

/// The request line for the settings in effect.
fn settings_line() -> String {
    serde_json::json!({
        "type": "control_request",
        "request_id": SETTINGS_REQUEST_ID,
        "request": { "subtype": "get_settings" }
    })
    .to_string()
}

/// `Some` once `line` is the CLI's answer to the request `request_id`: the answer
/// itself, or what the CLI said went wrong. `None` for every other line of its output.
fn reply_to(line: &str, request_id: &str) -> Option<Result<serde_json::Value, String>> {
    let event: serde_json::Value = serde_json::from_str(line.trim()).ok()?;
    if event["type"].as_str() != Some("control_response") {
        return None;
    }
    let inner = &event["response"];
    if inner["request_id"].as_str() != Some(request_id) {
        return None;
    }
    Some(if inner["subtype"].as_str() == Some("error") { Err(said(inner)) } else { Ok(inner["response"].clone()) })
}

/// What the settings in effect say of "Switch models when a message is flagged":
/// on, off, or nothing (`null`), which the CLI takes as on.
fn flagged_message_switch(settings: &serde_json::Value) -> serde_json::Value {
    match settings["effective"]["switchModelsOnFlag"].as_bool() {
        Some(on) => serde_json::Value::Bool(on),
        None => serde_json::Value::Null,
    }
}

/// What [`fetch_commands`] returns: `{"ok":true}` with the [`KEPT_KEYS`] of the
/// answer, or `{"ok":false,"error"}`.
fn fetch_json(result: Result<serde_json::Value, String>) -> String {
    let json = match result {
        Ok(answer) => {
            let mut kept = serde_json::Map::new();
            kept.insert("ok".into(), serde_json::json!(true));
            for key in KEPT_KEYS {
                if let Some(value) = answer.get(key) {
                    kept.insert(key.into(), value.clone());
                }
            }
            serde_json::Value::Object(kept)
        }
        Err(error) => serde_json::json!({ "ok": false, "error": error }),
    };
    json.to_string()
}

/// Asks a short-lived `claude`, started in `cwd`, what that folder offers: its slash
/// commands (built-in, skills, plug-in and the user's own, each with its
/// description, argument hint and aliases), its models and its output styles, and
/// the one setting a menu row shows. Sends `initialize` and `get_settings` and
/// nothing else, so no turn runs and nothing is spent, and no transcript is written.
///
/// **Blocking** — starts the CLI and waits for its answer, up to 20s. Off the UI thread.
pub fn fetch_commands(claude_cmd: &str, cwd: &str) -> String {
    let started = Instant::now();
    let result = run_once(claude_cmd, cwd);
    if crate::is_debug() {
        match &result {
            Ok(answer) => eprintln!(
                "[cli-ask] initialize answered in {} ms: {} commands (in {cwd})",
                started.elapsed().as_millis(),
                answer["commands"].as_array().map_or(0, Vec::len)
            ),
            Err(error) => eprintln!("[cli-ask] initialize failed after {} ms: {error} (in {cwd})", started.elapsed().as_millis()),
        }
    }
    fetch_json(result)
}

/// Starts the short-lived process, asks, and sees it out whatever the answer was.
fn run_once(claude_cmd: &str, cwd: &str) -> Result<serde_json::Value, String> {
    let (child, result) = asked(claude_cmd, cwd)?;
    if result.is_ok() {
        // The answer is in hand, so nobody waits for the rest: the CLI is left to
        // close what it has open and go, which took about a second when measured.
        let _ = std::thread::Builder::new()
            .name("claude-cli-ask-reap".into())
            .spawn(move || reap(child, EXIT_GRACE));
    } else {
        reap(child, Duration::ZERO);
    }
    result
}

/// Starts the short-lived process and asks. The process comes back with the answer,
/// still running and with its stdin closed, for [`reap`].
fn asked(claude_cmd: &str, cwd: &str) -> Result<(Child, Result<serde_json::Value, String>), String> {
    if cwd.is_empty() || !std::path::Path::new(cwd).is_dir() {
        return Err(format!("Working directory not found: {cwd}"));
    }
    let settings = no_hooks_settings().map_err(|e| format!("Could not write the settings file: {e}"))?;
    let mut cmd = crate::launch::claude_command(claude_cmd, &once_args(&settings));
    cmd.current_dir(cwd)
        .stdin(Stdio::piped())
        .stdout(Stdio::piped())
        .stderr(Stdio::piped());

    #[cfg(windows)]
    cmd.creation_flags(0x08000000); // CREATE_NO_WINDOW

    for (k, v) in crate::shell_env::captured_env().to_inject() {
        cmd.env(k, v);
    }
    // Named as a tab's process is (see chat.rs), but given none of the IDE variables
    // and no `--mcp-config`: it must not connect to this plugin's own MCP server,
    // which would see a client arrive and leave on every ask.
    cmd.env("CLAUDE_CODE_ENTRYPOINT", "claude-eclipse-ide")
        .env_remove("CLAUDE_CODE_SSE_PORT")
        .env_remove("CLAUDE_IDE_PORT")
        .env_remove("CLAUDE_IDE_AUTH_TOKEN")
        .env_remove("CLAUDE_IDE_NAME");

    let mut child = cmd.spawn().map_err(|e| format!("Could not run Claude Code: {e}"))?;
    let result = ask(&mut child);
    Ok((child, result))
}

/// Sees a short-lived process out, its stdin already closed: waits `grace` for it to
/// leave by itself, and ends it if it has not. True when it left by itself.
fn reap(mut child: Child, grace: Duration) -> bool {
    let started = Instant::now();
    let left = loop {
        match child.try_wait() {
            Ok(Some(_)) => break true,
            Ok(None) if started.elapsed() < grace => std::thread::sleep(Duration::from_millis(50)),
            _ => break false,
        }
    };
    if !left {
        crate::launch::kill_process_tree(&mut child);
        let _ = child.wait();
    }
    if crate::is_debug() {
        let how = if left { "left by itself" } else { "was ended" };
        eprintln!("[cli-ask] the short-lived process {how} after {} ms", started.elapsed().as_millis());
    }
    left
}

/// Why a wait for an answer ended without one.
enum NoAnswer {
    TimedOut,
    /// The CLI's stdout closed: it has exited.
    Gone,
}

/// Reads the CLI's lines until the request `request_id` is answered or `deadline` passes.
fn answer_to(
    lines: &mpsc::Receiver<String>,
    deadline: Instant,
    request_id: &str,
) -> Result<Result<serde_json::Value, String>, NoAnswer> {
    loop {
        match lines.recv_timeout(deadline.saturating_duration_since(Instant::now())) {
            Ok(line) => {
                if let Some(reply) = reply_to(&line, request_id) {
                    return Ok(reply);
                }
            }
            Err(mpsc::RecvTimeoutError::Timeout) => return Err(NoAnswer::TimedOut),
            Err(mpsc::RecvTimeoutError::Disconnected) => return Err(NoAnswer::Gone),
        }
    }
}

/// Sends `initialize` and reads until it is answered, the CLI has gone, or
/// [`FETCH_TIMEOUT`] has passed; then asks for the settings in effect, which a CLI
/// that will not say is not held to.
fn ask(child: &mut Child) -> Result<serde_json::Value, String> {
    let gone = || "Claude Code exited before answering.".to_string();
    // Held open until the answer is in: closing it is how the CLI is told to exit.
    let mut stdin = child.stdin.take().ok_or_else(gone)?;
    let stdout = child.stdout.take().ok_or_else(gone)?;
    // Drained while it runs, so a chatty CLI cannot fill the pipe and stall.
    let stderr = child.stderr.take().map(|mut pipe| {
        std::thread::spawn(move || {
            let mut s = String::new();
            let _ = pipe.read_to_string(&mut s);
            s
        })
    });
    let (tx, rx) = mpsc::channel::<String>();
    std::thread::spawn(move || {
        for line in BufReader::new(stdout).lines().map_while(Result::ok) {
            if tx.send(line).is_err() {
                break;
            }
        }
    });
    writeln!(stdin, "{}", init_line()).and_then(|()| stdin.flush()).map_err(|_| gone())?;

    let mut answer = match answer_to(&rx, Instant::now() + FETCH_TIMEOUT, INIT_REQUEST_ID) {
        Ok(reply) => reply?,
        Err(NoAnswer::TimedOut) => return Err("Claude Code did not answer in time.".into()),
        Err(NoAnswer::Gone) => {
            // It has exited, so what it said on the way out is in.
            let why = stderr.and_then(|t| t.join().ok()).unwrap_or_default();
            let why = why.trim();
            return Err(if why.is_empty() { gone() } else { why.to_string() });
        }
    };
    // The commands are in hand whatever comes of this.
    if writeln!(stdin, "{}", settings_line()).and_then(|()| stdin.flush()).is_ok() {
        if let Ok(Ok(settings)) = answer_to(&rx, Instant::now() + SETTINGS_TIMEOUT, SETTINGS_REQUEST_ID) {
            if let Some(kept) = answer.as_object_mut() {
                kept.insert("switch_models_on_flag".into(), flagged_message_switch(&settings));
            }
        }
    }
    Ok(answer)
}

/// One of the CLI's subcommands that is run to completion for the page.
struct Errand {
    /// What the page asks for it by.
    name: &'static str,
    args: &'static [&'static str],
    /// Whether the page's JSON goes in on its stdin. Without this nothing does.
    reads_input: bool,
    /// Whether it is run in the conversation's folder, which then has to be there.
    in_folder: bool,
}

/// The CLI's own subcommands run here, each as the extension runs it.
///
/// The first six save what the dialogs change (`claude edit-… --json`, the edit as
/// JSON on stdin, in the conversation's folder), so the files they write are written
/// by the thing that owns their format.
const ERRANDS: [Errand; 9] = [
    Errand { name: "edit-memory-settings", args: &["edit-memory-settings", "--json"], reads_input: true, in_folder: true },
    Errand { name: "edit-skill-overrides", args: &["edit-skill-overrides", "--json"], reads_input: true, in_folder: true },
    Errand { name: "edit-chrome-settings", args: &["edit-chrome-settings", "--json"], reads_input: true, in_folder: true },
    Errand { name: "edit-permission-rules", args: &["edit-permission-rules", "--json"], reads_input: true, in_folder: true },
    Errand { name: "edit-hook", args: &["edit-hook", "--json"], reads_input: true, in_folder: true },
    Errand { name: "edit-sandbox-settings", args: &["edit-sandbox-settings", "--json"], reads_input: true, in_folder: true },
    // Whether the Claude Design authorization is there. The sign-in itself is a
    // conversation, not an errand ([`crate::design_login`]).
    Errand { name: "design-login-status", args: &["design-login", "--json", "--status"], reads_input: true, in_folder: true },
    // Signs this computer out of Claude. Given nothing and run wherever this process
    // is, as the extension runs it: it is not about a folder.
    Errand { name: "auth-logout", args: &["auth", "logout"], reads_input: false, in_folder: false },
    // Whether anyone is signed in. Of what it prints, only that is handed on ([`signed_in`]).
    Errand { name: AUTH_STATUS, args: &["auth", "status", "--json"], reads_input: false, in_folder: false },
];

/// The errand that asks whether anyone is signed in.
const AUTH_STATUS: &str = "auth-status";

/// Whether `claude auth status --json` says somebody is signed in, or `None` when what
/// it printed does not say. It prints who as well (email, organization); that is read
/// past here and goes no further.
fn signed_in(printed: &str) -> Option<bool> {
    let said: serde_json::Value = serde_json::from_str(printed.trim()).ok()?;
    said["loggedIn"].as_bool()
}

/// What the status errand hands on in place of what the CLI printed: `{"loggedIn":bool}`
/// as the errand's output, whether the CLI left with success or not (it need not when
/// nobody is signed in), or the failure as it was when it does not say.
fn auth_status(result: Result<String, String>) -> Result<String, String> {
    let printed = match &result {
        Ok(text) | Err(text) => text,
    };
    match signed_in(printed) {
        Some(yes) => Ok(serde_json::json!({ "loggedIn": yes }).to_string()),
        None => Err(result.err().unwrap_or_else(|| "Claude Code did not say whether anyone is signed in.".into())),
    }
}

fn errand(name: &str) -> Option<&'static Errand> {
    ERRANDS.iter().find(|e| e.name == name)
}

/// How long an edit may take — the extension's limit.
const EDIT_TIMEOUT: Duration = Duration::from_secs(30);

/// The arguments `subcommand` is run with, or `None` when it is not one of the [`ERRANDS`].
fn edit_args(subcommand: &str) -> Option<Vec<String>> {
    errand(subcommand).map(|e| e.args.iter().map(|arg| arg.to_string()).collect())
}

/// The edit as the CLI is to read it on stdin, or `None` when it is not a JSON object.
fn edit_input(input_json: &str) -> Option<String> {
    let value: serde_json::Value = serde_json::from_str(input_json).ok()?;
    value.is_object().then(|| value.to_string())
}

/// What [`edit`] returns: `{"ok":true,"output"}` with what the CLI printed, or
/// `{"ok":false,"error"}` with what it said went wrong.
fn edit_json(result: Result<String, String>) -> String {
    match result {
        Ok(output) => serde_json::json!({ "ok": true, "output": output.trim() }),
        Err(error) => serde_json::json!({ "ok": false, "error": error }),
    }
    .to_string()
}

/// Saves one change a dialog made, by running the CLI's own edit subcommand in `cwd`
/// with the change as JSON on its stdin. The other [`ERRANDS`] are run the same way.
///
/// **Blocking** — runs the CLI, up to 30s. Off the UI thread.
pub fn edit(claude_cmd: &str, cwd: &str, subcommand: &str, input_json: &str) -> String {
    let mut result = run_edit(claude_cmd, cwd, subcommand, input_json);
    if subcommand == AUTH_STATUS {
        result = auth_status(result);
    }
    if crate::is_debug() {
        // The subcommand and the outcome only: an edit can carry a rule or a hook's command.
        match &result {
            Ok(_) => eprintln!("[cli-ask] claude {subcommand} finished (in {cwd})"),
            Err(error) => eprintln!("[cli-ask] claude {subcommand} failed: {error} (in {cwd})"),
        }
    }
    edit_json(result)
}

fn run_edit(claude_cmd: &str, cwd: &str, subcommand: &str, input_json: &str) -> Result<String, String> {
    let (Some(errand), Some(args), Some(input)) = (errand(subcommand), edit_args(subcommand), edit_input(input_json)) else {
        return Err("Invalid request.".into());
    };
    let mut cmd = crate::launch::claude_command(claude_cmd, &args);
    if errand.in_folder {
        if cwd.is_empty() || !std::path::Path::new(cwd).is_dir() {
            return Err(format!("Working directory not found: {cwd}"));
        }
        cmd.current_dir(cwd);
    }
    cmd.stdin(Stdio::piped()).stdout(Stdio::piped()).stderr(Stdio::piped());

    #[cfg(windows)]
    cmd.creation_flags(0x08000000); // CREATE_NO_WINDOW

    for (k, v) in crate::shell_env::captured_env().to_inject() {
        cmd.env(k, v);
    }
    // The CLI refuses these edits from "an editor started inside a Claude Code session",
    // which is how it reads these two when an Eclipse was itself started from one. The
    // extension clears them for the same reason.
    cmd.env_remove("CLAUDECODE").env_remove("CLAUDE_CODE_CHILD_SESSION");

    let mut child = cmd.spawn().map_err(|e| format!("Could not run Claude Code: {e}"))?;
    if let Some(mut stdin) = child.stdin.take() {
        // Closed as it goes out of scope here: the CLI reads the edit to the end of its input.
        if errand.reads_input {
            let _ = stdin.write_all(input.as_bytes());
        }
    }
    // Both pipes drained while it runs, so a chatty CLI cannot fill one and stall.
    let drain = |pipe: Option<Box<dyn Read + Send>>| {
        std::thread::spawn(move || {
            let mut s = String::new();
            if let Some(mut p) = pipe {
                let _ = p.read_to_string(&mut s);
            }
            s
        })
    };
    let out = drain(child.stdout.take().map(|p| Box::new(p) as Box<dyn Read + Send>));
    let err = drain(child.stderr.take().map(|p| Box::new(p) as Box<dyn Read + Send>));

    let started = Instant::now();
    let status = loop {
        match child.try_wait() {
            Ok(Some(status)) => break status,
            Ok(None) if started.elapsed() < EDIT_TIMEOUT => std::thread::sleep(Duration::from_millis(50)),
            Ok(None) => {
                crate::launch::kill_process_tree(&mut child);
                let _ = child.wait();
                return Err("Claude Code did not finish in time.".into());
            }
            Err(e) => return Err(format!("Could not run Claude Code: {e}")),
        }
    };
    let stdout = out.join().unwrap_or_default();
    let stderr = err.join().unwrap_or_default();
    if status.success() {
        return Ok(stdout);
    }
    let said = if stderr.trim().is_empty() { stdout.trim() } else { stderr.trim() };
    Err(if said.is_empty() { format!("Claude Code exited with {status}.") } else { said.to_string() })
}

// ---------------------------------------------------------------------------
// Tests
// ---------------------------------------------------------------------------

#[cfg(test)]
mod tests {
    use super::*;
    use serde_json::json;

    fn parsed(s: &str) -> serde_json::Value {
        serde_json::from_str(s).expect("valid JSON")
    }

    /// `Some` once `line` is the CLI's answer to [`init_line`].
    fn init_reply(line: &str) -> Option<Result<serde_json::Value, String>> {
        reply_to(line, INIT_REQUEST_ID)
    }

    #[test]
    fn a_live_request_carries_the_token_and_the_request() {
        let line = request_line("t1", &json!({"subtype":"get_memory_dialog"})).expect("allowed");
        assert_eq!(
            parsed(&line),
            json!({"type":"control_request","request_id":"eclipse-ask-t1","request":{"subtype":"get_memory_dialog"}})
        );
        assert!(!line.contains('\n'), "one line on the CLI's stdin");
    }

    #[test]
    fn every_reading_request_of_the_dialogs_is_allowed() {
        for subtype in [
            "get_memory_dialog", "get_skills_dialog", "get_status", "get_chrome_dialog", "get_chrome_browsers",
            "get_hooks_listing", "list_permission_rules", "get_sandbox_dialog", "get_plan", "export_conversation",
            "get_context_usage", "get_usage",
        ] {
            assert!(request_line("t", &json!({ "subtype": subtype })).is_some(), "{subtype}");
        }
    }

    fn sendable(request: serde_json::Value) -> bool {
        request_line("t", &request).is_some()
    }

    #[test]
    fn an_output_style_is_saved_for_this_folder_and_this_user_and_nothing_else_is() {
        assert!(sendable(json!({"subtype":"update_settings","source":"localSettings","settings":{"outputStyle":"Explanatory"}})));
        for refused in [
            json!({"subtype":"update_settings","source":"userSettings","settings":{"outputStyle":"Explanatory"}}),
            json!({"subtype":"update_settings","source":"projectSettings","settings":{"outputStyle":"Explanatory"}}),
            json!({"subtype":"update_settings","settings":{"outputStyle":"Explanatory"}}),
            json!({"subtype":"update_settings","source":"localSettings","settings":{"outputStyle":7}}),
            json!({"subtype":"update_settings","source":"localSettings","settings":{"outputStyle":"x","permissions":{"allow":["Bash"]}}}),
            json!({"subtype":"update_settings","source":"localSettings","settings":{"permissions":{"allow":["Bash"]}}}),
            json!({"subtype":"update_settings","source":"localSettings","settings":{}}),
            json!({"subtype":"update_settings","source":"localSettings","settings":{"outputStyle":"x"},"extra":1}),
        ] {
            assert!(!sendable(refused.clone()), "{refused}");
        }
    }

    #[test]
    fn a_running_session_is_told_of_the_flagged_message_switch_and_of_no_other_setting() {
        assert!(sendable(json!({"subtype":"apply_flag_settings","settings":{"switchModelsOnFlag":false}})));
        assert!(sendable(json!({"subtype":"apply_flag_settings","settings":{"switchModelsOnFlag":true}})));
        for refused in [
            json!({"subtype":"apply_flag_settings","settings":{"switchModelsOnFlag":"no"}}),
            json!({"subtype":"apply_flag_settings","settings":{"switchModelsOnFlag":null}}),
            json!({"subtype":"apply_flag_settings","settings":{"model":"opus"}}),
            json!({"subtype":"apply_flag_settings","settings":{"switchModelsOnFlag":false,"permissions":{"defaultMode":"bypassPermissions"}}}),
            json!({"subtype":"apply_flag_settings","settings":[]}),
            json!({"subtype":"apply_flag_settings"}),
        ] {
            assert!(!sendable(refused.clone()), "{refused}");
        }
    }

    #[test]
    fn a_report_goes_in_the_one_shape_its_dialog_sends() {
        assert!(sendable(json!({"subtype":"submit_feedback","description":"","surface":"ide","save_locally":false})));
        assert!(sendable(json!({"subtype":"submit_feedback","description":"It froze","surface":"ide","save_locally":true})));
        for refused in [
            json!({"subtype":"submit_feedback","description":"x","surface":"cli","save_locally":false}),
            json!({"subtype":"submit_feedback","description":"x","surface":"ide"}),
            json!({"subtype":"submit_feedback","surface":"ide","save_locally":false}),
            json!({"subtype":"submit_feedback","description":"x","surface":"ide","save_locally":false,"attach_transcript":true}),
        ] {
            assert!(!sendable(refused.clone()), "{refused}");
        }
    }

    #[test]
    fn signing_in_is_three_requests_each_with_what_it_takes() {
        assert!(sendable(json!({"subtype":"claude_authenticate","loginWithClaudeAi":true})));
        assert!(sendable(json!({"subtype":"claude_authenticate","loginWithClaudeAi":false})));
        assert!(sendable(json!({"subtype":"claude_oauth_wait_for_completion"})));
        assert!(sendable(json!({"subtype":"claude_oauth_callback","authorizationCode":"abc","state":"def"})));
        for refused in [
            json!({"subtype":"claude_authenticate"}),
            json!({"subtype":"claude_authenticate","loginWithClaudeAi":"yes"}),
            json!({"subtype":"claude_oauth_wait_for_completion","timeout":0}),
            json!({"subtype":"claude_oauth_callback","authorizationCode":"abc"}),
            json!({"subtype":"claude_oauth_callback","authorizationCode":"abc","state":7}),
        ] {
            assert!(!sendable(refused.clone()), "{refused}");
        }
    }

    #[test]
    fn a_request_that_writes_or_is_not_a_dialogs_is_refused() {
        for subtype in [
            "update_settings", "apply_flag_settings", "submit_feedback", "claude_authenticate", "claude_oauth_callback",
            "mcp_set_servers", "set_model", "set_permission_mode", "remote_control", "initialize", "interrupt", "",
            "get_settings",
        ] {
            assert!(request_line("t", &json!({ "subtype": subtype })).is_none(), "{subtype}");
        }
        assert!(request_line("t", &json!({})).is_none(), "no subtype at all");
    }

    #[test]
    fn a_token_that_could_change_the_request_id_is_refused() {
        let get = json!({"subtype":"get_status"});
        for token in ["", "a b", "a\"b", "a\nb", &"x".repeat(65)] {
            assert!(request_line(token, &get).is_none(), "{token:?}");
        }
        assert!(request_line("Tab-1_9", &get).is_some());
    }

    #[test]
    fn a_reply_comes_back_under_its_token_with_the_cli_answer() {
        let inner = json!({"subtype":"success","request_id":"eclipse-ask-t7","response":{"sections":[{"title":"Session"}]}});
        assert_eq!(
            parsed(&reply_json(&inner).expect("ours")),
            json!({"token":"t7","ok":true,"response":{"sections":[{"title":"Session"}]}})
        );
    }

    #[test]
    fn an_error_reply_says_what_the_cli_said() {
        let inner = json!({"subtype":"error","request_id":"eclipse-ask-t7","error":"  Unknown request  "});
        assert_eq!(parsed(&reply_json(&inner).expect("ours")), json!({"token":"t7","ok":false,"error":"Unknown request"}));
        let silent = json!({"subtype":"error","request_id":"eclipse-ask-t7"});
        assert_eq!(parsed(&reply_json(&silent).expect("ours")), json!({"token":"t7","ok":false,"error":"Unknown error"}));
    }

    #[test]
    fn a_reply_to_someone_elses_request_is_not_ours() {
        assert!(reply_json(&json!({"subtype":"success","request_id":"eclipse-mcp-t7","response":{}})).is_none());
        assert!(reply_json(&json!({"subtype":"success","request_id":"eclipse-once-init","response":{}})).is_none());
        assert!(reply_json(&json!({"subtype":"success","response":{}})).is_none());
    }

    #[test]
    fn the_mcp_window_does_not_take_our_replies_for_its_own() {
        let ours = json!({"subtype":"success","request_id":"eclipse-ask-t7","response":{}});
        assert!(crate::mcp_servers::reply_json(&ours).is_none());
    }

    #[test]
    fn fast_mode_is_read_off_the_init_event_and_off_a_turns_result() {
        let init = json!({"type":"system","subtype":"init","session_id":"s","fast_mode_state":"off","fast_mode_disabled_reason":null});
        assert_eq!(parsed(&fast_mode_json(&init).expect("it says")), json!({"state":"off","disabledReason":null}));
        let result = json!({"type":"result","subtype":"success","fast_mode_state":"on","fast_mode_disabled_reason":null});
        assert_eq!(parsed(&fast_mode_json(&result).expect("it says")), json!({"state":"on","disabledReason":null}));
        let refused = json!({"type":"result","subtype":"success","fast_mode_state":"off","fast_mode_disabled_reason":"model"});
        assert_eq!(parsed(&fast_mode_json(&refused).expect("it says")), json!({"state":"off","disabledReason":"model"}));
    }

    #[test]
    fn an_event_that_does_not_say_whether_fast_mode_is_on_gives_nothing() {
        for event in [
            json!({"type":"system","subtype":"init","session_id":"s"}),
            json!({"type":"result","subtype":"success"}),
            json!({"type":"result","subtype":"success","fast_mode_state":null}),
            json!({"type":"system","subtype":"status","fast_mode_state":"on"}),
            json!({"type":"assistant","fast_mode_state":"on"}),
            json!({"fast_mode_state":"on"}),
        ] {
            assert!(fast_mode_json(&event).is_none(), "{event}");
        }
    }

    #[test]
    fn a_short_lived_process_is_started_for_stream_json_with_the_settings_file() {
        assert_eq!(
            once_args("C:\\Temp\\s.json"),
            ["-p", "--input-format", "stream-json", "--output-format", "stream-json", "--verbose",
             "--settings", "C:\\Temp\\s.json"]
        );
    }

    #[test]
    fn the_settings_file_switches_hooks_off_and_nothing_else() {
        let path = no_hooks_settings().expect("written");
        let text = std::fs::read_to_string(&path).expect("readable");
        assert_eq!(parsed(&text), json!({"disableAllHooks":true}));
    }

    #[test]
    fn the_one_request_is_initialize() {
        let line = init_line();
        assert_eq!(
            parsed(&line),
            json!({"type":"control_request","request_id":"eclipse-once-init","request":{"subtype":"initialize"}})
        );
        assert!(!line.contains('\n'));
    }

    #[test]
    fn the_answer_is_picked_out_of_everything_else_the_cli_prints() {
        // What a 2.1.291 process printed before answering, with a hook configured.
        for other in [
            r#"{"type":"system","subtype":"hook_started","hook_name":"SessionStart:startup"}"#,
            r#"{"type":"control_response","response":{"subtype":"success","request_id":"eclipse-ask-t1","response":{}}}"#,
            r#"{"type":"control_request","request_id":"x","request":{"subtype":"can_use_tool"}}"#,
            "not json at all",
            "",
        ] {
            assert!(init_reply(other).is_none(), "{other}");
        }
        let answer = r#"{"type":"control_response","response":{"subtype":"success","request_id":"eclipse-once-init","response":{"commands":[{"name":"init"}]}}}"#;
        assert_eq!(init_reply(answer), Some(Ok(json!({"commands":[{"name":"init"}]}))));
        assert_eq!(init_reply(&format!("{answer}\r")), Some(Ok(json!({"commands":[{"name":"init"}]}))), "a CRLF line");
    }

    #[test]
    fn the_second_request_asks_for_the_settings_in_effect() {
        let line = settings_line();
        assert_eq!(
            parsed(&line),
            json!({"type":"control_request","request_id":"eclipse-once-settings","request":{"subtype":"get_settings"}})
        );
        assert!(!line.contains('\n'));
    }

    #[test]
    fn each_answer_is_matched_to_its_own_request() {
        let init = r#"{"type":"control_response","response":{"subtype":"success","request_id":"eclipse-once-init","response":{"commands":[]}}}"#;
        let settings = r#"{"type":"control_response","response":{"subtype":"success","request_id":"eclipse-once-settings","response":{"effective":{}}}}"#;
        assert_eq!(reply_to(settings, SETTINGS_REQUEST_ID), Some(Ok(json!({"effective":{}}))));
        assert_eq!(reply_to(init, SETTINGS_REQUEST_ID), None);
        assert_eq!(reply_to(settings, INIT_REQUEST_ID), None);
        assert_eq!(init_reply(settings), None);
        let refused = r#"{"type":"control_response","response":{"subtype":"error","request_id":"eclipse-once-settings","error":"Unsupported control request subtype: get_settings"}}"#;
        assert_eq!(reply_to(refused, SETTINGS_REQUEST_ID), Some(Err("Unsupported control request subtype: get_settings".to_string())));
    }

    #[test]
    fn the_flagged_message_switch_is_read_off_the_settings_in_effect() {
        assert_eq!(flagged_message_switch(&json!({"effective":{"switchModelsOnFlag":false},"sources":[]})), json!(false));
        assert_eq!(flagged_message_switch(&json!({"effective":{"switchModelsOnFlag":true}})), json!(true));
        // Unset, which the CLI takes as on; and answers that do not say.
        for silent in [json!({"effective":{"model":"opus"}}), json!({"effective":{"switchModelsOnFlag":"yes"}}), json!({}), json!(null)] {
            assert_eq!(flagged_message_switch(&silent), json!(null), "{silent}");
        }
    }

    #[test]
    fn an_answer_that_is_an_error_carries_what_the_cli_said() {
        let refused = r#"{"type":"control_response","response":{"subtype":"error","request_id":"eclipse-once-init","error":"Not logged in"}}"#;
        assert_eq!(init_reply(refused), Some(Err("Not logged in".to_string())));
        let silent = r#"{"type":"control_response","response":{"subtype":"error","request_id":"eclipse-once-init"}}"#;
        assert_eq!(init_reply(silent), Some(Err("Unknown error".to_string())));
    }

    #[test]
    fn only_what_the_menus_are_drawn_from_is_handed_on() {
        let answer = json!({
            "commands": [{"name":"init","description":"d","argumentHint":"","builtin":true,"aliases":[]}],
            "models": [{"value":"opus","supportsFastMode":true}],
            "output_style": "default",
            "available_output_styles": ["default","Explanatory"],
            "fast_mode_state": "off",
            "fast_mode_disabled_reason": null,
            "feedback_mode": {"kind":"bundle"},
            "switch_models_on_flag": false,
            "account": {"email":"someone@example.com"},
            "pid": 4242,
            "agents": []
        });
        assert_eq!(
            parsed(&fetch_json(Ok(answer))),
            json!({
                "ok": true,
                "commands": [{"name":"init","description":"d","argumentHint":"","builtin":true,"aliases":[]}],
                "models": [{"value":"opus","supportsFastMode":true}],
                "output_style": "default",
                "available_output_styles": ["default","Explanatory"],
                "fast_mode_state": "off",
                "fast_mode_disabled_reason": null,
                "feedback_mode": {"kind":"bundle"},
                "switch_models_on_flag": false
            })
        );
    }

    #[test]
    fn an_older_cli_that_answers_with_less_still_gives_a_list() {
        assert_eq!(parsed(&fetch_json(Ok(json!({"commands":[]})))), json!({"ok":true,"commands":[]}));
    }

    #[test]
    fn a_failure_is_reported_with_its_reason() {
        assert_eq!(
            parsed(&fetch_json(Err("Claude Code did not answer in time.".into()))),
            json!({"ok":false,"error":"Claude Code did not answer in time."})
        );
    }

    /// The real thing, end to end: a folder whose own settings carry a SessionStart
    /// hook, asked through [`fetch_commands`]. The hook leaves a file behind when it
    /// runs, which it must not.
    #[test]
    #[ignore = "starts the installed Claude Code CLI"]
    fn the_installed_cli_answers_with_its_commands_and_runs_no_hook() {
        let folder = tempfile::tempdir().expect("a folder");
        let ran = folder.path().join("hook-ran.txt");
        let hook = format!("echo ran > \"{}\"", ran.to_string_lossy().replace('\\', "/"));
        std::fs::create_dir_all(folder.path().join(".claude")).expect("its .claude");
        std::fs::write(
            folder.path().join(".claude").join("settings.json"),
            json!({"hooks":{"SessionStart":[{"hooks":[{"type":"command","command":hook}]}]}}).to_string(),
        )
        .expect("its settings");

        let started = Instant::now();
        let answer = parsed(&fetch_commands("", &folder.path().to_string_lossy()));
        let took = started.elapsed();

        assert_eq!(answer["ok"], json!(true), "{answer}");
        let commands = answer["commands"].as_array().expect("a list of commands");
        assert!(commands.len() > 20, "only {} commands", commands.len());
        for command in commands {
            assert!(command["name"].as_str().is_some_and(|n| !n.is_empty()), "{command}");
            assert!(command["description"].is_string(), "{command}");
        }
        assert!(commands.iter().any(|c| c["name"] == "init"), "no /init among them");
        assert!(answer.get("account").is_none() && answer.get("pid").is_none(), "more than the menus need");
        let switch = &answer["switch_models_on_flag"];
        assert!(answer.get("switch_models_on_flag").is_some() && (switch.is_null() || switch.is_boolean()), "{switch}");
        assert!(took < FETCH_TIMEOUT, "took {took:?}");
        assert!(!ran.exists(), "the folder's SessionStart hook ran");
        eprintln!("{} commands in {took:?}", commands.len());
    }

    /// Once it has answered and its stdin is closed, the CLI is left to finish by
    /// itself rather than being ended mid-step: it is a full Claude Code process with
    /// files of its own to close.
    #[test]
    #[ignore = "starts the installed Claude Code CLI"]
    fn a_short_lived_process_leaves_by_itself_once_it_has_answered() {
        let folder = tempfile::tempdir().expect("a folder");
        let (child, result) = asked("", &folder.path().to_string_lossy()).expect("started");
        assert!(result.is_ok(), "{result:?}");
        let started = Instant::now();
        let left = reap(child, EXIT_GRACE);
        eprintln!("left by itself: {left}, after {:?}", started.elapsed());
        assert!(left, "it had to be ended");
    }

    #[test]
    fn only_the_clis_own_edit_subcommands_are_run() {
        for sub in ["edit-memory-settings", "edit-skill-overrides", "edit-chrome-settings", "edit-permission-rules",
                    "edit-hook", "edit-sandbox-settings"] {
            assert_eq!(edit_args(sub), Some(vec![sub.to_string(), "--json".to_string()]), "{sub}");
        }
        for sub in ["mcp", "", "--help", "edit-hook --force", "edit-anything", "update", "auth", "logout", "auth logout",
                    "auth-login", "design-login", "setup-token"] {
            assert_eq!(edit_args(sub), None, "{sub}");
        }
    }

    #[test]
    fn whether_anyone_is_signed_in_is_all_that_is_kept_of_the_status() {
        let printed = r#"{
  "loggedIn": true,
  "authMethod": "claude.ai",
  "email": "someone@example.com",
  "orgName": "Example"
}"#;
        assert_eq!(signed_in(printed), Some(true));
        assert_eq!(auth_status(Ok(printed.into())), Ok(r#"{"loggedIn":true}"#.to_string()));
        // Signed out, the CLI may leave with a failure and the same kind of answer.
        assert_eq!(auth_status(Err(r#"{"loggedIn":false,"authMethod":"none"}"#.into())), Ok(r#"{"loggedIn":false}"#.to_string()));
        assert_eq!(auth_status(Ok(r#"{"loggedIn":false}"#.into())), Ok(r#"{"loggedIn":false}"#.to_string()));
    }

    #[test]
    fn a_status_that_does_not_say_is_a_failure_and_not_a_guess() {
        assert_eq!(auth_status(Err("Could not run Claude Code: not found".into())), Err("Could not run Claude Code: not found".to_string()));
        assert_eq!(auth_status(Ok("".into())), Err("Claude Code did not say whether anyone is signed in.".to_string()));
        assert_eq!(auth_status(Ok(r#"{"loggedIn":"yes"}"#.into())), Err("Claude Code did not say whether anyone is signed in.".to_string()));
        assert_eq!(signed_in("not json"), None);
    }

    #[test]
    fn the_status_is_asked_for_as_json_given_nothing_and_not_about_a_folder() {
        assert_eq!(edit_args("auth-status"), Some(vec!["auth".to_string(), "status".to_string(), "--json".to_string()]));
        let status = errand("auth-status").expect("an errand");
        assert!(!status.reads_input && !status.in_folder);
    }

    /// The real thing, reading only: the answer is the one field and nothing of who.
    #[test]
    #[ignore = "starts the installed Claude Code CLI"]
    fn the_installed_cli_says_whether_anyone_is_signed_in_and_no_more() {
        let answer = parsed(&edit("", "", "auth-status", "{}"));
        assert_eq!(answer["ok"], json!(true), "{answer}");
        let said = parsed(answer["output"].as_str().unwrap_or(""));
        assert!(said["loggedIn"].is_boolean(), "{said}");
        assert_eq!(said.as_object().map(|o| o.len()), Some(1), "more than whether: {said}");
    }

    #[test]
    fn signing_out_and_the_design_status_are_run_as_the_extension_runs_them() {
        assert_eq!(edit_args("auth-logout"), Some(vec!["auth".to_string(), "logout".to_string()]));
        let sign_out = errand("auth-logout").expect("an errand");
        assert!(!sign_out.reads_input && !sign_out.in_folder, "given nothing, and not about a folder");
        assert_eq!(
            edit_args("design-login-status"),
            Some(vec!["design-login".to_string(), "--json".to_string(), "--status".to_string()])
        );
        let status = errand("design-login-status").expect("an errand");
        assert!(status.reads_input && status.in_folder);
        for edit in ERRANDS.iter().filter(|e| e.name.starts_with("edit-")) {
            assert!(edit.reads_input && edit.in_folder, "{}", edit.name);
        }
    }

    #[test]
    fn an_edit_is_one_json_object() {
        assert_eq!(edit_input(r#"{ "enabledByDefault" : true }"#).as_deref(), Some(r#"{"enabledByDefault":true}"#));
        for bad in ["[1]", "\"x\"", "7", "not json", ""] {
            assert_eq!(edit_input(bad), None, "{bad}");
        }
    }

    #[test]
    fn an_edit_reports_what_the_cli_printed_or_what_it_said_went_wrong() {
        assert_eq!(parsed(&edit_json(Ok("saved
".into()))), json!({"ok":true,"output":"saved"}));
        assert_eq!(
            parsed(&edit_json(Err("op must be one of: add, remove".into()))),
            json!({"ok":false,"error":"op must be one of: add, remove"})
        );
    }

    #[test]
    fn an_edit_that_is_not_one_starts_nothing() {
        let folder = tempfile::tempdir().expect("a folder");
        let cwd = folder.path().to_string_lossy();
        assert_eq!(parsed(&edit("claude", &cwd, "mcp", "{}")), json!({"ok":false,"error":"Invalid request."}));
        assert_eq!(parsed(&edit("claude", &cwd, "edit-hook", "[]")), json!({"ok":false,"error":"Invalid request."}));
        let gone = parsed(&edit("claude", "", "edit-hook", "{}"));
        assert!(gone["error"].as_str().unwrap_or("").starts_with("Working directory not found: "), "{gone}");
    }

    /// The real thing with an edit that changes nothing: an empty one, which the CLI
    /// refuses by naming the field it wants. That it gets as far as reading the edit
    /// shows the subcommand ran, the JSON reached its stdin, and it did not take this
    /// for an editor started inside a Claude Code session.
    #[test]
    #[ignore = "starts the installed Claude Code CLI"]
    fn the_installed_cli_reads_an_edit_and_refuses_an_empty_one() {
        let folder = tempfile::tempdir().expect("a folder");
        let answer = parsed(&edit("", &folder.path().to_string_lossy(), "edit-chrome-settings", "{}"));
        assert_eq!(answer["ok"], json!(false), "{answer}");
        let error = answer["error"].as_str().unwrap_or("");
        assert!(error.contains("enabledByDefault"), "{error}");
        assert!(!error.contains("inside a Claude Code session"), "{error}");
    }

    /// The real thing, reading only: whether the Claude Design authorization is there.
    #[test]
    #[ignore = "starts the installed Claude Code CLI"]
    fn the_installed_cli_says_whether_the_design_authorization_is_there() {
        let folder = tempfile::tempdir().expect("a folder");
        let answer = parsed(&edit("", &folder.path().to_string_lossy(), "design-login-status", "{}"));
        assert_eq!(answer["ok"], json!(true), "{answer}");
        let state = parsed(answer["output"].as_str().unwrap_or("").lines().last().unwrap_or(""));
        for key in ["available", "signed_in", "can_sign_in_here"] {
            assert!(state[key].is_boolean(), "{key} in {state}");
        }
    }

    #[test]
    fn a_folder_that_is_not_there_is_reported_without_starting_anything() {
        let missing = std::env::temp_dir().join("claude-eclipse-no-such-folder-for-cli-ask");
        let json = parsed(&fetch_commands("claude", &missing.to_string_lossy()));
        assert_eq!(json["ok"], json!(false));
        assert!(json["error"].as_str().unwrap_or("").starts_with("Working directory not found: "), "{json}");
        assert_eq!(parsed(&fetch_commands("claude", ""))["ok"], json!(false), "an empty folder name");
    }
}
