use jni::objects::{JByteArray, JClass, JIntArray, JObjectArray, JString};
use jni::sys::{jboolean, jint, jintArray, jlong, jstring};
use jni::JNIEnv;
use std::sync::Mutex;
use terapp_core::TerminalEmulator;
use terapp_pty::unix::UnixPty;

pub struct TerminalSession {
    pub emulator: Mutex<TerminalEmulator>,
}

// ---------------------------------------------------------
// Terminal Emulator Engine JNI
// ---------------------------------------------------------

#[no_mangle]
pub extern "system" fn Java_com_terapp_TerAppBridge_initTerminal(
    _env: JNIEnv,
    _class: JClass,
    cols: jint,
    rows: jint,
    max_scrollback: jint,
) -> jlong {
    let emulator = TerminalEmulator::new(
        cols.max(1) as usize,
        rows.max(1) as usize,
        max_scrollback.max(0) as usize,
    );

    let session = Box::new(TerminalSession {
        emulator: Mutex::new(emulator),
    });

    Box::into_raw(session) as jlong
}

#[no_mangle]
pub unsafe extern "system" fn Java_com_terapp_TerAppBridge_freeTerminal(
    _env: JNIEnv,
    _class: JClass,
    ptr: jlong,
) {
    if ptr != 0 {
        drop(Box::from_raw(ptr as *mut TerminalSession));
    }
}

#[no_mangle]
pub unsafe extern "system" fn Java_com_terapp_TerAppBridge_resize(
    _env: JNIEnv,
    _class: JClass,
    ptr: jlong,
    cols: jint,
    rows: jint,
) {
    if ptr != 0 {
        let session = &*(ptr as *const TerminalSession);
        if let Ok(mut emu) = session.emulator.lock() {
            emu.resize(cols.max(1) as usize, rows.max(1) as usize);
        }
    }
}

#[no_mangle]
pub unsafe extern "system" fn Java_com_terapp_TerAppBridge_feedBytes(
    env: JNIEnv,
    _class: JClass,
    ptr: jlong,
    bytes: jni::sys::jbyteArray,
) {
    if ptr != 0 && !bytes.is_null() {
        let session = &*(ptr as *const TerminalSession);
        let byte_array = JByteArray::from_raw(bytes);
        if let Ok(data) = env.convert_byte_array(&byte_array) {
            if let Ok(mut emu) = session.emulator.lock() {
                emu.process_bytes(&data);
            }
        }
    }
}

#[no_mangle]
pub unsafe extern "system" fn Java_com_terapp_TerAppBridge_getCursorX(
    _env: JNIEnv,
    _class: JClass,
    ptr: jlong,
) -> jint {
    if ptr != 0 {
        let session = &*(ptr as *const TerminalSession);
        if let Ok(emu) = session.emulator.lock() {
            return emu.cursor.x as jint;
        }
    }
    0
}

#[no_mangle]
pub unsafe extern "system" fn Java_com_terapp_TerAppBridge_getCursorY(
    _env: JNIEnv,
    _class: JClass,
    ptr: jlong,
) -> jint {
    if ptr != 0 {
        let session = &*(ptr as *const TerminalSession);
        if let Ok(emu) = session.emulator.lock() {
            return emu.cursor.y as jint;
        }
    }
    0
}

#[no_mangle]
pub unsafe extern "system" fn Java_com_terapp_TerAppBridge_isCursorVisible(
    _env: JNIEnv,
    _class: JClass,
    ptr: jlong,
) -> jboolean {
    if ptr != 0 {
        let session = &*(ptr as *const TerminalSession);
        if let Ok(emu) = session.emulator.lock() {
            return if emu.cursor.visible { 1 } else { 0 };
        }
    }
    1
}

#[no_mangle]
pub unsafe extern "system" fn Java_com_terapp_TerAppBridge_getLineText(
    env: JNIEnv,
    _class: JClass,
    ptr: jlong,
    row: jint,
    scroll_offset: jint,
) -> jstring {
    if ptr != 0 && row >= 0 {
        let session = &*(ptr as *const TerminalSession);
        if let Ok(emu) = session.emulator.lock() {
            let offset = scroll_offset.max(0) as usize;
            if let Some(r) = emu.buffer.get_view_row(row as usize, offset) {
                let line_str: String = r.cells.iter().map(|c| c.ch).collect();
                let trimmed = line_str.trim_end();
                if let Ok(jstr) = env.new_string(trimmed) {
                    return jstr.into_raw();
                }
            }
        }
    }
    JString::default().into_raw()
}

