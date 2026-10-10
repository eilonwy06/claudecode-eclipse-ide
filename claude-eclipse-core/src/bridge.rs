use std::io::{Read, Write};
use std::net::{Shutdown, SocketAddr, TcpListener, TcpStream};
use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::{Arc, Mutex, OnceLock};
use std::time::Duration;

// ---------------------------------------------------------------------------
// Client half — the core's own connection into the relay (port A side).
// ---------------------------------------------------------------------------

static BRIDGE_STREAM: OnceLock<Arc<Mutex<Option<TcpStream>>>> = OnceLock::new();

fn stream_holder() -> &'static Arc<Mutex<Option<TcpStream>>> {
    BRIDGE_STREAM.get_or_init(|| Arc::new(Mutex::new(None)))
}

pub fn connect(port: u16, token: &str) -> bool {
    let addr = format!("127.0.0.1:{}", port);
    match TcpStream::connect(&addr) {
        Ok(mut stream) => {
            stream.set_nodelay(true).ok();
            // Authenticate to the relay before it will wire this side through.
            // The relay drops any peer that does not present the matching token
            // on its first line, so an unauthorized local process cannot attach.
            if stream.write_all(format!("{}\n", token).as_bytes()).is_err() {
                return false;
            }
            stream.flush().ok();
            *stream_holder().lock().unwrap() = Some(stream);
            true
        }
        Err(_) => false,
    }
}

pub fn disconnect() {
    if let Some(stream) = stream_holder().lock().unwrap().take() {
        drop(stream);
    }
}

pub fn is_connected() -> bool {
    stream_holder().lock().unwrap().is_some()
}

pub fn send(data: &[u8]) -> bool {
    let mut guard = stream_holder().lock().unwrap();
    if let Some(ref mut stream) = *guard {
        if stream.write_all(data).is_ok() {
            stream.flush().ok();
            return true;
        }
        *guard = None;
    }
    false
}

pub fn send_str(s: &str) -> bool {
    send(s.as_bytes())
}

pub fn send_line(s: &str) -> bool {
    let mut data = s.to_string();
    data.push('\n');
    send(data.as_bytes())
}

// ---------------------------------------------------------------------------
// Relay half — in-process replacement for the external relay helper.
//
// Scans the configured port range and binds the FIRST TWO free ports (so
// concurrent IDE instances each get their own pair instead of colliding on
// fixed ports), then accepts one peer per port. Every peer must present the
// shared-secret handshake token on its first line within a short window or it
// is dropped — no other local process can attach to either side. Once both
// peers are authenticated, bytes are pumped verbatim in both directions.
//
// A disconnect ends that PAIRING, not the relay: the listeners stay bound and
// the loop goes back to accepting, so the relay keeps the same two ports for as
// long as it is running and a peer can reconnect into them. Only relay_stop()
// ends it.
// ---------------------------------------------------------------------------

struct RelayState {
    ports: (u16, u16),
    stop: Arc<AtomicBool>,
    // Clones of the accepted peers, kept ONLY so relay_stop() can shutdown()
    // reads that a pump thread is blocked on.
    peers: Arc<Mutex<Vec<TcpStream>>>,
}

static RELAY: OnceLock<Mutex<Option<RelayState>>> = OnceLock::new();

fn relay_holder() -> &'static Mutex<Option<RelayState>> {
    RELAY.get_or_init(|| Mutex::new(None))
}

/// A bind alone is not a fully reliable "port free" signal across platforms
/// (e.g. sockets in TIME_WAIT, or listeners bound with SO_REUSEADDR); probe
/// with a connect first and skip any port that answers.
fn port_in_use(port: u16) -> bool {
    let addr: SocketAddr = format!("127.0.0.1:{}", port).parse().unwrap();
    TcpStream::connect_timeout(&addr, Duration::from_millis(200)).is_ok()
}

/// Starts the relay on the first two free ports in `[port_min, port_max]`.
/// Returns the bound `(portA, portB)` pair, the existing pair if a relay is
/// already running, or `None` when no two free ports exist.
pub fn relay_start(port_min: u16, port_max: u16, token: &str) -> Option<(u16, u16)> {
    let mut guard = relay_holder().lock().unwrap();
    if let Some(ref state) = *guard {
        if !state.stop.load(Ordering::Relaxed) {
            return Some(state.ports);
        }
    }

    let mut listeners: Vec<(u16, TcpListener)> = Vec::new();
    for p in port_min..=port_max {
        if port_in_use(p) {
            continue;
        }
        if let Ok(l) = TcpListener::bind(("127.0.0.1", p)) {
            // Non-blocking so the accept loop can watch both ports and the
            // stop flag without dedicating a thread per listener.
            if l.set_nonblocking(true).is_err() {
                continue;
            }
            listeners.push((p, l));
            if listeners.len() == 2 {
                break;
            }
        }
    }
    if listeners.len() < 2 {
        return None;
    }
    let (port_b, listener_b) = listeners.pop().unwrap();
    let (port_a, listener_a) = listeners.pop().unwrap();

    let stop = Arc::new(AtomicBool::new(false));
    let peers = Arc::new(Mutex::new(Vec::new()));
    let token = token.to_string();
    {
        let stop = Arc::clone(&stop);
        let peers = Arc::clone(&peers);
        let spawned = std::thread::Builder::new()
            .name("bridge-relay".into())
            .spawn(move || relay_loop(listener_a, listener_b, &token, &stop, &peers));
        if spawned.is_err() {
            return None;
        }
    }

    *guard = Some(RelayState { ports: (port_a, port_b), stop, peers });
    Some((port_a, port_b))
}

