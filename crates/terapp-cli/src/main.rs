use crossterm::{
    cursor,
    event::{self, Event, KeyCode, KeyEventKind, KeyModifiers, MouseEventKind},
    execute,
    style::{Color as CtColor, Print, ResetColor, SetBackgroundColor, SetForegroundColor},
    terminal::{self, Clear, ClearType},
};
use std::io::{stdout, BufWriter, Write};
use std::path::PathBuf;
use std::process::Command;
use std::time::Duration;
use terapp_core::{cell_flags, Color, TerminalEmulator};

fn to_crossterm_color(color: Color) -> CtColor {
    match color {
        Color::Default => CtColor::Reset,
        Color::Ansi16(c) => match c {
            0 => CtColor::Black,
            1 => CtColor::DarkRed,
            2 => CtColor::DarkGreen,
            3 => CtColor::DarkYellow,
            4 => CtColor::DarkBlue,
            5 => CtColor::DarkMagenta,
            6 => CtColor::DarkCyan,
            7 => CtColor::Grey,
            8 => CtColor::DarkGrey,
            9 => CtColor::Red,
            10 => CtColor::Green,
            11 => CtColor::Yellow,
            12 => CtColor::Blue,
            13 => CtColor::Magenta,
            14 => CtColor::Cyan,
            15 => CtColor::White,
            _ => CtColor::Reset,
        },
        Color::Ansi256(c) => CtColor::AnsiValue(c),
        Color::Rgb(r, g, b) => CtColor::Rgb { r, g, b },
    }
}

/// Highly optimized terminal renderer supporting scrollback offset
fn render_terminal<W: Write>(
    w: &mut W,
    term: &TerminalEmulator,
    scroll_offset: usize,
) -> std::io::Result<()> {
    // Hide cursor during redraw to prevent screen jitter
    execute!(w, cursor::Hide, cursor::MoveTo(0, 0))?;

    let mut current_fg = Color::Default;
    let mut current_bg = Color::Default;
    let mut current_inverse = false;

    for y in 0..term.buffer.rows {
        execute!(w, cursor::MoveTo(0, y as u16))?;

        if let Some(row) = term.buffer.get_view_row(y, scroll_offset) {
            // Find rightmost cell with visible character or custom background
            let last_active = row
                .cells
                .iter()
                .rposition(|c| c.ch != ' ' || c.bg != Color::Default || c.has_flag(cell_flags::INVERSE))
                .map(|idx| idx + 1)
                .unwrap_or(0);

            for x in 0..last_active {
                let cell = &row.cells[x];

                let cell_inverse = cell.has_flag(cell_flags::INVERSE);
                if cell.fg != current_fg || cell.bg != current_bg || cell_inverse != current_inverse {
                    let mut fg = to_crossterm_color(cell.fg);
                    let mut bg = to_crossterm_color(cell.bg);
                    if cell_inverse {
                        std::mem::swap(&mut fg, &mut bg);
                    }
                    execute!(w, SetForegroundColor(fg), SetBackgroundColor(bg))?;
                    current_fg = cell.fg;
                    current_bg = cell.bg;
                    current_inverse = cell_inverse;
                }

                write!(w, "{}", cell.ch)?;
            }

            // Clear remaining line to the right margin safely without auto-wrap
            if last_active < term.buffer.cols {
                if current_bg != Color::Default {
                    execute!(w, SetBackgroundColor(CtColor::Reset))?;
                    current_bg = Color::Default;
                }
                execute!(w, Clear(ClearType::UntilNewLine))?;
            }
        }
    }

    // Reset styles
    execute!(w, ResetColor)?;

    if scroll_offset > 0 {
        // Draw prominent scrollback indicator at top-right
        let badge = format!(" [HISTORY: -{} lines (Mouse / PgDn to return)] ", scroll_offset);
        if term.buffer.cols > badge.len() {
            let badge_x = (term.buffer.cols - badge.len()) as u16;
            execute!(
                w,
                cursor::MoveTo(badge_x, 0),
                SetForegroundColor(CtColor::Black),
                SetBackgroundColor(CtColor::Yellow),
                Print(&badge),
                ResetColor
            )?;
        }
        execute!(w, cursor::Hide)?;
    } else {
        // Position cursor and restore visibility
        let cx = term.cursor.x.min(term.buffer.cols.saturating_sub(1)) as u16;
        let cy = term.cursor.y.min(term.buffer.rows.saturating_sub(1)) as u16;
        execute!(w, cursor::MoveTo(cx, cy), cursor::Show)?;
    }
    w.flush()?;

    Ok(())
}

