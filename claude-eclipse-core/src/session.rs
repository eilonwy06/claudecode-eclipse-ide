use std::fs;
use std::io::{BufRead, BufReader};
use std::path::PathBuf;

/// Compute the Claude CLI project hash for a workspace path.
///
/// The algorithm mirrors what Claude CLI uses:
///   `C:\Users\Foo\Project` → `C--Users-Foo-Project`
///   `:` becomes `-`, `\` becomes `-`, `/` becomes `-`.
fn workspace_hash(workspace_root: &str) -> String {
    workspace_root
        .replace(':', "-")
        .replace('\\', "-")
        .replace('/', "-")
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

        let mut display = String::new();
        let mut timestamp = String::new();
        let mut found_user = false;

        for line in reader.lines() {
            let line = match line {
                Ok(l) if !l.is_empty() => l,
                _ => continue,
            };
            let event: serde_json::Value = match serde_json::from_str(&line) {
                Ok(v) => v,
                Err(_) => continue,
            };

            if event["type"].as_str() == Some("user") && !found_user {
                // Extract display text — first 120 chars of the user message content.
                if let Some(content) = event["message"]["content"].as_str() {
                    display = content.chars().take(120).collect();
                }
                if let Some(ts) = event["timestamp"].as_str() {
                    timestamp = ts.to_string();
                }
                found_user = true;
                break;
            }
        }

        if !found_user {
            continue; // Skip sessions with no user messages.
        }

        sessions.push(serde_json::json!({
            "sessionId": session_id,
            "display": display,
            "timestamp": timestamp,
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
                    messages.push(serde_json::json!({
                        "role": "assistant",
                        "content": text,
                        "timestamp": ts,
                    }));
                }
            }
            _ => {}
        }
    }

    serde_json::to_string(&messages).unwrap_or_else(|_| "[]".into())
}