/// Stops the relay: wakes the accept loop and tears down both peers so the
/// pump threads unblock and exit.
pub fn relay_stop() {
    let mut guard = relay_holder().lock().unwrap();
    if let Some(state) = guard.take() {
        state.stop.store(true, Ordering::Relaxed);
        for peer in state.peers.lock().unwrap().drain(..) {
            peer.shutdown(Shutdown::Both).ok();
        }
    }
}

/// True while the relay is up: listeners bound, or both peers wired through.
pub fn relay_is_running() -> bool {
    relay_holder()
        .lock()
        .unwrap()
        .as_ref()
        .map_or(false, |state| !state.stop.load(Ordering::Relaxed))
}

fn relay_loop(
    listener_a: TcpListener,
    listener_b: TcpListener,
    token: &str,
    stop: &Arc<AtomicBool>,
    peers: &Arc<Mutex<Vec<TcpStream>>>,
) {
    // One iteration per peer pairing. The listeners are owned by this function and
    // stay bound across iterations, which is what lets the relay keep its two ports
    // when a peer hangs up instead of dying with the first disconnect.
    while !stop.load(Ordering::Relaxed) {
        let mut client_a: Option<TcpStream> = None;
        let mut client_b: Option<TcpStream> = None;

        while !stop.load(Ordering::Relaxed) && (client_a.is_none() || client_b.is_none()) {
            if client_a.is_none() {
                client_a = accept_authed(&listener_a, token);
            }
            if client_b.is_none() {
                client_b = accept_authed(&listener_b, token);
            }
            if client_a.is_none() || client_b.is_none() {
                std::thread::sleep(Duration::from_millis(10));
            }
        }
        // Torn down while accepting; any half-accepted peer drops with the scope.
        let (a, b) = match (client_a, client_b) {
            (Some(a), Some(b)) if !stop.load(Ordering::Relaxed) => (a, b),
            _ => return,
        };

        // Register clones so relay_stop() can shutdown() blocked pump reads. The
        // previous pairing's entries are dead sockets by now, so replace rather than
        // append — otherwise the list grows without bound across reconnects.
        {
            let mut guard = peers.lock().unwrap();
            guard.clear();
            if let (Ok(ca), Ok(cb)) = (a.try_clone(), b.try_clone()) {
                guard.push(ca);
                guard.push(cb);
            }
        }
        // relay_stop() may have drained the list between the accept and the push
        // above, in which case nothing will ever shut these two down and the pumps
        // would block forever. Tear them down here instead.
        if stop.load(Ordering::Relaxed) {
            a.shutdown(Shutdown::Both).ok();
            b.shutdown(Shutdown::Both).ok();
            return;
        }

        match (a.try_clone(), b.try_clone()) {
            (Ok(a_writer), Ok(b_writer)) => {
                let pump_ab = std::thread::Builder::new()
                    .name("bridge-relay-ab".into())
                    .spawn(move || pump(a, b_writer));
                pump(b, a_writer); // B→A runs on the relay thread itself
                if let Ok(handle) = pump_ab {
                    handle.join().ok();
                }
            }
            _ => {
                a.shutdown(Shutdown::Both).ok();
                b.shutdown(Shutdown::Both).ok();
            }
        }
        // This pairing is over. Unless relay_stop() tore us down, loop back and accept
        // the next one on the SAME ports — the relay outlives the disconnect.
    }
}

/// Accepts a pending connection only if it presents the expected token on its
/// first line within a short window; otherwise drops it and keeps listening.
fn accept_authed(listener: &TcpListener, token: &str) -> Option<TcpStream> {
    let (mut conn, _addr) = match listener.accept() {
        Ok(pair) => pair,
        Err(_) => return None, // WouldBlock — nothing pending
    };
    // The accepted socket may inherit the listener's non-blocking mode; the
    // handshake read and the pump both want plain blocking I/O.
    conn.set_nonblocking(false).ok();
    conn.set_nodelay(true).ok();
    conn.set_read_timeout(Some(Duration::from_secs(2))).ok();

    let mut line = Vec::with_capacity(64);
    let mut byte = [0u8; 1];
    let got_line = loop {
        match conn.read(&mut byte) {
            Ok(0) | Err(_) => break false,
            Ok(_) => {
                if byte[0] == b'\n' {
                    break true;
                }
                line.push(byte[0]);
                if line.len() >= 4096 {
                    break false;
                }
            }
        }
    };
    if !got_line {
        return None;
    }
    let presented = String::from_utf8_lossy(&line);
    if !constant_time_eq(presented.trim().as_bytes(), token.as_bytes()) {
        if crate::is_debug() {
            eprintln!("[bridge-relay] rejected unauthenticated peer");
        }
        return None;
    }
    conn.set_read_timeout(None).ok();
    if crate::is_debug() {
        eprintln!("[bridge-relay] authenticated peer");
    }
    Some(conn)
}

