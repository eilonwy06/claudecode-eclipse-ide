mod archive;
mod bookmarks;
mod bridge;
mod chat;
mod chrome;
mod cli_ask;
mod console;
mod design_login;
mod dialogs;
mod freebsd_guide;
mod launch;
mod lock_file;
mod mcp;
mod mcp_servers;
mod mentions;
mod promptcache;
mod server;
mod session;
mod shell_env;
mod stt;
#[cfg(any(target_os = "linux", target_os = "freebsd"))]
mod alsa_capture;
mod teleport;
#[cfg(test)]
mod test_support;
mod user_settings;
mod web_history;

use chat::ChatManager;
use jni::objects::{JClass, JObject, JString};
use jni::sys::{jboolean, jint, jlong, jobjectArray, jstring};
use jni::JNIEnv;
use server::Server;
use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::{Arc, OnceLock};

// ---------------------------------------------------------------------------
// Global JavaVM — populated in JNI_OnLoad, used for all callbacks into Java.
// ---------------------------------------------------------------------------

static JAVA_VM: OnceLock<Arc<jni::JavaVM>> = OnceLock::new();

// ---------------------------------------------------------------------------
// Global debug flag — set from Java via setDebugMode().
// ---------------------------------------------------------------------------

static DEBUG_MODE: AtomicBool = AtomicBool::new(false);

pub(crate) fn is_debug() -> bool {
    DEBUG_MODE.load(Ordering::Relaxed)
}

// ---------------------------------------------------------------------------
// Whether a chat process may be launched able to enter Auto mode — set from Java
// via setLiveAutoMode(). Defaults to off, as the preference does.
// ---------------------------------------------------------------------------

static LIVE_AUTO_MODE: AtomicBool = AtomicBool::new(false);

pub(crate) fn live_auto_mode() -> bool {
    LIVE_AUTO_MODE.load(Ordering::Relaxed)
}

pub(crate) fn java_vm() -> Arc<jni::JavaVM> {
    Arc::clone(JAVA_VM.get().expect("JavaVM not initialised"))
}

/// Called once by the JVM when System.loadLibrary("claude_eclipse_core") succeeds.
/// A panic here has no JNIEnv to raise in, so it returns JNI_ERR instead, which Java
/// reports as a failed load rather than the process aborting (see `finish_export`).
#[no_mangle]
pub unsafe extern "system" fn JNI_OnLoad(
    raw_jvm: *mut jni::sys::JavaVM,
    _reserved: *mut std::ffi::c_void,
) -> jni::sys::jint {
    std::panic::catch_unwind(|| {
        let vm = jni::JavaVM::from_raw(raw_jvm).expect("JavaVM::from_raw failed");
        JAVA_VM.set(Arc::new(vm)).ok();
        jni::sys::JNI_VERSION_1_8
    })
    .unwrap_or(jni::sys::JNI_ERR)
}

// ===========================================================================
// JNI helpers: string conversion, and the panic guard every export ends in
// ===========================================================================

/// The end of every JNI export. A panic must not unwind out of an `extern "system"`
/// function: since Rust 1.81 that aborts the process, which here is Eclipse. So each
/// export runs its body under `catch_unwind` and hands the outcome here: a normal
/// result passes through; a panic becomes a Java RuntimeException naming the export
/// (unless a Java exception is already pending, which is then the real error) and the
/// export returns `fallback`. Nothing in here can itself panic.
fn finish_export<T>(env: &mut JNIEnv, export: &str, result: std::thread::Result<T>, fallback: T) -> T {
    let panic = match result {
        Ok(value) => return value,
        Err(panic) => panic,
    };
    if !env.exception_check().unwrap_or(true) {
        let _ = env.throw_new("java/lang/RuntimeException", panic_report(export, panic.as_ref()));
    }
    fallback
}

/// The message Java sees for a panic in `export`: the panic's own text when it has one.
fn panic_report(export: &str, panic: &(dyn std::any::Any + Send)) -> String {
    let why = panic
        .downcast_ref::<&str>()
        .map(|s| s.to_string())
        .or_else(|| panic.downcast_ref::<String>().cloned())
        .unwrap_or_else(|| "an unknown panic".to_string());
    format!("claude_eclipse_core: {export} panicked: {why}")
}

/// A Java string as a Rust one: `""` when it is null or cannot be read, never a
/// panic (a panic in a JNI export aborts the whole IDE).
fn jstr(env: &mut JNIEnv, s: &JString) -> String {
    if s.is_null() {
        return String::new();
    }
    env.get_string(s).ok().map(|v| v.into()).unwrap_or_default()
}

/// A Rust string as a Java one: `value`, or `fallback` if the JVM cannot make it,
/// or null if it cannot make either. Never a panic, for the same reason as [`jstr`].
fn jout(env: &mut JNIEnv, value: impl AsRef<str>, fallback: &str) -> jstring {
    env.new_string(value)
        .or_else(|_| env.new_string(fallback))
        .map(JString::into_raw)
        .unwrap_or(std::ptr::null_mut())
}

// ===========================================================================
// Server JNI entry points
// ===========================================================================

/// Creates a Server instance and returns it as an opaque jlong handle.
/// Java must later call serverStop(handle) to free it.
#[no_mangle]
pub extern "system" fn Java_com_anthropic_claudecode_eclipse_NativeCore_serverCreate(
    mut env: JNIEnv,
    _class: JClass,
    port_min: jint,
    port_max: jint,
) -> jlong {
    let result = std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| {
        let server = Server::new(port_min as u16, port_max as u16);
        Box::into_raw(Box::new(server)) as jlong
    }));
    finish_export(&mut env, "serverCreate", result, 0)
}

#[no_mangle]
pub extern "system" fn Java_com_anthropic_claudecode_eclipse_NativeCore_serverCreateWithConfig(
    mut env: JNIEnv,
    _class: JClass,
    port_min: jint,
    port_max: jint,
    preferred_port: jint,
    auth_token: JString,
) -> jlong {
    let result = std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| {
        let pref_port = if preferred_port > 0 { Some(preferred_port as u16) } else { None };
        let token: Option<String> = if auth_token.is_null() {
            None
        } else {
            env.get_string(&auth_token).ok().map(|s| s.into())
        };
        let server = Server::new_with_config(port_min as u16, port_max as u16, pref_port, token);
        Box::into_raw(Box::new(server)) as jlong
    }));
    finish_export(&mut env, "serverCreateWithConfig", result, 0)
}

/// Starts the server.  Returns the bound port, or 0 on failure.
#[no_mangle]
pub extern "system" fn Java_com_anthropic_claudecode_eclipse_NativeCore_serverStart(
    mut env: JNIEnv,
    _class: JClass,
    handle: jlong,
) -> jint {
    let result = std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| {
        if handle == 0 {
            return 0;
        }
        let server = unsafe { &*(handle as *const Server) };
        server.start() as jint
    }));
    finish_export(&mut env, "serverStart", result, 0)
}

/// Stops the server and frees its memory.  The handle must not be used after this call.
#[no_mangle]
pub extern "system" fn Java_com_anthropic_claudecode_eclipse_NativeCore_serverStop(
    mut env: JNIEnv,
    _class: JClass,
    handle: jlong,
) {
    let result = std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| {
        if handle == 0 {
            return;
        }
        // Reconstruct the Box so it is dropped (and Server::drop runs) at end of scope.
        let server = unsafe { Box::from_raw(handle as *mut Server) };
        drop(server);
    }));
    finish_export(&mut env, "serverStop", result, ())
}

#[no_mangle]
pub extern "system" fn Java_com_anthropic_claudecode_eclipse_NativeCore_serverGetPort(
    mut env: JNIEnv,
    _class: JClass,
    handle: jlong,
) -> jint {
    let result = std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| {
        if handle == 0 {
            return 0;
        }
        let server = unsafe { &*(handle as *const Server) };
        server.port() as jint
    }));
    finish_export(&mut env, "serverGetPort", result, 0)
}

#[no_mangle]
pub extern "system" fn Java_com_anthropic_claudecode_eclipse_NativeCore_serverGetAuthToken(
    mut env: JNIEnv,
    _class: JClass,
    handle: jlong,
) -> jstring {
    let result = std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| {
        if handle == 0 {
            return jout(&mut env, "", "");
        }
        let server = unsafe { &*(handle as *const Server) };
        jout(&mut env, server.auth_token(), "")
    }));
    finish_export(&mut env, "serverGetAuthToken", result, std::ptr::null_mut())
}

#[no_mangle]
pub extern "system" fn Java_com_anthropic_claudecode_eclipse_NativeCore_serverBroadcast(
    mut env: JNIEnv,
    _class: JClass,
    handle: jlong,
    json: JString,
) {
    let result = std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| {
        if handle == 0 {
            return;
        }
        let server = unsafe { &*(handle as *const Server) };
        let json_str: String = jstr(&mut env, &json);
        server.broadcast(&json_str);
    }));
    finish_export(&mut env, "serverBroadcast", result, ())
}

#[no_mangle]
pub extern "system" fn Java_com_anthropic_claudecode_eclipse_NativeCore_serverGetClientCount(
    mut env: JNIEnv,
    _class: JClass,
    handle: jlong,
) -> jint {
    let result = std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| {
        if handle == 0 {
            return 0;
        }
        let server = unsafe { &*(handle as *const Server) };
        server.client_count() as jint
    }));
    finish_export(&mut env, "serverGetClientCount", result, 0)
}

/// Debounces a selection-changed event and broadcasts it to all SSE clients after 50 ms.
#[no_mangle]
pub extern "system" fn Java_com_anthropic_claudecode_eclipse_NativeCore_serverNotifySelection(
    mut env: JNIEnv,
    _class: JClass,
    handle: jlong,
    file_path: JString,
    text: JString,
    start_line: jint,
    end_line: jint,
    start_col: jint,
    end_col: jint,
    is_empty: jboolean,
) {
    let result = std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| {
        if handle == 0 {
            return;
        }
        let server = unsafe { &*(handle as *const Server) };
        let fp: String = jstr(&mut env, &file_path);
        let t: String = jstr(&mut env, &text);
        server.notify_selection(fp, t, start_line, end_line, start_col, end_col, is_empty != 0);
    }));
    finish_export(&mut env, "serverNotifySelection", result, ())
}

