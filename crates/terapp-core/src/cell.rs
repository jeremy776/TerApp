/// Represents a compact color representation to keep memory usage minimal.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum Color {
    Default,
    Ansi16(u8),       // 0-15 standard terminal colors
    Ansi256(u8),      // 0-255 extended xterm colors
    Rgb(u8, u8, u8),  // TrueColor 24-bit fallback
}

impl Color {
    /// Converts color to a 32-bit ARGB integer given the default foreground/background
    /// and a 16-color ANSI palette.
    pub fn to_argb(&self, default_color: u32, palette16: &[u32; 16]) -> u32 {
        match *self {
            Color::Default => default_color,
            Color::Ansi16(idx) => {
                let i = (idx as usize).min(15);
                palette16[i]
            }
            Color::Ansi256(idx) => {
                if idx < 16 {
                    palette16[idx as usize]
                } else if idx < 232 {
                    // 6x6x6 color cube
                    let mut code = idx - 16;
                    let b = code % 6;
                    code /= 6;
                    let g = code % 6;
                    let r = code / 6;
                    let r_val = if r == 0 { 0 } else { 55 + r * 40 } as u32;
                    let g_val = if g == 0 { 0 } else { 55 + g * 40 } as u32;
                    let b_val = if b == 0 { 0 } else { 55 + b * 40 } as u32;
                    0xFF000000 | (r_val << 16) | (g_val << 8) | b_val
                } else {
                    // 24 grayscale levels from 232 to 255
                    let gray = (8 + (idx - 232) * 10) as u32;
                    0xFF000000 | (gray << 16) | (gray << 8) | gray
                }
            }
            Color::Rgb(r, g, b) => {
                0xFF000000 | ((r as u32) << 16) | ((g as u32) << 8) | (b as u32)
            }
        }
    }
}

pub mod cell_flags {
    pub const BOLD: u8 = 1 << 0;
    pub const DIM: u8 = 1 << 1;
    pub const ITALIC: u8 = 1 << 2;
    pub const UNDERLINE: u8 = 1 << 3;
    pub const INVERSE: u8 = 1 << 4;
    pub const HIDDEN: u8 = 1 << 5;
    pub const STRIKETHROUGH: u8 = 1 << 6;
}

/// A compact terminal grid cell.
/// Kept minimal to optimize cache locality and reduce memory consumption.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub struct Cell {
    pub ch: char,
    pub fg: Color,
    pub bg: Color,
    pub flags: u8,
}

impl Cell {
    pub const EMPTY: Self = Self {
        ch: ' ',
        fg: Color::Default,
        bg: Color::Default,
        flags: 0,
    };

    #[inline(always)]
    pub fn new(ch: char) -> Self {
        Self {
            ch,
            fg: Color::Default,
            bg: Color::Default,
            flags: 0,
        }
    }

    #[inline(always)]
    pub fn is_empty(&self) -> bool {
        self.ch == ' ' && self.bg == Color::Default && self.flags == 0
    }

    #[inline(always)]
    pub fn has_flag(&self, flag: u8) -> bool {
        (self.flags & flag) != 0
    }

    #[inline(always)]
    pub fn set_flag(&mut self, flag: u8, enable: bool) {
        if enable {
            self.flags |= flag;
        } else {
            self.flags &= !flag;
        }
    }
}

impl Default for Cell {
    fn default() -> Self {
        Self::EMPTY
    }
}