/// Copies bytes from `from` to `to` until EOF or error, then tears both down
/// so the opposite pump unblocks and exits too.
fn pump(mut from: TcpStream, mut to: TcpStream) {
    let mut buf = [0u8; 65536];
    loop {
        match from.read(&mut buf) {
            Ok(0) | Err(_) => break,
            Ok(n) => {
                if to.write_all(&buf[..n]).is_err() {
                    break;
                }
                to.flush().ok();
            }
        }
    }
    from.shutdown(Shutdown::Both).ok();
    to.shutdown(Shutdown::Both).ok();
}

/// Token comparison that doesn't leak the mismatch position through timing
/// (same intent as hash_equals in the old relay).
fn constant_time_eq(a: &[u8], b: &[u8]) -> bool {
    if a.len() != b.len() {
        return false;
    }
    let mut diff = 0u8;
    for (x, y) in a.iter().zip(b.iter()) {
        diff |= x ^ y;
    }
    diff == 0
}


// ---------------------------------------------------------------------------
// Remote-control frames
// ---------------------------------------------------------------------------

/// The request id prefix carried by remote-control control requests.
pub(crate) const RC_REQ_PREFIX: &str = "eclipse-rc-";

/// Builds the control-request line for a remote-control toggle.
///
/// `seq` is the caller's own counter; the returned id embeds it so the matching
/// reply can be told apart from every other control request in flight.
pub(crate) fn rc_request_line(seq: u64, enabled: bool) -> (String, String) {
    let request_id = format!("{}{}", RC_REQ_PREFIX, seq);
    let line = serde_json::json!({
        "type": "control_request",
        "request_id": request_id,
        "request": { "subtype": "remote_control", "enabled": enabled }
    })
    .to_string();
    (request_id, line)
}

/// Request ids of a switch-on that takes over the bridge a replaced process held.
/// Starts with [`RC_REQ_PREFIX`], so [`rc_owns_response`] still claims its reply.
const RC_REATTACH_PREFIX: &str = "eclipse-rc-reattach-";

/// Builds the switch-on for a process replacing one that had Remote Control on
/// (a respawn for a model or effort change). `reattach_session_id` hands it the
/// bridge session the old process held, so the phone and claude.ai stay on the
/// same session instead of being left on one nothing answers — verified live: a
/// fresh process given the id of a hard-killed one's session gets that session
/// back, same url. An empty id (the CLI sometimes omits it) asks for a new one.
pub(crate) fn rc_reattach_line(seq: u64, bridge_session_id: &str) -> (String, String) {
    let request_id = format!("{}{}", RC_REATTACH_PREFIX, seq);
    let mut request = serde_json::json!({ "subtype": "remote_control", "enabled": true });
    if !bridge_session_id.is_empty() {
        request["reattach_session_id"] = serde_json::json!(bridge_session_id);
    }
    let line = serde_json::json!({
        "type": "control_request",
        "request_id": request_id,
        "request": request
    })
    .to_string();
    (request_id, line)
}

/// Whether a control-response id answers a [`rc_reattach_line`] — a switch-on
/// nobody on the page asked for, so the page is owed no reply to it.
pub(crate) fn rc_is_reattach(request_id: &str) -> bool {
    request_id.starts_with(RC_REATTACH_PREFIX)
}

/// Whether a control-response id belongs to a remote-control request.
pub(crate) fn rc_owns_response(request_id: &str) -> bool {
    request_id.starts_with(RC_REQ_PREFIX)
}

/// The fields a remote-control reply carries, flattened for the caller.
pub(crate) struct RcReply {
    pub enabled: bool,
    pub url: String,
    pub bridge_session_id: String,
    /// Which bridge this is, when the CLI says: a later `failed` is matched against it.
    pub bridge_epoch: Option<i64>,
    pub error: serde_json::Value,
}

/// Reads a control-response body into [`RcReply`].
///
/// `inner` is the response object — the `response` member of the event, whose
/// own `response` member holds the payload.
pub(crate) fn rc_parse_reply(inner: &serde_json::Value) -> RcReply {
    let ok = inner["subtype"].as_str() == Some("success");
    let payload = &inner["response"];
    let url = payload["session_url"].as_str().unwrap_or("").to_string();
    RcReply {
        enabled: ok && !url.is_empty(),
        url,
        bridge_session_id: payload["bridge_session_id"].as_str().unwrap_or("").to_string(),
        bridge_epoch: payload["bridge_epoch"].as_i64(),
        error: if ok {
            serde_json::Value::Null
        } else {
            inner["error"].clone()
        },
    }
}

