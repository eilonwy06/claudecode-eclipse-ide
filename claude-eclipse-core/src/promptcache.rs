//! The line a reopened conversation ends with when its next message will cost more
//! than usual, because the prompt cache no longer holds it.
//!
//! Every request sends the whole conversation again, and the API keeps the unchanged
//! front of it cached for five minutes or for an hour, renewed each time it is used.
//! Reading it from the cache costs a fraction of sending it fresh; a conversation left
//! past that lifetime, or just compacted into something the cache has never seen, is
//! written to the cache all over again by its next message.
//!
//! Nothing here asks the API. A reply's own usage numbers say which lifetime its
//! request cached under, and the clock says whether that has run out: hence "likely".
//! The rule and both sentences are the VS Code extension's for the same note.

use std::fs;
use std::io::{BufRead, BufReader};
use std::path::Path;
use std::time::{SystemTime, UNIX_EPOCH};

use serde::Deserialize;

/// The model name the CLI gives a reply it wrote itself (an API error, a limit notice).
const SYNTHETIC_MODEL: &str = "<synthetic>";

/// How long the API keeps a cached prompt that is not used again.
#[derive(Clone, Copy, Debug, PartialEq)]
enum Lifetime {
    FiveMinutes,
    OneHour,
}

impl Lifetime {
    fn ms(self) -> i64 {
        match self {
            Lifetime::FiveMinutes => 5 * 60 * 1000,
            Lifetime::OneHour => 60 * 60 * 1000,
        }
    }
}

/// What the transcript says about the cache as of its last reply.
#[derive(Clone, Debug, Default, PartialEq)]
struct Record {
    /// The API message the reply belongs to. Its lines share one request.
    message_id: Option<String>,
    /// When the request that produced it was sent: the lifetime runs from there.
    anchor_at: Option<i64>,
    lifetime: Option<Lifetime>,
    /// The size of that request's prompt, which is what gets cached again.
    recache_tokens: Option<u64>,
    /// Set by a compaction no reply has followed yet.
    compacted_at: Option<i64>,
}

/// Where the cache stands at a given moment.
#[derive(Debug, PartialEq)]
enum Window {
    /// Nothing to go by: no reply, or one that names no lifetime.
    Unknown,
    Warm,
    Expired { idle_ms: i64, recache_tokens: Option<u64> },
    Compacted,
}

// ---------------------------------------------------------------------------
// The note
// ---------------------------------------------------------------------------

/// The note for a saved conversation as of now, `""` when there is none to show: the
/// cache is still warm, the conversation has no reply to go by, or it cannot be read.
pub fn resume_note(workspace_root: &str, session_id: &str) -> String {
    crate::session::projects_dir(workspace_root)
        .filter(|_| crate::bookmarks::plain_id(session_id))
        .and_then(|dir| note_at(&dir.join(format!("{session_id}.jsonl")), now_ms()))
        .unwrap_or_default()
}

/// [`resume_note`] over a given transcript file, at a given time.
fn note_at(transcript: &Path, now_ms: i64) -> Option<String> {
    note(&window(read_record(transcript).as_ref(), now_ms))
}

/// [`resume_note`] for a transcript the caller has already read, from its lines: the
/// conversation is opened in one reading, and the note is part of what that gives.
pub(crate) fn note_of_lines(lines: impl Iterator<Item = impl AsRef<str>>) -> String {
    note(&window(record_of(lines).as_ref(), now_ms())).unwrap_or_default()
}

/// The sentence for a window, None when it calls for none.
fn note(window: &Window) -> Option<String> {
    match window {
        Window::Compacted => Some("The conversation was compacted, so your next message will re-cache it.".to_string()),
        Window::Expired { idle_ms, recache_tokens } => {
            let what = match recache_tokens {
                Some(tokens) => format!("about {} tokens", short_count(*tokens)),
                None => "the conversation".to_string(),
            };
            Some(format!("Idle {}. The prompt cache has likely expired, so your next message will re-cache {what}.", idle(*idle_ms)))
        }
        Window::Warm | Window::Unknown => None,
    }
}

/// Where the cache stands at `now_ms`, going by `record`. A compaction nothing has
/// answered comes first: whatever the clock says, the cache does not hold what it made.
fn window(record: Option<&Record>, now_ms: i64) -> Window {
    let Some(record) = record else {
        return Window::Unknown;
    };
    if record.compacted_at.is_some() {
        return Window::Compacted;
    }
    let (Some(anchor_at), Some(lifetime)) = (record.anchor_at, record.lifetime) else {
        return Window::Unknown;
    };
    if anchor_at + lifetime.ms() > now_ms {
        Window::Warm
    } else {
        Window::Expired { idle_ms: now_ms - anchor_at, recache_tokens: record.recache_tokens }
    }
}

