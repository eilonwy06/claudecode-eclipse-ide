use std::collections::HashSet;
use std::fs;
use std::io::{BufRead, BufReader, Write};
use std::path::PathBuf;
use std::process::{Command, Stdio};

#[cfg(windows)]
use std::os::windows::process::CommandExt;

/// Compute the Claude CLI project hash for a workspace path.
///
/// The algorithm mirrors what Claude CLI uses: every character that is not
/// ASCII alphanumeric becomes `-` (so `:`, `\`, `/`, spaces, dots, etc. all map
/// to `-`). Example:
///   `C:\Users\Windows 10\Project` → `C--Users-Windows-10-Project`
/// Replacing only `:\/` (the previous behaviour) broke any path containing a
/// space — e.g. the "Windows 10" home folder — so no sessions were ever found.
fn workspace_hash(workspace_root: &str) -> String {
    workspace_root
        .chars()
        .map(|c| if c.is_ascii_alphanumeric() { c } else { '-' })
        .collect()
}

/// Strip the editor-context preamble AND Claude Code command/meta wrappers so
/// session titles aren't raw `<ide_selection>` / `<command-name>` /
/// `<local-command-*>` tags. Removes ALL occurrences, not just a leading one,
/// and unwraps `<command-name>X</command-name>` to `X`. No regex crate needed —
/// simple scans.
fn strip_ide_preamble(s: &str) -> String {
    let mut t = remove_tag_block(s, "ide_selection");
    t = remove_self_closing_tag(&t, "ide_context");
    t = remove_tag_block(&t, "local-command-caveat");
    t = remove_tag_block(&t, "command-message");
    t = remove_tag_block(&t, "command-args");
    t = remove_tag_block(&t, "local-command-stdout");
    t = unwrap_tag_block(&t, "command-name");
    t.trim_start().to_string()
}

/// Finds the next `<tag ...>` opening (word-boundary after the tag name, like
/// `\b` in a regex) at or after byte `from`. Returns (start, end-of-open-tag)
/// byte offsets, the end being one past the closing `>`.
fn find_open_tag(s: &str, tag: &str, from: usize) -> Option<(usize, usize)> {
    let pat = format!("<{}", tag);
    let mut i = from;
    while let Some(rel) = s[i..].find(&pat) {
        let start = i + rel;
        let after = start + pat.len();
        let boundary = s[after..]
            .chars()
            .next()
            .map_or(false, |c| c == '>' || c == '/' || c.is_whitespace());
        if boundary {
            if let Some(gt) = s[after..].find('>') {
                return Some((start, after + gt + 1));
            }
            return None; // unterminated open tag — nothing to strip
        }
        i = after;
    }
    None
}

/// Removes every `<tag ...>inner</tag>` block, inner included. A block missing
/// its closing tag is left untouched.
fn remove_tag_block(s: &str, tag: &str) -> String {
    let close = format!("</{}>", tag);
    let mut out = String::with_capacity(s.len());
    let mut i = 0;
    while let Some((start, open_end)) = find_open_tag(s, tag, i) {
        match s[open_end..].find(&close) {
            Some(rel) => {
                out.push_str(&s[i..start]);
                i = open_end + rel + close.len();
            }
            None => break,
        }
    }
    out.push_str(&s[i..]);
    out
}

/// Removes every self-closing `<tag ... />` occurrence.
fn remove_self_closing_tag(s: &str, tag: &str) -> String {
    let mut out = String::with_capacity(s.len());
    let mut i = 0;
    while let Some((start, open_end)) = find_open_tag(s, tag, i) {
        if s[..open_end].ends_with("/>") {
            out.push_str(&s[i..start]);
        } else {
            out.push_str(&s[i..open_end]);
        }
        i = open_end;
    }
    out.push_str(&s[i..]);
    out
}

