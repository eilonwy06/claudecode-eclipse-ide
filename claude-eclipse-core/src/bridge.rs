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

