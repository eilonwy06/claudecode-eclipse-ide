use std::collections::{HashMap, HashSet};
use std::fs;
use std::io::{BufRead, BufReader, Write};
use std::path::PathBuf;
use std::process::{Command, Stdio};
use std::sync::atomic::{AtomicU64, Ordering};

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
    // Arrived with CLI 2.1.x as a text block prepended to the user's own message,
    // so without this a session title (and the delete sweep's text match) starts
    // with a paragraph about which file was open.
    t = remove_tag_block(&t, "ide_opened_file");
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
                    } else if let Some(blocks) = event["message"]["content"].as_array() {
                        // A first message sent with a pasted image is stored as
                        // content blocks — title the session from its text block
                        // instead of falling through to a later message.
                        for b in blocks {
                            if b["type"].as_str() != Some("text") {
                                continue;
                            }
                            let s = strip_ide_preamble(b["text"].as_str().unwrap_or(""));
                            if !s.trim().is_empty() {
                                first_user = s.chars().take(120).collect();
                                break;
                            }
                        }
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
// search_session_content — grep a caller-supplied subset of sessions for a
// query string, message text only (not titles — the caller already knows how
// to match those instantly from the cached list_sessions result, so it only
// asks this for the sessions whose title didn't match). First hit per file
// wins: the file is read line-by-line and abandoned the moment a match is
// found, so a session's cost is bounded by how early the match falls, not by
// its total length.
//
// Cooperative cancellation: every call publishes its own `generation` as the
// latest one requested (SEARCH_GENERATION), then checks before starting each
// session file whether a NEWER call has since arrived — the caller fires one
// search per keystroke, so a slow typist's Nth keystroke would otherwise still
// be scanning file #1 while the (N+1)th keystroke's results are already what
// the UI wants. A superseded scan exits at the next file boundary rather than
// running to completion for a result the UI is about to discard anyway.
// ---------------------------------------------------------------------------

static SEARCH_GENERATION: AtomicU64 = AtomicU64::new(0);

/// @param generation this call's ordinal (the caller increments a per-session-search
///   counter each time the query changes) — used only for cancellation, unrelated to
///   the requestId round-tripped back to JS for discarding stale results.
/// @param own_messages_only restrict the scan to `type:"user"` events (the user's own
///   messages), skipping assistant turns entirely — a cheaper, narrower scope than
///   the full conversation.
pub fn search_session_content(workspace_root: &str, session_ids: &[String], query: &str, own_messages_only: bool, generation: u64) -> String {
    SEARCH_GENERATION.fetch_max(generation, Ordering::Relaxed);

    let dir = match projects_dir(workspace_root) {
        Some(d) => d,
        None => return "[]".into(),
    };
    let needle = query.to_lowercase();
    if needle.is_empty() {
        return "[]".into();
    }

    let mut results: Vec<serde_json::Value> = Vec::new();

    for session_id in session_ids {
        if SEARCH_GENERATION.load(Ordering::Relaxed) != generation {
            break;   // superseded by a newer keystroke's search — stop wasted I/O
        }
        let path = dir.join(format!("{session_id}.jsonl"));
        let file = match fs::File::open(&path) {
            Ok(f) => f,
            Err(_) => continue,
        };
        let reader = BufReader::new(file);

        for line in reader.lines() {
            // Checked every line, not just every file: one large session shouldn't
            // stall a supersede until its whole file is read.
            if SEARCH_GENERATION.load(Ordering::Relaxed) != generation {
                return serde_json::to_string(&results).unwrap_or_else(|_| "[]".into());
            }
            let line = match line {
                Ok(l) if !l.is_empty() => l,
                _ => continue,
            };
            let event: serde_json::Value = match serde_json::from_str(&line) {
                Ok(v) => v,
                Err(_) => continue,
            };
            if own_messages_only && event["type"].as_str() != Some("user") {
                continue;
            }

            let mut texts: Vec<String> = Vec::new();
            if let Some(content) = event["message"]["content"].as_str() {
                texts.push(strip_ide_preamble(content));
            } else if let Some(blocks) = event["message"]["content"].as_array() {
                for b in blocks {
                    if b["type"].as_str() == Some("text") {
                        texts.push(strip_ide_preamble(b["text"].as_str().unwrap_or("")));
                    }
                }
            }

            let mut found: Option<String> = None;
            for text in &texts {
                if let Some(pos) = text.to_lowercase().find(&needle) {
                    let start = text[..pos].char_indices().rev().nth(39).map(|(i, _)| i).unwrap_or(0);
                    let end = (pos + needle.len() + 40).min(text.len());
                    found = Some(text[start..end].trim().to_string());
                    break;
                }
            }

            if let Some(snippet) = found {
                results.push(serde_json::json!({
                    "sessionId": session_id,
                    "snippet": snippet,
                }));
                break;   // one match is enough — move to the next session
            }
        }
    }

    serde_json::to_string(&results).unwrap_or_else(|_| "[]".into())
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
    // Map each tool_use id → the index of its item in `items`, so a later
    // tool_result can stamp that tool's outcome (finished vs. interrupted).
    let mut tool_idx: HashMap<String, usize> = HashMap::new();
    // tool_use id → whether its tool_result reported an error (interrupt/reject).
    let mut result_error: HashMap<String, bool> = HashMap::new();
    // tool_use id → the one-line reason a failed tool gave, for the muted line
    // under its tool row. Only failures the user did not cause are recorded —
    // see `tool_error_summary`, which returns None for their own decisions.
    let mut result_text: HashMap<String, String> = HashMap::new();

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
                    // A post-compaction summary is stored as a user line flagged
                    // isCompactSummary — surface it as the expandable "Compacted
                    // chat" body, never as a (huge) user bubble.
                    if event["isCompactSummary"].as_bool().unwrap_or(false) {
                        items.push(serde_json::json!({ "t": "compact_summary", "text": c }));
                    } else {
                        let mut item = serde_json::json!({ "t": "user", "content": c });
                        // The transcript uuid, so the GUI can target THIS message for
                        // per-message actions (rewind/fork/delete). Only set when the
                        // line actually carries one — an id-less item isn't targetable.
                        if let Some(u) = event["uuid"].as_str() {
                            if !u.is_empty() {
                                item["id"] = serde_json::Value::from(u);
                            }
                        }
                        // ISO 8601, same field list_sessions already reads for its own
                        // sort key — forwarded so the GUI can show it above the bubble
                        // (opt-in preference), not currently used for anything else here.
                        if let Some(ts) = event["timestamp"].as_str() {
                            if !ts.is_empty() {
                                item["ts"] = serde_json::Value::from(ts);
                            }
                        }
                        items.push(item);
                    }
                } else if let Some(blocks) = content.as_array() {
                    // A message the user sent with pasted images is stored as
                    // content BLOCKS (text + image), not a plain string — rebuild
                    // it as one user item so the bubble and its image chips come
                    // back on reload. Images carry their base64 so the chip can
                    // draw its thumbnail; tool_result-only lines add nothing.
                    let mut text = String::new();
                    let mut images: Vec<serde_json::Value> = Vec::new();
                    for b in blocks {
                        match b["type"].as_str() {
                            Some("text") => {
                                let s = b["text"].as_str().unwrap_or("");
                                if !s.is_empty() {
                                    if !text.is_empty() {
                                        text.push('\n');
                                    }
                                    text.push_str(s);
                                }
                            }
                            Some("image") => {
                                let src = &b["source"];
                                let data = src["data"].as_str().unwrap_or("");
                                if data.is_empty() {
                                    continue;
                                }
                                let mt = src["media_type"].as_str().unwrap_or("image/png");
                                images.push(
                                    serde_json::json!({ "media_type": mt, "data": data }),
                                );
                            }
                            _ => {}
                        }
                    }
                    if !text.is_empty() || !images.is_empty() {
                        let mut item = serde_json::json!({ "t": "user", "content": text });
                        if !images.is_empty() {
                            item["images"] = serde_json::Value::Array(images);
                        }
                        if let Some(u) = event["uuid"].as_str() {
                            if !u.is_empty() {
                                item["id"] = serde_json::Value::from(u);
                            }
                        }
                        if let Some(ts) = event["timestamp"].as_str() {
                            if !ts.is_empty() {
                                item["ts"] = serde_json::Value::from(ts);
                            }
                        }
                        items.push(item);
                    }
                    for b in blocks {
                        if b["type"].as_str() != Some("tool_result") {
                            continue;
                        }
                        let tuid = b["tool_use_id"].as_str().unwrap_or("");
                        // Record the tool's outcome so its dot can be reconstructed:
                        // is_error ⇒ interrupted/rejected, otherwise finished. (A tool
                        // with no result at all stays unresolved → interrupted below.)
                        if !tuid.is_empty() {
                            let is_err = b["is_error"].as_bool().unwrap_or(false);
                            result_error.insert(tuid.to_string(), is_err);
                            // Keep WHY it failed, not just that it did — reloading a
                            // conversation used to leave a bare red dot with the reason
                            // thrown away, so a past failure read as an unexplained stop.
                            if is_err {
                                if let Some(sum) = tool_error_summary(&flatten_result_content(b)) {
                                    result_text.insert(tuid.to_string(), sum);
                                }
                            }
                        }
                        if !ask_ids.contains(tuid) {
                            continue;
                        }
                        let rc = strip_answer_prefix(&flatten_result_content(b));
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
                // A synthetic assistant message standing in for a backend error
                // (529 overload, session-limit hit, …). The CLI flags it
                // isApiErrorMessage — verified on disk for both of those texts —
                // and live it renders as the muted "⚠ …" line via onError, never
                // as a paragraph. Reload has to rebuild that same muted line, so
                // surface it as its own item type rather than ordinary text.
                if event["isApiErrorMessage"].as_bool().unwrap_or(false) {
                    let mut text = String::new();
                    for b in content {
                        if b["type"].as_str() != Some("text") {
                            continue;
                        }
                        let s = b["text"].as_str().unwrap_or("");
                        if !s.is_empty() {
                            if !text.is_empty() {
                                text.push('\n');
                            }
                            text.push_str(s);
                        }
                    }
                    if !text.is_empty() {
                        items.push(serde_json::json!({ "t": "error", "text": text }));
                    }
                    continue;
                }
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
                            if let Some(id) = b["id"].as_str() {
                                // Remember where this tool sits so its result can stamp
                                // a status onto it after the whole file is read.
                                tool_idx.insert(id.to_string(), items.len() - 1);
                                if name.to_ascii_lowercase().contains("askuserquestion") {
                                    ask_ids.insert(id.to_string());
                                }
                            }
                        }
                        _ => {}
                    }
                }
            }
            Some("system") => {
                // Compaction marker (written by /compact or auto-compact). The
                // jsonl uses camelCase compactMetadata (unlike the stream's
                // compact_metadata) — verified against CLI 2.1.177.
                if event["subtype"].as_str() == Some("compact_boundary") {
                    let md = &event["compactMetadata"];
                    items.push(serde_json::json!({
                        "t": "compact",
                        "trigger": md["trigger"].as_str().unwrap_or("manual"),
                        "preTokens": md["preTokens"].as_u64().unwrap_or(0),
                        "postTokens": md["postTokens"].as_u64().unwrap_or(0),
                    }));
                }
            }
            _ => {}
        }
    }

    // Stamp each tool with a reconstructed dot status so reloading a past
    // conversation keeps the green/red it had live:
    //   • result present, not an error → "done"        (finished, green)
    //   • result present with is_error → "interrupted"  (rejected/stopped, red)
    //   • no result at all             → "interrupted"  (turn was cut off, red)
    for (id, &idx) in &tool_idx {
        let status = match result_error.get(id) {
            Some(false) => "done",
            Some(true) => "interrupted",
            None => "interrupted",
        };
        if let Some(obj) = items.get_mut(idx).and_then(|v| v.as_object_mut()) {
            obj.insert("status".into(), serde_json::Value::from(status));
            // The reason, when the failure was the tool's own. A cut-off turn has
            // no result and so no text — the red dot alone still says "stopped".
            if let Some(txt) = result_text.get(id) {
                obj.insert("errorText".into(), serde_json::Value::from(txt.as_str()));
            }
        }
    }

    serde_json::to_string(&items).unwrap_or_else(|_| "[]".into())
}

