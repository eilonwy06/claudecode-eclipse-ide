//! Embeds a real Windows console window inside an SWT Composite.
//!
//! Spawns a child process with CREATE_NEW_CONSOLE, finds the console
//! window via AttachConsole/GetConsoleWindow, and reparents it into
//! the given parent HWND.  Keyboard input works natively — no WebView2,
//! no xterm.js, no focus hacks.
//!
//! Two key Win32 details that make this work:
//!   1. AttachThreadInput — links Eclipse's SWT UI thread with conhost.exe's
//!      UI thread so cross-process SetFocus actually works.
//!   2. Job Object with KILL_ON_JOB_CLOSE — kills the entire process tree
//!      (cmd.exe → node.exe → …) when the session is closed, so SSE
//!      connections are properly cleaned up.

use std::sync::Mutex;

// ---------------------------------------------------------------------------
// Win32 FFI
// ---------------------------------------------------------------------------

#[cfg(windows)]
mod win32 {
    use std::ffi::OsStr;
    use std::os::windows::ffi::OsStrExt;

    #[repr(C)]
    pub struct STARTUPINFOW {
        pub cb: u32,
        pub reserved: *mut u16,
        pub desktop: *mut u16,
        pub title: *mut u16,
        pub x: u32,
        pub y: u32,
        pub x_size: u32,
        pub y_size: u32,
        pub x_count_chars: u32,
        pub y_count_chars: u32,
        pub fill_attribute: u32,
        pub flags: u32,
        pub show_window: u16,
        pub cb_reserved2: u16,
        pub reserved2: *mut u8,
        pub std_input: isize,
        pub std_output: isize,
        pub std_error: isize,
    }

    #[repr(C)]
    pub struct PROCESS_INFORMATION {
        pub process: isize,
        pub thread: isize,
        pub process_id: u32,
        pub thread_id: u32,
    }

    // Job Object structures
    #[repr(C)]
    pub struct JOBOBJECT_EXTENDED_LIMIT_INFORMATION {
        pub basic: JOBOBJECT_BASIC_LIMIT_INFORMATION,
        pub io_info: IO_COUNTERS,
        pub process_memory_limit: usize,
        pub job_memory_limit: usize,
        pub peak_process_memory_used: usize,
        pub peak_job_memory_used: usize,
    }

    #[repr(C)]
    pub struct JOBOBJECT_BASIC_LIMIT_INFORMATION {
        pub per_process_user_time_limit: i64,
        pub per_job_user_time_limit: i64,
        pub limit_flags: u32,
        pub minimum_working_set_size: usize,
        pub maximum_working_set_size: usize,
        pub active_process_limit: u32,
        pub affinity: usize,
        pub priority_class: u32,
        pub scheduling_class: u32,
    }

    #[repr(C)]
    pub struct IO_COUNTERS {
        pub read_operations: u64,
        pub write_operations: u64,
        pub other_operations: u64,
        pub read_transfer: u64,
        pub write_transfer: u64,
        pub other_transfer: u64,
    }

    pub const CREATE_NEW_CONSOLE: u32 = 0x00000010;
    pub const CREATE_UNICODE_ENVIRONMENT: u32 = 0x00000400;
    pub const CREATE_SUSPENDED: u32 = 0x00000004;
    pub const STARTF_USESHOWWINDOW: u32 = 0x00000001;
    pub const SW_HIDE: u16 = 0;
    pub const SW_SHOW: i32 = 5;
    pub const GWL_STYLE: i32 = -16;
    pub const WS_CHILD: u32 = 0x40000000;
    pub const WS_CAPTION: u32 = 0x00C00000;
    pub const WS_THICKFRAME: u32 = 0x00040000;
    pub const WS_POPUP: u32 = 0x80000000;
    pub const SWP_FRAMECHANGED: u32 = 0x0020;
    pub const SWP_NOZORDER: u32 = 0x0004;
    pub const SWP_NOMOVE: u32 = 0x0002;
    pub const SWP_NOSIZE: u32 = 0x0001;
    pub const HWND_TOP: isize = 0;
    pub const ATTACH_PARENT_PROCESS: u32 = 0xFFFFFFFF;

    // Job Object constants
    pub const JOB_OBJECT_LIMIT_KILL_ON_JOB_CLOSE: u32 = 0x00002000;
    pub const JOB_OBJECT_EXTENDED_LIMIT_INFORMATION_CLASS: u32 = 9;

