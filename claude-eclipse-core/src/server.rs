use std::collections::HashMap;
use std::convert::Infallible;
use std::pin::Pin;
use std::sync::{Arc, Mutex};
use std::sync::atomic::{AtomicBool, AtomicU16, Ordering};
use std::task::{Context, Poll};
use std::time::Duration;

use axum::{
    Router,
    body::Bytes,
    extract::{Query, State},
    http::StatusCode,
    response::{
        IntoResponse, Response,
        sse::{Event, KeepAlive, Sse},
    },
    routing::{get, post},
};
use futures_util::{Stream, StreamExt};
use jni::objects::GlobalRef;
use serde::Deserialize;
use tokio::runtime::Runtime;
use tokio::sync::mpsc;
use tokio_stream::wrappers::UnboundedReceiverStream;
use uuid::Uuid;

// ---------------------------------------------------------------------------
// Public types
// ---------------------------------------------------------------------------

/// A single SSE event (event-type + data line).
#[derive(Clone)]
pub struct SseEvent {
    pub event_type: String,
    pub data: String,
}

/// Tool callback stored once per server after Java calls registerToolCallback.
pub struct ToolCallbackRef {
    pub java_vm: Arc<jni::JavaVM>,
    /// Arc so cloning in mcp.rs never needs JVM thread-attachment (no NewGlobalRef/DeleteGlobalRef
    /// on tokio async threads); only the original GlobalRef creation and its final drop touch JNI.
    pub callback: Arc<GlobalRef>,
}

// ---------------------------------------------------------------------------
// Shared state (Arc'd into every Axum handler)
// ---------------------------------------------------------------------------

pub struct AppState {
    /// Keyed by sessionId → unbounded sender for that SSE stream.
    pub clients: Mutex<HashMap<String, mpsc::UnboundedSender<SseEvent>>>,
    pub auth_token: String,
    pub tool_callback: Mutex<Option<ToolCallbackRef>>,
}

// ---------------------------------------------------------------------------
// Selection data (passed from Java on every selection change)
// ---------------------------------------------------------------------------

#[derive(Clone)]
struct SelectionArgs {
    file_path: String,
    text: String,
    start_line: i32,
    end_line: i32,
    is_empty: bool,
}

// ---------------------------------------------------------------------------
// Server  (one instance per plugin lifecycle)
// ---------------------------------------------------------------------------

pub struct Server {
    // NOTE: Field declaration order = drop order.
    // `runtime` must drop before `state` so that all tokio tasks (which hold
    // Arc<AppState> clones) complete and release their references before the
    // Server's own Arc<AppState> clone is decremented.  Runtime::drop() blocks
    // until all tasks finish, guaranteeing AppState outlives every task.
    runtime: Runtime,
    pub state: Arc<AppState>,
    port_min: u16,
    port_max: u16,
    shutdown_tx: Mutex<Option<tokio::sync::oneshot::Sender<()>>>,
    port: AtomicU16,
    running: AtomicBool,
    /// Pending debounce task for selection-changed notifications (50 ms).
    /// Aborted in stop() before runtime shuts down.
    selection_debounce: Mutex<Option<tokio::task::JoinHandle<()>>>,
}

impl Server {
    pub fn new(port_min: u16, port_max: u16) -> Self {
        let auth_token = Uuid::new_v4().to_string();
        let state = Arc::new(AppState {
            clients: Mutex::new(HashMap::new()),
            auth_token,
            tool_callback: Mutex::new(None),
        });
        let runtime = tokio::runtime::Builder::new_multi_thread()
            .enable_all()
            .thread_name("claude-eclipse")
            .build()
            .expect("Failed to build tokio runtime");

        Server {
            runtime,
            state,
            port_min,
            port_max,
            shutdown_tx: Mutex::new(None),
            port: AtomicU16::new(0),
            running: AtomicBool::new(false),
            selection_debounce: Mutex::new(None),
        }
    }