#[no_mangle]
pub unsafe extern "system" fn Java_com_terapp_TerAppBridge_getLineColors(
    env: JNIEnv,
    _class: JClass,
    ptr: jlong,
    row: jint,
    scroll_offset: jint,
    default_fg: jint,
    _default_bg: jint,
    palette16_arr: jintArray,
) -> jintArray {
    if ptr == 0 || row < 0 || palette16_arr.is_null() {
        return std::ptr::null_mut();
    }

    let mut palette = [0u32; 16];
    let jpalette = JIntArray::from_raw(palette16_arr);
    let mut pal_buf = [0 as jint; 16];
    if env.get_int_array_region(&jpalette, 0, &mut pal_buf).is_ok() {
        for (i, p) in pal_buf.iter().enumerate() {
            palette[i] = *p as u32;
        }
    }

    let session = &*(ptr as *const TerminalSession);
    if let Ok(emu) = session.emulator.lock() {
        let offset = scroll_offset.max(0) as usize;
        if let Some(r) = emu.buffer.get_view_row(row as usize, offset) {
            let cols = r.cells.len();
            // Each cell has 2 ints: [fg_argb, (flags << 24) | (bg_color & 0x00FFFFFF)]
            let mut out = vec![0 as jint; cols * 2];
            for (i, cell) in r.cells.iter().enumerate() {
                let fg = cell.fg.to_argb(default_fg as u32, &palette) as jint;
                let flags = (cell.flags as jint) << 24;
                out[i * 2] = fg;
                out[i * 2 + 1] = flags;
            }
            if let Ok(result_array) = env.new_int_array(out.len() as jint) {
                let _ = env.set_int_array_region(&result_array, 0, &out);
                return result_array.into_raw();
            }
        }
    }

    std::ptr::null_mut()
}

#[no_mangle]
pub unsafe extern "system" fn Java_com_terapp_TerAppBridge_getTitle(
    env: JNIEnv,
    _class: JClass,
    ptr: jlong,
) -> jstring {
    if ptr != 0 {
        let session = &*(ptr as *const TerminalSession);
        if let Ok(emu) = session.emulator.lock() {
            if let Ok(jstr) = env.new_string(&emu.title) {
                return jstr.into_raw();
            }
        }
    }
    JString::default().into_raw()
}

#[no_mangle]
pub unsafe extern "system" fn Java_com_terapp_TerAppBridge_getBellCount(
    _env: JNIEnv,
    _class: JClass,
    ptr: jlong,
) -> jint {
    if ptr != 0 {
        let session = &*(ptr as *const TerminalSession);
        if let Ok(emu) = session.emulator.lock() {
            return emu.bell_count as jint;
        }
    }
    0
}

#[no_mangle]
pub unsafe extern "system" fn Java_com_terapp_TerAppBridge_getScrollbackLength(
    _env: JNIEnv,
    _class: JClass,
    ptr: jlong,
) -> jint {
    if ptr != 0 {
        let session = &*(ptr as *const TerminalSession);
        if let Ok(emu) = session.emulator.lock() {
            return emu.buffer.scrollback_len as jint;
        }
    }
    0
}

#[no_mangle]
pub unsafe extern "system" fn Java_com_terapp_TerAppBridge_isAltScreen(
    _env: JNIEnv,
    _class: JClass,
    ptr: jlong,
) -> jboolean {
    if ptr != 0 {
        let session = &*(ptr as *const TerminalSession);
        if let Ok(emu) = session.emulator.lock() {
            return if emu.buffer.is_alt_screen { 1 } else { 0 };
        }
    }
    0
}

// ---------------------------------------------------------
// Native PTY Subprocess Management JNI
// ---------------------------------------------------------

