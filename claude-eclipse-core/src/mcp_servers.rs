//! The MCP servers window (the `/` menu → MCP servers), reached the two ways the
//! VS Code extension reaches its own:
//!
//! * **What the running conversation sees** — status, tools, enable/disable,
//!   reconnect, sign-in — is asked of the tab's live process over the control
//!   channel it already has (`mcp_status`, `mcp_toggle`, `mcp_reconnect`,
//!   `mcp_authenticate`, `mcp_clear_auth`). Replies come back on the reader thread
//!   and reach the page as `onMcp`, matched by a token the page chose.
//! * **What is configured** — adding and removing a server — is the CLI's own
//!   `claude mcp add|remove`, run in the conversation's folder, so the files it
//!   writes (`~/.claude.json`, `.mcp.json`) are written by the thing that owns
//!   their format. The same argv the extension builds, flag for flag.
//!
//! Not `mcp.rs`: that is the IDE's own MCP server, the one this window hides.

use std::io::Read;
use std::process::Stdio;
use std::time::{Duration, Instant};

#[cfg(windows)]
use std::os::windows::process::CommandExt;

/// Request ids of the window's control requests: this prefix, then the page's token.
pub(crate) const REQUEST_PREFIX: &str = "eclipse-mcp-";

/// The control requests the page may send. Deliberately not `mcp_set_servers`: that
/// replaces every server set that way, `claude-in-chrome` included, behind the
/// browser banner's back.
const ALLOWED_SUBTYPES: [&str; 5] =
    ["mcp_status", "mcp_toggle", "mcp_reconnect", "mcp_authenticate", "mcp_clear_auth"];

/// Our own server (`--mcp-config`). Hidden from the window and refused as a name,
/// as the extension does with `claude-vscode`: disabling it would cut the plugin off
/// from the conversation it is showing.
pub(crate) const RESERVED_NAME: &str = "eclipse";

/// How long `claude mcp add|remove` may take — the extension's limit.
const CONFIG_TIMEOUT: Duration = Duration::from_secs(30);

/// A token the page chose to match a reply to its request. Kept to characters that
/// cannot change the meaning of the request id they end up in.
fn valid_token(token: &str) -> bool {
    !token.is_empty()
        && token.len() <= 64
        && token.chars().all(|c| c.is_ascii_alphanumeric() || c == '-' || c == '_')
}

/// The control-request line for one of the window's requests, or `None` when the
/// token or the subtype is not one the window may send.
pub(crate) fn request_line(token: &str, request: &serde_json::Value) -> Option<String> {
    if !valid_token(token) {
        return None;
    }
    let subtype = request["subtype"].as_str()?;
    if !ALLOWED_SUBTYPES.contains(&subtype) {
        return None;
    }
    Some(
        serde_json::json!({
            "type": "control_request",
            "request_id": format!("{REQUEST_PREFIX}{token}"),
            "request": request
        })
        .to_string(),
    )
}

/// What the page is told about a reply to one of its requests:
/// `{"token","ok":true,"response"}` or `{"token","ok":false,"error"}`.
/// `None` when the reply is to someone else's request.
pub(crate) fn reply_json(inner: &serde_json::Value) -> Option<String> {
    let token = inner["request_id"].as_str()?.strip_prefix(REQUEST_PREFIX)?;
    let json = if inner["subtype"].as_str() == Some("error") {
        let e = inner["error"].as_str().unwrap_or("").trim();
        serde_json::json!({
            "token": token,
            "ok": false,
            "error": if e.is_empty() { "Unknown error" } else { e }
        })
    } else {
        serde_json::json!({ "token": token, "ok": true, "response": inner["response"] })
    };
    Some(json.to_string())
}

/// The extension's rule for a new server's name, worded as it words it.
fn check_name(name: &str) -> Result<(), String> {
    if name.is_empty() {
        return Err("Server name is required.".into());
    }
    if !name.chars().all(|c| c.is_ascii_alphanumeric() || c == '-' || c == '_') {
        return Err(format!(
            "Invalid name {name}. Names can only contain letters, numbers, hyphens, and underscores."
        ));
    }
    if name == RESERVED_NAME {
        return Err(format!("{RESERVED_NAME} is reserved for the plugin itself."));
    }
    Ok(())
}

