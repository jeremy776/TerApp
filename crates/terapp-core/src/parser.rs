use crate::buffer::TerminalBuffer;
use crate::cell::{cell_flags, Cell, Color};

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
enum ParserState {
    Ground,
    Escape,
    Csi,
    Osc,
}

#[derive(Debug, Clone)]
pub struct Cursor {
    pub x: usize,
    pub y: usize,
    pub visible: bool,
}

/// High-performance ANSI/VT100/xterm emulator with zero dynamic memory allocation
/// during normal byte stream processing.
#[derive(Debug)]
pub struct TerminalEmulator {
    pub buffer: TerminalBuffer,
    pub cursor: Cursor,
    saved_cursor: (usize, usize),
    pub title: String,
    pub bell_count: u32,
    current_fg: Color,
    current_bg: Color,
    current_flags: u8,
    state: ParserState,
    csi_params: [u16; 16],
    csi_param_count: usize,
    has_param: bool,
    is_dec_private: bool,
    utf8_buf: [u8; 4],
    utf8_len: usize,
    osc_buf: Vec<u8>,
}

impl TerminalEmulator {
    pub fn new(cols: usize, rows: usize, max_scrollback: usize) -> Self {
        Self {
            buffer: TerminalBuffer::new(cols, rows, max_scrollback),
            cursor: Cursor {
                x: 0,
                y: 0,
                visible: true,
            },
            saved_cursor: (0, 0),
            title: String::new(),
            bell_count: 0,
            current_fg: Color::Default,
            current_bg: Color::Default,
            current_flags: 0,
            state: ParserState::Ground,
            csi_params: [0; 16],
            csi_param_count: 0,
            has_param: false,
            is_dec_private: false,
            utf8_buf: [0; 4],
            utf8_len: 0,
            osc_buf: Vec::with_capacity(128),
        }
    }

    pub fn resize(&mut self, new_cols: usize, new_rows: usize) {
        self.buffer.resize(new_cols, new_rows);
        if self.cursor.x >= new_cols {
            self.cursor.x = new_cols.saturating_sub(1);
        }
        if self.cursor.y >= new_rows {
            self.cursor.y = new_rows.saturating_sub(1);
        }
    }

    /// Process a slice of raw bytes received from the PTY/shell stream
    pub fn process_bytes(&mut self, data: &[u8]) {
        for &byte in data {
            self.process_byte(byte);
        }
    }

