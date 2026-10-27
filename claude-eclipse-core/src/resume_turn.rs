//! Continue After Reload: a conversation restored when Eclipse starts again finishes
//! the step that Eclipse's closing cut off.
//!
//! The CLI does the deciding. Started on the conversation with
//! `CLAUDE_CODE_RESUME_INTERRUPTED_TURN` set, it re-runs the last turn by itself when
//! that turn was cut off, and does nothing when the turn had finished or the user had
//! stopped it (verified against 2.1.310). What is left to this module is when it is
//! safe to ask: the transcript is recent, and no other live process holds the
//! conversation — to the CLI, a turn still running somewhere else looks exactly like
//! one that was cut off.

use std::io::Read;
use std::path::Path;
use std::time::{Duration, SystemTime};

/// How old the newest message may be for its turn to be re-run, as the CLI is told.
pub const MAX_AGE: Duration = Duration::from_secs(60 * 60);

/// What [`decide`] found.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum Decision {
    /// Safe to start the conversation with the variable set.
    Continue,
    /// There is no transcript to continue.
    NoTranscript,
    /// The newest message is older than [`MAX_AGE`].
    TooOld,
    /// Another live process has the conversation open.
    HeldElsewhere,
}

impl Decision {
    pub fn as_str(self) -> &'static str {
        match self {
            Decision::Continue => "continue",
            Decision::NoTranscript => "no-transcript",
            Decision::TooOld => "too-old",
            Decision::HeldElsewhere => "held-elsewhere",
        }
    }
}

/// Whether the conversation `session_id` may be started to continue its last turn.
///
/// `transcript` is its `.jsonl`; when it was last written stands for its newest
/// message. `sessions_dir` is the CLI's register of running sessions, `None` when
/// there is no config folder to look in. `alive` says whether a process id is running.
pub fn decide(
    transcript: &Path,
    sessions_dir: Option<&Path>,
    session_id: &str,
    now: SystemTime,
    alive: &dyn Fn(u32) -> bool,
) -> Decision {
    let Ok(written) = std::fs::metadata(transcript).and_then(|m| m.modified()) else {
        return Decision::NoTranscript;
    };
    // A clock that moved backwards reads as "just written": the CLI applies the same
    // limit itself, so the worst case is a process started for nothing.
    if now.duration_since(written).unwrap_or_default() >= MAX_AGE {
        return Decision::TooOld;
    }
    if sessions_dir.is_some_and(|d| held_elsewhere(d, session_id, alive)) {
        return Decision::HeldElsewhere;
    }
    Decision::Continue
}

/// Whether a live process other than ours has `session_id` open, from the CLI's own
/// register of running sessions (`<config dir>/sessions/<pid>.json`).
///
/// An entry counts only while its process runs: one killed outright leaves its file
/// behind. Mistaking a reused process id for the owner only means a turn is not
/// re-run, which is the safe way to be wrong.
pub fn held_elsewhere(sessions_dir: &Path, session_id: &str, alive: &dyn Fn(u32) -> bool) -> bool {
    let Ok(entries) = std::fs::read_dir(sessions_dir) else {
        return false;
    };
    entries.flatten().any(|e| {
        let path = e.path();
        if path.extension().is_none_or(|x| x != "json") {
            return false;
        }
        let Ok(text) = std::fs::read_to_string(&path) else {
            return false;
        };
        let Ok(entry) = serde_json::from_str::<serde_json::Value>(&text) else {
            return false;
        };
        entry["sessionId"].as_str() == Some(session_id)
            && entry["pid"].as_u64().and_then(|p| u32::try_from(p).ok()).is_some_and(alive)
    })
}

/// How much of the end of a transcript [`tail_unfinished`] reads: the last message is
/// what counts, and a long conversation's file runs to many megabytes.
const TAIL_BYTES: u64 = 256 * 1024;

