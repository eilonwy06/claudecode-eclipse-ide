use std::sync::Arc;

use jni::objects::{JObject, JValue};
use serde_json::{Value, json};
use tokio::sync::mpsc::UnboundedSender;

use crate::server::{AppState, SseEvent};

// ---------------------------------------------------------------------------
// Entry point called from the POST /messages Axum handler
// ---------------------------------------------------------------------------

pub async fn handle_message(
    state: Arc<AppState>,
    sender: UnboundedSender<SseEvent>,
    body: String,
) {
    let msg: Value = match serde_json::from_str(&body) {
        Ok(v) => v,
        Err(e) => {
            if crate::is_debug() {
                eprintln!("MCP: failed to parse JSON: {} — body: {}", e, body);
            }
            return;
        }
    };

    if msg.get("jsonrpc").and_then(Value::as_str) != Some("2.0") {
        return;
    }

    if msg.get("method").is_some() {
        handle_request(state, sender, msg).await;
    }
    // Responses (result/error) from Claude are informational only; we ignore them.
}

// ---------------------------------------------------------------------------
// Request dispatch
// ---------------------------------------------------------------------------

async fn handle_request(
    state: Arc<AppState>,
    sender: UnboundedSender<SseEvent>,
    msg: Value,
) {
    let method = msg["method"].as_str().unwrap_or("");
    let id = msg.get("id").cloned();

    match method {
        "initialize" => handle_initialize(Arc::clone(&state), sender, id),
        "initialized" => {} // notification, no response
        "tools/list" => handle_tools_list(state, sender, id).await,
        "tools/call" => handle_tools_call(state, sender, id, &msg).await,
        "shutdown" => {
            if let Some(id) = id {
                send_result(&sender, &id, json!({}));
            }
            // Closing the sender ends the SSE stream for this client.
        }
        _ => {
            if let Some(id) = id {
                send_error(&sender, &id, -32601, &format!("Method not found: {}", method));
            }
        }
    }
}

// ---------------------------------------------------------------------------
// initialize
// ---------------------------------------------------------------------------

fn handle_initialize(state: Arc<AppState>, sender: UnboundedSender<SseEvent>, id: Option<Value>) {
    let id = match id {
        Some(id) => id,
        None => return,
    };
    let result = json!({
        "protocolVersion": "2024-11-05",
        "capabilities": { "tools": { "listChanged": false } },
        "serverInfo": { "name": "claude-code-eclipse", "version": "1.0.0" }
    });
    send_result(&sender, &id, result);

    // Replay the last known editor selection so Claude knows the active file the
    // moment it connects — without the user having to move the cursor first. The
    // cached message is already in the CLI's "selection_changed" shape.
    if let Some(sel) = state.last_selection.lock().unwrap().clone() {
        let _ = sender.send(SseEvent {
            event_type: "message".to_string(),
            data: sel,
        });
    }

    // A new MCP client just connected — close any Eclipse diff tabs that were
    // left open from a previous session (same as what happens when the user
    // types a new command and Claude calls closeAllDiffTabs at the turn start).
    let guard = state.tool_callback.lock().unwrap();
    if let Some(cb) = guard.as_ref() {
        let java_vm  = Arc::clone(&cb.java_vm);
        let callback = Arc::clone(&cb.callback);
        std::thread::spawn(move || {
            call_java_tool(&java_vm, &callback, "closeAllDiffTabs", "{}");
        });
    }
}

// ---------------------------------------------------------------------------
// tools/list — asked of the Java McpToolRegistry at request time
// ---------------------------------------------------------------------------

/// Sentinel tool name that makes the existing `executeEclipseTool` callback return the
/// registry's own tool definitions. Deliberately NOT a new JNI method: adding one to
/// `NativeCore.ToolCallback` would change the interface the DLL looks up by signature, so
/// a DLL and a plugin jar of different vintages would stop talking to each other. Reusing
/// the one call that already exists keeps both directions compatible — an older plugin
/// simply answers "Unknown tool" and we fall back below.
///
/// The leading `$` cannot collide with a registered tool: names come from
/// `McpTool.toolName()`, which are all plain identifiers.
const LIST_TOOLS_SENTINEL: &str = "$listTools";

async fn handle_tools_list(
    state: Arc<AppState>,
    sender: UnboundedSender<SseEvent>,
    id: Option<Value>,
) {
    let id = match id {
        Some(id) => id,
        None => return,
    };

    if let Some(tools) = tools_from_java(state).await {
        send_result(&sender, &id, json!({ "tools": tools }));
        return;
    }

    // Fallback only. Reached when no callback is registered yet (a client connecting
    // before Eclipse finishes wiring the bridge) or when the plugin predates the
    // sentinel. It is allowed to be incomplete — Java is the source of truth, and
    // anything missing here is still callable, just not advertised.
    send_result(&sender, &id, json!({ "tools": fallback_tools() }));
}