    pub const WM_LBUTTONDOWN: u32 = 0x0201;
    pub const WM_LBUTTONUP: u32 = 0x0202;
    pub const MK_LBUTTON: usize = 0x0001;

    // Console font constants
    pub const LF_FACESIZE: usize = 32;
    pub const GENERIC_READ: u32 = 0x80000000;
    pub const GENERIC_WRITE: u32 = 0x40000000;
    pub const FILE_SHARE_WRITE: u32 = 0x00000002;
    pub const OPEN_EXISTING: u32 = 3;
    pub const INVALID_HANDLE_VALUE: isize = -1;

    #[repr(C)]
    pub struct COORD {
        pub x: i16,
        pub y: i16,
    }

    #[repr(C)]
    pub struct CONSOLE_FONT_INFOEX {
        pub cb_size: u32,
        pub font_index: u32,
        pub font_size: COORD,
        pub font_family: u32,
        pub font_weight: u32,
        pub face_name: [u16; LF_FACESIZE],
    }

    #[repr(C)]
    pub struct SMALL_RECT {
        pub left: i16,
        pub top: i16,
        pub right: i16,
        pub bottom: i16,
    }

    #[repr(C)]
    pub struct CONSOLE_SCREEN_BUFFER_INFOEX {
        pub cb_size: u32,
        pub size: COORD,
        pub cursor_position: COORD,
        pub attributes: u16,
        pub window: SMALL_RECT,
        pub maximum_window_size: COORD,
        pub popup_attributes: u16,
        pub fullscreen_supported: i32,
        pub color_table: [u32; 16],
    }

    #[link(name = "kernel32")]
    extern "system" {
        pub fn CreateProcessW(
            app: *const u16, cmd: *mut u16,
            pa: *mut u8, ta: *mut u8,
            inherit: i32, flags: u32,
            env: *const u16, cwd: *const u16,
            si: *const STARTUPINFOW, pi: *mut PROCESS_INFORMATION,
        ) -> i32;
        pub fn ResumeThread(h: isize) -> u32;
        pub fn TerminateProcess(h: isize, code: u32) -> i32;
        pub fn CloseHandle(h: isize) -> i32;
        pub fn FreeConsole() -> i32;
        pub fn AttachConsole(pid: u32) -> i32;
        pub fn GetConsoleWindow() -> isize;
        pub fn GetCurrentThreadId() -> u32;
        pub fn CreateFileW(
            file_name: *const u16,
            desired_access: u32,
            share_mode: u32,
            security_attrs: *mut u8,
            creation_disposition: u32,
            flags_and_attrs: u32,
            template_file: isize,
        ) -> isize;
        pub fn SetCurrentConsoleFontEx(
            console_output: isize,
            maximum_window: i32,
            console_font_info: *mut CONSOLE_FONT_INFOEX,
        ) -> i32;
        pub fn GetConsoleScreenBufferInfoEx(
            console_output: isize,
            console_screen_buffer_info: *mut CONSOLE_SCREEN_BUFFER_INFOEX,
        ) -> i32;
        pub fn SetConsoleScreenBufferInfoEx(
            console_output: isize,
            console_screen_buffer_info: *const CONSOLE_SCREEN_BUFFER_INFOEX,
        ) -> i32;

        // Job Object
        pub fn CreateJobObjectW(sa: *mut u8, name: *const u16) -> isize;
        pub fn SetInformationJobObject(
            job: isize, class: u32,
            info: *const u8, len: u32,
        ) -> i32;
        pub fn AssignProcessToJobObject(job: isize, process: isize) -> i32;
    }

    #[repr(C)]
    pub struct GUITHREADINFO {
        pub cb_size: u32,
        pub flags: u32,
        pub hwnd_active: isize,
        pub hwnd_focus: isize,
        pub hwnd_capture: isize,
        pub hwnd_menu_owner: isize,
        pub hwnd_move_size: isize,
        pub hwnd_caret: isize,
        pub rc_caret_left: i32,
        pub rc_caret_top: i32,
        pub rc_caret_right: i32,
        pub rc_caret_bottom: i32,
    }