#[no_mangle]
pub unsafe extern "system" fn Java_com_terapp_TerAppBridge_createSubprocess(
    mut env: JNIEnv,
    _class: JClass,
    cmd: JString,
    cwd: JString,
    args: JObjectArray,
    env_vars: JObjectArray,
    process_id_array: jintArray,
    rows: jint,
    cols: jint,
) -> jint {
    let cmd_str = match env.get_string(&cmd) {
        Ok(s) => s.to_string_lossy().to_string(),
        Err(_) => return -1,
    };
    let cwd_str = match env.get_string(&cwd) {
        Ok(s) => s.to_string_lossy().to_string(),
        Err(_) => return -1,
    };

    let mut args_vec = Vec::new();
    if !args.is_null() {
        if let Ok(len) = env.get_array_length(&args) {
            for i in 0..len {
                if let Ok(obj) = env.get_object_array_element(&args, i) {
                    if !obj.is_null() {
                        let jstr = JString::from(obj);
                        let s = env.get_string(&jstr).map(|v| v.to_string_lossy().to_string()).unwrap_or_default();
                        args_vec.push(s);
                    }
                }
            }
        }
    }

    let mut env_vec = Vec::new();
    if !env_vars.is_null() {
        if let Ok(len) = env.get_array_length(&env_vars) {
            for i in 0..len {
                if let Ok(obj) = env.get_object_array_element(&env_vars, i) {
                    if !obj.is_null() {
                        let jstr = JString::from(obj);
                        let s = env.get_string(&jstr).map(|v| v.to_string_lossy().to_string()).unwrap_or_default();
                        env_vec.push(s);
                    }
                }
            }
        }
    }

    let args_slices: Vec<&str> = args_vec.iter().map(|s| s.as_str()).collect();
    let env_slices: Vec<&str> = env_vec.iter().map(|s| s.as_str()).collect();

    match UnixPty::spawn(
        &cmd_str,
        &cwd_str,
        &args_slices,
        &env_slices,
        rows.max(1) as u16,
        cols.max(1) as u16,
    ) {
        Ok(pty) => {
            if !process_id_array.is_null() {
                let pid_arr = JIntArray::from_raw(process_id_array);
                let pid_val = [pty.child_pid as jint];
                let _ = env.set_int_array_region(&pid_arr, 0, &pid_val);
            }
            let master_fd = pty.master_fd;
            // Forget pty so master_fd is not closed on drop here
            std::mem::forget(pty);
            master_fd as jint
        }
        Err(_) => -1,
    }
}

#[no_mangle]
pub unsafe extern "system" fn Java_com_terapp_TerAppBridge_readPty(
    env: JNIEnv,
    _class: JClass,
    fd: jint,
    buffer: jni::sys::jbyteArray,
) -> jint {
    if fd < 0 || buffer.is_null() {
        return -1;
    }
    let jbuf = JByteArray::from_raw(buffer);
    let len = match env.get_array_length(&jbuf) {
        Ok(l) => l as usize,
        Err(_) => return -1,
    };
    let mut temp = vec![0u8; len];
    let res = libc::read(fd, temp.as_mut_ptr() as *mut libc::c_void, len);
    if res > 0 {
        let i8_slice: &[i8] = std::slice::from_raw_parts(temp.as_ptr() as *const i8, res as usize);
        let _ = env.set_byte_array_region(&jbuf, 0, i8_slice);
        res as jint
    } else {
        res as jint
    }
}

#[no_mangle]
pub unsafe extern "system" fn Java_com_terapp_TerAppBridge_writePty(
    env: JNIEnv,
    _class: JClass,
    fd: jint,
    bytes: jni::sys::jbyteArray,
) -> jint {
    if fd < 0 || bytes.is_null() {
        return -1;
    }
    let jbytes = JByteArray::from_raw(bytes);
    if let Ok(data) = env.convert_byte_array(&jbytes) {
        let res = libc::write(fd, data.as_ptr() as *const libc::c_void, data.len());
        res as jint
    } else {
        -1
    }
}

#[no_mangle]
pub unsafe extern "system" fn Java_com_terapp_TerAppBridge_setPtyWindowSize(
    _env: JNIEnv,
    _class: JClass,
    fd: jint,
    rows: jint,
    cols: jint,
) {
    if fd >= 0 {
        let ws = libc::winsize {
            ws_row: rows.max(1) as u16,
            ws_col: cols.max(1) as u16,
            ws_xpixel: 0,
            ws_ypixel: 0,
        };
        libc::ioctl(fd, libc::TIOCSWINSZ, &ws);
    }
}

#[no_mangle]
pub unsafe extern "system" fn Java_com_terapp_TerAppBridge_waitFor(
    _env: JNIEnv,
    _class: JClass,
    pid: jint,
) -> jint {
    if pid <= 0 {
        return 0;
    }
    let mut status: libc::c_int = 0;
    let res = libc::waitpid(pid, &mut status, 0);
    if res > 0 {
        if libc::WIFEXITED(status) {
            libc::WEXITSTATUS(status)
        } else if libc::WIFSIGNALED(status) {
            -libc::WTERMSIG(status)
        } else {
            0
        }
    } else {
        0
    }
}

#[no_mangle]
pub unsafe extern "system" fn Java_com_terapp_TerAppBridge_closePty(
    _env: JNIEnv,
    _class: JClass,
    fd: jint,
) {
    if fd >= 0 {
        libc::close(fd);
    }
}
