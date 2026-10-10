use std::collections::{HashMap, HashSet};
use std::fs;
use std::io::{BufRead, BufReader, Read, Seek, SeekFrom, Write};
use std::path::{Path, PathBuf};
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
pub(crate) fn workspace_hash(workspace_root: &str) -> String {
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

/// A synthetic message the host sent (the browser-disconnected notice), as the CLI
/// records it: flagged `isMeta`, its text prefixed with this marker (CLI 2.1.266).
const NON_USER_MARKER: &str = "[MESSAGE FROM NON-USER SOURCE - NOT USER INPUT]";

fn is_non_user_notice(event: &serde_json::Value, content: &str) -> bool {
    event["isMeta"].as_bool().unwrap_or(false) && content.starts_with(NON_USER_MARKER)
}

/// A background-task notification the host injected while the conversation ran.
///
/// The CLI stores it as an ordinary user line whose whole text is the
/// `<task-notification>` block (and, in its queued form, carries
/// `commandMode: "task-notification"`). Nobody typed it, so reopening the
/// conversation must not show it as something the user said — which is exactly how
/// it looked: several raw XML blocks in a row where the messages should be.
fn is_task_notification(event: &serde_json::Value, content: &str) -> bool {
    const TAG: &str = "<task-notification>";
    const MODE: &str = "task-notification";
    content.trim_start().starts_with(TAG)
        || event["commandMode"].as_str() == Some(MODE)
        || event["attachment"]["commandMode"].as_str() == Some(MODE)
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
pub(crate) fn projects_dir(workspace_root: &str) -> Option<PathBuf> {
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
pub(crate) fn dirs_home() -> Option<PathBuf> {
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
// list_sessions  — every *.jsonl file's title and last time, from its two ends
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

        let Some((summary, _read)) = summarize(&path) else {
            continue;
        };
        sessions.push(serde_json::json!({
            "sessionId": session_id,
            "display": summary.display,
            "timestamp": summary.last_ts,
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

/// What the session list shows of one conversation.
struct Summary {
    /// Its title: the user's rename, else the AI title, else the first message.
    display: String,
    /// When it was last active, as the transcript writes a time.
    last_ts: String,
}

/// How much of a transcript's end is read first. The CLI writes a conversation's title
/// lines again after each turn, so the latest of them, and the last line with a time on
/// it, are nearly always inside this.
const LIST_TAIL: u64 = 64 * 1024;

/// How far back from the end a title is looked for when the first read holds none.
/// Most conversations without one are short and have no title line anywhere.
const LIST_TAIL_FOR_TITLE: u64 = 1024 * 1024;

/// The list's line for one transcript and how many bytes of it were read to get there,
/// or None for a file the list leaves out (nothing in it to name it by).
///
/// Only the two ends are read, as the CLI's own `/resume` list and the VS Code extension
/// read them: the end for the titles and the last time, and — for a conversation with no
/// title line — the start, as far as its first message. A long transcript is megabytes
/// of tool output in between, none of which the list shows; reading all of it on every
/// opening of the list is what used to keep the list seconds behind.
fn summarize(path: &Path) -> Option<(Summary, u64)> {
    let mut file = fs::File::open(path).ok()?;
    let size = file.metadata().ok()?.len();
    let mut read = 0;

    // Looked at again four times as far back until it holds a line with a time on it,
    // and either a title or a megabyte of the file (or all of it).
    let mut window = LIST_TAIL.min(size);
    let end = loop {
        let start = size - window;
        let bytes = read_span(&mut file, start, window)?;
        read += bytes.len() as u64;
        let end = TranscriptEnd::of(&bytes, start == 0);
        let titled = !end.custom_title.is_empty() || !end.ai_title.is_empty();
        if start == 0 || (!end.last_ts.is_empty() && (titled || window >= LIST_TAIL_FOR_TITLE)) {
            break end;
        }
        window = (window * 4).min(size);
    };

    // The user's rename beats the AI's title, which beats the first message.
    let display = if !end.custom_title.is_empty() {
        end.custom_title
    } else if !end.ai_title.is_empty() {
        end.ai_title
    } else {
        let (first, bytes) = first_message(&mut file);
        read += bytes;
        first
    };
    if display.is_empty() {
        return None;
    }
    // No line carried a time (a title-only stub): the file's own, so it still sorts.
    let last_ts = if end.last_ts.is_empty() {
        modified_iso8601(path).unwrap_or_default()
    } else {
        end.last_ts
    };
    Some((Summary { display, last_ts }, read))
}

/// `len` bytes of a file from `start` — fewer when the file ends sooner.
fn read_span(file: &mut fs::File, start: u64, len: u64) -> Option<Vec<u8>> {
    file.seek(SeekFrom::Start(start)).ok()?;
    let mut bytes = Vec::with_capacity(len as usize);
    Read::take(&mut *file, len).read_to_end(&mut bytes).ok()?;
    Some(bytes)
}

/// The top-level fields of a transcript line that the list goes by. A line's content,
/// however large, is passed over without being kept.
#[derive(serde::Deserialize)]
struct ListLine {
    #[serde(rename = "type")]
    kind: Option<serde_json::Value>,
    timestamp: Option<serde_json::Value>,
    #[serde(rename = "customTitle")]
    custom_title: Option<serde_json::Value>,
    #[serde(rename = "aiTitle")]
    ai_title: Option<serde_json::Value>,
}

/// What the end of a transcript says: its latest titles and the last time on any line.
#[derive(Default)]
struct TranscriptEnd {
    custom_title: String,
    ai_title: String,
    last_ts: String,
}

impl TranscriptEnd {
    /// Reads the last `bytes` of a transcript. They begin wherever the count fell,
    /// usually inside a line, unless `whole` says they are the entire file.
    fn of(bytes: &[u8], whole: bool) -> Self {
        // A first line cut in two says nothing, and it is not worth finding out
        // whether this one was.
        let lines = if whole {
            bytes
        } else {
            match bytes.iter().position(|&b| b == b'\n') {
                Some(cut) => &bytes[cut + 1..],
                None => &[],
            }
        };
        let text = String::from_utf8_lossy(lines);
        let mut end = TranscriptEnd::default();
        // Last line first: the first of each kind met this way is the file's last.
        for line in text.rsplit('\n') {
            let wanted = (end.last_ts.is_empty() && line.contains("\"timestamp\""))
                || (end.custom_title.is_empty() && line.contains("\"custom-title\""))
                || (end.ai_title.is_empty() && line.contains("\"ai-title\""));
            if !wanted {
                continue;
            }
            let Ok(event) = serde_json::from_str::<ListLine>(line) else {
                continue;   // the line the CLI is still writing, among others
            };
            let text_of = |v: &Option<serde_json::Value>| v.as_ref().and_then(|v| v.as_str()).unwrap_or_default().to_string();
            if end.last_ts.is_empty() {
                end.last_ts = text_of(&event.timestamp);
            }
            // An empty title is no title: an older line may still hold one.
            let kind = text_of(&event.kind);
            if kind == "custom-title" && end.custom_title.is_empty() {
                end.custom_title = text_of(&event.custom_title).chars().take(120).collect();
            } else if kind == "ai-title" && end.ai_title.is_empty() {
                end.ai_title = text_of(&event.ai_title).chars().take(120).collect();
            }
        }
        end
    }
}

/// What the first message that says something would title its conversation with, read
/// from the start of the transcript and no further than that message; and the bytes
/// that took. Empty when no message says anything.
fn first_message(file: &mut fs::File) -> (String, u64) {
    if file.seek(SeekFrom::Start(0)).is_err() {
        return (String::new(), 0);
    }
    let mut read = 0;
    for line in BufReader::new(file).lines() {
        let line = match line {
            Ok(l) => l,
            Err(_) => continue,
        };
        read += line.len() as u64 + 1;
        if !line.contains("\"user\"") {
            continue;
        }
        let Ok(event) = serde_json::from_str::<serde_json::Value>(&line) else {
            continue;
        };
        if event["type"].as_str() != Some("user") {
            continue;
        }
        let text = first_message_text(&event);
        if !text.is_empty() {
            return (text, read);
        }
    }
    (String::new(), read)
}

/// The text a `user` line would title its conversation with: the first 120 characters
/// of what was typed, without the editor context the plugin sends along. Empty for a
/// line that types nothing (a tool's result, a pasted image on its own).
fn first_message_text(event: &serde_json::Value) -> String {
    // Extract display text — first 120 chars of the user message content,
    // with any injected <ide_selection>/<ide_context> preamble removed so
    // the fallback title is the user's actual text, not the editor context.
    if let Some(content) = event["message"]["content"].as_str() {
        return strip_ide_preamble(content).chars().take(120).collect();
    }
    if let Some(blocks) = event["message"]["content"].as_array() {
        // A first message sent with a pasted image is stored as
        // content blocks — title the session from its text block
        // instead of falling through to a later message.
        for b in blocks {
            if b["type"].as_str() != Some("text") {
                continue;
            }
            let raw = b["text"].as_str().unwrap_or("");
            if is_browser_context(raw) || attached_file_path(raw).is_some() {
                continue;
            }
            let s = strip_ide_preamble(raw);
            if !s.trim().is_empty() {
                return s.chars().take(120).collect();
            }
        }
    }
    String::new()
}

/// A file's modified time as an ISO-8601 UTC string, so it string-sorts interleaved
/// with the real event timestamps (the PHP reader did the same via gmdate()).
fn modified_iso8601(path: &Path) -> Option<String> {
    let modified = fs::metadata(path).ok()?.modified().ok()?;
    let since = modified.duration_since(std::time::UNIX_EPOCH).ok()?;
    Some(epoch_to_iso8601(since.as_secs()))
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

/// Nearest valid UTF-8 char boundary at or BEFORE `idx` (never past it) — a portable
/// stand-in for the standard library's floor_char_boundary, which is still
/// nightly-only. Used to safely widen/narrow a byte-offset window computed against a
/// DIFFERENT string's positions (see search_session_content's snippet extraction).
fn floor_char_boundary(s: &str, idx: usize) -> usize {
    let mut i = idx.min(s.len());
    while i > 0 && !s.is_char_boundary(i) {
        i -= 1;
    }
    i
}

/// Nearest valid UTF-8 char boundary at or AFTER `idx` (never past the string's end).
fn ceil_char_boundary(s: &str, idx: usize) -> usize {
    let mut i = idx.min(s.len());
    while i < s.len() && !s.is_char_boundary(i) {
        i += 1;
    }
    i
}

/// @param generation this call's ordinal (the caller increments a per-session-search
///   counter each time the query changes) — used only for cancellation, unrelated to
///   the requestId round-tripped back to JS for discarding stale results.
/// @param own_messages_only restrict the scan to `type:"user"` events (the user's own
///   messages), skipping assistant turns entirely — a cheaper, narrower scope than
///   the full conversation.
pub fn search_session_content(workspace_root: &str, session_ids: &[String], query: &str, own_messages_only: bool, generation: u64) -> String {
    // A plain store, not fetch_max: semantically, every call IS the latest request,
    // full stop — "the latest caller wins" is the actual rule, not "the highest number
    // wins". fetch_max ratcheted this upward forever, so once ANY higher generation had
    // ever been seen, a legitimately newer but lower-numbered request (e.g. after
    // searchRequestId resets to 0 on a webview reload, while this native library and
    // its process-lifetime static stay loaded) could never win again and would silently
    // return zero matches — caught by a test failure whose real cause turned out to be
    // exactly this, not test-order flakiness.
    SEARCH_GENERATION.store(generation, Ordering::Relaxed);

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
                // pos/needle.len() are byte offsets into text.to_lowercase(), NOT into
                // `text` itself — case-folding some characters changes their UTF-8 byte
                // length (e.g. Turkish İ, German ẞ), so a straight `text[pos..]` slice
                // using the LOWERCASED string's offsets can land mid-character in the
                // ORIGINAL string and panic (confirmed: "ẞẞxquilt" searching "quilt"
                // panics with "byte index 5 is not a char boundary"). Across the JNI
                // boundary a Rust panic is undefined behavior (unwinding into a JVM
                // frame), not a catchable Java exception — this crashed the whole
                // Eclipse process with no JVM crash dump and nothing in dmesg, exactly
                // matching a real user report. Snapping start/end to the nearest valid
                // char boundary in `text` (not truncating to the lowercased string,
                // which would need re-deriving positions entirely) keeps the fix local
                // and the snippet's casing exactly as the user typed it.
                if let Some(pos) = text.to_lowercase().find(&needle) {
                    let raw_start = pos.saturating_sub(40).min(text.len());
                    let raw_end = (pos + needle.len() + 40).min(text.len());
                    let start = floor_char_boundary(text, raw_start);
                    let end = ceil_char_boundary(text, raw_end);
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

/// A subagent's (Task/Agent tool) own accumulated nested transcript — the on-disk
/// counterpart of chat.js's live `agentLogs` entries, so a reopened conversation's agents
/// keep their duration/tokens/prompt/tool-call list/"Open transcript" instead of losing it
/// the moment the webview holding the live version is torn down.
///
/// NOT reconstructed from the top-level transcript file: a subagent's own conversation is
/// NEVER multiplexed into its parent's `.jsonl` (confirmed on disk — there is no
/// parent_tool_use_id/parentToolUseId anywhere in it, live-stream-only). It gets its OWN
/// file instead, at `<projects_dir>/<session_id>/subagents/agent-<id>.jsonl`, with an
/// `agent-<id>.meta.json` sidecar whose `toolUseId` field is the top-level Agent tool_use's
/// own id — see `read_agent_log`, which finds and parses that file directly.
///
/// `items` matches chat.js's own shape exactly ({"kind":"text"|"thinking"|"tool", ...}) so
/// history.js can hand it to buildAgentLogItemEl with no translation. Unlike the live
/// version, an item's text/thinking blocks never need delta accumulation — every assistant
/// line in a SAVED transcript is already the complete, final message (`partial:true` lines
/// are skipped, same as the top-level reconstruction already does).
struct AgentLogAccum {
    items: Vec<serde_json::Value>,
    /// The subagent's OWN tool_use ids → index into `items`, so ITS tool_results (from
    /// the SAME dedicated file) can stamp the right step — a separate id space from the
    /// top-level `tool_idx` used elsewhere in this function.
    tool_idx: HashMap<String, usize>,
    tokens: u64,
    model: String,
    started_at: Option<String>,
    ended_at: Option<String>,
}

/// Finds and parses a subagent's own dedicated transcript file, given the PARENT
/// session's own projects directory/id and the Agent tool_use's own id (matched against
/// each `agent-*.meta.json`'s `toolUseId` field — see AgentLogAccum's doc comment for the
/// directory layout this assumes). Returns `None` when no matching subagent file exists
/// (an older conversation predating this feature, or a tool call that was never an
/// Agent/Task in the first place).
fn read_agent_log(projects_dir: &std::path::Path, session_id: &str, tool_use_id: &str) -> Option<AgentLogAccum> {
    let subagents_dir = projects_dir.join(session_id).join("subagents");
    let mut jsonl_path: Option<std::path::PathBuf> = None;
    for entry in fs::read_dir(&subagents_dir).ok()?.flatten() {
        let path = entry.path();
        let name = path.file_name().and_then(|n| n.to_str()).unwrap_or("");
        if !name.ends_with(".meta.json") {
            continue;
        }
        // A malformed sidecar only rules out its own agent, not the rest of the folder.
        let Some(meta) = fs::read_to_string(&path).ok()
            .and_then(|s| serde_json::from_str::<serde_json::Value>(&s).ok()) else { continue; };
        if meta["toolUseId"].as_str() == Some(tool_use_id) {
            let jsonl_name = format!("{}.jsonl", name.trim_end_matches(".meta.json"));
            jsonl_path = Some(subagents_dir.join(jsonl_name));
            break;
        }
    }
    let file = fs::File::open(jsonl_path?).ok()?;
    let mut accum = AgentLogAccum {
        items: Vec::new(), tool_idx: HashMap::new(), tokens: 0, model: String::new(),
        started_at: None, ended_at: None,
    };
    for line in BufReader::new(file).lines() {
        let line = match line {
            Ok(l) if !l.is_empty() => l,
            _ => continue,
        };
        let event: serde_json::Value = match serde_json::from_str(&line) {
            Ok(v) => v,
            Err(_) => continue,
        };
        if let Some(ts) = event["timestamp"].as_str() {
            if !ts.is_empty() {
                if accum.started_at.is_none() {
                    accum.started_at = Some(ts.to_string());
                }
                accum.ended_at = Some(ts.to_string());
            }
        }
        match event["type"].as_str() {
            Some("assistant") => {
                // Every line in a SAVED transcript is already the final message — unlike
                // the live stream there is no partial/delta form to skip, but a defensive
                // check costs nothing if one ever did slip through.
                if event.get("partial").and_then(|v| v.as_bool()).unwrap_or(false) {
                    continue;
                }
                if let Some(m) = event["message"]["model"].as_str() {
                    if !m.is_empty() {
                        accum.model = m.to_string();
                    }
                }
                let usage = &event["message"]["usage"];
                accum.tokens += usage["input_tokens"].as_u64().unwrap_or(0)
                    + usage["output_tokens"].as_u64().unwrap_or(0)
                    + usage["cache_creation_input_tokens"].as_u64().unwrap_or(0)
                    + usage["cache_read_input_tokens"].as_u64().unwrap_or(0);
                if let Some(content) = event["message"]["content"].as_array() {
                    for b in content {
                        match b["type"].as_str() {
                            Some("text") => {
                                if let Some(t) = b["text"].as_str() {
                                    if !t.is_empty() {
                                        accum.items.push(serde_json::json!({ "kind": "text", "text": t }));
                                    }
                                }
                            }
                            Some("thinking") => {
                                let t = b["thinking"].as_str().unwrap_or("");
                                if !t.is_empty() {
                                    accum.items.push(serde_json::json!({ "kind": "thinking", "text": t }));
                                }
                            }
                            Some("tool_use") => {
                                let name = b["name"].as_str().unwrap_or("tool");
                                let input = if b["input"].is_null() {
                                    serde_json::json!({})
                                } else {
                                    b["input"].clone()
                                };
                                accum.items.push(serde_json::json!({ "kind": "tool", "name": name, "input": input }));
                                if let Some(id) = b["id"].as_str() {
                                    accum.tool_idx.insert(id.to_string(), accum.items.len() - 1);
                                }
                            }
                            _ => {}
                        }
                    }
                }
            }
            Some("user") => {
                if let Some(blocks) = event["message"]["content"].as_array() {
                    for b in blocks {
                        if b["type"].as_str() != Some("tool_result") {
                            continue;
                        }
                        let tuid = b["tool_use_id"].as_str().unwrap_or("");
                        if tuid.is_empty() {
                            continue;
                        }
                        let is_err = b["is_error"].as_bool().unwrap_or(false);
                        let text = if is_err {
                            tool_error_summary(&flatten_result_content(b)).unwrap_or_default()
                        } else {
                            flatten_result_content(b)
                        };
                        if let Some(&idx) = accum.tool_idx.get(tuid) {
                            if let Some(obj) = accum.items.get_mut(idx).and_then(|v| v.as_object_mut()) {
                                obj.insert("status".into(),
                                    serde_json::Value::from(if is_err { "interrupted" } else { "done" }));
                                if is_err {
                                    obj.insert("errorText".into(), serde_json::Value::from(text.as_str()));
                                } else {
                                    obj.insert("resultText".into(), serde_json::Value::from(text.as_str()));
                                }
                            }
                        }
                    }
                }
            }
            _ => {}
        }
    }
    Some(accum)
}

pub fn load_session_history(workspace_root: &str, session_id: &str) -> String {
    serde_json::to_string(&history_items(workspace_root, session_id, false)).unwrap_or_else(|_| "[]".into())
}

/// The render items of a conversation. With `with_reply_ids`, each text item also
/// carries the transcript line it is (`id`) and when that was written (`at`) — kept
/// out of [`load_session_history`]'s own output, whose shape the page is built on.
fn history_items(workspace_root: &str, session_id: &str, with_reply_ids: bool) -> Vec<serde_json::Value> {
    let dir = match projects_dir(workspace_root) {
        Some(d) => d,
        None => return Vec::new(),
    };
    if session_id.is_empty() {
        return Vec::new();
    }

    let path = dir.join(format!("{}.jsonl", session_id));
    let file = match fs::File::open(&path) {
        Ok(f) => f,
        Err(_) => return Vec::new(),
    };
    let pass = if with_reply_ids { Pass::REPLY_IDS } else { Pass::RENDER };
    items_of(&dir, session_id, BufReader::new(file).lines().filter_map(Result::ok), pass)
}

/// What a pass over a transcript's lines is to produce.
#[derive(Clone, Copy)]
struct Pass {
    /// Each text item carries the transcript line it is (`id`) and that line's time (`at`).
    reply_ids: bool,
    /// The tool items get their outcome, and an Agent's its own log: the second half of
    /// the work, and for every Agent a file read of its own.
    tools: bool,
}

impl Pass {
    /// What [`load_session_history`] gives the page.
    const RENDER: Pass = Pass { reply_ids: false, tools: true };
    /// What [`reply_ids`] needs and no more.
    const REPLY_IDS: Pass = Pass { reply_ids: true, tools: false };
    /// Both at once, for [`open_session`].
    const OPEN: Pass = Pass { reply_ids: true, tools: true };
}

/// A message the user sent while Claude was working, as the CLI keeps it.
///
/// It is not written as a user line. The CLI queues it, and when it hands it over, at the
/// end of a tool call, it writes an attachment of type `queued_command` that carries the
/// words (`prompt`: a string, or text blocks with the editor's context in front). Only
/// when the turn was interrupted is a user line written as well, for the same words and
/// after them. So a conversation reopened without reading these lacks the messages that
/// were sent mid-turn, which is how a bubble that was on screen went missing.
struct QueuedPrompt {
    /// What was said, with the editor's context taken off.
    text: String,
    /// When it was sent, as the attachment says.
    ts: String,
    /// The attachment line's own `uuid`: the one id there is for this message. Not the
    /// attachment's `source_uuid`, which matches no line when the sender gave the message
    /// none (this plugin does not), and is the same for a delivery written twice.
    id: String,
    /// Where in the items it goes: the number of items there were when it was read.
    at: usize,
}

/// The words a queued-message attachment carries, as written: its `prompt` is a string, or
/// text blocks (with the editor's context in front, and a browser's). None for any line
/// that is not a `queued_command` in `prompt` mode, which is the only mode somebody talks in.
fn queued_prompt_raw(event: &serde_json::Value) -> Option<String> {
    let a = &event["attachment"];
    if event["type"].as_str() != Some("attachment")
        || a["type"].as_str() != Some("queued_command")
        || a["commandMode"].as_str() != Some("prompt")
    {
        return None;
    }
    match &a["prompt"] {
        serde_json::Value::String(s) => Some(s.clone()),
        serde_json::Value::Array(blocks) => Some(
            blocks
                .iter()
                .filter(|b| b["type"].as_str() == Some("text"))
                .filter_map(|b| b["text"].as_str())
                .filter(|t| !is_browser_context(t))
                .collect::<Vec<_>>()
                .join("
"),
        ),
        _ => None,
    }
}

impl QueuedPrompt {
    /// The queued message an attachment line carries, None when it is not one. Only the
    /// `prompt` mode is somebody talking: a background task's notice is queued the same
    /// way and, like its user line, is nothing the user typed.
    fn of(event: &serde_json::Value, at: usize) -> Option<QueuedPrompt> {
        let a = &event["attachment"];
        let raw = queued_prompt_raw(event)?;
        // A task notice can come in a prompt too, and a bare slash command is the page's
        // own to draw, so those are not messages to show here.
        let text = strip_ide_preamble(&raw).trim().to_string();
        if text.is_empty() || is_task_notification(&serde_json::Value::Null, &text) {
            return None;
        }
        let ts = a["timestamp"].as_str().or_else(|| event["timestamp"].as_str()).unwrap_or("").to_string();
        let id = event["uuid"].as_str().unwrap_or("").to_string();
        Some(QueuedPrompt { text, ts, id, at })
    }
}

/// How close, in milliseconds, a user line has to be to a queued message's send time for
/// the two to be the same message. Measured over every transcript on this machine: the
/// ones that are, come within a minute of the send. A longer gap is somebody saying the
/// same thing again.
const QUEUED_TWIN_MS: i64 = 120_000;

/// Remembers a user bubble's words and time, for [`splice_queued`].
fn note_typed(typed: &mut Vec<(String, i64)>, text: &str, ts: Option<&str>) {
    let at = ts.and_then(crate::promptcache::iso_ms).unwrap_or(i64::MIN);
    typed.push((strip_ide_preamble(text).trim().to_string(), at));
}

/// Puts the queued messages into `items`, each where it was handed over, and returns how
/// many went in. A message that also has a user line of its own, the same words within
/// [`QUEUED_TWIN_MS`] of when it was sent, is left out: that line is its bubble, and a
/// second would say it twice. Nothing is added twice for a delivery the CLI wrote more
/// than once either: the same words from the same send are one message.
fn splice_queued(items: &mut Vec<serde_json::Value>, queued: Vec<QueuedPrompt>, typed: &[(String, i64)]) -> usize {
    let mut keep: Vec<QueuedPrompt> = Vec::new();
    for q in queued {
        let sent = crate::promptcache::iso_ms(&q.ts);
        let has_line = typed.iter().any(|(text, at)| {
            *text == q.text && sent.map_or(true, |s| (at - s).abs() <= QUEUED_TWIN_MS)
        });
        let repeated = keep.iter().any(|k| k.text == q.text && k.ts == q.ts);
        if !has_line && !repeated {
            keep.push(q);
        }
    }
    let n = keep.len();
    // Last place first: an insertion moves what is after it, never what is before.
    for q in keep.into_iter().rev() {
        // The `id` is the attachment line, the one thing rewind, fork and delete can name
        // it by: it has no user line.
        let mut item = serde_json::json!({ "t": "user", "content": q.text });
        if !q.id.is_empty() {
            item["id"] = serde_json::Value::from(q.id);
        }
        if !q.ts.is_empty() {
            item["ts"] = serde_json::Value::from(q.ts);
        }
        items.insert(q.at.min(items.len()), item);
    }
    n
}

/// [`history_items`] over lines of a transcript, whichever part of it they are. `dir` is
/// the folder the transcript is in, where a subagent's own log is looked for.
fn items_of(dir: &Path, session_id: &str, lines: impl Iterator<Item = impl AsRef<str>>, pass: Pass) -> Vec<serde_json::Value> {
    let mut items: Vec<serde_json::Value> = Vec::new();
    // Messages the user sent while Claude was working, which the CLI records as
    // attachments rather than as user lines (see [`QueuedPrompt`]), and where in `items`
    // each one was handed over.
    let mut queued: Vec<QueuedPrompt> = Vec::new();
    // The user lines, by cleaned text and time, for telling a queued message that also
    // has a line of its own from one that has none.
    let mut typed: Vec<(String, i64)> = Vec::new();
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
    // tool_use id → the FULL result text for a SUCCESSFUL tool — separate map from
    // result_text above, which is error-only and pre-condensed to one line. This one
    // feeds the same "OUT" rendering (chat.js's renderToolOutput) the live path uses via
    // chat.rs's build_status_json-adjacent tool_result handler — without it, a reloaded/
    // resumed conversation showed tool input but never its output, since history.js's
    // reconstruction never had anything but errorText to hand makeToolLine.
    let mut result_success_text: HashMap<String, String> = HashMap::new();

    for line in lines {
        let line = line.as_ref();
        if line.is_empty() {
            continue;
        }
        let event: serde_json::Value = match serde_json::from_str(line) {
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
                    if is_non_user_notice(&event, c) || is_task_notification(&event, c) {
                        // Not something the user typed (the browser-disconnected notice,
                        // or a background-task notification): Claude's reply to it is what
                        // the conversation shows.
                    } else if event["isCompactSummary"].as_bool().unwrap_or(false) {
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
                        note_typed(&mut typed, c, event["timestamp"].as_str());
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
                    let mut documents: Vec<serde_json::Value> = Vec::new();
                    // Which document block of THIS message each chip came from, so a
                    // click can find it again in the transcript.
                    let mut doc_index = 0usize;
                    for b in blocks {
                        match b["type"].as_str() {
                            Some("text") => {
                                let s = b["text"].as_str().unwrap_or("");
                                // A file uploaded as a path is one of these blocks: it
                                // comes back as its chip, not as words in the bubble.
                                if let Some(path) = attached_file_path(s) {
                                    documents.push(serde_json::json!({
                                        "title": path.rsplit(['/', '\\']).next().unwrap_or(path),
                                        "encoding": "path",
                                        "path": path,
                                    }));
                                    continue;
                                }
                                // The browser blocks a `@browser` message carries are
                                // context for the model, not words the user typed.
                                if !s.is_empty() && !is_browser_context(s) {
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
                            // An uploaded file: only its name and where to find it
                            // again. The contents stay here — a transcript holds them
                            // in full, and pushing 30MB of base64 through JNI into the
                            // webview to redraw a chip would freeze the view. Clicking
                            // the chip asks for the file itself (session_document_file).
                            Some("document") => {
                                let src = &b["source"];
                                if src["data"].as_str().unwrap_or("").is_empty() {
                                    continue;
                                }
                                documents.push(serde_json::json!({
                                    "title": b["title"].as_str().unwrap_or(""),
                                    "media_type": src["media_type"].as_str().unwrap_or(""),
                                    "encoding": src["type"].as_str().unwrap_or(""),
                                    "index": doc_index,
                                }));
                                doc_index += 1;
                            }
                            _ => {}
                        }
                    }
                    if !text.is_empty() || !images.is_empty() || !documents.is_empty() {
                        let mut item = serde_json::json!({ "t": "user", "content": text });
                        if !images.is_empty() {
                            item["images"] = serde_json::Value::Array(images);
                        }
                        if !documents.is_empty() {
                            item["documents"] = serde_json::Value::Array(documents);
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
                        note_typed(&mut typed, &text, event["timestamp"].as_str());
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
                            } else {
                                // Full text, no condensing — chat.js caps/links-out to a
                                // full view for long content on its own (capIfOverflowing),
                                // same as the live path.
                                let full = flatten_result_content(b);
                                if !full.is_empty() {
                                    result_success_text.insert(tuid.to_string(), full);
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
                                    let mut item = serde_json::json!({
                                        "t": "text", "text": t, "model": model,
                                    });
                                    if pass.reply_ids {
                                        item["id"] = event["uuid"].clone();
                                        item["at"] = event["timestamp"].clone();
                                    }
                                    items.push(item);
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
                            let id = b["id"].as_str().unwrap_or("");
                            items.push(serde_json::json!({
                                // Its own tool_use id — needed so history.js can set
                                // data-tuid the same way the live path does (addToolLine),
                                // which is what an Agent/Task line's own reconstructed
                                // agentLog (below) gets matched up against.
                                "t": "tool", "name": name, "input": input, "model": model, "id": id,
                            }));
                            if !id.is_empty() {
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
            // A message sent while Claude was working. The CLI keeps it as the user's words
            // in an attachment at the point it handed them over, and writes no user line for
            // it (unless the turn was interrupted, when it writes one later).
            Some("attachment") => {
                if let Some(q) = QueuedPrompt::of(&event, items.len()) {
                    queued.push(q);
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
                // Where a conversation pulled down from claude.ai ends and the
                // local one continues. Written by teleport::run into the
                // transcript, so it survives into history like any other event.
                if event["subtype"].as_str() == Some("teleported_from_web") {
                    items.push(serde_json::json!({ "t": "teleported" }));
                }
            }
            _ => {}
        }
    }

    // What was queued goes in at the place it was handed over, which is where Claude
    // read it. Last place first, so each insertion leaves the places of the earlier ones
    // where they were counted. Nothing below this holds an index but the tool stamps,
    // and those are made against the order as it now stands.
    let inserted = splice_queued(&mut items, queued, &typed);
    if inserted > 0 {
        tool_idx.clear();
        for (i, it) in items.iter().enumerate() {
            if it["t"].as_str() == Some("tool") {
                if let Some(id) = it["id"].as_str() {
                    tool_idx.insert(id.to_string(), i);
                }
            }
        }
    }

    // Asked for the replies' lines, the caller wants none of what follows: it is all
    // about the tool items, and a subagent's log is a file read of its own.
    if !pass.tools {
        return items;
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
            // The successful tool's actual output — makeToolLine (chat.js) renders this
            // into an OUT box/result-list/checklist exactly like the live path does.
            if let Some(txt) = result_success_text.get(id) {
                obj.insert("resultText".into(), serde_json::Value::from(txt.as_str()));
            }
        }
    }

    // Attach each Agent/Task tool's own nested log onto its top-level item — read
    // straight from its own dedicated file (see read_agent_log's doc comment: a
    // subagent's conversation is never multiplexed into its PARENT's own transcript at
    // all, confirmed on disk). history.js hands this to chat.js's ensureAgentLog/
    // agentLogs so a reopened conversation's /agents popup (duration, tokens, model,
    // Prompt, Tool calls, "Open transcript") works the same as it did live.
    for (id, &idx) in &tool_idx {
        let is_agent = items.get(idx)
            .and_then(|v| v["name"].as_str())
            .map(|n| { let n = n.to_ascii_lowercase(); n == "agent" || n == "task" })
            .unwrap_or(false);
        if !is_agent {
            continue;
        }
        if let Some(accum) = read_agent_log(dir, session_id, id) {
            if let Some(obj) = items.get_mut(idx).and_then(|v| v.as_object_mut()) {
                obj.insert("agentLog".into(), serde_json::json!({
                    "items": accum.items,
                    "tokens": accum.tokens,
                    "model": accum.model,
                    "startedAt": accum.started_at,
                    "endedAt": accum.ended_at,
                }));
            }
        }
    }

    items
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

/// Writes one uploaded document out of a transcript to a file and returns its
/// path, or `""` when it isn't there. `index` counts document blocks within the
/// message `message_uuid`, as `load_session_history` numbered them.
///
/// The bytes never go to Java: a transcript keeps every uploaded file in full, so
/// the page gets a name and this locator, and only a click on the chip spends the
/// copy — of that one file, straight to disk for the OS to open.
pub fn session_document_file(
    workspace_root: &str,
    session_id: &str,
    message_uuid: &str,
    index: usize,
) -> String {
    let Some(dir) = projects_dir(workspace_root) else { return String::new() };
    if session_id.is_empty() || message_uuid.is_empty() {
        return String::new();
    }
    let Ok(file) = fs::File::open(dir.join(format!("{session_id}.jsonl"))) else {
        return String::new();
    };
    for line in BufReader::new(file).lines().map_while(Result::ok) {
        let Ok(event) = serde_json::from_str::<serde_json::Value>(&line) else { continue };
        if event["uuid"].as_str() != Some(message_uuid) {
            continue;
        }
        let Some(blocks) = event["message"]["content"].as_array() else { continue };
        let mut seen = 0usize;
        for b in blocks {
            if b["type"].as_str() != Some("document")
                || b["source"]["data"].as_str().unwrap_or("").is_empty()
            {
                continue;
            }
            if seen == index {
                return write_document_file(b);
            }
            seen += 1;
        }
    }
    String::new()
}

/// One document block's contents, written under the temp directory and named after
/// the file it came from. Returns the path, or `""` if anything failed.
fn write_document_file(block: &serde_json::Value) -> String {
    use base64::Engine as _;
    let src = &block["source"];
    let data = src["data"].as_str().unwrap_or("");
    let bytes = if src["type"].as_str() == Some("base64") {
        match base64::engine::general_purpose::STANDARD.decode(data) {
            Ok(b) => b,
            Err(_) => return String::new(),
        }
    } else {
        data.as_bytes().to_vec()
    };
    let title = block["title"].as_str().unwrap_or("");
    let safe: String = title
        .chars()
        .map(|c| if matches!(c, '/' | '\\' | ':' | '*' | '?' | '"' | '<' | '>' | '|') { '_' } else { c })
        .collect();
    let name = if safe.trim().is_empty() { "attachment".to_string() } else { safe };
    let dir = std::env::temp_dir().join(format!("claude-attachment-{}", std::process::id()));
    if fs::create_dir_all(&dir).is_err() {
        return String::new();
    }
    let out = dir.join(name);
    if fs::write(&out, bytes).is_err() {
        return String::new();
    }
    out.to_string_lossy().into_owned()
}

/// The `<browser_instruction>` and `<browser tabGroupId=…>` text blocks sent
/// alongside a message that mentions the browser.
fn is_browser_context(text: &str) -> bool {
    text.starts_with("<browser_instruction>") || text.starts_with("<browser tabGroupId=\"")
}

/// The path in an `<attached_file path="…" />` block — the whole block and nothing
/// else, so a message that merely quotes one stays the user's own words.
fn attached_file_path(text: &str) -> Option<&str> {
    let rest = text.strip_prefix("<attached_file path=\"")?;
    let (path, tail) = rest.split_once('"')?;
    (tail == " />" && !path.is_empty()).then_some(path)
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
///
/// A message written after a compaction also carries `"compactions"`: how many of the
/// conversation's compactions precede it. A view that opened the conversation from its
/// then last compaction on ([`open_session`], `cut.nth`) has not drawn what precedes that
/// one, and must not take such a line for a message it has just drawn that says the same
/// ("continue").
pub fn message_ids(workspace_root: &str, session_id: &str) -> String {
    let items: Vec<serde_json::Value> =
        serde_json::from_str(&load_session_history(workspace_root, session_id))
            .unwrap_or_default();
    let out: Vec<serde_json::Value> = after_compactions(&items)
        .filter(|(_, it)| it["t"].as_str() == Some("user"))
        .filter_map(|(compactions, it)| {
            let id = it["id"].as_str()?;
            let mut entry = serde_json::json!({ "id": id, "text": it["content"].as_str().unwrap_or("") });
            if compactions > 0 {
                entry["compactions"] = compactions.into();
            }
            Some(entry)
        })
        .collect();
    serde_json::to_string(&out).unwrap_or_else(|_| "[]".into())
}

/// The replies the GUI draws as text, in order, as
/// `[{"id":<uuid>,"text":<raw text>,"at":<the line's timestamp, "" if it has none>}]`.
/// From the same pass as `load_session_history`, so these are exactly the rendered
/// replies: an API error or a tool call is not among them.
///
/// The text ships with the id for the reason `message_ids`' does: a reply streamed
/// this run is on screen before the page knows its line, so the page matches on text.
/// `"compactions"` is on a reply for the reason it is on a message ([`message_ids`]).
pub fn reply_ids(workspace_root: &str, session_id: &str) -> String {
    let items = history_items(workspace_root, session_id, true);
    let out: Vec<serde_json::Value> = after_compactions(&items)
        .filter(|(_, it)| it["t"].as_str() == Some("text"))
        .filter_map(|(compactions, it)| {
            let id = it["id"].as_str()?;
            let mut entry = serde_json::json!({
                "id": id,
                "text": it["text"].as_str().unwrap_or(""),
                "at": it["at"].as_str().unwrap_or(""),
            });
            if compactions > 0 {
                entry["compactions"] = compactions.into();
            }
            Some(entry)
        })
        .collect();
    serde_json::to_string(&out).unwrap_or_else(|_| "[]".into())
}

/// A conversation's render items, each with the number of its compactions that precede it.
fn after_compactions(items: &[serde_json::Value]) -> impl Iterator<Item = (u64, &serde_json::Value)> {
    let mut compactions = 0;
    items.iter().map(move |it| {
        if it["t"].as_str() == Some("compact") {
            compactions += 1;
        }
        (compactions, it)
    })
}

// ---------------------------------------------------------------------------
// open_session — a saved conversation for the view to draw, in one reading
// ---------------------------------------------------------------------------

/// What [`open_session`] answers when there is nothing to read.
const NOTHING_OPENED: &str = r#"{"items":[],"note":"","cut":null,"earlier":null}"#;

/// A saved conversation for the view to draw, from one reading of its transcript: its
/// render items as [`load_session_history`] gives them, each reply also carrying the
/// transcript line it is (`id`) and that line's time (`at`); the note for reopening it
/// ([`crate::promptcache`]); and its last compaction (`cut`), when it has one.
///
/// With `from_last_compaction` the items begin at that compaction, which is all the view
/// shows of a conversation while it hides what was said before one. The lines before it
/// — two thirds of a long compacted transcript — are then not turned into items at all,
/// and `earlier` holds what the view still has to know of them: which replies they hold
/// (so a bookmark of one can be told from the others), and the model last used and
/// whether there was thinking (what the conversation is resumed with, when the part
/// that was drawn says neither). `earlier` is null when nothing was left out.
///
/// `cut.nth` says which of the conversation's compactions the last one is, counted from 1:
/// what [`message_ids`] and [`reply_ids`] call `compactions` on the lines that follow it.
///
/// `{"items":[…], "note":"…", "cut":{"uuid","at","nth"}|null,
///   "earlier":{"replies":[uuid…],"model":"…","thinking":bool}|null}`
pub fn open_session(workspace_root: &str, session_id: &str, from_last_compaction: bool) -> String {
    let Some((dir, bytes)) = read_transcript(workspace_root, session_id) else {
        return NOTHING_OPENED.into();
    };
    let mut found = compactions(&bytes);
    let nth = found.len();
    let cut = found.pop();
    let start = match &cut {
        Some(cut) if from_last_compaction => cut.offset,
        _ => 0,
    };
    let earlier = Some(EarlierPart::of(&bytes[..start])).filter(|part| part.said_something);
    serde_json::json!({
        "items": items_of(&dir, session_id, lines_of(&bytes[start..]), Pass::OPEN),
        "note": crate::promptcache::note_of_lines(lines_of(&bytes)),
        "cut": cut.map(|cut| serde_json::json!({ "uuid": cut.uuid, "at": cut.at, "nth": nth })),
        "earlier": earlier.map(|part| serde_json::json!({
            "replies": part.replies, "model": part.model, "thinking": part.thinking,
        })),
    })
    .to_string()
}

/// The render items of the part of a conversation before one of its compactions, the
/// one whose boundary line is `boundary_uuid` — for the view that opened it from that
/// compaction on and is now asked for the rest. `{"items":[…]}`, empty when the
/// conversation has no such compaction.
pub fn open_session_before(workspace_root: &str, session_id: &str, boundary_uuid: &str) -> String {
    let items = read_transcript(workspace_root, session_id)
        .filter(|_| !boundary_uuid.is_empty())
        .and_then(|(dir, bytes)| {
            let cut = compactions(&bytes).into_iter().find(|cut| cut.uuid == boundary_uuid)?;
            Some(items_of(&dir, session_id, lines_of(&bytes[..cut.offset]), Pass::OPEN))
        })
        .unwrap_or_default();
    serde_json::json!({ "items": items }).to_string()
}

/// A conversation's transcript, whole, and the folder it is in. None for a session id
/// that is not a plain name, or a transcript that cannot be read.
fn read_transcript(workspace_root: &str, session_id: &str) -> Option<(PathBuf, Vec<u8>)> {
    if !crate::bookmarks::plain_id(session_id) {
        return None;
    }
    let dir = projects_dir(workspace_root)?;
    let bytes = fs::read(dir.join(format!("{session_id}.jsonl"))).ok()?;
    Some((dir, bytes))
}

/// The lines of a transcript held in memory. What is not text is not a line of one.
fn lines_of(bytes: &[u8]) -> impl Iterator<Item = &str> {
    bytes
        .split(|&b| b == b'\n')
        .filter_map(|line| std::str::from_utf8(line).ok())
        .map(|line| line.trim_end_matches('\r'))
}

/// A compaction: where its boundary line starts in the transcript, and what names it.
struct Compaction {
    offset: usize,
    uuid: String,
    at: String,
}

/// The top-level fields of a line that say it is a compaction's boundary.
#[derive(serde::Deserialize)]
struct BoundaryLine {
    #[serde(rename = "type")]
    kind: Option<serde_json::Value>,
    subtype: Option<serde_json::Value>,
    uuid: Option<serde_json::Value>,
    timestamp: Option<serde_json::Value>,
}

/// A transcript's compactions, in order — the lines [`items_of`] makes a `compact` item
/// of, so that the last of these is the last of those.
fn compactions(bytes: &[u8]) -> Vec<Compaction> {
    let text = |value: &Option<serde_json::Value>| value.as_ref().and_then(|v| v.as_str()).unwrap_or_default().to_string();
    let mut found = Vec::new();
    let mut offset = 0;
    for raw in bytes.split(|&b| b == b'\n') {
        let start = offset;
        offset += raw.len() + 1;
        // Cheap reject first: a transcript has a handful of these among thousands of lines.
        let Some(line) = std::str::from_utf8(raw).ok().filter(|line| line.contains("\"compact_boundary\"")) else {
            continue;
        };
        let Ok(line) = serde_json::from_str::<BoundaryLine>(line) else {
            continue;
        };
        if text(&line.kind) == "system" && text(&line.subtype) == "compact_boundary" {
            found.push(Compaction { offset: start, uuid: text(&line.uuid), at: text(&line.timestamp) });
        }
    }
    found
}

/// What the view has to know of a part of a conversation it was not given the items of.
struct EarlierPart {
    /// The transcript lines of its replies.
    replies: Vec<String>,
    /// The model it last ran on, `""` when no reply names one.
    model: String,
    thinking: bool,
    /// Whether anybody said anything in it: a part with no message is no part.
    said_something: bool,
}

/// The fields of a reply line that [`EarlierPart`] is read from. What a block says is
/// passed over: only its kind is asked.
#[derive(serde::Deserialize)]
struct ReplyLine {
    #[serde(rename = "type")]
    kind: Option<serde_json::Value>,
    uuid: Option<serde_json::Value>,
    #[serde(rename = "isApiErrorMessage")]
    is_api_error: Option<serde_json::Value>,
    message: Option<ReplyMessage>,
}

#[derive(serde::Deserialize)]
struct ReplyMessage {
    model: Option<serde_json::Value>,
    content: Option<Vec<ReplyBlock>>,
}

#[derive(serde::Deserialize)]
struct ReplyBlock {
    #[serde(rename = "type")]
    kind: Option<serde_json::Value>,
}

impl EarlierPart {
    fn of(bytes: &[u8]) -> Self {
        let text = |value: &Option<serde_json::Value>| value.as_ref().and_then(|v| v.as_str()).unwrap_or_default().to_string();
        let mut part = EarlierPart { replies: Vec::new(), model: String::new(), thinking: false, said_something: false };
        for line in lines_of(bytes) {
            if !line.contains("\"assistant\"") {
                part.said_something |= line.contains("\"type\":\"user\"");
                continue;
            }
            // A line this cannot make out is a user's (their content may be plain text).
            let Ok(reply) = serde_json::from_str::<ReplyLine>(line) else {
                part.said_something |= line.contains("\"type\":\"user\"");
                continue;
            };
            if text(&reply.kind) != "assistant" {
                part.said_something |= text(&reply.kind) == "user";
                continue;
            }
            part.said_something = true;
            // An error the CLI wrote in a reply's place is drawn as one, not as a reply.
            if reply.is_api_error.as_ref().and_then(|v| v.as_bool()) == Some(true) {
                continue;
            }
            let Some(message) = reply.message else {
                continue;
            };
            let model = text(&message.model);
            if model.starts_with("claude-") {
                part.model = model;
            }
            let kinds: Vec<String> = message.content.iter().flatten().map(|block| text(&block.kind)).collect();
            part.thinking |= kinds.iter().any(|kind| kind == "thinking");
            if kinds.iter().any(|kind| kind == "text") {
                part.replies.push(text(&reply.uuid));
            }
        }
        part
    }
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

/// Any string anywhere in the line that is exactly `needle`, once the editor's context is off.
fn holds_exactly(v: &serde_json::Value, needle: &str) -> bool {
    match v {
        serde_json::Value::String(s) => strip_ide_preamble(s).trim() == needle,
        serde_json::Value::Array(a) => a.iter().any(|x| holds_exactly(x, needle)),
        serde_json::Value::Object(o) => o.values().any(|x| holds_exactly(x, needle)),
        _ => false,
    }
}

/// Permanently removes one message from a session transcript: a user line, or a message
/// sent while Claude was working (the attachment that hands it over, and any copy of it).
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

    // A message is a user line, or, when it was sent while Claude was working, the
    // attachment that hands it over (see `QueuedPrompt`): the one id the page has for it.
    let target = parsed
        .iter()
        .position(|e| {
            e.as_ref().map_or(false, |e| {
                e["uuid"].as_str() == Some(message_id)
                    && (e["type"].as_str() == Some("user") || queued_prompt_raw(e).is_some())
            })
        })
        .ok_or("That message is no longer in this conversation.")?;
    let queued = parsed[target].as_ref().map_or(false, |e| queued_prompt_raw(e).is_some());
    let text = parsed[target]
        .as_ref()
        .and_then(|e| if queued { queued_prompt_raw(e) } else { prompt_text(e) })
        .unwrap_or_default();
    let needle = strip_ide_preamble(&text).trim().to_string();

    // The lines that go. A queued message's delivery is sometimes written twice, and a
    // copy left behind would draw the bubble again, so every copy of it goes.
    let mut gone: std::collections::HashSet<usize> = std::collections::HashSet::new();
    gone.insert(target);
    if queued {
        let sent = parsed[target].as_ref().map(|e| e["attachment"]["timestamp"].clone());
        for (i, e) in parsed.iter().enumerate() {
            let Some(e) = e else { continue };
            if i != target
                && Some(e["attachment"]["timestamp"].clone()) == sent
                && queued_prompt_raw(e).map_or(false, |t| strip_ide_preamble(&t).trim() == needle)
            {
                gone.insert(i);
            }
        }
    }
    // Each removed line's parent, so what hung below one of them is handed up to the
    // nearest line that stays (the copies can hang below one another).
    let removed: std::collections::HashMap<String, serde_json::Value> = gone
        .iter()
        .filter_map(|&i| parsed[i].as_ref())
        .filter_map(|e| Some((e["uuid"].as_str()?.to_string(), e["parentUuid"].clone())))
        .collect();
    let survivor = |mut parent: serde_json::Value| {
        for _ in 0..=removed.len() {
            match parent.as_str().and_then(|u| removed.get(u)) {
                Some(up) => parent = up.clone(),
                None => break,
            }
        }
        parent
    };

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
    // A queued message sits in the middle of a turn, so the span between typed prompts
    // would be the whole turn, and a short message ("wait") would then match half of it.
    // Its own span is the queue log around its delivery: the enqueue when it was sent, and
    // the remove after, found by their exact words.
    let queue_op = |i: usize, op: &str| {
        parsed[i].as_ref().map_or(false, |e| {
            e["type"].as_str() == Some("queue-operation")
                && e["operation"].as_str() == Some(op)
                && e["content"].as_str().map_or(false, |c| strip_ide_preamble(c).trim() == needle)
        })
    };
    let last_gone = gone.iter().copied().max().unwrap_or(target);
    let (start, end) = if queued {
        (
            (0..target).rev().find(|&i| queue_op(i, "enqueue")).unwrap_or(target),
            ((last_gone + 1)..parsed.len())
                .find(|&i| queue_op(i, "remove"))
                .map_or(last_gone + 1, |i| i + 1),
        )
    } else {
        (
            (0..target).rev().find(|&i| is_boundary(i)).map_or(0, |i| i + 1),
            ((target + 1)..parsed.len()).find(|&i| is_boundary(i)).unwrap_or(parsed.len()),
        )
    };
    // How a bookkeeping field is told to be this message's: a user message's is compared
    // loosely (the queue log keeps the text wrapped in context), a queued one's exactly.
    let is_this = |s: &str| {
        if queued {
            strip_ide_preamble(s).trim() == needle
        } else {
            same_prompt(s, &text)
        }
    };

    let mut out: Vec<String> = Vec::with_capacity(lines.len());
    let mut span_check: Vec<serde_json::Value> = Vec::new();
    let mut stripped = 0usize;
    for (i, line) in lines.iter().enumerate() {
        if gone.contains(&i) {
            continue; // the message itself, and any copy of it
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
            // Children of a removed line adopt its parent, so the chain still closes
            // for `--resume`.
            for key in ["parentUuid", "logicalParentUuid", "leafUuid"] {
                if obj.get(key).and_then(|v| v.as_str()).map_or(false, |u| removed.contains_key(u)) {
                    let up = survivor(obj[key].clone());
                    obj.insert(key.to_string(), up);
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
                        .map_or(false, |s| is_this(s));
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

    if !needle.is_empty() {
        // A user message's words are looked for anywhere in a line; a queued message's only
        // as a whole string, since the span is narrow and a message whose words contain
        // these ("wait please" around "wait") is somebody else's.
        let holds = |e: &serde_json::Value| if queued { holds_exactly(e, &needle) } else { holds_prompt(e, &needle) };
        if let Some(bad) = span_check.iter().find(|e| holds(e)) {
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
            crate::launch::kill_process_tree(&mut child);
            return false;
        }
    } else {
        crate::launch::kill_process_tree(&mut child);
        return false;
    }

    // Watch stdout for the success control_response. The read loop alone can
    // block forever on a silent child (auth prompt, network stall), so a
    // watchdog kills the child at the deadline — that forces stdout EOF and
    // unblocks the loop.
    let stdout = match child.stdout.take() {
        Some(s) => s,
        None => {
            crate::launch::kill_process_tree(&mut child);
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
    crate::launch::kill_process_tree(&mut child);
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

// ---------------------------------------------------------------------------
// message_text_by_uuid — the local half of the Remote Control inbound lookup
// ---------------------------------------------------------------------------

/// The text of one transcript message, found by its `uuid`.
///
/// The local counterpart to [`crate::bridge::rc_lookup_message`], and the reason
/// an inbound Remote Control message no longer depends on the network. The CLI
/// writes every turn of a conversation to `~/.claude/projects/<hash>/<id>.jsonl`
/// **including the ones that arrived over the bridge** — verified against a real
/// bridge session, where a message typed on a phone is on disk as an ordinary
/// `user` line carrying the same uuid `command_lifecycle` announced it under.
/// (It is stamped with the *host's* `entrypoint`/`promptSource`, so those two
/// fields cannot be used to tell it from a locally typed one — the uuid can.)
///
/// Why this is the primary path: the API lookup spends an OAuth credential, and
/// on macOS that credential lives in the login Keychain, where a read can be
/// refused for reasons that have nothing to do with being signed in. Every such
/// failure rendered as *silence* — the message simply never appeared. Reading
/// the file the CLI already wrote costs no round trip and no credential.
///
/// Scans backwards: an inbound message is by definition near the end.
pub(crate) fn message_text_by_uuid(
    workspace_root: &str,
    session_id: &str,
    uuid: &str,
) -> Option<String> {
    if uuid.is_empty()
        || session_id.is_empty()
        || session_id.contains('/')
        || session_id.contains('\\')
        || session_id.contains("..")
    {
        return None;
    }
    let path = projects_dir(workspace_root)?.join(format!("{}.jsonl", session_id));
    let raw = fs::read_to_string(&path).ok()?;
    for line in raw.lines().rev() {
        if line.is_empty() || !line.contains(uuid) {
            continue; // cheap reject — parsing every line of a long transcript is not free
        }
        let v: serde_json::Value = match serde_json::from_str(line) {
            Ok(v) => v,
            Err(_) => continue,
        };
        if v["uuid"].as_str() != Some(uuid) {
            continue;
        }
        return crate::bridge::rc_incoming_text(&v);
    }
    None
}


// ---------------------------------------------------------------------------
// Tests
// ---------------------------------------------------------------------------

#[cfg(test)]
mod tests {
    use std::fs;

    // The tests below all repoint the home directory at a per-test fake home,
    // under EnvGuard: one lock shared with every test that changes the
    // environment, and the real home put back afterwards.
    use crate::test_support::EnvGuard;

    /// Hermetic fixture (same one the PHP reader was verified against): builds a
    /// fake home + ~/.claude/projects/<hash>/ under a temp dir and points the
    /// home env var at it, so the test runs anywhere. Asserts: custom-title beats
    /// ai-title (LAST custom-title wins), ai-title-only stubs are listed (with an
    /// mtime-derived sort key), untitled sessions fall back to the stripped first
    /// user message, and ordering is last-activity descending.
    /// The Remote Control inbound lookup, against the shape a real bridge session
    /// leaves on disk (taken from one: a message typed on a phone is an ordinary
    /// `user` line stamped with the HOST's entrypoint/promptSource, so the uuid is
    /// the only thing that identifies it).
    ///
    /// Also pins the three kinds that are not somebody talking - tool results,
    /// synthetic echoes and meta notices - because rendering any of them as an
    /// inbound bubble would put the CLI's own plumbing in the transcript.
    #[test]
    fn message_text_by_uuid_reads_a_bridge_message_from_the_transcript() {
        let mut env = EnvGuard::lock();
        let home = std::env::temp_dir().join("claude-eclipse-inbound-test-home");
        let root = r"C:\inbound";
        let dir = home.join(".claude").join("projects").join("C--inbound");
        let _ = fs::remove_dir_all(&home);
        fs::create_dir_all(&dir).unwrap();

        fs::write(dir.join("sess1.jsonl"), concat!(
            r#"{"type":"user","uuid":"local-1","promptSource":"sdk","entrypoint":"claude-eclipse-ide","message":{"role":"user","content":"typed here"}}"#, "\n",
            r#"{"type":"assistant","uuid":"a-1","message":{"content":[{"type":"text","text":"hi"}]}}"#, "\n",
            r#"{"type":"user","uuid":"tool-1","message":{"role":"user","content":[{"type":"tool_result","content":"ok"}]}}"#, "\n",
            r#"{"type":"user","uuid":"synth-1","isSynthetic":true,"message":{"role":"user","content":"compact summary"}}"#, "\n",
            r#"{"type":"user","uuid":"meta-1","isMeta":true,"message":{"role":"user","content":"a meta notice"}}"#, "\n",
            r#"{"type":"user","uuid":"phone-1","message":{"role":"user","content":"sent from my phone"}}"#, "\n",
            r#"{"type":"user","uuid":"phone-2","message":{"role":"user","content":[{"type":"text","text":"two"},{"type":"text","text":"lines"}]}}"#, "\n",
        )).unwrap();

        env.set_home(&home);

        let phone = super::message_text_by_uuid(root, "sess1", "phone-1");
        let blocks = super::message_text_by_uuid(root, "sess1", "phone-2");
        let local = super::message_text_by_uuid(root, "sess1", "local-1");
        let tool = super::message_text_by_uuid(root, "sess1", "tool-1");
        let synth = super::message_text_by_uuid(root, "sess1", "synth-1");
        let meta = super::message_text_by_uuid(root, "sess1", "meta-1");
        let missing = super::message_text_by_uuid(root, "sess1", "nope");
        let no_session = super::message_text_by_uuid(root, "gone", "phone-1");
        // The uuid appears as a SUBSTRING of a longer one - the cheap
        // line.contains() reject must not be mistaken for a match.
        let prefix = super::message_text_by_uuid(root, "sess1", "phone");
        let escape = super::message_text_by_uuid(root, "../sess1", "phone-1");

        let _ = fs::remove_dir_all(&home);

        assert_eq!(phone.as_deref(), Some("sent from my phone"));
        assert_eq!(blocks.as_deref(), Some("two\nlines"), "text blocks are joined");
        assert_eq!(local.as_deref(), Some("typed here"),
                   "a locally typed line is readable too - the caller decides which uuids to ask about");
        assert!(tool.is_none(), "tool results are the CLI's plumbing, not a message");
        assert!(synth.is_none(), "synthetic echoes are not somebody talking");
        assert!(meta.is_none(), "meta notices are not somebody talking");
        assert!(missing.is_none());
        assert!(no_session.is_none());
        assert!(prefix.is_none(), "a uuid prefix is not a uuid");
        assert!(escape.is_none(), "a session id may not climb out of the projects dir");
    }

    #[test]
    fn list_sessions_title_precedence_matches_php_reader() {
        let mut env = EnvGuard::lock();
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

        env.set_home(&home);

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
        let mut env = EnvGuard::lock();
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

        env.set_home(&home);
        let ids = vec!["aaaa1111".to_string(), "bbbb2222".to_string()];
        let json = super::search_session_content(root, &ids, "quilt", false, 1);
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
        let mut env = EnvGuard::lock();
        let home = std::env::temp_dir().join("claude-eclipse-session-search-own-home");
        let root = r"C:\searchownt";
        let dir = home.join(".claude").join("projects").join("C--searchownt");
        let _ = fs::remove_dir_all(&home);
        fs::create_dir_all(&dir).unwrap();

        fs::write(dir.join("aaaa1111.jsonl"), concat!(
            r#"{"type":"user","message":{"role":"user","content":"please help me"},"timestamp":"2026-07-01T10:00:00.000Z"}"#, "\n",
            r#"{"type":"assistant","message":{"content":[{"type":"text","text":"the quilt patch system works like this"}]},"timestamp":"2026-07-01T10:00:05.000Z"}"#, "\n",
        )).unwrap();

        env.set_home(&home);
        let ids = vec!["aaaa1111".to_string()];
        let full = super::search_session_content(root, &ids, "quilt", false, 1);
        let own_only = super::search_session_content(root, &ids, "quilt", true, 1);
        let _ = fs::remove_dir_all(&home);

        let full_v: serde_json::Value = serde_json::from_str(&full).unwrap();
        assert_eq!(full_v.as_array().unwrap().len(), 1, "full-conversation scope finds the assistant match: {full}");
        let own_v: serde_json::Value = serde_json::from_str(&own_only).unwrap();
        assert_eq!(own_v.as_array().unwrap().len(), 0, "own_messages_only must not match assistant text: {own_only}");
    }

    // ---- messages sent while Claude was working ----

    /// The history items of some transcript lines, with no tool stamping.
    fn items_of_lines(lines: &[&str]) -> Vec<serde_json::Value> {
        super::items_of(std::path::Path::new("."), "s", lines.iter(), super::Pass::REPLY_IDS)
    }
    fn user_line(text: &str, ts: &str) -> String {
        serde_json::json!({"type":"user","uuid":"u","timestamp":ts,"message":{"role":"user","content":text}}).to_string()
    }
    fn tool_use_line(id: &str) -> String {
        serde_json::json!({"type":"assistant","uuid":"a","message":{"content":[{"type":"tool_use","id":id,"name":"Bash","input":{}}]}}).to_string()
    }
    fn tool_result_line(id: &str) -> String {
        serde_json::json!({"type":"user","uuid":"r","message":{"role":"user","content":[{"type":"tool_result","tool_use_id":id,"content":"ok"}]}}).to_string()
    }
    fn queued_line(prompt: serde_json::Value, mode: &str, ts: &str, delivery: &str) -> String {
        serde_json::json!({"type":"attachment","uuid":"q","attachment":{
            "type":"queued_command","prompt":prompt,"source_uuid":"x","delivery_id":delivery,"commandMode":mode,"timestamp":ts}}).to_string()
    }
    fn contents(items: &[serde_json::Value]) -> Vec<String> {
        items.iter().map(|i| format!("{}:{}", i["t"].as_str().unwrap_or("?"), i["content"].as_str().unwrap_or(""))).collect()
    }

    #[test]
    fn a_message_sent_mid_turn_is_there_when_the_conversation_is_reopened() {
        // The real shape: no user line for it, only the attachment after the tool result.
        let lines = [
            user_line("start", "2026-10-09T12:50:00.000Z"),
            tool_use_line("t1"),
            tool_result_line("t1"),
            queued_line(serde_json::json!("wait"), "prompt", "2026-10-09T12:53:20.347Z", "d1"),
            tool_use_line("t2"),
        ];
        let refs: Vec<&str> = lines.iter().map(String::as_str).collect();
        let items = items_of_lines(&refs);
        let got = contents(&items);
        assert_eq!(got, vec!["user:start", "tool:", "user:wait", "tool:"], "it sits where Claude was handed it");
        assert_eq!(items[2]["ts"], "2026-10-09T12:53:20.347Z", "with the time it was sent, which the page can show");
    }

    #[test]
    fn a_queued_message_carries_the_id_of_its_attachment_line() {
        // Rewind, fork and delete need an id to act on; the attachment line has the only
        // one there is (its source_uuid matches no line when the sender gave none).
        let lines = [
            user_line("start", "2026-10-09T12:50:00.000Z"),
            tool_use_line("t1"),
            tool_result_line("t1"),
            queued_line(serde_json::json!("wait"), "prompt", "2026-10-09T12:53:20.347Z", "d1"),
        ];
        let refs: Vec<&str> = lines.iter().map(String::as_str).collect();
        let items = items_of_lines(&refs);
        let queued = items.iter().find(|i| i["content"] == "wait").expect("the queued bubble");
        assert_eq!(queued["id"], "q", "the attachment line's own uuid, not its source_uuid (\"x\")");
    }

    #[test]
    fn a_queued_message_with_a_user_line_of_its_own_is_that_line() {
        let lines = [
            queued_line(serde_json::json!("no dont do the hint"), "prompt", "2026-10-09T13:42:37.000Z", "d1"),
            user_line("no dont do the hint", "2026-10-09T13:42:36.000Z"),
        ];
        let refs: Vec<&str> = lines.iter().map(String::as_str).collect();
        let items = items_of_lines(&refs);
        assert_eq!(items.len(), 1);
        assert_eq!(items[0]["id"], "u", "the user line's id, which has the checkpoint");
    }

    #[test]
    fn the_editors_context_is_taken_off_a_queued_message() {
        let blocks = serde_json::json!([
            {"type":"text","text":"<ide_opened_file>The user opened the file C:/a.java in the IDE.</ide_opened_file>"},
            {"type":"text","text":"not above, below"},
        ]);
        let line = queued_line(blocks, "prompt", "2026-10-09T12:00:00.000Z", "d1");
        assert_eq!(contents(&items_of_lines(&[line.as_str()])), vec!["user:not above, below"]);
    }

    #[test]
    fn a_queued_message_with_a_user_line_of_its_own_is_shown_once() {
        // An interrupted turn writes both: the attachment, then the user line a moment later.
        let lines = [
            queued_line(serde_json::json!("no dont do the hint"), "prompt", "2026-10-09T13:42:37.000Z", "d1"),
            user_line("no dont do the hint", "2026-10-09T13:42:36.000Z"),
        ];
        let refs: Vec<&str> = lines.iter().map(String::as_str).collect();
        assert_eq!(contents(&items_of_lines(&refs)), vec!["user:no dont do the hint"]);
    }

    #[test]
    fn the_same_words_said_again_later_are_a_new_message() {
        // Minutes apart, so the later user line is not this message's twin.
        let lines = [
            queued_line(serde_json::json!("wait"), "prompt", "2026-10-09T12:00:00.000Z", "d1"),
            user_line("wait", "2026-10-09T12:30:00.000Z"),
        ];
        let refs: Vec<&str> = lines.iter().map(String::as_str).collect();
        assert_eq!(contents(&items_of_lines(&refs)), vec!["user:wait", "user:wait"]);
    }

    #[test]
    fn a_background_task_notice_is_not_something_the_user_said() {
        let notice = "<task-notification><task-id>1</task-id></task-notification>";
        let lines = [
            queued_line(serde_json::json!(notice), "task-notification", "2026-10-09T12:00:00.000Z", "d1"),
            queued_line(serde_json::json!(notice), "prompt", "2026-10-09T12:00:01.000Z", "d2"),
        ];
        let refs: Vec<&str> = lines.iter().map(String::as_str).collect();
        assert!(items_of_lines(&refs).is_empty());
    }

    #[test]
    fn a_delivery_the_cli_wrote_twice_is_one_message() {
        let a = queued_line(serde_json::json!("wait"), "prompt", "2026-10-09T12:00:00.000Z", "d1");
        let refs = [a.as_str(), a.as_str()];
        assert_eq!(contents(&items_of_lines(&refs)), vec!["user:wait"]);
    }

    #[test]
    fn queued_messages_leave_the_tool_stamps_on_the_right_tools() {
        // Splicing a bubble in shifts the items after it; the tools' outcomes follow them.
        let lines = [
            tool_use_line("t1"),
            queued_line(serde_json::json!("wait"), "prompt", "2026-10-09T12:00:00.000Z", "d1"),
            tool_use_line("t2"),
            tool_result_line("t2"),
        ];
        let refs: Vec<&str> = lines.iter().map(String::as_str).collect();
        let items = super::items_of(std::path::Path::new("."), "s", refs.iter(), super::Pass::RENDER);
        let status = |id: &str| items.iter().find(|i| i["id"] == id).map(|i| i["status"].as_str().unwrap_or("").to_string());
        assert_eq!(status("t2").as_deref(), Some("done"), "t2 had a result");
        assert_eq!(status("t1").as_deref(), Some("interrupted"), "t1 had none");
    }

    /// Regression test for a real crash: certain characters (German ẞ, Turkish İ, …)
    /// change UTF-8 byte length when lowercased, so a match position found via
    /// text.to_lowercase().find() does not correspond to the same byte offset in the
    /// ORIGINAL text — slicing the original at that offset can land mid-character and
    /// panic ("byte index N is not a char boundary"). Across the JNI boundary that
    /// panic is undefined behavior (an unwind into a JVM-owned native frame), which
    /// crashed a live user's whole Eclipse process with no JVM crash dump and nothing
    /// in dmesg — exactly the kind of failure that looks like it isn't ours. Confirmed
    /// via a standalone repro before this test existed: "ẞẞxquilt" searching "quilt"
    /// panicked at the exact line this function now guards.
    #[test]
    fn search_session_content_snippet_survives_case_folding_byte_length_change() {
        let mut env = EnvGuard::lock();
        let home = std::env::temp_dir().join("claude-eclipse-session-search-unicode-home");
        let root = r"C:\searchunicode";
        let dir = home.join(".claude").join("projects").join("C--searchunicode");
        let _ = fs::remove_dir_all(&home);
        fs::create_dir_all(&dir).unwrap();

        // ẞ (U+1E9E, LATIN CAPITAL LETTER SHARP S) lowercases to "ß" — same character
        // count but a different UTF-8 byte length, which is what desynchronizes the
        // lowercased string's match offset from the original string's byte layout.
        fs::write(dir.join("aaaa1111.jsonl"), concat!(
            r#"{"type":"user","message":{"role":"user","content":"ẞẞxquilt talk"},"timestamp":"2026-07-01T10:00:00.000Z"}"#, "\n",
        )).unwrap();

        env.set_home(&home);
        let ids = vec!["aaaa1111".to_string()];
        // Must not panic — that's the entire point of this test.
        let json = super::search_session_content(root, &ids, "quilt", false, 1);
        let _ = fs::remove_dir_all(&home);

        let v: serde_json::Value = serde_json::from_str(&json).unwrap();
        let arr = v.as_array().unwrap();
        assert_eq!(arr.len(), 1, "the match is still found despite the preceding multibyte characters: {json}");
        assert!(arr[0]["snippet"].as_str().unwrap().to_lowercase().contains("quilt"));
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
        let mut env = EnvGuard::lock();
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

        env.set_home(&home);

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
            {"t":"tool","name":"mcp__eclipse__askUserQuestion","input":{"q":"Which color?"},"model":"claude-fable-5","id":"toolu_01","status":"done","resultText":"  The user answered: Blue"},
            {"t":"answered","text":"Blue"},
            {"t":"tool","name":"Edit","input":{"file_path":"C:\\x.java","old_string":"a","new_string":"b"},"model":"claude-opus-4-8","id":"toolu_02","status":"interrupted"}
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

    /// Background-task notifications are injected into the transcript as ordinary
    /// user lines. Reopening a conversation showed them as the user's own messages —
    /// several raw <task-notification> XML blocks in a row where the conversation
    /// should be.
    #[test]
    fn load_session_hides_background_task_notifications() {
        let mut env = EnvGuard::lock();
        let home = std::env::temp_dir().join("claude-eclipse-session-tasknote-home");
        let root = r"C:\tasknotews";
        let dir = home.join(".claude").join("projects").join("C--tasknotews");
        let _ = fs::remove_dir_all(&home);
        fs::create_dir_all(&dir).unwrap();
        // Both shapes the CLI writes, copied from a real transcript.
        fs::write(dir.join("sessn.jsonl"), concat!(
            r#"{"type":"user","message":{"role":"user","content":"hi"},"timestamp":"2026-09-16T17:19:00.000Z"}"#, "\n",
            r#"{"type":"user","message":{"role":"user","content":"<task-notification>\n<task-id>b0mc0q2r4</task-id>\n<summary>Monitor event</summary>\n</task-notification>"},"timestamp":"2026-09-16T17:19:37.000Z"}"#, "\n",
            r#"{"type":"user","message":{"role":"user","content":"<task-notification>\n<status>completed</status>\n</task-notification>"},"commandMode":"task-notification","timestamp":"2026-09-16T17:19:41.000Z"}"#, "\n",
            r#"{"type":"assistant","message":{"model":"claude-opus-5","content":[{"type":"text","text":"noted."}]},"timestamp":"2026-09-16T17:19:45.000Z"}"#, "\n",
        )).unwrap();

        env.set_home(&home);
        let loaded = super::load_session_history(root, "sessn");
        let _ = fs::remove_dir_all(&home);

        let got: serde_json::Value = serde_json::from_str(&loaded).unwrap();
        let want: serde_json::Value = serde_json::from_str(r#"[
            {"t":"user","content":"hi","ts":"2026-09-16T17:19:00.000Z"},
            {"t":"text","text":"noted.","model":"claude-opus-5"}
        ]"#).unwrap();
        assert_eq!(got, want, "a notification nobody typed must not come back as a message");
    }


    #[test]
    fn load_session_hides_the_browser_disconnected_notice() {
        let mut env = EnvGuard::lock();
        let home = std::env::temp_dir().join("claude-eclipse-session-notice-home");
        let root = r"C:\noticews";
        let dir = home.join(".claude").join("projects").join("C--noticews");
        let _ = fs::remove_dir_all(&home);
        fs::create_dir_all(&dir).unwrap();
        fs::write(dir.join("sessn.jsonl"), concat!(
            r#"{"type":"user","message":{"role":"user","content":"hi"},"timestamp":"2026-09-15T08:40:00.000Z"}"#, "\n",
            r#"{"type":"user","message":{"role":"user","content":"[MESSAGE FROM NON-USER SOURCE - NOT USER INPUT]\n[Browser disconnected: The browser connection has been closed. Browser tools are no longer available.]"},"isMeta":true,"timestamp":"2026-09-15T08:40:23.282Z"}"#, "\n",
            r#"{"type":"assistant","message":{"model":"claude-opus-5","content":[{"type":"text","text":"Chrome has disconnected."}]},"timestamp":"2026-09-15T08:40:25.000Z"}"#, "\n",
        )).unwrap();

        env.set_home(&home);
        let loaded = super::load_session_history(root, "sessn");
        let _ = fs::remove_dir_all(&home);

        let got: serde_json::Value = serde_json::from_str(&loaded).unwrap();
        let want: serde_json::Value = serde_json::from_str(r#"[
            {"t":"user","content":"hi","ts":"2026-09-15T08:40:00.000Z"},
            {"t":"text","text":"Chrome has disconnected.","model":"claude-opus-5"}
        ]"#).unwrap();
        assert_eq!(got, want);
    }

    /// A compacted session reloads as a "Compacted chat" marker + expandable
    /// summary: the compact_boundary system line becomes a t:"compact" item
    /// (camelCase compactMetadata → trigger/preTokens/postTokens) and the
    /// isCompactSummary user line becomes t:"compact_summary" — never a user
    /// bubble. Fixture shapes captured from a real CLI 2.1.177 /compact run.
    #[test]
    fn load_session_surfaces_compact_boundary_and_summary() {
        let mut env = EnvGuard::lock();
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

        env.set_home(&home);
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
        let mut env = EnvGuard::lock();
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

        env.set_home(&home);
        let loaded = super::load_session_history(root, "sessi");
        let listed = super::list_sessions(root);
        let _ = fs::remove_dir_all(&home);

        let got: serde_json::Value = serde_json::from_str(&loaded).unwrap();
        let want: serde_json::Value = serde_json::from_str(r#"[
            {"t":"user","content":"<ide_context openFile=\"C:\\a\\B.java\" />\n\nwhat is this",
             "images":[{"media_type":"image/jpeg","data":"QUJD"}],"ts":"2026-07-30T01:00:00.000Z"},
            {"t":"tool","name":"Read","input":{"file_path":"a.txt"},"status":"done","model":"claude-opus-4-8","id":"t1","resultText":"file contents"},
            {"t":"text","text":"a screenshot","model":"claude-opus-4-8"}
        ]"#).unwrap();
        assert_eq!(got, want, "pasted-image session render items");

        // The list title comes from the text block, with the IDE preamble stripped.
        let sessions: serde_json::Value = serde_json::from_str(&listed).unwrap();
        assert_eq!(sessions[0]["display"], serde_json::json!("what is this"));
    }

    /// The editor context sent as its own leading text block: the reloaded item
    /// keeps it (the GUI builds the chip from it) and the list title skips it.
    #[test]
    fn editor_context_block_stays_out_of_the_title() {
        let mut env = EnvGuard::lock();
        let home = std::env::temp_dir().join("claude-eclipse-session-ctxblock-home");
        let root = r"C:\ctxws";
        let dir = home.join(".claude").join("projects").join("C--ctxws");
        let _ = fs::remove_dir_all(&home);
        fs::create_dir_all(&dir).unwrap();

        fs::write(dir.join("sessc.jsonl"), concat!(
            r#"{"type":"user","message":{"role":"user","content":[{"type":"text","text":"<ide_opened_file>The user opened the file /C:/a/B.java in the IDE. This may or may not be related to the current task.</ide_opened_file>"},{"type":"text","text":"what is this"}]},"timestamp":"2026-07-30T01:00:00.000Z"}"#, "\n",
            r#"{"type":"user","message":{"role":"user","content":[{"type":"text","text":"<ide_selection>The user selected the lines 3 to 5 from /C:/a/B.java:\nint x;\n\nThis may or may not be related to the current task.</ide_selection>"},{"type":"text","text":"fix it"}]},"timestamp":"2026-07-30T01:00:05.000Z"}"#, "\n",
        )).unwrap();

        env.set_home(&home);
        let loaded = super::load_session_history(root, "sessc");
        let listed = super::list_sessions(root);
        let _ = fs::remove_dir_all(&home);

        let got: serde_json::Value = serde_json::from_str(&loaded).unwrap();
        let want: serde_json::Value = serde_json::from_str(r#"[
            {"t":"user","content":"<ide_opened_file>The user opened the file /C:/a/B.java in the IDE. This may or may not be related to the current task.</ide_opened_file>\nwhat is this","ts":"2026-07-30T01:00:00.000Z"},
            {"t":"user","content":"<ide_selection>The user selected the lines 3 to 5 from /C:/a/B.java:\nint x;\n\nThis may or may not be related to the current task.</ide_selection>\nfix it","ts":"2026-07-30T01:00:05.000Z"}
        ]"#).unwrap();
        assert_eq!(got, want, "editor-context session render items");

        let sessions: serde_json::Value = serde_json::from_str(&listed).unwrap();
        assert_eq!(sessions[0]["display"], serde_json::json!("what is this"));
    }

    /// An uploaded file comes back as a document its chip can redraw and open, and
    /// the browser blocks a `@browser` message carries stay out of the bubble and
    /// the title.
    #[test]
    fn load_session_restores_documents_and_hides_browser_blocks() {
        let mut env = EnvGuard::lock();
        let home = std::env::temp_dir().join("claude-eclipse-session-docs-home");
        let root = r"C:\docws";
        let dir = home.join(".claude").join("projects").join("C--docws");
        let _ = fs::remove_dir_all(&home);
        fs::create_dir_all(&dir).unwrap();

        fs::write(dir.join("sessd.jsonl"), concat!(
            r#"{"type":"user","uuid":"u-doc","message":{"role":"user","content":[{"type":"text","text":"<browser_instruction># x</browser_instruction>"},{"type":"text","text":"read this @browser:new_tab"},{"type":"document","source":{"type":"text","media_type":"text/plain","data":"hello"},"title":"notes.txt"},{"type":"text","text":"<browser tabGroupId=\"1\" tabId=\"2\"></browser>"}]},"timestamp":"2026-09-14T01:00:00.000Z"}"#, "\n",
        )).unwrap();

        env.set_home(&home);
        let loaded = super::load_session_history(root, "sessd");
        let listed = super::list_sessions(root);
        // The chip's click fetches the contents the render item deliberately left behind.
        let file = super::session_document_file(root, "sessd", "u-doc", 0);
        let second = super::session_document_file(root, "sessd", "u-doc", 1);
        let unknown = super::session_document_file(root, "sessd", "nope", 0);
        let _ = fs::remove_dir_all(&home);

        let got: serde_json::Value = serde_json::from_str(&loaded).unwrap();
        let want: serde_json::Value = serde_json::from_str(r#"[
            {"t":"user","content":"read this @browser:new_tab","id":"u-doc",
             "documents":[{"title":"notes.txt","media_type":"text/plain","encoding":"text","index":0}],
             "ts":"2026-09-14T01:00:00.000Z"}
        ]"#).unwrap();
        assert_eq!(got, want, "document session render items");

        assert!(file.ends_with("notes.txt"), "{file}");
        assert_eq!(fs::read_to_string(&file).unwrap(), "hello");
        assert!(second.is_empty(), "no second document");
        assert!(unknown.is_empty(), "unknown message");
        let _ = fs::remove_file(&file);

        let sessions: serde_json::Value = serde_json::from_str(&listed).unwrap();
        assert_eq!(sessions[0]["display"], serde_json::json!("read this @browser:new_tab"));
    }

    /// A file uploaded as a path comes back as its chip; a message that merely quotes
    /// the block keeps it as the user's own words.
    #[test]
    fn load_session_restores_path_attachments() {
        let mut env = EnvGuard::lock();
        let home = std::env::temp_dir().join("claude-eclipse-session-paths-home");
        let root = r"C:\pathws";
        let dir = home.join(".claude").join("projects").join("C--pathws");
        let _ = fs::remove_dir_all(&home);
        fs::create_dir_all(&dir).unwrap();

        fs::write(dir.join("sessp.jsonl"), concat!(
            r#"{"type":"user","message":{"role":"user","content":[{"type":"text","text":"what is in here"},{"type":"text","text":"<attached_file path=\"C:\\x\\big.zip\" />"}]},"timestamp":"2026-09-15T01:00:00.000Z"}"#, "\n",
            r#"{"type":"user","message":{"role":"user","content":[{"type":"text","text":"I wrote <attached_file path=\"C:\\x\\big.zip\" /> myself"}]},"timestamp":"2026-09-15T01:01:00.000Z"}"#, "\n",
        )).unwrap();

        env.set_home(&home);
        let loaded = super::load_session_history(root, "sessp");
        let listed = super::list_sessions(root);
        let _ = fs::remove_dir_all(&home);

        let got: serde_json::Value = serde_json::from_str(&loaded).unwrap();
        let want: serde_json::Value = serde_json::from_str(r#"[
            {"t":"user","content":"what is in here",
             "documents":[{"title":"big.zip","encoding":"path","path":"C:\\x\\big.zip"}],
             "ts":"2026-09-15T01:00:00.000Z"},
            {"t":"user","content":"I wrote <attached_file path=\"C:\\x\\big.zip\" /> myself",
             "ts":"2026-09-15T01:01:00.000Z"}
        ]"#).unwrap();
        assert_eq!(got, want, "path attachment session render items");

        let sessions: serde_json::Value = serde_json::from_str(&listed).unwrap();
        assert_eq!(sessions[0]["display"], serde_json::json!("what is in here"));
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
        let mut env = EnvGuard::lock();
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

        env.set_home(&home);
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
    /// the red dot but no text; a successful one carries its full output as
    /// resultText instead (a DIFFERENT field — see result_text vs
    /// result_success_text above — so makeToolLine can render it as an OUT box
    /// rather than the muted one-line error note).
    #[test]
    fn load_session_attaches_error_text_to_failed_tools() {
        let mut env = EnvGuard::lock();
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

        env.set_home(&home);
        let loaded = super::load_session_history(root, "sesst");
        let _ = fs::remove_dir_all(&home);

        let got: serde_json::Value = serde_json::from_str(&loaded).unwrap();
        let want: serde_json::Value = serde_json::from_str(r#"[
            {"t":"user","content":"go","ts":"2026-09-04T01:00:00.000Z"},
            {"t":"tool","name":"Read","input":{"file_path":"C:\\nope.java"},"model":"claude-opus-4-8","id":"toolu_a","status":"interrupted","errorText":"File does not exist. Note: your current working directory is C:\\ws"},
            {"t":"tool","name":"Edit","input":{"file_path":"C:\\x.java"},"model":"claude-opus-4-8","id":"toolu_b","status":"interrupted"},
            {"t":"tool","name":"Read","input":{"file_path":"C:\\ok.java"},"model":"claude-opus-4-8","id":"toolu_c","status":"done","resultText":"contents"}
        ]"#).unwrap();
        assert_eq!(got, want, "failed tools carry their reason; declined ones stay quiet; successful ones carry their output");
    }

    /// A subagent's own nested transcript lives in its own dedicated file, never
    /// multiplexed into its parent's — confirmed against a real on-disk conversation (no
    /// parent_tool_use_id/parentToolUseId anywhere in the parent file; a background Agent
    /// call's own steps only showed up under
    /// `<session_id>/subagents/agent-<id>.jsonl`, matched to the top-level tool_use via
    /// that file's `agent-<id>.meta.json` sidecar's `toolUseId` field). This fixture
    /// reproduces that exact layout.
    #[test]
    fn load_session_reads_agent_log_from_its_own_subagent_file() {
        let mut env = EnvGuard::lock();
        let home = std::env::temp_dir().join("claude-eclipse-session-agentlog-home");
        let root = r"C:\agentws";
        let dir = home.join(".claude").join("projects").join("C--agentws");
        let _ = fs::remove_dir_all(&home);
        fs::create_dir_all(&dir).unwrap();

        // The parent conversation: just the Agent tool_use and an early ack tool_result
        // (a background call's own real work never appears here — that is the whole
        // point of this fixture).
        fs::write(dir.join("sessa.jsonl"), concat!(
            r#"{"type":"assistant","message":{"model":"claude-sonnet-5","content":[{"type":"tool_use","id":"toolu_top","name":"Agent","input":{"description":"Count to 3","prompt":"count to 3","subagent_type":"Explore","run_in_background":true}}]},"timestamp":"2026-09-16T13:54:40.400Z"}"#, "\n",
            r#"{"type":"user","message":{"role":"user","content":[{"type":"tool_result","tool_use_id":"toolu_top","content":"Agent running in the background"}]},"timestamp":"2026-09-16T13:54:40.410Z"}"#, "\n",
        )).unwrap();

        // The subagent's own dedicated conversation, in its own subdirectory.
        let sub_dir = dir.join("sessa").join("subagents");
        fs::create_dir_all(&sub_dir).unwrap();
        fs::write(sub_dir.join("agent-abc123.meta.json"),
            r#"{"agentType":"Explore","description":"Count to 3","toolUseId":"toolu_top","spawnDepth":1,"requestShape":"background"}"#,
        ).unwrap();
        fs::write(sub_dir.join("agent-abc123.jsonl"), concat!(
            r#"{"type":"user","isSidechain":true,"agentId":"abc123","message":{"role":"user","content":"count to 3"},"timestamp":"2026-09-16T13:54:40.450Z"}"#, "\n",
            r#"{"type":"assistant","isSidechain":true,"agentId":"abc123","message":{"model":"claude-sonnet-5","usage":{"input_tokens":100,"output_tokens":50},"content":[{"type":"tool_use","id":"toolu_sub1","name":"Bash","input":{"command":"echo 1 2 3"}}]},"timestamp":"2026-09-16T13:54:40.460Z"}"#, "\n",
            r#"{"type":"user","isSidechain":true,"agentId":"abc123","message":{"role":"user","content":[{"type":"tool_result","tool_use_id":"toolu_sub1","content":"1 2 3"}]},"timestamp":"2026-09-16T13:54:40.470Z"}"#, "\n",
            r#"{"type":"assistant","isSidechain":true,"agentId":"abc123","message":{"model":"claude-sonnet-5","usage":{"input_tokens":80,"output_tokens":40},"content":[{"type":"text","text":"Counted to 3."}]},"timestamp":"2026-09-16T13:54:40.480Z"}"#, "\n",
        )).unwrap();

        env.set_home(&home);
        let loaded = super::load_session_history(root, "sessa");
        let _ = fs::remove_dir_all(&home);

        let got: serde_json::Value = serde_json::from_str(&loaded).unwrap();
        let tool_item = got.as_array().unwrap().iter()
            .find(|it| it["t"] == "tool" && it["id"] == "toolu_top")
            .expect("the top-level Agent tool item");
        let log = &tool_item["agentLog"];
        assert_eq!(log["tokens"], 270, "sums usage across every completed message in the subagent's own file (100+50+80+40)");
        assert_eq!(log["model"], "claude-sonnet-5");
        assert_eq!(log["startedAt"], "2026-09-16T13:54:40.450Z", "the subagent file's own FIRST timestamp, not the parent's");
        assert_eq!(log["endedAt"], "2026-09-16T13:54:40.480Z", "the subagent file's own LAST timestamp");
        // The initial plain "user" message (the subagent's own starting prompt) produces
        // no item at all — only its own text/thinking/tool_use content does — so this is
        // exactly [tool_use Bash (stamped done+resultText), text "Counted to 3."].
        let items = log["items"].as_array().unwrap();
        assert_eq!(items.len(), 2);
        assert_eq!(items[0]["kind"], "tool");
        assert_eq!(items[0]["name"], "Bash");
        assert_eq!(items[0]["status"], "done");
        assert_eq!(items[0]["resultText"], "1 2 3");
        assert_eq!(items[1]["kind"], "text");
        assert_eq!(items[1]["text"], "Counted to 3.");
    }

    #[test]
    fn load_session_reconstructs_tool_status() {
        let mut env = EnvGuard::lock();
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

        env.set_home(&home);
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
        let mut env = EnvGuard::lock();
        let home = std::env::temp_dir().join("claude-eclipse-session-del-home");
        let root = r"C:\deltest";
        let dir = home.join(".claude").join("projects").join("C--deltest");
        let _ = fs::remove_dir_all(&home);
        fs::create_dir_all(&dir).unwrap();
        fs::write(dir.join("victim.jsonl"), "{}\n").unwrap();

        env.set_home(&home);

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
    fn the_id_list_names_a_queued_message() {
        let mut env = EnvGuard::lock();
        let (home, dir) = msg_home("queued-ids");
        let lines = [
            user_line("start", "2026-10-09T12:50:00.000Z"),
            tool_use_line("t1"),
            tool_result_line("t1"),
            queued_line(serde_json::json!("wait"), "prompt", "2026-10-09T12:53:20.347Z", "d1"),
        ];
        fs::write(dir.join("sess1.jsonl"), lines.join("\n")).unwrap();
        env.set_home(&home);
        let ids = super::message_ids(r"C:\msgtest", "sess1");
        let _ = fs::remove_dir_all(&home);

        let v: serde_json::Value = serde_json::from_str(&ids).unwrap();
        let list = v.as_array().unwrap();
        assert_eq!(list.len(), 2, "{ids}");
        assert_eq!((list[0]["id"].as_str(), list[0]["text"].as_str()), (Some("u"), Some("start")));
        assert_eq!((list[1]["id"].as_str(), list[1]["text"].as_str()), (Some("q"), Some("wait")));
    }

    #[test]
    fn message_ids_track_the_rendered_user_bubbles() {
        let mut env = EnvGuard::lock();
        let (home, dir) = msg_home("ids");
        fs::write(dir.join("sess1.jsonl"), msg_fixture("")).unwrap();
        env.set_home(&home);

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
    fn reply_ids_track_the_rendered_replies_and_leave_the_loaders_output_as_it_was() {
        let mut env = EnvGuard::lock();
        let (home, dir) = msg_home("replies");
        let extra = [
            "",
            r#"{"type":"assistant","uuid":"a-stamped","parentUuid":"tr1","message":{"model":"claude-opus-5","content":[{"type":"text","text":"written at a known time"}]},"timestamp":"2026-07-30T10:01:30.000Z"}"#,
            r#"{"type":"assistant","uuid":"a-error","isApiErrorMessage":true,"message":{"content":[{"type":"text","text":"API Error: 529"}]}}"#,
            r#"{"type":"assistant","uuid":"a-tool","message":{"model":"claude-opus-5","content":[{"type":"tool_use","id":"t9","name":"Read","input":{}}]}}"#,
            r#"{"type":"assistant","uuid":"a-thinking","message":{"model":"claude-opus-5","content":[{"type":"thinking","thinking":"hmm"}]}}"#,
        ]
        .join("\n");
        fs::write(dir.join("sess1.jsonl"), msg_fixture(&extra)).unwrap();
        env.set_home(&home);

        let ids = super::reply_ids(r"C:\msgtest", "sess1");
        let loaded = super::load_session_history(r"C:\msgtest", "sess1");
        let _ = fs::remove_dir_all(&home);

        let v: serde_json::Value = serde_json::from_str(&ids).unwrap();
        let rows: Vec<(&str, &str, &str)> = v
            .as_array()
            .unwrap()
            .iter()
            .map(|r| (r["id"].as_str().unwrap(), r["text"].as_str().unwrap(), r["at"].as_str().unwrap()))
            .collect();
        assert_eq!(
            rows,
            vec![
                ("a1", "answering the first question", ""),
                ("a2", "you asked: second question", ""),
                ("a-stamped", "written at a known time", "2026-07-30T10:01:30.000Z"),
                ("a3", "ok", ""),
            ],
            "only replies, in render order, each with its line: {ids}"
        );
        // What the page renders from is untouched: a text item has no id of its own.
        let items: serde_json::Value = serde_json::from_str(&loaded).unwrap();
        let texts: Vec<&serde_json::Value> =
            items.as_array().unwrap().iter().filter(|it| it["t"] == "text").collect();
        assert_eq!(texts.len(), 4);
        for it in texts {
            assert!(it.get("id").is_none() && it.get("at").is_none(), "{it}");
        }
    }

    #[test]
    fn delete_message_relinks_the_chain_and_sweeps_unchained_copies() {
        let mut env = EnvGuard::lock();
        let (home, dir) = msg_home("del");
        let path = dir.join("sess1.jsonl");
        fs::write(&path, msg_fixture("")).unwrap();
        env.set_home(&home);

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

    /// A transcript with a message sent mid-turn, in the shape the CLI writes it: the queue
    /// log's enqueue when it is sent, the attachment that hands it over (written twice), the
    /// log's remove after. Another message, "wait please", is queued in the same turn, and
    /// a reply hangs below the second copy of the delivery.
    fn queued_fixture(extra: Option<serde_json::Value>) -> String {
        let stamp = "2026-10-10T07:10:38.563Z";
        let delivery = |uuid: &str, parent: &str| {
            serde_json::json!({"type":"attachment","uuid":uuid,"parentUuid":parent,"timestamp":stamp,
                "attachment":{"type":"queued_command","prompt":"wait","source_uuid":"src","commandMode":"prompt","timestamp":stamp}})
        };
        let mut v = vec![
            serde_json::json!({"type":"user","uuid":"u1","parentUuid":null,"message":{"role":"user","content":"start"}}),
            serde_json::json!({"type":"assistant","uuid":"a1","parentUuid":"u1","message":{"content":[{"type":"tool_use","id":"t1","name":"Bash","input":{}}]}}),
            serde_json::json!({"type":"user","uuid":"r1","parentUuid":"a1","message":{"role":"user","content":[{"type":"tool_result","tool_use_id":"t1","content":"ok"}]}}),
            serde_json::json!({"type":"queue-operation","operation":"enqueue","content":"wait"}),
            serde_json::json!({"type":"queue-operation","operation":"enqueue","content":"wait please"}),
            delivery("q1", "r1"),
            delivery("q2", "q1"),
            serde_json::json!({"type":"queue-operation","operation":"remove","content":"wait"}),
            serde_json::json!({"type":"assistant","uuid":"a2","parentUuid":"q2","message":{"content":[{"type":"text","text":"ok, waiting"}]}}),
            serde_json::json!({"type":"last-prompt","lastPrompt":"start"}),
        ];
        if let Some(line) = extra {
            v.insert(6, line);
        }
        v.iter().map(|l| l.to_string()).collect::<Vec<_>>().join("\n")
    }

    #[test]
    fn deleting_a_queued_message_takes_every_copy_and_hands_what_hung_below_to_a_line_that_stays() {
        let mut env = EnvGuard::lock();
        let (home, dir) = msg_home("del-queued");
        let path = dir.join("sess1.jsonl");
        fs::write(&path, queued_fixture(None)).unwrap();
        env.set_home(&home);

        let res = super::delete_message(r"C:\msgtest", "sess1", "q1");
        let after = lines_of(&path);
        let _ = fs::remove_dir_all(&home);

        let v: serde_json::Value = serde_json::from_str(&res).unwrap();
        assert_eq!(v["ok"], serde_json::json!(true), "{res}");
        assert_eq!(v["stripped"], serde_json::json!(2), "its enqueue and its remove: {res}");
        // Both copies of the delivery are gone, so the bubble is not drawn again on reopen.
        assert!(!after.iter().any(|l| l["attachment"]["type"] == "queued_command"), "{after:?}");
        // The reply that hung below the second copy now hangs below what the first one did.
        let a2 = after.iter().find(|l| l["uuid"] == "a2").unwrap();
        assert_eq!(a2["parentUuid"], "r1");
        let uuids: std::collections::HashSet<&str> = after.iter().filter_map(|l| l["uuid"].as_str()).collect();
        for l in &after {
            if let Some(p) = l["parentUuid"].as_str() {
                assert!(uuids.contains(p), "dangling parentUuid {p} in {l}");
            }
        }
        // Only this message's words were taken out of the queue log.
        let ops: Vec<(&str, bool)> = after
            .iter()
            .filter(|l| l["type"] == "queue-operation")
            .map(|l| (l["operation"].as_str().unwrap(), l.get("content").is_some()))
            .collect();
        assert_eq!(ops, vec![("enqueue", false), ("enqueue", true), ("remove", false)], "{after:?}");
        let please = after.iter().find(|l| l["content"] == "wait please").expect("the other message's enqueue is left alone");
        assert_eq!(please["operation"], "enqueue");
        assert!(after.iter().any(|l| l["lastPrompt"] == "start"), "another message's bookkeeping is left alone");
    }

    #[test]
    fn a_queued_message_that_leaves_its_words_in_an_unknown_line_aborts_the_delete() {
        let mut env = EnvGuard::lock();
        let (home, dir) = msg_home("del-queued-abort");
        let path = dir.join("sess1.jsonl");
        let fixture = queued_fixture(Some(serde_json::json!({"type":"mystery_carrier","note":"wait"})));
        fs::write(&path, &fixture).unwrap();
        env.set_home(&home);

        let res = super::delete_message(r"C:\msgtest", "sess1", "q1");
        let on_disk = fs::read_to_string(&path).unwrap();
        let _ = fs::remove_dir_all(&home);

        let v: serde_json::Value = serde_json::from_str(&res).unwrap();
        assert!(v["error"].as_str().map_or(false, |e| e.contains("mystery_carrier")), "{res}");
        assert_eq!(on_disk, fixture, "the transcript was left untouched");
    }

    #[test]
    fn a_background_tasks_notice_is_not_a_message_to_delete() {
        let mut env = EnvGuard::lock();
        let (home, dir) = msg_home("del-queued-notice");
        let path = dir.join("sess1.jsonl");
        let notice = serde_json::json!({"type":"attachment","uuid":"n1","parentUuid":null,
            "attachment":{"type":"queued_command","prompt":"<task-notification/>","commandMode":"task-notification"}});
        fs::write(&path, notice.to_string()).unwrap();
        env.set_home(&home);

        let res = super::delete_message(r"C:\msgtest", "sess1", "n1");
        let _ = fs::remove_dir_all(&home);

        let v: serde_json::Value = serde_json::from_str(&res).unwrap();
        assert!(v["error"].as_str().map_or(false, |e| e.contains("no longer in this conversation")), "{res}");
    }

    /// The guard that matters most: a prompt carrier this code does not know
    /// about must abort the delete rather than report a success that leaves the
    /// text on disk. Uses a fabricated line type standing in for whatever a
    /// future CLI adds.
    #[test]
    fn delete_message_aborts_on_an_unknown_prompt_carrier() {
        let mut env = EnvGuard::lock();
        let (home, dir) = msg_home("abort");
        let path = dir.join("sess1.jsonl");
        let planted = "\n".to_string()
            + r#"{"type":"future-prompt-log","promptText":"second question","sessionId":"sess1"}"#;
        let original = msg_fixture(&planted);
        fs::write(&path, &original).unwrap();
        env.set_home(&home);

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
        let mut env = EnvGuard::lock();
        let (home, dir) = msg_home("guard");
        fs::write(dir.join("sess1.jsonl"), msg_fixture("")).unwrap();
        env.set_home(&home);

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

    // ---- the session list reads only the two ends of a transcript ----

    const KB: u64 = 1024;

    /// A reply line of exactly `len` bytes with its newline, written at 10:00 on the 1st.
    fn filler(n: usize, len: usize, text: &str) -> String {
        let start = format!(r#"{{"type":"assistant","uuid":"f{n}","timestamp":"2026-07-01T10:00:00.000Z","message":{{"role":"assistant","content":[{{"type":"text","text":""#);
        let end = "\"}]}}\n";
        let room = len - start.len() - end.len();
        let mut said = String::new();
        while said.len() + text.len() <= room {
            said.push_str(text);
        }
        while said.len() < room {
            said.push('.');
        }
        format!("{start}{said}{end}")
    }

    /// A transcript: `head` lines, about `middle` bytes of replies, then `tail` (each
    /// given whole, newline included). Answers its folder, its path and its size.
    fn long_transcript(head: &[String], middle: u64, tail: &[String]) -> (tempfile::TempDir, std::path::PathBuf, u64) {
        let tmp = tempfile::tempdir().unwrap();
        let path = tmp.path().join("session.jsonl");
        let mut out = head.concat();
        let mut n = 0;
        while (out.len() as u64) < head.concat().len() as u64 + middle {
            out.push_str(&filler(n, 400, "said at length "));
            n += 1;
        }
        out.push_str(&tail.concat());
        fs::write(&path, &out).unwrap();
        let size = out.len() as u64;
        (tmp, path, size)
    }

    fn opening(text: &str) -> String {
        format!(r#"{{"type":"user","uuid":"u1","message":{{"role":"user","content":"{text}"}},"timestamp":"2026-07-01T09:59:00.000Z"}}"#) + "\n"
    }

    fn reply_at(time: &str) -> String {
        format!(r#"{{"type":"assistant","uuid":"last","message":{{"role":"assistant","content":[{{"type":"text","text":"the last thing said"}}]}},"timestamp":"{time}"}}"#) + "\n"
    }

    fn ai_title(title: &str) -> String {
        format!(r#"{{"type":"ai-title","aiTitle":"{title}","sessionId":"s"}}"#) + "\n"
    }

    fn custom_title(title: &str) -> String {
        format!(r#"{{"type":"custom-title","customTitle":"{title}","sessionId":"s"}}"#) + "\n"
    }

    /// `(title, last time, bytes read)` of a transcript as the list shows it.
    fn summary_of(path: &std::path::Path) -> (String, String, u64) {
        let (summary, read) = super::summarize(path).expect("a conversation the list shows");
        (summary.display, summary.last_ts, read)
    }

    #[test]
    fn the_filler_lines_are_the_size_they_say() {
        assert_eq!(filler(7, 400, "said at length ").len(), 400);
        assert_eq!(filler(123, 200, "é✓").len(), 200);
        assert!(serde_json::from_str::<serde_json::Value>(filler(0, 200, "é✓").trim_end()).is_ok());
    }

    #[test]
    fn a_long_conversation_is_listed_from_its_two_ends() {
        let (_tmp, path, size) = long_transcript(
            &[opening("what the conversation opened with")],
            2 * KB * KB,
            &[reply_at("2026-07-03T08:00:00.000Z"), ai_title("Named by the AI")],
        );

        let (title, last, read) = summary_of(&path);

        assert_eq!((title.as_str(), last.as_str()), ("Named by the AI", "2026-07-03T08:00:00.000Z"));
        assert!(size > 2 * KB * KB && read <= 64 * KB, "read {read} of {size} bytes");
    }

    #[test]
    fn a_rename_at_the_end_beats_the_ai_title_written_after_it() {
        let (_tmp, path, size) = long_transcript(
            &[opening("what the conversation opened with")],
            2 * KB * KB,
            &[reply_at("2026-07-03T08:00:00.000Z"), custom_title("Renamed by hand"), ai_title("Named by the AI")],
        );

        let (title, _, read) = summary_of(&path);

        assert_eq!(title, "Renamed by hand");
        assert!(read <= 64 * KB, "read {read} of {size} bytes");
    }

    #[test]
    fn the_last_time_is_found_behind_a_long_end_that_carries_none() {
        // 150 KB of lines with no time on them after the last one that has one.
        let untimed: String = (0..1500)
            .map(|n| format!(r#"{{"type":"file-history-snapshot","messageId":"m{n:04}","isSnapshotUpdate":true,"snapshot":{{"trackedFileBackups":{{}}}}}}"#) + "\n")
            .collect();
        assert!(untimed.len() as u64 > 150 * KB);
        let (_tmp, path, size) = long_transcript(
            &[opening("what the conversation opened with")],
            2 * KB * KB,
            &[reply_at("2026-07-03T08:00:00.000Z"), untimed, ai_title("Named by the AI")],
        );

        let (title, last, read) = summary_of(&path);

        assert_eq!((title.as_str(), last.as_str()), ("Named by the AI", "2026-07-03T08:00:00.000Z"));
        assert!(read <= 400 * KB, "read {read} of {size} bytes");
    }

    #[test]
    fn a_conversation_with_no_title_is_named_from_its_first_message_without_reading_all_of_it() {
        let (_tmp, path, size) = long_transcript(
            &[opening("what the conversation opened with")],
            3 * KB * KB,
            &[reply_at("2026-07-03T08:00:00.000Z")],
        );

        let (title, last, read) = summary_of(&path);

        assert_eq!((title.as_str(), last.as_str()), ("what the conversation opened with", "2026-07-03T08:00:00.000Z"));
        assert!(size > 3 * KB * KB && read <= 1500 * KB, "read {read} of {size} bytes");
    }

    #[test]
    fn a_title_line_the_first_look_cuts_in_two_is_still_found() {
        // The title line is 600 bytes and 65,000 bytes of replies follow it, so the last
        // 64 KB of the file begins in the middle of it.
        let long_title = "T".repeat(560);
        let after: String = (0..325).map(|n| filler(n, 200, "x")).collect();
        assert_eq!(after.len(), 65_000);
        assert!(ai_title(&long_title).len() > 600);
        let (_tmp, path, size) = long_transcript(
            &[opening("what the conversation opened with")],
            2 * KB * KB,
            &[ai_title(&long_title), after],
        );

        let (title, _, read) = summary_of(&path);

        assert_eq!(title, "T".repeat(120), "a title is its first 120 characters");
        assert!(read <= 400 * KB, "read {read} of {size} bytes");
    }

    #[test]
    fn a_short_conversation_is_read_once() {
        let (_tmp, path, size) = long_transcript(
            &[opening("what the conversation opened with")],
            0,
            &[reply_at("2026-07-03T08:00:00.000Z"), ai_title("Named by the AI")],
        );

        let (title, last, read) = summary_of(&path);

        assert_eq!((title.as_str(), last.as_str()), ("Named by the AI", "2026-07-03T08:00:00.000Z"));
        assert_eq!(read, size);
    }

    #[test]
    fn text_of_more_than_one_byte_at_the_edge_of_a_look_is_no_obstacle() {
        // Replies full of two- and three-byte characters, at 97 different offsets, so
        // some look is bound to begin inside one.
        for shift in 0..97 {
            let tmp = tempfile::tempdir().unwrap();
            let path = tmp.path().join("session.jsonl");
            let mut out = opening("what the conversation opened with");
            out.push_str(&"p".repeat(shift));
            out.push('\n');
            for n in 0..2500 {
                out.push_str(&filler(n, 211, "é✓日本"));
            }
            out.push_str(&reply_at("2026-07-03T08:00:00.000Z"));
            out.push_str(&ai_title("Named by the AI"));
            fs::write(&path, &out).unwrap();

            let (title, last, _) = summary_of(&path);

            assert_eq!((title.as_str(), last.as_str()), ("Named by the AI", "2026-07-03T08:00:00.000Z"), "shift {shift}");
        }
    }

    #[test]
    fn a_last_line_still_being_written_is_passed_over() {
        let (_tmp, path, _) = long_transcript(
            &[opening("what the conversation opened with")],
            0,
            &[ai_title("Named by the AI"), reply_at("2026-07-03T08:00:00.000Z"), r#"{"type":"assistant","timestamp":"2026-07-03T09"#.to_string()],
        );

        let (title, last, _) = summary_of(&path);

        assert_eq!((title.as_str(), last.as_str()), ("Named by the AI", "2026-07-03T08:00:00.000Z"));
    }

    #[test]
    fn a_file_with_nothing_to_name_it_by_is_left_out_of_the_list() {
        let tmp = tempfile::tempdir().unwrap();
        let path = tmp.path().join("session.jsonl");
        fs::write(&path, concat!(
            r#"{"type":"mode","mode":"default","sessionId":"s"}"#, "\n",
            r#"{"type":"user","message":{"role":"user","content":[{"type":"tool_result","tool_use_id":"t","content":"ok"}]},"timestamp":"2026-07-03T08:00:00.000Z"}"#, "\n",
        )).unwrap();

        assert!(super::summarize(&path).is_none());
        assert!(super::summarize(&tmp.path().join("no-such.jsonl")).is_none());
    }

    #[test]
    fn the_first_message_that_says_something_names_an_untitled_conversation() {
        let tmp = tempfile::tempdir().unwrap();
        let path = tmp.path().join("session.jsonl");
        fs::write(&path, concat!(
            r#"{"type":"user","message":{"role":"user","content":"<ide_opened_file>The user opened the file a.java in the IDE.</ide_opened_file>"},"timestamp":"2026-07-03T07:59:00.000Z"}"#, "\n",
            r#"{"type":"user","message":{"role":"user","content":[{"type":"image","source":{"type":"base64","media_type":"image/png","data":"AAAA"}},{"type":"text","text":"the question itself"}]},"timestamp":"2026-07-03T08:00:00.000Z"}"#, "\n",
            r#"{"type":"user","message":{"role":"user","content":"a later message"},"timestamp":"2026-07-03T08:05:00.000Z"}"#, "\n",
        )).unwrap();

        let (title, last, _) = summary_of(&path);

        assert_eq!((title.as_str(), last.as_str()), ("the question itself", "2026-07-03T08:05:00.000Z"));
    }

    // ---- a conversation opened for the view in one reading ----

    const OPEN_ROOT: &str = r"C:\msgtest";

    /// Two exchanges, a compaction (`b1`), then a third exchange. The last reply carries
    /// the usage a resume note is worked out from.
    fn compacted_lines() -> Vec<&'static str> {
        vec![
            r#"{"type":"user","uuid":"u1","message":{"role":"user","content":"first question"},"timestamp":"2026-07-30T10:00:00.000Z"}"#,
            r#"{"type":"assistant","uuid":"a1","message":{"id":"m1","model":"claude-opus-5","content":[{"type":"thinking","thinking":"hmm"}]},"timestamp":"2026-07-30T10:00:02.000Z"}"#,
            r#"{"type":"assistant","uuid":"a2","message":{"id":"m1","model":"claude-opus-5","content":[{"type":"text","text":"first answer"}]},"timestamp":"2026-07-30T10:00:03.000Z"}"#,
            r#"{"type":"assistant","uuid":"a3","message":{"id":"m2","model":"claude-opus-5","content":[{"type":"tool_use","id":"t1","name":"Read","input":{"file_path":"a.java"}}]},"timestamp":"2026-07-30T10:00:04.000Z"}"#,
            r#"{"type":"user","uuid":"r1","message":{"role":"user","content":[{"type":"tool_result","tool_use_id":"t1","content":"contents"}]},"timestamp":"2026-07-30T10:00:05.000Z"}"#,
            r#"{"type":"assistant","uuid":"a4","message":{"id":"m3","model":"claude-opus-5","content":[{"type":"text","text":"read it"}]},"timestamp":"2026-07-30T10:00:06.000Z"}"#,
            r#"{"type":"system","subtype":"compact_boundary","uuid":"b1","parentUuid":null,"compactMetadata":{"trigger":"manual","preTokens":900000,"postTokens":13000},"timestamp":"2026-07-30T11:00:00.000Z"}"#,
            r#"{"type":"user","uuid":"s1","isCompactSummary":true,"isVisibleInTranscriptOnly":true,"message":{"role":"user","content":"This session is being continued from a previous conversation."},"timestamp":"2026-07-30T10:59:59.000Z"}"#,
            r#"{"type":"user","uuid":"u2","message":{"role":"user","content":"<command-name>/compact</command-name>"},"timestamp":"2026-07-30T10:59:00.000Z"}"#,
            r#"{"type":"user","uuid":"u3","message":{"role":"user","content":"second question"},"timestamp":"2026-07-30T11:05:00.000Z"}"#,
            r#"{"type":"assistant","uuid":"a5","message":{"id":"m4","model":"claude-sonnet-5","content":[{"type":"tool_use","id":"t2","name":"Bash","input":{"command":"ls"}}]},"timestamp":"2026-07-30T11:05:02.000Z"}"#,
            r#"{"type":"user","uuid":"r2","message":{"role":"user","content":[{"type":"tool_result","tool_use_id":"t2","content":"a b c"}]},"timestamp":"2026-07-30T11:05:03.000Z"}"#,
            r#"{"type":"assistant","uuid":"a6","message":{"id":"m5","model":"claude-sonnet-5","content":[{"type":"text","text":"second answer"}],"usage":{"input_tokens":3,"cache_read_input_tokens":70000,"cache_creation_input_tokens":646,"cache_creation":{"ephemeral_1h_input_tokens":646}}},"timestamp":"2026-07-30T11:05:04.000Z"}"#,
        ]
    }

    /// Writes `lines` as conversation `sess1` of a fake home and runs `f` against it.
    fn with_transcript<T>(tag: &str, lines: &[&str], f: impl FnOnce() -> T) -> T {
        let mut env = EnvGuard::lock();
        let (home, dir) = msg_home(tag);
        fs::write(dir.join("sess1.jsonl"), lines.join("\n") + "\n").unwrap();
        env.set_home(&home);
        let out = f();
        let _ = fs::remove_dir_all(&home);
        out
    }

    fn opened(from_last_compaction: bool) -> serde_json::Value {
        serde_json::from_str(&super::open_session(OPEN_ROOT, "sess1", from_last_compaction)).expect("an answer in JSON")
    }

    fn before(boundary: &str) -> serde_json::Value {
        serde_json::from_str(&super::open_session_before(OPEN_ROOT, "sess1", boundary)).expect("an answer in JSON")
    }

    fn items_of_answer(answer: &serde_json::Value) -> Vec<serde_json::Value> {
        answer["items"].as_array().cloned().unwrap_or_default()
    }

    /// Where the last compaction's own item sits among a conversation's items.
    fn last_compact(items: &[serde_json::Value]) -> usize {
        items.iter().rposition(|it| it["t"] == "compact").expect("a compaction among the items")
    }

    #[test]
    fn a_conversation_opens_as_the_loader_gives_it_with_each_reply_named() {
        let (whole, loaded) = with_transcript("open-whole", &compacted_lines(), || {
            (opened(false), super::load_session_history(OPEN_ROOT, "sess1"))
        });
        let loaded: serde_json::Value = serde_json::from_str(&loaded).unwrap();

        // The same items, save for what names a reply's line and its time.
        let mut plain = items_of_answer(&whole);
        for item in plain.iter_mut().filter(|it| it["t"] == "text") {
            let item = item.as_object_mut().unwrap();
            item.remove("id");
            item.remove("at");
        }
        assert_eq!(serde_json::Value::Array(plain), loaded);

        let named: Vec<(String, String)> = items_of_answer(&whole)
            .iter()
            .filter(|it| it["t"] == "text")
            .map(|it| (it["id"].as_str().unwrap().to_string(), it["at"].as_str().unwrap().to_string()))
            .collect();
        assert_eq!(
            named,
            vec![
                ("a2".to_string(), "2026-07-30T10:00:03.000Z".to_string()),
                ("a4".to_string(), "2026-07-30T10:00:06.000Z".to_string()),
                ("a6".to_string(), "2026-07-30T11:05:04.000Z".to_string()),
            ]
        );
        assert_eq!(whole["cut"], serde_json::json!({ "uuid": "b1", "at": "2026-07-30T11:00:00.000Z", "nth": 1 }));
        assert!(whole["earlier"].is_null(), "nothing was left out: {}", whole["earlier"]);
    }

    #[test]
    fn the_resume_note_comes_with_the_conversation() {
        let (whole, tail, note) = with_transcript("open-note", &compacted_lines(), || {
            (opened(false), opened(true), crate::promptcache::resume_note(OPEN_ROOT, "sess1"))
        });

        // Written in July 2026 and read by today's clock: long cold.
        assert!(note.starts_with("Idle ") && note.ends_with("about 71k tokens."), "{note}");
        assert_eq!(whole["note"], note.as_str());
        assert_eq!(tail["note"], note.as_str(), "the note is of the whole conversation, whatever part is drawn");
    }

    #[test]
    fn opened_from_its_last_compaction_a_conversation_starts_at_that_line() {
        let (whole, tail) = with_transcript("open-tail", &compacted_lines(), || (opened(false), opened(true)));
        let all = items_of_answer(&whole);
        let cut = last_compact(&all);

        assert!(cut > 0 && cut < all.len() - 1, "the fixture has items on both sides: {cut} of {}", all.len());
        assert_eq!(items_of_answer(&tail), all[cut..].to_vec(), "exactly what the whole conversation ends with");
        assert_eq!(tail["cut"], whole["cut"]);
        assert_eq!(
            tail["earlier"],
            serde_json::json!({ "replies": ["a2", "a4"], "model": "claude-opus-5", "thinking": true }),
            "what was left out: its replies, the model last used in it, and that it had thinking"
        );
    }

    #[test]
    fn the_part_before_a_compaction_is_had_by_naming_its_boundary() {
        let (whole, head, unknown) = with_transcript("open-before", &compacted_lines(), || (opened(false), before("b1"), before("nope")));
        let all = items_of_answer(&whole);
        let cut = last_compact(&all);

        assert_eq!(items_of_answer(&head), all[..cut].to_vec(), "exactly what the whole conversation begins with");
        assert_eq!(items_of_answer(&unknown), Vec::<serde_json::Value>::new(), "no such compaction, nothing before it");
    }

    #[test]
    fn compacted_twice_it_starts_at_the_second_and_either_part_before_can_be_asked_for() {
        let mut lines = compacted_lines();
        lines.extend([
            r#"{"type":"system","subtype":"compact_boundary","uuid":"b2","parentUuid":null,"compactMetadata":{"trigger":"auto","preTokens":960000,"postTokens":19000},"timestamp":"2026-07-30T12:00:00.000Z"}"#,
            r#"{"type":"user","uuid":"s2","isCompactSummary":true,"message":{"role":"user","content":"Continued again."},"timestamp":"2026-07-30T11:59:59.000Z"}"#,
            r#"{"type":"assistant","uuid":"a7","message":{"id":"m6","model":"claude-sonnet-5","content":[{"type":"text","text":"carrying on"}]},"timestamp":"2026-07-30T12:00:05.000Z"}"#,
        ]);
        let (whole, tail, first, second) = with_transcript("open-twice", &lines, || (opened(false), opened(true), before("b1"), before("b2")));
        let all = items_of_answer(&whole);
        let cut = last_compact(&all);
        let first_cut = all.iter().position(|it| it["t"] == "compact").unwrap();

        assert!(first_cut < cut);
        assert_eq!(items_of_answer(&tail), all[cut..].to_vec());
        assert_eq!(tail["cut"]["uuid"], "b2");
        assert_eq!(tail["earlier"]["replies"], serde_json::json!(["a2", "a4", "a6"]));
        assert_eq!(tail["earlier"]["model"], "claude-sonnet-5", "the model last used before the second compaction");
        assert_eq!(items_of_answer(&second), all[..cut].to_vec());
        assert_eq!(items_of_answer(&first), all[..first_cut].to_vec());
    }

    #[test]
    fn a_conversation_never_compacted_opens_whole_either_way() {
        let lines: Vec<&str> = compacted_lines().into_iter().take(6).collect();
        let (whole, tail) = with_transcript("open-plain", &lines, || (opened(false), opened(true)));

        assert_eq!(items_of_answer(&whole).len(), 5, "a question, thinking, two replies and a tool");
        assert_eq!(tail, whole);
        assert!(whole["cut"].is_null() && whole["earlier"].is_null(), "{whole}");
    }

    #[test]
    fn a_compaction_with_nothing_said_before_it_leaves_nothing_out() {
        let lines: Vec<&str> = compacted_lines().into_iter().skip(6).collect();
        let (whole, tail) = with_transcript("open-first", &lines, || (opened(false), opened(true)));

        assert_eq!(items_of_answer(&tail), items_of_answer(&whole));
        assert_eq!(tail["cut"]["uuid"], "b1");
        assert!(tail["earlier"].is_null(), "{}", tail["earlier"]);
    }

    #[test]
    fn a_reply_the_cli_wrote_itself_is_not_among_the_replies_left_out() {
        let mut lines = compacted_lines();
        lines.insert(6, r#"{"type":"assistant","uuid":"err","isApiErrorMessage":true,"message":{"model":"<synthetic>","content":[{"type":"text","text":"API Error: 529"}]},"timestamp":"2026-07-30T10:30:00.000Z"}"#);
        let tail = with_transcript("open-synthetic", &lines, || opened(true));

        assert_eq!(tail["earlier"]["replies"], serde_json::json!(["a2", "a4"]));
        assert_eq!(tail["earlier"]["model"], "claude-opus-5", "<synthetic> is not a model");
    }

    #[test]
    fn nothing_to_read_opens_as_an_empty_conversation() {
        let (missing, outside, outside_before) = with_transcript("open-missing", &compacted_lines(), || {
            (
                super::open_session(OPEN_ROOT, "no-such-session", false),
                super::open_session(OPEN_ROOT, r"..\C--msgtest\sess1", false),
                super::open_session_before(OPEN_ROOT, r"..\C--msgtest\sess1", "b1"),
            )
        });

        for answer in [missing, outside, outside_before] {
            let answer: serde_json::Value = serde_json::from_str(&answer).expect("an answer in JSON");
            assert_eq!(items_of_answer(&answer), Vec::<serde_json::Value>::new(), "{answer}");
            assert!(answer["cut"].is_null() && answer["earlier"].is_null(), "{answer}");
        }
    }

    // ---- which lines the view may give a message or a reply it has just drawn ----

    /// The `(id, compactions)` of each entry of an id list.
    fn marked(list: &str) -> Vec<(String, u64)> {
        let list: serde_json::Value = serde_json::from_str(list).expect("a list in JSON");
        list.as_array()
            .unwrap()
            .iter()
            .map(|entry| (entry["id"].as_str().unwrap().to_string(), entry["compactions"].as_u64().unwrap_or(0)))
            .collect()
    }

    fn ids(pairs: &[(&str, u64)]) -> Vec<(String, u64)> {
        pairs.iter().map(|(id, n)| (id.to_string(), *n)).collect()
    }

    /// `compacted_lines`, compacted once more (`b2`) with a message sent as it was.
    fn compacted_twice() -> Vec<&'static str> {
        let mut lines = compacted_lines();
        lines.push(r#"{"type":"system","subtype":"compact_boundary","uuid":"b2","parentUuid":null,"compactMetadata":{"trigger":"auto","preTokens":900000,"postTokens":13000},"timestamp":"2026-07-30T12:00:00.000Z"}"#);
        lines.push(r#"{"type":"user","uuid":"u4","message":{"role":"user","content":"sent as it compacted"},"timestamp":"2026-07-30T12:00:05.000Z"}"#);
        lines.push(r#"{"type":"assistant","uuid":"a7","message":{"id":"m6","model":"claude-sonnet-5","content":[{"type":"text","text":"third answer"}]},"timestamp":"2026-07-30T12:00:09.000Z"}"#);
        lines
    }

    #[test]
    fn message_ids_say_how_many_compactions_precede_each_message() {
        let (once, twice) = (
            with_transcript("ids-once", &compacted_lines(), || super::message_ids(OPEN_ROOT, "sess1")),
            with_transcript("ids-twice", &compacted_twice(), || super::message_ids(OPEN_ROOT, "sess1")),
        );

        // The /compact a compaction answers is written after its boundary, as is all that
        // was said since: a view showing the conversation from there on has drawn those.
        assert_eq!(marked(&once), ids(&[("u1", 0), ("u2", 1), ("u3", 1)]), "{once}");
        assert_eq!(marked(&twice), ids(&[("u1", 0), ("u2", 1), ("u3", 1), ("u4", 2)]), "{twice}");
    }

    #[test]
    fn reply_ids_say_how_many_compactions_precede_each_reply() {
        let twice = with_transcript("replies-twice", &compacted_twice(), || super::reply_ids(OPEN_ROOT, "sess1"));

        assert_eq!(marked(&twice), ids(&[("a2", 0), ("a4", 0), ("a6", 1), ("a7", 2)]), "{twice}");
    }

    #[test]
    fn a_conversation_opened_at_its_last_compaction_says_which_one_that_is() {
        let (once, twice) = (
            with_transcript("nth-once", &compacted_lines(), || opened(true)),
            with_transcript("nth-twice", &compacted_twice(), || opened(true)),
        );

        // The count the id lists put on the lines that follow that compaction.
        assert_eq!((once["cut"]["uuid"].as_str(), once["cut"]["nth"].as_u64()), (Some("b1"), Some(1)));
        assert_eq!((twice["cut"]["uuid"].as_str(), twice["cut"]["nth"].as_u64()), (Some("b2"), Some(2)));
    }

    #[test]
    fn the_id_lists_of_a_conversation_never_compacted_are_as_they_were() {
        let plain: Vec<&str> = compacted_lines().into_iter().take(6).collect();
        let (messages, replies) = with_transcript("ids-plain", &plain, || {
            (super::message_ids(OPEN_ROOT, "sess1"), super::reply_ids(OPEN_ROOT, "sess1"))
        });

        assert_eq!(marked(&messages), ids(&[("u1", 0)]));
        assert!(!messages.contains("compactions") && !replies.contains("compactions"), "{messages} {replies}");
    }
}