    #[link(name = "user32")]
    extern "system" {
        pub fn SetParent(child: isize, parent: isize) -> isize;
        pub fn GetWindowLongW(h: isize, idx: i32) -> i32;
        pub fn SetWindowLongW(h: isize, idx: i32, val: i32) -> i32;
        pub fn SetWindowPos(h: isize, after: isize, x: i32, y: i32, w: i32, h2: i32, flags: u32) -> i32;
        pub fn MoveWindow(h: isize, x: i32, y: i32, w: i32, h2: i32, repaint: i32) -> i32;
        pub fn ShowWindow(h: isize, cmd: i32) -> i32;
        pub fn SetFocus(h: isize) -> isize;
        pub fn SetForegroundWindow(h: isize) -> i32;
        pub fn IsWindow(h: isize) -> i32;
        pub fn GetWindowThreadProcessId(h: isize, pid: *mut u32) -> u32;
        pub fn AttachThreadInput(attach: u32, to: u32, yes: i32) -> i32;
        pub fn PostMessageW(h: isize, msg: u32, wparam: usize, lparam: isize) -> i32;
        pub fn GetGUIThreadInfo(thread_id: u32, info: *mut GUITHREADINFO) -> i32;
    }

    pub fn to_wide(s: &str) -> Vec<u16> {
        OsStr::new(s).encode_wide().chain(std::iter::once(0)).collect()
    }

    pub fn build_command_line(cmd: &str, args: &[String]) -> Vec<u16> {
        let mut s = String::new();
        if cmd.contains(' ') {
            s.push('"'); s.push_str(cmd); s.push('"');
        } else {
            s.push_str(cmd);
        }
        for arg in args {
            s.push(' ');
            if arg.contains(' ') || arg.contains('"') {
                s.push('"');
                s.push_str(&arg.replace('"', "\\\""));
                s.push('"');
            } else {
                s.push_str(arg);
            }
        }
        to_wide(&s)
    }

    pub fn build_env_block(extra: &[(String, String)]) -> Vec<u16> {
        let mut env: std::collections::HashMap<String, String> = std::env::vars().collect();
        for (k, v) in extra {
            env.insert(k.clone(), v.clone());
        }
        let mut block: Vec<u16> = Vec::new();
        for (k, v) in &env {
            let entry = format!("{}={}", k, v);
            block.extend(OsStr::new(&entry).encode_wide());
            block.push(0);
        }
        block.push(0);
        block
    }
}

// ---------------------------------------------------------------------------
// ConsoleSession
// ---------------------------------------------------------------------------

#[cfg(windows)]
static CONSOLE_LOCK: Mutex<()> = Mutex::new(());

pub struct ConsoleSession {
    #[cfg(windows)]
    process_handle: isize,
    #[cfg(windows)]
    thread_handle: isize,
    #[cfg(windows)]
    job_handle: isize,
    #[cfg(windows)]
    child_pid: u32,
    #[cfg(windows)]
    console_hwnd: isize,
    /// conhost.exe's UI thread ID — needed for AttachThreadInput.
    #[cfg(windows)]
    console_thread_id: u32,
    /// Eclipse SWT UI thread ID that called try_embed.
    #[cfg(windows)]
    swt_thread_id: u32,
    /// Whether we've attached the thread input queues.
    #[cfg(windows)]
    threads_attached: bool,
}

impl ConsoleSession {
    /// Spawns a child process with its own console window (hidden).
    /// The process is assigned to a Job Object so the entire tree is
    /// killed when the session is destroyed.
    #[cfg(windows)]
    pub fn create(
        cmd: &str,
        args: &[String],
        extra_env: &[(String, String)],
        cwd: &str,
    ) -> Option<Self> {
        use win32::*;
        unsafe {
            let job = CreateJobObjectW(std::ptr::null_mut(), std::ptr::null());
            if job == 0 { return None; }

            let mut info: JOBOBJECT_EXTENDED_LIMIT_INFORMATION = std::mem::zeroed();
            info.basic.limit_flags = JOB_OBJECT_LIMIT_KILL_ON_JOB_CLOSE;
            SetInformationJobObject(
                job,
                JOB_OBJECT_EXTENDED_LIMIT_INFORMATION_CLASS,
                &info as *const _ as *const u8,
                std::mem::size_of::<JOBOBJECT_EXTENDED_LIMIT_INFORMATION>() as u32,
            );

            let mut cmdline = build_command_line(cmd, args);
            let cwd_w = to_wide(cwd);
            let env_block = build_env_block(extra_env);

            let mut si: STARTUPINFOW = std::mem::zeroed();
            si.cb = std::mem::size_of::<STARTUPINFOW>() as u32;
            si.flags = STARTF_USESHOWWINDOW;
            si.show_window = SW_HIDE;

            let mut pi: PROCESS_INFORMATION = std::mem::zeroed();

            if CreateProcessW(
                std::ptr::null(), cmdline.as_mut_ptr(),
                std::ptr::null_mut(), std::ptr::null_mut(),
                0, CREATE_NEW_CONSOLE | CREATE_UNICODE_ENVIRONMENT | CREATE_SUSPENDED,
                env_block.as_ptr(), cwd_w.as_ptr(),
                &si, &mut pi,
            ) == 0 {
                CloseHandle(job);
                return None;
            }

            AssignProcessToJobObject(job, pi.process);
            ResumeThread(pi.thread);

            Some(ConsoleSession {
                process_handle: pi.process,
                thread_handle: pi.thread,
                job_handle: job,
                child_pid: pi.process_id,
                console_hwnd: 0,
                console_thread_id: 0,
                swt_thread_id: 0,
                threads_attached: false,
            })
        }
    }

