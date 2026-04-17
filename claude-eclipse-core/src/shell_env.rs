//! macOS login-shell PATH capture.
//!
//! Eclipse.app launched from Finder/Dock does not inherit the user's shell
//! PATH (zshrc/bashrc/etc are only sourced by login shells), so spawning
//! `claude` fails even when `which claude` works in a terminal.  This module
//! runs the user's login shell once to capture PATH and lets callers inject
//! it into child processes.
//!
//! No-op on Windows and Linux — those inherit PATH correctly from the GUI
//! session, so `login_shell_path()` returns `None` and callers skip injection.

#[cfg(target_os = "macos")]
use std::sync::OnceLock;

#[cfg(target_os = "macos")]
static SHELL_PATH: OnceLock<Option<String>> = OnceLock::new();

/// Returns the PATH captured from an interactive login shell on macOS.
/// Returns `None` on other platforms, or on macOS if capture failed —
/// callers then spawn with the inherited PATH (today's behavior).
pub fn login_shell_path() -> Option<&'static str> {
    #[cfg(target_os = "macos")]
    {
        SHELL_PATH.get_or_init(capture_macos_path).as_deref()
    }
    #[cfg(not(target_os = "macos"))]
    {
        None
    }
}

#[cfg(target_os = "macos")]
fn capture_macos_path() -> Option<String> {
    use std::process::{Command, Stdio};
    use std::time::{Duration, Instant};

    // Honor the user's chosen shell; fall back to zsh, the macOS default since 10.15.
    let shell = std::env::var("SHELL").unwrap_or_else(|_| "/bin/zsh".to_string());

    // -l: login shell (sources .zprofile, /etc/paths, etc.)
    // -i: interactive shell (sources .zshrc where users typically edit PATH)
    // A sentinel lets us ignore banner/prompt noise from the profile.
    const SENTINEL: &str = "__CEC_PATH_DELIM__";
    let script = format!("printf '%s%s' '{}' \"$PATH\"", SENTINEL);

    let mut child = Command::new(&shell)
        .args(["-l", "-i", "-c", &script])
        .stdin(Stdio::null())
        .stdout(Stdio::piped())
        .stderr(Stdio::null())
        .spawn()
        .ok()?;

    // Bound the wait so a pathological .zshrc (e.g. one that blocks on network
    // or waits for a tty) can't freeze the first chat/CLI spawn forever.
    let deadline = Instant::now() + Duration::from_secs(5);
    loop {
        match child.try_wait() {
            Ok(Some(_)) => break,
            Ok(None) => {
                if Instant::now() >= deadline {
                    let _ = child.kill();
                    return None;
                }
                std::thread::sleep(Duration::from_millis(50));
            }
            Err(_) => return None,
        }
    }

    let output = child.wait_with_output().ok()?;
    if !output.status.success() {
        return None;
    }

    let stdout = String::from_utf8_lossy(&output.stdout);
    let idx = stdout.rfind(SENTINEL)?;
    let path = stdout[idx + SENTINEL.len()..].trim();
    if path.is_empty() {
        return None;
    }
    Some(path.to_string())
}
