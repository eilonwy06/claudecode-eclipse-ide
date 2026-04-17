use std::io::{Read, Write};
use std::sync::atomic::{AtomicBool, AtomicU32, Ordering};
use std::sync::{Arc, Mutex};

use jni::objects::{JObject, JValue};
use portable_pty::{native_pty_system, ChildKiller, MasterPty, PtySize};

use crate::vterm::VirtualTerminal;

// ---------------------------------------------------------------------------
// Callbacks
// ---------------------------------------------------------------------------

struct CallbacksRef {
    java_vm: Arc<jni::JavaVM>,
    obj:     Arc<jni::objects::GlobalRef>,
}

// ---------------------------------------------------------------------------
// Public PtySession
// ---------------------------------------------------------------------------

/// Packs (cols, rows) into a single u32: high 16 bits = cols, low 16 bits = rows.
fn pack_size(cols: u16, rows: u16) -> u32 {
    ((cols as u32) << 16) | (rows as u32)
}
fn unpack_size(v: u32) -> (u16, u16) {
    ((v >> 16) as u16, (v & 0xFFFF) as u16)
}

pub struct PtySession {
    master:    Mutex<Option<Box<dyn MasterPty + Send>>>,
    writer:    Mutex<Option<Box<dyn Write + Send>>>,
    killer:    Mutex<Option<Box<dyn ChildKiller + Send + Sync>>>,
    callbacks: Arc<Mutex<Option<CallbacksRef>>>,
    cancelled: Arc<AtomicBool>,
    /// Pending resize from the JNI thread — reader thread picks it up.
    pending_size: Arc<AtomicU32>,
}

impl PtySession {
    pub fn new() -> Self {
        PtySession {
            master:       Mutex::new(None),
            writer:       Mutex::new(None),
            killer:       Mutex::new(None),
            callbacks:    Arc::new(Mutex::new(None)),
            cancelled:    Arc::new(AtomicBool::new(false)),
            pending_size: Arc::new(AtomicU32::new(0)),
        }
    }

    pub fn register_callbacks(&self, vm: Arc<jni::JavaVM>, obj: jni::objects::GlobalRef) {
        *self.callbacks.lock().unwrap() = Some(CallbacksRef {
            java_vm: vm,
            obj:     Arc::new(obj),
        });
    }

    pub fn start(
        &self,
        cmd:       String,
        args:      Vec<String>,
        extra_env: Vec<(String, String)>,
        cwd:       String,
        cols:      u16,
        rows:      u16,
    ) {
        let pty_system = native_pty_system();

        let pair = match pty_system.openpty(PtySize {
            cols, rows, pixel_width: 0, pixel_height: 0,
        }) {
            Ok(p)  => p,
            Err(e) => { self.write_error(&format!("openpty failed: {}", e)); return; }
        };

        let mut builder = portable_pty::CommandBuilder::new(&cmd);
        for arg in &args      { builder.arg(arg); }
        builder.cwd(&cwd);
        for (k, v) in extra_env { builder.env(k, v); }

        // macOS: inherit the login shell's PATH so bare commands like `claude`
        // resolve when Eclipse was launched from Finder.  See shell_env.rs.
        if let Some(p) = crate::shell_env::login_shell_path() {
            builder.env("PATH", p);
        }

        let child = match pair.slave.spawn_command(builder) {
            Ok(c)  => c,
            Err(e) => { self.write_error(&format!("spawn failed: {}", e)); return; }
        };
        drop(pair.slave);

        let killer = child.clone_killer();
        drop(child);

        let writer = match pair.master.take_writer() {
            Ok(w)  => w,
            Err(e) => { self.write_error(&format!("take_writer failed: {}", e)); return; }
        };

        let reader = match pair.master.try_clone_reader() {
            Ok(r)  => r,
            Err(e) => { self.write_error(&format!("try_clone_reader failed: {}", e)); return; }
        };

        *self.master.lock().unwrap() = Some(pair.master);
        *self.writer.lock().unwrap() = Some(writer);
        *self.killer.lock().unwrap() = Some(killer);

        // Store initial size so the reader thread can create the VirtualTerminal.
        self.pending_size.store(pack_size(cols, rows), Ordering::Relaxed);

        let callbacks = Arc::clone(&self.callbacks);
        let cancelled = Arc::clone(&self.cancelled);
        let pending_size = Arc::clone(&self.pending_size);

        std::thread::Builder::new()
            .name("claude-pty-reader".into())
            .spawn(move || {
                let mut buf = [0u8; 4096];
                let mut reader = reader;

                // Create the virtual terminal with the initial size and
                // clear pending_size so the first loop iteration doesn't
                // redundantly resize (which could disrupt early output).
                let init = unpack_size(pending_size.swap(0, Ordering::Relaxed));
                let mut vterm = VirtualTerminal::new(init.1, init.0);

                loop {
                    if cancelled.load(Ordering::Relaxed) { break; }

                    // Check for pending resize.
                    let sz = pending_size.swap(0, Ordering::Relaxed);
                    if sz != 0 {
                        let (cols, rows) = unpack_size(sz);
                        vterm.resize(rows, cols);
                    }

                    match reader.read(&mut buf) {
                        Ok(0) | Err(_) => break,
                        Ok(n) => {
                            vterm.process(&buf[..n]);

                            if let Some(json) = vterm.screen_to_json() {
                                let guard = callbacks.lock().unwrap();
                                if let Some(cb) = guard.as_ref() {
                                    fire_string(&cb.java_vm, &cb.obj, "onScreenUpdate", &json);
                                }
                            }
                        }
                    }
                }
                if !cancelled.load(Ordering::Relaxed) {
                    let guard = callbacks.lock().unwrap();
                    if let Some(cb) = guard.as_ref() {
                        fire_void(&cb.java_vm, &cb.obj, "onExit");
                    }
                }
            })
            .expect("Failed to spawn PTY reader thread");
    }

