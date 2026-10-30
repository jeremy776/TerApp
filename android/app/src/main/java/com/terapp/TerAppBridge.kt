package com.terapp

object TerAppBridge {
    var isLoaded = false
        private set

    init {
        try {
            System.loadLibrary("terapp_android")
            isLoaded = true
            android.util.Log.i("TerAppBridge", "TerApp native Rust engine loaded successfully: isLoaded=$isLoaded")
        } catch (e: Throwable) {
            android.util.Log.e("TerAppBridge", "TerAppBridge: Native library not loaded: ${e.message}")
            isLoaded = false
        }
    }

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

    fun getLineTextSafe(ptr: Long, row: Int): String {
        if (!isLoaded || ptr == 0L) return ""
        return try { getLineText(ptr, row) } catch (e: Throwable) { "" }
    }

    private external fun initTerminal(cols: Int, rows: Int, maxScrollback: Int): Long
    private external fun freeTerminal(ptr: Long)
    private external fun resize(ptr: Long, cols: Int, rows: Int)
    private external fun feedBytes(ptr: Long, bytes: ByteArray)
    private external fun getCursorX(ptr: Long): Int
    private external fun getCursorY(ptr: Long): Int
    private external fun getLineText(ptr: Long, row: Int): String
}
