mod bridge;
mod chat;
mod console;
mod launch;
mod lock_file;
mod mcp;
mod server;
mod session;
mod shell_env;

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

pub(crate) fn java_vm() -> Arc<jni::JavaVM> {
    Arc::clone(JAVA_VM.get().expect("JavaVM not initialised"))
}

/// Called once by the JVM when System.loadLibrary("claude_eclipse_core") succeeds.
#[no_mangle]
pub unsafe extern "system" fn JNI_OnLoad(
    raw_jvm: *mut jni::sys::JavaVM,
    _reserved: *mut std::ffi::c_void,
) -> jni::sys::jint {
    let vm = jni::JavaVM::from_raw(raw_jvm).expect("JavaVM::from_raw failed");
    JAVA_VM.set(Arc::new(vm)).ok();
    jni::sys::JNI_VERSION_1_8
}

// ===========================================================================
// Server JNI entry points
// ===========================================================================

/// Creates a Server instance and returns it as an opaque jlong handle.
/// Java must later call serverStop(handle) to free it.
#[no_mangle]
pub extern "system" fn Java_com_anthropic_claudecode_eclipse_NativeCore_serverCreate(
    _env: JNIEnv,
    _class: JClass,
    port_min: jint,
    port_max: jint,
) -> jlong {
    let server = Server::new(port_min as u16, port_max as u16);
    Box::into_raw(Box::new(server)) as jlong
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
    let pref_port = if preferred_port > 0 { Some(preferred_port as u16) } else { None };
    let token: Option<String> = if auth_token.is_null() {
        None
    } else {
        env.get_string(&auth_token).ok().map(|s| s.into())
    };
    let server = Server::new_with_config(port_min as u16, port_max as u16, pref_port, token);
    Box::into_raw(Box::new(server)) as jlong
}

/// Starts the server.  Returns the bound port, or 0 on failure.
#[no_mangle]
pub extern "system" fn Java_com_anthropic_claudecode_eclipse_NativeCore_serverStart(
    _env: JNIEnv,
    _class: JClass,
    handle: jlong,
) -> jint {
    if handle == 0 {
        return 0;
    }
    let server = unsafe { &*(handle as *const Server) };
    server.start() as jint
}

/// Stops the server and frees its memory.  The handle must not be used after this call.
#[no_mangle]
pub extern "system" fn Java_com_anthropic_claudecode_eclipse_NativeCore_serverStop(
    _env: JNIEnv,
    _class: JClass,
    handle: jlong,
) {
    if handle == 0 {
        return;
    }
    // Reconstruct the Box so it is dropped (and Server::drop runs) at end of scope.
    let server = unsafe { Box::from_raw(handle as *mut Server) };
    drop(server);
}

#[no_mangle]
pub extern "system" fn Java_com_anthropic_claudecode_eclipse_NativeCore_serverGetPort(
    _env: JNIEnv,
    _class: JClass,
    handle: jlong,
) -> jint {
    if handle == 0 {
        return 0;
    }
    let server = unsafe { &*(handle as *const Server) };
    server.port() as jint
}

#[no_mangle]
pub extern "system" fn Java_com_anthropic_claudecode_eclipse_NativeCore_serverGetAuthToken(
    env: JNIEnv,
    _class: JClass,
    handle: jlong,
) -> jstring {
    if handle == 0 {
        return env.new_string("").unwrap().into_raw();
    }
    let server = unsafe { &*(handle as *const Server) };
    env.new_string(server.auth_token()).unwrap().into_raw()
}

#[no_mangle]
pub extern "system" fn Java_com_anthropic_claudecode_eclipse_NativeCore_serverBroadcast(
    mut env: JNIEnv,
    _class: JClass,
    handle: jlong,
    json: JString,
) {
    if handle == 0 {
        return;
    }
    let server = unsafe { &*(handle as *const Server) };
    let json_str: String = env.get_string(&json).unwrap().into();
    server.broadcast(&json_str);
}

