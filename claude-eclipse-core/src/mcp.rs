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
        "tools/list" => handle_tools_list(sender, id),
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
// tools/list — static definitions that mirror the Java McpToolRegistry
// ---------------------------------------------------------------------------

fn handle_tools_list(sender: UnboundedSender<SseEvent>, id: Option<Value>) {
    let id = match id {
        Some(id) => id,
        None => return,
    };
    let tools = json!([
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
        }
    ]);
    send_result(&sender, &id, json!({ "tools": tools }));
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