/// Flattens a `tool_result` block's content to plain text. The CLI writes it
/// either as a bare string or as `[{type:"text",…}]` blocks, so both shapes have
/// to collapse to the same thing.
pub(crate) fn flatten_result_content(b: &serde_json::Value) -> String {
    let mut out = String::new();
    if let Some(s) = b["content"].as_str() {
        out.push_str(s);
    } else if let Some(parts) = b["content"].as_array() {
        for rb in parts {
            if rb["type"].as_str() == Some("text") {
                out.push_str(rb["text"].as_str().unwrap_or(""));
            }
        }
    }
    out
}

/// Longest error summary we surface. The full text stays in the transcript; the
/// GUI shows one line, and real results run to 100+ lines.
const ERROR_SUMMARY_MAX: usize = 160;

/// Prefixes that mark a result as the USER'S OWN decision rather than a tool
/// failure. The CLI reports "declined", "rejected" and "answered instead" through
/// the same `is_error` channel a genuine failure uses, but the GUI already shows
/// those through its decision cards — repeating the sentence under the tool row
/// would be noise. Verified against 111 real `is_error` results: 23 are these.
const DECISION_PREFIXES: [&str; 4] = [
    "The user doesn't want to proceed",
    "The user declined",
    "The user dismissed",
    "[User typed]:",
];

/// Condenses a failed tool's result into the single muted line shown beneath it,
/// or `None` when nothing should be shown.
///
/// Returns `None` for the user's own decisions (see [`DECISION_PREFIXES`]) so a
/// declined tool keeps its red dot and stays quiet.
///
/// A bare `Exit code N` first line is joined to the next real line: three
/// quarters of genuine failures lead with it, and the number alone says nothing
/// about what broke. The exit status is kept rather than dropped because 143
/// (timeout) and 1 (ordinary failure) mean different things.
pub(crate) fn tool_error_summary(raw: &str) -> Option<String> {
    let mut t = raw.trim();
    if t.is_empty() {
        return None;
    }
    if DECISION_PREFIXES.iter().any(|p| t.starts_with(p)) {
        return None;
    }
    // Unwrap the CLI's own error envelope so the message reads plainly.
    if let Some(inner) = t.strip_prefix("<tool_use_error>") {
        t = inner.strip_suffix("</tool_use_error>").unwrap_or(inner).trim();
    }
    let mut lines = t.lines().map(str::trim).filter(|l| !l.is_empty());
    let head = lines.next()?;
    let mut summary = head.to_string();
    if is_bare_exit_code(head) {
        if let Some(next) = lines.next() {
            summary.push_str(" · ");
            summary.push_str(next);
        }
    }
    if summary.is_empty() {
        return None;
    }
    // char_indices, not byte slicing — these carry paths and prose that are not
    // guaranteed ASCII, and a mid-codepoint cut would panic.
    if summary.chars().count() > ERROR_SUMMARY_MAX {
        let cut: String = summary.chars().take(ERROR_SUMMARY_MAX).collect();
        summary = format!("{}…", cut.trim_end());
    }
    Some(summary)
}