#[no_mangle]
pub extern "system" fn Java_com_anthropic_claudecode_eclipse_NativeCore_serverGetClientCount(
    _env: JNIEnv,
    _class: JClass,
    handle: jlong,
) -> jint {
    if handle == 0 {
        return 0;
    }
    let server = unsafe { &*(handle as *const Server) };
    server.client_count() as jint
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
    if handle == 0 {
        return;
    }
    let server = unsafe { &*(handle as *const Server) };
    let fp: String = env.get_string(&file_path).map(|s| s.into()).unwrap_or_default();
    let t: String = env.get_string(&text).map(|s| s.into()).unwrap_or_default();
    server.notify_selection(fp, t, start_line, end_line, start_col, end_col, is_empty != 0);
}

/// Registers the Java ToolCallback object.  From this point, tool calls
/// from Claude are dispatched via callback.executeEclipseTool(toolName, argsJson).
#[no_mangle]
pub extern "system" fn Java_com_anthropic_claudecode_eclipse_NativeCore_registerToolCallback(
    env: JNIEnv,
    _class: JClass,
    handle: jlong,
    callback: JObject,
) {
    if handle == 0 {
        return;
    }
    let server = unsafe { &*(handle as *const Server) };
    let global_ref = env.new_global_ref(callback).expect("new_global_ref failed");
    server.register_tool_callback(java_vm(), global_ref);
}

/// Registers the Java StatusCallback object.  From this point, statusLine updates
/// POSTed to /statusline are dispatched via callback.onStatusUpdate(tabToken, statusJson).
/// Dedicated channel — separate from the MCP tool callback above.
#[no_mangle]
pub extern "system" fn Java_com_anthropic_claudecode_eclipse_NativeCore_registerStatusCallback(
    env: JNIEnv,
    _class: JClass,
    handle: jlong,
    callback: JObject,
) {
    if handle == 0 {
        return;
    }
    let server = unsafe { &*(handle as *const Server) };
    let global_ref = env.new_global_ref(callback).expect("new_global_ref failed");
    server.register_status_callback(java_vm(), global_ref);
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
    let cmd: String = match env.get_string(&claude_cmd) {
        Ok(s) => s.into(),
        Err(_) => return env.new_string("").unwrap().into_raw(),
    };
    let root: String = match env.get_string(&workspace_root) {
        Ok(s) => s.into(),
        Err(_) => String::new(),
    };
    let json = chat::fetch_usage(&cmd, &root).unwrap_or_default();
    env.new_string(json).unwrap().into_raw()
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
    let auth_token: String = env.get_string(&auth_token).unwrap().into();
    let workspace_root: String = env.get_string(&workspace_root).unwrap().into();
    let project_paths_json: String = env.get_string(&project_paths_json).unwrap().into();
    lock_file::write(port as u16, &auth_token, &workspace_root, &project_paths_json);
}

#[no_mangle]
pub extern "system" fn Java_com_anthropic_claudecode_eclipse_NativeCore_lockFileRemove(
    _env: JNIEnv,
    _class: JClass,
) {
    lock_file::remove();
}

// ===========================================================================
// Chat JNI entry points
// ===========================================================================

#[no_mangle]
pub extern "system" fn Java_com_anthropic_claudecode_eclipse_NativeCore_chatCreate(
    _env: JNIEnv,
    _class: JClass,
) -> jlong {
    let manager = ChatManager::new();
    Box::into_raw(Box::new(manager)) as jlong
}

