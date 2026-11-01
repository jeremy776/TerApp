package com.terapp

object TerAppBridge {
    var isLoaded = false
        private set

    init {
        try {
            System.loadLibrary("terapp_android")
            isLoaded = true
            android.util.Log.i("TerAppBridge", "TerApp native Rust engine loaded successfully")
        } catch (e: Throwable) {
            android.util.Log.e("TerAppBridge", "TerAppBridge: Native library not loaded: ${e.message}")
            isLoaded = false
        }
    }

    // --- Terminal Emulator Safe Wrappers ---

    fun initTerminalSafe(cols: Int, rows: Int, maxScrollback: Int): Long {
        if (!isLoaded) return 0L
        return try { initTerminal(cols, rows, maxScrollback) } catch (e: Throwable) { 0L }
    }

    fun freeTerminalSafe(ptr: Long) {
        if (!isLoaded || ptr == 0L) return
        try { freeTerminal(ptr) } catch (e: Throwable) {}
    }

    fun resizeSafe(ptr: Long, cols: Int, rows: Int) {
        if (!isLoaded || ptr == 0L) return
        try { resize(ptr, cols, rows) } catch (e: Throwable) {}
    }

    fun feedBytesSafe(ptr: Long, bytes: ByteArray) {
        if (!isLoaded || ptr == 0L) return
        try { feedBytes(ptr, bytes) } catch (e: Throwable) {}
    }

    fun getCursorXSafe(ptr: Long): Int {
        if (!isLoaded || ptr == 0L) return 0
        return try { getCursorX(ptr) } catch (e: Throwable) { 0 }
    }

    fun getCursorYSafe(ptr: Long): Int {
        if (!isLoaded || ptr == 0L) return 0
        return try { getCursorY(ptr) } catch (e: Throwable) { 0 }
    }

    fun isCursorVisibleSafe(ptr: Long): Boolean {
        if (!isLoaded || ptr == 0L) return true
        return try { isCursorVisible(ptr) } catch (e: Throwable) { true }
    }

    fun getLineTextSafe(ptr: Long, row: Int, scrollOffset: Int = 0): String {
        if (!isLoaded || ptr == 0L) return ""
        return try { getLineText(ptr, row, scrollOffset) } catch (e: Throwable) { "" }
    }

    fun getLineColorsSafe(
        ptr: Long,
        row: Int,
        scrollOffset: Int = 0,
        defaultFg: Int,
        defaultBg: Int,
        palette16: IntArray
    ): IntArray? {
        if (!isLoaded || ptr == 0L) return null
        return try {
            getLineColors(ptr, row, scrollOffset, defaultFg, defaultBg, palette16)
        } catch (e: Throwable) {
            null
        }
    }

    fun getTitleSafe(ptr: Long): String {
        if (!isLoaded || ptr == 0L) return ""
        return try { getTitle(ptr) } catch (e: Throwable) { "" }
    }

    fun getBellCountSafe(ptr: Long): Int {
        if (!isLoaded || ptr == 0L) return 0
        return try { getBellCount(ptr) } catch (e: Throwable) { 0 }
    }

    fun getScrollbackLengthSafe(ptr: Long): Int {
        if (!isLoaded || ptr == 0L) return 0
        return try { getScrollbackLength(ptr) } catch (e: Throwable) { 0 }
    }

    fun isAltScreenSafe(ptr: Long): Boolean {
        if (!isLoaded || ptr == 0L) return false
        return try { isAltScreen(ptr) } catch (e: Throwable) { false }
    }

    // --- Native PTY Safe Wrappers ---

    fun createSubprocessSafe(
        cmd: String,
        cwd: String,
        args: Array<String>,
        envVars: Array<String>,
        processIdArray: IntArray,
        rows: Int,
        cols: Int
    ): Int {
        if (!isLoaded) return -1
        return try {
            createSubprocess(cmd, cwd, args, envVars, processIdArray, rows, cols)
        } catch (e: Throwable) {
            android.util.Log.e("TerAppBridge", "createSubprocess error: ${e.message}")
            -1
        }
    }

    fun readPtySafe(fd: Int, buffer: ByteArray): Int {
        if (!isLoaded || fd < 0) return -1
        return try { readPty(fd, buffer) } catch (e: Throwable) { -1 }
    }

    fun writePtySafe(fd: Int, bytes: ByteArray): Int {
        if (!isLoaded || fd < 0) return -1
        return try { writePty(fd, bytes) } catch (e: Throwable) { -1 }
    }

    fun setPtyWindowSizeSafe(fd: Int, rows: Int, cols: Int) {
        if (!isLoaded || fd < 0) return
        try { setPtyWindowSize(fd, rows, cols) } catch (e: Throwable) {}
    }

    fun waitForSafe(pid: Int): Int {
        if (!isLoaded || pid <= 0) return 0
        return try { waitFor(pid) } catch (e: Throwable) { 0 }
    }

    fun closePtySafe(fd: Int) {
        if (!isLoaded || fd < 0) return
        try { closePty(fd) } catch (e: Throwable) {}
    }

    // --- External Native Declarations ---

    private external fun initTerminal(cols: Int, rows: Int, maxScrollback: Int): Long
    private external fun freeTerminal(ptr: Long)
    private external fun resize(ptr: Long, cols: Int, rows: Int)
    private external fun feedBytes(ptr: Long, bytes: ByteArray)
    private external fun getCursorX(ptr: Long): Int
    private external fun getCursorY(ptr: Long): Int
    private external fun isCursorVisible(ptr: Long): Boolean
    private external fun getLineText(ptr: Long, row: Int, scrollOffset: Int): String
    private external fun getLineColors(
        ptr: Long,
        row: Int,
        scrollOffset: Int,
        defaultFg: Int,
        defaultBg: Int,
        palette16: IntArray
    ): IntArray?
    private external fun getTitle(ptr: Long): String
    private external fun getBellCount(ptr: Long): Int
    private external fun getScrollbackLength(ptr: Long): Int
    private external fun isAltScreen(ptr: Long): Boolean

    private external fun createSubprocess(
        cmd: String,
        cwd: String,
        args: Array<String>,
        envVars: Array<String>,
        processIdArray: IntArray,
        rows: Int,
        cols: Int
    ): Int
    private external fun readPty(fd: Int, buffer: ByteArray): Int
    private external fun writePty(fd: Int, bytes: ByteArray): Int
    private external fun setPtyWindowSize(fd: Int, rows: Int, cols: Int)
    private external fun waitFor(pid: Int): Int
    private external fun closePty(fd: Int)
}