/// Registers the Java ToolCallback object.  From this point, tool calls
/// from Claude are dispatched via callback.executeEclipseTool(toolName, argsJson).
#[no_mangle]
pub extern "system" fn Java_com_anthropic_claudecode_eclipse_NativeCore_registerToolCallback(
    mut env: JNIEnv,
    _class: JClass,
    handle: jlong,
    callback: JObject,
) {
    let result = std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| {
        if handle == 0 {
            return;
        }
        let server = unsafe { &*(handle as *const Server) };
        let global_ref = env.new_global_ref(callback).expect("new_global_ref failed");
        server.register_tool_callback(java_vm(), global_ref);
    }));
    finish_export(&mut env, "registerToolCallback", result, ())
}

/// Registers the Java StatusCallback object.  From this point, statusLine updates
/// POSTed to /statusline are dispatched via callback.onStatusUpdate(tabToken, statusJson).
/// Dedicated channel — separate from the MCP tool callback above.
#[no_mangle]
pub extern "system" fn Java_com_anthropic_claudecode_eclipse_NativeCore_registerStatusCallback(
    mut env: JNIEnv,
    _class: JClass,
    handle: jlong,
    callback: JObject,
) {
    let result = std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| {
        if handle == 0 {
            return;
        }
        let server = unsafe { &*(handle as *const Server) };
        let global_ref = env.new_global_ref(callback).expect("new_global_ref failed");
        server.register_status_callback(java_vm(), global_ref);
    }));
    finish_export(&mut env, "registerStatusCallback", result, ())
}

// ===========================================================================
// Lock-file JNI entry points
// ===========================================================================

#[no_mangle]
pub extern "system" fn Java_com_anthropic_claudecode_eclipse_NativeCore_lockFileWrite(
    mut env: JNIEnv,
    _class: JClass,
    port: jint,
    auth_token: JString,
    workspace_root: JString,
    project_paths_json: JString,
) {
    let result = std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| {
        let auth_token: String = jstr(&mut env, &auth_token);
        let workspace_root: String = jstr(&mut env, &workspace_root);
        let project_paths_json: String = jstr(&mut env, &project_paths_json);
        lock_file::write(port as u16, &auth_token, &workspace_root, &project_paths_json);
    }));
    finish_export(&mut env, "lockFileWrite", result, ())
}

#[no_mangle]
pub extern "system" fn Java_com_anthropic_claudecode_eclipse_NativeCore_lockFileRemove(
    mut env: JNIEnv,
    _class: JClass,
) {
    let result = std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| {
        lock_file::remove();
    }));
    finish_export(&mut env, "lockFileRemove", result, ())
}

// ===========================================================================
// Chat JNI entry points
// ===========================================================================

#[no_mangle]
pub extern "system" fn Java_com_anthropic_claudecode_eclipse_NativeCore_chatCreate(
    mut env: JNIEnv,
    _class: JClass,
) -> jlong {
    let result = std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| {
        let manager = ChatManager::new();
        Box::into_raw(Box::new(manager)) as jlong
    }));
    finish_export(&mut env, "chatCreate", result, 0)
}

#[no_mangle]
pub extern "system" fn Java_com_anthropic_claudecode_eclipse_NativeCore_chatRegisterCallbacks(
    mut env: JNIEnv,
    _class: JClass,
    handle: jlong,
    callbacks: JObject,
) {
    let result = std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| {
        if handle == 0 {
            return;
        }
        let manager = unsafe { &*(handle as *const ChatManager) };
        let global_ref = env.new_global_ref(callbacks).expect("new_global_ref failed");
        manager.register_callbacks(java_vm(), global_ref);
    }));
    finish_export(&mut env, "chatRegisterCallbacks", result, ())
}

#[no_mangle]
pub extern "system" fn Java_com_anthropic_claudecode_eclipse_NativeCore_chatSendMessage(
    mut env: JNIEnv,
    _class: JClass,
    handle: jlong,
    message: JString,
    claude_cmd: JString,
    workspace_root: JString,
    mcp_port: jint,
    mcp_auth_token: JString,
    resume_id: JString,
    perm_mode: JString,
    effort: JString,
    model: JString,
    thinking: JString,
    ultracode: jboolean,
    images_json: JString,
) {
    let result = std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| {
        if handle == 0 {
            return;
        }
        let manager = unsafe { &*(handle as *const ChatManager) };
        let message: String = jstr(&mut env, &message);
        let claude_cmd: String = jstr(&mut env, &claude_cmd);
        let workspace_root: String = jstr(&mut env, &workspace_root);
        let mcp_auth_token: String = jstr(&mut env, &mcp_auth_token);
        let resume_id: String = jstr(&mut env, &resume_id);
        let perm_mode: String = jstr(&mut env, &perm_mode);
        let effort: String = jstr(&mut env, &effort);
        let model: String = jstr(&mut env, &model);
        let thinking: String = jstr(&mut env, &thinking);
        let images_json: String = jstr(&mut env, &images_json);
        manager.send_message(message, claude_cmd, workspace_root, mcp_port as u16, mcp_auth_token, resume_id, perm_mode, effort, model, thinking, ultracode != 0, images_json);
    }));
    finish_export(&mut env, "chatSendMessage", result, ())
}

#[no_mangle]
pub extern "system" fn Java_com_anthropic_claudecode_eclipse_NativeCore_chatSetPersistent(
    mut env: JNIEnv,
    _class: JClass,
    handle: jlong,
    persistent: jboolean,
) {
    let result = std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| {
        if handle == 0 {
            return;
        }
        let manager = unsafe { &*(handle as *const ChatManager) };
        manager.set_persistent(persistent != 0);
    }));
    finish_export(&mut env, "chatSetPersistent", result, ())
}

#[no_mangle]
pub extern "system" fn Java_com_anthropic_claudecode_eclipse_NativeCore_chatCancel(
    mut env: JNIEnv,
    _class: JClass,
    handle: jlong,
) {
    let result = std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| {
        if handle == 0 {
            return;
        }
        let manager = unsafe { &*(handle as *const ChatManager) };
        manager.cancel();
    }));
    finish_export(&mut env, "chatCancel", result, ())
}

#[no_mangle]
pub extern "system" fn Java_com_anthropic_claudecode_eclipse_NativeCore_chatResetSession(
    mut env: JNIEnv,
    _class: JClass,
    handle: jlong,
) {
    let result = std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| {
        if handle == 0 {
            return;
        }
        let manager = unsafe { &*(handle as *const ChatManager) };
        manager.reset_session();
    }));
    finish_export(&mut env, "chatResetSession", result, ())
}

/// Drops the live conversation process WITHOUT clearing the conversation, so the
/// next send re-spawns with `--resume <id>` and rebuilds context from the
/// transcript on disk. Used after editing that transcript — the running process
/// holds its own copy of the conversation and would otherwise keep (and
/// re-serialize) a message that has just been deleted.
#[no_mangle]
pub extern "system" fn Java_com_anthropic_claudecode_eclipse_NativeCore_chatRestartProcess(
    mut env: JNIEnv,
    _class: JClass,
    handle: jlong,
) {
    let result = std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| {
        if handle == 0 {
            return;
        }
        let manager = unsafe { &*(handle as *const ChatManager) };
        manager.restart_process();
    }));
    finish_export(&mut env, "chatRestartProcess", result, ())
}

#[no_mangle]
pub extern "system" fn Java_com_anthropic_claudecode_eclipse_NativeCore_chatDestroy(
    mut env: JNIEnv,
    _class: JClass,
    handle: jlong,
) {
    let result = std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| {
        if handle == 0 {
            return;
        }
        let manager = unsafe { Box::from_raw(handle as *mut ChatManager) };
        drop(manager);
    }));
    finish_export(&mut env, "chatDestroy", result, ())
}

/// Runs the CLI's own `/usage` command and returns the account-global
/// subscription limits as statusLine-schema JSON (`{"rate_limits":{…}}`), or
/// `""` when they can't be determined.
///
/// **Blocking** — it spawns a short-lived `claude` process (~1.3 s), so Java
/// must call it off the UI thread. It costs the user's quota nothing (the CLI
/// answers `/usage` locally, with no API call); see `chat::fetch_usage`.
#[no_mangle]
pub extern "system" fn Java_com_anthropic_claudecode_eclipse_NativeCore_fetchUsage(
    mut env: JNIEnv,
    _class: JClass,
    claude_cmd: JString,
    workspace_root: JString,
) -> jstring {
    let result = std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| {
        let cmd: String = match env.get_string(&claude_cmd) {
            Ok(s) => s.into(),
            Err(_) => return jout(&mut env, "", ""),
        };
        let root: String = match env.get_string(&workspace_root) {
            Ok(s) => s.into(),
            Err(_) => String::new(),
        };
        let json = chat::fetch_usage(&cmd, &root).unwrap_or_default();
        jout(&mut env, json, "")
    }));
    finish_export(&mut env, "fetchUsage", result, std::ptr::null_mut())
}

/// Takes the browser back out of this tab's conversation — the banner's ×.
/// Returns whether it was on.
#[no_mangle]
pub extern "system" fn Java_com_anthropic_claudecode_eclipse_NativeCore_chatDisableChrome(
    mut env: JNIEnv,
    _class: JClass,
    handle: jlong,
) -> jboolean {
    let result = std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| {
        if handle == 0 {
            return 0;
        }
        let manager = unsafe { &*(handle as *const ChatManager) };
        manager.disable_chrome() as jboolean
    }));
    finish_export(&mut env, "chatDisableChrome", result, jni::sys::JNI_FALSE)
}