/// An idle time the way the note gives it: minutes, hours and minutes, or days and hours.
fn idle(ms: i64) -> String {
    let minutes = ms.div_euclid(60_000).max(0);
    let hours = minutes / 60;
    if minutes < 60 {
        format!("{minutes}m")
    } else if hours < 24 {
        format!("{hours}h {}m", minutes % 60)
    } else {
        format!("{}d {}h", hours / 24, hours % 24)
    }
}

/// A token count the way the note gives it: 999, 71k, 1.3M.
fn short_count(tokens: u64) -> String {
    if tokens >= 1_000_000 {
        millions(tokens)
    } else if tokens >= 1000 {
        // Half a thousand goes up, as `Math.round` sends it.
        format!("{}k", (tokens + 500) / 1000)
    } else {
        tokens.to_string()
    }
}

/// `tokens` in millions to one decimal, as JavaScript's `toFixed(1)` prints the
/// quotient: the double nearest to it, rounded on its exact value, a tie going up.
/// A count half way between two tenths goes whichever way that double fell (1.15M is
/// stored a hair under and prints "1.1M"), so the rounding is done on the double's own
/// bits, not on the count.
fn millions(tokens: u64) -> String {
    let bits = (tokens as f64 / 1e6).to_bits();
    // The quotient is mantissa / 2^shift. It is at least 1 and nowhere near 2^52,
    // which keeps the shift between 1 and 52.
    let mantissa = (bits & ((1 << 52) - 1)) | (1 << 52);
    let shift = (1075 - ((bits >> 52) & 0x7ff) as i64).clamp(1, 52) as u32;
    let scaled = u128::from(mantissa) * 10;
    let half_or_more = (scaled >> (shift - 1)) & 1 == 1;
    let tenths = (scaled >> shift) + u128::from(half_or_more);
    format!("{}.{}M", tenths / 10, tenths % 10)
}

// ---------------------------------------------------------------------------
// Reading the transcript
// ---------------------------------------------------------------------------

/// The fields of a transcript line this goes by. The rest of the line, a tool's whole
/// output or a pasted image included, is passed over without being kept.
#[derive(Deserialize)]
struct Line {
    #[serde(rename = "type")]
    kind: Option<String>,
    #[serde(rename = "isSidechain")]
    is_sidechain: Option<bool>,
    #[serde(rename = "isCompactSummary")]
    is_compact_summary: Option<bool>,
    timestamp: Option<String>,
    message: Option<Message>,
}

#[derive(Deserialize)]
struct Message {
    id: Option<String>,
    model: Option<String>,
    usage: Option<Usage>,
}

#[derive(Deserialize)]
struct Usage {
    input_tokens: Option<u64>,
    cache_read_input_tokens: Option<u64>,
    cache_creation_input_tokens: Option<u64>,
    cache_creation: Option<CacheCreation>,
}

#[derive(Deserialize)]
struct CacheCreation {
    ephemeral_1h_input_tokens: Option<u64>,
    ephemeral_5m_input_tokens: Option<u64>,
}

impl Usage {
    /// The size of the request's prompt: what it sent fresh, what it read from the
    /// cache and what it wrote there. None when the line reports none of it.
    fn prompt_tokens(&self) -> Option<u64> {
        let total = self.input_tokens.unwrap_or(0).saturating_add(self.cached_tokens());
        (total > 0).then_some(total)
    }

    fn cached_tokens(&self) -> u64 {
        self.cache_read_input_tokens.unwrap_or(0).saturating_add(self.cache_creation_input_tokens.unwrap_or(0))
    }

    /// The lifetime the request wrote its part of the cache under, when it wrote any.
    fn named_lifetime(&self) -> Option<Lifetime> {
        let written = self.cache_creation.as_ref()?;
        if written.ephemeral_1h_input_tokens.unwrap_or(0) > 0 {
            Some(Lifetime::OneHour)
        } else if written.ephemeral_5m_input_tokens.unwrap_or(0) > 0 {
            Some(Lifetime::FiveMinutes)
        } else {
            None
        }
    }
}

/// A pass over a transcript: the record so far, and when the request the next reply
/// answers was sent.
#[derive(Default)]
struct Reading {
    record: Option<Record>,
    request_at: Option<i64>,
}

impl Reading {
    fn take(&mut self, line: Line) {
        // A subagent talks to the API on its own account: its lines are not this
        // conversation's requests or replies.
        if line.is_sidechain == Some(true) {
            return;
        }
        let at = line.timestamp.as_deref().and_then(iso_ms);
        match line.kind.as_deref() {
            Some("user") => self.request(at, line.is_compact_summary == Some(true)),
            Some("assistant") => {
                let Some(message) = line.message else {
                    return;
                };
                if message.model.as_deref() == Some(SYNTHETIC_MODEL) {
                    return;
                }
                if let Some(usage) = message.usage {
                    self.reply(message.id, at, &usage);
                }
            }
            _ => {}
        }
    }