    pub fn write_input(&self, data: &[u8]) {
        if let Some(w) = self.writer.lock().unwrap().as_mut() {
            let _ = w.write_all(data);
            let _ = w.flush();
        }
    }

    pub fn resize(&self, cols: u16, rows: u16) {
        // Tell ConPTY about the new size.
        if let Some(m) = self.master.lock().unwrap().as_ref() {
            let _ = m.resize(PtySize { cols, rows, pixel_width: 0, pixel_height: 0 });
        }
        // Tell the vterm in the reader thread about the new size.
        self.pending_size.store(pack_size(cols, rows), Ordering::Relaxed);
    }

    pub fn kill(&self) {
        self.cancelled.store(true, Ordering::Relaxed);
        if let Some(k) = self.killer.lock().unwrap().as_mut() {
            let _ = k.kill();
        }
        *self.master.lock().unwrap() = None;
    }

    fn write_error(&self, msg: &str) {
        let text = format!("\r\nError: {}\r\n", msg);
        let guard = self.callbacks.lock().unwrap();
        if let Some(cb) = guard.as_ref() {
            // Send error as a screen update with the error text.
            let json = format!(
                "{{\"rows\":1,\"cols\":80,\"cy\":0,\"cx\":0,\"cv\":true,\"lines\":[{{\"t\":\"{}\",\"s\":[]}}]}}",
                text.replace('"', "\\\"").replace('\r', "").replace('\n', " ")
            );
            fire_string(&cb.java_vm, &cb.obj, "onScreenUpdate", &json);
            fire_void(&cb.java_vm, &cb.obj, "onExit");
        }
    }
}

impl Drop for PtySession {
    fn drop(&mut self) {
        self.kill();
    }
}

// ---------------------------------------------------------------------------
// JNI helpers
// ---------------------------------------------------------------------------

fn fire_void(
    java_vm: &Arc<jni::JavaVM>,
    obj:     &Arc<jni::objects::GlobalRef>,
    method:  &str,
) {
    let mut env = match java_vm.attach_current_thread() {
        Ok(e)  => e,
        Err(_) => return,
    };
    let _ = env.call_method(obj.as_ref(), method, "()V", &[]);
}

fn fire_string(
    java_vm: &Arc<jni::JavaVM>,
    obj:     &Arc<jni::objects::GlobalRef>,
    method:  &str,
    value:   &str,
) {
    let mut env = match java_vm.attach_current_thread() {
        Ok(e)  => e,
        Err(_) => return,
    };
    let jstr = match env.new_string(value) {
        Ok(s)  => s,
        Err(_) => return,
    };
    let jobj = JObject::from(jstr);
    let _ = env.call_method(
        obj.as_ref(),
        method,
        "(Ljava/lang/String;)V",
        &[JValue::Object(&jobj)],
    );
}
