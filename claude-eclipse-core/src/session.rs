use std::fs;
use std::io::{BufRead, BufReader};
use std::path::PathBuf;

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

/// Remove a leading `<ide_selection ...>...</ide_selection>` block and/or a
/// `<ide_context ... />` tag that the GUI injects ahead of the user's message,
/// so session titles show the real text. No regex crate needed — simple scan.
fn strip_ide_preamble(s: &str) -> String {
    let mut t = s.trim_start();
    if let Some(rest) = t.strip_prefix("<ide_selection") {
        if let Some(end) = rest.find("</ide_selection>") {
            t = rest[end + "</ide_selection>".len()..].trim_start();
        }
    }
    if t.starts_with("<ide_context") {
        if let Some(end) = t.find("/>") {
            t = t[end + 2..].trim_start();
        }
    }
    t.to_string()
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

        // The list title mirrors the CLI's /resume: the AI-generated "ai-title"
        // (its event carries no timestamp) wins, falling back to the first user
        // message. The user's custom rename (session-titles.json, applied Java-side)
        // still overrides this. Sort key is the LAST activity timestamp (newest
        // event scanned), matching /resume's most-recently-used ordering.
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

        // Include a session with any recognizable title source: an ai-title (covers
        // title-only stubs that /resume lists) or a first user message.
        let display = if !ai_title.is_empty() { ai_title } else { first_user };
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
// load_session_history — read a specific session's JSONL and return messages
// ---------------------------------------------------------------------------

pub fn load_session_history(workspace_root: &str, session_id: &str) -> String {
    let dir = match projects_dir(workspace_root) {
        Some(d) => d,
        None => return "[]".into(),
    };

    let path = dir.join(format!("{}.jsonl", session_id));
    let file = match fs::File::open(&path) {
        Ok(f) => f,
        Err(_) => return "[]".into(),
    };
    let reader = BufReader::new(file);

    let mut messages: Vec<serde_json::Value> = Vec::new();

    for line in reader.lines() {
        let line = match line {
            Ok(l) if !l.is_empty() => l,
            _ => continue,
        };
        let event: serde_json::Value = match serde_json::from_str(&line) {
            Ok(v) => v,
            Err(_) => continue,
        };

        let event_type = match event["type"].as_str() {
            Some(t) => t,
            None => continue,
        };

        match event_type {
            "user" => {
                let content = event["message"]["content"].as_str().unwrap_or("").to_string();
                let ts = event["timestamp"].as_str().unwrap_or("").to_string();
                messages.push(serde_json::json!({
                    "role": "user",
                    "content": content,
                    "timestamp": ts,
                }));
            }
            "assistant" => {
                // Only include non-partial (final) assistant messages.
                let is_partial = event.get("partial").and_then(|v| v.as_bool()).unwrap_or(false);
                if is_partial {
                    continue;
                }

                // Extract text blocks only — skip tool_use, thinking, etc.
                let mut text = String::new();
                if let Some(content) = event["message"]["content"].as_array() {
                    for block in content {
                        if block["type"].as_str() == Some("text") {
                            if let Some(t) = block["text"].as_str() {
                                if !text.is_empty() {
                                    text.push('\n');
                                }
                                text.push_str(t);
                            }
                        }
                    }
                }

                if !text.is_empty() {
                    let ts = event["timestamp"].as_str().unwrap_or("").to_string();
                    // Surface the model so the GUI can resume the conversation with
                    // the model it last used (and show it in the status bar).
                    let model = event["message"]["model"].as_str().unwrap_or("");
                    messages.push(serde_json::json!({
                        "role": "assistant",
                        "content": text,
                        "timestamp": ts,
                        "model": model,
                    }));
                }
            }
            _ => {}
        }
    }

    serde_json::to_string(&messages).unwrap_or_else(|_| "[]".into())
}
