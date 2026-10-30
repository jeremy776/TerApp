use std::io::{Read, Write};

pub trait PtySession: Read + Write {
    fn resize(&mut self, cols: u16, rows: u16) -> std::io::Result<()>;
    fn is_alive(&mut self) -> bool;
}

#[cfg(unix)]
pub mod unix {
    use super::*;
    use std::os::unix::io::RawFd;

    pub struct UnixPty {
        pub master_fd: RawFd,
        pub child_pid: libc::pid_t,
    }

    impl Read for UnixPty {
        fn read(&mut self, buf: &mut [u8]) -> std::io::Result<usize> {
            let res = unsafe { libc::read(self.master_fd, buf.as_mut_ptr() as *mut libc::c_void, buf.len()) };
            if res < 0 {
                Err(std::io::Error::last_os_error())
            } else {
                Ok(res as usize)
            }
        }
    }

    impl Write for UnixPty {
        fn write(&mut self, buf: &[u8]) -> std::io::Result<usize> {
            let res = unsafe { libc::write(self.master_fd, buf.as_ptr() as *const libc::c_void, buf.len()) };
            if res < 0 {
                Err(std::io::Error::last_os_error())
            } else {
                Ok(res as usize)
            }
        }

        fn flush(&mut self) -> std::io::Result<()> {
            Ok(())
        }
    }

    impl PtySession for UnixPty {
        fn resize(&mut self, cols: u16, rows: u16) -> std::io::Result<()> {
            let ws = libc::winsize {
                ws_row: rows,
                ws_col: cols,
                ws_xpixel: 0,
                ws_ypixel: 0,
            };
            let ret = unsafe { libc::ioctl(self.master_fd, libc::TIOCSWINSZ, &ws) };
            if ret < 0 {
                Err(std::io::Error::last_os_error())
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
}