/// Renders a reply as the JSON Java receives.
pub(crate) fn rc_reply_json(r: &RcReply) -> String {
    serde_json::json!({
        "enabled": r.enabled,
        "url": r.url,
        "bridgeSessionId": r.bridge_session_id,
        "bridgeEpoch": r.bridge_epoch,
        "error": r.error,
    })
    .to_string()
}

/// Renders a bridge-state signal as the JSON Java receives.
pub(crate) fn rc_state_json(state: &str) -> String {
    serde_json::json!({ "bridgeState": state }).to_string()
}

/// Renders a `bridge_state` event, with its epoch when the CLI sent one. The epoch
/// is what a `failed` is matched against, so the failure of a bridge the tab no
/// longer shows is not read as the failure of the one it does — the VS Code
/// extension's rule.
///
/// `detail` is the CLI's own words for why a bridge failed ("Remote Control requires a
/// claude.ai subscription…"). It arrives on the `failed` event itself, ahead of the
/// control reply, so it is passed along rather than leaving the page to announce a
/// failure it cannot explain. Left out when the CLI sent none or an empty one.
pub(crate) fn rc_bridge_state_json(state: &str, bridge_epoch: Option<i64>, detail: Option<&str>) -> String {
    let mut v = serde_json::json!({ "bridgeState": state });
    if let Some(epoch) = bridge_epoch {
        v["bridgeEpoch"] = serde_json::json!(epoch);
    }
    if let Some(detail) = detail.map(str::trim).filter(|d| !d.is_empty()) {
        v["detail"] = serde_json::json!(detail);
    }
    v.to_string()
}

/// Renders a session url as a scannable QR code, as SVG.
///
/// SVG rather than a bitmap so it stays crisp at whatever size the panel gives
/// it — a QR that has been resampled is a QR that will not scan.
///
/// **Deliberately not themed.** Black modules on white, always, with the four-
/// module quiet zone the spec requires. Decoders key off that contrast and that
/// margin; honouring a dark theme here would produce something that looks right
/// and scans badly, which is worse than looking out of place.
///
/// Returns `""` when the url cannot be encoded — the caller then simply shows
/// no code rather than a broken one.
pub(crate) fn rc_qr_svg(url: &str) -> String {
    if url.is_empty() {
        return String::new();
    }
    let Ok(code) = qrcode::QrCode::new(url.as_bytes()) else {
        return String::new();
    };
    let width = code.width();
    const QUIET: usize = 4;
    let side = width + QUIET * 2;

    // One path for every dark module: far fewer nodes than a rect each, and it
    // renders identically.
    let colors = code.to_colors();
    let mut d = String::new();
    for y in 0..width {
        for x in 0..width {
            if colors[y * width + x] == qrcode::Color::Dark {
                d.push_str(&format!("M{} {}h1v1h-1z", x + QUIET, y + QUIET));
            }
        }
    }

    format!(
        "<svg xmlns=\"http://www.w3.org/2000/svg\" viewBox=\"0 0 {s} {s}\" \
         shape-rendering=\"crispEdges\" role=\"img\" aria-label=\"QR code for this session\">\
         <rect width=\"{s}\" height=\"{s}\" fill=\"#ffffff\"/>\
         <path d=\"{d}\" fill=\"#000000\"/></svg>",
        s = side,
        d = d
    )
}

/// Looks up the text of a message that arrived over the bridge.
///
/// **Why a lookup is needed at all.** When someone types on claude.ai or their
/// phone, the CLI does not put that message on stdout. It announces the work
/// instead — `command_lifecycle` with a `command_uuid` and a state — and then
/// streams the reply. Verified by posting a client event to a live bridge
/// session and reading every line the CLI emitted: `queued`, `started`, the
/// assistant turn, `completed`, and no `user` event anywhere. The words only
/// exist in the session's own event log, so that is where they are read from.
///
/// Matches on `payload.uuid`, which is the same value `command_uuid` carries.
/// Scans newest-first and stops at the first page that has it, since an inbound
/// message is by definition the most recent thing in the log.
///
/// **Blocking** — one HTTPS round trip. Never call it from the reader thread.
pub(crate) fn rc_lookup_message(
    claude_cmd: &str,
    bridge_session_id: &str,
    command_uuid: &str,
) -> Option<String> {
    if bridge_session_id.is_empty() || command_uuid.is_empty() {
        return None;
    }
    let path = format!("/v1/code/sessions/{}/events", bridge_session_id);
    let body = crate::web_history::api_get(claude_cmd, &path).ok()?;
    let v: serde_json::Value = serde_json::from_str(&body).ok()?;
    for e in v.get("data")?.as_array()? {
        let payload = &e["payload"];
        if payload["uuid"].as_str() != Some(command_uuid) {
            continue;
        }
        return rc_incoming_text(payload);
    }
    None
}

