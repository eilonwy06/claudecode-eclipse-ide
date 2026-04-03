//! Virtual terminal: wraps the `vt100` crate to maintain a screen buffer
//! and serialize it as JSON for the Java StyledText widget.

use std::collections::hash_map::DefaultHasher;
use std::hash::{Hash, Hasher};

/// Standard 256-color palette (0-255) → (R, G, B).
static PALETTE: [(u8, u8, u8); 256] = {
    let mut t = [(0u8, 0u8, 0u8); 256];
    // 0-7: standard ANSI
    t[0]  = (0,0,0);       t[1]  = (205,0,0);     t[2]  = (0,205,0);
    t[3]  = (205,205,0);   t[4]  = (0,0,238);     t[5]  = (205,0,205);
    t[6]  = (0,205,205);   t[7]  = (229,229,229);
    // 8-15: bright ANSI
    t[8]  = (127,127,127); t[9]  = (255,0,0);     t[10] = (0,255,0);
    t[11] = (255,255,0);   t[12] = (92,92,255);   t[13] = (255,0,255);
    t[14] = (0,255,255);   t[15] = (255,255,255);
    // 16-231: 6×6×6 color cube
    let mut i = 16usize;
    let mut r = 0u8;
    while r < 6 {
        let mut g = 0u8;
        while g < 6 {
            let mut b = 0u8;
            while b < 6 {
                let rv = if r == 0 { 0 } else { 55 + r * 40 };
                let gv = if g == 0 { 0 } else { 55 + g * 40 };
                let bv = if b == 0 { 0 } else { 55 + b * 40 };
                t[i] = (rv, gv, bv);
                i += 1;
                b += 1;
            }
            g += 1;
        }
        r += 1;
    }
    // 232-255: grayscale ramp
    let mut j = 232usize;
    while j < 256 {
        let v = (8 + (j - 232) * 10) as u8;
        t[j] = (v, v, v);
        j += 1;
    }
    t
};

#[derive(Clone, PartialEq)]
struct CellStyle {
    fg: Option<(u8,u8,u8)>,
    bg: Option<(u8,u8,u8)>,
    bold: bool,
    italic: bool,
    underline: bool,
    inverse: bool,
}

impl CellStyle {
    fn has_style(&self) -> bool {
        self.fg.is_some() || self.bg.is_some()
            || self.bold || self.italic || self.underline || self.inverse
    }

    fn write_span(&self, json: &mut String, start: u16, len: u16) {
        json.push_str("{\"o\":");
        json.push_str(&start.to_string());
        json.push_str(",\"l\":");
        json.push_str(&len.to_string());

        if let Some((r, g, b)) = self.fg {
            json.push_str(",\"fg\":[");
            json.push_str(&r.to_string()); json.push(',');
            json.push_str(&g.to_string()); json.push(',');
            json.push_str(&b.to_string()); json.push(']');
        }
        if let Some((r, g, b)) = self.bg {
            json.push_str(",\"bg\":[");
            json.push_str(&r.to_string()); json.push(',');
            json.push_str(&g.to_string()); json.push(',');
            json.push_str(&b.to_string()); json.push(']');
        }
        if self.bold      { json.push_str(",\"b\":1"); }
        if self.italic    { json.push_str(",\"i\":1"); }
        if self.underline { json.push_str(",\"u\":1"); }
        if self.inverse   { json.push_str(",\"v\":1"); }

        json.push('}');
    }
}

fn cell_to_style(cell: &vt100::Cell) -> CellStyle {
    CellStyle {
        fg: color_to_rgb(cell.fgcolor()),
        bg: color_to_rgb(cell.bgcolor()),
        bold: cell.bold(),
        italic: cell.italic(),
        underline: cell.underline(),
        inverse: cell.inverse(),
    }
}

fn color_to_rgb(c: vt100::Color) -> Option<(u8, u8, u8)> {
    match c {
        vt100::Color::Default => None,
        vt100::Color::Idx(i) => Some(PALETTE[i as usize]),
        vt100::Color::Rgb(r, g, b) => Some((r, g, b)),
    }
}

pub struct VirtualTerminal {
    parser: vt100::Parser,
    prev_hash: u64,
    /// Text lines that have scrolled off the top of the visible screen.
    scrollback_buf: Vec<String>,
    /// How many entries in scrollback_buf have already been sent to Java.
    last_sent_sb: usize,
}

impl VirtualTerminal {
    pub fn new(rows: u16, cols: u16) -> Self {
        VirtualTerminal {
            parser: vt100::Parser::new(rows, cols, 10_000),
            prev_hash: 0,
            scrollback_buf: Vec::new(),
            last_sent_sb: 0,
        }
    }

    pub fn process(&mut self, data: &[u8]) {
        // Snapshot the visible top rows and scrollback count BEFORE processing,
        // so we can detect lines that scroll off into the scrollback buffer.
        let (old_sb, top_rows) = {
            let screen = self.parser.screen();
            let sb = screen.scrollback();
            let rows = screen.size().0;
            let cols = screen.size().1;
            let mut lines = Vec::with_capacity(rows as usize);
            for row in 0..rows {
                let mut line = String::with_capacity(cols as usize);
                for col in 0..cols {
                    if let Some(cell) = screen.cell(row, col) {
                        let ch = cell.contents();
                        if ch.is_empty() { line.push(' '); } else { line.push_str(&ch); }
                    }
                }
                lines.push(line.trim_end().to_string());
            }
            (sb, lines)
        };

        self.parser.process(data);

        let new_sb = self.parser.screen().scrollback();
        let delta = new_sb.saturating_sub(old_sb);
        if delta > 0 {
            // The top `delta` rows from the previous visible screen scrolled off.
            for i in 0..delta.min(top_rows.len()) {
                self.scrollback_buf.push(top_rows[i].clone());
            }
        }

        // Cap at 10 000 lines.
        if self.scrollback_buf.len() > 10_000 {
            let excess = self.scrollback_buf.len() - 10_000;
            self.scrollback_buf.drain(0..excess);
            self.last_sent_sb = self.last_sent_sb.saturating_sub(excess);
        }
    }

