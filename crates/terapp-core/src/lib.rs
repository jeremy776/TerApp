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

    #[test]
    fn test_alternate_screen_buffer() {
        let mut term = TerminalEmulator::new(10, 2, 5);
        term.process_bytes(b"main screen");
        assert_eq!(term.buffer.get_cell(0, 0).unwrap().ch, 'm');

        // Switch to alternate screen buffer: \x1b[?1049h
        term.process_bytes(b"\x1b[?1049h");
        assert!(term.buffer.is_alt_screen);
        assert_eq!(term.buffer.get_cell(0, 0).unwrap().ch, ' ');

        term.process_bytes(b"alt screen");
        assert_eq!(term.buffer.get_cell(0, 0).unwrap().ch, 'a');

        // Switch back to main screen: \x1b[?1049l
        term.process_bytes(b"\x1b[?1049l");
        assert!(!term.buffer.is_alt_screen);
        assert_eq!(term.buffer.get_cell(0, 0).unwrap().ch, 'm');
    }

    #[test]
    fn test_extended_colors() {
        let mut term = TerminalEmulator::new(20, 2, 5);
        // 256 colors foreground: \x1b[38;5;196m
        term.process_bytes(b"\x1b[38;5;196mA");
        let c = term.buffer.get_cell(0, 0).unwrap();
        assert_eq!(c.ch, 'A');
        assert_eq!(c.fg, Color::Ansi256(196));

        // TrueColor RGB foreground: \x1b[38;2;12;34;56m
        term.process_bytes(b"\x1b[38;2;12;34;56mB");
        let c2 = term.buffer.get_cell(1, 0).unwrap();
        assert_eq!(c2.ch, 'B');
        assert_eq!(c2.fg, Color::Rgb(12, 34, 56));
    }

    #[test]
    fn test_cursor_save_restore_and_title() {
        let mut term = TerminalEmulator::new(20, 5, 5);
        term.process_bytes(b"Hello");
        assert_eq!(term.cursor.x, 5);

        // Save cursor with \x1b7
        term.process_bytes(b"\x1b7");
        term.process_bytes(b"\r\nNew Line");
        assert_eq!(term.cursor.y, 1);

        // Restore cursor with \x1b8
        term.process_bytes(b"\x1b8");
        assert_eq!(term.cursor.x, 5);
        assert_eq!(term.cursor.y, 0);

        // OSC 0;MyTitle\x07
        term.process_bytes(b"\x1b]0;My Terminal\x07");
        assert_eq!(term.title, "My Terminal");

        // Bell character \x07
        term.process_bytes(b"\x07");
        assert_eq!(term.bell_count, 1);
    }
}

