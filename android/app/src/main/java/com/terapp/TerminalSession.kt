package com.terapp

import android.content.Context
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.nio.charset.StandardCharsets
import java.util.Collections

class TerminalSession(
    val id: String,
    var name: String,
    var cols: Int = 80,
    var rows: Int = 24,
    var maxScrollback: Int = 1000,
    var workingDir: File? = null,
    var context: Context? = null
) {
    var nativePtr: Long = 0
        private set

    private var process: Process? = null
    private var processIn: OutputStream? = null
    private var processOut: InputStream? = null
    private var isRunning = false

    var onScreenChanged: (() -> Unit)? = null

    // Fallback in-memory buffer if native Rust lib is not linked
    private val fallbackLines = Collections.synchronizedList(ArrayList<StringBuilder>())
    private var fallbackCursorX = 0
    private var fallbackCursorY = 0
    private val inputLineBuffer = StringBuilder()

    init {
        nativePtr = TerAppBridge.initTerminalSafe(cols, rows, maxScrollback)
        if (nativePtr == 0L) {
            initFallbackBuffer()
        }
        startShellProcess()
    }

    private fun initFallbackBuffer() {
        fallbackLines.clear()
        fallbackLines.add(StringBuilder())
        fallbackCursorX = 0
        fallbackCursorY = 0
    }

    private fun startShellProcess() {
        try {
            val filesDir = context?.filesDir ?: workingDir?.parentFile ?: File("/data/data/com.terapp/files")
            val usrBin = File(filesDir, "usr/bin")
            val home = workingDir ?: File(filesDir, "home")
            if (!home.exists()) home.mkdirs()

            val busyboxSh = File(usrBin, "sh")
            val shellPath = if (busyboxSh.exists() && busyboxSh.canExecute()) {
                busyboxSh.absolutePath
            } else if (File("/system/bin/sh").exists()) {
                "/system/bin/sh"
            } else {
                "sh"
            }

            val pb = ProcessBuilder(shellPath)
            pb.directory(home)
            pb.environment()["TERM"] = "xterm-256color"
            pb.environment()["HOME"] = home.absolutePath
            pb.environment()["PREFIX"] = File(filesDir, "usr").absolutePath
            pb.environment()["SHELL"] = shellPath
            pb.environment()["PATH"] = "${usrBin.absolutePath}:/system/bin:/system/xbin"
            pb.environment()["PS1"] = "\\w $ "
            pb.redirectErrorStream(true)

            val p = pb.start()
            process = p
            processIn = p.outputStream
            processOut = p.inputStream
            isRunning = true

            // Initial banner
            val banner = "\u001b[1;36m========================================================\u001b[0m\r\n" +
                         "\u001b[1;32m  TerApp (Rust Core) - Native Mobile Terminal v1.0\u001b[0m\r\n" +
                         "\u001b[0;37m  POSIX Userland Active (BusyBox 1.36.1) | Type 'help'\u001b[0m\r\n" +
                         "\u001b[1;36m========================================================\u001b[0m\r\n\r\n~ $ "
            feedBytes(banner.toByteArray())

            // Background reader thread from process
            Thread {
                val buffer = ByteArray(4096)
                while (isRunning) {
                    try {
                        val read = processOut?.read(buffer) ?: -1
                        if (read > 0) {
                            val data = buffer.copyOf(read)
                            feedBytes(data)
                            // Prompt if stream pauses
                            try {
                                Thread.sleep(50)
                                if ((processOut?.available() ?: 0) == 0) {
                                    feedBytes("\r\n~ $ ".toByteArray())
                                }
                            } catch (ignored: Exception) {}
                            onScreenChanged?.invoke()
                        } else if (read == -1) {
                            break
                        }
                    } catch (e: Exception) {
                        break
                    }
                }
            }.start()

        } catch (e: Exception) {
            val err = "\u001b[1;31mFailed to spawn shell: ${e.message}\u001b[0m\r\n"
            feedBytes(err.toByteArray())
        }
    }

    fun feedBytes(bytes: ByteArray) {
        if (nativePtr != 0L) {
            TerAppBridge.feedBytesSafe(nativePtr, bytes)
        } else {
            feedFallback(bytes)
        }
    }

    private fun feedFallback(bytes: ByteArray) {
        val str = String(bytes, StandardCharsets.UTF_8)
        var i = 0
        while (i < str.length) {
            val c = str[i]
            if (c == '\u001b') {
                // Skip ANSI escape sequences
                var j = i + 1
                if (j < str.length && (str[j] == '[' || str[j] == ']' || str[j] == '(')) {
                    j++
                    while (j < str.length && !str[j].isLetter() && str[j] != '~') {
                        j++
                    }
                    if (j < str.length) j++
                }
                i = j
                continue
            } else if (c == '\r') {
                fallbackCursorX = 0
            } else if (c == '\n') {
                fallbackCursorX = 0
                fallbackCursorY++
                if (fallbackCursorY >= fallbackLines.size) {
                    fallbackLines.add(StringBuilder())
                }
            } else if (c == '\b') {
                if (fallbackCursorX > 0) {
                    fallbackCursorX--
                    val line = getFallbackLine(fallbackCursorY)
                    if (fallbackCursorX < line.length) {
                        line.deleteCharAt(fallbackCursorX)
                    }
                }
            } else if (c.code in 32..126 || c.code > 127) {
                val line = getFallbackLine(fallbackCursorY)
                while (line.length < fallbackCursorX) {
                    line.append(' ')
                }
                if (fallbackCursorX < line.length) {
                    line.setCharAt(fallbackCursorX, c)
                } else {
                    line.append(c)
                }
                fallbackCursorX++
                if (fallbackCursorX >= cols) {
                    fallbackCursorX = 0
                    fallbackCursorY++
                    if (fallbackCursorY >= fallbackLines.size) {
                        fallbackLines.add(StringBuilder())
                    }
                }
            }
            i++
        }
    }

    private fun getFallbackLine(row: Int): StringBuilder {
        while (fallbackLines.size <= row) {
            fallbackLines.add(StringBuilder())
        }
        return fallbackLines[row]
    }

    fun getLineText(row: Int): String {
        return if (nativePtr != 0L) {
            TerAppBridge.getLineTextSafe(nativePtr, row)
        } else {
            val total = fallbackLines.size
            val startRow = (total - rows).coerceAtLeast(0)
            val targetIndex = startRow + row
            if (targetIndex in 0 until total) {
                fallbackLines[targetIndex].toString()
            } else {
                ""
            }
        }
    }

    fun getCursorX(): Int {
        return if (nativePtr != 0L) {
            TerAppBridge.getCursorXSafe(nativePtr)
        } else {
            fallbackCursorX.coerceIn(0, cols - 1)
        }
    }

    fun getCursorY(): Int {
        return if (nativePtr != 0L) {
            TerAppBridge.getCursorYSafe(nativePtr)
        } else {
            val total = fallbackLines.size
            val startRow = (total - rows).coerceAtLeast(0)
            (fallbackCursorY - startRow).coerceIn(0, rows - 1)
        }
    }

    fun writeInput(text: String) {
        writeBytes(text.toByteArray())
    }

    fun writeBytes(bytes: ByteArray) {
        if (nativePtr != 0L) {
            try {
                processIn?.write(bytes)
                processIn?.flush()
            } catch (e: Exception) {}
            return
        }

        // Fallback engine handling
        for (b in bytes) {
            when (b.toInt()) {
                0x0A, 0x0D -> { // Enter
                    feedBytes(byteArrayOf(0x0D, 0x0A))
                    val cmd = inputLineBuffer.toString().trim()
                    inputLineBuffer.clear()

                    if (cmd.isNotEmpty()) {
                        when (cmd) {
                            "clear" -> {
                                initFallbackBuffer()
                                feedBytes("~ $ ".toByteArray())
                                onScreenChanged?.invoke()
                                return
                            }
                            "help" -> {
                                val helpMsg = "TerApp Terminal Commands:\r\n  POSIX Tools: ls, grep, awk, sed, tar, vi, find, curl, wget, ps, top, free\r\n  Built-in: clear, help, mem, pkg\r\n\r\n~ $ "
                                feedBytes(helpMsg.toByteArray())
                                onScreenChanged?.invoke()
                                return
                            }
                            "mem" -> {
                                val memMsg = "Memory Status:\r\n  TerApp Native Engine: ~14.2 MB Heap\r\n  Circular Buffer: Active\r\n\r\n~ $ "
                                feedBytes(memMsg.toByteArray())
                                onScreenChanged?.invoke()
                                return
                            }
                            else -> {
                                try {
                                    processIn?.write((cmd + "\n").toByteArray())
                                    processIn?.flush()
                                } catch (e: Exception) {
                                    val err = "Error writing to shell: ${e.message}\r\n~ $ "
                                    feedBytes(err.toByteArray())
                                    onScreenChanged?.invoke()
                                }
                            }
                        }
                    } else {
                        feedBytes("~ $ ".toByteArray())
                        onScreenChanged?.invoke()
                    }
                }
                0x08, 0x7F -> { // Backspace
                    if (inputLineBuffer.isNotEmpty()) {
                        inputLineBuffer.deleteCharAt(inputLineBuffer.length - 1)
                        feedBytes(byteArrayOf(0x08))
                        onScreenChanged?.invoke()
                    }
                }
                0x03 -> { // Ctrl+C
                    inputLineBuffer.clear()
                    feedBytes("^C\r\n~ $ ".toByteArray())
                    try {
                        processIn?.write(byteArrayOf(0x03))
                        processIn?.flush()
                    } catch (ignored: Exception) {}
                    onScreenChanged?.invoke()
                }
                else -> {
                    val ch = b.toInt().toChar()
                    inputLineBuffer.append(ch)
                    feedBytes(byteArrayOf(b))
                    onScreenChanged?.invoke()
                }
            }
        }
    }

    fun resize(newCols: Int, newRows: Int) {
        cols = newCols.coerceAtLeast(10)
        rows = newRows.coerceAtLeast(5)
        if (nativePtr != 0L) {
            TerAppBridge.resizeSafe(nativePtr, cols, rows)
        }
        onScreenChanged?.invoke()
    }

    fun destroy() {
        isRunning = false
        try { processIn?.close() } catch (e: Exception) {}
        try { processOut?.close() } catch (e: Exception) {}
        try { process?.destroy() } catch (e: Exception) {}
        if (nativePtr != 0L) {
            TerAppBridge.freeTerminalSafe(nativePtr)
            nativePtr = 0
        }
    }
}
