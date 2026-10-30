pub mod buffer;
pub mod cell;
pub mod parser;

pub use buffer::{Row, TerminalBuffer};
pub use cell::{cell_flags, Cell, Color};
pub use parser::{Cursor, TerminalEmulator};

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn test_print_and_scroll() {
        let mut term = TerminalEmulator::new(10, 2, 5);
        term.process_bytes(b"hello");
        assert_eq!(term.cursor.x, 5);
        assert_eq!(term.cursor.y, 0);

        // Check content
        let c = term.buffer.get_cell(0, 0).unwrap();
        assert_eq!(c.ch, 'h');

        // Line feed and print
        term.process_bytes(b"\r\nworld");
        assert_eq!(term.cursor.x, 5);
        assert_eq!(term.cursor.y, 1);

        // One more line feed should cause scroll_up
        term.process_bytes(b"\r\nscroll");
        assert_eq!(term.cursor.y, 1);
        assert_eq!(term.buffer.scrollback_len, 1);
        assert_eq!(term.buffer.scrollback[0].cells[0].ch, 'h');
    }

    #[test]
    fn test_ansi_colors() {
        let mut term = TerminalEmulator::new(20, 5, 5);
        // Red foreground (\x1b[31m), bold (\x1b[1m)
        term.process_bytes(b"\x1b[1;31mHi\x1b[0m");

        let c1 = term.buffer.get_cell(0, 0).unwrap();
        assert_eq!(c1.ch, 'H');
        assert_eq!(c1.fg, Color::Ansi16(1)); // Red
        assert!(c1.has_flag(cell_flags::BOLD));

        let c2 = term.buffer.get_cell(1, 0).unwrap();
        assert_eq!(c2.ch, 'i');
        assert_eq!(c2.fg, Color::Ansi16(1));
        assert!(c2.has_flag(cell_flags::BOLD));
    }

    #[test]
    fn test_view_row_scrolling() {
        let mut term = TerminalEmulator::new(10, 2, 5);
        term.process_bytes(b"line1\r\nline2\r\nline3");
        // Screen has line2 and line3, scrollback has line1
        assert_eq!(term.buffer.scrollback_len, 1);

        // At scroll_offset 0: active screen
        let r0 = term.buffer.get_view_row(0, 0).unwrap();
        assert_eq!(r0.cells[0].ch, 'l');
        assert_eq!(r0.cells[4].ch, '2');

        // At scroll_offset 1: shifted up, r0 is line1
        let r0_scrolled = term.buffer.get_view_row(0, 1).unwrap();
        assert_eq!(r0_scrolled.cells[4].ch, '1');
    }
}

