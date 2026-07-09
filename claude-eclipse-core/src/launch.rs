//! Cross-platform spawning of the `claude` CLI.
//!
//! On macOS/Linux `Command::new(claude_cmd)` already does the right thing:
//! the kernel does PATH lookup for a bare name, and Rust's default argv
//! escaping matches what the child's C runtime un-escapes.
//!
//! Windows needs help on two fronts, and BOTH bit real users (issues #64/#83):
//!
//!   1. **Bare name resolution.** `claude` installed via `npm -g` is
//!      `claude.cmd` on `%PATH%`. `Command::new("claude")` calls
//!      `CreateProcess("claude")`, which does NOT consult `PATHEXT`, so it
//!      fails with `CreateProcess error=2` even though `where claude` works in
//!      a shell. We resolve the name against PATH + PATHEXT ourselves.
//!
//!   2. **`.cmd`/`.bat` argument mangling.** A batch file can't be executed by
//!      `CreateProcess` directly, so Rust routes it through `cmd.exe /c` and —
//!      since 1.77, for CVE-2024-24576 (BatBadBut) — applies *batch-specific*
//!      escaping that doubles every `"` into `""`. That's correct for a script
//!      that reads args via `%~1`, but the npm `claude.cmd` shim forwards the
//!      RAW tail (`... claude.exe %*`). cmd.exe collapses the doubled quotes,
//!      so `claude.exe` receives corrupted JSON for `--mcp-config`:
//!          {"mcpServers":{...}}  →  {mcpServers:{...}}
//!      Claude then can't parse it as inline JSON and falls back to treating
//!      the value as a FILE PATH (relative to the workspace), producing
//!      "MCP config file not found: C:\ws\{mcpServers:...".
//!
//! Fix: when the resolved command is a `.cmd`/`.bat`, we invoke `cmd.exe /c`
//! OURSELVES and pass the whole command line via `raw_arg`, quoting each token
//! with the standard MSVCRT convention that `claude.exe` un-escapes — so the
//! shim's `%*` forwards intact JSON. `.exe` targets spawn directly (Rust's
//! default escaping is correct there).

use std::process::Command;

/// The default command used when the Claude-command preference is left blank.
/// Matches the Java-side `Constants.DEFAULT_CLAUDE_CMD`; on every platform we
/// resolve it against PATH before spawning.
const DEFAULT_CLAUDE_CMD: &str = "claude";

/// Builds a `Command` for the `claude` CLI with `args`, handling Windows
/// PATH/PATHEXT resolution and `.cmd`/`.bat` quoting. The caller still sets
/// `current_dir`, stdio, env vars and (Windows) `creation_flags` — this only
/// owns the program + argument wiring so JSON args survive on every platform.
///
/// `claude_cmd` may be an absolute path, a bare name (`claude`), or empty
/// (→ the default `claude`, resolved against PATH like macOS/Linux do).
pub fn claude_command(claude_cmd: &str, args: &[String]) -> Command {
    let requested = if claude_cmd.trim().is_empty() {
        DEFAULT_CLAUDE_CMD
    } else {
        claude_cmd.trim()
    };

    #[cfg(windows)]
    {
        build_windows(requested, args)
    }
    #[cfg(not(windows))]
    {
        // The kernel resolves a bare name via PATH; absolute paths pass through.
        let mut cmd = Command::new(requested);
        cmd.args(args);
        cmd
    }
}

// ───────────────────────────────────────────────────────────────────────────
// Windows
// ───────────────────────────────────────────────────────────────────────────

#[cfg(windows)]
fn build_windows(requested: &str, args: &[String]) -> Command {
    use std::os::windows::process::CommandExt;

    // Resolve to a concrete file so we can (a) find `claude.cmd` for a bare
    // name and (b) know whether it's a batch script or a real executable.
    let resolved = resolve_windows(requested);

    let is_batch = resolved
        .rsplit('.')
        .next()
        .map(|ext| ext.eq_ignore_ascii_case("cmd") || ext.eq_ignore_ascii_case("bat"))
        .unwrap_or(false);

    if !is_batch {
        // Real .exe (or unresolved bare name we couldn't map — let CreateProcess
        // try, same as before). Rust's default argv escaping is correct here.
        let mut cmd = Command::new(&resolved);
        cmd.args(args);
        return cmd;
    }

    // Batch shim: drive cmd.exe ourselves so Rust's BatBadBut `"`-doubling
    // never touches our args. We build the tail with MSVCRT quoting, which is
    // exactly what the `%*`-forwarded `claude.exe` un-escapes.
    let comspec = std::env::var("ComSpec").unwrap_or_else(|_| "cmd.exe".to_string());
    let mut line = String::from("/c ");
    line.push_str(&quote_cmd_program(&resolved));
    for arg in args {
        line.push(' ');
        line.push_str(&quote_msvcrt(arg));
    }

    let mut cmd = Command::new(comspec);
    // raw_arg bypasses Rust's per-arg escaping entirely — we hand cmd.exe the
    // exact command line, already correctly quoted.
    cmd.raw_arg(&line);
    cmd
}

