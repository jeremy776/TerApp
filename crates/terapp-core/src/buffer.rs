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
    /// The currently visible screen rows (primary buffer)
    pub screen: Vec<Row>,
    /// Alternate screen buffer (for full-screen apps like vi, nano, top, less)
    pub alt_screen: Vec<Row>,
    /// Whether the alternate screen buffer is active
    pub is_alt_screen: bool,
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
            alt_screen: Vec::new(),
            is_alt_screen: false,
        }
    }

    #[inline]
    pub fn active_screen(&self) -> &Vec<Row> {
        if self.is_alt_screen {
            &self.alt_screen
        } else {
            &self.screen
        }
    }

    #[inline]
    pub fn active_screen_mut(&mut self) -> &mut Vec<Row> {
        if self.is_alt_screen {
            &mut self.alt_screen
        } else {
            &mut self.screen
        }
    }

    pub fn switch_to_alt_screen(&mut self) {
        if !self.is_alt_screen {
            self.alt_screen.clear();
            for _ in 0..self.rows {
                self.alt_screen.push(Row::new(self.cols));
            }
            self.is_alt_screen = true;
        }
    }

    pub fn switch_to_main_screen(&mut self) {
        if self.is_alt_screen {
            self.is_alt_screen = false;
            self.alt_screen.clear();
        }
    }

    pub fn get_cell(&self, col: usize, row: usize) -> Option<&Cell> {
        if row < self.rows && col < self.cols {
            self.active_screen().get(row).and_then(|r| r.cells.get(col))
        } else {
            None
        }
    }

    pub fn get_cell_mut(&mut self, col: usize, row: usize) -> Option<&mut Cell> {
        if row < self.rows && col < self.cols {
            self.active_screen_mut().get_mut(row).and_then(|r| r.cells.get_mut(col))
        } else {
            None
        }
    }

    /// Scroll up by 1 line (e.g. when typing at the bottom of the screen).
    /// Pushes top visible line into scrollback history when on primary screen.
    pub fn scroll_up(&mut self) {
        if self.rows == 0 {
            return;
        }

        if self.is_alt_screen {
            if !self.alt_screen.is_empty() {
                self.alt_screen.remove(0);
                self.alt_screen.push(Row::new(self.cols));
            }
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

    /// Scroll down by 1 line (moves lines down, empty row inserted at top).
    pub fn scroll_down(&mut self) {
        if self.rows == 0 {
            return;
        }
        let cols = self.cols;
        let screen = self.active_screen_mut();
        if !screen.is_empty() {
            screen.pop();
            screen.insert(0, Row::new(cols));
        }
    }

    pub fn insert_lines(&mut self, start_y: usize, count: usize) {
        if start_y >= self.rows || count == 0 {
            return;
        }
        let cols = self.cols;
        let screen = self.active_screen_mut();
        let rows = screen.len();
        let actual_count = count.min(rows.saturating_sub(start_y));
        for _ in 0..actual_count {
            if screen.len() > start_y {
                screen.pop();
                screen.insert(start_y, Row::new(cols));
            }
        }
    }

    pub fn delete_lines(&mut self, start_y: usize, count: usize) {
        if start_y >= self.rows || count == 0 {
            return;
        }
        let cols = self.cols;
        let screen = self.active_screen_mut();
        let rows = screen.len();
        let actual_count = count.min(rows.saturating_sub(start_y));
        for _ in 0..actual_count {
            if start_y < screen.len() {
                screen.remove(start_y);
                screen.push(Row::new(cols));
            }
        }
    }

    pub fn insert_cells(&mut self, y: usize, x: usize, count: usize) {
        if y >= self.rows || x >= self.cols || count == 0 {
            return;
        }
        let cols = self.cols;
        let screen = self.active_screen_mut();
        if let Some(row) = screen.get_mut(y) {
            for _ in 0..count {
                if x < row.cells.len() {
                    row.cells.insert(x, Cell::EMPTY);
                    row.cells.truncate(cols);
                }
            }
        }
    }

    pub fn delete_cells(&mut self, y: usize, x: usize, count: usize) {
        if y >= self.rows || x >= self.cols || count == 0 {
            return;
        }
        let cols = self.cols;
        let screen = self.active_screen_mut();
        if let Some(row) = screen.get_mut(y) {
            for _ in 0..count {
                if x < row.cells.len() {
                    row.cells.remove(x);
                    row.cells.push(Cell::EMPTY);
                }
            }
            row.cells.truncate(cols);
        }
    }

    pub fn erase_cells(&mut self, y: usize, x: usize, count: usize) {
        if y >= self.rows || x >= self.cols || count == 0 {
            return;
        }
        let cols = self.cols;
        let screen = self.active_screen_mut();
        if let Some(row) = screen.get_mut(y) {
            let end = (x + count).min(cols);
            for col in x..end {
                if let Some(cell) = row.cells.get_mut(col) {
                    *cell = Cell::EMPTY;
                }
            }
        }
    }

    pub fn clear_screen(&mut self) {
        for row in self.active_screen_mut().iter_mut() {
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

        if self.is_alt_screen {
            return self.alt_screen.get(y);
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
        for row in self.alt_screen.iter_mut() {
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

        if !self.alt_screen.is_empty() {
            if self.alt_screen.len() < new_rows {
                while self.alt_screen.len() < new_rows {
                    self.alt_screen.push(Row::new(new_cols));
                }
            } else if self.alt_screen.len() > new_rows {
                self.alt_screen.truncate(new_rows);
            }
        }
    }
}