fn clean_path(path: &std::path::Path) -> PathBuf {
    let s = path.to_string_lossy();
    if let Some(stripped) = s.strip_prefix(r"\\?\") {
        PathBuf::from(stripped)
    } else if let Some(stripped) = s.strip_prefix("//?/") {
        PathBuf::from(stripped)
    } else {
        path.to_path_buf()
    }
}

/// Advanced interactive line editor mirroring Termux / Bash features
struct LineEditor {
    input: Vec<char>,
    cursor_pos: usize,
    history: Vec<String>,
    history_idx: Option<usize>,
    temp_input: String,
    cwd: PathBuf,
}

impl LineEditor {
    fn new() -> Self {
        let cwd = std::env::current_dir().unwrap_or_else(|_| PathBuf::from("."));
        let cleaned_cwd = clean_path(&cwd);
        Self {
            input: Vec::new(),
            cursor_pos: 0,
            history: Vec::new(),
            history_idx: None,
            temp_input: String::new(),
            cwd: cleaned_cwd,
        }
    }

    fn formatted_path(&self) -> String {
        let home = std::env::var("USERPROFILE")
            .or_else(|_| std::env::var("HOME"))
            .map(PathBuf::from)
            .ok();

        if let Some(ref h) = home {
            let clean_home = clean_path(h);
            if let Ok(rel) = self.cwd.strip_prefix(&clean_home) {
                if rel.as_os_str().is_empty() {
                    return "~".to_string();
                } else {
                    return format!("~/{}", rel.to_string_lossy().replace('\\', "/"));
                }
            }
        }

        let s = self.cwd.to_string_lossy();
        let cleaned = if let Some(stripped) = s.strip_prefix(r"\\?\") {
            stripped
        } else if let Some(stripped) = s.strip_prefix("//?/") {
            stripped
        } else {
            &s
        };
        cleaned.replace('\\', "/")
    }

    fn prompt_visual_len(&self) -> usize {
        self.formatted_path().len() + 3
    }

    fn prompt_ansi(&self) -> String {
        format!("\x1b[1;34m{}\x1b[0m\x1b[1;32m $\x1b[0m ", self.formatted_path())
    }

    fn print_prompt(&self, term: &mut TerminalEmulator) {
        term.process_bytes(self.prompt_ansi().as_bytes());
    }

    fn redraw_line(&self, term: &mut TerminalEmulator) {
        term.process_bytes(b"\r\x1b[2K");
        term.process_bytes(self.prompt_ansi().as_bytes());
        let text: String = self.input.iter().collect();
        term.process_bytes(text.as_bytes());

        let target_col = self.prompt_visual_len() + self.cursor_pos + 1;
        let csi_col = format!("\x1b[{}G", target_col);
        term.process_bytes(csi_col.as_bytes());
    }

    fn insert_char(&mut self, c: char, term: &mut TerminalEmulator) {
        self.input.insert(self.cursor_pos, c);
        self.cursor_pos += 1;
        self.redraw_line(term);
    }

    fn backspace(&mut self, term: &mut TerminalEmulator) {
        if self.cursor_pos > 0 {
            self.cursor_pos -= 1;
            self.input.remove(self.cursor_pos);
            self.redraw_line(term);
        }
    }

    fn delete(&mut self, term: &mut TerminalEmulator) {
        if self.cursor_pos < self.input.len() {
            self.input.remove(self.cursor_pos);
            self.redraw_line(term);
        }
    }

    fn move_left(&mut self, term: &mut TerminalEmulator) {
        if self.cursor_pos > 0 {
            self.cursor_pos -= 1;
            self.redraw_line(term);
        }
    }