#[no_mangle]
pub extern "system" fn Java_com_anthropic_claudecode_eclipse_NativeCore_chatRegisterCallbacks(
    env: JNIEnv,
    _class: JClass,
    handle: jlong,
    callbacks: JObject,
) {
    if handle == 0 {
        return;
    }
    let manager = unsafe { &*(handle as *const ChatManager) };
    let global_ref = env.new_global_ref(callbacks).expect("new_global_ref failed");
    manager.register_callbacks(java_vm(), global_ref);
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
    images_json: JString,
) {
    if handle == 0 {
        return;
    }
    let manager = unsafe { &*(handle as *const ChatManager) };
    let message: String = env.get_string(&message).unwrap().into();
    let claude_cmd: String = env.get_string(&claude_cmd).unwrap().into();
    let workspace_root: String = env.get_string(&workspace_root).unwrap().into();
    let mcp_auth_token: String = env.get_string(&mcp_auth_token).unwrap().into();
    let resume_id: String = if resume_id.is_null() {
        String::new()
    } else {
        env.get_string(&resume_id).ok().map(|s| s.into()).unwrap_or_default()
    };
    let perm_mode: String = if perm_mode.is_null() {
        String::new()
    } else {
        env.get_string(&perm_mode).ok().map(|s| s.into()).unwrap_or_default()
    };
    let effort: String = if effort.is_null() {
        String::new()
    } else {
        env.get_string(&effort).ok().map(|s| s.into()).unwrap_or_default()
    };
    let model: String = if model.is_null() {
        String::new()
    } else {
        env.get_string(&model).ok().map(|s| s.into()).unwrap_or_default()
    };
    let thinking: String = if thinking.is_null() {
        String::new()
    } else {
        env.get_string(&thinking).ok().map(|s| s.into()).unwrap_or_default()
    };
    let images_json: String = if images_json.is_null() {
        String::new()
    } else {
        env.get_string(&images_json).ok().map(|s| s.into()).unwrap_or_default()
    };
    manager.send_message(message, claude_cmd, workspace_root, mcp_port as u16, mcp_auth_token, resume_id, perm_mode, effort, model, thinking, images_json);
}

#[no_mangle]
pub extern "system" fn Java_com_anthropic_claudecode_eclipse_NativeCore_chatSetPersistent(
    _env: JNIEnv,
    _class: JClass,
    handle: jlong,
    persistent: jboolean,
) {
    if handle == 0 {
        return;
    }
    let manager = unsafe { &*(handle as *const ChatManager) };
    manager.set_persistent(persistent != 0);
}

#[no_mangle]
pub extern "system" fn Java_com_anthropic_claudecode_eclipse_NativeCore_chatCancel(
    _env: JNIEnv,
    _class: JClass,
    handle: jlong,
) {
    if handle == 0 {
        return;
    }
    let manager = unsafe { &*(handle as *const ChatManager) };
    manager.cancel();
}

#[no_mangle]
pub extern "system" fn Java_com_anthropic_claudecode_eclipse_NativeCore_chatResetSession(
    _env: JNIEnv,
    _class: JClass,
    handle: jlong,
) {
    if handle == 0 {
        return;
    }
    let manager = unsafe { &*(handle as *const ChatManager) };
    manager.reset_session();
}

/// Drops the live conversation process WITHOUT clearing the conversation, so the
/// next send re-spawns with `--resume <id>` and rebuilds context from the
/// transcript on disk. Used after editing that transcript — the running process
/// holds its own copy of the conversation and would otherwise keep (and
/// re-serialize) a message that has just been deleted.
#[no_mangle]
pub extern "system" fn Java_com_anthropic_claudecode_eclipse_NativeCore_chatRestartProcess(
    _env: JNIEnv,
    _class: JClass,
    handle: jlong,
) {
    if handle == 0 {
        return;
    }
    let manager = unsafe { &*(handle as *const ChatManager) };
    manager.restart_process();
}

#[no_mangle]
pub extern "system" fn Java_com_anthropic_claudecode_eclipse_NativeCore_chatDestroy(
    _env: JNIEnv,
    _class: JClass,
    handle: jlong,
) {
    if handle == 0 {
        return;
    }
    let manager = unsafe { Box::from_raw(handle as *mut ChatManager) };
    drop(manager);
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
    let cmd_s: String = env.get_string(&cmd).map(|s| s.into()).unwrap_or_default();
    let args_s: String = env.get_string(&args_json).map(|s| s.into()).unwrap_or_default();
    let env_s: String = env.get_string(&extra_env_json).map(|s| s.into()).unwrap_or_default();
    let cwd_s: String = env.get_string(&cwd).map(|s| s.into()).unwrap_or_default();

    let args: Vec<String> = serde_json::from_str(&args_s).unwrap_or_default();
    let raw_env: Vec<[String; 2]> = serde_json::from_str(&env_s).unwrap_or_default();
    let extra_env: Vec<(String, String)> = raw_env.into_iter()
        .map(|p| (p[0].clone(), p[1].clone()))
        .collect();

    match console::ConsoleSession::create(&cmd_s, &args, &extra_env, &cwd_s) {
        Some(session) => Box::into_raw(Box::new(session)) as jlong,
        None => 0,
    }
}