/// Whether the conversation's last turn looks cut off, from the end of its transcript.
///
/// The last message decides, bookkeeping after it (titles, meta lines) looked past. A
/// turn is over when Claude's reply ended it (`stop_reason` `end_turn`) or the user
/// stopped it (the CLI's "[Request interrupted by user" line); anything else — a tool
/// call with no result, a result or a prompt with nothing from Claude after it — was cut
/// off. Wrong in either direction costs little: the CLI makes the real decision.
pub fn tail_unfinished(transcript: &Path) -> bool {
    use std::io::{Seek, SeekFrom};
    let Ok(mut file) = std::fs::File::open(transcript) else {
        return false;
    };
    let len = file.metadata().map(|m| m.len()).unwrap_or(0);
    let start = len.saturating_sub(TAIL_BYTES);
    if file.seek(SeekFrom::Start(start)).is_err() {
        return false;
    }
    let mut bytes = Vec::new();
    if file.read_to_end(&mut bytes).is_err() {
        return false;
    }
    let text = String::from_utf8_lossy(&bytes);
    // Started mid-file: the first line is a fragment.
    let lines: Vec<&str> = text.lines().skip(if start > 0 { 1 } else { 0 }).collect();
    let last = lines
        .into_iter()
        .rev()
        .filter_map(|l| serde_json::from_str::<serde_json::Value>(l).ok())
        .find(|e| {
            matches!(e["type"].as_str(), Some("user") | Some("assistant"))
                && e["message"].is_object()
                && !e["isMeta"].as_bool().unwrap_or(false)
                && !is_bookkeeping(e)
        });
    let Some(last) = last else { return false };
    let message = &last["message"];
    match last["type"].as_str() {
        Some("assistant") => message["stop_reason"].as_str() != Some("end_turn"),
        _ => !stopped_by_user(&message["content"]),
    }
}

/// Whether a user line is the CLI's own record rather than a turn: a compaction's summary,
/// or a local command (`/compact`) and what it printed. Written after a finished turn, they
/// would otherwise read as a prompt nothing answered.
fn is_bookkeeping(entry: &serde_json::Value) -> bool {
    if entry["isCompactSummary"].as_bool().unwrap_or(false)
        || entry["isVisibleInTranscriptOnly"].as_bool().unwrap_or(false)
    {
        return true;
    }
    entry["message"]["content"].as_str().is_some_and(|s| {
        let s = s.trim_start();
        ["<command-name>", "<command-message>", "<local-command-stdout>", "<local-command-stderr>"]
            .iter()
            .any(|tag| s.starts_with(tag))
    })
}

/// Whether a user message is the CLI's record of the user stopping the turn.
fn stopped_by_user(content: &serde_json::Value) -> bool {
    let said = |t: &str| t.starts_with("[Request interrupted by user");
    match content {
        serde_json::Value::String(s) => said(s),
        serde_json::Value::Array(blocks) => blocks.iter().any(|b| b["text"].as_str().is_some_and(said)),
        _ => false,
    }
}

/// Whether a conversation restored at startup is one Continue After Reload will start:
/// [`decide`] allows it and its last turn looks cut off ([`tail_unfinished`]). Asked
/// without starting anything, so the page can hold Remote Control back for it until the
/// continue has started its process.
pub fn candidate(
    transcript: &Path,
    sessions_dir: Option<&Path>,
    session_id: &str,
    now: SystemTime,
    alive: &dyn Fn(u32) -> bool,
) -> bool {
    decide(transcript, sessions_dir, session_id, now, alive) == Decision::Continue && tail_unfinished(transcript)
}

/// [`candidate`] for the conversation `session_id` of the folder `workspace_root`, its
/// transcript and the CLI's register found where the CLI keeps them.
pub fn candidate_in(workspace_root: &str, session_id: &str) -> bool {
    if session_id.is_empty() || !crate::bookmarks::plain_id(session_id) {
        return false;
    }
    let Some(transcript) = crate::session::projects_dir(workspace_root).map(|d| d.join(format!("{session_id}.jsonl")))
    else {
        return false;
    };
    let sessions = crate::session::config_dir().map(|d| d.join("sessions"));
    candidate(&transcript, sessions.as_deref(), session_id, SystemTime::now(), &pid_alive)
}