/// Renames the LIVE conversation on this chat manager's process via its control
/// channel (no extra process). Returns false if the manager isn't currently on
/// `session_id` — caller falls back to sessionRename.
#[no_mangle]
pub extern "system" fn Java_com_anthropic_claudecode_eclipse_NativeCore_chatRenameSession(
    mut env: JNIEnv,
    _class: JClass,
    handle: jlong,
    session_id: JString,
    title: JString,
) -> jboolean {
    let result = std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| {
        if handle == 0 {
            return 0;
        }
        let manager = unsafe { &*(handle as *const ChatManager) };
        let id: String = jstr(&mut env, &session_id);
        let title: String = jstr(&mut env, &title);
        manager.rename_session(&id, &title) as jboolean
    }));
    finish_export(&mut env, "chatRenameSession", result, jni::sys::JNI_FALSE)
}

/// Stops one specific background agent by its own internal task id (see
/// ChatManager::stop_task's doc comment — a different id from its tool_use id).
/// Returns false when there's no live process to send it to.
#[no_mangle]
pub extern "system" fn Java_com_anthropic_claudecode_eclipse_NativeCore_chatStopTask(
    mut env: JNIEnv,
    _class: JClass,
    handle: jlong,
    task_id: JString,
) -> jboolean {
    let result = std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| {
        if handle == 0 {
            return 0;
        }
        let manager = unsafe { &*(handle as *const ChatManager) };
        let id: String = jstr(&mut env, &task_id);
        manager.stop_task(&id) as jboolean
    }));
    finish_export(&mut env, "chatStopTask", result, jni::sys::JNI_FALSE)
}

/// Switches a live conversation's permission mode over the existing control
/// channel, so the GUI's per-tab mode dropdown applies mid-conversation instead
/// of only at the next spawn. Returns false when there's no live process (the
/// next spawn passes the mode as `--permission-mode` regardless).
#[no_mangle]
pub extern "system" fn Java_com_anthropic_claudecode_eclipse_NativeCore_chatSetPermissionMode(
    mut env: JNIEnv,
    _class: JClass,
    handle: jlong,
    mode: JString,
) -> jboolean {
    let result = std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| {
        if handle == 0 {
            return 0;
        }
        let manager = unsafe { &*(handle as *const ChatManager) };
        let mode: String = jstr(&mut env, &mode);
        manager.set_permission_mode(&mode) as jboolean
    }));
    finish_export(&mut env, "chatSetPermissionMode", result, jni::sys::JNI_FALSE)
}

/// The browser text blocks for a message about to be sent on this tab, as a JSON
/// array — `[]` when it mentions no browser. Switches the browser on for the
/// tab's live process first when it isn't yet. **Blocking** — may open a Chrome
/// tab. Off the UI thread, after the tab's process has been ensured.
#[no_mangle]
pub extern "system" fn Java_com_anthropic_claudecode_eclipse_NativeCore_chatBrowserBlocks(
    mut env: JNIEnv,
    _class: JClass,
    handle: jlong,
    claude_cmd: JString,
    message: JString,
) -> jstring {
    let result = std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| {
        if handle == 0 {
            return jout(&mut env, "[]", "");
        }
        let manager = unsafe { &*(handle as *const ChatManager) };
        let cmd: String = jstr(&mut env, &claude_cmd);
        let message: String = jstr(&mut env, &message);
        let blocks = chrome::browser_blocks(
            &cmd,
            &message,
            |cfg| manager.enable_chrome(cfg),
            chrome::instruction,
        );
        let json = serde_json::to_string(&blocks).unwrap_or_else(|_| "[]".to_string());
        jout(&mut env, json, "")
    }));
    finish_export(&mut env, "chatBrowserBlocks", result, std::ptr::null_mut())
}

/// Starts this tab's CLI process if it has none, sending nothing.
///
/// Lets Remote Control be switched on in a tab that has not had a conversation
/// yet: the CLI answers a control request before any turn, so the only thing
/// missing was a process to ask.
///
/// **Blocking** — spawns a child process. Off the UI thread.
#[no_mangle]
pub extern "system" fn Java_com_anthropic_claudecode_eclipse_NativeCore_chatEnsureProcess(
    mut env: JNIEnv,
    _class: JClass,
    handle: jlong,
    claude_cmd: JString,
    workspace_root: JString,
    mcp_port: jint,
    mcp_auth_token: JString,
    resume_id: JString,
    perm_mode: JString,
    effort: JString,
    model: JString,
    thinking: JString,
    ultracode: jboolean,
) -> jboolean {
    let result = std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| {
        if handle == 0 {
            return 0;
        }
        let manager = unsafe { &*(handle as *const ChatManager) };
        let claude_cmd = jstr(&mut env, &claude_cmd);
        let workspace_root = jstr(&mut env, &workspace_root);
        let mcp_auth_token = jstr(&mut env, &mcp_auth_token);
        let resume_id = jstr(&mut env, &resume_id);
        let perm_mode = jstr(&mut env, &perm_mode);
        let effort = jstr(&mut env, &effort);
        let model = jstr(&mut env, &model);
        let thinking = jstr(&mut env, &thinking);
        manager.ensure_process(
            claude_cmd, workspace_root, mcp_port as u16, mcp_auth_token,
            resume_id, perm_mode, effort, model, thinking,
            ultracode != 0,
        ) as jboolean
    }));
    finish_export(&mut env, "chatEnsureProcess", result, jni::sys::JNI_FALSE)
}

/// Turns Remote Control on or off for this tab's live process.
///
/// Fire-and-forget: the CLI answers asynchronously, and that answer reaches
/// Java as an `onRemoteControl` callback carrying the bridge session url.
/// Returns false only when the tab has no live process to ask.
#[no_mangle]
pub extern "system" fn Java_com_anthropic_claudecode_eclipse_NativeCore_chatRemoteControl(
    mut env: JNIEnv,
    _class: JClass,
    handle: jlong,
    enabled: jboolean,
) -> jboolean {
    let result = std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| {
        if handle == 0 {
            return 0;
        }
        let manager = unsafe { &*(handle as *const ChatManager) };
        manager.remote_control(enabled != 0) as jboolean
    }));
    finish_export(&mut env, "chatRemoteControl", result, jni::sys::JNI_FALSE)
}

/// Sends one of the MCP servers window's control requests to this tab's live
/// process, under the page's `token`.
///
/// Fire-and-forget: the reply reaches Java as an `onMcp` callback carrying the same
/// token. Returns false when the tab has no live process, or the request is not one
/// the window may send.
#[no_mangle]
pub extern "system" fn Java_com_anthropic_claudecode_eclipse_NativeCore_chatMcpRequest(
    mut env: JNIEnv,
    _class: JClass,
    handle: jlong,
    token: JString,
    request: JString,
) -> jboolean {
    let result = std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| {
        if handle == 0 {
            return 0;
        }
        let manager = unsafe { &*(handle as *const ChatManager) };
        let mut text = |s: JString| jstr(&mut env, &s);
        let (token, request) = (text(token), text(request));
        manager.mcp_request(&token, &request) as jboolean
    }));
    finish_export(&mut env, "chatMcpRequest", result, jni::sys::JNI_FALSE)
}

/// Asks this tab's live process for what one of the CLI's dialogs shows, under the
/// page's `token`.
///
/// Fire-and-forget: the reply reaches Java as an `onCliReply` callback carrying the
/// same token. Returns false when the tab has no live process, or the request is not
/// one the page may send.
#[no_mangle]
pub extern "system" fn Java_com_anthropic_claudecode_eclipse_NativeCore_chatCliRequest(
    mut env: JNIEnv,
    _class: JClass,
    handle: jlong,
    token: JString,
    request: JString,
) -> jboolean {
    let result = std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| {
        if handle == 0 {
            return 0;
        }
        let manager = unsafe { &*(handle as *const ChatManager) };
        let mut text = |s: JString| jstr(&mut env, &s);
        let (token, request) = (text(token), text(request));
        manager.cli_request(&token, &request) as jboolean
    }));
    finish_export(&mut env, "chatCliRequest", result, jni::sys::JNI_FALSE)
}

/// Applies a tab's launch settings (permission mode, effort, model, thinking) to its
/// live process at once, rather than leaving them for the next message. What the view
/// shows and what the process is running are then the same thing — and under Remote
/// Control, so is what the phone and claude.ai show.
///
/// Returns false when the tab has no live process, or when the change needs a new one.
#[no_mangle]
pub extern "system" fn Java_com_anthropic_claudecode_eclipse_NativeCore_chatApplySettings(
    mut env: JNIEnv,
    _class: JClass,
    handle: jlong,
    perm_mode: JString,
    effort: JString,
    model: JString,
    thinking: JString,
    ultracode: jboolean,
) -> jboolean {
    let result = std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| {
        if handle == 0 {
            return 0;
        }
        let manager = unsafe { &*(handle as *const ChatManager) };
        let mut text = |s: JString| jstr(&mut env, &s);
        let (perm_mode, effort, model, thinking) =
            (text(perm_mode), text(effort), text(model), text(thinking));
        manager.apply_settings_now(&perm_mode, &effort, &model, &thinking, ultracode != 0) as jboolean
    }));
    finish_export(&mut env, "chatApplySettings", result, jni::sys::JNI_FALSE)
}

/// Whether switching this tab back to a Default model would restart its process.
///
/// Every other launch setting is applied to the running process; this one can be too,
/// but only when the CLI has no model setting of its own to fall back to. The view
/// warns before the restart, because it archives the conversation's Remote Control
/// session on the other devices.
#[no_mangle]
pub extern "system" fn Java_com_anthropic_claudecode_eclipse_NativeCore_chatDefaultModelRestarts(
    mut env: JNIEnv,
    _class: JClass,
    handle: jlong,
) -> jboolean {
    let result = std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| {
        if handle == 0 {
            return 0;
        }
        let manager = unsafe { &*(handle as *const ChatManager) };
        manager.default_model_restarts() as jboolean
    }));
    finish_export(&mut env, "chatDefaultModelRestarts", result, jni::sys::JNI_FALSE)
}

// ===========================================================================
// Console embedding JNI entry points
// ===========================================================================

