use std::io::Write;
use std::net::TcpStream;
use std::sync::{Arc, Mutex, OnceLock};

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