/// The variables that ask the CLI to continue an interrupted turn.
pub fn resume_vars() -> [(&'static str, String); 2] {
    [
        ("CLAUDE_CODE_RESUME_INTERRUPTED_TURN", "1".to_string()),
        ("CLAUDE_CODE_RESUME_INTERRUPTED_TURN_MAX_AGE_MS", MAX_AGE.as_millis().to_string()),
    ]
}

/// Of the variables a process would inherit, the `CLAUDE_CODE_RESUME_*` ones: removed
/// from every chat process, so only the spawn that asks for a continue carries them.
/// Eclipse started from inside a Claude Code session would otherwise hand them to every
/// conversation, and each would re-run its last turn.
pub fn inherited_resume_vars<I: IntoIterator<Item = String>>(names: I) -> Vec<String> {
    names
        .into_iter()
        .filter(|n| n.to_ascii_uppercase().starts_with("CLAUDE_CODE_RESUME_"))
        .collect()
}

#[cfg(windows)]
#[link(name = "kernel32")]
extern "system" {
    fn OpenProcess(desired_access: u32, inherit_handle: i32, pid: u32) -> isize;
    fn GetExitCodeProcess(process: isize, exit_code: *mut u32) -> i32;
    fn CloseHandle(handle: isize) -> i32;
}

/// Whether the process `pid` is running.
pub fn pid_alive(pid: u32) -> bool {
    #[cfg(windows)]
    {
        const PROCESS_QUERY_LIMITED_INFORMATION: u32 = 0x1000;
        const STILL_ACTIVE: u32 = 259;
        // A process that has exited can still be opened while anything holds a handle
        // to it; its exit code says whether it is still running.
        let h = unsafe { OpenProcess(PROCESS_QUERY_LIMITED_INFORMATION, 0, pid) };
        if h == 0 {
            return false;
        }
        let mut code = 0u32;
        let ok = unsafe { GetExitCodeProcess(h, &mut code) };
        unsafe { CloseHandle(h) };
        ok != 0 && code == STILL_ACTIVE
    }
    #[cfg(not(windows))]
    {
        // `kill -0` delivers nothing; it only asks whether the process exists. The same on
        // Linux, macOS and FreeBSD, where /proc cannot be counted on.
        std::process::Command::new("kill")
            .args(["-0", &pid.to_string()])
            .stdout(std::process::Stdio::null())
            .stderr(std::process::Stdio::null())
            .status()
            .is_ok_and(|s| s.success())
    }
}

// ---------------------------------------------------------------------------
// Tests
// ---------------------------------------------------------------------------

#[cfg(test)]
mod tests {
    use super::*;
    use std::fs;

    const ID: &str = "601c4f0f-6a43-41de-badc-172f28fd262c";

    fn transcript(dir: &Path, age: Duration) -> std::path::PathBuf {
        let p = dir.join(format!("{ID}.jsonl"));
        fs::write(&p, "{}\n").unwrap();
        let f = fs::File::options().write(true).open(&p).unwrap();
        f.set_modified(SystemTime::now() - age).unwrap();
        p
    }

    fn register(sessions: &Path, pid: u32, session_id: &str) {
        fs::create_dir_all(sessions).unwrap();
        fs::write(
            sessions.join(format!("{pid}.json")),
            format!(r#"{{"pid":{pid},"sessionId":"{session_id}","status":"busy"}}"#),
        )
        .unwrap();
    }

    #[test]
    fn a_recent_conversation_nobody_else_holds_is_continued() {
        let tmp = tempfile::tempdir().unwrap();
        let t = transcript(tmp.path(), Duration::from_secs(60));
        let sessions = tmp.path().join("sessions");
        assert_eq!(decide(&t, Some(&sessions), ID, SystemTime::now(), &|_| true), Decision::Continue);
    }

    #[test]
    fn without_a_transcript_there_is_nothing_to_continue() {
        let tmp = tempfile::tempdir().unwrap();
        let missing = tmp.path().join(format!("{ID}.jsonl"));
        assert_eq!(decide(&missing, None, ID, SystemTime::now(), &|_| false), Decision::NoTranscript);
    }

    #[test]
    fn a_conversation_last_written_over_an_hour_ago_is_left_alone() {
        let tmp = tempfile::tempdir().unwrap();
        let t = transcript(tmp.path(), Duration::from_secs(2 * 60 * 60));
        assert_eq!(decide(&t, None, ID, SystemTime::now(), &|_| false), Decision::TooOld);
    }

    #[test]
    fn a_conversation_a_live_process_holds_is_left_alone() {
        let tmp = tempfile::tempdir().unwrap();
        let t = transcript(tmp.path(), Duration::from_secs(60));
        let sessions = tmp.path().join("sessions");
        register(&sessions, 4242, ID);
        assert_eq!(decide(&t, Some(&sessions), ID, SystemTime::now(), &|pid| pid == 4242), Decision::HeldElsewhere);
    }

    #[test]
    fn an_entry_left_by_a_process_that_has_ended_holds_nothing() {
        let tmp = tempfile::tempdir().unwrap();
        let sessions = tmp.path().join("sessions");
        register(&sessions, 4242, ID);
        assert!(!held_elsewhere(&sessions, ID, &|_| false));
    }

    #[test]
    fn another_conversation_running_does_not_hold_this_one() {
        let tmp = tempfile::tempdir().unwrap();
        let sessions = tmp.path().join("sessions");
        register(&sessions, 4242, "some-other-session");
        assert!(!held_elsewhere(&sessions, ID, &|_| true));
    }

    #[test]
    fn unreadable_entries_and_a_missing_register_hold_nothing() {
        let tmp = tempfile::tempdir().unwrap();
        let sessions = tmp.path().join("sessions");
        assert!(!held_elsewhere(&sessions, ID, &|_| true), "no register at all");
        fs::create_dir_all(&sessions).unwrap();
        fs::write(sessions.join("1.json"), "not json").unwrap();
        fs::write(sessions.join("2.json"), r#"{"sessionId":"601c4f0f-6a43-41de-badc-172f28fd262c"}"#).unwrap();
        fs::write(sessions.join("3.key"), ID).unwrap();
        assert!(!held_elsewhere(&sessions, ID, &|_| true), "no pid, not json, not an entry");
        register(&sessions, 7, ID);
        assert!(held_elsewhere(&sessions, ID, &|pid| pid == 7), "a good entry among bad ones still counts");
    }

    #[test]
    fn the_variables_ask_for_the_turn_within_the_hour() {
        let vars = resume_vars();
        assert!(vars.contains(&("CLAUDE_CODE_RESUME_INTERRUPTED_TURN", "1".to_string())));
        assert!(vars.contains(&("CLAUDE_CODE_RESUME_INTERRUPTED_TURN_MAX_AGE_MS", "3600000".to_string())));
    }

    #[test]
    fn inherited_resume_variables_are_found_and_nothing_else() {
        let names = ["PATH", "CLAUDE_CODE_RESUME_INTERRUPTED_TURN", "CLAUDE_CODE_RESUME_REASON",
                     "CLAUDE_CODE_ENTRYPOINT", "claude_code_resume_prompt"]
            .map(String::from);
        let mut found = inherited_resume_vars(names);
        found.sort();
        assert_eq!(found, vec![
            "CLAUDE_CODE_RESUME_INTERRUPTED_TURN".to_string(),
            "CLAUDE_CODE_RESUME_REASON".to_string(),
            "claude_code_resume_prompt".to_string(),
        ]);
    }

    /// A transcript of the given lines, last written `age` ago.
    fn transcript_of(dir: &Path, lines: &[&str], age: Duration) -> std::path::PathBuf {
        let p = dir.join(format!("{ID}.jsonl"));
        fs::write(&p, lines.join("\n") + "\n").unwrap();
        let f = fs::File::options().write(true).open(&p).unwrap();
        f.set_modified(SystemTime::now() - age).unwrap();
        p
    }

    const PROMPT: &str = r#"{"type":"user","message":{"role":"user","content":"Run ping"}}"#;
    const TOOL_CALL: &str = r#"{"type":"assistant","message":{"role":"assistant","stop_reason":"tool_use","content":[{"type":"tool_use","id":"t1","name":"Bash","input":{"command":"ping"}}]}}"#;
    const TOOL_RESULT: &str = r#"{"type":"user","message":{"role":"user","content":[{"type":"tool_result","tool_use_id":"t1","content":"ok"}]}}"#;
    const REPLY_DONE: &str = r#"{"type":"assistant","message":{"role":"assistant","stop_reason":"end_turn","content":[{"type":"text","text":"FINISHED"}]}}"#;
    const STOPPED: &str = r#"{"type":"user","message":{"role":"user","content":[{"type":"text","text":"[Request interrupted by user for tool use]"}]}}"#;
    const META: &str = r#"{"type":"user","isMeta":true,"message":{"role":"user","content":"Caveat: local command"}}"#;
    const TITLE: &str = r#"{"type":"custom-title","customTitle":"Ping"}"#;

    #[test]
    fn a_tool_call_with_no_result_is_cut_off() {
        let tmp = tempfile::tempdir().unwrap();
        assert!(tail_unfinished(&transcript_of(tmp.path(), &[PROMPT, TOOL_CALL], Duration::ZERO)));
    }

    #[test]
    fn a_prompt_with_no_reply_yet_is_cut_off() {
        let tmp = tempfile::tempdir().unwrap();
        assert!(tail_unfinished(&transcript_of(tmp.path(), &[PROMPT], Duration::ZERO)));
        assert!(tail_unfinished(&transcript_of(tmp.path(), &[PROMPT, TOOL_CALL, TOOL_RESULT], Duration::ZERO)),
                "a tool's result with nothing from Claude after it");
    }

    #[test]
    fn a_turn_claude_finished_is_not_cut_off() {
        let tmp = tempfile::tempdir().unwrap();
        assert!(!tail_unfinished(&transcript_of(tmp.path(), &[PROMPT, TOOL_CALL, TOOL_RESULT, REPLY_DONE], Duration::ZERO)));
    }

    #[test]
    fn a_turn_the_user_stopped_is_not_cut_off() {
        let tmp = tempfile::tempdir().unwrap();
        assert!(!tail_unfinished(&transcript_of(tmp.path(), &[PROMPT, TOOL_CALL, TOOL_RESULT, STOPPED], Duration::ZERO)));
    }

    #[test]
    fn bookkeeping_after_the_last_message_is_looked_past() {
        let tmp = tempfile::tempdir().unwrap();
        assert!(!tail_unfinished(&transcript_of(tmp.path(), &[PROMPT, REPLY_DONE, META, TITLE], Duration::ZERO)));
        assert!(tail_unfinished(&transcript_of(tmp.path(), &[PROMPT, TOOL_CALL, META, TITLE, "not json"], Duration::ZERO)));
    }

    #[test]
    fn a_compaction_or_a_local_command_after_the_last_reply_is_looked_past() {
        // As the CLI writes a /compact after a finished turn (seen 2026-10-27).
        let summary = r#"{"type":"user","isVisibleInTranscriptOnly":true,"isCompactSummary":true,"message":{"role":"user","content":"This session is being continued from a previous conversation"}}"#;
        let command = r#"{"type":"user","message":{"role":"user","content":"<command-name>/compact</command-name>\n<command-message>compact</command-message>"}}"#;
        let output = r#"{"type":"user","message":{"role":"user","content":"<local-command-stdout>Compacted </local-command-stdout>"}}"#;
        let tmp = tempfile::tempdir().unwrap();
        assert!(!tail_unfinished(&transcript_of(tmp.path(), &[PROMPT, REPLY_DONE, summary, command, output], Duration::ZERO)));
        assert!(tail_unfinished(&transcript_of(tmp.path(), &[PROMPT, TOOL_CALL, command, output], Duration::ZERO)),
                "a cut-off turn is still cut off with a local command after it");
    }

    #[test]
    fn no_transcript_or_an_empty_one_is_not_cut_off() {
        let tmp = tempfile::tempdir().unwrap();
        assert!(!tail_unfinished(&tmp.path().join("missing.jsonl")));
        assert!(!tail_unfinished(&transcript_of(tmp.path(), &[TITLE], Duration::ZERO)));
    }

    #[test]
    fn only_the_end_of_a_long_transcript_is_read() {
        let tmp = tempfile::tempdir().unwrap();
        let filler = r#"{"type":"assistant","message":{"role":"assistant","stop_reason":"end_turn","content":[{"type":"text","text":"x"}]}}"#;
        let mut lines: Vec<&str> = vec![filler; 20_000];
        lines.push(PROMPT);
        lines.push(TOOL_CALL);
        assert!(tail_unfinished(&transcript_of(tmp.path(), &lines, Duration::ZERO)));
    }

    #[test]
    fn a_candidate_is_recent_unheld_and_cut_off() {
        let tmp = tempfile::tempdir().unwrap();
        let sessions = tmp.path().join("sessions");
        let cut = transcript_of(tmp.path(), &[PROMPT, TOOL_CALL], Duration::from_secs(60));
        assert!(candidate(&cut, Some(&sessions), ID, SystemTime::now(), &|_| false));

        let done = transcript_of(tmp.path(), &[PROMPT, REPLY_DONE], Duration::from_secs(60));
        assert!(!candidate(&done, Some(&sessions), ID, SystemTime::now(), &|_| false), "finished");

        let old = transcript_of(tmp.path(), &[PROMPT, TOOL_CALL], Duration::from_secs(2 * 60 * 60));
        assert!(!candidate(&old, Some(&sessions), ID, SystemTime::now(), &|_| false), "too old");

        let held = transcript_of(tmp.path(), &[PROMPT, TOOL_CALL], Duration::from_secs(60));
        register(&sessions, 99, ID);
        assert!(!candidate(&held, Some(&sessions), ID, SystemTime::now(), &|pid| pid == 99), "held elsewhere");
    }

    #[test]
    fn this_process_is_alive_and_one_that_has_ended_is_not() {
        assert!(pid_alive(std::process::id()));
        #[cfg(windows)]
        let mut child = std::process::Command::new("cmd").args(["/C", "exit"]).spawn().unwrap();
        #[cfg(not(windows))]
        let mut child = std::process::Command::new("true").spawn().unwrap();
        let pid = child.id();
        child.wait().unwrap();
        drop(child);   // the last handle to it goes, so nothing keeps it around
        assert!(!pid_alive(pid));
    }
}