    /// A line that goes to the API: a typed message, a tool's result, or the summary a
    /// compaction leaves in place of what came before.
    fn request(&mut self, at: Option<i64>, is_compact_summary: bool) {
        let Some(at) = at else {
            return;
        };
        self.request_at = Some(self.request_at.map_or(at, |seen| seen.max(at)));
        if is_compact_summary {
            // The record stays as it is otherwise: a reply that follows may still
            // inherit the lifetime it names.
            self.record.get_or_insert_with(Record::default).compacted_at = Some(at);
        }
    }

    /// A reply line that reports what its request sent.
    fn reply(&mut self, message_id: Option<String>, at: Option<i64>, usage: &Usage) {
        let Some(recache_tokens) = usage.prompt_tokens() else {
            return;
        };
        let before = self.record.take();
        let before = before.as_ref();
        // One API message is written as a line per block: they are one request.
        let same_message = message_id.is_some() && before.is_some_and(|b| b.message_id == message_id);
        let anchor_at = before.filter(|_| same_message).and_then(|b| b.anchor_at).or(match (self.request_at, at) {
            (Some(request), Some(reply)) if request > reply => Some(reply),
            (Some(request), _) => Some(request),
            (None, reply) => reply,
        });
        let lifetime = if usage.cached_tokens() > 0 {
            usage.named_lifetime().or(before.and_then(|b| b.lifetime))
        } else {
            None
        };
        // A compaction stays unanswered until a reply written after it. The lines it
        // kept come back after the summary, stamped with the times they first had.
        let compacted_at = before
            .and_then(|b| b.compacted_at)
            .filter(|&compacted| same_message || !at.is_some_and(|reply| reply > compacted));
        if before.and_then(|b| b.message_id.as_ref()) != message_id.as_ref() {
            // Answered by a new message: the one after it needs a request of its own.
            self.request_at = None;
        }
        self.record = Some(Record { message_id, anchor_at, lifetime, recache_tokens: Some(recache_tokens), compacted_at });
    }
}

/// The record a transcript ends on, None when it holds nothing to go by.
fn read_record(transcript: &Path) -> Option<Record> {
    let file = fs::File::open(transcript).ok()?;
    record_of(BufReader::new(file).lines().filter_map(Result::ok))
}

/// [`read_record`] over the lines of a transcript, wherever they were read from.
fn record_of(lines: impl Iterator<Item = impl AsRef<str>>) -> Option<Record> {
    let mut reading = Reading::default();
    for line in lines {
        let line = line.as_ref();
        // Cheap reject first: much of a long transcript is neither kind of line.
        if !line.contains("\"user\"") && !line.contains("\"assistant\"") {
            continue;
        }
        if let Ok(line) = serde_json::from_str::<Line>(line) {
            reading.take(line);
        }
    }
    reading.record
}

/// A transcript timestamp in milliseconds since 1970: `2026-07-30T10:00:00.000Z`, with
/// or without the fraction, `Z` or a `+08:00` offset. None for anything else.
pub(crate) fn iso_ms(text: &str) -> Option<i64> {
    let bytes = text.as_bytes();
    let number = |from: usize, to: usize| -> Option<i64> {
        let digits = bytes.get(from..to)?;
        digits.iter().all(u8::is_ascii_digit).then(|| digits.iter().fold(0, |n, d| n * 10 + i64::from(d - b'0')))
    };
    let punctuated = |at: usize, mark: u8| bytes.get(at) == Some(&mark);
    if !(punctuated(4, b'-') && punctuated(7, b'-') && punctuated(10, b'T') && punctuated(13, b':') && punctuated(16, b':')) {
        return None;
    }
    let (year, month, day) = (number(0, 4)?, number(5, 7)?, number(8, 10)?);
    let (hour, minute, second) = (number(11, 13)?, number(14, 16)?, number(17, 19)?);
    if !(1..=12).contains(&month) || !(1..=days_in_month(year, month)).contains(&day) || hour > 23 || minute > 59 || second > 59 {
        return None;
    }
    let mut zone = &bytes[19..];
    let mut millis = 0;
    if let Some((b'.', fraction)) = zone.split_first() {
        let digits = fraction.iter().take_while(|b| b.is_ascii_digit()).count();
        if digits == 0 {
            return None;
        }
        // Anything past the third digit is finer than a millisecond, and dropped.
        millis = (0..3).fold(0, |n, i| n * 10 + fraction[..digits].get(i).map_or(0, |d| i64::from(d - b'0')));
        zone = &fraction[digits..];
    }
    let offset_minutes = match zone {
        b"Z" => 0,
        [sign @ (b'+' | b'-'), _, _, b':', _, _] => {
            let (hours, minutes) = (number(bytes.len() - 5, bytes.len() - 3)?, number(bytes.len() - 2, bytes.len())?);
            if hours > 23 || minutes > 59 {
                return None;
            }
            (hours * 60 + minutes) * if *sign == b'-' { -1 } else { 1 }
        }
        _ => return None,
    };
    let minutes_since_1970 = (days_from_civil(year, month, day) * 24 + hour) * 60 + minute - offset_minutes;
    Some(minutes_since_1970 * 60_000 + second * 1000 + millis)
}