/// The text of a `user` event that arrived from elsewhere, or `None`.
///
/// A message typed here never comes back on stdout — verified against claude
/// 2.1.251, which answers a locally written user message with `system`,
/// `stream_event`, `assistant` and `result` and no `user` event at all. So a
/// text-bearing `user` event is one that arrived over the bridge, and rendering
/// it cannot duplicate a bubble the page already drew.
///
/// Returns `None` for the three kinds that are not somebody talking:
/// tool results (the CLI feeds those back as a user turn), synthetic echoes
/// such as a compact summary, and meta notices the CLI injects itself.
pub(crate) fn rc_incoming_text(event: &serde_json::Value) -> Option<String> {
    if event["isSynthetic"].as_bool().unwrap_or(false) || event["isMeta"].as_bool().unwrap_or(false)
    {
        return None;
    }
    let content = &event["message"]["content"];

    if let Some(s) = content.as_str() {
        let t = s.trim();
        return if t.is_empty() { None } else { Some(t.to_string()) };
    }

    let blocks = content.as_array()?;
    // A turn carrying any tool_result is the CLI's own plumbing, not a message,
    // even when a text block rides along with it.
    if blocks
        .iter()
        .any(|b| b["type"].as_str() == Some("tool_result"))
    {
        return None;
    }
    let mut text = String::new();
    for b in blocks {
        if b["type"].as_str() != Some("text") {
            continue;
        }
        if let Some(s) = b["text"].as_str() {
            if !text.is_empty() {
                text.push('\n');
            }
            text.push_str(s);
        }
    }
    let t = text.trim();
    if t.is_empty() {
        None
    } else {
        Some(t.to_string())
    }
}

// ---------------------------------------------------------------------------
// Tests
// ---------------------------------------------------------------------------

#[cfg(test)]
mod tests {
    use super::*;

    fn read_exact_str(stream: &mut TcpStream, len: usize) -> String {
        let mut buf = vec![0u8; len];
        stream.read_exact(&mut buf).expect("read");
        String::from_utf8(buf).expect("utf8")
    }

    /// One combined test (the relay is a process-global singleton): a peer with
    /// the wrong token is rejected, authenticated peers get wired through in
    /// both directions, and relay_stop tears everything down.
    #[test]
    fn relay_rejects_bad_token_then_forwards_both_ways() {
        let token = "test-secret-token";
        let (port_a, port_b) =
            relay_start(47610, 47690, token).expect("two free ports in test range");
        assert!(relay_is_running());
        assert_ne!(port_a, port_b);

        // Unauthenticated peer: dropped after its bogus first line.
        {
            let mut bad = TcpStream::connect(("127.0.0.1", port_a)).unwrap();
            bad.write_all(b"wrong-token\n").unwrap();
            bad.set_read_timeout(Some(Duration::from_secs(5))).unwrap();
            let mut one = [0u8; 1];
            assert!(
                matches!(bad.read(&mut one), Ok(0) | Err(_)),
                "unauthenticated peer must be disconnected"
            );
        }

        // Authenticated peers on both ports get wired through.
        let mut a = TcpStream::connect(("127.0.0.1", port_a)).unwrap();
        a.write_all(format!("{}\n", token).as_bytes()).unwrap();
        let mut b = TcpStream::connect(("127.0.0.1", port_b)).unwrap();
        b.write_all(format!("{}\n", token).as_bytes()).unwrap();
        a.set_read_timeout(Some(Duration::from_secs(5))).unwrap();
        b.set_read_timeout(Some(Duration::from_secs(5))).unwrap();

        a.write_all(b"CHAT:onText:hello\n").unwrap();
        assert_eq!(read_exact_str(&mut b, 18), "CHAT:onText:hello\n");
        b.write_all(b"pong\n").unwrap();
        assert_eq!(read_exact_str(&mut a, 5), "pong\n");

        // A disconnect must end only this pairing: the relay keeps the SAME two ports
        // and accepts a fresh pair, rather than dying with the first hang-up.
        drop(a);
        drop(b);
        assert!(relay_is_running(), "a peer disconnect must not end the relay");

        let (port_a2, port_b2) =
            relay_start(47610, 47690, token).expect("relay is still up on its ports");
        assert_eq!(
            (port_a2, port_b2),
            (port_a, port_b),
            "the relay must not rebind after a disconnect"
        );

        let mut a2 = TcpStream::connect(("127.0.0.1", port_a)).unwrap();
        a2.write_all(format!("{}\n", token).as_bytes()).unwrap();
        let mut b2 = TcpStream::connect(("127.0.0.1", port_b)).unwrap();
        b2.write_all(format!("{}\n", token).as_bytes()).unwrap();
        a2.set_read_timeout(Some(Duration::from_secs(5))).unwrap();
        b2.set_read_timeout(Some(Duration::from_secs(5))).unwrap();

        a2.write_all(b"second\n").unwrap();
        assert_eq!(read_exact_str(&mut b2, 7), "second\n");
        b2.write_all(b"back\n").unwrap();
        assert_eq!(read_exact_str(&mut a2, 5), "back\n");

        // Stop tears down whichever pairing is live at the time — here, the second one.
        relay_stop();
        assert!(!relay_is_running());
        let mut one = [0u8; 1];
        assert!(
            matches!(a2.read(&mut one), Ok(0) | Err(_)),
            "peers are torn down on stop"
        );
    }
}