/// Replaces every `<tag>inner</tag>` with just `inner`.
fn unwrap_tag_block(s: &str, tag: &str) -> String {
    let close = format!("</{}>", tag);
    let mut out = String::with_capacity(s.len());
    let mut i = 0;
    while let Some((start, open_end)) = find_open_tag(s, tag, i) {
        match s[open_end..].find(&close) {
            Some(rel) => {
                out.push_str(&s[i..start]);
                out.push_str(&s[open_end..open_end + rel]);
                i = open_end + rel + close.len();
            }
            None => break,
        }
    }
    out.push_str(&s[i..]);
    out
}

/// Returns the path to `~/.claude/projects/{hash}/`.
fn projects_dir(workspace_root: &str) -> Option<PathBuf> {
    let home = dirs_home()?;
    let hash = workspace_hash(workspace_root);
    let dir = home.join(".claude").join("projects").join(hash);
    if dir.is_dir() {
        Some(dir)
    } else {
        None
    }
}

/// Formats a Unix epoch-seconds value as an ISO-8601 UTC string
/// (`YYYY-MM-DDTHH:MM:SSZ`) using the civil-from-days algorithm (Howard Hinnant's,
/// public domain) — no external crate. Only used as a sort-key fallback for the rare
/// title-only session stubs whose events carry no timestamp, so it string-sorts
/// interleaved with the real ISO timestamps from normal sessions.
fn epoch_to_iso8601(secs: u64) -> String {
    let days = (secs / 86_400) as i64;
    let rem = secs % 86_400;
    let (hh, mm, ss) = (rem / 3600, (rem % 3600) / 60, rem % 60);
    // Days since 1970-01-01 → civil (year, month, day).
    let z = days + 719_468;
    let era = if z >= 0 { z } else { z - 146_096 } / 146_097;
    let doe = (z - era * 146_097) as i64; // [0, 146096]
    let yoe = (doe - doe / 1460 + doe / 36_524 - doe / 146_096) / 365; // [0, 399]
    let y = yoe + era * 400;
    let doy = doe - (365 * yoe + yoe / 4 - yoe / 100); // [0, 365]
    let mp = (5 * doy + 2) / 153; // [0, 11]
    let d = doy - (153 * mp + 2) / 5 + 1; // [1, 31]
    let m = if mp < 10 { mp + 3 } else { mp - 9 }; // [1, 12]
    let year = if m <= 2 { y + 1 } else { y };
    format!(
        "{:04}-{:02}-{:02}T{:02}:{:02}:{:02}Z",
        year, m, d, hh, mm, ss
    )
}

/// Platform-agnostic home directory lookup.
fn dirs_home() -> Option<PathBuf> {
    #[cfg(windows)]
    {
        std::env::var("USERPROFILE").ok().map(PathBuf::from)
    }
    #[cfg(not(windows))]
    {
        std::env::var("HOME").ok().map(PathBuf::from)
    }
}

// ---------------------------------------------------------------------------
// list_sessions  — scan *.jsonl files, extract first user message + timestamp
// ---------------------------------------------------------------------------