#[no_mangle]
pub extern "system" fn Java_com_anthropic_claudecode_eclipse_NativeCore_consoleCreate(
    mut env: JNIEnv,
    _class: JClass,
    cmd: JString,
    args_json: JString,
    extra_env_json: JString,
    cwd: JString,
) -> jlong {
    let result = std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| {
        let cmd_s: String = jstr(&mut env, &cmd);
        let args_s: String = jstr(&mut env, &args_json);
        let env_s: String = jstr(&mut env, &extra_env_json);
        let cwd_s: String = jstr(&mut env, &cwd);

        let (args, extra_env) = console::launch_spec(&args_s, &env_s);

        match console::ConsoleSession::create(&cmd_s, &args, &extra_env, &cwd_s) {
            Some(session) => Box::into_raw(Box::new(session)) as jlong,
            None => 0,
        }
    }));
    finish_export(&mut env, "consoleCreate", result, 0)
}

/// Tries to find the console window and embed it in `parent_hwnd`.
/// Returns true if embedded, false if the console window hasn't appeared yet.
#[no_mangle]
pub extern "system" fn Java_com_anthropic_claudecode_eclipse_NativeCore_consoleEmbed(
    mut env: JNIEnv,
    _class: JClass,
    handle: jlong,
    parent_hwnd: jlong,
    width: jint,
    height: jint,
) -> jboolean {
    let result = std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| {
        if handle == 0 { return 0; }
        let session = unsafe { &mut *(handle as *mut console::ConsoleSession) };
        session.try_embed(parent_hwnd as isize, width, height) as jboolean
    }));
    finish_export(&mut env, "consoleEmbed", result, jni::sys::JNI_FALSE)
}

#[no_mangle]
pub extern "system" fn Java_com_anthropic_claudecode_eclipse_NativeCore_consoleResize(
    mut env: JNIEnv,
    _class: JClass,
    handle: jlong,
    width: jint,
    height: jint,
) {
    let result = std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| {
        if handle == 0 { return; }
        let session = unsafe { &*(handle as *const console::ConsoleSession) };
        session.resize(width, height);
    }));
    finish_export(&mut env, "consoleResize", result, ())
}

#[no_mangle]
pub extern "system" fn Java_com_anthropic_claudecode_eclipse_NativeCore_consoleFocus(
    mut env: JNIEnv,
    _class: JClass,
    handle: jlong,
) {
    let result = std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| {
        if handle == 0 { return; }
        let session = unsafe { &*(handle as *const console::ConsoleSession) };
        session.set_focus();
    }));
    finish_export(&mut env, "consoleFocus", result, ())
}

/// Returns true if the console HWND currently has Win32 keyboard focus.
#[no_mangle]
pub extern "system" fn Java_com_anthropic_claudecode_eclipse_NativeCore_consoleIsFocused(
    mut env: JNIEnv,
    _class: JClass,
    handle: jlong,
) -> jboolean {
    let result = std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| {
        if handle == 0 { return 0; }
        let session = unsafe { &*(handle as *const console::ConsoleSession) };
        session.is_focused() as jboolean
    }));
    finish_export(&mut env, "consoleIsFocused", result, jni::sys::JNI_FALSE)
}

/// Posts a Win32 message (WM_CHAR, WM_KEYDOWN, etc.) to the console HWND.
#[no_mangle]
pub extern "system" fn Java_com_anthropic_claudecode_eclipse_NativeCore_consolePostMessage(
    mut env: JNIEnv,
    _class: JClass,
    handle: jlong,
    msg: jint,
    wparam: jlong,
    lparam: jlong,
) {
    let result = std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| {
        if handle == 0 { return; }
        let session = unsafe { &*(handle as *const console::ConsoleSession) };
        session.post_message(msg as u32, wparam as usize, lparam as isize);
    }));
    finish_export(&mut env, "consolePostMessage", result, ())
}

#[no_mangle]
pub extern "system" fn Java_com_anthropic_claudecode_eclipse_NativeCore_consoleSetFont(
    mut env: JNIEnv,
    _class: JClass,
    handle: jlong,
    font_name: JString,
    font_size: jint,
) {
    let result = std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| {
        if handle == 0 { return; }
        let name: String = match env.get_string(&font_name) {
            Ok(s) => s.into(),
            Err(_) => return,
        };
        let session = unsafe { &*(handle as *const console::ConsoleSession) };
        session.set_font(&name, font_size as i16);
    }));
    finish_export(&mut env, "consoleSetFont", result, ())
}

#[no_mangle]
pub extern "system" fn Java_com_anthropic_claudecode_eclipse_NativeCore_consoleSetColors(
    mut env: JNIEnv,
    _class: JClass,
    handle: jlong,
    bg_r: jint,
    bg_g: jint,
    bg_b: jint,
    fg_r: jint,
    fg_g: jint,
    fg_b: jint,
) {
    let result = std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| {
        if handle == 0 { return; }
        let session = unsafe { &*(handle as *const console::ConsoleSession) };
        session.set_colors(bg_r as u8, bg_g as u8, bg_b as u8, fg_r as u8, fg_g as u8, fg_b as u8);
    }));
    finish_export(&mut env, "consoleSetColors", result, ())
}

#[no_mangle]
pub extern "system" fn Java_com_anthropic_claudecode_eclipse_NativeCore_consoleDestroy(
    mut env: JNIEnv,
    _class: JClass,
    handle: jlong,
) {
    let result = std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| {
        if handle == 0 { return; }
        let session = unsafe { Box::from_raw(handle as *mut console::ConsoleSession) };
        drop(session);
    }));
    finish_export(&mut env, "consoleDestroy", result, ())
}

// ===========================================================================
// Browser input activation (Windows only — used by Chat view)
//
// WebView2 will NOT route keyboard events to the page until it has received
// at least one real Win32 WM_KEYDOWN on its host window.  SWT's Browser
// WndProc DOES forward WM_KEYDOWN to the WebView2 controller (keyboard
// input must reach the web page), but does NOT forward WM_LBUTTONDOWN the
// same way.  This is why pressing keys during the blank loading phase works
// as a workaround, but clicking does not.
// ===========================================================================

#[cfg(windows)]
#[link(name = "user32")]
extern "system" {
    fn PostMessageW(hwnd: isize, msg: u32, wparam: usize, lparam: isize) -> i32;
    fn SetFocus(hwnd: isize) -> isize;
    fn GetWindow(hwnd: isize, cmd: u32) -> isize;
}

/// Walks down the child window chain from `hwnd` to find the deepest
/// descendant (the WebView2 Chromium rendering surface).  Returns
/// `hwnd` itself if it has no children.
#[cfg(windows)]
unsafe fn find_deepest_child(hwnd: isize) -> isize {
    let mut current = hwnd;
    loop {
        let child = GetWindow(current, 5); // GW_CHILD = 5
        if child == 0 { return current; }
        current = child;
    }
}

#[no_mangle]
pub extern "system" fn Java_com_anthropic_claudecode_eclipse_NativeCore_browserActivateInput(
    mut env: JNIEnv,
    _class: JClass,
    hwnd: jlong,
) {
    let result = std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| {
        if hwnd == 0 { return; }
        #[cfg(windows)]
        unsafe {
            // Find the actual WebView2 rendering window buried inside the
            // SWT Browser host.  Sending directly to this child bypasses
            // SWT's WndProc, which only forwards WM_KEYDOWN when the
            // browser has SWT focus — the root cause of the intermittent
            // "can't type" issue.
            let target = find_deepest_child(hwnd as isize);

            // Give Win32 keyboard focus directly to the WebView2 child.
            SetFocus(target);

            // Simulate pressing and releasing Shift (VK_SHIFT = 0x10).
            // Shift is harmless — xterm.js ignores bare modifier keys.
            let kd_lp: isize = (0x2A_isize << 16) | 1;
            let ku_lp: isize = kd_lp | (3_isize << 30);
            PostMessageW(target, 0x0100, 0x10, kd_lp);  // WM_KEYDOWN  VK_SHIFT
            PostMessageW(target, 0x0101, 0x10, ku_lp);  // WM_KEYUP    VK_SHIFT
        }
        #[cfg(not(windows))]
        let _ = hwnd;
    }));
    finish_export(&mut env, "browserActivateInput", result, ())
}

// ===========================================================================
// Bridge JNI entry points
// ===========================================================================

/// Generates a fresh random handshake token for one relay session.
/// Java hands it to the relay and to its own socket, then passes it back here
/// via bridgeConnect so the Rust side authenticates too.
#[no_mangle]
pub extern "system" fn Java_com_anthropic_claudecode_eclipse_NativeCore_bridgeGenerateToken(
    mut env: JNIEnv,
    _class: JClass,
) -> jstring {
    let result = std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| {
        let token = uuid::Uuid::new_v4().to_string();
        jout(&mut env, token, "")
    }));
    finish_export(&mut env, "bridgeGenerateToken", result, std::ptr::null_mut())
}

/// Starts the in-process relay: binds the first two free ports in
/// [portMin, portMax] and returns "portA portB", or "" when no pair is free.
/// Every peer must present `token` on its first line (see bridgeGenerateToken).
#[no_mangle]
pub extern "system" fn Java_com_anthropic_claudecode_eclipse_NativeCore_bridgeStartRelay(
    mut env: JNIEnv,
    _class: JClass,
    port_min: jint,
    port_max: jint,
    token: JString,
) -> jstring {
    let result = std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| {
        let token: String = jstr(&mut env, &token);
        let out = match bridge::relay_start(port_min as u16, port_max as u16, &token) {
            Some((a, b)) => format!("{} {}", a, b),
            None => String::new(),
        };
        jout(&mut env, out, "")
    }));
    finish_export(&mut env, "bridgeStartRelay", result, std::ptr::null_mut())
}

#[no_mangle]
pub extern "system" fn Java_com_anthropic_claudecode_eclipse_NativeCore_bridgeStopRelay(
    mut env: JNIEnv,
    _class: JClass,
) {
    let result = std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| {
        bridge::relay_stop();
    }));
    finish_export(&mut env, "bridgeStopRelay", result, ())
}

#[no_mangle]
pub extern "system" fn Java_com_anthropic_claudecode_eclipse_NativeCore_bridgeRelayIsRunning(
    mut env: JNIEnv,
    _class: JClass,
) -> jboolean {
    let result = std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| {
        bridge::relay_is_running() as jboolean
    }));
    finish_export(&mut env, "bridgeRelayIsRunning", result, jni::sys::JNI_FALSE)
}