/// Asks Java for the live tool definitions. `None` on any failure, so the caller can fall
/// back rather than serve an empty list and leave the client with no tools at all.
async fn tools_from_java(state: Arc<AppState>) -> Option<Value> {
    let (java_vm, callback) = {
        let guard = state.tool_callback.lock().unwrap();
        let cb = guard.as_ref()?;
        (Arc::clone(&cb.java_vm), Arc::clone(&cb.callback))
    };

    // Same blocking-thread treatment as tools/call: JNI must not stall the executor.
    let raw = tokio::task::spawn_blocking(move || {
        call_java_tool(&java_vm, &callback, LIST_TOOLS_SENTINEL, "{}")
    })
    .await
    .ok()?;

    // Java answers in the standard McpToolResult envelope, so the array arrives as text
    // inside content[0]. An older plugin returns isError with "Unknown tool: $listTools",
    // which fails one of the steps below and lands us on the fallback.
    let envelope: Value = serde_json::from_str(&raw).ok()?;
    if envelope.get("isError").and_then(Value::as_bool).unwrap_or(false) {
        return None;
    }
    let text = envelope.get("content")?.get(0)?.get("text")?.as_str()?;
    let tools: Value = serde_json::from_str(text).ok()?;
    match tools.as_array() {
        Some(a) if !a.is_empty() => Some(tools),
        _ => None,
    }
}