pub fn list_sessions(workspace_root: &str) -> String {
    let dir = match projects_dir(workspace_root) {
        Some(d) => d,
        None => return "[]".into(),
    };

    let mut sessions: Vec<serde_json::Value> = Vec::new();

    let entries: Vec<_> = match fs::read_dir(&dir) {
        Ok(rd) => rd.filter_map(|e| e.ok()).collect(),
        Err(_) => return "[]".into(),
    };

    for entry in entries {
        let path = entry.path();
        if path.extension().and_then(|e| e.to_str()) != Some("jsonl") {
            continue;
        }

        let session_id = match path.file_stem().and_then(|s| s.to_str()) {
            Some(s) => s.to_string(),
            None => continue,
        };

        // Read just enough of the file to find the first user message and timestamp.
        let file = match fs::File::open(&path) {
            Ok(f) => f,
            Err(_) => continue,
        };
        let reader = BufReader::new(file);

        // The list title mirrors the CLI's /resume: the user's rename ("custom-title"
        // event, written by the rename_session control request — LAST one wins) beats
        // the AI-generated "ai-title", which beats the first user message. Neither
        // title event carries a timestamp. The legacy Eclipse-only rename sidecar
        // (session-titles.json, applied Java-side) still overrides all of these.
        // Sort key is the LAST activity timestamp (newest event scanned), matching
        // /resume's most-recently-used ordering.
        let mut custom_title = String::new();
        let mut ai_title = String::new();
        let mut first_user = String::new();
        let mut last_ts = String::new();
        let mut saw_line = false;

        for line in reader.lines() {
            let line = match line {
                Ok(l) if !l.is_empty() => l,
                _ => continue,
            };
            let event: serde_json::Value = match serde_json::from_str(&line) {
                Ok(v) => v,
                Err(_) => continue,
            };
            saw_line = true;

            if let Some(ts) = event["timestamp"].as_str() {
                last_ts = ts.to_string();
            }
            match event["type"].as_str() {
                Some("custom-title") => {
                    if let Some(ct) = event["customTitle"].as_str() {
                        if !ct.is_empty() {
                            custom_title = ct.chars().take(120).collect();
                        }
                    }
                }
                Some("ai-title") => {
                    if let Some(at) = event["aiTitle"].as_str() {
                        if !at.is_empty() {
                            ai_title = at.chars().take(120).collect();
                        }
                    }
                }
                Some("user") if first_user.is_empty() => {
                    // Extract display text — first 120 chars of the user message content,
                    // with any injected <ide_selection>/<ide_context> preamble removed so
                    // the fallback title is the user's actual text, not the editor context.
                    if let Some(content) = event["message"]["content"].as_str() {
                        first_user = strip_ide_preamble(content).chars().take(120).collect();
                    }
                }
                _ => {}
            }
        }

        // Include a session with any recognizable title source: a custom-title
        // (user rename), an ai-title (covers title-only stubs that /resume lists)
        // or a first user message.
        let display = if !custom_title.is_empty() {
            custom_title
        } else if !ai_title.is_empty() {
            ai_title
        } else {
            first_user
        };
        if !saw_line || display.is_empty() {
            continue;
        }
        // Fall back to file mtime when no event carried a timestamp (e.g. stubs).
        // Format as an ISO-8601 UTC string so it string-sorts interleaved with the
        // real event timestamps (the PHP reader does the same via gmdate()).
        if last_ts.is_empty() {
            if let Ok(meta) = fs::metadata(&path) {
                if let Ok(modified) = meta.modified() {
                    if let Ok(dur) = modified.duration_since(std::time::UNIX_EPOCH) {
                        last_ts = epoch_to_iso8601(dur.as_secs());
                    }
                }
            }
        }

        sessions.push(serde_json::json!({
            "sessionId": session_id,
            "display": display,
            "timestamp": last_ts,
        }));
    }

    // Sort by timestamp descending (newest first).
    sessions.sort_by(|a, b| {
        let ta = a["timestamp"].as_str().unwrap_or("");
        let tb = b["timestamp"].as_str().unwrap_or("");
        tb.cmp(ta)
    });

    // Limit to 100 most recent sessions.
    sessions.truncate(100);

    serde_json::to_string(&sessions).unwrap_or_else(|_| "[]".into())
}

// ---------------------------------------------------------------------------
// load_session_history — read a specific session's JSONL and return the
// conversation as an ordered list of render items so the GUI can reconstruct
// EXACTLY how the live session looked:
//   {t:user, content}      - user message (raw; GUI parses the ide_selection chip)
//   {t:thinking}           - a thinking block (shown as "Thinking", no duration)
//   {t:tool, name, input}  - a tool call (Read/Edit/Search/Asking... + inline diff)
//   {t:answered, text}     - the user's answer to an askUserQuestion card
//   {t:text, text}         - assistant prose
// Each assistant item carries the model that turn ran on so the GUI can resume
// the conversation with its last-used model and show it in the status bar.
// ---------------------------------------------------------------------------