/// Resolves a Windows command name to a concrete path. Absolute/relative paths
/// with an extension are returned as-is; a bare name is searched on `%PATH%`
/// with each `%PATHEXT%` suffix (so `claude` → `...\claude.cmd`). Returns the
/// input unchanged when nothing matches (CreateProcess then reports the error,
/// preserving the old behavior).
#[cfg(windows)]
fn resolve_windows(requested: &str) -> String {
    use std::path::Path;

    let p = Path::new(requested);

    // Already a path (contains a separator) AND already has an extension → use
    // it directly. A path without an extension still needs PATHEXT probing.
    let has_sep = requested.contains('\\') || requested.contains('/');
    if has_sep && p.extension().is_some() && p.is_file() {
        return requested.to_string();
    }

    let pathext: Vec<String> = std::env::var("PATHEXT")
        .unwrap_or_else(|_| ".COM;.EXE;.BAT;.CMD".to_string())
        .split(';')
        .filter(|s| !s.is_empty())
        .map(|s| s.to_string())
        .collect();

    // If the name already carries a known extension, don't append another.
    let already_has_ext = p
        .extension()
        .map(|e| {
            let dotted = format!(".{}", e.to_string_lossy());
            pathext.iter().any(|x| x.eq_ignore_ascii_case(&dotted))
        })
        .unwrap_or(false);

    // A path with a separator: probe extensions next to it, don't walk PATH.
    if has_sep {
        if already_has_ext && p.is_file() {
            return requested.to_string();
        }
        for ext in &pathext {
            let candidate = format!("{}{}", requested, ext);
            if Path::new(&candidate).is_file() {
                return candidate;
            }
        }
        return requested.to_string();
    }

    // Bare name: walk %PATH%, probing PATHEXT in each directory.
    let path_var = std::env::var("PATH").unwrap_or_default();
    for dir in std::env::split_paths(&path_var) {
        if already_has_ext {
            let candidate = dir.join(requested);
            if candidate.is_file() {
                return candidate.to_string_lossy().into_owned();
            }
        }
        for ext in &pathext {
            let candidate = dir.join(format!("{}{}", requested, ext));
            if candidate.is_file() {
                return candidate.to_string_lossy().into_owned();
            }
        }
    }

    requested.to_string()
}

/// Quotes the program (the `.cmd` path) for a `cmd.exe /c` line. cmd.exe parses
/// the program with its own rules, not MSVCRT: wrap in double quotes when it
/// contains spaces or cmd metacharacters; there is no in-value quote to escape
/// for a real filesystem path (Windows filenames can't contain `"`).
#[cfg(windows)]
fn quote_cmd_program(path: &str) -> String {
    let needs_quotes = path.is_empty()
        || path.chars().any(|c| " \t&()[]{}^=;!'+,`~".contains(c));
    if needs_quotes {
        format!("\"{}\"", path)
    } else {
        path.to_string()
    }
}

/// Quotes one argument using the standard MSVCRT `CommandLineToArgvW`
/// convention that `claude.exe` (a normal C-runtime program) un-escapes:
/// wrap in double quotes and backslash-escape any embedded `"`, doubling the
/// run of backslashes that immediately precedes a `"` (or the closing quote).
/// The npm `claude.cmd` shim forwards this tail verbatim via `%*`, so what we
/// write here is exactly what the CLI sees.
#[cfg(windows)]
fn quote_msvcrt(arg: &str) -> String {
    // Unquoted is safe only for a non-empty arg with no whitespace or quotes.
    if !arg.is_empty() && !arg.chars().any(|c| c == ' ' || c == '\t' || c == '"') {
        return arg.to_string();
    }

    let mut out = String::with_capacity(arg.len() + 2);
    out.push('"');
    let mut backslashes = 0usize;
    for c in arg.chars() {
        match c {
            '\\' => {
                backslashes += 1;
            }
            '"' => {
                // Escape the backslash run (double it) then escape the quote.
                out.extend(std::iter::repeat('\\').take(backslashes * 2 + 1));
                out.push('"');
                backslashes = 0;
            }
            _ => {
                out.extend(std::iter::repeat('\\').take(backslashes));
                out.push(c);
                backslashes = 0;
            }
        }
    }
    // Trailing backslashes precede the closing quote → double them.
    out.extend(std::iter::repeat('\\').take(backslashes * 2));
    out.push('"');
    out
}

#[cfg(all(test, windows))]
mod tests {
    use super::*;

    #[test]
    fn json_arg_quoted_for_msvcrt() {
        // The exact --mcp-config value from the bug report.
        let cfg = r#"{"mcpServers":{"eclipse":{"type":"sse","url":"http://127.0.0.1:10002/sse"}}}"#;
        let q = quote_msvcrt(cfg);
        // Wrapped in quotes; every inner `"` backslash-escaped, NOT doubled.
        assert!(q.starts_with('"') && q.ends_with('"'));
        assert!(q.contains(r#"\"mcpServers\""#), "inner quotes escaped: {q}");
        assert!(!q.contains(r#""""#), "must NOT double-quote (BatBadBut bug): {q}");
    }

    #[test]
    fn plain_arg_unquoted() {
        assert_eq!(quote_msvcrt("--verbose"), "--verbose");
        assert_eq!(quote_msvcrt("mcp__ide__openDiff"), "mcp__ide__openDiff");
    }

    #[test]
    fn arg_with_space_quoted() {
        assert_eq!(quote_msvcrt("hello world"), r#""hello world""#);
    }

    #[test]
    fn empty_arg_becomes_empty_quotes() {
        assert_eq!(quote_msvcrt(""), r#""""#);
    }

    #[test]
    fn trailing_backslashes_doubled_before_close() {
        // A path-like value ending in a backslash inside a quoted arg.
        assert_eq!(quote_msvcrt(r"a b\"), r#""a b\\""#);
    }
}