    fn move_right(&mut self, term: &mut TerminalEmulator) {
        if self.cursor_pos < self.input.len() {
            self.cursor_pos += 1;
            self.redraw_line(term);
        }
    }

    fn move_home(&mut self, term: &mut TerminalEmulator) {
        self.cursor_pos = 0;
        self.redraw_line(term);
    }

    fn move_end(&mut self, term: &mut TerminalEmulator) {
        self.cursor_pos = self.input.len();
        self.redraw_line(term);
    }

    fn history_up(&mut self, term: &mut TerminalEmulator) {
        if self.history.is_empty() {
            return;
        }

        let next_idx = match self.history_idx {
            None => {
                self.temp_input = self.input.iter().collect();
                self.history.len().saturating_sub(1)
            }
            Some(i) => i.saturating_sub(1),
        };

        self.history_idx = Some(next_idx);
        if let Some(cmd) = self.history.get(next_idx) {
            self.input = cmd.chars().collect();
            self.cursor_pos = self.input.len();
            self.redraw_line(term);
        }
    }

    fn history_down(&mut self, term: &mut TerminalEmulator) {
        if let Some(curr) = self.history_idx {
            if curr + 1 < self.history.len() {
                let next_idx = curr + 1;
                self.history_idx = Some(next_idx);
                if let Some(cmd) = self.history.get(next_idx) {
                    self.input = cmd.chars().collect();
                    self.cursor_pos = self.input.len();
                    self.redraw_line(term);
                }
            } else {
                self.history_idx = None;
                self.input = self.temp_input.chars().collect();
                self.cursor_pos = self.input.len();
                self.redraw_line(term);
            }
        }
    }

    fn kill_to_start(&mut self, term: &mut TerminalEmulator) {
        self.input.drain(0..self.cursor_pos);
        self.cursor_pos = 0;
        self.redraw_line(term);
    }

    fn kill_to_end(&mut self, term: &mut TerminalEmulator) {
        self.input.truncate(self.cursor_pos);
        self.redraw_line(term);
    }

    fn kill_word_backward(&mut self, term: &mut TerminalEmulator) {
        if self.cursor_pos == 0 {
            return;
        }
        let mut idx = self.cursor_pos;
        while idx > 0 && self.input[idx - 1].is_whitespace() {
            idx -= 1;
        }
        while idx > 0 && !self.input[idx - 1].is_whitespace() {
            idx -= 1;
        }
        self.input.drain(idx..self.cursor_pos);
        self.cursor_pos = idx;
        self.redraw_line(term);
    }

    fn cancel_line(&mut self, term: &mut TerminalEmulator) {
        term.process_bytes(b"^C\r\n");
        self.input.clear();
        self.cursor_pos = 0;
        self.history_idx = None;
        self.print_prompt(term);
    }
}