pub fn load_session_history(workspace_root: &str, session_id: &str) -> String {
    let dir = match projects_dir(workspace_root) {
        Some(d) => d,
        None => return "[]".into(),
    };
    if session_id.is_empty() {
        return "[]".into();
    }

    let path = dir.join(format!("{}.jsonl", session_id));
    let file = match fs::File::open(&path) {
        Ok(f) => f,
        Err(_) => return "[]".into(),
    };
    let reader = BufReader::new(file);

    let mut items: Vec<serde_json::Value> = Vec::new();
    // tool_use ids of askUserQuestion calls, so their answers can be surfaced.
    let mut ask_ids: HashSet<String> = HashSet::new();

    for line in reader.lines() {
        let line = match line {
            Ok(l) if !l.is_empty() => l,
            _ => continue,
        };
        let event: serde_json::Value = match serde_json::from_str(&line) {
            Ok(v) => v,
            Err(_) => continue,
        };

        match event["type"].as_str() {
            Some("user") => {
                let content = &event["message"]["content"];
                if let Some(c) = content.as_str() {
                    items.push(serde_json::json!({ "t": "user", "content": c }));
                } else if let Some(blocks) = content.as_array() {
                    for b in blocks {
                        if b["type"].as_str() != Some("tool_result") {
                            continue;
                        }
                        let tuid = b["tool_use_id"].as_str().unwrap_or("");
                        if !ask_ids.contains(tuid) {
                            continue;
                        }
                        let mut rc = String::new();
                        if let Some(s) = b["content"].as_str() {
                            rc.push_str(s);
                        } else if let Some(parts) = b["content"].as_array() {
                            for rb in parts {
                                if rb["type"].as_str() == Some("text") {
                                    rc.push_str(rb["text"].as_str().unwrap_or(""));
                                }
                            }
                        }
                        let rc = strip_answer_prefix(&rc);
                        if !rc.is_empty() {
                            items.push(serde_json::json!({ "t": "answered", "text": rc }));
                        }
                    }
                }
            }
            Some("assistant") => {
                // Only include non-partial (final) assistant messages.
                if event.get("partial").and_then(|v| v.as_bool()).unwrap_or(false) {
                    continue;
                }
                let content = match event["message"]["content"].as_array() {
                    Some(c) => c,
                    None => continue,
                };
                // The model this turn ran on — attached to each item.
                let model = event["message"]["model"].as_str().unwrap_or("");
                for b in content {
                    match b["type"].as_str() {
                        Some("thinking") => {
                            let tt = b["thinking"].as_str().unwrap_or("");
                            items.push(serde_json::json!({
                                "t": "thinking", "model": model, "text": tt,
                            }));
                        }
                        Some("text") => {
                            if let Some(t) = b["text"].as_str() {
                                if !t.is_empty() {
                                    items.push(serde_json::json!({
                                        "t": "text", "text": t, "model": model,
                                    }));
                                }
                            }
                        }
                        Some("tool_use") => {
                            let name = b["name"].as_str().unwrap_or("tool");
                            let input = if b["input"].is_null() {
                                serde_json::json!({})
                            } else {
                                b["input"].clone()
                            };
                            items.push(serde_json::json!({
                                "t": "tool", "name": name, "input": input, "model": model,
                            }));
                            if name.to_ascii_lowercase().contains("askuserquestion") {
                                if let Some(id) = b["id"].as_str() {
                                    ask_ids.insert(id.to_string());
                                }
                            }
                        }
                        _ => {}
                    }
                }
            }
            _ => {}
        }
    }

    serde_json::to_string(&items).unwrap_or_else(|_| "[]".into())
}

/// Drops a leading "The user answered: " (any case, any leading whitespace)
/// from an askUserQuestion tool_result, leaving just the chosen answer.
fn strip_answer_prefix(s: &str) -> String {
    const PREFIX: &str = "the user answered:";
    let t = s.trim_start();
    let matched = t
        .get(..PREFIX.len())
        .map_or(false, |p| p.eq_ignore_ascii_case(PREFIX));
    if matched {
        t[PREFIX.len()..].trim_start().to_string()
    } else {
        s.to_string()
    }
}