/// Pins the remote-control frame shapes byte-for-byte. These are wire formats:
/// the request goes to the CLI and the rendered JSON goes to Java, so a change
/// here is a change to something outside this crate.
#[cfg(test)]
mod rc_tests {
    use super::*;

    #[test]
    fn request_line_is_exact() {
        let (id, line) = rc_request_line(7, true);
        assert_eq!(id, "eclipse-rc-7");
        let v: serde_json::Value = serde_json::from_str(&line).unwrap();
        assert_eq!(v["type"], "control_request");
        assert_eq!(v["request_id"], "eclipse-rc-7");
        assert_eq!(v["request"]["subtype"], "remote_control");
        assert_eq!(v["request"]["enabled"], true);

        let (_, off) = rc_request_line(8, false);
        let v2: serde_json::Value = serde_json::from_str(&off).unwrap();
        assert_eq!(v2["request"]["enabled"], false);
    }

    #[test]
    fn ids_are_unique_per_sequence() {
        assert_ne!(rc_request_line(1, true).0, rc_request_line(2, true).0);
    }

    #[test]
    fn reattach_hands_the_old_bridge_session_to_the_new_process() {
        let (id, line) = rc_reattach_line(3, "cse_01YS1GZJiryQChtiFb2D94ei");
        let v: serde_json::Value = serde_json::from_str(&line).unwrap();
        assert_eq!(v["request"]["subtype"], "remote_control");
        assert_eq!(v["request"]["enabled"], true);
        assert_eq!(v["request"]["reattach_session_id"], "cse_01YS1GZJiryQChtiFb2D94ei");
        assert!(rc_owns_response(&id) && rc_is_reattach(&id));
        assert!(!rc_is_reattach(&rc_request_line(3, true).0));

        let (_, fresh) = rc_reattach_line(4, "");
        let v: serde_json::Value = serde_json::from_str(&fresh).unwrap();
        assert!(v["request"].get("reattach_session_id").is_none());
    }

