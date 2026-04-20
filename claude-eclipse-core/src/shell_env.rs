//! Login-shell environment capture for GUI-launched Eclipse.
//!
//! Eclipse launched from Finder/Dock (macOS) or a GNOME/KDE menu entry
//! (Linux) inherits a minimal process environment that does **not**
//! include anything the user set only in their shell rc files
//! (`~/.zshrc`, `~/.bashrc`, `~/.profile`, …).  Two practical consequences:
//!
//!   1. `claude` installed via nvm / asdf / Homebrew / `npm -g` prefix is
//!      invisible on PATH, so spawning it from Eclipse fails even though
//!      `which claude` works in the user's terminal.
//!   2. Corporate proxy variables (`HTTP_PROXY`, `HTTPS_PROXY`, `NO_PROXY`)
//!      set only in shell rc are silently missing, which makes Claude CLI
//!      fail with "Unable to connect" on locked-down networks.
//!
//! This module runs the user's login shell **once** (lazy, OnceLock-cached)
//! to capture PATH and the three proxy vars.  Callers inject whichever
//! values are present into child processes (chat + PTY).
//!
//! macOS and Linux have **fully independent** capture paths.  They do
//! similar work but are never unified under `cfg(unix)` or any other
//! umbrella — future divergence (different shell quirks, different
//! fallback logic) should not require refactoring, and keeping the
//! branches split makes it obvious at a glance what each platform does.

use std::sync::{Mutex, OnceLock};

/// Snapshot of the vars we care about from the user's login shell.
#[derive(Default, Clone, Debug)]
pub struct CapturedEnv {
    pub path:        Option<String>,
    pub http_proxy:  Option<String>,
    pub https_proxy: Option<String>,
    pub no_proxy:    Option<String>,
}

/// Proxy overrides set from Java preferences. Takes precedence over
/// process env and captured shell env.
#[derive(Default, Clone, Debug)]
pub struct ProxyOverrides {
    pub http_proxy:  Option<String>,
    pub https_proxy: Option<String>,
    pub no_proxy:    Option<String>,
}

static PROXY_OVERRIDES: OnceLock<Mutex<ProxyOverrides>> = OnceLock::new();

fn overrides() -> &'static Mutex<ProxyOverrides> {
    PROXY_OVERRIDES.get_or_init(|| Mutex::new(ProxyOverrides::default()))
}

/// Called from JNI when user changes proxy preferences.
pub fn set_proxy_overrides(http: Option<String>, https: Option<String>, no_proxy: Option<String>) {
    let mut guard = overrides().lock().unwrap();
    guard.http_proxy = http;
    guard.https_proxy = https;
    guard.no_proxy = no_proxy;
}

/// Returns current proxy overrides.
pub fn get_proxy_overrides() -> ProxyOverrides {
    overrides().lock().unwrap().clone()
}

impl CapturedEnv {
    /// Returns the `(key, value)` pairs to set on a spawned child process.
    ///
    /// Precedence (highest wins): preference override → process env → captured shell.
    ///
    /// - `PATH` is injected whenever captured — it overrides the sparse
    ///   inherited PATH, which is the whole point of capturing it.
    /// - Proxy vars follow the 3-tier precedence.
    /// - Auto-localhost safeguard: if any proxy is active and NO_PROXY doesn't
    ///   include localhost, we prepend it to prevent MCP traffic being routed
    ///   through a corporate proxy that can't see loopback.
    pub fn to_inject(&self) -> Vec<(&'static str, String)> {
        let mut out = Vec::new();
        let overrides = get_proxy_overrides();

        if let Some(ref p) = self.path {
            out.push(("PATH", p.clone()));
        }

        let http = resolve_proxy_var(
            &overrides.http_proxy,
            &["HTTP_PROXY", "http_proxy"],
            &self.http_proxy,
        );
        let https = resolve_proxy_var(
            &overrides.https_proxy,
            &["HTTPS_PROXY", "https_proxy"],
            &self.https_proxy,
        );
        let mut no_proxy = resolve_proxy_var(
            &overrides.no_proxy,
            &["NO_PROXY", "no_proxy"],
            &self.no_proxy,
        );

        // Auto-localhost safeguard: if any proxy is active and NO_PROXY
        // doesn't include localhost, prepend it.
        if (http.is_some() || https.is_some()) {
            no_proxy = Some(ensure_localhost_in_no_proxy(no_proxy));
        }

        if let Some(v) = http {
            out.push(("HTTP_PROXY", v));
        }
        if let Some(v) = https {
            out.push(("HTTPS_PROXY", v));
        }
        if let Some(v) = no_proxy {
            out.push(("NO_PROXY", v));
        }

        out
    }
}