// ---------------------------------------------------------------------------
// delete_session — remove one local session file
// ---------------------------------------------------------------------------

/// Deletes `~/.claude/projects/<hash>/<sessionId>.jsonl`. The id is rejected if
/// it could escape the projects directory (path separators or "..").
pub fn delete_session(workspace_root: &str, session_id: &str) -> bool {
    if session_id.is_empty()
        || session_id.contains('/')
        || session_id.contains('\\')
        || session_id.contains("..")
    {
        return false;
    }
    let dir = match projects_dir(workspace_root) {
        Some(d) => d,
        None => return false,
    };
    let path = dir.join(format!("{}.jsonl", session_id));
    path.is_file() && fs::remove_file(&path).is_ok()
}

// ---------------------------------------------------------------------------
// rename_session_offline — rename a session that has NO live process, by
// resuming it headless and sending the CLI's rename_session control request.
// ---------------------------------------------------------------------------

/// Renames an inactive session the CLI-native way (verified on claude 2.1.177):
/// spawn `claude -p --resume <id> --input-format stream-json --output-format
/// stream-json --verbose`, write one `rename_session` control request, close
/// stdin. The CLI appends a `custom-title` event to the ORIGINAL session jsonl
/// (no fork), runs zero model turns (zero cost) and exits on its own. Success =
/// the CLI's control_response for our request id. Blocks up to ~15s; callers
/// run it off the UI thread.
pub fn rename_session_offline(
    claude_cmd: &str,
    workspace_root: &str,
    session_id: &str,
    title: &str,
) -> bool {
    if claude_cmd.is_empty() || session_id.is_empty() || title.is_empty() {
        return false;
    }

    // crate::launch handles Windows PATH/PATHEXT resolution (bare `claude`
    // → `claude.cmd`) and `.cmd` shim quoting, same as the chat spawn paths.
    let args: Vec<String> = [
        "-p",
        "--resume",
        session_id,
        "--input-format",
        "stream-json",
        "--output-format",
        "stream-json",
        "--verbose",
    ]
    .iter()
    .map(|s| s.to_string())
    .collect();
    let mut cmd = crate::launch::claude_command(claude_cmd, &args);
    cmd.current_dir(workspace_root)
        .stdin(Stdio::piped())
        .stdout(Stdio::piped())
        .stderr(Stdio::null());

    #[cfg(windows)]
    cmd.creation_flags(0x08000000); // CREATE_NO_WINDOW

    for (k, v) in crate::shell_env::captured_env().to_inject() {
        cmd.env(k, v);
    }

    let mut child = match cmd.spawn() {
        Ok(c) => c,
        Err(_) => return false,
    };

    let req_id = "eclipse-ren-offline";
    let request = serde_json::json!({
        "type": "control_request",
        "request_id": req_id,
        "request": { "subtype": "rename_session", "title": title }
    });

    // Write the request, then drop stdin (EOF) so the CLI exits after answering.
    if let Some(mut stdin) = child.stdin.take() {
        let ok = stdin
            .write_all(request.to_string().as_bytes())
            .and_then(|_| stdin.write_all(b"\n"))
            .and_then(|_| stdin.flush())
            .is_ok();
        drop(stdin);
        if !ok {
            let _ = child.kill();
            return false;
        }
    } else {
        let _ = child.kill();
        return false;
    }

    // Watch stdout for the success control_response. The read loop alone can
    // block forever on a silent child (auth prompt, network stall), so a
    // watchdog kills the child at the deadline — that forces stdout EOF and
    // unblocks the loop.
    let stdout = match child.stdout.take() {
        Some(s) => s,
        None => {
            let _ = child.kill();
            return false;
        }
    };

    let done = std::sync::Arc::new(std::sync::atomic::AtomicBool::new(false));
    let watchdog = {
        let done = std::sync::Arc::clone(&done);
        // Killing via the child handle needs ownership; signal the watchdog to
        // kill by pid instead so the main thread keeps `child` for reaping.
        let pid = child.id();
        std::thread::spawn(move || {
            for _ in 0..150 {
                if done.load(std::sync::atomic::Ordering::Relaxed) {
                    return;
                }
                std::thread::sleep(std::time::Duration::from_millis(100));
            }
            kill_pid(pid);
        })
    };

    let mut renamed = false;
    let reader = BufReader::new(stdout);
    for line in reader.lines() {
        let line = match line {
            Ok(l) => l,
            Err(_) => break,
        };
        let event: serde_json::Value = match serde_json::from_str(&line) {
            Ok(v) => v,
            Err(_) => continue,
        };
        if event["type"].as_str() == Some("control_response")
            && event["response"]["request_id"].as_str() == Some(req_id)
            && event["response"]["subtype"].as_str() == Some("success")
        {
            renamed = true;
            break;
        }
    }
    done.store(true, std::sync::atomic::Ordering::Relaxed);

    // Reap (or put down) the child either way; the rename outcome is decided.
    let _ = child.kill();
    let _ = child.wait();
    let _ = watchdog.join();
    renamed
}