    #[cfg(windows)]
    pub fn try_embed(&mut self, parent_hwnd: isize, width: i32, height: i32) -> bool {
        use win32::*;
        if self.console_hwnd != 0 { return true; }

        let _lock = CONSOLE_LOCK.lock().unwrap();
        unsafe {
            let original = GetConsoleWindow();
            FreeConsole();

            let mut found = 0isize;
            if AttachConsole(self.child_pid) != 0 {
                found = GetConsoleWindow();
                FreeConsole();
            }

            if original != 0 {
                AttachConsole(ATTACH_PARENT_PROCESS);
            }

            if found == 0 { return false; }

            SetParent(found, parent_hwnd);

            let style = GetWindowLongW(found, GWL_STYLE) as u32;
            let new_style = (style & !(WS_CAPTION | WS_THICKFRAME | WS_POPUP)) | WS_CHILD;
            SetWindowLongW(found, GWL_STYLE, new_style as i32);
            SetWindowPos(found, 0, 0, 0, 0, 0, SWP_FRAMECHANGED | SWP_NOZORDER);
            MoveWindow(found, 0, 0, width, height, 1);
            ShowWindow(found, SW_SHOW);

            self.console_hwnd = found;

            let console_tid = GetWindowThreadProcessId(found, std::ptr::null_mut());
            let swt_tid = GetCurrentThreadId();
            if console_tid != 0 && console_tid != swt_tid {
                AttachThreadInput(swt_tid, console_tid, 1);
                self.console_thread_id = console_tid;
                self.swt_thread_id = swt_tid;
                self.threads_attached = true;
            }

            SetFocus(found);

            true
        }
    }

    #[cfg(windows)]
    pub fn resize(&self, width: i32, height: i32) {
        if self.console_hwnd == 0 { return; }
        unsafe {
            if win32::IsWindow(self.console_hwnd) != 0 {
                win32::MoveWindow(self.console_hwnd, 0, 0, width, height, 1);
            }
        }
    }

    #[cfg(windows)]
    pub fn set_focus(&self) {
        if self.console_hwnd == 0 { return; }
        unsafe {
            if win32::IsWindow(self.console_hwnd) == 0 { return; }

            // Re-attach thread input queues every time.
            if self.console_thread_id != 0 && self.swt_thread_id != 0 {
                win32::AttachThreadInput(self.swt_thread_id, self.console_thread_id, 0);
                win32::AttachThreadInput(self.swt_thread_id, self.console_thread_id, 1);
            }

            win32::SetWindowPos(
                self.console_hwnd, win32::HWND_TOP,
                0, 0, 0, 0,
                win32::SWP_NOMOVE | win32::SWP_NOSIZE,
            );
            win32::SetForegroundWindow(self.console_hwnd);
            win32::SetFocus(self.console_hwnd);

            let lparam: isize = 5 | (5 << 16);
            win32::PostMessageW(
                self.console_hwnd,
                win32::WM_LBUTTONDOWN,
                win32::MK_LBUTTON,
                lparam,
            );
            win32::PostMessageW(
                self.console_hwnd,
                win32::WM_LBUTTONUP,
                0,
                lparam,
            );
        }
    }

    /// Returns true if the console HWND currently has Win32 keyboard focus
    /// in its thread.  Used by Java to detect when the user clicked on the
    /// console so Eclipse can programmatically activate the view.
    #[cfg(windows)]
    pub fn is_focused(&self) -> bool {
        if self.console_hwnd == 0 || self.console_thread_id == 0 { return false; }
        unsafe {
            let mut info: win32::GUITHREADINFO = std::mem::zeroed();
            info.cb_size = std::mem::size_of::<win32::GUITHREADINFO>() as u32;
            if win32::GetGUIThreadInfo(self.console_thread_id, &mut info) != 0 {
                info.hwnd_focus == self.console_hwnd
            } else {
                false
            }
        }
    }