    /// Binds the first available port and spawns the Axum server.  Returns the port.
    pub fn start(&self) -> u16 {
        if self.running.load(Ordering::Relaxed) {
            return self.port.load(Ordering::Relaxed);
        }

        // Bind synchronously before spawning so we can return the port number.
        let (port_min, port_max) = (self.port_min, self.port_max);
        let listener = self
            .runtime
            .block_on(async move {
                for p in port_min..=port_max {
                    if let Ok(l) =
                        tokio::net::TcpListener::bind(format!("127.0.0.1:{}", p)).await
                    {
                        return Ok(l);
                    }
                }
                Err(std::io::Error::other("No available port"))
            })
            .expect("Failed to bind HTTP server to any port");

        let bound_port = listener.local_addr().unwrap().port();
        self.port.store(bound_port, Ordering::Relaxed);

        let (shutdown_tx, shutdown_rx) = tokio::sync::oneshot::channel::<()>();
        *self.shutdown_tx.lock().unwrap() = Some(shutdown_tx);

        let state = Arc::clone(&self.state);
        self.runtime.spawn(async move {
            let app = Router::new()
                .route("/sse", get(sse_handler))
                .route("/messages", post(messages_handler))
                .with_state(state);

            axum::serve(listener, app)
                .with_graceful_shutdown(async {
                    let _ = shutdown_rx.await;
                })
                .await
                .ok();
        });

        self.running.store(true, Ordering::Relaxed);
        bound_port
    }

    fn stop(&self) {
        if self
            .running
            .compare_exchange(true, false, Ordering::SeqCst, Ordering::Relaxed)
            .is_err()
        {
            return; // already stopped
        }

        // Abort any pending selection debounce task.
        if let Some(handle) = self.selection_debounce.lock().unwrap().take() {
            handle.abort();
        }

        // Drop all SSE senders first so their streams return Poll::Ready(None)
        // and the Axum tasks complete before the runtime shuts down.
        self.state.clients.lock().unwrap().clear();

        // Signal Axum to stop accepting new connections.
        if let Some(tx) = self.shutdown_tx.lock().unwrap().take() {
            let _ = tx.send(());
        }
    }

    /// Called from Java on every raw selection event.
    /// Debounces 50 ms then broadcasts a notifications/selectionChanged message to all SSE clients.
    pub fn notify_selection(
        &self,
        file_path: String,
        text: String,
        start_line: i32,
        end_line: i32,
        is_empty: bool,
    ) {
        if !self.running.load(Ordering::Relaxed) {
            return;
        }

        // Cancel any previous pending broadcast.
        if let Some(handle) = self.selection_debounce.lock().unwrap().take() {
            handle.abort();
        }

        let args = SelectionArgs { file_path, text, start_line, end_line, is_empty };
        let state = Arc::clone(&self.state);

        let join_handle = self.runtime.spawn(async move {
            tokio::time::sleep(tokio::time::Duration::from_millis(50)).await;

            // Only bother broadcasting if there are connected clients.
            if state.clients.lock().unwrap().is_empty() {
                return;
            }

            let json = serde_json::json!({
                "jsonrpc": "2.0",
                "method": "notifications/selectionChanged",
                "params": {
                    "selection": {
                        "filePath": args.file_path,
                        "text": args.text,
                        "startLine": args.start_line,
                        "endLine": args.end_line,
                        "startColumn": 0,
                        "endColumn": 0,
                        "isEmpty": args.is_empty
                    }
                }
            })
            .to_string();

            let event = SseEvent {
                event_type: "message".to_string(),
                data: json,
            };
            let mut clients = state.clients.lock().unwrap();
            clients.retain(|_, tx| tx.send(event.clone()).is_ok());
        });

        *self.selection_debounce.lock().unwrap() = Some(join_handle);
    }

    pub fn broadcast(&self, json: &str) {
        let event = SseEvent {
            event_type: "message".to_string(),
            data: json.to_string(),
        };
        let mut clients = self.state.clients.lock().unwrap();
        // retain() removes clients whose channel has been closed (disconnected).
        clients.retain(|_, tx| tx.send(event.clone()).is_ok());
    }

    pub fn client_count(&self) -> usize {
        let mut clients = self.state.clients.lock().unwrap();
        // Prune clients whose receiver has been dropped (TCP connection dead).
        clients.retain(|_, tx| !tx.is_closed());
        clients.len()
    }

    pub fn port(&self) -> u16 {
        self.port.load(Ordering::Relaxed)
    }

    pub fn auth_token(&self) -> &str {
        &self.state.auth_token
    }