/// Best-effort kill by pid, used only by the offline-rename watchdog.
#[cfg(windows)]
fn kill_pid(pid: u32) {
    let _ = Command::new("taskkill")
        .args(["/PID", &pid.to_string(), "/T", "/F"])
        .creation_flags(0x08000000) // CREATE_NO_WINDOW
        .stdout(Stdio::null())
        .stderr(Stdio::null())
        .status();
}

/// Best-effort kill by pid, used only by the offline-rename watchdog.
#[cfg(target_os = "macos")]
fn kill_pid(pid: u32) {
    let _ = Command::new("kill")
        .args(["-KILL", &pid.to_string()])
        .stdout(Stdio::null())
        .stderr(Stdio::null())
        .status();
}

/// Best-effort kill by pid, used only by the offline-rename watchdog.
#[cfg(target_os = "linux")]
fn kill_pid(pid: u32) {
    let _ = Command::new("kill")
        .args(["-KILL", &pid.to_string()])
        .stdout(Stdio::null())
        .stderr(Stdio::null())
        .status();
}

#[cfg(test)]
mod tests {
    use std::fs;
    use std::sync::Mutex;

    /// The tests below all repoint the home env var (USERPROFILE / HOME) at a
    /// per-test fake home; serialize them so parallel test threads don't clobber
    /// each other's environment.
    static ENV_LOCK: Mutex<()> = Mutex::new(());

    fn set_home(home: &std::path::Path) {
        #[cfg(windows)]
        std::env::set_var("USERPROFILE", home);
        #[cfg(not(windows))]
        std::env::set_var("HOME", home);
    }

