use std::io::Write;
use std::net::TcpStream;
use std::sync::{Arc, Mutex, OnceLock};

static BRIDGE_STREAM: OnceLock<Arc<Mutex<Option<TcpStream>>>> = OnceLock::new();

fn stream_holder() -> &'static Arc<Mutex<Option<TcpStream>>> {
    BRIDGE_STREAM.get_or_init(|| Arc::new(Mutex::new(None)))
}

pub fn connect(port: u16) -> bool {
    let addr = format!("127.0.0.1:{}", port);
    match TcpStream::connect(&addr) {
        Ok(stream) => {
            stream.set_nodelay(true).ok();
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
