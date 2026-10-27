//! Create Worktree: a git worktree for a new line of work, opened in the Claude
//! Code view as a folder of its own.
//!
//! The layout is the Claude Code CLI's own (`claude --worktree <name>`): the
//! worktree lives at `<main checkout>/.claude/worktrees/<name>` on a branch named
//! `worktree-<name>`, so the CLI and the view agree on where a worktree is.

use std::io::Read;
use std::path::{Path, PathBuf};
use std::process::{Command, Stdio};
use std::time::{Duration, Instant};

/// Local git commands: a status, a branch, a worktree. Seconds at most.
const LOCAL_TIMEOUT: Duration = Duration::from_secs(30);
/// `git fetch` goes over the network; a worktree that cannot fetch starts from HEAD.
const FETCH_TIMEOUT: Duration = Duration::from_secs(60);

/// Why a worktree name cannot be used, or `None` when it can.
///
/// The name becomes a folder and part of a branch name, so it keeps to what is safe
/// as both — the same rule the CLI applies to `--worktree <name>`.
pub fn name_error(name: &str) -> Option<&'static str> {
    if name.is_empty() {
        return Some("Name is required");
    }
    if name.chars().count() > 64 {
        return Some("Name must be 64 characters or fewer");
    }
    if !name.chars().all(|c| c.is_ascii_alphanumeric() || matches!(c, '.' | '_' | '-')) {
        return Some("Only letters, numbers, dots, hyphens, and underscores");
    }
    if name.contains("..") || name == "." {
        return Some("Name cannot be \".\" or \"..\" or contain \"..\"");
    }
    if name.ends_with('.') || name.ends_with(".lock") {
        return Some("Name cannot end with \".\" or \".lock\"");
    }
    if name.eq_ignore_ascii_case(".git") {
        return Some("Name cannot be \".git\"");
    }
    None
}

/// Creates the worktree `name` for the repository `start` is in, and returns its folder.
///
/// From inside a worktree the new one still goes beside the others, under the main
/// checkout. A worktree already at that place is opened, not made again. A branch
/// `worktree-<name>` left behind by an earlier worktree is checked out as it is, with
/// its commits — never deleted and made anew. Otherwise the branch starts from the
/// remote's default branch, freshly fetched, or from HEAD when there is no remote or
/// the fetch fails.
pub fn create(start: &Path, name: &str) -> Result<PathBuf, String> {
    if let Some(e) = name_error(name) {
        return Err(format!("Invalid worktree name: {e}"));
    }
    let repo = main_checkout(start)?;
    let path = repo.join(".claude").join("worktrees").join(name);
    if is_worktree_root(&path) {
        return Ok(path);
    }
    let branch = format!("worktree-{name}");
    // Registrations of worktrees whose folders are gone would block the add below.
    let _ = git(&repo, &["worktree", "prune"], LOCAL_TIMEOUT);
    if let Some(parent) = path.parent() {
        std::fs::create_dir_all(parent).map_err(|e| format!("Could not create {}: {e}", parent.display()))?;
    }
    let path_arg = path.to_string_lossy().to_string();
    let added = if git(&repo, &["rev-parse", "--verify", "--quiet", &format!("refs/heads/{branch}")], LOCAL_TIMEOUT).is_ok() {
        git(&repo, &["worktree", "add", &path_arg, &branch], LOCAL_TIMEOUT)
    } else {
        let base = starting_point(&repo);
        git(&repo, &["worktree", "add", "-b", &branch, &path_arg, &base], LOCAL_TIMEOUT)
    };
    match added {
        Ok(_) => Ok(path),
        Err(e) if e.contains("is already checked out at") || e.contains("is already used by worktree at") => Err(format!(
            "Cannot create worktree: the branch {branch} is already checked out elsewhere in this repository. \
             Use another name for this worktree."
        )),
        Err(e) => Err(e),
    }
}

/// [`create`] as the JSON the view reads: `{"ok":true,"path"}` or `{"ok":false,"error"}`.
pub fn create_json(start: &str, name: &str) -> String {
    match create(Path::new(start), name) {
        Ok(p) => serde_json::json!({ "ok": true, "path": p.to_string_lossy() }).to_string(),
        Err(e) => serde_json::json!({ "ok": false, "error": e }).to_string(),
    }
}