#[no_mangle]
pub extern "system" fn Java_com_anthropic_claudecode_eclipse_NativeCore_bridgeConnect(
    mut env: JNIEnv,
    _class: JClass,
    port: jint,
    token: JString,
) -> jboolean {
    let result = std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| {
        let token: String = jstr(&mut env, &token);
        bridge::connect(port as u16, &token) as jboolean
    }));
    finish_export(&mut env, "bridgeConnect", result, jni::sys::JNI_FALSE)
}

#[no_mangle]
pub extern "system" fn Java_com_anthropic_claudecode_eclipse_NativeCore_bridgeDisconnect(
    mut env: JNIEnv,
    _class: JClass,
) {
    let result = std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| {
        bridge::disconnect();
    }));
    finish_export(&mut env, "bridgeDisconnect", result, ())
}

#[no_mangle]
pub extern "system" fn Java_com_anthropic_claudecode_eclipse_NativeCore_bridgeIsConnected(
    mut env: JNIEnv,
    _class: JClass,
) -> jboolean {
    let result = std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| {
        bridge::is_connected() as jboolean
    }));
    finish_export(&mut env, "bridgeIsConnected", result, jni::sys::JNI_FALSE)
}

/// Renders a Remote Control session url as a scannable QR code, as SVG.
///
/// Generated on demand rather than carried on every toggle reply: it is a few
/// KB of markup and is only wanted when the user actually reveals it.
#[no_mangle]
pub extern "system" fn Java_com_anthropic_claudecode_eclipse_NativeCore_remoteControlQr(
    mut env: JNIEnv,
    _class: JClass,
    url: JString,
) -> jstring {
    let result = std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| {
        let u: String = jstr(&mut env, &url);
        let svg = bridge::rc_qr_svg(&u);
        jout(&mut env, svg, "")
    }));
    finish_export(&mut env, "remoteControlQr", result, std::ptr::null_mut())
}

// ===========================================================================
// Proxy configuration JNI entry points
// ===========================================================================

#[no_mangle]
pub extern "system" fn Java_com_anthropic_claudecode_eclipse_NativeCore_setProxyOverrides(
    mut env: JNIEnv,
    _class: JClass,
    http_proxy: JString,
    https_proxy: JString,
    no_proxy: JString,
) {
    let result = std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| {
        let http = Some(jstr(&mut env, &http_proxy)).filter(|s| !s.is_empty());
        let https = Some(jstr(&mut env, &https_proxy)).filter(|s| !s.is_empty());
        let no = Some(jstr(&mut env, &no_proxy)).filter(|s| !s.is_empty());
        shell_env::set_proxy_overrides(http, https, no);
    }));
    finish_export(&mut env, "setProxyOverrides", result, ())
}

// ===========================================================================
// Session history JNI entry points (local — reads ~/.claude/projects/*.jsonl)
// ===========================================================================

#[no_mangle]
pub extern "system" fn Java_com_anthropic_claudecode_eclipse_NativeCore_sessionList(
    mut env: JNIEnv,
    _class: JClass,
    workspace_root: JString,
) -> jstring {
    let result = std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| {
        let root: String = jstr(&mut env, &workspace_root);
        let json = session::list_sessions(&root);
        jout(&mut env, json, "[]")
    }));
    finish_export(&mut env, "sessionList", result, std::ptr::null_mut())
}

#[no_mangle]
pub extern "system" fn Java_com_anthropic_claudecode_eclipse_NativeCore_sessionSearchContent(
    mut env: JNIEnv,
    _class: JClass,
    workspace_root: JString,
    session_ids_json: JString,
    query: JString,
    own_messages_only: jboolean,
    generation: jlong,
) -> jstring {
    let result = std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| {
        let root: String = jstr(&mut env, &workspace_root);
        let ids_json: String = if session_ids_json.is_null() {
            "[]".to_string()
        } else {
            env.get_string(&session_ids_json).ok().map(|s| s.into()).unwrap_or_else(|| "[]".to_string())
        };
        let q: String = jstr(&mut env, &query);
        let ids: Vec<String> = serde_json::from_str(&ids_json).unwrap_or_default();
        let json = session::search_session_content(&root, &ids, &q, own_messages_only != 0, generation as u64);
        jout(&mut env, json, "[]")
    }));
    finish_export(&mut env, "sessionSearchContent", result, std::ptr::null_mut())
}

#[no_mangle]
pub extern "system" fn Java_com_anthropic_claudecode_eclipse_NativeCore_sessionLoad(
    mut env: JNIEnv,
    _class: JClass,
    workspace_root: JString,
    session_id: JString,
) -> jstring {
    let result = std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| {
        let root: String = jstr(&mut env, &workspace_root);
        let id: String = jstr(&mut env, &session_id);
        let json = session::load_session_history(&root, &id);
        jout(&mut env, json, "[]")
    }));
    finish_export(&mut env, "sessionLoad", result, std::ptr::null_mut())
}

/// Writes one uploaded document out of a transcript to a file and returns its
/// path (`""` when it isn't there) — what a reloaded conversation's attachment
/// chip opens. **Blocking**, and the file can be large: off the UI thread.
#[no_mangle]
pub extern "system" fn Java_com_anthropic_claudecode_eclipse_NativeCore_sessionDocumentFile(
    mut env: JNIEnv,
    _class: JClass,
    workspace_root: JString,
    session_id: JString,
    message_uuid: JString,
    index: jint,
) -> jstring {
    let result = std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| {
        let mut arg = |s: JString| jstr(&mut env, &s);
        let root = arg(workspace_root);
        let id = arg(session_id);
        let uuid = arg(message_uuid);
        let path = session::session_document_file(&root, &id, &uuid, index.max(0) as usize);
        jout(&mut env, path, "")
    }));
    finish_export(&mut env, "sessionDocumentFile", result, std::ptr::null_mut())
}

/// Deletes one local session jsonl. Rejects ids that could escape the
/// projects directory; returns whether the file was actually removed.
#[no_mangle]
pub extern "system" fn Java_com_anthropic_claudecode_eclipse_NativeCore_sessionDelete(
    mut env: JNIEnv,
    _class: JClass,
    workspace_root: JString,
    session_id: JString,
) -> jboolean {
    let result = std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| {
        let root: String = jstr(&mut env, &workspace_root);
        let id: String = jstr(&mut env, &session_id);
        session::delete_session(&root, &id) as jboolean
    }));
    finish_export(&mut env, "sessionDelete", result, jni::sys::JNI_FALSE)
}

/// Ordered transcript uuids of a session's user messages, matching the bubbles
/// `sessionLoad` renders — the ids the GUI's per-message actions target.
#[no_mangle]
pub extern "system" fn Java_com_anthropic_claudecode_eclipse_NativeCore_sessionMessageIds(
    mut env: JNIEnv,
    _class: JClass,
    workspace_root: JString,
    session_id: JString,
) -> jstring {
    let result = std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| {
        let root = jstr(&mut env, &workspace_root);
        let id = jstr(&mut env, &session_id);
        let json = session::message_ids(&root, &id);
        jout(&mut env, json, "[]")
    }));
    finish_export(&mut env, "sessionMessageIds", result, std::ptr::null_mut())
}

/// Permanently removes one user message from a session transcript: the chained
/// line, its unchained prompt copies, and nothing else. Returns
/// `{"ok":true,"stripped":N}` or `{"error":"…"}`.
#[no_mangle]
pub extern "system" fn Java_com_anthropic_claudecode_eclipse_NativeCore_sessionDeleteMessage(
    mut env: JNIEnv,
    _class: JClass,
    workspace_root: JString,
    session_id: JString,
    message_id: JString,
) -> jstring {
    let result = std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| {
        let root = jstr(&mut env, &workspace_root);
        let id = jstr(&mut env, &session_id);
        let mid = jstr(&mut env, &message_id);
        let json = session::delete_message(&root, &id, &mid);
        jout(&mut env, json, r#"{"error":"internal"}"#)
    }));
    finish_export(&mut env, "sessionDeleteMessage", result, std::ptr::null_mut())
}

/// Renames an INACTIVE session the CLI-native way (headless --resume + the
/// rename_session control request → `custom-title` event in the shared jsonl,
/// visible to /resume and VSCode). Blocks up to ~15s; call off the UI thread.
#[no_mangle]
pub extern "system" fn Java_com_anthropic_claudecode_eclipse_NativeCore_sessionRename(
    mut env: JNIEnv,
    _class: JClass,
    claude_cmd: JString,
    workspace_root: JString,
    session_id: JString,
    title: JString,
) -> jboolean {
    let result = std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| {
        let cmd = jstr(&mut env, &claude_cmd);
        let root = jstr(&mut env, &workspace_root);
        let id = jstr(&mut env, &session_id);
        let title = jstr(&mut env, &title);
        session::rename_session_offline(&cmd, &root, &id, &title) as jboolean
    }));
    finish_export(&mut env, "sessionRename", result, jni::sys::JNI_FALSE)
}

// ===========================================================================
// Session archive JNI entry points (the Claude Code view's own record)
// ===========================================================================

/// The history list with the archive applied: `sessions_json` (what `sessionList`
/// gave for `workspace_root`) with an `archived` flag on every row, after archiving
/// the rows inactive for `days`. Returns `{"sessions": [...], "archivedNow": [...]}`;
/// `in_use_json` names the conversations open in a tab, which the sweep passes over.
/// Reads the store and one file time per row, so not for the UI thread.
#[no_mangle]
pub extern "system" fn Java_com_anthropic_claudecode_eclipse_NativeCore_sessionArchiveApply(
    mut env: JNIEnv,
    _class: JClass,
    store_path: JString,
    workspace_root: JString,
    sessions_json: JString,
    days: jint,
    in_use_json: JString,
) -> jstring {
    let result = std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| {
        let mut arg = |s: JString| jstr(&mut env, &s);
        let store = arg(store_path);
        let root = arg(workspace_root);
        let sessions = arg(sessions_json);
        let in_use = arg(in_use_json);
        let json = archive::apply(&store, &root, &sessions, days as i64, &in_use);
        jout(&mut env, json, "")
    }));
    finish_export(&mut env, "sessionArchiveApply", result, std::ptr::null_mut())
}