    /// Hermetic fixture (same one the PHP reader was verified against): builds a
    /// fake home + ~/.claude/projects/<hash>/ under a temp dir and points the
    /// home env var at it, so the test runs anywhere. Asserts: custom-title beats
    /// ai-title (LAST custom-title wins), ai-title-only stubs are listed (with an
    /// mtime-derived sort key), untitled sessions fall back to the stripped first
    /// user message, and ordering is last-activity descending.
    #[test]
    fn list_sessions_title_precedence_matches_php_reader() {
        let _env = ENV_LOCK.lock().unwrap();
        let home = std::env::temp_dir().join("claude-eclipse-session-test-home");
        let root = r"C:\histtest";
        let dir = home.join(".claude").join("projects").join("C--histtest");
        let _ = fs::remove_dir_all(&home);
        fs::create_dir_all(&dir).unwrap();

        fs::write(dir.join("aaaa1111.jsonl"), concat!(
            r#"{"type":"user","message":{"role":"user","content":"first user words here"},"timestamp":"2026-07-01T10:00:00.000Z"}"#, "\n",
            r#"{"type":"ai-title","aiTitle":"AI generated title","sessionId":"aaaa1111"}"#, "\n",
            r#"{"type":"assistant","message":{"content":[{"type":"text","text":"hi"}]},"timestamp":"2026-07-01T10:00:05.000Z"}"#, "\n",
            r#"{"type":"custom-title","customTitle":"Old rename","sessionId":"aaaa1111"}"#, "\n",
            r#"{"type":"custom-title","customTitle":"USER RENAMED TITLE","sessionId":"aaaa1111"}"#, "\n",
        )).unwrap();
        fs::write(dir.join("bbbb2222.jsonl"), concat!(
            r#"{"type":"ai-title","aiTitle":"title-only stub","sessionId":"bbbb2222"}"#, "\n",
            r#"{"type":"agent-name","agentName":"title-only stub","sessionId":"bbbb2222"}"#, "\n",
        )).unwrap();
        fs::write(dir.join("cccc3333.jsonl"), concat!(
            r#"{"type":"user","message":{"role":"user","content":"<ide_selection a=\"b\">junk</ide_selection>real question text"},"timestamp":"2026-07-02T09:00:00.000Z"}"#, "\n",
            r#"{"type":"assistant","message":{"content":[{"type":"text","text":"answer"}]},"timestamp":"2026-07-02T09:00:04.000Z"}"#, "\n",
        )).unwrap();

        set_home(&home);

        let json = super::list_sessions(root);
        let _ = fs::remove_dir_all(&home);

        let v: serde_json::Value = serde_json::from_str(&json).unwrap();
        let arr = v.as_array().unwrap();
        assert_eq!(arr.len(), 3, "all three fixture sessions listed: {json}");
        let displays: Vec<&str> = arr.iter().map(|s| s["display"].as_str().unwrap()).collect();
        // bbbb2222 has no event timestamps → sort key is its (fresh) mtime → newest.
        assert_eq!(
            displays,
            vec!["title-only stub", "real question text", "USER RENAMED TITLE"],
            "titles + last-activity order must match the PHP reader"
        );
    }