fn check_scope(scope: &str) -> Result<(), String> {
    match scope {
        "local" | "user" | "project" => Ok(()),
        _ => Err(format!("Unknown scope {scope}.")),
    }
}

/// The strings in a JSON array, trimmed, blanks dropped.
fn lines(v: &serde_json::Value) -> Vec<String> {
    v.as_array()
        .map(|a| {
            a.iter()
                .filter_map(|x| x.as_str())
                .map(|s| s.trim().to_string())
                .filter(|s| !s.is_empty())
                .collect()
        })
        .unwrap_or_default()
}

/// The `claude` argv for an add or remove from the window. The page has already
/// checked all of this to word its own errors; it is checked again here because
/// these become the arguments of a process.
///
/// * add: `{"op":"add","name","scope","config":{"transport":"stdio","command","args":[…],"env":["K=V"]}}`
///   or `{"transport":"http"|"sse","url","headers":["Name: value"]}`
/// * remove: `{"op":"remove","name","scope"}`
fn config_args(op: &serde_json::Value) -> Result<Vec<String>, String> {
    let name = op["name"].as_str().unwrap_or("").trim().to_string();
    let scope = op["scope"].as_str().unwrap_or("");
    check_scope(scope)?;
    match op["op"].as_str() {
        Some("remove") => {
            // Any name the config holds, since it came from the config: hand-edited
            // files are not held to the add rule.
            if name.is_empty() {
                return Err("Server name is required.".into());
            }
            Ok(vec!["mcp".into(), "remove".into(), "--scope".into(), scope.into(), "--".into(), name])
        }
        Some("add") => {
            check_name(&name)?;
            let cfg = &op["config"];
            let transport = cfg["transport"].as_str().unwrap_or("");
            let mut args: Vec<String> =
                vec!["mcp".into(), "add".into(), "--scope".into(), scope.into(), "--transport".into()];
            match transport {
                "stdio" => {
                    let command = cfg["command"].as_str().unwrap_or("").trim().to_string();
                    if command.is_empty() {
                        return Err("Command is required.".into());
                    }
                    args.push("stdio".into());
                    for (i, kv) in lines(&cfg["env"]).into_iter().enumerate() {
                        let (key, value) = kv.split_once('=').unwrap_or(("", ""));
                        if key.trim().is_empty() {
                            return Err(format!("Environment variables must be KEY=value (line {}).", i + 1));
                        }
                        args.push("--env".into());
                        args.push(format!("{}={}", key.trim(), value.trim()));
                    }
                    args.push("--".into());
                    args.push(name);
                    args.push(command);
                    args.extend(lines(&cfg["args"]));
                }
                "http" | "sse" => {
                    let url = cfg["url"].as_str().unwrap_or("").trim().to_string();
                    if url.is_empty() {
                        return Err("URL is required.".into());
                    }
                    args.push(transport.into());
                    for (i, h) in lines(&cfg["headers"]).into_iter().enumerate() {
                        if h.find(':').map_or(true, |c| c == 0) {
                            return Err(format!("Headers must be \"Header-Name: value\" (line {}).", i + 1));
                        }
                        args.push("--header".into());
                        args.push(h);
                    }
                    args.push("--".into());
                    args.push(name);
                    args.push(url);
                }
                _ => return Err(format!("Unknown transport {transport}.")),
            }
            Ok(args)
        }
        _ => Err("Unknown MCP server operation.".into()),
    }
}

/// The argv for a debug line: env values and header values are credentials, so
/// they go, and so does everything after the server name (a URL or a command line
/// can carry a token too). The extension logs the same shape.
fn redacted(args: &[String]) -> String {
    let mut out: Vec<String> = Vec::new();
    let mut i = 0;
    while i < args.len() && args[i] != "--" {
        let a = &args[i];
        if (a == "--env" || a == "--header") && i + 1 < args.len() {
            let v = &args[i + 1];
            let sep = if a == "--env" { '=' } else { ':' };
            let key = v.find(sep).map_or("", |p| &v[..p]);
            out.push(a.clone());
            out.push(if sep == '=' { format!("{key}=<redacted>") } else { format!("{key}: <redacted>") });
            i += 2;
            continue;
        }
        out.push(a.clone());
        i += 1;
    }
    if i < args.len() {
        out.push("--".into());
        out.push(args.get(i + 1).cloned().unwrap_or_default());
        if i + 2 < args.len() {
            out.push("…".into());
        }
    }
    out.join(" ")
}

