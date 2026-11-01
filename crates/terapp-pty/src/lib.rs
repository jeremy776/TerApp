use std::io::{self, Read, Write};

pub trait PtySession: Read + Write {
    fn resize(&mut self, cols: u16, rows: u16) -> io::Result<()>;
    fn is_alive(&mut self) -> bool;
}

#[cfg(unix)]
pub mod unix {
    use super::*;
    use std::ffi::CString;
    use std::os::unix::io::RawFd;

    pub struct UnixPty {
        pub master_fd: RawFd,
        pub child_pid: libc::pid_t,
    }

    impl UnixPty {
        /// Spawns a new subprocess attached to a newly created Linux pseudoterminal (PTY).
        pub fn spawn(
            cmd: &str,
            cwd: &str,
            args: &[&str],
            envp: &[&str],
            rows: u16,
            cols: u16,
        ) -> io::Result<Self> {
            unsafe {
                // 1. Open master PTY
                let master_fd = libc::open(
                    b"/dev/ptmx\0".as_ptr() as *const libc::c_char,
                    libc::O_RDWR | libc::O_CLOEXEC,
                );
                if master_fd < 0 {
                    return Err(io::Error::last_os_error());
                }

                // 2. Grant and unlock
                if libc::grantpt(master_fd) != 0 || libc::unlockpt(master_fd) != 0 {
                    libc::close(master_fd);
                    return Err(io::Error::last_os_error());
                }

                // 3. Get pts slave name
                let mut pts_name = [0 as libc::c_char; 64];
                if libc::ptsname_r(master_fd, pts_name.as_mut_ptr(), pts_name.len()) != 0 {
                    libc::close(master_fd);
                    return Err(io::Error::last_os_error());
                }

                // 4. Set termios: enable UTF-8 mode and disable software flow control
                let mut tios: libc::termios = std::mem::zeroed();
                if libc::tcgetattr(master_fd, &mut tios) == 0 {
                    tios.c_iflag |= libc::IUTF8;
                    tios.c_iflag &= !(libc::IXON | libc::IXOFF);
                    libc::tcsetattr(master_fd, libc::TCSANOW, &tios);
                }

                // 5. Set initial winsize
                let ws = libc::winsize {
                    ws_row: rows,
                    ws_col: cols,
                    ws_xpixel: 0,
                    ws_ypixel: 0,
                };
                libc::ioctl(master_fd, libc::TIOCSWINSZ, &ws);

                // Prepare CStrings
                let c_cmd = CString::new(cmd).map_err(|e| io::Error::new(io::ErrorKind::InvalidInput, e))?;
                let c_cwd = CString::new(cwd).map_err(|e| io::Error::new(io::ErrorKind::InvalidInput, e))?;

                let mut c_args = Vec::new();
                for a in args {
                    c_args.push(CString::new(*a).map_err(|e| io::Error::new(io::ErrorKind::InvalidInput, e))?);
                }
                let mut arg_ptrs: Vec<*const libc::c_char> = c_args.iter().map(|s| s.as_ptr()).collect();
                arg_ptrs.push(std::ptr::null());

                let mut c_env = Vec::new();
                for e in envp {
                    c_env.push(CString::new(*e).map_err(|err| io::Error::new(io::ErrorKind::InvalidInput, err))?);
                }
                let mut env_ptrs: Vec<*const libc::c_char> = c_env.iter().map(|s| s.as_ptr()).collect();
                env_ptrs.push(std::ptr::null());

                // 6. Fork child process
                let pid = libc::fork();
                if pid < 0 {
                    libc::close(master_fd);
                    return Err(io::Error::last_os_error());
                }

                if pid == 0 {
                    // Child process:
                    // Unblock all signals
                    let mut set: libc::sigset_t = std::mem::zeroed();
                    libc::sigfillset(&mut set);
                    libc::sigprocmask(libc::SIG_UNBLOCK, &set, std::ptr::null_mut());

                    libc::close(master_fd);
                    libc::setsid();

                    let slave_fd = libc::open(pts_name.as_ptr(), libc::O_RDWR);
                    if slave_fd < 0 {
                        libc::_exit(1);
                    }

                    // Set controlling terminal
                    libc::ioctl(slave_fd, libc::TIOCSCTTY, 0);

                    libc::dup2(slave_fd, 0);
                    libc::dup2(slave_fd, 1);
                    libc::dup2(slave_fd, 2);

                    if slave_fd > 2 {
                        libc::close(slave_fd);
                    }

                    // Close any file descriptors > 2
                    let dir = libc::opendir(b"/proc/self/fd\0".as_ptr() as *const libc::c_char);
                    if !dir.is_null() {
                        let dir_fd = libc::dirfd(dir);
                        loop {
                            let entry = libc::readdir(dir);
                            if entry.is_null() {
                                break;
                            }
                            let name_c = (*entry).d_name.as_ptr();
                            let fd_num = libc::atoi(name_c);
                            if fd_num > 2 && fd_num != dir_fd {
                                libc::close(fd_num);
                            }
                        }
                        libc::closedir(dir);
                    }

                    // Setup environment variables
                    libc::clearenv();
                    for &p in &env_ptrs {
                        if !p.is_null() {
                            libc::putenv(p as *mut libc::c_char);
                        }
                    }

                    // Change working directory
                    if libc::chdir(c_cwd.as_ptr()) != 0 {
                        // ignore or fallback
                    }

                    // Execute command
                    libc::execvp(c_cmd.as_ptr(), arg_ptrs.as_ptr());
                    libc::_exit(127);
                }

                // Parent process
                Ok(Self {
                    master_fd,
                    child_pid: pid,
                })
            }
        }