    /// Verified against the reference reader on 2026-07-10: the fixture below was
    /// fed to it and the expected JSON here is its captured output, byte-for-byte
    /// (compared as Values since key order differs). Covers: raw user content
    /// (ide_selection kept), partial assistant skipped, thinking/text/tool_use
    /// items with per-turn model, askUserQuestion answer surfacing with "The user
    /// answered:" prefix stripping, empty text blocks dropped, and non-ask
    /// tool_results ignored.
    #[test]
    fn load_session_render_items_match_php_reader() {
        let _env = ENV_LOCK.lock().unwrap();
        let home = std::env::temp_dir().join("claude-eclipse-session-load-home");
        let root = r"C:\phpfixws";
        let dir = home.join(".claude").join("projects").join("C--phpfixws");
        let _ = fs::remove_dir_all(&home);
        fs::create_dir_all(&dir).unwrap();

        fs::write(dir.join("sess1.jsonl"), concat!(
            r#"{"type":"user","message":{"role":"user","content":"<ide_selection a=\"b\">sel junk</ide_selection>please fix the bug"},"timestamp":"2026-07-01T10:00:00.000Z"}"#, "\n",
            r#"{"type":"assistant","partial":true,"message":{"model":"claude-fable-5","content":[{"type":"text","text":"par"}]},"timestamp":"2026-07-01T10:00:01.000Z"}"#, "\n",
            r#"{"type":"assistant","message":{"model":"claude-fable-5","content":[{"type":"thinking","thinking":"hmm secret"},{"type":"text","text":"Here is my answer"},{"type":"tool_use","id":"toolu_01","name":"mcp__eclipse__askUserQuestion","input":{"q":"Which color?"}}]},"timestamp":"2026-07-01T10:00:05.000Z"}"#, "\n",
            r#"{"type":"user","message":{"role":"user","content":[{"type":"tool_result","tool_use_id":"toolu_01","content":[{"type":"text","text":"  The user answered: Blue"}]}]},"timestamp":"2026-07-01T10:00:09.000Z"}"#, "\n",
            r#"{"type":"assistant","message":{"model":"claude-opus-4-8","content":[{"type":"tool_use","id":"toolu_02","name":"Edit","input":{"file_path":"C:\\x.java","old_string":"a","new_string":"b"}},{"type":"text","text":""}]},"timestamp":"2026-07-01T10:00:12.000Z"}"#, "\n",
            r#"{"type":"custom-title","customTitle":"My renamed session","sessionId":"sess1"}"#, "\n",
        )).unwrap();
        fs::write(dir.join("sess2.jsonl"), concat!(
            r#"{"type":"user","message":{"role":"user","content":"<command-name>/clear</command-name><command-message>clear</command-message><command-args>now</command-args>"},"timestamp":"2026-07-03T08:00:00.000Z"}"#, "\n",
            r#"{"type":"user","message":{"role":"user","content":[{"type":"tool_result","tool_use_id":"toolu_99","content":"unrelated result"}]},"timestamp":"2026-07-03T08:00:02.000Z"}"#, "\n",
        )).unwrap();

        set_home(&home);

        let loaded1 = super::load_session_history(root, "sess1");
        let loaded2 = super::load_session_history(root, "sess2");
        let listed = super::list_sessions(root);
        let _ = fs::remove_dir_all(&home);

        let got1: serde_json::Value = serde_json::from_str(&loaded1).unwrap();
        let want1: serde_json::Value = serde_json::from_str(r#"[
            {"t":"user","content":"<ide_selection a=\"b\">sel junk</ide_selection>please fix the bug"},
            {"t":"thinking","model":"claude-fable-5","text":"hmm secret"},
            {"t":"text","text":"Here is my answer","model":"claude-fable-5"},
            {"t":"tool","name":"mcp__eclipse__askUserQuestion","input":{"q":"Which color?"},"model":"claude-fable-5"},
            {"t":"answered","text":"Blue"},
            {"t":"tool","name":"Edit","input":{"file_path":"C:\\x.java","old_string":"a","new_string":"b"},"model":"claude-opus-4-8"}
        ]"#).unwrap();
        assert_eq!(got1, want1, "sess1 render items must match the reference reader");

        let got2: serde_json::Value = serde_json::from_str(&loaded2).unwrap();
        let want2: serde_json::Value = serde_json::from_str(r#"[
            {"t":"user","content":"<command-name>/clear</command-name><command-message>clear</command-message><command-args>now</command-args>"}
        ]"#).unwrap();
        assert_eq!(got2, want2, "sess2: raw user kept, non-ask tool_result ignored");

        // List titles: sess2's fallback title is the fully-unwrapped command text.
        let lv: serde_json::Value = serde_json::from_str(&listed).unwrap();
        let sess2 = lv.as_array().unwrap().iter()
            .find(|s| s["sessionId"] == "sess2").expect("sess2 listed");
        assert_eq!(sess2["display"], "/clear", "command wrappers stripped from title");
        assert_eq!(sess2["timestamp"], "2026-07-03T08:00:02.000Z");
    }

    #[test]
    fn delete_session_guards_and_removes() {
        let _env = ENV_LOCK.lock().unwrap();
        let home = std::env::temp_dir().join("claude-eclipse-session-del-home");
        let root = r"C:\deltest";
        let dir = home.join(".claude").join("projects").join("C--deltest");
        let _ = fs::remove_dir_all(&home);
        fs::create_dir_all(&dir).unwrap();
        fs::write(dir.join("victim.jsonl"), "{}\n").unwrap();

        set_home(&home);

        assert!(!super::delete_session(root, ""), "empty id rejected");
        assert!(!super::delete_session(root, "../victim"), "traversal rejected");
        assert!(!super::delete_session(root, "a\\b"), "separator rejected");
        assert!(!super::delete_session(root, "missing"), "absent file is false");
        assert!(super::delete_session(root, "victim"), "existing file deleted");
        assert!(!dir.join("victim.jsonl").exists());

        let _ = fs::remove_dir_all(&home);
    }
}