/// Adds or removes a server with `claude mcp add|remove`, run in `cwd` — the
/// conversation's folder, which is what a Local server is keyed on and where a
/// Project server's `.mcp.json` goes. **Blocking**, up to [`CONFIG_TIMEOUT`].
///
/// Returns `{"token","ok":true}` or `{"token","ok":false,"error"}`, the error being
/// what the CLI said.
pub fn edit_config(claude_cmd: &str, cwd: &str, token: &str, op_json: &str) -> String {
    let result = serde_json::from_str::<serde_json::Value>(op_json)
        .map_err(|_| "Invalid request.".to_string())
        .and_then(|op| config_args(&op))
        .and_then(|args| run_claude(claude_cmd, cwd, &args));
    match result {
        Ok(()) => serde_json::json!({ "token": token, "ok": true }),
        Err(e) => serde_json::json!({ "token": token, "ok": false, "error": e }),
    }
    .to_string()
}

fn run_claude(claude_cmd: &str, cwd: &str, args: &[String]) -> Result<(), String> {
    if cwd.is_empty() || !std::path::Path::new(cwd).is_dir() {
        return Err(format!("Working directory not found: {cwd}"));
    }
    if crate::is_debug() {
        eprintln!("[mcp] claude {} (in {cwd})", redacted(args));
    }
    let mut cmd = crate::launch::claude_command(claude_cmd, args);
    cmd.current_dir(cwd)
        .stdin(Stdio::null())
        .stdout(Stdio::piped())
        .stderr(Stdio::piped());

    #[cfg(windows)]
    cmd.creation_flags(0x08000000); // CREATE_NO_WINDOW

    for (k, v) in crate::shell_env::captured_env().to_inject() {
        cmd.env(k, v);
    }
    let mut child = cmd.spawn().map_err(|e| format!("Could not run Claude Code: {e}"))?;

    // Both pipes drained while it runs, so a chatty CLI cannot fill one and stall.
    let drain = |pipe: Option<Box<dyn Read + Send>>| {
        std::thread::spawn(move || {
            let mut s = String::new();
            if let Some(mut p) = pipe {
                let _ = p.read_to_string(&mut s);
            }
            s
        })
    };
    let out = drain(child.stdout.take().map(|p| Box::new(p) as Box<dyn Read + Send>));
    let err = drain(child.stderr.take().map(|p| Box::new(p) as Box<dyn Read + Send>));

    let started = Instant::now();
    let status = loop {
        match child.try_wait() {
            Ok(Some(status)) => break status,
            Ok(None) if started.elapsed() < CONFIG_TIMEOUT => std::thread::sleep(Duration::from_millis(50)),
            Ok(None) => {
                crate::launch::kill_process_tree(&mut child);
                let _ = child.wait();
                return Err("Claude Code did not finish in time.".into());
            }
            Err(e) => return Err(format!("Could not run Claude Code: {e}")),
        }
    };
    let stdout = out.join().unwrap_or_default();
    let stderr = err.join().unwrap_or_default();
    if status.success() {
        return Ok(());
    }
    if crate::is_debug() {
        eprintln!("[mcp] claude mcp failed ({status})");
    }
    let said = if stderr.trim().is_empty() { stdout.trim() } else { stderr.trim() };
    Err(if said.is_empty() { format!("Claude Code exited with {status}.") } else { said.to_string() })
}

#[cfg(test)]
mod tests {
    use super::*;
    use serde_json::json;

    fn args(op: serde_json::Value) -> Result<Vec<String>, String> {
        config_args(&op)
    }

    #[test]
    fn stdio_add_matches_the_extension() {
        let a = args(json!({"op":"add","name":"tools","scope":"local","config":{
            "transport":"stdio","command":"npx","args":["-y","pkg"," "],"env":["A=1","B = two"]}}))
        .unwrap();
        assert_eq!(a, ["mcp", "add", "--scope", "local", "--transport", "stdio",
                       "--env", "A=1", "--env", "B=two", "--", "tools", "npx", "-y", "pkg"]);
    }