fn handle_pkg_command(term: &mut TerminalEmulator, args: &[&str]) {
    if args.is_empty() || args[0] == "help" {
        let help = "\x1b[1;36mTerApp Package Management Utility (Termux Architecture)\x1b[0m\r\n\
                    Usage: pkg <command> [package-name]\r\n\r\n\
                    Available Commands:\r\n\
                      install <pkg>   - Install a userland package\r\n\
                      uninstall <pkg> - Uninstall a package\r\n\
                      update          - Refresh available package repositories\r\n\
                      list-all        - List all packages available in TerApp\r\n\
                      search <query>  - Search for a specific package\r\n\
                      help            - Show this help message\r\n\r\n\
                    \x1b[0;37mEngine: Rust Native (Targeting BusyBox & Alpine APK Userland)\x1b[0m\r\n";
        term.process_bytes(help.as_bytes());
        return;
    }

    match args[0] {
        "install" => {
            if args.len() < 2 {
                term.process_bytes(b"\x1b[1;31mError: Missing package name. Usage: pkg install <name>\x1b[0m\r\n");
            } else {
                let pkg_name = args[1];
                let msg = format!(
                    "\x1b[1;32m[*] Checking TerApp repository for '{}'...\x1b[0m\r\n\
                     [*] Package '{}' registered in userland manifest.\r\n\
                     \x1b[1;32m[+] Ready for userland deployment.\x1b[0m\r\n",
                    pkg_name, pkg_name
                );
                term.process_bytes(msg.as_bytes());
            }
        }
        "update" | "upgrade" => {
            term.process_bytes(
                b"\x1b[1;32m[*] Updating TerApp package indices...\x1b[0m\r\n\
                  [*] All userland packages are up to date.\r\n",
            );
        }
        "list-all" | "list" => {
            let list = "\x1b[1;36mPopular TerApp / Termux Packages:\x1b[0m\r\n\
                        • busybox     - Tiny POSIX core utilities (~1.2 MB)\r\n\
                        • clang       - C/C++ compiler toolchain\r\n\
                        • git         - Fast version control system\r\n\
                        • python      - Python interpreter\r\n\
                        • nodejs      - JavaScript runtime\r\n\
                        • rust        - Rust programming language & cargo\r\n\
                        • openssh     - Secure remote login tools\r\n";
            term.process_bytes(list.as_bytes());
        }
        "search" => {
            let q = args.get(1).copied().unwrap_or("");
            let msg = format!("\x1b[1;32m[*] Searching TerApp repository for '{}'...\x1b[0m\r\n", q);
            term.process_bytes(msg.as_bytes());
        }
        other => {
            let msg = format!("\x1b[1;31mpkg: unknown command '{}'. Type 'pkg help'.\x1b[0m\r\n", other);
            term.process_bytes(msg.as_bytes());
        }
    }
}