    pub fn process_byte(&mut self, byte: u8) {
        match self.state {
            ParserState::Ground => match byte {
                b'\x1b' => {
                    self.state = ParserState::Escape;
                }
                b'\x07' => {
                    // BEL character
                    self.bell_count = self.bell_count.wrapping_add(1);
                }
                b'\r' => {
                    self.cursor.x = 0;
                }
                b'\n' => {
                    self.line_feed();
                }
                b'\x08' => {
                    // Backspace
                    if self.cursor.x > 0 {
                        self.cursor.x -= 1;
                    }
                }
                b'\t' => {
                    // Tab stop (every 8 columns)
                    self.cursor.x = (self.cursor.x + 8) & !7;
                    if self.cursor.x >= self.buffer.cols {
                        self.cursor.x = self.buffer.cols.saturating_sub(1);
                    }
                }
                0x20..=0x7E => {
                    // Printable ASCII
                    self.utf8_len = 0;
                    self.print_char(byte as char);
                }
                0x80..=0xFF => {
                    // Multi-byte UTF-8
                    if self.utf8_len < 4 {
                        self.utf8_buf[self.utf8_len] = byte;
                        self.utf8_len += 1;
                        if let Ok(s) = std::str::from_utf8(&self.utf8_buf[..self.utf8_len]) {
                            if let Some(ch) = s.chars().next() {
                                self.print_char(ch);
                                self.utf8_len = 0;
                            }
                        }
                    } else {
                        self.utf8_len = 0;
                    }
                }
                _ => {}
            },
            ParserState::Escape => match byte {
                b'[' => {
                    self.state = ParserState::Csi;
                    self.csi_param_count = 0;
                    self.csi_params = [0; 16];
                    self.has_param = false;
                    self.is_dec_private = false;
                }
                b']' => {
                    self.state = ParserState::Osc;
                    self.osc_buf.clear();
                }
                b'7' => {
                    // Save Cursor (DECSC)
                    self.saved_cursor = (self.cursor.x, self.cursor.y);
                    self.state = ParserState::Ground;
                }
                b'8' => {
                    // Restore Cursor (DECRC)
                    self.cursor.x = self.saved_cursor.0.min(self.buffer.cols.saturating_sub(1));
                    self.cursor.y = self.saved_cursor.1.min(self.buffer.rows.saturating_sub(1));
                    self.state = ParserState::Ground;
                }
                b'M' => {
                    // Reverse Index (RI): move cursor up 1 line, or scroll down if at top
                    if self.cursor.y == 0 {
                        self.buffer.scroll_down();
                    } else {
                        self.cursor.y -= 1;
                    }
                    self.state = ParserState::Ground;
                }
                _ => {
                    self.state = ParserState::Ground;
                }
            },
            ParserState::Csi => match byte {
                b'?' => {
                    self.is_dec_private = true;
                }
                b'0'..=b'9' => {
                    let digit = (byte - b'0') as u16;
                    if self.csi_param_count < self.csi_params.len() {
                        self.csi_params[self.csi_param_count] =
                            self.csi_params[self.csi_param_count] * 10 + digit;
                        self.has_param = true;
                    }
                }
                b';' => {
                    if self.csi_param_count + 1 < self.csi_params.len() {
                        self.csi_param_count += 1;
                        self.has_param = false;
                    }
                }
                b'm' => {
                    // SGR (Select Graphic Rendition) - Colors & Styles
                    if self.has_param {
                        self.csi_param_count += 1;
                    }
                    self.handle_sgr();
                    self.state = ParserState::Ground;
                }
                b'h' | b'l' => {
                    let enable = byte == b'h';
                    if self.is_dec_private {
                        let param = self.csi_params[0];
                        match param {
                            1049 | 47 => {
                                // Alternate screen buffer switch
                                if enable {
                                    self.saved_cursor = (self.cursor.x, self.cursor.y);
                                    self.buffer.switch_to_alt_screen();
                                    self.cursor.x = 0;
                                    self.cursor.y = 0;
                                } else {
                                    self.buffer.switch_to_main_screen();
                                    self.cursor.x = self.saved_cursor.0.min(self.buffer.cols.saturating_sub(1));
                                    self.cursor.y = self.saved_cursor.1.min(self.buffer.rows.saturating_sub(1));
                                }
                            }
                            25 => {
                                // Show / Hide cursor
                                self.cursor.visible = enable;
                            }
                            _ => {}
                        }
                    }
                    self.state = ParserState::Ground;
                }
                b'A' => {
                    // Cursor Up
                    let count = self.csi_params[0].max(1) as usize;
                    self.cursor.y = self.cursor.y.saturating_sub(count);
                    self.state = ParserState::Ground;
                }
                b'B' => {
                    // Cursor Down
                    let count = self.csi_params[0].max(1) as usize;
                    self.cursor.y = (self.cursor.y + count).min(self.buffer.rows.saturating_sub(1));
                    self.state = ParserState::Ground;
                }
                b'C' => {
                    // Cursor Forward / Right
                    let count = self.csi_params[0].max(1) as usize;
                    self.cursor.x = (self.cursor.x + count).min(self.buffer.cols.saturating_sub(1));
                    self.state = ParserState::Ground;
                }
                b'D' => {
                    // Cursor Backward / Left
                    let count = self.csi_params[0].max(1) as usize;
                    self.cursor.x = self.cursor.x.saturating_sub(count);
                    self.state = ParserState::Ground;
                }
                b'E' => {
                    // Cursor Next Line
                    let count = self.csi_params[0].max(1) as usize;
                    self.cursor.y = (self.cursor.y + count).min(self.buffer.rows.saturating_sub(1));
                    self.cursor.x = 0;
                    self.state = ParserState::Ground;
                }
                b'F' => {
                    // Cursor Preceding Line
                    let count = self.csi_params[0].max(1) as usize;
                    self.cursor.y = self.cursor.y.saturating_sub(count);
                    self.cursor.x = 0;
                    self.state = ParserState::Ground;
                }
                b'G' => {
                    // Cursor Horizontal Absolute
                    let col = if self.csi_params[0] > 0 { self.csi_params[0] - 1 } else { 0 } as usize;
                    self.cursor.x = col.min(self.buffer.cols.saturating_sub(1));
                    self.state = ParserState::Ground;
                }
                b'd' => {
                    // Line Position Absolute (VPA)
                    let line = if self.csi_params[0] > 0 { self.csi_params[0] - 1 } else { 0 } as usize;
                    self.cursor.y = line.min(self.buffer.rows.saturating_sub(1));
                    self.state = ParserState::Ground;
                }
                b'H' | b'f' => {
                    // Cursor Position: CSI [row] ; [col] H
                    let row = if self.csi_params[0] > 0 {
                        (self.csi_params[0] - 1) as usize
                    } else {
                        0
                    };
                    let col = if self.csi_param_count > 0 && self.csi_params[1] > 0 {
                        (self.csi_params[1] - 1) as usize
                    } else {
                        0
                    };
                    self.cursor.y = row.min(self.buffer.rows.saturating_sub(1));
                    self.cursor.x = col.min(self.buffer.cols.saturating_sub(1));
                    self.state = ParserState::Ground;
                }
                b'J' => {
                    // Erase in Display
                    let param = self.csi_params[0];
                    if param == 2 || param == 3 {
                        self.buffer.clear_screen();
                        self.cursor.x = 0;
                        self.cursor.y = 0;
                    }
                    self.state = ParserState::Ground;
                }
                b'K' => {
                    // Erase in Line
                    let param = self.csi_params[0];
                    let cols = self.buffer.cols;
                    let cursor_x = self.cursor.x;
                    if let Some(row) = self.buffer.active_screen_mut().get_mut(self.cursor.y) {
                        match param {
                            0 => {
                                // Clear from cursor to end of line
                                for x in cursor_x..cols {
                                    if let Some(cell) = row.cells.get_mut(x) {
                                        *cell = Cell::EMPTY;
                                    }
                                }
                            }
                            1 => {
                                // Clear from start of line to cursor
                                for x in 0..=cursor_x.min(cols.saturating_sub(1)) {
                                    if let Some(cell) = row.cells.get_mut(x) {
                                        *cell = Cell::EMPTY;
                                    }
                                }
                            }
                            2 => {
                                // Clear entire line
                                for cell in row.cells.iter_mut() {
                                    *cell = Cell::EMPTY;
                                }
                            }
                            _ => {}
                        }
                    }
                    self.state = ParserState::Ground;
                }
                b'L' => {
                    // Insert Line(s)
                    let count = self.csi_params[0].max(1) as usize;
                    self.buffer.insert_lines(self.cursor.y, count);
                    self.state = ParserState::Ground;
                }
                b'M' => {
                    // Delete Line(s)
                    let count = self.csi_params[0].max(1) as usize;
                    self.buffer.delete_lines(self.cursor.y, count);
                    self.state = ParserState::Ground;
                }
                b'@' => {
                    // Insert Character(s)
                    let count = self.csi_params[0].max(1) as usize;
                    self.buffer.insert_cells(self.cursor.y, self.cursor.x, count);
                    self.state = ParserState::Ground;
                }
                b'P' => {
                    // Delete Character(s)
                    let count = self.csi_params[0].max(1) as usize;
                    self.buffer.delete_cells(self.cursor.y, self.cursor.x, count);
                    self.state = ParserState::Ground;
                }
                b'X' => {
                    // Erase Character(s)
                    let count = self.csi_params[0].max(1) as usize;
                    self.buffer.erase_cells(self.cursor.y, self.cursor.x, count);
                    self.state = ParserState::Ground;
                }
                b'S' => {
                    // Scroll Up
                    let count = self.csi_params[0].max(1) as usize;
                    for _ in 0..count {
                        self.buffer.scroll_up();
                    }
                    self.state = ParserState::Ground;
                }
                b'T' => {
                    // Scroll Down
                    let count = self.csi_params[0].max(1) as usize;
                    for _ in 0..count {
                        self.buffer.scroll_down();
                    }
                    self.state = ParserState::Ground;
                }
                b's' => {
                    // Save Cursor (ANSI)
                    self.saved_cursor = (self.cursor.x, self.cursor.y);
                    self.state = ParserState::Ground;
                }
                b'u' => {
                    // Restore Cursor (ANSI)
                    self.cursor.x = self.saved_cursor.0.min(self.buffer.cols.saturating_sub(1));
                    self.cursor.y = self.saved_cursor.1.min(self.buffer.rows.saturating_sub(1));
                    self.state = ParserState::Ground;
                }
                _ => {
                    // Unhandled or finished sequence
                    self.state = ParserState::Ground;
                }
            },
            ParserState::Osc => {
                if byte == b'\x07' || byte == b'\x1b' {
                    // End of OSC string
                    if let Ok(osc_str) = std::str::from_utf8(&self.osc_buf) {
                        // OSC 0;title or OSC 2;title sets terminal window title
                        if osc_str.starts_with("0;") || osc_str.starts_with("2;") {
                            let title_part = &osc_str[2..];
                            self.title = title_part.to_string();
                        }
                    }
                    self.state = ParserState::Ground;
                } else if self.osc_buf.len() < 256 {
                    self.osc_buf.push(byte);
                }
            }
        }
    }