    pub fn resize(&mut self, rows: u16, cols: u16) {
        // After resize the parser may reflow content, changing the scrollback
        // count.  Reset our tracking so we don't misattribute reflow as scroll.
        let old_sb = self.parser.screen().scrollback();
        self.parser.set_size(rows, cols);
        let new_sb = self.parser.screen().scrollback();
        // If reflow added/removed scrollback lines, absorb the delta silently
        // (we can't recover their content after set_size already ran).
        let _ = (old_sb, new_sb);
    }

    /// Serializes the current screen state as JSON.
    /// Returns `None` if the screen hasn't changed since the last call.
    pub fn screen_to_json(&mut self) -> Option<String> {
        let screen = self.parser.screen();
        let rows = screen.size().0;
        let cols = screen.size().1;

        // Mouse protocol state — needed by Java for mouse-wheel scrolling.
        let mouse_mode: u8 = match screen.mouse_protocol_mode() {
            vt100::MouseProtocolMode::None          => 0,
            vt100::MouseProtocolMode::Press          => 1,
            vt100::MouseProtocolMode::PressRelease   => 2,
            vt100::MouseProtocolMode::ButtonMotion   => 3,
            vt100::MouseProtocolMode::AnyMotion      => 4,
        };
        let mouse_enc: u8 = match screen.mouse_protocol_encoding() {
            vt100::MouseProtocolEncoding::Default => 0,
            vt100::MouseProtocolEncoding::Utf8    => 1,
            vt100::MouseProtocolEncoding::Sgr     => 2,
        };

        // Quick hash to skip unchanged frames.
        let mut hasher = DefaultHasher::new();
        screen.contents().hash(&mut hasher);
        screen.cursor_position().hash(&mut hasher);
        screen.hide_cursor().hash(&mut hasher);
        mouse_mode.hash(&mut hasher);
        mouse_enc.hash(&mut hasher);
        self.scrollback_buf.len().hash(&mut hasher);
        let hash = hasher.finish();
        if hash == self.prev_hash { return None; }
        self.prev_hash = hash;

        let cursor = screen.cursor_position();
        let mut json = String::with_capacity(8192);
        json.push_str("{\"rows\":");
        json.push_str(&rows.to_string());
        json.push_str(",\"cols\":");
        json.push_str(&cols.to_string());
        json.push_str(",\"cy\":");
        json.push_str(&cursor.0.to_string());
        json.push_str(",\"cx\":");
        json.push_str(&cursor.1.to_string());
        json.push_str(",\"cv\":");
        json.push_str(if screen.hide_cursor() { "false" } else { "true" });
        json.push_str(",\"mm\":");
        json.push_str(&mouse_mode.to_string());
        json.push_str(",\"me\":");
        json.push_str(&mouse_enc.to_string());
        json.push_str(",\"alt\":");
        json.push_str(if screen.alternate_screen() { "1" } else { "0" });
        json.push_str(",\"lines\":[");

        for row in 0..rows {
            if row > 0 { json.push(','); }
            json.push_str("{\"t\":\"");

            // Row text (JSON-escaped).
            for col in 0..cols {
                let cell = screen.cell(row, col).unwrap();
                let ch = cell.contents();
                if ch.is_empty() {
                    json.push(' ');
                } else {
                    for c in ch.chars() {
                        match c {
                            '"'  => json.push_str("\\\""),
                            '\\' => json.push_str("\\\\"),
                            c if (c as u32) < 0x20 => {
                                json.push_str(&format!("\\u{:04x}", c as u32));
                            }
                            _ => json.push(c),
                        }
                    }
                }
            }

            json.push_str("\",\"s\":[");

            // Style spans — run-length encoded.
            if cols > 0 {
                let mut span_start = 0u16;
                let mut cur = cell_to_style(screen.cell(row, 0).unwrap());
                let mut first_span = true;

                for col in 1..cols {
                    let next = cell_to_style(screen.cell(row, col).unwrap());
                    if next != cur {
                        if cur.has_style() {
                            if !first_span { json.push(','); }
                            first_span = false;
                            cur.write_span(&mut json, span_start, col - span_start);
                        }
                        span_start = col;
                        cur = next;
                    }
                }
                // Final span.
                if cur.has_style() {
                    if !first_span { json.push(','); }
                    cur.write_span(&mut json, span_start, cols - span_start);
                }
            }

            json.push_str("]}");
        }

        json.push_str("]");

        // Append new scrollback lines (only the ones not yet sent to Java).
        if self.scrollback_buf.len() > self.last_sent_sb {
            json.push_str(",\"nsb\":[");
            for i in self.last_sent_sb..self.scrollback_buf.len() {
                if i > self.last_sent_sb { json.push(','); }
                json.push('"');
                for c in self.scrollback_buf[i].chars() {
                    match c {
                        '"'  => json.push_str("\\\""),
                        '\\' => json.push_str("\\\\"),
                        c if (c as u32) < 0x20 => {
                            json.push_str(&format!("\\u{:04x}", c as u32));
                        }
                        _ => json.push(c),
                    }
                }
                json.push('"');
            }
            json.push(']');
            self.last_sent_sb = self.scrollback_buf.len();
        }

        json.push('}');
        Some(json)
    }
}