/// Tries to find the console window and embed it in `parent_hwnd`.
/// Returns true if embedded, false if the console window hasn't appeared yet.
#[no_mangle]
pub extern "system" fn Java_com_anthropic_claudecode_eclipse_NativeCore_consoleEmbed(
    _env: JNIEnv,
    _class: JClass,
    handle: jlong,
    parent_hwnd: jlong,
    width: jint,
    height: jint,
) -> jboolean {
    if handle == 0 { return 0; }
    let session = unsafe { &mut *(handle as *mut console::ConsoleSession) };
    session.try_embed(parent_hwnd as isize, width, height) as jboolean
}

#[no_mangle]
pub extern "system" fn Java_com_anthropic_claudecode_eclipse_NativeCore_consoleResize(
    _env: JNIEnv,
    _class: JClass,
    handle: jlong,
    width: jint,
    height: jint,
) {
    if handle == 0 { return; }
    let session = unsafe { &*(handle as *const console::ConsoleSession) };
    session.resize(width, height);
}

#[no_mangle]
pub extern "system" fn Java_com_anthropic_claudecode_eclipse_NativeCore_consoleFocus(
    _env: JNIEnv,
    _class: JClass,
    handle: jlong,
) {
    if handle == 0 { return; }
    let session = unsafe { &*(handle as *const console::ConsoleSession) };
    session.set_focus();
}

/// Returns true if the console HWND currently has Win32 keyboard focus.
#[no_mangle]
pub extern "system" fn Java_com_anthropic_claudecode_eclipse_NativeCore_consoleIsFocused(
    _env: JNIEnv,
    _class: JClass,
    handle: jlong,
) -> jboolean {
    if handle == 0 { return 0; }
    let session = unsafe { &*(handle as *const console::ConsoleSession) };
    session.is_focused() as jboolean
}

/// Posts a Win32 message (WM_CHAR, WM_KEYDOWN, etc.) to the console HWND.
#[no_mangle]
pub extern "system" fn Java_com_anthropic_claudecode_eclipse_NativeCore_consolePostMessage(
    _env: JNIEnv,
    _class: JClass,
    handle: jlong,
    msg: jint,
    wparam: jlong,
    lparam: jlong,
) {
    if handle == 0 { return; }
    let session = unsafe { &*(handle as *const console::ConsoleSession) };
    session.post_message(msg as u32, wparam as usize, lparam as isize);
}

#[no_mangle]
pub extern "system" fn Java_com_anthropic_claudecode_eclipse_NativeCore_consoleSetFont(
    mut env: JNIEnv,
    _class: JClass,
    handle: jlong,
    font_name: JString,
    font_size: jint,
) {
    if handle == 0 { return; }
    let name: String = match env.get_string(&font_name) {
        Ok(s) => s.into(),
        Err(_) => return,
    };
    let session = unsafe { &*(handle as *const console::ConsoleSession) };
    session.set_font(&name, font_size as i16);
}

#[no_mangle]
pub extern "system" fn Java_com_anthropic_claudecode_eclipse_NativeCore_consoleSetColors(
    _env: JNIEnv,
    _class: JClass,
    handle: jlong,
    bg_r: jint,
    bg_g: jint,
    bg_b: jint,
    fg_r: jint,
    fg_g: jint,
    fg_b: jint,
) {
    if handle == 0 { return; }
    let session = unsafe { &*(handle as *const console::ConsoleSession) };
    session.set_colors(bg_r as u8, bg_g as u8, bg_b as u8, fg_r as u8, fg_g as u8, fg_b as u8);
}

#[no_mangle]
pub extern "system" fn Java_com_anthropic_claudecode_eclipse_NativeCore_consoleDestroy(
    _env: JNIEnv,
    _class: JClass,
    handle: jlong,
) {
    if handle == 0 { return; }
    let session = unsafe { Box::from_raw(handle as *mut console::ConsoleSession) };
    drop(session);
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
    _env: JNIEnv,
    _class: JClass,
    hwnd: jlong,
) {
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
}