fn days_in_month(year: i64, month: i64) -> i64 {
    match month {
        4 | 6 | 9 | 11 => 30,
        2 if year % 4 == 0 && (year % 100 != 0 || year % 400 == 0) => 29,
        2 => 28,
        _ => 31,
    }
}

/// Days from 1970-01-01 to a date of the Gregorian calendar, counting years from March
/// so that the leap day falls at the end of one.
fn days_from_civil(year: i64, month: i64, day: i64) -> i64 {
    let year = if month <= 2 { year - 1 } else { year };
    let (era, year_of_era) = (year.div_euclid(400), year.rem_euclid(400));
    let day_of_year = (153 * (if month > 2 { month - 3 } else { month + 9 }) + 2) / 5 + day - 1;
    let day_of_era = year_of_era * 365 + year_of_era / 4 - year_of_era / 100 + day_of_year;
    era * 146_097 + day_of_era - 719_468
}

fn now_ms() -> i64 {
    SystemTime::now().duration_since(UNIX_EPOCH).map(|d| d.as_millis() as i64).unwrap_or(0)
}

// ---------------------------------------------------------------------------
// Tests
// ---------------------------------------------------------------------------

#[cfg(test)]
mod tests {
    use super::*;
    use crate::test_support::EnvGuard;

    const MINUTE: i64 = 60 * 1000;
    const HOUR: i64 = 60 * MINUTE;
    const DAY: i64 = 24 * HOUR;

    /// 2026-07-30T10:00:00.000Z, the moment the fixtures start from.
    const T0: i64 = 1_785_405_600_000;

    /// A prompt of 70,649 tokens cached for an hour.
    const HOUR_USAGE: &str = r#"{"input_tokens":3,"cache_read_input_tokens":70000,"cache_creation_input_tokens":646,"output_tokens":900,"cache_creation":{"ephemeral_1h_input_tokens":646,"ephemeral_5m_input_tokens":0}}"#;
    /// A prompt of 2,400 tokens cached for five minutes.
    const FIVE_MINUTE_USAGE: &str = r#"{"input_tokens":400,"cache_read_input_tokens":0,"cache_creation_input_tokens":2000,"cache_creation":{"ephemeral_1h_input_tokens":0,"ephemeral_5m_input_tokens":2000}}"#;
    /// A prompt read from the cache, which says nothing of the lifetime it was written under.
    const READ_ONLY_USAGE: &str = r#"{"input_tokens":10,"cache_read_input_tokens":5000,"cache_creation_input_tokens":0}"#;
    /// A prompt that touched no cache at all.
    const UNCACHED_USAGE: &str = r#"{"input_tokens":1200,"output_tokens":40}"#;

    /// `T0 + ms` as the transcript writes a time.
    fn at(ms: i64) -> String {
        let total = T0 + ms;
        let (days, in_day) = (total.div_euclid(DAY), total.rem_euclid(DAY));
        // Good for the fixtures' range only: every one of them falls in July to October 2026.
        let (month, day) = [(7, 31), (8, 31), (9, 30), (10, 31)]
            .iter()
            .scan(days - 20_635, |left, &(month, len)| {
                let here = *left;
                *left -= len;
                Some((month, here, len))
            })
            .find(|&(_, here, len)| here < len)
            .map(|(month, here, _)| (month, here + 1))
            .expect("a fixture time between July and October 2026");
        format!(
            "2026-{:02}-{:02}T{:02}:{:02}:{:02}.{:03}Z",
            month,
            day,
            in_day / HOUR,
            in_day % HOUR / MINUTE,
            in_day % MINUTE / 1000,
            in_day % 1000
        )
    }