/// Resolves a proxy var using 3-tier precedence:
/// 1. Preference override (if set and non-empty)
/// 2. Process environment (check both upper and lower case)
/// 3. Captured shell env (if set)
fn resolve_proxy_var(
    pref_override: &Option<String>,
    env_keys: &[&str],
    captured: &Option<String>,
) -> Option<String> {
    // 1. Preference override
    if let Some(ref v) = pref_override {
        if !v.is_empty() {
            return Some(v.clone());
        }
    }

    // 2. Process environment
    for key in env_keys {
        if let Ok(v) = std::env::var(key) {
            if !v.is_empty() {
                return Some(v);
            }
        }
    }

    // 3. Captured shell env
    captured.clone()
}

/// Ensures localhost/127.0.0.1/::1 are in NO_PROXY.
fn ensure_localhost_in_no_proxy(current: Option<String>) -> String {
    let localhost_entries = ["localhost", "127.0.0.1", "::1"];

    let current_lower = current.as_ref()
        .map(|s| s.to_lowercase())
        .unwrap_or_default();

    let mut missing: Vec<&str> = Vec::new();
    for entry in &localhost_entries {
        if !current_lower.contains(entry) {
            missing.push(entry);
        }
    }

    if missing.is_empty() {
        return current.unwrap_or_default();
    }

    let prefix = missing.join(",");
    match current {
        Some(v) if !v.is_empty() => format!("{},{}", prefix, v),
        _ => prefix,
    }
}

static CAPTURED: OnceLock<CapturedEnv> = OnceLock::new();

/// Returns the captured login-shell environment, computing it on first call
/// and caching for the life of the process.  Safe to call on all platforms.
pub fn captured_env() -> &'static CapturedEnv {
    CAPTURED.get_or_init(capture_impl)
}

// ───────────────────────────────────────────────────────────────────────────
// macOS
// ───────────────────────────────────────────────────────────────────────────

#[cfg(target_os = "macos")]
fn capture_impl() -> CapturedEnv {
    use std::process::{Command, Stdio};
    use std::time::{Duration, Instant};

    // zsh is the macOS default since 10.15; honor $SHELL if the user changed it.
    let shell = std::env::var("SHELL").unwrap_or_else(|_| "/bin/zsh".to_string());

    // `-l` login shell (sources .zprofile, /etc/paths*, etc.)
    // `-i` interactive (sources .zshrc, where most users set PATH/proxy)
    // The delimiter lets us skip banner/prompt noise and parse multi-value
    // output even when any value contains whitespace.
    const DELIM: &str = "__CEC_DELIM__";
    let script = format!(
        "printf '%s%s%s%s%s%s%s%s%s' \
           '{d}' \"$PATH\" \
           '{d}' \"${{HTTP_PROXY:-$http_proxy}}\" \
           '{d}' \"${{HTTPS_PROXY:-$https_proxy}}\" \
           '{d}' \"${{NO_PROXY:-$no_proxy}}\" \
           '{d}'",
        d = DELIM,
    );

    let mut child = match Command::new(&shell)
        .args(["-l", "-i", "-c", &script])
        .stdin(Stdio::null())
        .stdout(Stdio::piped())
        .stderr(Stdio::null())
        .spawn()
    {
        Ok(c)  => c,
        Err(_) => return CapturedEnv::default(),
    };

    // 5s ceiling: a pathological .zshrc (blocks on network, waits for tty)
    // must not freeze the first chat/CLI spawn forever.
    let deadline = Instant::now() + Duration::from_secs(5);
    loop {
        match child.try_wait() {
            Ok(Some(_)) => break,
            Ok(None) => {
                if Instant::now() >= deadline {
                    let _ = child.kill();
                    return CapturedEnv::default();
                }
                std::thread::sleep(Duration::from_millis(50));
            }
            Err(_) => return CapturedEnv::default(),
        }
    }

    let output = match child.wait_with_output() {
        Ok(o) if o.status.success() => o,
        _ => return CapturedEnv::default(),
    };

    let stdout = String::from_utf8_lossy(&output.stdout);
    // Skip any banner noise before the first sentinel, then split the payload.
    let Some(start) = stdout.find(DELIM) else { return CapturedEnv::default(); };
    let parts: Vec<&str> = stdout[start + DELIM.len()..].split(DELIM).collect();
    let get = |i: usize| {
        parts.get(i)
             .map(|s| s.trim())
             .filter(|s| !s.is_empty())
             .map(|s| s.to_string())
    };

    CapturedEnv {
        path:        get(0),
        http_proxy:  get(1),
        https_proxy: get(2),
        no_proxy:    get(3),
    }
}