/// True for a line that is exactly "Exit code <digits>" and nothing else.
fn is_bare_exit_code(line: &str) -> bool {
    match line.strip_prefix("Exit code ") {
        Some(rest) => !rest.is_empty() && rest.chars().all(|c| c.is_ascii_digit()),
        None => false,
    }
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
// message_ids / delete_message — per-message actions inside one transcript
//
// The jsonl is a parentUuid-linked chain, so dropping a line means re-linking
// its children onto that line's OWN parent; a plain filter leaves a dangling
// reference in the chain the CLI walks on `--resume`.
//
// The raw prompt is also stored OUTSIDE the chain, in line types that carry no
// uuid at all: `queue-operation.content` (what was typed, `<ide_context …>`
// wrapper included) and `last-prompt.lastPrompt` (rewritten every time the leaf
// advances, so one message leaves many copies). Measured on a live transcript:
// a single message existed 9 times — 1 chained, 6 last-prompt, 2
// queue-operation. Removing only the chained line leaves the text on disk while
// every UI surface reports success (the readers here and in the CLI both render
// line-by-line and never look at these types), so the copies are stripped too
// and the result is asserted BEFORE anything is written.
//
// `file-history-snapshot` lines are deliberately left untouched: RewindService
// forward-merges them in first-appearance order to recover pre-first-edit
// backups, so dropping one silently corrupts rewinding to EARLIER messages.
// ---------------------------------------------------------------------------

/// The user messages the GUI draws as bubbles, in order, as
/// `[{"id":<uuid>,"text":<raw content>}]`. Derived from `load_session_history`'s
/// own output, so these can never drift from the rendered items.
///
/// The text ships with the id because position alone cannot identify a bubble: a
/// message queued mid-stream is on screen BEFORE its transcript line exists, so
/// the two sequences differ in length and pairing by index (from either end)
/// mis-assigns. The caller matches on text instead.
pub fn message_ids(workspace_root: &str, session_id: &str) -> String {
    let items: Vec<serde_json::Value> =
        serde_json::from_str(&load_session_history(workspace_root, session_id))
            .unwrap_or_default();
    let out: Vec<serde_json::Value> = items
        .iter()
        .filter(|it| it["t"].as_str() == Some("user"))
        .filter_map(|it| {
            let id = it["id"].as_str()?;
            Some(serde_json::json!({ "id": id, "text": it["content"].as_str().unwrap_or("") }))
        })
        .collect();
    serde_json::to_string(&out).unwrap_or_else(|_| "[]".into())
}

/// The typed prompt a line carries, or None when it isn't a real user message
/// (tool_result turns and every non-user line included).
fn prompt_text(event: &serde_json::Value) -> Option<String> {
    if event["type"].as_str() != Some("user") {
        return None;
    }
    let content = &event["message"]["content"];
    if let Some(s) = content.as_str() {
        return Some(s.to_string());
    }
    let mut text = String::new();
    for b in content.as_array()? {
        if b["type"].as_str() != Some("text") {
            continue;
        }
        if let Some(s) = b["text"].as_str() {
            if !text.is_empty() {
                text.push('\n');
            }
            text.push_str(s);
        }
    }
    if text.is_empty() {
        None
    } else {
        Some(text)
    }
}

/// Whether a bookkeeping field holds the prompt being removed. The queue log
/// keeps the text with its `<ide_context …>` wrapper still attached, so an exact
/// match would miss it — compare stripped forms, either containing the other.
fn same_prompt(field: &str, target: &str) -> bool {
    let a = strip_ide_preamble(field).trim().to_string();
    let b = strip_ide_preamble(target).trim().to_string();
    if a.is_empty() || b.is_empty() {
        return false;
    }
    a == b || a.contains(&b) || b.contains(&a)
}

/// Any string anywhere in the line still holding `needle`. Walks the parsed
/// value rather than the raw text so JSON escaping can't hide a match.
fn holds_prompt(v: &serde_json::Value, needle: &str) -> bool {
    match v {
        serde_json::Value::String(s) => strip_ide_preamble(s).trim().contains(needle),
        serde_json::Value::Array(a) => a.iter().any(|x| holds_prompt(x, needle)),
        serde_json::Value::Object(o) => o.values().any(|x| holds_prompt(x, needle)),
        _ => false,
    }
}

/// Permanently removes one user message from a session transcript.
/// Returns `{"ok":true,"stripped":N}` or `{"error":"…"}` — N being the unchained
/// bookkeeping copies cleared alongside the message itself.
pub fn delete_message(workspace_root: &str, session_id: &str, message_id: &str) -> String {
    match delete_message_inner(workspace_root, session_id, message_id) {
        Ok(n) => serde_json::json!({ "ok": true, "stripped": n }).to_string(),
        Err(e) => serde_json::json!({ "error": e }).to_string(),
    }
}

fn delete_message_inner(
    workspace_root: &str,
    session_id: &str,
    message_id: &str,
) -> Result<usize, String> {
    if session_id.is_empty()
        || session_id.contains('/')
        || session_id.contains('\\')
        || session_id.contains("..")
    {
        return Err("Bad session id.".into());
    }
    if message_id.is_empty() {
        return Err("Bad message id.".into());
    }
    let dir = projects_dir(workspace_root).ok_or("No transcripts for this workspace.")?;
    let path = dir.join(format!("{}.jsonl", session_id));
    let raw =
        fs::read_to_string(&path).map_err(|e| format!("Cannot read the transcript ({e})."))?;
    let eol = if raw.contains("\r\n") { "\r\n" } else { "\n" };
    let lines: Vec<&str> = raw.lines().collect();
    let parsed: Vec<Option<serde_json::Value>> = lines
        .iter()
        .map(|l| {
            if l.trim().is_empty() {
                None
            } else {
                serde_json::from_str(l).ok()
            }
        })
        .collect();

    let target = parsed
        .iter()
        .position(|e| {
            e.as_ref().map_or(false, |e| {
                e["type"].as_str() == Some("user") && e["uuid"].as_str() == Some(message_id)
            })
        })
        .ok_or("That message is no longer in this conversation.")?;
    let text = parsed[target].as_ref().and_then(prompt_text).unwrap_or_default();
    let dead_parent = parsed[target]
        .as_ref()
        .map(|e| e["parentUuid"].clone())
        .unwrap_or(serde_json::Value::Null);

    // The span this message owns: from the previous typed prompt to the next one.
    // Its bookkeeping copies live inside that window (queue-operation just ahead
    // of the message, last-prompt repeatedly after it). The span does NOT by
    // itself separate this message's copies from the previous message's trailing
    // ones — those sit inside it too — that is what `same_prompt` is for; the span
    // keeps the sweep and the assertion off messages further away. Two CONSECUTIVE
    // prompts with identical text can therefore clear each other's bookkeeping
    // field, which is harmless (the other message's own line is untouched).
    let is_boundary = |i: usize| {
        parsed[i]
            .as_ref()
            .map_or(false, |e| prompt_text(e).is_some())
    };
    let start = (0..target).rev().find(|&i| is_boundary(i)).map_or(0, |i| i + 1);
    let end = ((target + 1)..parsed.len())
        .find(|&i| is_boundary(i))
        .unwrap_or(parsed.len());

    let mut out: Vec<String> = Vec::with_capacity(lines.len());
    let mut span_check: Vec<serde_json::Value> = Vec::new();
    let mut stripped = 0usize;
    for (i, line) in lines.iter().enumerate() {
        if i == target {
            continue; // the message itself
        }
        let Some(orig) = parsed[i].as_ref() else {
            out.push((*line).to_string());
            continue;
        };
        let mut ev = orig.clone();
        let mut changed = false;
        let mut ty = String::new();
        if let Some(obj) = ev.as_object_mut() {
            ty = obj
                .get("type")
                .and_then(|v| v.as_str())
                .unwrap_or("")
                .to_string();
            // Children of the removed line adopt its parent, so the chain still
            // closes for `--resume`.
            for key in ["parentUuid", "logicalParentUuid", "leafUuid"] {
                if obj.get(key).and_then(|v| v.as_str()) == Some(message_id) {
                    obj.insert(key.to_string(), dead_parent.clone());
                    changed = true;
                }
            }
            let field = match ty.as_str() {
                "queue-operation" => Some("content"),
                "last-prompt" => Some("lastPrompt"),
                _ => None,
            };
            if let Some(field) = field {
                let hit = i >= start
                    && i < end
                    && obj
                        .get(field)
                        .and_then(|v| v.as_str())
                        .map_or(false, |s| same_prompt(s, &text));
                if hit {
                    obj.remove(field);
                    changed = true;
                    stripped += 1;
                }
            }
        }
        // Assertion set: everything in this message's span that ISN'T a message
        // in its own right. `assistant` lines are skipped because Claude quoting
        // the text back is legitimate; `user` lines are skipped because they are
        // other people's messages. Anything else — including a line type a
        // future CLI adds — must come out clean.
        if i >= start && i < end && ty != "user" && ty != "assistant" {
            span_check.push(ev.clone());
        }
        out.push(if changed {
            ev.to_string()
        } else {
            (*line).to_string()
        });
    }

    let needle = strip_ide_preamble(&text).trim().to_string();
    if !needle.is_empty() {
        if let Some(bad) = span_check.iter().find(|e| holds_prompt(e, &needle)) {
            return Err(format!(
                "The message text is still present in a \"{}\" line — the transcript was left untouched.",
                bad["type"].as_str().unwrap_or("transcript")
            ));
        }
    }

    // Replace via a sibling temp file so a crash mid-write can't truncate the
    // transcript.
    let tmp = dir.join(format!("{}.jsonl.tmp", session_id));
    {
        let mut f =
            fs::File::create(&tmp).map_err(|e| format!("Cannot write the transcript ({e})."))?;
        for l in &out {
            f.write_all(l.as_bytes())
                .and_then(|_| f.write_all(eol.as_bytes()))
                .map_err(|e| format!("Cannot write the transcript ({e})."))?;
        }
    }
    fs::rename(&tmp, &path).map_err(|e| format!("Cannot replace the transcript ({e})."))?;
    Ok(stripped)
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

/// Best-effort kill by pid, used only by the offline-rename watchdog.
#[cfg(target_os = "freebsd")]
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

    /// Covers: a match on the first session found + snippet returned, no match on a
    /// second, an id NOT in the search list skipped even though its file would match
    /// (proving the caller-supplied subset is honored, not re-derived), and matching
    /// is case-insensitive.
    #[test]
    fn search_session_content_finds_first_match_and_skips_others() {
        let _env = ENV_LOCK.lock().unwrap();
        let home = std::env::temp_dir().join("claude-eclipse-session-search-home");
        let root = r"C:\searchtest";
        let dir = home.join(".claude").join("projects").join("C--searchtest");
        let _ = fs::remove_dir_all(&home);
        fs::create_dir_all(&dir).unwrap();

        fs::write(dir.join("aaaa1111.jsonl"), concat!(
            r#"{"type":"user","message":{"role":"user","content":"talking about the quilt patch system"},"timestamp":"2026-07-01T10:00:00.000Z"}"#, "\n",
        )).unwrap();
        fs::write(dir.join("bbbb2222.jsonl"), concat!(
            r#"{"type":"user","message":{"role":"user","content":"nothing relevant here"},"timestamp":"2026-07-01T10:00:00.000Z"}"#, "\n",
        )).unwrap();
        // Would match too, but deliberately left out of the search list below.
        fs::write(dir.join("cccc3333.jsonl"), concat!(
            r#"{"type":"user","message":{"role":"user","content":"QUILT also appears here"},"timestamp":"2026-07-01T10:00:00.000Z"}"#, "\n",
        )).unwrap();

        set_home(&home);
        let ids = vec!["aaaa1111".to_string(), "bbbb2222".to_string()];
        // Each test uses its own generation band, well clear of the others', so the
        // shared process-wide SEARCH_GENERATION ratchet (parallel test threads) can't
        // make one test's call see itself as superseded by another's.
        let json = super::search_session_content(root, &ids, "quilt", false, 1_000);
        let _ = fs::remove_dir_all(&home);

        let v: serde_json::Value = serde_json::from_str(&json).unwrap();
        let arr = v.as_array().unwrap();
        assert_eq!(arr.len(), 1, "only aaaa1111 matches within the requested subset: {json}");
        assert_eq!(arr[0]["sessionId"].as_str().unwrap(), "aaaa1111");
        assert!(arr[0]["snippet"].as_str().unwrap().to_lowercase().contains("quilt"));
    }

    /// A query that only appears in an assistant turn matches with the full-conversation
    /// scope but not with own_messages_only — proving the scope actually excludes
    /// assistant text rather than just being ignored.
    #[test]
    fn search_session_content_own_messages_only_excludes_assistant_text() {
        let _env = ENV_LOCK.lock().unwrap();
        let home = std::env::temp_dir().join("claude-eclipse-session-search-own-home");
        let root = r"C:\searchownt";
        let dir = home.join(".claude").join("projects").join("C--searchownt");
        let _ = fs::remove_dir_all(&home);
        fs::create_dir_all(&dir).unwrap();

        fs::write(dir.join("aaaa1111.jsonl"), concat!(
            r#"{"type":"user","message":{"role":"user","content":"please help me"},"timestamp":"2026-07-01T10:00:00.000Z"}"#, "\n",
            r#"{"type":"assistant","message":{"content":[{"type":"text","text":"the quilt patch system works like this"}]},"timestamp":"2026-07-01T10:00:05.000Z"}"#, "\n",
        )).unwrap();

        set_home(&home);
        let ids = vec!["aaaa1111".to_string()];
        // Own generation band — see the comment in the sibling test above.
        let full = super::search_session_content(root, &ids, "quilt", false, 2_000);
        let own_only = super::search_session_content(root, &ids, "quilt", true, 2_000);
        let _ = fs::remove_dir_all(&home);

        let full_v: serde_json::Value = serde_json::from_str(&full).unwrap();
        assert_eq!(full_v.as_array().unwrap().len(), 1, "full-conversation scope finds the assistant match: {full}");
        let own_v: serde_json::Value = serde_json::from_str(&own_only).unwrap();
        assert_eq!(own_v.as_array().unwrap().len(), 0, "own_messages_only must not match assistant text: {own_only}");
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
        // toolu_01 (askUserQuestion) has a non-error tool_result → status "done";
        // toolu_02 (Edit) has no tool_result in the fixture → status "interrupted".
        let want1: serde_json::Value = serde_json::from_str(r#"[
            {"t":"user","content":"<ide_selection a=\"b\">sel junk</ide_selection>please fix the bug","ts":"2026-07-01T10:00:00.000Z"},
            {"t":"thinking","model":"claude-fable-5","text":"hmm secret"},
            {"t":"text","text":"Here is my answer","model":"claude-fable-5"},
            {"t":"tool","name":"mcp__eclipse__askUserQuestion","input":{"q":"Which color?"},"model":"claude-fable-5","status":"done"},
            {"t":"answered","text":"Blue"},
            {"t":"tool","name":"Edit","input":{"file_path":"C:\\x.java","old_string":"a","new_string":"b"},"model":"claude-opus-4-8","status":"interrupted"}
        ]"#).unwrap();
        assert_eq!(got1, want1, "sess1 render items must match the reference reader");

        let got2: serde_json::Value = serde_json::from_str(&loaded2).unwrap();
        let want2: serde_json::Value = serde_json::from_str(r#"[
            {"t":"user","content":"<command-name>/clear</command-name><command-message>clear</command-message><command-args>now</command-args>","ts":"2026-07-03T08:00:00.000Z"}
        ]"#).unwrap();
        assert_eq!(got2, want2, "sess2: raw user kept, non-ask tool_result ignored");

        // List titles: sess2's fallback title is the fully-unwrapped command text.
        let lv: serde_json::Value = serde_json::from_str(&listed).unwrap();
        let sess2 = lv.as_array().unwrap().iter()
            .find(|s| s["sessionId"] == "sess2").expect("sess2 listed");
        assert_eq!(sess2["display"], "/clear", "command wrappers stripped from title");
        assert_eq!(sess2["timestamp"], "2026-07-03T08:00:02.000Z");
    }

    /// A compacted session reloads as a "Compacted chat" marker + expandable
    /// summary: the compact_boundary system line becomes a t:"compact" item
    /// (camelCase compactMetadata → trigger/preTokens/postTokens) and the
    /// isCompactSummary user line becomes t:"compact_summary" — never a user
    /// bubble. Fixture shapes captured from a real CLI 2.1.177 /compact run.
    #[test]
    fn load_session_surfaces_compact_boundary_and_summary() {
        let _env = ENV_LOCK.lock().unwrap();
        let home = std::env::temp_dir().join("claude-eclipse-session-compact-home");
        let root = r"C:\compactws";
        let dir = home.join(".claude").join("projects").join("C--compactws");
        let _ = fs::remove_dir_all(&home);
        fs::create_dir_all(&dir).unwrap();

        fs::write(dir.join("sessc.jsonl"), concat!(
            r#"{"type":"user","message":{"role":"user","content":"tell me things"},"timestamp":"2026-07-27T02:00:00.000Z"}"#, "\n",
            r#"{"type":"assistant","message":{"model":"claude-haiku-4-5-20251001","content":[{"type":"text","text":"things"}]},"timestamp":"2026-07-27T02:00:05.000Z"}"#, "\n",
            r#"{"type":"system","subtype":"compact_boundary","content":"Conversation compacted","isMeta":false,"compactMetadata":{"trigger":"manual","preTokens":23670,"durationMs":10550,"postTokens":1682},"timestamp":"2026-07-27T02:37:08.042Z"}"#, "\n",
            r#"{"type":"user","isCompactSummary":true,"isVisibleInTranscriptOnly":true,"message":{"role":"user","content":"This session is being continued from a previous conversation. Summary: things were told."},"timestamp":"2026-07-27T02:37:08.100Z"}"#, "\n",
            r#"{"type":"user","isMeta":true,"message":{"role":"user","content":"<local-command-caveat>Caveat: ...</local-command-caveat>"},"timestamp":"2026-07-27T02:37:08.120Z"}"#, "\n",
            r#"{"type":"user","message":{"role":"user","content":"<command-name>/compact</command-name>"},"timestamp":"2026-07-27T02:37:08.130Z"}"#, "\n",
        )).unwrap();

        set_home(&home);
        let loaded = super::load_session_history(root, "sessc");
        let _ = fs::remove_dir_all(&home);

        let got: serde_json::Value = serde_json::from_str(&loaded).unwrap();
        let want: serde_json::Value = serde_json::from_str(r#"[
            {"t":"user","content":"tell me things","ts":"2026-07-27T02:00:00.000Z"},
            {"t":"text","text":"things","model":"claude-haiku-4-5-20251001"},
            {"t":"compact","trigger":"manual","preTokens":23670,"postTokens":1682},
            {"t":"compact_summary","text":"This session is being continued from a previous conversation. Summary: things were told."},
            {"t":"user","content":"<local-command-caveat>Caveat: ...</local-command-caveat>","ts":"2026-07-27T02:37:08.120Z"},
            {"t":"user","content":"<command-name>/compact</command-name>","ts":"2026-07-27T02:37:08.130Z"}
        ]"#).unwrap();
        assert_eq!(got, want, "compacted session render items");
    }

    /// A message sent with pasted images is stored as content BLOCKS, not a
    /// string — it must come back as one user item carrying its text and the
    /// images' base64 (so the chips redraw), the session must be titled from
    /// that text, and tool_result-only block lines must still add no bubble.
    /// Shapes captured from a real CLI transcript (note the CLI re-encodes a
    /// pasted PNG to image/jpeg).
    #[test]
    fn load_session_restores_pasted_images() {
        let _env = ENV_LOCK.lock().unwrap();
        let home = std::env::temp_dir().join("claude-eclipse-session-images-home");
        let root = r"C:\imgws";
        let dir = home.join(".claude").join("projects").join("C--imgws");
        let _ = fs::remove_dir_all(&home);
        fs::create_dir_all(&dir).unwrap();

        fs::write(dir.join("sessi.jsonl"), concat!(
            r#"{"type":"user","message":{"role":"user","content":[{"type":"text","text":"<ide_context openFile=\"C:\\a\\B.java\" />\n\nwhat is this"},{"type":"image","source":{"type":"base64","media_type":"image/jpeg","data":"QUJD"}}]},"timestamp":"2026-07-30T01:00:00.000Z"}"#, "\n",
            r#"{"type":"assistant","message":{"model":"claude-opus-4-8","content":[{"type":"tool_use","id":"t1","name":"Read","input":{"file_path":"a.txt"}}]},"timestamp":"2026-07-30T01:00:03.000Z"}"#, "\n",
            r#"{"type":"user","message":{"role":"user","content":[{"type":"tool_result","tool_use_id":"t1","content":"file contents"}]},"timestamp":"2026-07-30T01:00:04.000Z"}"#, "\n",
            r#"{"type":"assistant","message":{"model":"claude-opus-4-8","content":[{"type":"text","text":"a screenshot"}]},"timestamp":"2026-07-30T01:00:06.000Z"}"#, "\n",
        )).unwrap();

        set_home(&home);
        let loaded = super::load_session_history(root, "sessi");
        let listed = super::list_sessions(root);
        let _ = fs::remove_dir_all(&home);

        let got: serde_json::Value = serde_json::from_str(&loaded).unwrap();
        let want: serde_json::Value = serde_json::from_str(r#"[
            {"t":"user","content":"<ide_context openFile=\"C:\\a\\B.java\" />\n\nwhat is this",
             "images":[{"media_type":"image/jpeg","data":"QUJD"}],"ts":"2026-07-30T01:00:00.000Z"},
            {"t":"tool","name":"Read","input":{"file_path":"a.txt"},"status":"done","model":"claude-opus-4-8"},
            {"t":"text","text":"a screenshot","model":"claude-opus-4-8"}
        ]"#).unwrap();
        assert_eq!(got, want, "pasted-image session render items");

        // The list title comes from the text block, with the IDE preamble stripped.
        let sessions: serde_json::Value = serde_json::from_str(&listed).unwrap();
        assert_eq!(sessions[0]["display"], serde_json::json!("what is this"));
    }

    /// Tool dots are reconstructed from the transcript so a reloaded conversation
    /// keeps its green/red: a non-error tool_result ⇒ "done", an is_error result ⇒
    /// "interrupted", and a tool with no result at all ⇒ "interrupted".
    /// A backend error the CLI stores as a SYNTHETIC assistant message flagged
    /// isApiErrorMessage must come back as t:"error" (the muted "⚠ …" line the
    /// live run showed via onError), never as t:"text" — otherwise reopening a
    /// past session reads the outage as something the model said. Both fixture
    /// lines are real shapes captured from local transcripts (a 429 session-limit
    /// hit and a 529 overload); ordinary assistant text alongside them must stay
    /// t:"text". If the CLI ever stops setting the flag this test breaks instead
    /// of the errors silently turning back into paragraphs.
    #[test]
    fn load_session_surfaces_api_errors_as_muted_lines() {
        let _env = ENV_LOCK.lock().unwrap();
        let home = std::env::temp_dir().join("claude-eclipse-session-apierr-home");
        let root = r"C:\errws";
        let dir = home.join(".claude").join("projects").join("C--errws");
        let _ = fs::remove_dir_all(&home);
        fs::create_dir_all(&dir).unwrap();

        fs::write(dir.join("sesse.jsonl"), concat!(
            r#"{"type":"user","message":{"role":"user","content":"go"},"timestamp":"2026-08-26T01:00:00.000Z"}"#, "
",
            r#"{"type":"assistant","message":{"model":"claude-opus-4-8","content":[{"type":"text","text":"working on it"}]},"timestamp":"2026-08-26T01:00:02.000Z"}"#, "
",
            r#"{"type":"assistant","isApiErrorMessage":true,"apiErrorStatus":429,"error":"rate_limit","message":{"model":"<synthetic>","role":"assistant","content":[{"type":"text","text":"You've hit your session limit · resets 2:10am (Asia/Irkutsk)"}]},"timestamp":"2026-08-26T01:00:03.000Z"}"#, "
",
            r#"{"type":"assistant","isApiErrorMessage":true,"apiErrorStatus":529,"error":"overloaded","message":{"model":"<synthetic>","role":"assistant","content":[{"type":"text","text":"API Error: 529 Overloaded. This is a server-side issue, usually temporary — try again in a moment. If it persists, check https://status.claude.com."}]},"timestamp":"2026-08-26T01:00:04.000Z"}"#, "
",
        )).unwrap();

        set_home(&home);
        let loaded = super::load_session_history(root, "sesse");
        let _ = fs::remove_dir_all(&home);

        let got: serde_json::Value = serde_json::from_str(&loaded).unwrap();
        let want: serde_json::Value = serde_json::from_str(r#"[
            {"t":"user","content":"go","ts":"2026-08-26T01:00:00.000Z"},
            {"t":"text","text":"working on it","model":"claude-opus-4-8"},
            {"t":"error","text":"You've hit your session limit · resets 2:10am (Asia/Irkutsk)"},
            {"t":"error","text":"API Error: 529 Overloaded. This is a server-side issue, usually temporary — try again in a moment. If it persists, check https://status.claude.com."}
        ]"#).unwrap();
        assert_eq!(got, want, "api error render items");
    }

    /// The one-line reason shown under a failed tool. Every input below is a real
    /// shape from local transcripts (111 `is_error` results were surveyed).
    #[test]
    fn tool_error_summary_condenses_real_failures() {
        use super::tool_error_summary as sum;

        // Three quarters of genuine failures lead with a bare exit code, which on
        // its own says nothing — the next real line is what broke.
        assert_eq!(
            sum("Exit code 1\nTraceback (most recent call last):\r\n  File \"<string>\", line 4"),
            Some("Exit code 1 · Traceback (most recent call last):".into())
        );
        // The status is kept, not dropped: 143 (timeout) ≠ 1 (ordinary failure).
        assert_eq!(
            sum("Exit code 143\nCommand timed out after 2m 0s"),
            Some("Exit code 143 · Command timed out after 2m 0s".into())
        );
        // An exit code with nothing after it still beats showing nothing.
        assert_eq!(sum("Exit code 2"), Some("Exit code 2".into()));
        // "Exit code" that is NOT bare is a message in its own right — left alone.
        assert_eq!(sum("Exit code 1 was returned"), Some("Exit code 1 was returned".into()));

        // The CLI's own error envelope is unwrapped so the message reads plainly.
        assert_eq!(
            sum("<tool_use_error>File has not been read yet. Read it first before writing to it.</tool_use_error>"),
            Some("File has not been read yet. Read it first before writing to it.".into())
        );

        // A single-line failure passes through untouched.
        assert_eq!(
            sum("File does not exist. Note: your current working directory is C:\\ws"),
            Some("File does not exist. Note: your current working directory is C:\\ws".into())
        );

        // The user's own decisions are NOT failures: the GUI already shows those
        // through its decision cards, so the tool row stays quiet (red dot only).
        assert_eq!(sum("The user doesn't want to proceed with this tool use. The tool use was rejected"), None);
        assert_eq!(sum("The user declined this action in Eclipse."), None);
        assert_eq!(sum("The user dismissed the prompt."), None);
        assert_eq!(sum("[User typed]: okay do it differently"), None);

        // Nothing to say → no line at all, rather than an empty one.
        assert_eq!(sum(""), None);
        assert_eq!(sum("   \n  \n"), None);
    }

    /// Long results are cut to one line's worth. The cut counts CHARACTERS, not
    /// bytes — these carry Windows paths and prose, and slicing mid-codepoint
    /// would panic the loader on a conversation that merely contains a failure.
    #[test]
    fn tool_error_summary_truncates_on_char_boundaries() {
        let long = "é".repeat(400);
        let got = super::tool_error_summary(&long).unwrap();
        assert_eq!(got.chars().count(), 161, "160 chars plus the ellipsis");
        assert!(got.ends_with('…'));

        let ascii = "x".repeat(400);
        let got = super::tool_error_summary(&ascii).unwrap();
        assert!(got.starts_with("xxxx") && got.ends_with('…'));
    }

    /// A failed tool must carry WHY it failed onto its render item, so a reopened
    /// conversation reads the same as it did live. A tool the user declined gets
    /// the red dot but no text, and a successful one neither.
    #[test]
    fn load_session_attaches_error_text_to_failed_tools() {
        let _env = ENV_LOCK.lock().unwrap();
        let home = std::env::temp_dir().join("claude-eclipse-session-toolerr-home");
        let root = r"C:\toolerrws";
        let dir = home.join(".claude").join("projects").join("C--toolerrws");
        let _ = fs::remove_dir_all(&home);
        fs::create_dir_all(&dir).unwrap();

        fs::write(dir.join("sesst.jsonl"), concat!(
            r#"{"type":"user","message":{"role":"user","content":"go"},"timestamp":"2026-09-04T01:00:00.000Z"}"#, "\n",
            r#"{"type":"assistant","message":{"model":"claude-opus-4-8","content":[{"type":"tool_use","id":"toolu_a","name":"Read","input":{"file_path":"C:\\nope.java"}}]},"timestamp":"2026-09-04T01:00:01.000Z"}"#, "\n",
            r#"{"type":"user","message":{"role":"user","content":[{"type":"tool_result","tool_use_id":"toolu_a","is_error":true,"content":"File does not exist. Note: your current working directory is C:\\ws"}]},"timestamp":"2026-09-04T01:00:02.000Z"}"#, "\n",
            r#"{"type":"assistant","message":{"model":"claude-opus-4-8","content":[{"type":"tool_use","id":"toolu_b","name":"Edit","input":{"file_path":"C:\\x.java"}}]},"timestamp":"2026-09-04T01:00:03.000Z"}"#, "\n",
            r#"{"type":"user","message":{"role":"user","content":[{"type":"tool_result","tool_use_id":"toolu_b","is_error":true,"content":"The user doesn't want to proceed with this tool use. The tool use was rejected"}]},"timestamp":"2026-09-04T01:00:04.000Z"}"#, "\n",
            r#"{"type":"assistant","message":{"model":"claude-opus-4-8","content":[{"type":"tool_use","id":"toolu_c","name":"Read","input":{"file_path":"C:\\ok.java"}}]},"timestamp":"2026-09-04T01:00:05.000Z"}"#, "\n",
            r#"{"type":"user","message":{"role":"user","content":[{"type":"tool_result","tool_use_id":"toolu_c","content":"contents"}]},"timestamp":"2026-09-04T01:00:06.000Z"}"#, "\n",
        )).unwrap();

        set_home(&home);
        let loaded = super::load_session_history(root, "sesst");
        let _ = fs::remove_dir_all(&home);

        let got: serde_json::Value = serde_json::from_str(&loaded).unwrap();
        let want: serde_json::Value = serde_json::from_str(r#"[
            {"t":"user","content":"go","ts":"2026-09-04T01:00:00.000Z"},
            {"t":"tool","name":"Read","input":{"file_path":"C:\\nope.java"},"model":"claude-opus-4-8","status":"interrupted","errorText":"File does not exist. Note: your current working directory is C:\\ws"},
            {"t":"tool","name":"Edit","input":{"file_path":"C:\\x.java"},"model":"claude-opus-4-8","status":"interrupted"},
            {"t":"tool","name":"Read","input":{"file_path":"C:\\ok.java"},"model":"claude-opus-4-8","status":"done"}
        ]"#).unwrap();
        assert_eq!(got, want, "failed tools carry their reason; declined ones stay quiet");
    }

    #[test]
    fn load_session_reconstructs_tool_status() {
        let _env = ENV_LOCK.lock().unwrap();
        let home = std::env::temp_dir().join("claude-eclipse-session-status-home");
        let root = r"C:\statusws";
        let dir = home.join(".claude").join("projects").join("C--statusws");
        let _ = fs::remove_dir_all(&home);
        fs::create_dir_all(&dir).unwrap();

        fs::write(dir.join("s.jsonl"), concat!(
            // A finished Read (has a normal result), an interrupted Bash (is_error
            // result — the "user doesn't want to proceed" case), and a trailing Edit
            // with no result at all (turn cut off).
            r#"{"type":"assistant","message":{"model":"claude-opus-4-8","content":[{"type":"tool_use","id":"t1","name":"Read","input":{"file_path":"a.txt"}},{"type":"tool_use","id":"t2","name":"Bash","input":{"command":"gh pr list"}},{"type":"tool_use","id":"t3","name":"Edit","input":{"file_path":"b.txt"}}]},"timestamp":"2026-07-15T10:00:00.000Z"}"#, "\n",
            r#"{"type":"user","message":{"role":"user","content":[{"type":"tool_result","tool_use_id":"t1","content":"file contents"},{"type":"tool_result","tool_use_id":"t2","is_error":true,"content":"The user doesn't want to proceed with this tool use."}]},"timestamp":"2026-07-15T10:00:03.000Z"}"#, "\n",
        )).unwrap();

        set_home(&home);
        let loaded = super::load_session_history(root, "s");
        let _ = fs::remove_dir_all(&home);

        let got: serde_json::Value = serde_json::from_str(&loaded).unwrap();
        let tools: Vec<(&str, &str)> = got.as_array().unwrap().iter()
            .filter(|it| it["t"] == "tool")
            .map(|it| (it["name"].as_str().unwrap(), it["status"].as_str().unwrap_or("MISSING")))
            .collect();
        assert_eq!(
            tools,
            vec![("Read", "done"), ("Bash", "interrupted"), ("Edit", "interrupted")],
            "tool dot status reconstructed from tool_result presence/is_error"
        );
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

    /// Fixture shaped like a real transcript (verified against a live one on
    /// 2026-07-30): a parentUuid chain, an `attachment` child hanging off the
    /// user line, `file-history-snapshot` lines keyed by messageId, and the two
    /// UNCHAINED prompt carriers — `queue-operation.content` (wrapper still
    /// attached) and `last-prompt.lastPrompt` (several copies per message).
    /// Also plants the two legitimate echoes that must NOT block a delete: an
    /// assistant line quoting the prompt and a tool_result line containing it.
    fn msg_fixture(extra: &str) -> String {
        [
            r#"{"type":"queue-operation","operation":"enqueue","content":"<ide_context openFile=\"C:\\a.java\" />\n\nfirst question","sessionId":"sess1"}"#,
            r#"{"type":"user","uuid":"u1","parentUuid":null,"message":{"role":"user","content":"first question"},"timestamp":"2026-07-30T10:00:00.000Z"}"#,
            r#"{"type":"file-history-snapshot","messageId":"u1","snapshot":{"messageId":"u1","trackedFileBackups":{"a.java":{"backupFileName":"blob1"}}}}"#,
            r#"{"type":"assistant","uuid":"a1","parentUuid":"u1","message":{"model":"claude-opus-5","content":[{"type":"text","text":"answering the first question"}]}}"#,
            r#"{"type":"last-prompt","leafUuid":"a1","lastPrompt":"first question","sessionId":"sess1"}"#,
            r#"{"type":"queue-operation","operation":"enqueue","content":"second question","sessionId":"sess1"}"#,
            r#"{"type":"user","uuid":"u2","parentUuid":"a1","message":{"role":"user","content":"second question"},"timestamp":"2026-07-30T10:01:00.000Z"}"#,
            r#"{"type":"attachment","uuid":"at1","parentUuid":"u2","attachment":{"type":"task_reminder"}}"#,
            r#"{"type":"file-history-snapshot","messageId":"u2","snapshot":{"messageId":"u2","trackedFileBackups":{"b.java":{"backupFileName":"blob2"}}}}"#,
            r#"{"type":"assistant","uuid":"a2","parentUuid":"at1","message":{"model":"claude-opus-5","content":[{"type":"text","text":"you asked: second question"}]}}"#,
            r#"{"type":"user","uuid":"tr1","parentUuid":"a2","message":{"role":"user","content":[{"type":"tool_result","tool_use_id":"t1","content":"grep hit: second question"}]}}"#,
            r#"{"type":"last-prompt","leafUuid":"tr1","lastPrompt":"second question","sessionId":"sess1"}"#,
            r#"{"type":"last-prompt","leafUuid":"a2","lastPrompt":"second question","sessionId":"sess1"}"#,
        ]
        .join("\n")
            + extra
            + "\n"
            + r#"{"type":"user","uuid":"u3","parentUuid":"tr1","message":{"role":"user","content":[{"type":"text","text":"third with image"},{"type":"image","source":{"type":"base64","media_type":"image/png","data":"QUJD"}}]},"timestamp":"2026-07-30T10:02:00.000Z"}"#
            + "\n"
            + r#"{"type":"assistant","uuid":"a3","parentUuid":"u3","message":{"model":"claude-opus-5","content":[{"type":"text","text":"ok"}]}}"#
            + "\n"
    }

    fn msg_home(tag: &str) -> (std::path::PathBuf, std::path::PathBuf) {
        let home = std::env::temp_dir().join(format!("claude-eclipse-msg-{tag}"));
        let dir = home.join(".claude").join("projects").join("C--msgtest");
        let _ = fs::remove_dir_all(&home);
        fs::create_dir_all(&dir).unwrap();
        (home, dir)
    }

    fn lines_of(p: &std::path::Path) -> Vec<serde_json::Value> {
        fs::read_to_string(p)
            .unwrap()
            .lines()
            .filter(|l| !l.trim().is_empty())
            .map(|l| serde_json::from_str(l).unwrap())
            .collect()
    }

    #[test]
    fn message_ids_track_the_rendered_user_bubbles() {
        let _env = ENV_LOCK.lock().unwrap();
        let (home, dir) = msg_home("ids");
        fs::write(dir.join("sess1.jsonl"), msg_fixture("")).unwrap();
        set_home(&home);

        let ids = super::message_ids(r"C:\msgtest", "sess1");
        let _ = fs::remove_dir_all(&home);

        // The image-bearing message (u3) counts; tool_result turns never do. Each
        // entry carries its text so the GUI can MATCH a bubble instead of guessing
        // by position.
        let v: serde_json::Value = serde_json::from_str(&ids).unwrap();
        let pairs: Vec<(&str, &str)> = v
            .as_array()
            .unwrap()
            .iter()
            .map(|m| (m["id"].as_str().unwrap(), m["text"].as_str().unwrap()))
            .collect();
        assert_eq!(
            pairs,
            vec![
                ("u1", "first question"),
                ("u2", "second question"),
                ("u3", "third with image"),
            ],
            "ids follow render order and carry their text: {ids}"
        );
    }

    #[test]
    fn delete_message_relinks_the_chain_and_sweeps_unchained_copies() {
        let _env = ENV_LOCK.lock().unwrap();
        let (home, dir) = msg_home("del");
        let path = dir.join("sess1.jsonl");
        fs::write(&path, msg_fixture("")).unwrap();
        set_home(&home);

        let res = super::delete_message(r"C:\msgtest", "sess1", "u2");
        let after = lines_of(&path);
        let _ = fs::remove_dir_all(&home);

        let v: serde_json::Value = serde_json::from_str(&res).unwrap();
        assert_eq!(v["ok"], serde_json::json!(true), "delete succeeded: {res}");
        // 1 queue-operation + 2 last-prompt copies of THIS prompt.
        assert_eq!(v["stripped"], serde_json::json!(3), "unchained copies cleared: {res}");

        // The message itself is gone.
        assert!(
            !after.iter().any(|l| l["uuid"] == serde_json::json!("u2")),
            "the user line was removed"
        );
        // Its child adopted its parent, so nothing dangles.
        let uuids: std::collections::HashSet<&str> =
            after.iter().filter_map(|l| l["uuid"].as_str()).collect();
        for l in &after {
            if let Some(p) = l["parentUuid"].as_str() {
                assert!(uuids.contains(p), "dangling parentUuid {p} in {l}");
            }
        }
        let at1 = after.iter().find(|l| l["uuid"] == serde_json::json!("at1")).unwrap();
        assert_eq!(at1["parentUuid"], serde_json::json!("a1"), "child re-linked past the hole");

        // Snapshots stay — RewindService forward-merges them for EARLIER messages.
        assert_eq!(
            after
                .iter()
                .filter(|l| l["type"] == serde_json::json!("file-history-snapshot"))
                .count(),
            2,
            "both snapshots preserved"
        );

        // This prompt's unchained copies are cleared…
        for l in &after {
            if l["type"] == serde_json::json!("queue-operation") {
                assert!(
                    l["content"].as_str().map_or(true, |c| !c.contains("second question")),
                    "queue copy cleared: {l}"
                );
            }
            if l["type"] == serde_json::json!("last-prompt") {
                assert!(
                    l["lastPrompt"].as_str().map_or(true, |c| !c.contains("second question")),
                    "last-prompt copy cleared: {l}"
                );
            }
        }
        // …while the OTHER message's copy is untouched.
        assert!(
            after.iter().any(|l| l["lastPrompt"] == serde_json::json!("first question")),
            "another message's bookkeeping is left alone"
        );
        // Legitimate echoes survive: they are not the message.
        assert!(
            after.iter().any(|l| l["type"] == serde_json::json!("assistant")
                && l["message"]["content"][0]["text"]
                    .as_str()
                    .map_or(false, |t| t.contains("second question"))),
            "an assistant quote is not treated as a copy"
        );
        assert!(
            after.iter().any(|l| l["uuid"] == serde_json::json!("tr1")),
            "a tool_result echoing the text is not treated as a copy"
        );
    }

    /// The guard that matters most: a prompt carrier this code does not know
    /// about must abort the delete rather than report a success that leaves the
    /// text on disk. Uses a fabricated line type standing in for whatever a
    /// future CLI adds.
    #[test]
    fn delete_message_aborts_on_an_unknown_prompt_carrier() {
        let _env = ENV_LOCK.lock().unwrap();
        let (home, dir) = msg_home("abort");
        let path = dir.join("sess1.jsonl");
        let planted = "\n".to_string()
            + r#"{"type":"future-prompt-log","promptText":"second question","sessionId":"sess1"}"#;
        let original = msg_fixture(&planted);
        fs::write(&path, &original).unwrap();
        set_home(&home);

        let res = super::delete_message(r"C:\msgtest", "sess1", "u2");
        let untouched = fs::read_to_string(&path).unwrap();
        let _ = fs::remove_dir_all(&home);

        let v: serde_json::Value = serde_json::from_str(&res).unwrap();
        assert!(
            v["error"].as_str().unwrap_or("").contains("future-prompt-log"),
            "names the offending line type: {res}"
        );
        assert_eq!(untouched, original, "nothing is written when the assertion fails");
    }

    #[test]
    fn delete_message_guards_bad_input() {
        let _env = ENV_LOCK.lock().unwrap();
        let (home, dir) = msg_home("guard");
        fs::write(dir.join("sess1.jsonl"), msg_fixture("")).unwrap();
        set_home(&home);

        let bad_session = super::delete_message(r"C:\msgtest", "../sess1", "u2");
        let bad_msg = super::delete_message(r"C:\msgtest", "sess1", "");
        let missing = super::delete_message(r"C:\msgtest", "sess1", "nope");
        let _ = fs::remove_dir_all(&home);

        for (label, res) in [
            ("traversal", bad_session),
            ("empty message id", bad_msg),
            ("absent message", missing),
        ] {
            let v: serde_json::Value = serde_json::from_str(&res).unwrap();
            assert!(v["error"].is_string(), "{label} rejected: {res}");
        }
    }
}