/// Archives (`archived`) or unarchives the conversations of `ids_json`, a JSON array
/// of session ids. Returns whether it was recorded.
#[no_mangle]
pub extern "system" fn Java_com_anthropic_claudecode_eclipse_NativeCore_sessionArchiveSet(
    mut env: JNIEnv,
    _class: JClass,
    store_path: JString,
    ids_json: JString,
    archived: jboolean,
) -> jboolean {
    let result = std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| {
        let store = jstr(&mut env, &store_path);
        let ids = jstr(&mut env, &ids_json);
        archive::set(&store, &ids, archived != 0) as jboolean
    }));
    finish_export(&mut env, "sessionArchiveSet", result, jni::sys::JNI_FALSE)
}

// ===========================================================================
// Bookmark JNI entry points (replies the user marked, per conversation)
// ===========================================================================

/// The replies the GUI draws as text, in order, as `[{"id","text","at"}]`: the
/// transcript line each one is, so a reply on screen can be bookmarked. Reads the
/// whole transcript, like `sessionLoad`.
#[no_mangle]
pub extern "system" fn Java_com_anthropic_claudecode_eclipse_NativeCore_sessionReplyIds(
    mut env: JNIEnv,
    _class: JClass,
    workspace_root: JString,
    session_id: JString,
) -> jstring {
    let result = std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| {
        let root: String = jstr(&mut env, &workspace_root);
        let id: String = jstr(&mut env, &session_id);
        let json = session::reply_ids(&root, &id);
        jout(&mut env, json, "[]")
    }));
    finish_export(&mut env, "sessionReplyIds", result, std::ptr::null_mut())
}

/// A conversation's bookmarks, oldest reply first, as a JSON array of
/// `{uuid, addedAt, writtenAt?}`, from the directory `dir`.
#[no_mangle]
pub extern "system" fn Java_com_anthropic_claudecode_eclipse_NativeCore_sessionBookmarks(
    mut env: JNIEnv,
    _class: JClass,
    dir: JString,
    session_id: JString,
) -> jstring {
    let result = std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| {
        let dir: String = jstr(&mut env, &dir);
        let id: String = jstr(&mut env, &session_id);
        let json = bookmarks::list(&dir, &id);
        jout(&mut env, json, "[]")
    }));
    finish_export(&mut env, "sessionBookmarks", result, std::ptr::null_mut())
}

/// Bookmarks the reply `uuid` of a conversation (`on`) or takes its bookmark away.
/// `written_at_ms` is when the reply was written, 0 when not known. Returns
/// `{"ok": <recorded>, "bookmarks": [<the list as it now is>]}`.
#[no_mangle]
pub extern "system" fn Java_com_anthropic_claudecode_eclipse_NativeCore_sessionBookmarkSet(
    mut env: JNIEnv,
    _class: JClass,
    dir: JString,
    session_id: JString,
    uuid: JString,
    on: jboolean,
    written_at_ms: jlong,
) -> jstring {
    let result = std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| {
        let mut arg = |s: JString| jstr(&mut env, &s);
        let dir = arg(dir);
        let id = arg(session_id);
        let uuid = arg(uuid);
        let json = bookmarks::set(&dir, &id, &uuid, on != 0, written_at_ms);
        jout(&mut env, json, "")
    }));
    finish_export(&mut env, "sessionBookmarkSet", result, std::ptr::null_mut())
}

/// The text of replies of a conversation, read from its transcript: a JSON object of
/// `uuid → text`, null for one the transcript does not hold. `uuids_json` is a JSON
/// array of ids. Reads the transcript: not for the UI thread on a long conversation.
#[no_mangle]
pub extern "system" fn Java_com_anthropic_claudecode_eclipse_NativeCore_sessionBookmarkTexts(
    mut env: JNIEnv,
    _class: JClass,
    workspace_root: JString,
    session_id: JString,
    uuids_json: JString,
) -> jstring {
    let result = std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| {
        let mut arg = |s: JString| jstr(&mut env, &s);
        let root = arg(workspace_root);
        let id = arg(session_id);
        let uuids = arg(uuids_json);
        let json = bookmarks::texts(&root, &id, &uuids);
        jout(&mut env, json, "{}")
    }));
    finish_export(&mut env, "sessionBookmarkTexts", result, std::ptr::null_mut())
}

// ===========================================================================
// Prompt cache JNI entry point (what resuming a saved conversation will cost)
// ===========================================================================

/// The note a reopened conversation ends with when the prompt cache no longer holds
/// it, `""` when there is none to show. Reads the whole transcript, like `sessionLoad`.
#[no_mangle]
pub extern "system" fn Java_com_anthropic_claudecode_eclipse_NativeCore_sessionResumeNote(
    mut env: JNIEnv,
    _class: JClass,
    workspace_root: JString,
    session_id: JString,
) -> jstring {
    let result = std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| {
        let root: String = jstr(&mut env, &workspace_root);
        let id: String = jstr(&mut env, &session_id);
        let note = promptcache::resume_note(&root, &id);
        jout(&mut env, note, "")
    }));
    finish_export(&mut env, "sessionResumeNote", result, std::ptr::null_mut())
}

// ===========================================================================
// Opening a saved conversation (everything the view draws it from, in one reading)
// ===========================================================================

/// A saved conversation for the view to draw: `{items, note, cut, earlier}` — its render
/// items with each reply's transcript line, the note for reopening it, and its last
/// compaction. With `from_last_compaction` the items begin there and `earlier` says what
/// was left out. Reads the whole transcript: for a background thread.
#[no_mangle]
pub extern "system" fn Java_com_anthropic_claudecode_eclipse_NativeCore_sessionOpen(
    mut env: JNIEnv,
    _class: JClass,
    workspace_root: JString,
    session_id: JString,
    from_last_compaction: jboolean,
) -> jstring {
    let result = std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| {
        let root: String = jstr(&mut env, &workspace_root);
        let id: String = jstr(&mut env, &session_id);
        let json = session::open_session(&root, &id, from_last_compaction != 0);
        jout(&mut env, json, "{}")
    }));
    finish_export(&mut env, "sessionOpen", result, std::ptr::null_mut())
}

/// The render items of the part of a conversation before one of its compactions, named
/// by its boundary line: `{items}`. Reads the whole transcript: for a background thread.
#[no_mangle]
pub extern "system" fn Java_com_anthropic_claudecode_eclipse_NativeCore_sessionOpenBefore(
    mut env: JNIEnv,
    _class: JClass,
    workspace_root: JString,
    session_id: JString,
    boundary_uuid: JString,
) -> jstring {
    let result = std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| {
        let mut arg = |s: JString| jstr(&mut env, &s);
        let root = arg(workspace_root);
        let id = arg(session_id);
        let boundary = arg(boundary_uuid);
        let json = session::open_session_before(&root, &id, &boundary);
        jout(&mut env, json, "{}")
    }));
    finish_export(&mut env, "sessionOpenBefore", result, std::ptr::null_mut())
}

// ===========================================================================
// Composer @-mention JNI entry point
// ===========================================================================

/// The composer's `@` list for `query`: the files and folders under `root`, with
/// the browser tabs after them when `with_browser` (see
/// `mentions::with_browser_rows` for the order). Those tabs come from the last
/// lookup so a keystroke never waits on Chrome; a word starting `browser:` asks
/// Chrome itself. **Blocking** (a folder walk, or Chrome): off the UI thread.
#[no_mangle]
pub extern "system" fn Java_com_anthropic_claudecode_eclipse_NativeCore_listMentions(
    mut env: JNIEnv,
    _class: JClass,
    root: JString,
    claude_cmd: JString,
    query: JString,
    with_browser: jboolean,
) -> jstring {
    let result = std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| {
        let mut arg = |s: JString| jstr(&mut env, &s);
        let root = arg(root);
        let cmd = arg(claude_cmd);
        let query = arg(query);
        let json = mentions::mention_rows_json(
            &root,
            &query,
            with_browser != 0,
            || chrome::browser_tabs_json(&cmd, &query),
            || chrome::cached_browser_tabs_json(&cmd, &query),
        );
        jout(&mut env, json, "[]")
    }));
    finish_export(&mut env, "listMentions", result, std::ptr::null_mut())
}

// ===========================================================================
// MCP servers window JNI entry point
// ===========================================================================

/// Adds or removes an MCP server with `claude mcp add|remove`, run in `cwd`.
/// Returns `{"token","ok"}` or `{"token","ok":false,"error"}`.
///
/// **Blocking** — runs the CLI, up to 30s. Off the UI thread.
#[no_mangle]
pub extern "system" fn Java_com_anthropic_claudecode_eclipse_NativeCore_mcpEditConfig(
    mut env: JNIEnv,
    _class: JClass,
    claude_cmd: JString,
    cwd: JString,
    token: JString,
    op: JString,
) -> jstring {
    let result = std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| {
        let mut text = |s: JString| jstr(&mut env, &s);
        let (claude_cmd, cwd, token, op) = (text(claude_cmd), text(cwd), text(token), text(op));
        let json = mcp_servers::edit_config(&claude_cmd, &cwd, &token, &op);
        jout(&mut env, json, "")
    }));
    finish_export(&mut env, "mcpEditConfig", result, std::ptr::null_mut())
}

// ===========================================================================
// CLI dialogs JNI entry point
// ===========================================================================

/// What the folder `cwd` offers, asked of a short-lived CLI that is sent
/// `initialize` and nothing else: its slash commands, models and output styles.
/// Returns `{"ok":true,"commands":[…],…}` or `{"ok":false,"error"}`.
///
/// **Blocking** — starts the CLI and waits for its answer, up to 20s. Off the UI thread.
#[no_mangle]
pub extern "system" fn Java_com_anthropic_claudecode_eclipse_NativeCore_cliFetchCommands(
    mut env: JNIEnv,
    _class: JClass,
    claude_cmd: JString,
    cwd: JString,
) -> jstring {
    let result = std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| {
        let mut text = |s: JString| jstr(&mut env, &s);
        let (claude_cmd, cwd) = (text(claude_cmd), text(cwd));
        let json = cli_ask::fetch_commands(&claude_cmd, &cwd);
        jout(&mut env, json, "")
    }));
    finish_export(&mut env, "cliFetchCommands", result, std::ptr::null_mut())
}