// ───────────────────────────────────────────────────────────────────────────
// Linux
// ───────────────────────────────────────────────────────────────────────────

#[cfg(target_os = "linux")]
fn capture_impl() -> CapturedEnv {
    use std::process::{Command, Stdio};
    use std::time::{Duration, Instant};

    // bash is the Ubuntu/Debian default; honor $SHELL if the user changed it.
    let shell = std::env::var("SHELL").unwrap_or_else(|_| "/bin/bash".to_string());

    // `-l` sources /etc/profile + ~/.profile (or ~/.bash_profile).
    // `-i` sources ~/.bashrc, where Ubuntu users typically set PATH/proxy.
    const DELIM: &str = "__CEC_DELIM__";
    let script = format!(
        "printf '%s%s%s%s%s%s%s%s%s' \
           '{d}' \"$PATH\" \
           '{d}' \"${{HTTP_PROXY:-$http_proxy}}\" \
           '{d}' \"${{HTTPS_PROXY:-$https_proxy}}\" \
           '{d}' \"${{NO_PROXY:-$no_proxy}}\" \
           '{d}'",
        d = DELIM,
    );

    let mut child = match Command::new(&shell)
        .args(["-l", "-i", "-c", &script])
        .stdin(Stdio::null())
        .stdout(Stdio::piped())
        .stderr(Stdio::null())
        .spawn()
    {
        Ok(c)  => c,
        Err(_) => return CapturedEnv::default(),
    };

    // 5s ceiling: a pathological .bashrc (blocks on network, waits for tty)
    // must not freeze the first chat/CLI spawn forever.
    let deadline = Instant::now() + Duration::from_secs(5);
    loop {
        match child.try_wait() {
            Ok(Some(_)) => break,
            Ok(None) => {
                if Instant::now() >= deadline {
                    let _ = child.kill();
                    return CapturedEnv::default();
                }
                std::thread::sleep(Duration::from_millis(50));
            }
            Err(_) => return CapturedEnv::default(),
        }
    }

    let output = match child.wait_with_output() {
        Ok(o) if o.status.success() => o,
        _ => return CapturedEnv::default(),
    };

    let stdout = String::from_utf8_lossy(&output.stdout);
    let Some(start) = stdout.find(DELIM) else { return CapturedEnv::default(); };
    let parts: Vec<&str> = stdout[start + DELIM.len()..].split(DELIM).collect();
    let get = |i: usize| {
        parts.get(i)
             .map(|s| s.trim())
             .filter(|s| !s.is_empty())
             .map(|s| s.to_string())
    };

    CapturedEnv {
        path:        get(0),
        http_proxy:  get(1),
        https_proxy: get(2),
        no_proxy:    get(3),
    }
}

// ───────────────────────────────────────────────────────────────────────────
// Windows
// ───────────────────────────────────────────────────────────────────────────

#[cfg(windows)]
fn capture_impl() -> CapturedEnv {
    // Windows GUI apps inherit the full user environment from the registry,
    // so there is nothing to capture — PATH already resolves `claude`, and
    // proxy vars set system-wide are already visible.
    CapturedEnv::default()
}