        pub fn wait_for(&self) -> i32 {
            unsafe {
                let mut status: libc::c_int = 0;
                let res = libc::waitpid(self.child_pid, &mut status, 0);
                if res > 0 {
                    if libc::WIFEXITED(status) {
                        return libc::WEXITSTATUS(status);
                    } else if libc::WIFSIGNALED(status) {
                        return -libc::WTERMSIG(status);
                    }
                }
                0
            }
        }
    }

    impl Read for UnixPty {
        fn read(&mut self, buf: &mut [u8]) -> io::Result<usize> {
            let res = unsafe { libc::read(self.master_fd, buf.as_mut_ptr() as *mut libc::c_void, buf.len()) };
            if res < 0 {
                Err(io::Error::last_os_error())
            } else {
                Ok(res as usize)
            }
        }
    }

    impl Write for UnixPty {
        fn write(&mut self, buf: &[u8]) -> io::Result<usize> {
            let res = unsafe { libc::write(self.master_fd, buf.as_ptr() as *const libc::c_void, buf.len()) };
            if res < 0 {
                Err(io::Error::last_os_error())
            } else {
                Ok(res as usize)
            }
        }

        fn flush(&mut self) -> io::Result<()> {
            Ok(())
        }
    }

    impl PtySession for UnixPty {
        fn resize(&mut self, cols: u16, rows: u16) -> io::Result<()> {
            let ws = libc::winsize {
                ws_row: rows,
                ws_col: cols,
                ws_xpixel: 0,
                ws_ypixel: 0,
            };
            let ret = unsafe { libc::ioctl(self.master_fd, libc::TIOCSWINSZ, &ws) };
            if ret < 0 {
                Err(io::Error::last_os_error())
            } else {
                Ok(())
            }
        }

        fn is_alive(&mut self) -> bool {
            let mut status: libc::c_int = 0;
            let res = unsafe { libc::waitpid(self.child_pid, &mut status, libc::WNOHANG) };
            res == 0
        }
    }

    impl Drop for UnixPty {
        fn drop(&mut self) {
            unsafe {
                if self.master_fd >= 0 {
                    libc::close(self.master_fd);
                    self.master_fd = -1;
                }
            }
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    #[cfg(unix)]
    fn test_pty_spawn_and_read() {
        let mut pty = unix::UnixPty::spawn(
            "echo",
            "/tmp",
            &["echo", "hello_pty"],
            &["PATH=/usr/bin:/bin"],
            24,
            80,
        ).expect("failed to spawn PTY subprocess");

        let mut buf = [0u8; 128];
        let mut output = Vec::new();
        loop {
            match pty.read(&mut buf) {
                Ok(n) if n > 0 => output.extend_from_slice(&buf[..n]),
                _ => break,
            }
        }
        let out_str = String::from_utf8_lossy(&output);
        assert!(out_str.contains("hello_pty"), "PTY output was: {}", out_str);
    }
}