fn fallback_tools() -> Value {
    json!([
        {
            "name": "openFile",
            "description": "Open a file in the Eclipse editor at an optional line and column, with optional text selection.",
            "inputSchema": {
                "type": "object",
                "properties": {
                    "file_path": { "type": "string", "description": "Absolute path to the file to open" },
                    "line":      { "type": "integer", "description": "Line number to navigate to (1-based)" },
                    "column":    { "type": "integer", "description": "Column number (1-based)" },
                    "select_text": { "type": "string", "description": "Text to select after opening" }
                },
                "required": ["file_path"]
            }
        },
        {
            "name": "openDiff",
            "description": "Open a diff/compare view in Eclipse showing changes to a file.",
            "inputSchema": {
                "type": "object",
                "properties": {
                    "old_file_path":    { "type": "string", "description": "Absolute path of the original file" },
                    "new_file_contents":{ "type": "string", "description": "New file contents to diff against" },
                    "new_file_path":    { "type": "string", "description": "Path for the new file side" },
                    "tab_name":         { "type": "string", "description": "Label for the diff tab" }
                },
                "required": ["old_file_path", "new_file_contents"]
            }
        },
        {
            "name": "getCurrentSelection",
            "description": "Get the current text selection in the active Eclipse editor.",
            "inputSchema": { "type": "object", "properties": {} }
        },
        {
            "name": "getLatestSelection",
            "description": "Get the most recent text selection tracked by the selection tracker.",
            "inputSchema": { "type": "object", "properties": {} }
        },
        {
            "name": "getOpenEditors",
            "description": "List all currently open editor tabs in Eclipse.",
            "inputSchema": { "type": "object", "properties": {} }
        },
        {
            "name": "getWorkspaceFolders",
            "description": "Get the list of workspace folders and open project paths.",
            "inputSchema": { "type": "object", "properties": {} }
        },
        {
            "name": "checkDocumentDirty",
            "description": "Check whether a file has unsaved changes in the Eclipse editor.",
            "inputSchema": {
                "type": "object",
                "properties": {
                    "file_path": { "type": "string", "description": "Absolute path to check" }
                },
                "required": ["file_path"]
            }
        },
        {
            "name": "saveDocument",
            "description": "Save a file that is currently open in the Eclipse editor.",
            "inputSchema": {
                "type": "object",
                "properties": {
                    "file_path": { "type": "string", "description": "Absolute path of the file to save" }
                },
                "required": ["file_path"]
            }
        },
        {
            "name": "getDiagnostics",
            "description": "Get Eclipse workspace diagnostics (errors, warnings, info markers).",
            "inputSchema": {
                "type": "object",
                "properties": {
                    "file_path": { "type": "string", "description": "Optional file path to filter diagnostics" }
                }
            }
        },
        {
            "name": "closeAllDiffTabs",
            "description": "Close all open diff/compare editor tabs in Eclipse.",
            "inputSchema": { "type": "object", "properties": {} }
        },
        {
            "name": "acceptDiff",
            "description": "Accept the proposed changes from a diff view, writing them to disk and closing the diff tab.",
            "inputSchema": {
                "type": "object",
                "properties": {
                    "file_path": { "type": "string", "description": "Absolute path of the file whose diff to accept" }
                },
                "required": ["file_path"]
            }
        },
        {
            "name": "rejectDiff",
            "description": "Reject the proposed changes from a diff view, closing the diff tab without writing.",
            "inputSchema": {
                "type": "object",
                "properties": {
                    "file_path": { "type": "string", "description": "Absolute path of the file whose diff to reject" }
                },
                "required": ["file_path"]
            }
        },
        {
            "name": "build",
            "description": "Clean and/or rebuild Eclipse projects and report the resulting compile errors. Scope: the named projects, or the whole workspace when none are given. Modes: clean-rebuild (default), clean, rebuild (full build), incremental. Returns per-project error/warning counts plus the problem markers themselves.",
            "inputSchema": {
                "type": "object",
                "properties": {
                    "projects": {
                        "type": "array",
                        "description": "Project names to build. Omit or leave empty to build the whole workspace.",
                        "items": { "type": "string" }
                    },
                    "mode": {
                        "type": "string",
                        "description": "clean-rebuild (default) | clean | rebuild | incremental",
                        "enum": ["clean-rebuild", "clean", "rebuild", "incremental"]
                    }
                }
            }
        },
        {
            "name": "runAs",
            "description": "Run a project the way right-click > Run As does — including 'Eclipse Application', 'Java Application', 'JUnit Plug-in Test', or any other launcher installed in this IDE, plus any saved launch configuration by name. Call without 'option' to list what is available for the project. Note that an Eclipse Application launch starts a second, long-running IDE.",
            "inputSchema": {
                "type": "object",
                "properties": {
                    "project": { "type": "string", "description": "Project name, e.g. com.anthropic.claudecode.eclipse" },
                    "option":  { "type": "string", "description": "Run As option label or id, e.g. 'Eclipse Application'. Omit to list the options instead of launching." },
                    "config":  { "type": "string", "description": "Name of an existing launch configuration to run instead of a Run As option. Takes precedence over 'option'." },
                    "mode": {
                        "type": "string",
                        "description": "run (default) or debug",
                        "enum": ["run", "debug"]
                    },
                    "force": { "type": "boolean", "description": "Run the option even when it does not apply to this project type (default false)." },
                    "waitSeconds": { "type": "integer", "description": "Seconds to wait for the launched process to exit before returning (default 0). Leave at 0 for an Eclipse Application, which is not meant to terminate." }
                },
                "required": ["project"]
            }
        },
        {
            "name": "approvalPrompt",
            "description": "Permission prompt: ask the user to approve a tool call before it runs. Returns a JSON string {\"behavior\":\"allow\",\"updatedInput\":<input>} or {\"behavior\":\"deny\",\"message\":\"...\"}.",
            "inputSchema": {
                "type": "object",
                "properties": {
                    "tool_name": { "type": "string", "description": "Name of the tool requesting permission" },
                    "input":     { "type": "object", "description": "The input the tool would run with" },
                    "tool_use_id": { "type": "string", "description": "Identifier for this tool use" }
                },
                "required": ["tool_name", "input"]
            }
        },
        {
            "name": "askUserQuestion",
            "description": "Ask the user one or more multiple-choice questions and wait for their selection. Use this instead of the built-in AskUserQuestion. Returns the user's chosen answers as text.",
            "inputSchema": {
                "type": "object",
                "properties": {
                    "questions": {
                        "type": "array",
                        "description": "The questions to ask",
                        "items": {
                            "type": "object",
                            "properties": {
                                "question":    { "type": "string", "description": "The full question text" },
                                "header":      { "type": "string", "description": "Short tab label (max ~12 chars)" },
                                "multiSelect": { "type": "boolean", "description": "Allow multiple selections" },
                                "options": {
                                    "type": "array",
                                    "items": {
                                        "type": "object",
                                        "properties": {
                                            "label":       { "type": "string" },
                                            "description": { "type": "string" }
                                        },
                                        "required": ["label"]
                                    }
                                }
                            },
                            "required": ["question", "header", "options"]
                        }
                    }
                },
                "required": ["questions"]
            }
        }
    ])
}

// ---------------------------------------------------------------------------
// tools/call — dispatched to Java via JNI callback
// ---------------------------------------------------------------------------

