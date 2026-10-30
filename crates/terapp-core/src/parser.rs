use crate::buffer::TerminalBuffer;
use crate::cell::{cell_flags, Cell, Color};

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
enum ParserState {
    Ground,
    Escape,
    Csi,
}

#[derive(Debug)]
pub struct Cursor {
    pub x: usize,
    pub y: usize,
    pub visible: bool,
}

/// Lightweight ANSI/VT100 parser with zero dynamic memory allocation during stream processing.
#[derive(Debug)]
pub struct TerminalEmulator {
    pub buffer: TerminalBuffer,
    pub cursor: Cursor,
    current_fg: Color,
    current_bg: Color,
    current_flags: u8,
    state: ParserState,
    csi_params: [u16; 16],
    csi_param_count: usize,
    has_param: bool,
    utf8_buf: [u8; 4],
    utf8_len: usize,
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
            current_fg: Color::Default,
            current_bg: Color::Default,
            current_flags: 0,
            state: ParserState::Ground,
            csi_params: [0; 16],
            csi_param_count: 0,
            has_param: false,
            utf8_buf: [0; 4],
            utf8_len: 0,
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
                        self.cursor.x = self.buffer.cols - 1;
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
                }
                _ => {
                    self.state = ParserState::Ground;
                }
            },
            ParserState::Csi => match byte {
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
                b'G' => {
                    // Cursor Horizontal Absolute
                    let col = if self.csi_params[0] > 0 { self.csi_params[0] - 1 } else { 0 } as usize;
                    self.cursor.x = col.min(self.buffer.cols.saturating_sub(1));
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
                    if let Some(row) = self.buffer.screen.get_mut(self.cursor.y) {
                        match param {
                            0 => {
                                // Clear from cursor to end of line
                                for x in self.cursor.x..self.buffer.cols {
                                    if let Some(cell) = row.cells.get_mut(x) {
                                        *cell = Cell::EMPTY;
                                    }
                                }
                            }
                            1 => {
                                // Clear from start of line to cursor
                                for x in 0..=self.cursor.x.min(self.buffer.cols.saturating_sub(1)) {
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
                _ => {
                    // Unhandled or finished sequence
                    self.state = ParserState::Ground;
                }
            },
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

        for i in 0..self.csi_param_count {
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
                22 => self.current_flags &= !(cell_flags::BOLD | cell_flags::DIM),
                23 => self.current_flags &= !cell_flags::ITALIC,
                24 => self.current_flags &= !cell_flags::UNDERLINE,
                27 => self.current_flags &= !cell_flags::INVERSE,
                // Standard Foreground (30-37)
                code @ 30..=37 => {
                    self.current_fg = Color::Ansi16((code - 30) as u8);
                }
                39 => self.current_fg = Color::Default,
                // Standard Background (40-47)
                code @ 40..=47 => {
                    self.current_bg = Color::Ansi16((code - 40) as u8);
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
        }
    }
}