    /// Posts a Win32 message to the console HWND.  Used by Java to forward
    /// keyboard events (WM_CHAR, WM_KEYDOWN, WM_KEYUP, etc.) when the
    /// console doesn't have real Win32 keyboard focus.
    #[cfg(windows)]
    pub fn post_message(&self, msg: u32, wparam: usize, lparam: isize) {
        if self.console_hwnd == 0 { return; }
        unsafe {
            if win32::IsWindow(self.console_hwnd) != 0 {
                win32::PostMessageW(self.console_hwnd, msg, wparam, lparam);
            }
        }
    }

    /// Sets the console font. Temporarily attaches to the child's console
    /// to call SetCurrentConsoleFontEx.
    #[cfg(windows)]
    pub fn set_font(&self, font_name: &str, font_size: i16) {
        let debug = crate::is_debug();
        if debug {
            eprintln!("[DEBUG] set_font called: name='{}', size={}, child_pid={}",
                      font_name, font_size, self.child_pid);
        }
        if self.child_pid == 0 {
            if debug { eprintln!("[DEBUG] child_pid is 0, returning early"); }
            return;
        }
        let _lock = CONSOLE_LOCK.lock().unwrap();
        unsafe {
            let original = win32::GetConsoleWindow();
            if debug { eprintln!("[DEBUG] original console window: {}", original); }
            win32::FreeConsole();

            let attach_result = win32::AttachConsole(self.child_pid);
            if debug {
                eprintln!("[DEBUG] AttachConsole({}) returned {}", self.child_pid, attach_result);
            }

            if attach_result != 0 {
                // Open CONOUT$ to get the attached console's screen buffer handle.
                let conout = win32::to_wide("CONOUT$");
                let stdout = win32::CreateFileW(
                    conout.as_ptr(),
                    win32::GENERIC_READ | win32::GENERIC_WRITE,
                    win32::FILE_SHARE_WRITE,
                    std::ptr::null_mut(),
                    win32::OPEN_EXISTING,
                    0,
                    0,
                );
                if debug { eprintln!("[DEBUG] CreateFileW(CONOUT$) returned {}", stdout); }

                if stdout != win32::INVALID_HANDLE_VALUE {
                    let mut font_info: win32::CONSOLE_FONT_INFOEX = std::mem::zeroed();
                    font_info.cb_size = std::mem::size_of::<win32::CONSOLE_FONT_INFOEX>() as u32;
                    font_info.font_size.y = font_size;
                    font_info.font_weight = 400; // FW_NORMAL

                    let wide_name = win32::to_wide(font_name);
                    let copy_len = wide_name.len().min(win32::LF_FACESIZE - 1);
                    font_info.face_name[..copy_len].copy_from_slice(&wide_name[..copy_len]);

                    let set_result = win32::SetCurrentConsoleFontEx(stdout, 0, &mut font_info);
                    if debug {
                        eprintln!("[DEBUG] SetCurrentConsoleFontEx returned {}", set_result);
                    }

                    if set_result == 0 && debug {
                        #[link(name = "kernel32")]
                        extern "system" {
                            fn GetLastError() -> u32;
                        }
                        eprintln!("[DEBUG] GetLastError: {}", GetLastError());
                    }
                    win32::CloseHandle(stdout);
                }
                win32::FreeConsole();
            }

            if original != 0 {
                win32::AttachConsole(win32::ATTACH_PARENT_PROCESS);
            }
        }
    }