// ===========================================================================
// Bridge JNI entry points
// ===========================================================================

/// Generates a fresh random handshake token for one relay session.
/// Java hands it to the relay and to its own socket, then passes it back here
/// via bridgeConnect so the Rust side authenticates too.
#[no_mangle]
pub extern "system" fn Java_com_anthropic_claudecode_eclipse_NativeCore_bridgeGenerateToken(
    env: JNIEnv,
    _class: JClass,
) -> jstring {
    let token = uuid::Uuid::new_v4().to_string();
    env.new_string(token).unwrap().into_raw()
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
    let token: String = env.get_string(&token).map(|s| s.into()).unwrap_or_default();
    let out = match bridge::relay_start(port_min as u16, port_max as u16, &token) {
        Some((a, b)) => format!("{} {}", a, b),
        None => String::new(),
    };
    env.new_string(out)
        .unwrap_or_else(|_| env.new_string("").unwrap())
        .into_raw()
}

#[no_mangle]
pub extern "system" fn Java_com_anthropic_claudecode_eclipse_NativeCore_bridgeStopRelay(
    _env: JNIEnv,
    _class: JClass,
) {
    bridge::relay_stop();
}

#[no_mangle]
pub extern "system" fn Java_com_anthropic_claudecode_eclipse_NativeCore_bridgeRelayIsRunning(
    _env: JNIEnv,
    _class: JClass,
) -> jboolean {
    bridge::relay_is_running() as jboolean
}

#[no_mangle]
pub extern "system" fn Java_com_anthropic_claudecode_eclipse_NativeCore_bridgeConnect(
    mut env: JNIEnv,
    _class: JClass,
    port: jint,
    token: JString,
) -> jboolean {
    let token: String = env.get_string(&token).map(|s| s.into()).unwrap_or_default();
    bridge::connect(port as u16, &token) as jboolean
}

#[no_mangle]
pub extern "system" fn Java_com_anthropic_claudecode_eclipse_NativeCore_bridgeDisconnect(
    _env: JNIEnv,
    _class: JClass,
) {
    bridge::disconnect();
}

#[no_mangle]
pub extern "system" fn Java_com_anthropic_claudecode_eclipse_NativeCore_bridgeIsConnected(
    _env: JNIEnv,
    _class: JClass,
) -> jboolean {
    bridge::is_connected() as jboolean
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
    let http = if http_proxy.is_null() {
        None
    } else {
        env.get_string(&http_proxy).ok()
            .map(|s| s.into())
            .filter(|s: &String| !s.is_empty())
    };
    let https = if https_proxy.is_null() {
        None
    } else {
        env.get_string(&https_proxy).ok()
            .map(|s| s.into())
            .filter(|s: &String| !s.is_empty())
    };
    let no = if no_proxy.is_null() {
        None
    } else {
        env.get_string(&no_proxy).ok()
            .map(|s| s.into())
            .filter(|s: &String| !s.is_empty())
    };
    shell_env::set_proxy_overrides(http, https, no);
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
    let root: String = if workspace_root.is_null() {
        String::new()
    } else {
        env.get_string(&workspace_root).ok().map(|s| s.into()).unwrap_or_default()
    };
    let json = session::list_sessions(&root);
    env.new_string(json).unwrap_or_else(|_| env.new_string("[]").unwrap()).into_raw()
}

#[no_mangle]
pub extern "system" fn Java_com_anthropic_claudecode_eclipse_NativeCore_sessionSearchContent(
    mut env: JNIEnv,
    _class: JClass,
    workspace_root: JString,
    session_ids_json: JString,
    query: JString,
) -> jstring {
    let root: String = if workspace_root.is_null() {
        String::new()
    } else {
        env.get_string(&workspace_root).ok().map(|s| s.into()).unwrap_or_default()
    };
    let ids_json: String = if session_ids_json.is_null() {
        "[]".to_string()
    } else {
        env.get_string(&session_ids_json).ok().map(|s| s.into()).unwrap_or_else(|| "[]".to_string())
    };
    let q: String = if query.is_null() {
        String::new()
    } else {
        env.get_string(&query).ok().map(|s| s.into()).unwrap_or_default()
    };
    let ids: Vec<String> = serde_json::from_str(&ids_json).unwrap_or_default();
    let json = session::search_session_content(&root, &ids, &q);
    env.new_string(json).unwrap_or_else(|_| env.new_string("[]").unwrap()).into_raw()
}