    fn user(ms: i64) -> String {
        format!(r#"{{"type":"user","uuid":"u{ms}","message":{{"role":"user","content":"a question"}},"timestamp":"{}"}}"#, at(ms))
    }

    fn tool_result(ms: i64) -> String {
        format!(
            r#"{{"type":"user","uuid":"r{ms}","message":{{"role":"user","content":[{{"type":"tool_result","tool_use_id":"t1","content":"done"}}]}},"timestamp":"{}"}}"#,
            at(ms)
        )
    }

    fn reply(message_id: &str, ms: i64, usage: &str) -> String {
        format!(
            r#"{{"type":"assistant","uuid":"a{ms}","message":{{"id":"{message_id}","model":"claude-opus-5","content":[{{"type":"text","text":"an answer"}}],"usage":{usage}}},"timestamp":"{}"}}"#,
            at(ms)
        )
    }

    fn summary(ms: i64) -> String {
        format!(
            r#"{{"type":"user","isCompactSummary":true,"isVisibleInTranscriptOnly":true,"message":{{"role":"user","content":"This session is being continued from a previous conversation."}},"timestamp":"{}"}}"#,
            at(ms)
        )
    }

    fn transcript(lines: &[String]) -> (tempfile::TempDir, std::path::PathBuf) {
        let tmp = tempfile::tempdir().unwrap();
        let path = tmp.path().join("session.jsonl");
        fs::write(&path, lines.join("\n") + "\n").unwrap();
        (tmp, path)
    }

    /// The note for a transcript of `lines`, `after` milliseconds past `T0`.
    fn note_after(lines: &[String], after: i64) -> Option<String> {
        let (_tmp, path) = transcript(lines);
        note_at(&path, T0 + after)
    }

    #[test]
    fn the_fixture_clock_writes_the_times_it_means() {
        assert_eq!(at(0), "2026-07-30T10:00:00.000Z");
        assert_eq!(at(5 * 1000 + 250), "2026-07-30T10:00:05.250Z");
        assert_eq!(at(22 * DAY + 15 * HOUR), "2026-08-22T01:00:00.000Z");
        assert_eq!(at(64 * DAY), "2026-10-02T10:00:00.000Z");
    }

    #[test]
    fn a_conversation_left_past_its_hour_says_how_long_and_how_much() {
        let lines = [user(0), reply("msg_1", 5_000, HOUR_USAGE)];

        assert_eq!(
            note_after(&lines, 22 * DAY + 15 * HOUR + 20 * MINUTE).as_deref(),
            Some("Idle 22d 15h. The prompt cache has likely expired, so your next message will re-cache about 71k tokens.")
        );
    }

    #[test]
    fn a_warm_cache_says_nothing() {
        let lines = [user(0), reply("msg_1", 5_000, HOUR_USAGE)];

        assert_eq!(note_after(&lines, 10 * MINUTE), None);
        assert_eq!(note_after(&lines, HOUR - 1), None, "one millisecond of the hour left");
        assert_eq!(
            note_after(&lines, HOUR).as_deref(),
            Some("Idle 1h 0m. The prompt cache has likely expired, so your next message will re-cache about 71k tokens."),
            "the hour is up"
        );
    }

    #[test]
    fn a_five_minute_cache_runs_out_after_five_minutes() {
        let lines = [user(0), reply("msg_1", 2_000, FIVE_MINUTE_USAGE)];

        assert_eq!(note_after(&lines, 4 * MINUTE), None);
        assert_eq!(
            note_after(&lines, 7 * MINUTE).as_deref(),
            Some("Idle 7m. The prompt cache has likely expired, so your next message will re-cache about 2k tokens.")
        );
    }

    #[test]
    fn a_reply_that_names_no_lifetime_gives_nothing_to_go_by() {
        for usage in [UNCACHED_USAGE, READ_ONLY_USAGE] {
            let lines = [user(0), reply("msg_1", 2_000, usage)];

            assert_eq!(note_after(&lines, 30 * DAY), None, "{usage}");
        }
    }

    #[test]
    fn a_cached_reply_that_names_no_lifetime_keeps_the_one_before() {
        let lines = [user(0), reply("msg_1", 2_000, HOUR_USAGE), tool_result(10_000), reply("msg_2", 12_000, READ_ONLY_USAGE)];

        assert_eq!(note_after(&lines, 30 * MINUTE), None, "still inside the hour it inherited");
        assert_eq!(
            note_after(&lines, 3 * HOUR).as_deref(),
            Some("Idle 2h 59m. The prompt cache has likely expired, so your next message will re-cache about 5k tokens.")
        );
    }

    #[test]
    fn a_reply_that_touched_no_cache_drops_the_lifetime() {
        let lines = [user(0), reply("msg_1", 2_000, HOUR_USAGE), user(10_000), reply("msg_2", 12_000, UNCACHED_USAGE)];

        assert_eq!(note_after(&lines, 3 * HOUR), None);
    }

    #[test]
    fn the_idle_time_runs_from_the_request_not_from_the_reply() {
        // The answer took ten minutes to come: the cache was written when it was asked for.
        let lines = [user(0), reply("msg_1", 1_000, HOUR_USAGE), tool_result(HOUR), reply("msg_2", HOUR + 10 * MINUTE, HOUR_USAGE)];

        assert_eq!(note_after(&lines, 2 * HOUR - 1), None, "an hour after the request has not passed");
        assert_eq!(
            note_after(&lines, 2 * HOUR + 5 * MINUTE).as_deref(),
            Some("Idle 1h 5m. The prompt cache has likely expired, so your next message will re-cache about 71k tokens.")
        );
    }

    #[test]
    fn a_request_stamped_after_its_reply_is_not_the_anchor() {
        // Lines kept through a compaction come back with the times they first had.
        let lines = [user(20 * MINUTE), reply("msg_1", 0, HOUR_USAGE)];

        assert_eq!(
            note_after(&lines, HOUR + 10 * MINUTE).as_deref(),
            Some("Idle 1h 10m. The prompt cache has likely expired, so your next message will re-cache about 71k tokens.")
        );
    }

    #[test]
    fn the_lines_of_one_message_share_its_first_anchor() {
        // One API message, written as a line per block, the last of them a minute on.
        let lines = [user(0), reply("msg_1", 5_000, HOUR_USAGE), reply("msg_1", MINUTE, HOUR_USAGE)];

        assert_eq!(
            note_after(&lines, HOUR + 30 * MINUTE).as_deref(),
            Some("Idle 1h 30m. The prompt cache has likely expired, so your next message will re-cache about 71k tokens.")
        );
    }

    #[test]
    fn a_new_message_does_not_reuse_the_request_before_it() {
        // No request line between the two: the second reply's own time is all there is.
        let lines = [user(0), reply("msg_1", 5_000, HOUR_USAGE), reply("msg_2", 30 * MINUTE, HOUR_USAGE)];

        assert_eq!(note_after(&lines, HOUR + 10 * MINUTE), None, "forty minutes after the second reply");
        assert_eq!(
            note_after(&lines, 2 * HOUR).as_deref(),
            Some("Idle 1h 30m. The prompt cache has likely expired, so your next message will re-cache about 71k tokens.")
        );
    }

    #[test]
    fn a_reply_the_cli_wrote_itself_does_not_count() {
        let api_error = format!(
            r#"{{"type":"assistant","isApiErrorMessage":true,"message":{{"id":"msg_err","model":"<synthetic>","content":[{{"type":"text","text":"API Error: 529"}}],"usage":{{"input_tokens":999999,"cache_read_input_tokens":0,"cache_creation_input_tokens":0}}}},"timestamp":"{}"}}"#,
            at(10 * MINUTE)
        );
        let lines = [user(0), reply("msg_1", 5_000, HOUR_USAGE), user(9 * MINUTE), api_error];

        assert_eq!(
            note_after(&lines, 2 * HOUR).as_deref(),
            Some("Idle 2h 0m. The prompt cache has likely expired, so your next message will re-cache about 71k tokens.")
        );
    }

    #[test]
    fn a_subagents_lines_do_not_count() {
        let side_request = user(20 * MINUTE).replace(r#""type":"user""#, r#""type":"user","isSidechain":true"#);
        let side_reply = reply("msg_side", 21 * MINUTE, FIVE_MINUTE_USAGE).replace(r#""type":"assistant""#, r#""type":"assistant","isSidechain":true"#);
        let lines = [user(0), reply("msg_1", 5_000, HOUR_USAGE), side_request, side_reply];

        assert_eq!(
            note_after(&lines, 2 * HOUR).as_deref(),
            Some("Idle 2h 0m. The prompt cache has likely expired, so your next message will re-cache about 71k tokens.")
        );
    }

    #[test]
    fn a_reply_with_no_prompt_tokens_changes_nothing() {
        let empty = reply("msg_2", 31 * MINUTE, r#"{"input_tokens":0,"output_tokens":12}"#);
        let lines = [user(0), reply("msg_1", 5_000, HOUR_USAGE), user(30 * MINUTE), empty];

        assert_eq!(
            note_after(&lines, 2 * HOUR).as_deref(),
            Some("Idle 2h 0m. The prompt cache has likely expired, so your next message will re-cache about 71k tokens.")
        );
    }

    #[test]
    fn a_compaction_nothing_followed_gets_its_own_sentence() {
        let lines = [user(0), reply("msg_1", 5_000, HOUR_USAGE), summary(10 * MINUTE), user(11 * MINUTE)];

        for after in [12 * MINUTE, 30 * DAY] {
            assert_eq!(
                note_after(&lines, after).as_deref(),
                Some("The conversation was compacted, so your next message will re-cache it."),
                "{after} ms on: warm or not, the cache does not hold what the compaction made"
            );
        }
    }

    #[test]
    fn a_compaction_with_no_reply_before_it_still_counts() {
        let lines = [summary(0)];

        assert_eq!(
            note_after(&lines, MINUTE).as_deref(),
            Some("The conversation was compacted, so your next message will re-cache it.")
        );
    }

    #[test]
    fn a_reply_after_the_compaction_brings_the_usual_note_back() {
        let lines = [
            user(0),
            reply("msg_1", 5_000, HOUR_USAGE),
            summary(10 * MINUTE),
            user(20 * MINUTE),
            reply("msg_2", 21 * MINUTE, FIVE_MINUTE_USAGE),
        ];

        assert_eq!(note_after(&lines, 22 * MINUTE), None, "replied to since, and warm");
        assert_eq!(
            note_after(&lines, HOUR).as_deref(),
            Some("Idle 40m. The prompt cache has likely expired, so your next message will re-cache about 2k tokens.")
        );
    }

    #[test]
    fn a_reply_kept_through_the_compaction_does_not_answer_it() {
        // Written after the summary, stamped before it: a line the compaction preserved.
        let lines = [user(0), summary(10 * MINUTE), reply("msg_kept", 5 * MINUTE, HOUR_USAGE)];

        assert_eq!(
            note_after(&lines, 3 * HOUR).as_deref(),
            Some("The conversation was compacted, so your next message will re-cache it.")
        );
    }

    #[test]
    fn a_reply_after_a_compaction_inherits_the_lifetime_from_before_it() {
        let lines = [
            user(0),
            reply("msg_1", 5_000, HOUR_USAGE),
            summary(10 * MINUTE),
            user(20 * MINUTE),
            reply("msg_2", 21 * MINUTE, READ_ONLY_USAGE),
        ];

        assert_eq!(
            note_after(&lines, 2 * HOUR).as_deref(),
            Some("Idle 1h 40m. The prompt cache has likely expired, so your next message will re-cache about 5k tokens.")
        );
    }

    #[test]
    fn lines_that_cannot_be_read_are_passed_over() {
        let lines = [
            "not json at all".to_string(),
            r#"{"type":"assistant","message":"not an object"}"#.to_string(),
            user(0),
            r#"{"type":"attachment","attachment":{"type":"hook","content":"\"assistant\""}}"#.to_string(),
            reply("msg_1", 5_000, HOUR_USAGE),
            String::new(),
            r#"{"type":"user","message":{"role":"user","content":"no time on this one"}}"#.to_string(),
        ];

        assert_eq!(
            note_after(&lines, 2 * HOUR).as_deref(),
            Some("Idle 2h 0m. The prompt cache has likely expired, so your next message will re-cache about 71k tokens.")
        );
    }

    #[test]
    fn nothing_to_read_means_no_note() {
        let tmp = tempfile::tempdir().unwrap();

        assert_eq!(note_at(&tmp.path().join("no-such.jsonl"), T0), None);
        assert_eq!(note_after(&[], DAY), None, "an empty transcript");
        assert_eq!(note_after(&[user(0)], DAY), None, "a question nobody answered");
    }

    #[test]
    fn the_window_goes_by_the_compaction_first_then_by_the_clock() {
        let record = Record {
            message_id: Some("msg_1".into()),
            anchor_at: Some(T0),
            lifetime: Some(Lifetime::OneHour),
            recache_tokens: Some(70_649),
            compacted_at: None,
        };

        assert_eq!(window(None, T0), Window::Unknown);
        assert_eq!(window(Some(&Record::default()), T0), Window::Unknown);
        assert_eq!(window(Some(&Record { lifetime: None, ..record.clone() }), T0 + DAY), Window::Unknown);
        assert_eq!(window(Some(&Record { anchor_at: None, ..record.clone() }), T0 + DAY), Window::Unknown);
        assert_eq!(window(Some(&record), T0 + HOUR - 1), Window::Warm);
        assert_eq!(window(Some(&record), T0 - DAY), Window::Warm, "a clock behind the transcript's");
        assert_eq!(window(Some(&record), T0 + HOUR), Window::Expired { idle_ms: HOUR, recache_tokens: Some(70_649) });
        assert_eq!(window(Some(&Record { compacted_at: Some(T0 + 5), ..record }), T0 + 6), Window::Compacted);
    }

    #[test]
    fn an_expired_window_with_no_count_names_the_conversation_instead() {
        assert_eq!(
            note(&Window::Expired { idle_ms: 3 * DAY + 2 * HOUR, recache_tokens: None }).as_deref(),
            Some("Idle 3d 2h. The prompt cache has likely expired, so your next message will re-cache the conversation.")
        );
        assert_eq!(note(&Window::Warm), None);
        assert_eq!(note(&Window::Unknown), None);
    }

    /// The vectors of the next three tests are what Node prints for the extension's own
    /// formatters and for `Date.parse`.
    #[test]
    fn token_counts_print_as_the_extension_prints_them() {
        let printed = [
            (1, "1"), (999, "999"), (1000, "1k"), (1499, "1k"), (1500, "2k"), (70649, "71k"), (71499, "71k"), (71500, "72k"),
            (999499, "999k"), (999500, "1000k"), (999999, "1000k"), (1000000, "1.0M"), (1049999, "1.0M"), (1050000, "1.1M"),
            (12345678, "12.3M"), (944321, "944k"),
        ];
        for (tokens, text) in printed {
            assert_eq!(short_count(tokens), text, "{tokens}");
        }
    }

    #[test]
    fn a_count_half_way_between_two_tenths_rounds_as_javascript_rounds_it() {
        // Every such count from 1.05M to 9.95M. Which way each goes depends on the double
        // nearest to it (1.15 is stored a hair under, 1.25 exactly), not on a rule of thumb.
        let printed = "1050000=1.1M 1150000=1.1M 1250000=1.3M 1350000=1.4M 1450000=1.4M 1550000=1.6M 1650000=1.6M 1750000=1.8M 1850000=1.9M 1950000=1.9M \
            2050000=2.0M 2150000=2.1M 2250000=2.3M 2350000=2.4M 2450000=2.5M 2550000=2.5M 2650000=2.6M 2750000=2.8M 2850000=2.9M 2950000=3.0M \
            3050000=3.0M 3150000=3.1M 3250000=3.3M 3350000=3.4M 3450000=3.5M 3550000=3.5M 3650000=3.6M 3750000=3.8M 3850000=3.9M 3950000=4.0M \
            4050000=4.0M 4150000=4.2M 4250000=4.3M 4350000=4.3M 4450000=4.5M 4550000=4.5M 4650000=4.7M 4750000=4.8M 4850000=4.8M 4950000=5.0M \
            5050000=5.0M 5150000=5.2M 5250000=5.3M 5350000=5.3M 5450000=5.5M 5550000=5.5M 5650000=5.7M 5750000=5.8M 5850000=5.8M 5950000=6.0M \
            6050000=6.0M 6150000=6.2M 6250000=6.3M 6350000=6.3M 6450000=6.5M 6550000=6.5M 6650000=6.7M 6750000=6.8M 6850000=6.8M 6950000=7.0M \
            7050000=7.0M 7150000=7.2M 7250000=7.3M 7350000=7.3M 7450000=7.5M 7550000=7.5M 7650000=7.7M 7750000=7.8M 7850000=7.8M 7950000=8.0M \
            8050000=8.1M 8150000=8.2M 8250000=8.3M 8350000=8.3M 8450000=8.4M 8550000=8.6M 8650000=8.7M 8750000=8.8M 8850000=8.8M 8950000=8.9M \
            9050000=9.1M 9150000=9.2M 9250000=9.3M 9350000=9.3M 9450000=9.4M 9550000=9.6M 9650000=9.7M 9750000=9.8M 9850000=9.8M 9950000=9.9M";
        let mut checked = 0;
        for pair in printed.split_whitespace() {
            let (tokens, text) = pair.split_once('=').unwrap();
            assert_eq!(short_count(tokens.parse().unwrap()), text, "{tokens}");
            checked += 1;
        }
        assert_eq!(checked, 90);
    }

    #[test]
    fn idle_times_print_as_the_extension_prints_them() {
        let printed = [
            (0, "0m"), (59999, "0m"), (60000, "1m"), (3599999, "59m"), (3600000, "1h 0m"), (3660000, "1h 1m"),
            (86399999, "23h 59m"), (86400000, "1d 0h"), (90000000, "1d 1h"), (1956000000, "22d 15h"), (-5, "0m"),
        ];
        for (ms, text) in printed {
            assert_eq!(idle(ms), text, "{ms}");
        }
    }

    #[test]
    fn transcript_times_parse_to_the_millisecond() {
        let parsed = [
            ("1970-01-01T00:00:00.000Z", 0),
            ("2026-07-30T10:00:00.000Z", T0),
            ("2026-09-22T01:44:57.917Z", 1_790_041_497_917),
            ("2024-02-29T23:59:59.999Z", 1_709_251_199_999),
            ("2026-10-07T02:02:56Z", 1_791_338_576_000),
            ("2026-10-07T10:02:56+08:00", 1_791_338_576_000),
            ("2026-10-07T02:02:56-05:30", 1_791_358_376_000),
            ("2026-10-07T10:02:56.5Z", 1_791_367_376_500),
            ("2026-10-07T10:02:56.123456Z", 1_791_367_376_123),
            ("1969-12-31T23:59:59.000Z", -1000),
        ];
        for (text, ms) in parsed {
            assert_eq!(iso_ms(text), Some(ms), "{text}");
        }
    }

    #[test]
    fn anything_else_is_not_a_time() {
        for text in ["", "yesterday", "2026-13-01T00:00:00Z", "2026-02-29T00:00:00Z", "2026-10-07T10:02:56", "2026-10-07T10:02:60Z",
            "2026-10-07T10:02:56.Z", "2026-10-07T10:02:56+0800", "2026-10-07X10:02:56Z", "2026-10-07T10:02:56Z trailing", "２０２６-10-07T10:02:56Z"]
        {
            assert_eq!(iso_ms(text), None, "{text:?}");
        }
    }

    #[test]
    fn the_note_of_a_saved_conversation_is_read_from_where_the_cli_keeps_it() {
        let mut env = EnvGuard::lock();
        let home = std::env::temp_dir().join("claude-eclipse-promptcache-note");
        let dir = home.join(".claude").join("projects").join("C--cachetest");
        let _ = fs::remove_dir_all(&home);
        fs::create_dir_all(&dir).unwrap();
        fs::write(dir.join("sess1.jsonl"), [user(0), reply("msg_1", 5_000, HOUR_USAGE)].join("\n")).unwrap();
        env.set_home(&home);

        let note = resume_note(r"C:\cachetest", "sess1");
        let unknown = resume_note(r"C:\cachetest", "no-such-session");
        let outside = resume_note(r"C:\cachetest", r"..\C--cachetest\sess1");
        let _ = fs::remove_dir_all(&home);

        // Written in July 2026 and read by today's clock: long cold, however long exactly.
        assert!(note.starts_with("Idle "), "{note}");
        assert!(note.ends_with(". The prompt cache has likely expired, so your next message will re-cache about 71k tokens."), "{note}");
        assert_eq!(unknown, "");
        assert_eq!(outside, "", "an id is a name, never a path");
    }
}