    /// Sets the console background and foreground colors.
    /// Colors are specified as RGB values (0-255 each).
    #[cfg(windows)]
    pub fn set_colors(&self, bg_r: u8, bg_g: u8, bg_b: u8, fg_r: u8, fg_g: u8, fg_b: u8) {
        let debug = crate::is_debug();
        if debug {
            eprintln!("[DEBUG] set_colors called: bg=({},{},{}), fg=({},{},{})",
                      bg_r, bg_g, bg_b, fg_r, fg_g, fg_b);
        }
        if self.child_pid == 0 {
            if debug { eprintln!("[DEBUG] child_pid is 0, returning early"); }
            return;
        }
        let _lock = CONSOLE_LOCK.lock().unwrap();
        unsafe {
            let original = win32::GetConsoleWindow();
            win32::FreeConsole();

            let attach_result = win32::AttachConsole(self.child_pid);
            if debug {
                eprintln!("[DEBUG] AttachConsole({}) returned {}", self.child_pid, attach_result);
            }

            if attach_result != 0 {
                let conout = win32::to_wide("CONOUT$");
                let stdout = win32::CreateFileW(
                    conout.as_ptr(),
                    win32::GENERIC_READ | win32::GENERIC_WRITE,
                    win32::FILE_SHARE_WRITE,
                    std::ptr::null_mut(),
                    win32::OPEN_EXISTING,
                    0,
                    0,
                );

                if stdout != win32::INVALID_HANDLE_VALUE {
                    let mut info: win32::CONSOLE_SCREEN_BUFFER_INFOEX = std::mem::zeroed();
                    info.cb_size = std::mem::size_of::<win32::CONSOLE_SCREEN_BUFFER_INFOEX>() as u32;

                    if win32::GetConsoleScreenBufferInfoEx(stdout, &mut info) != 0 {
                        // Windows COLORREF is 0x00BBGGRR (BGR format)
                        let bg_color: u32 = (bg_b as u32) << 16 | (bg_g as u32) << 8 | (bg_r as u32);
                        let fg_color: u32 = (fg_b as u32) << 16 | (fg_g as u32) << 8 | (fg_r as u32);

                        // Color table indices: 0 = background, 7 = default foreground (light gray)
                        // Also set index 15 (bright white) for programs that use it
                        info.color_table[0] = bg_color;
                        info.color_table[7] = fg_color;
                        info.color_table[15] = fg_color;

                        // Fix: SetConsoleScreenBufferInfoEx shrinks window by 1 in each dimension
                        // Work around by expanding it before the call
                        info.window.right += 1;
                        info.window.bottom += 1;

                        let set_result = win32::SetConsoleScreenBufferInfoEx(stdout, &info);
                        if debug {
                            eprintln!("[DEBUG] SetConsoleScreenBufferInfoEx returned {}", set_result);
                        }
                    } else if debug {
                        eprintln!("[DEBUG] GetConsoleScreenBufferInfoEx failed");
                    }
                    win32::CloseHandle(stdout);
                }
                win32::FreeConsole();
            }

            if original != 0 {
                win32::AttachConsole(win32::ATTACH_PARENT_PROCESS);
            }
        }
    }

    #[cfg(windows)]
    pub fn kill(&mut self) {
        unsafe {
            if self.threads_attached {
                win32::AttachThreadInput(
                    self.swt_thread_id, self.console_thread_id, 0,
                );
                self.threads_attached = false;
            }

            if self.job_handle != 0 {
                win32::CloseHandle(self.job_handle);
                self.job_handle = 0;
            }

            if self.process_handle != 0 {
                win32::TerminateProcess(self.process_handle, 1);
                win32::CloseHandle(self.process_handle);
                self.process_handle = 0;
            }
            if self.thread_handle != 0 {
                win32::CloseHandle(self.thread_handle);
                self.thread_handle = 0;
            }
        }
    }

    // ── Non-Windows stubs ────────────────────────────────────────────────

    #[cfg(not(windows))]
    pub fn create(_: &str, _: &[String], _: &[(String, String)], _: &str) -> Option<Self> { None }
    #[cfg(not(windows))]
    pub fn try_embed(&mut self, _: isize, _: i32, _: i32) -> bool { false }
    #[cfg(not(windows))]
    pub fn resize(&self, _: i32, _: i32) {}
    #[cfg(not(windows))]
    pub fn set_focus(&self) {}
    #[cfg(not(windows))]
    pub fn is_focused(&self) -> bool { false }
    #[cfg(not(windows))]
    pub fn post_message(&self, _msg: u32, _wparam: usize, _lparam: isize) {}
    #[cfg(not(windows))]
    pub fn set_font(&self, _font_name: &str, _font_size: i16) {}
    #[cfg(not(windows))]
    pub fn set_colors(&self, _bg_r: u8, _bg_g: u8, _bg_b: u8, _fg_r: u8, _fg_g: u8, _fg_b: u8) {}
    #[cfg(not(windows))]
    pub fn kill(&mut self) {}
}

impl Drop for ConsoleSession {
    fn drop(&mut self) {
        self.kill();
    }
}
