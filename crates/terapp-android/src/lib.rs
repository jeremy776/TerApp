use jni::objects::{JByteArray, JClass, JString};
use jni::sys::{jint, jlong, jstring};
use jni::JNIEnv;
use std::sync::Mutex;
use terapp_core::TerminalEmulator;

pub struct TerminalSession {
    pub emulator: Mutex<TerminalEmulator>,
}

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
pub unsafe extern "system" fn Java_com_terapp_TerAppBridge_getLineText(
    env: JNIEnv,
    _class: JClass,
    ptr: jlong,
    row: jint,
) -> jstring {
    if ptr != 0 && row >= 0 {
        let session = &*(ptr as *const TerminalSession);
        if let Ok(emu) = session.emulator.lock() {
            if let Some(r) = emu.buffer.screen.get(row as usize) {
                let line_str: String = r.cells.iter().map(|c| c.ch).collect();
                if let Ok(jstr) = env.new_string(line_str) {
                    return jstr.into_raw();
                }
            }
        }
    }
    JString::default().into_raw()
}
