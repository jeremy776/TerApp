/// Represents a compact color representation to keep memory usage minimal.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum Color {
    Default,
    Ansi16(u8),       // 0-15 standard terminal colors
    Rgb(u8, u8, u8),  // TrueColor fallback
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