#[no_mangle]
pub extern "system" fn Java_com_anthropic_claudecode_eclipse_NativeCore_sessionLoad(
    mut env: JNIEnv,
    _class: JClass,
    workspace_root: JString,
    session_id: JString,
) -> jstring {
    let root: String = if workspace_root.is_null() {
        String::new()
    } else {
        env.get_string(&workspace_root).ok().map(|s| s.into()).unwrap_or_default()
    };
    let id: String = if session_id.is_null() {
        String::new()
    } else {
        env.get_string(&session_id).ok().map(|s| s.into()).unwrap_or_default()
    };
    let json = session::load_session_history(&root, &id);
    env.new_string(json).unwrap_or_else(|_| env.new_string("[]").unwrap()).into_raw()
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
    let root: String = if workspace_root.is_null() {
        String::new()
    } else {
        env.get_string(&workspace_root).ok().map(|s| s.into()).unwrap_or_default()
    };
    let id: String = if session_id.is_null() {
        String::new()
    } else {
        env.get_string(&session_id).ok().map(|s| s.into()).unwrap_or_default()
    };
    session::delete_session(&root, &id) as jboolean
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
    let get = |env: &mut JNIEnv, s: &JString| -> String {
        if s.is_null() {
            String::new()
        } else {
            env.get_string(s).ok().map(|v| v.into()).unwrap_or_default()
        }
    };
    let root = get(&mut env, &workspace_root);
    let id = get(&mut env, &session_id);
    let json = session::message_ids(&root, &id);
    env.new_string(json).unwrap_or_else(|_| env.new_string("[]").unwrap()).into_raw()
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
    let get = |env: &mut JNIEnv, s: &JString| -> String {
        if s.is_null() {
            String::new()
        } else {
            env.get_string(s).ok().map(|v| v.into()).unwrap_or_default()
        }
    };
    let root = get(&mut env, &workspace_root);
    let id = get(&mut env, &session_id);
    let mid = get(&mut env, &message_id);
    let json = session::delete_message(&root, &id, &mid);
    env.new_string(json)
        .unwrap_or_else(|_| env.new_string(r#"{"error":"internal"}"#).unwrap())
        .into_raw()
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
    let get = |env: &mut JNIEnv, s: &JString| -> String {
        if s.is_null() {
            String::new()
        } else {
            env.get_string(s).ok().map(|v| v.into()).unwrap_or_default()
        }
    };
    let cmd = get(&mut env, &claude_cmd);
    let root = get(&mut env, &workspace_root);
    let id = get(&mut env, &session_id);
    let title = get(&mut env, &title);
    session::rename_session_offline(&cmd, &root, &id, &title) as jboolean
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
    if handle == 0 {
        return 0;
    }
    let manager = unsafe { &*(handle as *const ChatManager) };
    let id: String = if session_id.is_null() {
        String::new()
    } else {
        env.get_string(&session_id).ok().map(|s| s.into()).unwrap_or_default()
    };
    let title: String = if title.is_null() {
        String::new()
    } else {
        env.get_string(&title).ok().map(|s| s.into()).unwrap_or_default()
    };
    manager.rename_session(&id, &title) as jboolean
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
    if handle == 0 {
        return 0;
    }
    let manager = unsafe { &*(handle as *const ChatManager) };
    let mode: String = if mode.is_null() {
        String::new()
    } else {
        env.get_string(&mode).ok().map(|s| s.into()).unwrap_or_default()
    };
    manager.set_permission_mode(&mode) as jboolean
}

// ===========================================================================
// Debug mode JNI entry point
// ===========================================================================

#[no_mangle]
pub extern "system" fn Java_com_anthropic_claudecode_eclipse_NativeCore_setDebugMode(
    _env: JNIEnv,
    _class: JClass,
    enabled: jboolean,
) {
    DEBUG_MODE.store(enabled != 0, Ordering::Relaxed);
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
}