    #[test]
    fn remote_adds_carry_headers_and_url() {
        for t in ["http", "sse"] {
            let a = args(json!({"op":"add","name":"r","scope":"project","config":{
                "transport":t,"url":" https://x.test/mcp ","headers":["Authorization: Bearer k"]}}))
            .unwrap();
            assert_eq!(a, ["mcp", "add", "--scope", "project", "--transport", t,
                           "--header", "Authorization: Bearer k", "--", "r", "https://x.test/mcp"]);
        }
    }

    #[test]
    fn remove_takes_any_configured_name() {
        let a = args(json!({"op":"remove","name":"odd name","scope":"user"})).unwrap();
        assert_eq!(a, ["mcp", "remove", "--scope", "user", "--", "odd name"]);
    }

    #[test]
    fn bad_input_is_refused_with_the_extension_wording() {
        let add = |name: &str, cfg: serde_json::Value| {
            args(json!({"op":"add","name":name,"scope":"local","config":cfg})).unwrap_err()
        };
        let stdio = json!({"transport":"stdio","command":"x"});
        assert_eq!(add("", stdio.clone()), "Server name is required.");
        assert!(add("a b", stdio.clone()).starts_with("Invalid name a b."));
        assert!(add("eclipse", stdio).contains("reserved"));
        assert_eq!(add("n", json!({"transport":"stdio","command":" "})), "Command is required.");
        assert_eq!(add("n", json!({"transport":"stdio","command":"x","env":["ok=1","=v"]})),
                   "Environment variables must be KEY=value (line 2).");
        assert_eq!(add("n", json!({"transport":"http","url":""})), "URL is required.");
        assert_eq!(add("n", json!({"transport":"http","url":"u","headers":[":v"]})),
                   "Headers must be \"Header-Name: value\" (line 1).");
        assert!(add("n", json!({"transport":"ws","url":"u"})).starts_with("Unknown transport"));
        assert!(args(json!({"op":"add","name":"n","scope":"global","config":{}})).unwrap_err()
            .starts_with("Unknown scope"));
    }

    #[test]
    fn only_the_windows_requests_go_out() {
        let line = request_line("t1", &json!({"subtype":"mcp_toggle","serverName":"s","enabled":false})).unwrap();
        let v: serde_json::Value = serde_json::from_str(&line).unwrap();
        assert_eq!(v["type"], "control_request");
        assert_eq!(v["request_id"], "eclipse-mcp-t1");
        assert_eq!(v["request"]["enabled"], false);
        assert!(request_line("t1", &json!({"subtype":"mcp_set_servers","servers":{}})).is_none());
        assert!(request_line("t1", &json!({"subtype":"interrupt"})).is_none());
        assert!(request_line("bad\"id", &json!({"subtype":"mcp_status"})).is_none());
        assert!(request_line("", &json!({"subtype":"mcp_status"})).is_none());
    }

    #[test]
    fn replies_are_matched_by_token() {
        let ok = reply_json(&json!({"subtype":"success","request_id":"eclipse-mcp-7",
                                    "response":{"mcpServers":[]}})).unwrap();
        let v: serde_json::Value = serde_json::from_str(&ok).unwrap();
        assert_eq!(v, json!({"token":"7","ok":true,"response":{"mcpServers":[]}}));
        let err = reply_json(&json!({"subtype":"error","request_id":"eclipse-mcp-8",
                                     "error":"Server not found: nope"})).unwrap();
        let v: serde_json::Value = serde_json::from_str(&err).unwrap();
        assert_eq!(v, json!({"token":"8","ok":false,"error":"Server not found: nope"}));
        assert!(reply_json(&json!({"subtype":"success","request_id":"eclipse-live-1"})).is_none());
    }

    #[test]
    fn debug_argv_hides_credentials() {
        let a = args(json!({"op":"add","name":"n","scope":"local","config":{
            "transport":"stdio","command":"run","args":["--token","s3cret"],"env":["KEY=s3cret"]}}))
        .unwrap();
        let r = redacted(&a);
        assert!(!r.contains("s3cret"), "{r}");
        assert_eq!(r, "mcp add --scope local --transport stdio --env KEY=<redacted> -- n …");
        let h = args(json!({"op":"add","name":"n","scope":"local","config":{
            "transport":"http","url":"https://u","headers":["Authorization: Bearer s3cret"]}}))
        .unwrap();
        assert_eq!(redacted(&h), "mcp add --scope local --transport http --header Authorization: <redacted> -- n …");
    }
}
