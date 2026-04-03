use std::path::{Path, PathBuf};
use std::sync::{Mutex, OnceLock};

static LOCK_FILE_PATH: OnceLock<Mutex<Option<PathBuf>>> = OnceLock::new();

fn state() -> &'static Mutex<Option<PathBuf>> {
    LOCK_FILE_PATH.get_or_init(|| Mutex::new(None))
}

// ---------------------------------------------------------------------------
// Stale lock file cleanup
//
// Claude CLI reads every *.lock file in ~/.claude/ide/ and tries to connect
// to each IDE it finds.  When an Eclipse instance crashes without removing
// its lock file, or multiple live instances are open, Claude may attempt
// connections with mismatched auth tokens and fail.  We scan on startup and
// remove any lock file whose PID is no longer alive.
// ---------------------------------------------------------------------------

#[cfg(windows)]
#[link(name = "kernel32")]
extern "system" {
    fn OpenProcess(desired_access: u32, inherit_handle: i32, pid: u32) -> isize;
    fn CloseHandle(handle: isize) -> i32;
}

fn is_pid_alive(pid: u32) -> bool {
    #[cfg(windows)]
    {
        // PROCESS_QUERY_LIMITED_INFORMATION = 0x1000
        let h = unsafe { OpenProcess(0x1000, 0, pid) };
        if h == 0 { return false; }
        unsafe { CloseHandle(h) };
        true
    }
    #[cfg(target_os = "linux")]
    { std::path::Path::new(&format!("/proc/{}", pid)).exists() }
    #[cfg(not(any(windows, target_os = "linux")))]
    { let _ = pid; true } // macOS etc — conservatively assume alive
}

/// Remove any *.lock files in `dir` whose recorded PID is no longer running.
fn remove_stale_lock_files(dir: &Path) {
    let entries = match std::fs::read_dir(dir) {
        Ok(e) => e,
        Err(_) => return,
    };
    for entry in entries.flatten() {
        let path = entry.path();
        if path.extension().and_then(|e| e.to_str()) != Some("lock") {
            continue;
        }
        if let Ok(text) = std::fs::read_to_string(&path) {
            if let Ok(json) = serde_json::from_str::<serde_json::Value>(&text) {
                if let Some(pid) = json.get("pid").and_then(|v| v.as_u64()) {
                    if !is_pid_alive(pid as u32) {
                        let _ = std::fs::remove_file(&path);
                    }
                }
            }
        }
    }
}

/// Writes ~/.claude/ide/<port>.lock with the JSON content Claude CLI expects.
///
/// `project_paths_json` must be a JSON array string, e.g. `["/path/a","/path/b"]`.
pub fn write(port: u16, auth_token: &str, workspace_root: &str, project_paths_json: &str) {
    let dir = lock_file_dir();

    // Remove stale lock files from dead processes before advertising ourselves.
    remove_stale_lock_files(&dir);

    if let Err(e) = std::fs::create_dir_all(&dir) {
        eprintln!("lock_file: create_dir_all {:?}: {}", dir, e);
        return;
    }

    let path = dir.join(format!("{}.lock", port));

    // Parse the project paths array so we can include it in the JSON object.
    let folders: serde_json::Value = serde_json::from_str(project_paths_json)
        .unwrap_or_else(|_| serde_json::json!([]));

    let pid = std::process::id();

    let content = serde_json::json!({
        "port":            port,
        "authToken":       auth_token,
        "version":         "0.2.0",
        "ideName":         "Eclipse",
        "pid":             pid,
        "workspaceFolder": workspace_root,
        "workspaceFolders": folders
    })
    .to_string();

    match std::fs::write(&path, content) {
        Ok(_) => {
            *state().lock().unwrap() = Some(path);
        }
        Err(e) => eprintln!("lock_file: write {:?}: {}", path, e),
    }
}

/// Removes the lock file created by the last call to `write`.
pub fn remove() {
    let mut guard = state().lock().unwrap();
    if let Some(path) = guard.take() {
        if let Err(e) = std::fs::remove_file(&path) {
            eprintln!("lock_file: remove {:?}: {}", path, e);
        }
    }
}

fn lock_file_dir() -> PathBuf {
    if let Ok(config_dir) = std::env::var("CLAUDE_CONFIG_DIR") {
        if !config_dir.is_empty() {
            return PathBuf::from(config_dir).join("ide");
        }
    }
    let home = std::env::var("USERPROFILE")
        .or_else(|_| std::env::var("HOME"))
        .unwrap_or_else(|_| ".".to_string());
    PathBuf::from(home).join(".claude").join("ide")
}