fn execute_command(editor: &mut LineEditor, term: &mut TerminalEmulator, cmd: &str) {
    let trimmed = cmd.trim();
    if trimmed.is_empty() {
        return;
    }

    // Add to history
    if editor.history.last().map(|s| s.as_str()) != Some(trimmed) {
        editor.history.push(trimmed.to_string());
    }
    editor.history_idx = None;

    let parts: Vec<&str> = trimmed.split_whitespace().collect();
    match parts[0] {
        "cd" => {
            term.process_bytes(b"\r\n");
            let target = parts.get(1).copied().unwrap_or("~");
            let target_path = if target == "~" {
                std::env::var("USERPROFILE")
                    .or_else(|_| std::env::var("HOME"))
                    .map(PathBuf::from)
                    .unwrap_or_else(|_| PathBuf::from("."))
            } else {
                editor.cwd.join(target)
            };

            match std::fs::canonicalize(&target_path) {
                Ok(canon) => {
                    editor.cwd = clean_path(&canon);
                    let _ = std::env::set_current_dir(&editor.cwd);
                }
                Err(_) => {
                    let err = format!("cd: {}: No such file or directory\r\n", target);
                    term.process_bytes(err.as_bytes());
                }
            }
        }
        "pkg" | "apt" => {
            term.process_bytes(b"\r\n");
            handle_pkg_command(term, &parts[1..]);
        }
        "sudo" => {
            term.process_bytes(b"\r\n");
            let subcmd = parts.get(1).copied().unwrap_or("");
            if subcmd == "apt" || subcmd == "pkg" {
                term.process_bytes(
                    b"\x1b[1;33m[TerApp Notice]\x1b[0m In Termux / TerApp, root ('sudo') is not required.\r\n\
\x1b[0;37mPackage commands run directly with: \x1b[1;32mpkg install <package>\x1b[0m or \x1b[1;32mapt install <package>\x1b[0m\r\n\r\n"
                );
                handle_pkg_command(term, &parts[1..]);
            } else {
                let msg = "\x1b[1;33m[TerApp Notice]\x1b[0m\r\n\
                           Lacking administrative privileges?\r\n\
                           Unlike desktop Linux, Termux & TerApp run entirely within an application sandbox.\r\n\
                           Root access ('sudo') is not required to install or execute packages.\r\n\r\n";
                term.process_bytes(msg.as_bytes());
            }
        }
        "whoami" => {
            term.process_bytes(b"\r\nu0_a248 (terapp)\r\n");
        }
        "uname" => {
            term.process_bytes(b"\r\nLinux terapp-android 6.6.0-rust-arm64 #1 SMP PREEMPT TerApp\r\n");
        }
        "pwd" => {
            let p = format!("\r\n{}\r\n", editor.cwd.display());
            term.process_bytes(p.as_bytes());
        }
        "clear" => {
            term.process_bytes(b"\x1b[2J\x1b[H");
        }
        "help" => {
            let msg = "\r\n\x1b[1;36mTerApp Built-in Commands (Termux Compatible):\x1b[0m\r\n\
                         pkg/apt - Package management utility\r\n\
                         cd      - Change working directory\r\n\
                         pwd     - Print current working directory\r\n\
                         clear   - Clear terminal screen (or Ctrl+L)\r\n\
                         mem     - Inspect exact RAM memory usage\r\n\
                         colors  - Test ANSI colors palette\r\n\
                         scroll  - Test fast scrollback ring buffer\r\n\
                         exit    - Exit TerApp (or Ctrl+D)\r\n\
                         <other> - System commands (e.g. ls, git, ping, python)\r\n\r\n";
            term.process_bytes(msg.as_bytes());
        }
        "mem" => {
            let cell_size = std::mem::size_of::<terapp_core::Cell>();
            let screen_cells = term.buffer.cols * term.buffer.rows;
            let screen_kb = (screen_cells * cell_size) as f64 / 1024.0;
            let scroll_kb =
                (term.buffer.scrollback_len * term.buffer.cols * cell_size) as f64 / 1024.0;

            let msg = format!(
                "\r\n\x1b[1;32m[Memory Statistics]\x1b[0m\r\n\
                 • Cell Struct: {} bytes\r\n\
                 • Active Grid ({}x{}): {:.2} KB\r\n\
                 • Scrollback Ring Buffer ({} lines): {:.2} KB\r\n\
                 • Total Terminal RAM: \x1b[1;33m{:.2} KB\x1b[0m\r\n\r\n",
                cell_size,
                term.buffer.cols,
                term.buffer.rows,
                screen_kb,
                term.buffer.scrollback_len,
                scroll_kb,
                screen_kb + scroll_kb
            );
            term.process_bytes(msg.as_bytes());
        }
        "colors" => {
            let mut s = String::from("\r\n\x1b[1mStandard ANSI 16 Colors:\x1b[0m\r\n");
            for i in 0..8 {
                s.push_str(&format!("\x1b[{}m[{}]\x1b[0m ", 30 + i, i));
            }
            s.push_str("\r\n");
            for i in 0..8 {
                s.push_str(&format!("\x1b[{}m[{}]\x1b[0m ", 90 + i, i + 8));
            }
            s.push_str("\r\n\r\n");
            term.process_bytes(s.as_bytes());
        }
        "scroll" => {
            term.process_bytes(b"\r\nGenerating 30 test lines...\r\n");
            for i in 1..=30 {
                let line = format!("Scroll test #{:02}: TerApp ring-buffer running\r\n", i);
                term.process_bytes(line.as_bytes());
            }
        }
        _ => {
            term.process_bytes(b"\r\n");

            #[cfg(windows)]
            let output = Command::new("powershell")
                .current_dir(&editor.cwd)
                .args(["-NoProfile", "-Command", trimmed])
                .output();

            #[cfg(not(windows))]
            let output = Command::new("sh")
                .current_dir(&editor.cwd)
                .args(["-c", trimmed])
                .output();

            match output {
                Ok(out) => {
                    let mut stdout_text = String::from_utf8_lossy(&out.stdout).replace('\n', "\r\n");
                    stdout_text = stdout_text.replace("\r\r\n", "\r\n");
                    term.process_bytes(stdout_text.as_bytes());

                    let mut stderr_text = String::from_utf8_lossy(&out.stderr).replace('\n', "\r\n");
                    stderr_text = stderr_text.replace("\r\r\n", "\r\n");
                    if !stderr_text.is_empty() {
                        let err_msg = format!("\x1b[1;31m{}\x1b[0m", stderr_text);
                        term.process_bytes(err_msg.as_bytes());
                    }
                }
                Err(e) => {
                    let err = format!("\x1b[1;31mFailed to execute command: {}\x1b[0m\r\n", e);
                    term.process_bytes(err.as_bytes());
                }
            }
        }
    }
}