/// Saves one change a dialog made, by running the CLI's own edit subcommand
/// (`claude edit-… --json`) in `cwd` with the change as JSON on its stdin.
/// Returns `{"ok":true,"output"}` or `{"ok":false,"error"}`.
///
/// **Blocking** — runs the CLI, up to 30s. Off the UI thread.
#[no_mangle]
pub extern "system" fn Java_com_anthropic_claudecode_eclipse_NativeCore_cliEdit(
    mut env: JNIEnv,
    _class: JClass,
    claude_cmd: JString,
    cwd: JString,
    subcommand: JString,
    input: JString,
) -> jstring {
    let result = std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| {
        let mut text = |s: JString| jstr(&mut env, &s);
        let (claude_cmd, cwd, subcommand, input) = (text(claude_cmd), text(cwd), text(subcommand), text(input));
        let json = cli_ask::edit(&claude_cmd, &cwd, &subcommand, &input);
        jout(&mut env, json, "")
    }));
    finish_export(&mut env, "cliEdit", result, std::ptr::null_mut())
}

/// One step of the Claude Design sign-in (`start`, `wait`, `code`, `cancel`). See
/// [`design_login::run`].
///
/// **Blocking** — `start` waits for the CLI to name its pages and `wait` for the
/// sign-in to end, up to six minutes. Off the UI thread.
#[no_mangle]
pub extern "system" fn Java_com_anthropic_claudecode_eclipse_NativeCore_cliDesignLogin(
    mut env: JNIEnv,
    _class: JClass,
    claude_cmd: JString,
    cwd: JString,
    op: JString,
    arg: JString,
) -> jstring {
    let result = std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| {
        let mut text = |s: JString| jstr(&mut env, &s);
        let (claude_cmd, cwd, op, arg) = (text(claude_cmd), text(cwd), text(op), text(arg));
        let json = design_login::run(&claude_cmd, &cwd, &op, &arg);
        jout(&mut env, json, "")
    }));
    finish_export(&mut env, "cliDesignLogin", result, std::ptr::null_mut())
}

/// Saves one of the command menu's settings in the user's own settings file. See
/// [`user_settings::set`].
#[no_mangle]
pub extern "system" fn Java_com_anthropic_claudecode_eclipse_NativeCore_userSettingSet(
    mut env: JNIEnv,
    _class: JClass,
    key: JString,
    value: JString,
) -> jstring {
    let result = std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| {
        let mut text = |s: JString| jstr(&mut env, &s);
        let (key, value) = (text(key), text(value));
        let json = user_settings::set(&key, &value);
        jout(&mut env, json, "")
    }));
    finish_export(&mut env, "userSettingSet", result, std::ptr::null_mut())
}

// ===========================================================================
// Debug and Auto mode JNI entry points
// ===========================================================================

#[no_mangle]
pub extern "system" fn Java_com_anthropic_claudecode_eclipse_NativeCore_setDebugMode(
    mut env: JNIEnv,
    _class: JClass,
    enabled: jboolean,
) {
    let result = std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| {
        DEBUG_MODE.store(enabled != 0, Ordering::Relaxed);
    }));
    finish_export(&mut env, "setDebugMode", result, ())
}

/// Whether chat processes are launched able to enter Auto mode without a restart.
#[no_mangle]
pub extern "system" fn Java_com_anthropic_claudecode_eclipse_NativeCore_setLiveAutoMode(
    mut env: JNIEnv,
    _class: JClass,
    enabled: jboolean,
) {
    let result = std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| {
        LIVE_AUTO_MODE.store(enabled != 0, Ordering::Relaxed);
    }));
    finish_export(&mut env, "setLiveAutoMode", result, ())
}

/// Whether the CLI that `claude_cmd` runs knows `flag`, for the Claude Terminal's launch:
/// the check the chat's own launch makes before passing a flag an older CLI would stop on.
#[no_mangle]
pub extern "system" fn Java_com_anthropic_claudecode_eclipse_NativeCore_cliSupportsFlag(
    mut env: JNIEnv,
    _class: JClass,
    claude_cmd: JString,
    flag: JString,
) -> jboolean {
    let result = std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| {
        let claude_cmd: String = jstr(&mut env, &claude_cmd);
        let flag: String = jstr(&mut env, &flag);
        launch::cli_supports_flag(&claude_cmd, &flag) as jboolean
    }));
    finish_export(&mut env, "cliSupportsFlag", result, jni::sys::JNI_FALSE)
}

// ===========================================================================
// Login-shell environment JNI entry point
// ===========================================================================

/// Returns the login-shell environment to inject into a spawned terminal
/// process, as a `String[]` of `KEY=VALUE` entries (e.g. `PATH=...`,
/// `HTTPS_PROXY=...`).
///
/// This is the same capture used by the chat and the old PTY launch
/// (see [`shell_env`]): GUI-launched Eclipse on macOS/Linux inherits a sparse
/// environment, so without these entries `claude` installed via
/// nvm/asdf/Homebrew/`npm -g` is invisible on PATH and shell-rc proxy vars are
/// missing. On Windows the capture is empty (the full user environment is
/// already inherited), so this returns a zero-length array.
#[no_mangle]
pub extern "system" fn Java_com_anthropic_claudecode_eclipse_NativeCore_shellEnvInject(
    mut env: JNIEnv,
    _class: JClass,
) -> jobjectArray {
    let result = std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| {
        let pairs = shell_env::captured_env().to_inject();

        let string_class = match env.find_class("java/lang/String") {
            Ok(c) => c,
            Err(_) => return std::ptr::null_mut(),
        };
        let empty = match env.new_string("") {
            Ok(s) => s,
            Err(_) => return std::ptr::null_mut(),
        };
        let array = match env.new_object_array(pairs.len() as i32, &string_class, &empty) {
            Ok(a) => a,
            Err(_) => return std::ptr::null_mut(),
        };

        for (i, (k, v)) in pairs.iter().enumerate() {
            if let Ok(entry) = env.new_string(format!("{}={}", k, v)) {
                let _ = env.set_object_array_element(&array, i as i32, &entry);
            }
        }
        array.into_raw()
    }));
    finish_export(&mut env, "shellEnvInject", result, std::ptr::null_mut())
}

// ===========================================================================
// Web session history JNI entry points (claude.ai — GET /v1/code/sessions)
// ===========================================================================

/// Returns the last web session list we rendered, or `""` when there is none.
/// Non-blocking — cache only — so the Web tab can paint before the fetch lands.
#[no_mangle]
pub extern "system" fn Java_com_anthropic_claudecode_eclipse_NativeCore_webSessionCached(
    mut env: JNIEnv,
    _class: JClass,
) -> jstring {
    let result = std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| {
        let json = web_history::cached();
        jout(&mut env, json, "")
    }));
    finish_export(&mut env, "webSessionCached", result, std::ptr::null_mut())
}

/// Lists this account's claude.ai sessions as `{state, sessions:[…]}`.
///
/// **Blocking** — file I/O, one HTTPS round trip, and on a stale credential a
/// short-lived `claude` process; Java must call it off the UI thread.
///
/// The OAuth token stays inside Rust: it is read at call time, used for the one
/// request, and zeroized. Only display fields cross this boundary.
#[no_mangle]
pub extern "system" fn Java_com_anthropic_claudecode_eclipse_NativeCore_webSessionList(
    mut env: JNIEnv,
    _class: JClass,
    claude_cmd: JString,
    force_refresh: jboolean,
) -> jstring {
    let result = std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| {
        let cmd: String = jstr(&mut env, &claude_cmd);
        let json = web_history::list(&cmd, force_refresh != 0);
        jout(&mut env, json, r#"{"state":"error","sessions":[]}"#)
    }));
    finish_export(&mut env, "webSessionList", result, std::ptr::null_mut())
}

/// Whether the CLI is signed in with a claude.ai account — what Browse the web and
/// the browser tabs in the `@` list need. Cached for a minute in the core.
#[no_mangle]
pub extern "system" fn Java_com_anthropic_claudecode_eclipse_NativeCore_hasClaudeAiLogin(
    mut env: JNIEnv,
    _class: JClass,
) -> jboolean {
    let result = std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| {
        web_history::has_claude_ai_login() as jboolean
    }));
    finish_export(&mut env, "hasClaudeAiLogin", result, jni::sys::JNI_FALSE)
}

// ===========================================================================
// Teleport JNI entry points (continuing a claude.ai session locally)
// ===========================================================================

/// Classifies a session against this workspace for the repo dialog.
///
/// Returns `{status, proceed, sessionOwner, sessionName, sessionDisplay,
/// currentDisplay}`. `proceed` is true when teleport may start without asking —
/// only a genuine mismatch, or a folder that is not a checkout, interrupt.
///
/// **Blocking** (one HTTPS call plus git). Call it off the UI thread.
#[no_mangle]
pub extern "system" fn Java_com_anthropic_claudecode_eclipse_NativeCore_teleportRepoCheck(
    mut env: JNIEnv,
    _class: JClass,
    claude_cmd: JString,
    session_id: JString,
    workspace_root: JString,
) -> jstring {
    let result = std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| {
        let cmd = jstr(&mut env, &claude_cmd);
        let id = jstr(&mut env, &session_id);
        let root = jstr(&mut env, &workspace_root);
        let json = teleport::repo_check(&cmd, &id, &root);
        jout(&mut env, json, r#"{"status":"error"}"#)
    }));
    finish_export(&mut env, "teleportRepoCheck", result, std::ptr::null_mut())
}