    pub fn is_running(&self) -> bool {
        self.running.load(Ordering::Relaxed)
    }

    pub fn register_tool_callback(&self, vm: Arc<jni::JavaVM>, callback: GlobalRef) {
        *self.state.tool_callback.lock().unwrap() = Some(ToolCallbackRef {
            java_vm: vm,
            callback: Arc::new(callback),
        });
    }
}

impl Drop for Server {
    fn drop(&mut self) {
        self.stop();
        // The Runtime is dropped after this, which blocks until all Tokio tasks finish.
        // Because stop() already closed all SSE senders, tasks complete quickly.
    }
}

// ---------------------------------------------------------------------------
// Axum handlers
// ---------------------------------------------------------------------------

#[derive(Deserialize)]
struct SessionQuery {
    #[serde(rename = "sessionId")]
    session_id: Option<String>,
}

/// GET /sse — establishes a long-lived Server-Sent Events stream.
async fn sse_handler(State(state): State<Arc<AppState>>) -> Response {
    let (tx, rx) = mpsc::unbounded_channel::<SseEvent>();
    let session_id = Uuid::new_v4().to_string();

    // Tell the client where to POST messages.
    let endpoint_url = format!("/messages?sessionId={}", session_id);
    let _ = tx.send(SseEvent {
        event_type: "endpoint".to_string(),
        data: endpoint_url,
    });

    // Register sender; the GuardedStream below will remove it on disconnect.
    state.clients.lock().unwrap().insert(session_id.clone(), tx);

    let inner = UnboundedReceiverStream::new(rx).map(|ev: SseEvent| {
        Ok::<Event, Infallible>(Event::default().event(ev.event_type).data(ev.data))
    });

    let guarded = GuardedStream {
        inner,
        guard: Some(ClientGuard {
            session_id,
            state: Arc::clone(&state),
        }),
    };

    Sse::new(guarded)
        .keep_alive(
            KeepAlive::new()
                .interval(Duration::from_secs(3))
                .text(":"),
        )
        .into_response()
}

/// POST /messages — receives a JSON-RPC 2.0 body and processes it asynchronously.
async fn messages_handler(
    State(state): State<Arc<AppState>>,
    Query(params): Query<SessionQuery>,
    body: Bytes,
) -> Response {
    let session_id = match params.session_id {
        Some(id) => id,
        None => return (StatusCode::BAD_REQUEST, "Missing sessionId").into_response(),
    };

    let sender = {
        let clients = state.clients.lock().unwrap();
        clients.get(&session_id).cloned()
    };

    let sender = match sender {
        Some(s) => s,
        None => return (StatusCode::BAD_REQUEST, "Unknown session").into_response(),
    };

    let body_str = match String::from_utf8(body.to_vec()) {
        Ok(s) => s,
        Err(_) => return (StatusCode::BAD_REQUEST, "Invalid UTF-8 body").into_response(),
    };

    // Respond 202 immediately; the actual JSON-RPC reply arrives over SSE.
    tokio::spawn(async move {
        crate::mcp::handle_message(state, sender, body_str).await;
    });

    (StatusCode::ACCEPTED, "Accepted").into_response()
}

// ---------------------------------------------------------------------------
// GuardedStream — removes the SSE client from the map when it drops.
// ---------------------------------------------------------------------------

struct ClientGuard {
    session_id: String,
    state: Arc<AppState>,
}

impl Drop for ClientGuard {
    fn drop(&mut self) {
        self.state.clients.lock().unwrap().remove(&self.session_id);
    }
}

struct GuardedStream<S> {
    inner: S,
    guard: Option<ClientGuard>,
}

impl<S: Stream + Unpin> Stream for GuardedStream<S> {
    type Item = S::Item;

    fn poll_next(mut self: Pin<&mut Self>, cx: &mut Context<'_>) -> Poll<Option<Self::Item>> {
        match Pin::new(&mut self.inner).poll_next(cx) {
            Poll::Ready(None) => {
                // Stream exhausted — drop guard so cleanup runs immediately.
                self.guard.take();
                Poll::Ready(None)
            }
            other => other,
        }
    }
}

// Axum requires the SSE stream to be Send.
unsafe impl<S: Send> Send for GuardedStream<S> {}