    fn print_char(&mut self, ch: char) {
        if self.cursor.x >= self.buffer.cols {
            self.cursor.x = 0;
            self.line_feed();
        }

        if let Some(cell) = self.buffer.get_cell_mut(self.cursor.x, self.cursor.y) {
            cell.ch = ch;
            cell.fg = self.current_fg;
            cell.bg = self.current_bg;
            cell.flags = self.current_flags;
        }

        self.cursor.x += 1;
    }

    fn line_feed(&mut self) {
        if self.cursor.y + 1 < self.buffer.rows {
            self.cursor.y += 1;
        } else {
            self.buffer.scroll_up();
        }
    }

    fn handle_sgr(&mut self) {
        if self.csi_param_count == 0 {
            // Reset
            self.current_fg = Color::Default;
            self.current_bg = Color::Default;
            self.current_flags = 0;
            return;
        }

        let mut i = 0;
        while i < self.csi_param_count {
            match self.csi_params[i] {
                0 => {
                    self.current_fg = Color::Default;
                    self.current_bg = Color::Default;
                    self.current_flags = 0;
                }
                1 => self.current_flags |= cell_flags::BOLD,
                2 => self.current_flags |= cell_flags::DIM,
                3 => self.current_flags |= cell_flags::ITALIC,
                4 => self.current_flags |= cell_flags::UNDERLINE,
                7 => self.current_flags |= cell_flags::INVERSE,
                8 => self.current_flags |= cell_flags::HIDDEN,
                9 => self.current_flags |= cell_flags::STRIKETHROUGH,
                22 => self.current_flags &= !(cell_flags::BOLD | cell_flags::DIM),
                23 => self.current_flags &= !cell_flags::ITALIC,
                24 => self.current_flags &= !cell_flags::UNDERLINE,
                27 => self.current_flags &= !cell_flags::INVERSE,
                28 => self.current_flags &= !cell_flags::HIDDEN,
                29 => self.current_flags &= !cell_flags::STRIKETHROUGH,
                // Standard Foreground (30-37)
                code @ 30..=37 => {
                    self.current_fg = Color::Ansi16((code - 30) as u8);
                }
                // Extended Foreground: 38;5;N (256 color) or 38;2;R;G;B (TrueColor)
                38 => {
                    if i + 2 < self.csi_param_count && self.csi_params[i + 1] == 5 {
                        self.current_fg = Color::Ansi256(self.csi_params[i + 2] as u8);
                        i += 2;
                    } else if i + 4 < self.csi_param_count && self.csi_params[i + 1] == 2 {
                        let r = self.csi_params[i + 2] as u8;
                        let g = self.csi_params[i + 3] as u8;
                        let b = self.csi_params[i + 4] as u8;
                        self.current_fg = Color::Rgb(r, g, b);
                        i += 4;
                    }
                }
                39 => self.current_fg = Color::Default,
                // Standard Background (40-47)
                code @ 40..=47 => {
                    self.current_bg = Color::Ansi16((code - 40) as u8);
                }
                // Extended Background: 48;5;N (256 color) or 48;2;R;G;B (TrueColor)
                48 => {
                    if i + 2 < self.csi_param_count && self.csi_params[i + 1] == 5 {
                        self.current_bg = Color::Ansi256(self.csi_params[i + 2] as u8);
                        i += 2;
                    } else if i + 4 < self.csi_param_count && self.csi_params[i + 1] == 2 {
                        let r = self.csi_params[i + 2] as u8;
                        let g = self.csi_params[i + 3] as u8;
                        let b = self.csi_params[i + 4] as u8;
                        self.current_bg = Color::Rgb(r, g, b);
                        i += 4;
                    }
                }
                49 => self.current_bg = Color::Default,
                // Bright Foreground (90-97)
                code @ 90..=97 => {
                    self.current_fg = Color::Ansi16((code - 90 + 8) as u8);
                }
                // Bright Background (100-107)
                code @ 100..=107 => {
                    self.current_bg = Color::Ansi16((code - 100 + 8) as u8);
                }
                _ => {}
            }
            i += 1;
        }
    }
}