/// Pulls a session down as a local conversation in this workspace.
///
/// Returns `{ok:true, localSessionId, title, branch, messageCount}` — after
/// which `localSessionId` is an ordinary local session, resumable like any
/// other. `branch` is non-empty only when the session names one AND it really
/// exists; **this call never checks anything out**, so the working tree is
/// untouched. See {@link NativeCore#teleportCheckoutBranch}.
///
/// **Blocking** — several HTTPS round trips and a file write. Off the UI thread.
#[no_mangle]
pub extern "system" fn Java_com_anthropic_claudecode_eclipse_NativeCore_teleportRun(
    mut env: JNIEnv,
    _class: JClass,
    claude_cmd: JString,
    session_id: JString,
    workspace_root: JString,
) -> jstring {
    let result = std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| {
        let cmd = jstr(&mut env, &claude_cmd);
        let id = jstr(&mut env, &session_id);
        let root = jstr(&mut env, &workspace_root);
        let json = teleport::run(&cmd, &id, &root);
        jout(&mut env, json, r#"{"ok":false,"error":"error"}"#)
    }));
    finish_export(&mut env, "teleportRun", result, std::ptr::null_mut())
}

/// Switches the working tree to a teleported session's branch.
///
/// **The only teleport call that writes to the working tree**, and only after
/// the user has answered the branch prompt.
#[no_mangle]
pub extern "system" fn Java_com_anthropic_claudecode_eclipse_NativeCore_teleportCheckoutBranch(
    mut env: JNIEnv,
    _class: JClass,
    workspace_root: JString,
    branch: JString,
) -> jstring {
    let result = std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| {
        let root = jstr(&mut env, &workspace_root);
        let b = jstr(&mut env, &branch);
        let json = teleport::checkout_branch(&root, &b);
        jout(&mut env, json, r#"{"ok":false}"#)
    }));
    finish_export(&mut env, "teleportCheckoutBranch", result, std::ptr::null_mut())
}

/// Whether the tree is clean, what changed, and the branch currently out — the
/// branch prompt needs all three to warn before switching.
#[no_mangle]
pub extern "system" fn Java_com_anthropic_claudecode_eclipse_NativeCore_teleportGitStatus(
    mut env: JNIEnv,
    _class: JClass,
    workspace_root: JString,
) -> jstring {
    let result = std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| {
        let root = jstr(&mut env, &workspace_root);
        let json = teleport::git_status_json(&root);
        jout(&mut env, json, r#"{"clean":true,"changedFiles":[]}"#)
    }));
    finish_export(&mut env, "teleportGitStatus", result, std::ptr::null_mut())
}

// ===========================================================================
// Dictation JNI entry points
// ===========================================================================

/// One capture per IDE: the composer that started dictation is the only one
/// that can be typing into, and a second device open would fail anyway.
static DICTATION: OnceLock<stt::Dictation> = OnceLock::new();

fn dictation() -> &'static stt::Dictation {
    DICTATION.get_or_init(stt::Dictation::new)
}

#[no_mangle]
pub extern "system" fn Java_com_anthropic_claudecode_eclipse_NativeCore_sttRegisterCallbacks(
    mut env: JNIEnv,
    _class: JClass,
    callbacks: JObject,
) {
    let result = std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| {
        let global_ref = match env.new_global_ref(callbacks) {
            Ok(r) => r,
            Err(_) => return,
        };
        dictation().register_callbacks(java_vm(), global_ref);
    }));
    finish_export(&mut env, "sttRegisterCallbacks", result, ())
}

#[no_mangle]
pub extern "system" fn Java_com_anthropic_claudecode_eclipse_NativeCore_sttStart(
    mut env: JNIEnv,
    _class: JClass,
    keyterms: JString,
) {
    let result = std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| {
        // Hints are optional: an empty string just means no x-config-keyterms header.
        let terms: String = jstr(&mut env, &keyterms);
        dictation().start(terms);
    }));
    finish_export(&mut env, "sttStart", result, ())
}

#[no_mangle]
pub extern "system" fn Java_com_anthropic_claudecode_eclipse_NativeCore_sttStop(
    mut env: JNIEnv,
    _class: JClass,
) {
    let result = std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| {
        dictation().stop();
    }));
    finish_export(&mut env, "sttStop", result, ())
}

#[no_mangle]
pub extern "system" fn Java_com_anthropic_claudecode_eclipse_NativeCore_sttIsRecording(
    mut env: JNIEnv,
    _class: JClass,
) -> jboolean {
    let result = std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| {
        u8::from(dictation().is_recording())
    }));
    finish_export(&mut env, "sttIsRecording", result, jni::sys::JNI_FALSE)
}

/// Empty when dictation can capture on this machine, otherwise why it cannot.
/// Linux and FreeBSD load ALSA at runtime, so a machine without it still loads
/// this library and is told here instead.
#[no_mangle]
pub extern "system" fn Java_com_anthropic_claudecode_eclipse_NativeCore_sttUnavailableReason(
    mut env: JNIEnv,
    _class: JClass,
) -> jstring {
    let result = std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| {
        let reason = stt::unavailable_reason().unwrap_or_default();
        jout(&mut env, reason, "")
    }));
    finish_export(&mut env, "sttUnavailableReason", result, std::ptr::null_mut())
}

/// FreeBSD only: true when alsa-plugins -- ALSA's bridge to OSS -- is not
/// installed. Always false elsewhere.
#[no_mangle]
pub extern "system" fn Java_com_anthropic_claudecode_eclipse_NativeCore_sttNeedsAlsaPlugins(
    mut env: JNIEnv,
    _class: JClass,
) -> jboolean {
    let result = std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| {
        u8::from(stt::needs_alsa_plugins())
    }));
    finish_export(&mut env, "sttNeedsAlsaPlugins", result, jni::sys::JNI_FALSE)
}

/// Linux only: true when ALSA finds no sound card and no default capture device
/// opens. Always false elsewhere.
#[no_mangle]
pub extern "system" fn Java_com_anthropic_claudecode_eclipse_NativeCore_sttNoCaptureDevice(
    mut env: JNIEnv,
    _class: JClass,
) -> jboolean {
    let result = std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| {
        u8::from(stt::no_capture_device())
    }));
    finish_export(&mut env, "sttNoCaptureDevice", result, jni::sys::JNI_FALSE)
}

// ===========================================================================
// Dialog JNI entry points
// ===========================================================================

/// The dialogs Java cannot see as SWT widgets, as `{"dialogs":[…]}`: native ones in
/// this Eclipse and, unless `in_process_only`, those of other Eclipse instances. See
/// `dialogs`. **Blocking** — it asks other windows for their text. Off the UI thread.
#[no_mangle]
pub extern "system" fn Java_com_anthropic_claudecode_eclipse_NativeCore_dialogsList(
    mut env: JNIEnv,
    _class: JClass,
    in_process_only: jboolean,
) -> jstring {
    let result = std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| {
        let json = dialogs::list_json(in_process_only != 0);
        jout(&mut env, json, r#"{"dialogs":[]}"#)
    }));
    finish_export(&mut env, "dialogsList", result, std::ptr::null_mut())
}

/// Presses the button labelled `label` in the dialog `dialogsList` reported as `id`.
/// Returns `{"pressed":…,"dialog":…}` or `{"error":…}`. **Blocking**, as above.
#[no_mangle]
pub extern "system" fn Java_com_anthropic_claudecode_eclipse_NativeCore_dialogsPress(
    mut env: JNIEnv,
    _class: JClass,
    id: JString,
    label: JString,
) -> jstring {
    let result = std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| {
        let id = jstr(&mut env, &id);
        let label = jstr(&mut env, &label);
        let json = dialogs::press_json(&id, &label);
        jout(&mut env, json, r#"{"error":"internal"}"#)
    }));
    finish_export(&mut env, "dialogsPress", result, std::ptr::null_mut())
}

// ===========================================================================
// FreeBSD setup guide JNI entry point
// ===========================================================================

/// FreeBSD only: the setup guide (Markdown) the GUI view shows when the `claude`
/// CLI is missing. Empty elsewhere. See `freebsd_guide`.
#[no_mangle]
pub extern "system" fn Java_com_anthropic_claudecode_eclipse_NativeCore_freebsdSetupGuide(
    mut env: JNIEnv,
    _class: JClass,
) -> jstring {
    let result = std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| {
        jout(&mut env, freebsd_guide::markdown(), "")
    }));
    finish_export(&mut env, "freebsdSetupGuide", result, std::ptr::null_mut())
}

// ---------------------------------------------------------------------------
// Tests
// ---------------------------------------------------------------------------

#[cfg(test)]
mod tests {
    use super::*;

    /// Runs `f` the way an export runs its body. (The panic message the default hook
    /// prints is captured by the test harness like any other test output.)
    fn caught(f: impl FnOnce() + std::panic::UnwindSafe) -> Box<dyn std::any::Any + Send> {
        std::panic::catch_unwind(f).expect_err("the body panicked, and the panic was caught rather than aborting")
    }

    #[test]
    fn a_panic_with_a_literal_message_is_reported_with_it() {
        let panic = caught(|| panic!("handle was stale"));
        assert_eq!(
            panic_report("chatSendMessage", panic.as_ref()),
            "claude_eclipse_core: chatSendMessage panicked: handle was stale"
        );
    }

    #[test]
    fn a_panic_with_a_formatted_message_is_reported_with_it() {
        let port = 48123;
        let panic = caught(move || panic!("port {port} is gone"));
        assert_eq!(
            panic_report("serverStart", panic.as_ref()),
            "claude_eclipse_core: serverStart panicked: port 48123 is gone"
        );
    }

    #[test]
    fn a_panic_that_carries_no_text_is_still_reported() {
        let panic = caught(|| std::panic::panic_any(7_u8));
        assert_eq!(panic_report("sttStart", panic.as_ref()), "claude_eclipse_core: sttStart panicked: an unknown panic");
    }

    #[test]
    fn an_unwrap_on_a_failed_result_is_caught_like_any_other_panic() {
        // The shape of what the guard exists for: an .unwrap() deep inside an export.
        let panic = caught(|| {
            let r: Result<u8, &str> = Err("mutex poisoned");
            r.unwrap();
        });
        assert!(panic_report("serverBroadcast", panic.as_ref()).contains("mutex poisoned"));
    }
}
