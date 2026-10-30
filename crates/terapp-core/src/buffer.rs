use crate::cell::Cell;

/// A single row in the terminal.
#[derive(Clone, Debug)]
pub struct Row {
    pub cells: Vec<Cell>,
}

impl Row {
    pub fn new(cols: usize) -> Self {
        Self {
            cells: vec![Cell::EMPTY; cols],
        }
    }

    pub fn resize(&mut self, cols: usize) {
        self.cells.resize(cols, Cell::EMPTY);
    }

    pub fn clear(&mut self) {
        for cell in self.cells.iter_mut() {
            *cell = Cell::EMPTY;
        }
    }
}

/// High-performance, low-memory terminal buffer.
/// Uses a circular line buffer for scrollback history to prevent memory fragmentation.
#[derive(Debug)]
pub struct TerminalBuffer {
    pub cols: usize,
    pub rows: usize,
    pub max_scrollback: usize,
    /// Ring buffer storing lines scrolled off screen
    pub scrollback: Vec<Row>,
    pub scrollback_start: usize,
    pub scrollback_len: usize,
    /// The currently visible screen rows
    pub screen: Vec<Row>,
}

impl TerminalBuffer {
    pub fn new(cols: usize, rows: usize, max_scrollback: usize) -> Self {
        let mut screen = Vec::with_capacity(rows);
        for _ in 0..rows {
            screen.push(Row::new(cols));
        }

        Self {
            cols,
            rows,
            max_scrollback,
            scrollback: Vec::new(),
            scrollback_start: 0,
            scrollback_len: 0,
            screen,
        }
    }

    pub fn get_cell(&self, col: usize, row: usize) -> Option<&Cell> {
        if row < self.rows && col < self.cols {
            self.screen.get(row).and_then(|r| r.cells.get(col))
        } else {
            None
        }
    }

    pub fn get_cell_mut(&mut self, col: usize, row: usize) -> Option<&mut Cell> {
        if row < self.rows && col < self.cols {
            self.screen.get_mut(row).and_then(|r| r.cells.get_mut(col))
        } else {
            None
        }
    }

    /// Scroll up by 1 line (e.g. when typing at the bottom of the screen).
    /// Pushes top visible line into scrollback history.
    pub fn scroll_up(&mut self) {
        if self.rows == 0 {
            return;
        }

        let scrolled_row = self.screen.remove(0);

        // Manage ring buffer for scrollback
        if self.max_scrollback > 0 {
            if self.scrollback.len() < self.max_scrollback {
                self.scrollback.push(scrolled_row);
                self.scrollback_len = self.scrollback.len();
            } else {
                // Overwrite oldest line in circular buffer without reallocation
                self.scrollback[self.scrollback_start] = scrolled_row;
                self.scrollback_start = (self.scrollback_start + 1) % self.max_scrollback;
            }
        }

        // Add fresh blank line at bottom
        self.screen.push(Row::new(self.cols));
    }

    pub fn clear_screen(&mut self) {
        for row in self.screen.iter_mut() {
            row.clear();
        }
    }

    /// Retrieve a row for rendering at screen line `y` given a scrollback offset.
    /// `scroll_offset = 0` shows active screen.
    /// `scroll_offset > 0` scrolls upwards into past scrollback history.
    pub fn get_view_row(&self, y: usize, scroll_offset: usize) -> Option<&Row> {
        if y >= self.rows {
            return None;
        }

        let clamped_offset = scroll_offset.min(self.scrollback_len);
        let virtual_idx = y + (self.scrollback_len - clamped_offset);

        if virtual_idx < self.scrollback_len {
            // From circular scrollback history
            if self.max_scrollback == 0 {
                return None;
            }
            let physical_idx = (self.scrollback_start + virtual_idx) % self.max_scrollback;
            self.scrollback.get(physical_idx)
        } else {
            // From active screen
            let screen_idx = virtual_idx - self.scrollback_len;
            self.screen.get(screen_idx)
        }
    }

    pub fn resize(&mut self, new_cols: usize, new_rows: usize) {
        self.cols = new_cols;
        self.rows = new_rows;

        // Resize existing rows
        for row in self.screen.iter_mut() {
            row.resize(new_cols);
        }

        // Adjust number of rows
        if self.screen.len() < new_rows {
            while self.screen.len() < new_rows {
                self.screen.push(Row::new(new_cols));
            }
        } else if self.screen.len() > new_rows {
            self.screen.truncate(new_rows);
        }
    }
}