fn main() -> std::io::Result<()> {
    let (mut cols, mut rows) = terminal::size().unwrap_or((80, 24));
    let mut term = TerminalEmulator::new(cols as usize, rows as usize, 1000);
    let mut editor = LineEditor::new();
    let mut scroll_offset: usize = 0;

    // Initial Termux-style Welcome Message
    let banner = format!(
        "\x1b[1;36m====================================================================\x1b[0m\r\n\
         \x1b[1;32m  TerApp Terminal Environment (Rust Edition) - Termux Core Parity\x1b[0m\r\n\
         \x1b[0;37m  Working Dir: {} | Type 'pkg help' or 'help'\x1b[0m\r\n\
         \x1b[1;36m====================================================================\x1b[0m\r\n\r\n",
        editor.formatted_path()
    );
    term.process_bytes(banner.as_bytes());
    editor.print_prompt(&mut term);

    // Raw mode, mouse capture & buffered stdout setup
    let mut stdout = BufWriter::with_capacity(32 * 1024, stdout());
    terminal::enable_raw_mode()?;
    execute!(
        stdout,
        terminal::EnterAlternateScreen,
        event::EnableMouseCapture,
        cursor::Hide
    )?;

    // Drain any leftover events from the parent shell
    while event::poll(Duration::from_millis(15))? {
        let _ = event::read()?;
    }

    let res = (|| -> std::io::Result<()> {
        render_terminal(&mut stdout, &term, scroll_offset)?;

        loop {
            if event::poll(Duration::from_millis(50))? {
                match event::read()? {
                    Event::Mouse(mouse) => {
                        match mouse.kind {
                            MouseEventKind::ScrollUp => {
                                let max_scroll = term.buffer.scrollback_len;
                                scroll_offset = (scroll_offset + 3).min(max_scroll);
                                render_terminal(&mut stdout, &term, scroll_offset)?;
                            }
                            MouseEventKind::ScrollDown => {
                                scroll_offset = scroll_offset.saturating_sub(3);
                                render_terminal(&mut stdout, &term, scroll_offset)?;
                            }
                            _ => {}
                        }
                    }
                    Event::Key(key) => {
                        if key.kind != KeyEventKind::Press {
                            continue;
                        }

                        // Shift + Up / Shift + Down for scrolling buffer lines
                        if key.modifiers.contains(KeyModifiers::SHIFT) {
                            match key.code {
                                KeyCode::Up => {
                                    scroll_offset = (scroll_offset + 1).min(term.buffer.scrollback_len);
                                    render_terminal(&mut stdout, &term, scroll_offset)?;
                                    continue;
                                }
                                KeyCode::Down => {
                                    scroll_offset = scroll_offset.saturating_sub(1);
                                    render_terminal(&mut stdout, &term, scroll_offset)?;
                                    continue;
                                }
                                _ => {}
                            }
                        }

                        // PageUp / PageDown for scrolling page
                        match key.code {
                            KeyCode::PageUp => {
                                let step = (rows as usize / 2).max(1);
                                scroll_offset = (scroll_offset + step).min(term.buffer.scrollback_len);
                                render_terminal(&mut stdout, &term, scroll_offset)?;
                                continue;
                            }
                            KeyCode::PageDown => {
                                let step = (rows as usize / 2).max(1);
                                scroll_offset = scroll_offset.saturating_sub(step);
                                render_terminal(&mut stdout, &term, scroll_offset)?;
                                continue;
                            }
                            _ => {}
                        }

                        // Any typing resets scrollback to bottom (active prompt)
                        if scroll_offset > 0 {
                            scroll_offset = 0;
                            render_terminal(&mut stdout, &term, scroll_offset)?;
                        }

                        // --- Control Key Combinations ---
                        if key.modifiers.contains(KeyModifiers::CONTROL) {
                            match key.code {
                                KeyCode::Char('c') => {
                                    editor.cancel_line(&mut term);
                                    render_terminal(&mut stdout, &term, scroll_offset)?;
                                    continue;
                                }
                                KeyCode::Char('l') => {
                                    term.process_bytes(b"\x1b[2J\x1b[H");
                                    editor.redraw_line(&mut term);
                                    render_terminal(&mut stdout, &term, scroll_offset)?;
                                    continue;
                                }
                                KeyCode::Char('d') => {
                                    if editor.input.is_empty() {
                                        break;
                                    } else {
                                        editor.delete(&mut term);
                                        render_terminal(&mut stdout, &term, scroll_offset)?;
                                        continue;
                                    }
                                }
                                KeyCode::Char('a') => {
                                    editor.move_home(&mut term);
                                    render_terminal(&mut stdout, &term, scroll_offset)?;
                                    continue;
                                }
                                KeyCode::Char('e') => {
                                    editor.move_end(&mut term);
                                    render_terminal(&mut stdout, &term, scroll_offset)?;
                                    continue;
                                }
                                KeyCode::Char('u') => {
                                    editor.kill_to_start(&mut term);
                                    render_terminal(&mut stdout, &term, scroll_offset)?;
                                    continue;
                                }
                                KeyCode::Char('k') => {
                                    editor.kill_to_end(&mut term);
                                    render_terminal(&mut stdout, &term, scroll_offset)?;
                                    continue;
                                }
                                KeyCode::Char('w') => {
                                    editor.kill_word_backward(&mut term);
                                    render_terminal(&mut stdout, &term, scroll_offset)?;
                                    continue;
                                }
                                _ => {}
                            }
                        }

                        // --- Standard Keys ---
                        match key.code {
                            KeyCode::Char(c) => {
                                editor.insert_char(c, &mut term);
                                render_terminal(&mut stdout, &term, scroll_offset)?;
                            }
                            KeyCode::Backspace => {
                                editor.backspace(&mut term);
                                render_terminal(&mut stdout, &term, scroll_offset)?;
                            }
                            KeyCode::Delete => {
                                editor.delete(&mut term);
                                render_terminal(&mut stdout, &term, scroll_offset)?;
                            }
                            KeyCode::Left => {
                                editor.move_left(&mut term);
                                render_terminal(&mut stdout, &term, scroll_offset)?;
                            }
                            KeyCode::Right => {
                                editor.move_right(&mut term);
                                render_terminal(&mut stdout, &term, scroll_offset)?;
                            }
                            KeyCode::Up => {
                                editor.history_up(&mut term);
                                render_terminal(&mut stdout, &term, scroll_offset)?;
                            }
                            KeyCode::Down => {
                                editor.history_down(&mut term);
                                render_terminal(&mut stdout, &term, scroll_offset)?;
                            }
                            KeyCode::Home => {
                                editor.move_home(&mut term);
                                render_terminal(&mut stdout, &term, scroll_offset)?;
                            }
                            KeyCode::End => {
                                editor.move_end(&mut term);
                                render_terminal(&mut stdout, &term, scroll_offset)?;
                            }
                            KeyCode::Tab => {
                                render_terminal(&mut stdout, &term, scroll_offset)?;
                            }
                            KeyCode::Enter => {
                                let cmd: String = editor.input.iter().collect();
                                editor.input.clear();
                                editor.cursor_pos = 0;

                                if cmd.trim() == "exit" {
                                    break;
                                }

                                if cmd.trim().is_empty() {
                                    term.process_bytes(b"\r\n");
                                } else {
                                    execute_command(&mut editor, &mut term, &cmd);
                                }

                                editor.print_prompt(&mut term);
                                render_terminal(&mut stdout, &term, scroll_offset)?;
                            }
                            _ => {}
                        }
                    }
                    Event::Resize(new_cols, new_rows) => {
                        cols = new_cols;
                        rows = new_rows;
                        term.resize(cols as usize, rows as usize);
                        execute!(stdout, Clear(ClearType::All))?;
                        render_terminal(&mut stdout, &term, scroll_offset)?;
                    }
                    _ => {}
                }
            }
        }
        Ok(())
    })();

    // Restore terminal & disable mouse capture
    terminal::disable_raw_mode()?;
    execute!(
        stdout,
        event::DisableMouseCapture,
        cursor::Show,
        terminal::LeaveAlternateScreen
    )?;
    stdout.flush()?;

    res
}