/// The main checkout of the repository `start` is in — for a worktree, the checkout
/// it belongs to, found through the git directory they share.
fn main_checkout(start: &Path) -> Result<PathBuf, String> {
    let not_repo = || "Not inside a git repository".to_string();
    // Plain --git-common-dir, not --path-format=absolute: that option is git 2.31+, and an
    // older git echoes it back as text. The answer may be relative to `start`.
    let common = git(start, &["rev-parse", "--git-common-dir"], LOCAL_TIMEOUT).map_err(|_| not_repo())?;
    let common = start.join(common.trim());
    let common = std::fs::canonicalize(&common).map(plain).unwrap_or(common);
    if common.file_name().is_some_and(|n| n == ".git") {
        if let Some(parent) = common.parent() {
            return Ok(parent.to_path_buf());
        }
    }
    // A bare repository, or a git directory kept elsewhere: the top of this checkout.
    git(start, &["rev-parse", "--show-toplevel"], LOCAL_TIMEOUT)
        .map(|s| PathBuf::from(s.trim()))
        .map_err(|_| not_repo())
}

/// Whether `path` is itself the top of a checkout (a worktree made earlier).
fn is_worktree_root(path: &Path) -> bool {
    if !path.is_dir() {
        return false;
    }
    match git(path, &["rev-parse", "--show-toplevel"], LOCAL_TIMEOUT) {
        Ok(top) => same_place(Path::new(top.trim()), path),
        Err(_) => false,
    }
}