async fn handle_tools_call(
    state: Arc<AppState>,
    sender: UnboundedSender<SseEvent>,
    id: Option<Value>,
    msg: &Value,
) {
    let id = match id {
        Some(id) => id,
        None => return,
    };

    let params = msg.get("params").cloned().unwrap_or_else(|| json!({}));
    let tool_name = params["name"].as_str().unwrap_or("").to_string();
    let tool_args = params
        .get("arguments")
        .cloned()
        .unwrap_or_else(|| json!({}));
    let args_json = tool_args.to_string();

    // Snapshot callback ref under lock (don't hold lock across await).
    // Arc::clone increments refcounts only — no JVM interaction needed.
    let (java_vm, callback) = {
        let guard = state.tool_callback.lock().unwrap();
        match guard.as_ref() {
            Some(cb) => (Arc::clone(&cb.java_vm), Arc::clone(&cb.callback)),
            None => {
                send_error(&sender, &id, -32603, "No tool callback registered");
                return;
            }
        }
    };

    // Execute on a blocking thread so JNI doesn't stall the async executor.
    let result_json = tokio::task::spawn_blocking(move || {
        call_java_tool(&java_vm, &callback, &tool_name, &args_json)
    })
    .await
    .unwrap_or_else(|e| {
        format!(
            r#"{{"content":[{{"type":"text","text":"spawn_blocking panic: {}"}}],"isError":true}}"#,
            e
        )
    });

    // Parse the JSON returned by Java and wrap it in a JSON-RPC result envelope.
    let result_value: Value = serde_json::from_str(&result_json).unwrap_or_else(|_| {
        json!({
            "content": [{ "type": "text", "text": result_json }],
            "isError": false
        })
    });
    send_result(&sender, &id, result_value);
}

/// Attaches the current thread to the JVM, calls `callback.executeEclipseTool(name, argsJson)`,
/// and returns the JSON string result.
pub fn call_java_tool(
    java_vm: &jni::JavaVM,
    callback: &jni::objects::GlobalRef,
    tool_name: &str,
    args_json: &str,
) -> String {
    let mut env = match java_vm.attach_current_thread() {
        Ok(env) => env,
        Err(e) => {
            return format!(
                r#"{{"content":[{{"type":"text","text":"JVM attach failed: {}"}}],"isError":true}}"#,
                e
            )
        }
    };

    let tool_name_jstr = match env.new_string(tool_name) {
        Ok(s) => s,
        Err(e) => return jni_error_result(&format!("new_string(tool_name): {}", e)),
    };
    let args_jstr = match env.new_string(args_json) {
        Ok(s) => s,
        Err(e) => return jni_error_result(&format!("new_string(args_json): {}", e)),
    };

    let tn_obj = JObject::from(tool_name_jstr);
    let aj_obj = JObject::from(args_jstr);

    let call_result = env.call_method(
        callback,
        "executeEclipseTool",
        "(Ljava/lang/String;Ljava/lang/String;)Ljava/lang/String;",
        &[JValue::Object(&tn_obj), JValue::Object(&aj_obj)],
    );

    match call_result {
        Err(e) => {
            let _ = env.exception_clear();
            jni_error_result(&format!("call_method: {}", e))
        }
        Ok(result_jvalue) => {
            let result_jobj = match result_jvalue.l() {
                Err(e) => return jni_error_result(&format!("result.l(): {}", e)),
                Ok(obj) => obj,
            };
            let jstr = jni::objects::JString::from(result_jobj);
            let s: Result<String, _> = env.get_string(&jstr).map(|s| s.into());
            match s {
                Err(e) => jni_error_result(&format!("get_string: {}", e)),
                Ok(s) => s,
            }
        }
    }
}

fn jni_error_result(msg: &str) -> String {
    format!(
        r#"{{"content":[{{"type":"text","text":"JNI error: {}"}}],"isError":true}}"#,
        msg.replace('"', "\\\"")
    )
}

// ---------------------------------------------------------------------------
// SSE response helpers
// ---------------------------------------------------------------------------

fn send_result(sender: &UnboundedSender<SseEvent>, id: &Value, result: Value) {
    let json = json!({
        "jsonrpc": "2.0",
        "id": id,
        "result": result
    })
    .to_string();
    let _ = sender.send(SseEvent {
        event_type: "message".to_string(),
        data: json,
    });
}

fn send_error(sender: &UnboundedSender<SseEvent>, id: &Value, code: i32, message: &str) {
    let json = json!({
        "jsonrpc": "2.0",
        "id": id,
        "error": { "code": code, "message": message }
    })
    .to_string();
    let _ = sender.send(SseEvent {
        event_type: "message".to_string(),
        data: json,
    });
}