    #[test]
    fn bridge_epoch_rides_along_to_the_page() {
        let inner: serde_json::Value = serde_json::from_str(
            r#"{"subtype":"success","request_id":"eclipse-rc-1","response":{
                 "session_url":"https://claude.ai/code/session_01","bridge_epoch":3,
                 "bridge_session_id":"cse_01"}}"#,
        )
        .unwrap();
        let j: serde_json::Value = serde_json::from_str(&rc_reply_json(&rc_parse_reply(&inner))).unwrap();
        assert_eq!(j["bridgeEpoch"], 3);

        let s: serde_json::Value = serde_json::from_str(&rc_bridge_state_json("failed", Some(3), None)).unwrap();
        assert_eq!(s["bridgeState"], "failed");
        assert_eq!(s["bridgeEpoch"], 3);
        let bare: serde_json::Value = serde_json::from_str(&rc_bridge_state_json("failed", None, None)).unwrap();
        assert!(bare.get("bridgeEpoch").is_none());
        assert!(bare.get("detail").is_none());
    }

    #[test]
    fn a_failed_bridge_carries_the_reason_the_cli_gave() {
        let why = "Remote Control requires a claude.ai subscription. Run `claude auth login`.";
        let s: serde_json::Value =
            serde_json::from_str(&rc_bridge_state_json("failed", None, Some(&format!("  {why}\n")))).unwrap();
        assert_eq!(s["detail"], why);
        // Nothing to say is nothing sent: an empty or blank detail is not a reason.
        for blank in ["", "   "] {
            let s: serde_json::Value = serde_json::from_str(&rc_bridge_state_json("failed", None, Some(blank))).unwrap();
            assert!(s.get("detail").is_none());
        }
    }

    #[test]
    fn only_our_own_ids_are_claimed() {
        assert!(rc_owns_response("eclipse-rc-1"));
        assert!(!rc_owns_response("eclipse-ren-1"));
        assert!(!rc_owns_response("eclipse-mode-1"));
        assert!(!rc_owns_response(""));
    }

    /// The live reply, verbatim from claude 2.1.251.
    #[test]
    fn parses_the_real_reply() {
        let inner: serde_json::Value = serde_json::from_str(
            r#"{"subtype":"success","request_id":"eclipse-rc-1","response":{
                 "session_url":"https://claude.ai/code/session_01DAFk4EDDmKpExCkaKCCosU",
                 "connect_url":"https://claude.ai/code?environment=",
                 "environment_id":"","bridge_epoch":1,
                 "bridge_session_id":"cse_01DAFk4EDDmKpExCkaKCCosU"}}"#,
        )
        .unwrap();
        let r = rc_parse_reply(&inner);
        assert!(r.enabled);
        assert_eq!(r.url, "https://claude.ai/code/session_01DAFk4EDDmKpExCkaKCCosU");
        assert_eq!(r.bridge_session_id, "cse_01DAFk4EDDmKpExCkaKCCosU");

        let j: serde_json::Value = serde_json::from_str(&rc_reply_json(&r)).unwrap();
        assert_eq!(j["enabled"], true);
        assert_eq!(j["url"], "https://claude.ai/code/session_01DAFk4EDDmKpExCkaKCCosU");
        assert_eq!(j["bridgeSessionId"], "cse_01DAFk4EDDmKpExCkaKCCosU");
        assert!(j["error"].is_null());
    }

    /// A success with no url is not Remote Control being on — the indicator
    /// would otherwise light with nothing to link to.
    #[test]
    fn a_success_without_a_url_is_not_enabled() {
        let inner: serde_json::Value =
            serde_json::from_str(r#"{"subtype":"success","response":{}}"#).unwrap();
        assert!(!rc_parse_reply(&inner).enabled);
    }

    #[test]
    fn a_failure_carries_its_error_through() {
        let inner: serde_json::Value = serde_json::from_str(
            r#"{"subtype":"error","error":"policy denied","response":{}}"#,
        )
        .unwrap();
        let r = rc_parse_reply(&inner);
        assert!(!r.enabled);
        let j: serde_json::Value = serde_json::from_str(&rc_reply_json(&r)).unwrap();
        assert_eq!(j["error"], "policy denied");
    }

    #[test]
    fn state_json_is_exact() {
        let j: serde_json::Value = serde_json::from_str(&rc_state_json("connected")).unwrap();
        assert_eq!(j["bridgeState"], "connected");
        assert_eq!(j.as_object().unwrap().len(), 1, "one key only");
    }

    /// A locally sent message never comes back on stdout (verified against
    /// claude 2.1.251), so anything this returns is somebody else typing.
    /// Everything the CLI routes through a `user` event for its own reasons has
    /// to be filtered out here, or plumbing renders as speech.
    #[test]
    fn plain_text_from_another_device_is_a_message() {
        let e: serde_json::Value = serde_json::from_str(
            r#"{"type":"user","message":{"role":"user","content":"hello from my phone"}}"#).unwrap();
        assert_eq!(rc_incoming_text(&e).as_deref(), Some("hello from my phone"));
    }

    #[test]
    fn text_blocks_are_joined() {
        let e: serde_json::Value = serde_json::from_str(
            r#"{"type":"user","message":{"role":"user","content":[
                 {"type":"text","text":"first"},{"type":"text","text":"second"}]}}"#).unwrap();
        assert_eq!(rc_incoming_text(&e).as_deref(), Some("first
second"));
    }

    #[test]
    fn tool_results_are_not_messages() {
        // The CLI feeds tool output back as a user turn; onToolEnd already owns it.
        let e: serde_json::Value = serde_json::from_str(
            r#"{"type":"user","message":{"role":"user","content":[
                 {"type":"tool_result","tool_use_id":"x","content":"out"}]}}"#).unwrap();
        assert!(rc_incoming_text(&e).is_none());
    }

    /// The dangerous shape: a tool_result with a text block riding along would
    /// otherwise surface half a tool result as if someone had said it.
    #[test]
    fn a_tool_result_with_text_alongside_is_still_not_a_message() {
        let e: serde_json::Value = serde_json::from_str(
            r#"{"type":"user","message":{"role":"user","content":[
                 {"type":"text","text":"here you go"},
                 {"type":"tool_result","tool_use_id":"x","content":"out"}]}}"#).unwrap();
        assert!(rc_incoming_text(&e).is_none());
    }

    #[test]
    fn synthetic_and_meta_turns_are_not_messages() {
        // Compact summaries arrive synthetic; the CLI injects meta notices such as
        // "This session is being continued from another machine".
        let syn: serde_json::Value = serde_json::from_str(
            r#"{"type":"user","isSynthetic":true,"message":{"role":"user","content":"summary"}}"#).unwrap();
        assert!(rc_incoming_text(&syn).is_none());
        let meta: serde_json::Value = serde_json::from_str(
            r#"{"type":"user","isMeta":true,"message":{"role":"user","content":"continued elsewhere"}}"#).unwrap();
        assert!(rc_incoming_text(&meta).is_none());
    }

    #[test]
    fn empty_and_malformed_turns_yield_nothing() {
        for raw in [
            r#"{"type":"user","message":{"role":"user","content":""}}"#,
            r#"{"type":"user","message":{"role":"user","content":"   "}}"#,
            r#"{"type":"user","message":{"role":"user","content":[]}}"#,
            r#"{"type":"user","message":{"role":"user","content":[{"type":"image"}]}}"#,
            r#"{"type":"user"}"#,
            r#"{}"#,
        ] {
            let e: serde_json::Value = serde_json::from_str(raw).unwrap();
            assert!(rc_incoming_text(&e).is_none(), "should be ignored: {}", raw);
        }
    }

    #[test]
    fn surrounding_whitespace_is_trimmed() {
        let e: serde_json::Value = serde_json::from_str(
            r#"{"type":"user","message":{"role":"user","content":"  spaced  "}}"#).unwrap();
        assert_eq!(rc_incoming_text(&e).as_deref(), Some("spaced"));
    }

    /// The inbound path, pinned against the real trace. When a message arrives
    /// over the bridge the CLI emits `command_lifecycle` and NOT a user event,
    /// so the text is looked up by uuid; these guard the matching.
    #[test]
    fn a_lifecycle_announcement_carries_no_text() {
        // Verbatim from the live trace, so the shape cannot drift unnoticed.
        let e: serde_json::Value = serde_json::from_str(
            r#"{"type":"command_lifecycle","command_uuid":"11111111-2222-4333-8444-555555555555",
                 "state":"queued","uuid":"9de9f4d4","session_id":"d4bca867"}"#).unwrap();
        assert_eq!(e["state"], "queued");
        assert_eq!(e["command_uuid"], "11111111-2222-4333-8444-555555555555");
        // Nothing message-shaped to read — hence the lookup.
        assert!(e["message"].is_null());
        assert!(rc_incoming_text(&e).is_none());
    }

    #[test]
    fn a_looked_up_payload_yields_its_text() {
        // The payload as the events log stores it for a client-typed message.
        let payload: serde_json::Value = serde_json::from_str(
            r#"{"type":"user","client_platform":"claude_code_cli",
                 "message":{"role":"user","content":"ping from a client"},
                 "session_id":"cse_x","uuid":"11111111-2222-4333-8444-555555555555",
                 "parent_tool_use_id":null}"#).unwrap();
        assert_eq!(rc_incoming_text(&payload).as_deref(), Some("ping from a client"));
    }

    #[test]
    fn lookup_refuses_to_call_out_with_nothing_to_match() {
        assert!(rc_lookup_message("", "", "some-uuid").is_none());
        assert!(rc_lookup_message("", "cse_x", "").is_none());
    }

    /// The QR has to actually scan, so the properties decoders rely on are
    /// asserted rather than eyeballed: real modules, the mandatory quiet zone,
    /// and hard black-on-white contrast.
    #[test]
    fn renders_a_scannable_qr() {
        let svg = rc_qr_svg("https://claude.ai/code/session_01DAFk4EDDmKpExCkaKCCosU");
        assert!(svg.starts_with("<svg"), "{}", &svg[..svg.len().min(80)]);
        assert!(svg.contains("viewBox=\"0 0 "));
        assert!(svg.contains("fill=\"#ffffff\""), "needs a light background");
        assert!(svg.contains("fill=\"#000000\""), "needs dark modules");
        assert!(svg.contains("crispEdges"), "must not blur at the module edges");
        // Plenty of modules — a path with only a handful would mean it encoded
        // nothing useful.
        assert!(svg.matches('M').count() > 100, "too few modules: {}", svg.matches('M').count());
    }

    #[test]
    fn the_quiet_zone_is_present_on_every_side() {
        // The viewBox is the code plus 4 modules of margin each side, and no
        // module may be drawn inside that margin.
        let svg = rc_qr_svg("https://claude.ai/code/session_01ABC");
        let vb = svg.split("viewBox=\"0 0 ").nth(1).unwrap();
        let side: usize = vb.split(' ').next().unwrap().parse().unwrap();
        // Every module coordinate lies within [4, side-5].
        for seg in svg.split('M').skip(1) {
            let coords: Vec<&str> = seg.split('h').next().unwrap().split(' ').collect();
            let x: usize = coords[0].parse().unwrap();
            let y: usize = coords[1].parse().unwrap();
            assert!(x >= 4 && y >= 4, "module inside the quiet zone at {},{}", x, y);
            assert!(x < side - 4 && y < side - 4, "module past the quiet zone at {},{}", x, y);
        }
    }

    #[test]
    fn a_longer_url_still_encodes() {
        // Version scales with length; a long session url must not fall over.
        let long = format!("https://claude.ai/code/session_{}", "0123456789".repeat(8));
        assert!(rc_qr_svg(&long).starts_with("<svg"));
    }

    #[test]
    fn nothing_to_encode_yields_nothing_to_show() {
        assert_eq!(rc_qr_svg(""), "");
    }
}