/// A canonical path in the form git and the view take. On Windows `canonicalize` gives
/// the verbatim form (`\\?\C:\…`), which git cannot create folders through; a drive path
/// loses the prefix, a network one (`\\?\UNC\…`) keeps it, having no plain form to fall to.
fn plain(p: PathBuf) -> PathBuf {
    let s = p.to_string_lossy();
    match s.strip_prefix(r"\\?\") {
        Some(rest) if !rest.starts_with(r"UNC\") => PathBuf::from(rest),
        _ => p,
    }
}

fn same_place(a: &Path, b: &Path) -> bool {
    match (std::fs::canonicalize(a), std::fs::canonicalize(b)) {
        (Ok(x), Ok(y)) => x == y,
        _ => false,
    }
}

/// What a new branch starts from: `origin/<default branch>` when it can be fetched,
/// else `HEAD`.
fn starting_point(repo: &Path) -> String {
    if git(repo, &["remote", "get-url", "origin"], LOCAL_TIMEOUT).is_err() {
        return "HEAD".into();
    }
    let branch = default_branch(repo);
    match git(repo, &["fetch", "--quiet", "origin", &branch], FETCH_TIMEOUT) {
        Ok(_) => format!("origin/{branch}"),
        Err(_) => "HEAD".into(),
    }
}

/// The remote's default branch: what `origin/HEAD` points at, else `main` or `master`
/// if the remote has one, else `main`.
fn default_branch(repo: &Path) -> String {
    if let Ok(r) = git(repo, &["symbolic-ref", "--quiet", "refs/remotes/origin/HEAD"], LOCAL_TIMEOUT) {
        if let Some(b) = r.trim().strip_prefix("refs/remotes/origin/") {
            if !b.is_empty() {
                return b.to_string();
            }
        }
    }
    for b in ["main", "master"] {
        if git(repo, &["rev-parse", "--verify", "--quiet", &format!("refs/remotes/origin/{b}")], LOCAL_TIMEOUT).is_ok() {
            return b.to_string();
        }
    }
    "main".into()
}

/// Runs git in `dir`, its stdout on success, else what it said on stderr.
///
/// Every argument is its own token, never a shell line. Git is never allowed to ask
/// for anything — a credential prompt would wait forever on a stdin nobody writes to —
/// and a command that outlives `timeout` is stopped. Messages are kept in English,
/// so the ones this module recognises read the same in every locale.
fn git(dir: &Path, args: &[&str], timeout: Duration) -> Result<String, String> {
    let mut cmd = Command::new("git");
    cmd.args(args)
        .current_dir(dir)
        .env("GIT_TERMINAL_PROMPT", "0")
        .env("GCM_INTERACTIVE", "never")
        .env("LC_ALL", "C")
        .stdin(Stdio::null())
        .stdout(Stdio::piped())
        .stderr(Stdio::piped());
    #[cfg(windows)]
    {
        use std::os::windows::process::CommandExt;
        cmd.creation_flags(0x08000000); // CREATE_NO_WINDOW
    }
    let mut child = cmd.spawn().map_err(|e| format!("Could not run git: {e}"))?;

    // Both pipes drained while it runs, so neither can fill and stall it.
    let drain = |pipe: Option<Box<dyn Read + Send>>| {
        std::thread::spawn(move || {
            let mut bytes = Vec::new();
            if let Some(mut p) = pipe {
                let _ = p.read_to_end(&mut bytes);
            }
            String::from_utf8_lossy(&bytes).into_owned()
        })
    };
    let out = drain(child.stdout.take().map(|p| Box::new(p) as Box<dyn Read + Send>));
    let err = drain(child.stderr.take().map(|p| Box::new(p) as Box<dyn Read + Send>));

    let started = Instant::now();
    let status = loop {
        match child.try_wait() {
            Ok(Some(status)) => break status,
            Ok(None) if started.elapsed() < timeout => std::thread::sleep(Duration::from_millis(20)),
            Ok(None) => {
                crate::launch::kill_process_tree(&mut child);
                let _ = child.wait();
                return Err(format!("git {} did not finish in time.", args.first().unwrap_or(&"")));
            }
            Err(e) => return Err(format!("Could not run git: {e}")),
        }
    };
    let stdout = out.join().unwrap_or_default();
    let stderr = err.join().unwrap_or_default();
    if status.success() {
        Ok(stdout)
    } else {
        let said = stderr.trim();
        Err(if said.is_empty() { format!("git {} failed ({status}).", args.first().unwrap_or(&"")) } else { said.to_string() })
    }
}

// ---------------------------------------------------------------------------
// Tests
// ---------------------------------------------------------------------------

#[cfg(test)]
mod tests {
    use super::*;
    use std::process::Command;

    /// Runs git in `dir` for a test's setup, `None` when git is missing or fails.
    fn git_in(dir: &Path, args: &[&str]) -> Option<String> {
        let out = Command::new("git").args(args).current_dir(dir).output().ok()?;
        if !out.status.success() {
            return None;
        }
        Some(String::from_utf8_lossy(&out.stdout).trim().to_string())
    }

    /// A repository with one commit on `main`, or `None` when git is unavailable,
    /// so the suite still passes on a machine without it.
    fn repo_with_commit(dir: &Path) -> Option<()> {
        git_in(dir, &["init", "--quiet", "-b", "main"])?;
        git_in(dir, &["config", "user.email", "test@example.com"])?;
        git_in(dir, &["config", "user.name", "Test"])?;
        git_in(dir, &["config", "commit.gpgsign", "false"])?;
        std::fs::write(dir.join("a.txt"), "one\n").ok()?;
        git_in(dir, &["add", "a.txt"])?;
        git_in(dir, &["commit", "--quiet", "-m", "one"])?;
        Some(())
    }

    fn same_dir(a: &Path, b: &Path) -> bool {
        match (std::fs::canonicalize(a), std::fs::canonicalize(b)) {
            (Ok(x), Ok(y)) => x == y,
            _ => false,
        }
    }

    #[test]
    fn a_name_of_letters_digits_dots_dashes_and_underscores_is_accepted() {
        for ok in ["my-feature", "fix_1", "v2.0", "A", &"a".repeat(64)] {
            assert_eq!(name_error(ok), None, "{ok:?} should be accepted");
        }
    }

    #[test]
    fn names_that_cannot_be_a_folder_or_a_branch_are_refused() {
        assert_eq!(name_error(""), Some("Name is required"));
        assert_eq!(name_error(&"a".repeat(65)), Some("Name must be 64 characters or fewer"));
        for bad in ["a b", "a/b", "a\\b", "ä", "a:b", "x~1"] {
            assert_eq!(name_error(bad), Some("Only letters, numbers, dots, hyphens, and underscores"), "{bad:?}");
        }
        for bad in [".", "..", "a..b"] {
            assert_eq!(name_error(bad), Some("Name cannot be \".\" or \"..\" or contain \"..\""), "{bad:?}");
        }
        for bad in ["a.", "a.lock"] {
            assert_eq!(name_error(bad), Some("Name cannot end with \".\" or \".lock\""), "{bad:?}");
        }
        assert_eq!(name_error(".git"), Some("Name cannot be \".git\""));
        assert_eq!(name_error(".GIT"), Some("Name cannot be \".git\""));
    }

    #[test]
    fn creates_the_worktree_where_the_cli_puts_it_on_its_own_branch() {
        let tmp = tempfile::tempdir().unwrap();
        if repo_with_commit(tmp.path()).is_none() {
            return; // no git on this machine
        }
        let path = create(tmp.path(), "feat").expect("created");
        assert!(same_dir(&path, &tmp.path().join(".claude").join("worktrees").join("feat")), "{path:?}");
        assert_eq!(git_in(&path, &["symbolic-ref", "--short", "HEAD"]).as_deref(), Some("worktree-feat"));
        assert_eq!(
            git_in(&path, &["rev-parse", "HEAD"]),
            git_in(tmp.path(), &["rev-parse", "HEAD"]),
            "with no origin it starts from the current commit"
        );
        assert!(path.join("a.txt").is_file(), "the checkout has the files");
    }

    #[test]
    fn asking_again_for_the_same_name_opens_the_one_already_made() {
        let tmp = tempfile::tempdir().unwrap();
        if repo_with_commit(tmp.path()).is_none() {
            return;
        }
        let first = create(tmp.path(), "again").expect("created");
        let second = create(tmp.path(), "again").expect("reused");
        assert!(same_dir(&first, &second));
    }

    #[test]
    fn from_inside_a_worktree_the_new_one_goes_beside_it_not_inside_it() {
        let tmp = tempfile::tempdir().unwrap();
        if repo_with_commit(tmp.path()).is_none() {
            return;
        }
        let one = create(tmp.path(), "one").expect("created");
        let two = create(&one, "two").expect("created from inside the first");
        assert!(same_dir(&two, &tmp.path().join(".claude").join("worktrees").join("two")), "{two:?}");
    }

    #[test]
    fn from_a_subfolder_of_the_repository_it_goes_under_the_top() {
        let tmp = tempfile::tempdir().unwrap();
        if repo_with_commit(tmp.path()).is_none() {
            return;
        }
        let sub = tmp.path().join("src").join("deep");
        std::fs::create_dir_all(&sub).unwrap();
        let path = create(&sub, "sub").expect("created from a subfolder");
        assert!(same_dir(&path, &tmp.path().join(".claude").join("worktrees").join("sub")), "{path:?}");
    }

    #[test]
    fn a_folder_outside_any_repository_is_refused() {
        let tmp = tempfile::tempdir().unwrap();
        if git_in(tmp.path(), &["--version"]).is_none() {
            return;
        }
        assert_eq!(create(tmp.path(), "feat"), Err("Not inside a git repository".to_string()));
    }

    #[test]
    fn a_bad_name_creates_nothing() {
        let tmp = tempfile::tempdir().unwrap();
        if repo_with_commit(tmp.path()).is_none() {
            return;
        }
        assert!(create(tmp.path(), "a/b").is_err());
        assert!(!tmp.path().join(".claude").exists(), "nothing was made");
    }

    #[test]
    fn a_branch_left_from_an_earlier_worktree_is_picked_up_with_its_commits() {
        let tmp = tempfile::tempdir().unwrap();
        if repo_with_commit(tmp.path()).is_none() {
            return;
        }
        // worktree-old exists with a commit of its own and is checked out nowhere.
        git_in(tmp.path(), &["checkout", "--quiet", "-b", "worktree-old"]).unwrap();
        std::fs::write(tmp.path().join("b.txt"), "two\n").unwrap();
        git_in(tmp.path(), &["add", "b.txt"]).unwrap();
        git_in(tmp.path(), &["commit", "--quiet", "-m", "two"]).unwrap();
        let tip = git_in(tmp.path(), &["rev-parse", "HEAD"]);
        git_in(tmp.path(), &["checkout", "--quiet", "main"]).unwrap();

        let path = create(tmp.path(), "old").expect("created on the existing branch");
        assert_eq!(git_in(&path, &["rev-parse", "HEAD"]), tip, "the branch keeps its own commit");
        assert!(path.join("b.txt").is_file());
    }

    #[test]
    fn with_an_origin_it_starts_from_the_remote_default_branch() {
        let tmp = tempfile::tempdir().unwrap();
        let upstream = tmp.path().join("upstream");
        std::fs::create_dir(&upstream).unwrap();
        if repo_with_commit(&upstream).is_none() {
            return;
        }
        let clone = tmp.path().join("clone");
        git_in(tmp.path(), &["clone", "--quiet", upstream.to_str().unwrap(), clone.to_str().unwrap()]).unwrap();
        git_in(&clone, &["config", "user.email", "test@example.com"]).unwrap();
        git_in(&clone, &["config", "user.name", "Test"]).unwrap();
        git_in(&clone, &["config", "commit.gpgsign", "false"]).unwrap();
        // A local commit the remote does not have: the worktree must not start from it.
        std::fs::write(clone.join("local.txt"), "local\n").unwrap();
        git_in(&clone, &["add", "local.txt"]).unwrap();
        git_in(&clone, &["commit", "--quiet", "-m", "local"]).unwrap();

        let path = create(&clone, "fresh").expect("created");
        assert_eq!(
            git_in(&path, &["rev-parse", "HEAD"]),
            git_in(&upstream, &["rev-parse", "HEAD"]),
            "starts from origin/main"
        );
        assert!(!path.join("local.txt").exists());
    }

    #[test]
    fn the_view_gets_the_folder_or_the_reason_as_json() {
        let tmp = tempfile::tempdir().unwrap();
        if git_in(tmp.path(), &["--version"]).is_none() {
            return;
        }
        let v: serde_json::Value = serde_json::from_str(&create_json(&tmp.path().to_string_lossy(), "x")).unwrap();
        assert_eq!(v["ok"], false);
        assert_eq!(v["error"], "Not inside a git repository");
    }
}
