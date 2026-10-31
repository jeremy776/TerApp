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
    var cols: Int = 45,
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
    private var currentPrompt = "~ $ "
    private var canonicalHome: String = ""

    init {
        nativePtr = TerAppBridge.initTerminalSafe(cols, rows, maxScrollback)
        initFallbackBuffer()
        startShellProcess()
    }

    fun initFallbackBuffer() {
        synchronized(fallbackLines) {
            fallbackLines.clear()
            fallbackLines.add(StringBuilder())
            fallbackCursorX = 0
            fallbackCursorY = 0
        }
    }

    private fun createInitialBanner(): String {
        val width = (cols - 2).coerceIn(32, 54)
        val lineHoriz = "─".repeat(width - 2)
        val title = "TerApp Terminal ${VersionManager.CURRENT_VERSION}"
        val arch = "Arch: ${android.os.Build.SUPPORTED_ABIS.firstOrNull() ?: "arm64-v8a"} • Rust VT100 Core"
        val userland = "Userland: BusyBox 1.36.1 POSIX"
        val pkg = "Tools: pkg • Type 'help' or 'pkg'"

        fun padCenter(text: String, len: Int): String {
            val trimmed = if (text.length > len) text.substring(0, len) else text
            val padTotal = len - trimmed.length
            val padLeft = padTotal / 2
            val padRight = padTotal - padLeft
            return " ".repeat(padLeft) + trimmed + " ".repeat(padRight)
        }

        fun padLeft(text: String, len: Int): String {
            val trimmed = if (text.length > len) text.substring(0, len) else text
            return " " + trimmed + " ".repeat((len - trimmed.length - 1).coerceAtLeast(0))
        }

        val contentLen = width - 2
        val sb = StringBuilder()
        sb.append("\u001b[1;36m┌").append(lineHoriz).append("┐\u001b[0m\r\n")
        sb.append("\u001b[1;32m│").append(padCenter(title, contentLen)).append("│\u001b[0m\r\n")
        sb.append("\u001b[0;37m│").append(padLeft(arch, contentLen)).append("│\u001b[0m\r\n")
        sb.append("\u001b[0;37m│").append(padLeft(userland, contentLen)).append("│\u001b[0m\r\n")
        sb.append("\u001b[0;36m│").append(padLeft(pkg, contentLen)).append("│\u001b[0m\r\n")
        sb.append("\u001b[1;36m└").append(lineHoriz).append("┘\u001b[0m\r\n")
        sb.append("\u001b[0;90mChecking version status...\u001b[0m\r\n")
        sb.append("~ $ ")
        return sb.toString()
    }

    private fun startShellProcess() {
        try {
            val filesDir = context?.filesDir ?: workingDir?.parentFile ?: File("/data/data/com.terapp/files")
            val usrBin = File(filesDir, "usr/bin")
            val home = workingDir ?: File(filesDir, "home")
            if (!home.exists()) home.mkdirs()
            workingDir = home
            canonicalHome = try { home.canonicalPath } catch (e: Exception) { home.absolutePath }

            val shFile = File(usrBin, "sh")
            val shellPath = if (shFile.exists() && shFile.canExecute()) {
                shFile.absolutePath
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
            pb.environment()["COLUMNS"] = cols.toString()
            pb.environment()["LINES"] = rows.toString()
            pb.redirectErrorStream(true)

            val p = pb.start()
            process = p
            processIn = p.outputStream
            processOut = p.inputStream
            isRunning = true

            // Initial banner
            feedBytes(createInitialBanner().toByteArray())

            // Background version check
            VersionManager.checkForUpdates { isLatest, latestVer, releaseUrl ->
                if (!isRunning) return@checkForUpdates
                val statusMsg = if (isLatest) {
                    "\r\u001b[2K\u001b[1;32m✔ Version ${VersionManager.CURRENT_VERSION} is up-to-date\u001b[0m\r\n$currentPrompt"
                } else {
                    "\r\u001b[2K\u001b[1;33m⚡ Update available: $latestVer (Installed: ${VersionManager.CURRENT_VERSION})\u001b[0m\r\n\u001b[0;36m  Download: $releaseUrl\u001b[0m\r\n$currentPrompt"
                }
                feedBytes(statusMsg.toByteArray())
                onScreenChanged?.invoke()
            }

            // Background reader thread from process
            Thread {
                val buffer = ByteArray(4096)
                val streamBuffer = StringBuilder()
                val doneTag = "[TERAPP_DONE"

                while (isRunning) {
                    try {
                        val read = processOut?.read(buffer) ?: -1
                        if (read > 0) {
                            val chunk = String(buffer, 0, read, StandardCharsets.UTF_8)
                            streamBuffer.append(chunk)

                            while (true) {
                                val idx = streamBuffer.indexOf(doneTag)
                                if (idx != -1) {
                                    val endIdx = streamBuffer.indexOf("]", idx)
                                    if (endIdx != -1) {
                                        val before = streamBuffer.substring(0, idx)
                                        val tag = streamBuffer.substring(idx, endIdx + 1)
                                        streamBuffer.delete(0, endIdx + 1)

                                        if (streamBuffer.startsWith("\r\n")) {
                                            streamBuffer.delete(0, 2)
                                        } else if (streamBuffer.startsWith("\n")) {
                                            streamBuffer.delete(0, 1)
                                        }

                                        if (before.isNotEmpty()) {
                                            feedBytes(before.toByteArray())
                                            // If output didn't end with a newline, break line before prompt
                                            if (!before.endsWith("\n") && !before.endsWith("\r")) {
                                                feedBytes(byteArrayOf(0x0D, 0x0A))
                                            }
                                        }

                                        var promptPath = "~"
                                        if (tag.startsWith("[TERAPP_DONE:") && tag.endsWith("]")) {
                                            val rawPath = tag.substring("[TERAPP_DONE:".length, tag.length - 1).trim()
                                            val canonicalCurrent = try {
                                                File(rawPath).canonicalPath
                                            } catch (e: Exception) {
                                                rawPath
                                            }
                                            workingDir = File(canonicalCurrent)

                                            promptPath = if (canonicalCurrent == canonicalHome) {
                                                "~"
                                            } else if (canonicalCurrent.startsWith("$canonicalHome/")) {
                                                "~/" + canonicalCurrent.removePrefix("$canonicalHome/")
                                            } else {
                                                File(canonicalCurrent).name.ifEmpty { canonicalCurrent }
                                            }
                                        }

                                        currentPrompt = "$promptPath $ "
                                        feedBytes(currentPrompt.toByteArray())
                                        onScreenChanged?.invoke()
                                        continue
                                    } else {
                                        break
                                    }
                                } else {
                                    val safeLen = streamBuffer.length - doneTag.length
                                    if (safeLen > 0) {
                                        val toFeed = streamBuffer.substring(0, safeLen)
                                        streamBuffer.delete(0, safeLen)
                                        feedBytes(toFeed.toByteArray())
                                        onScreenChanged?.invoke()
                                    }
                                    break
                                }
                            }
                        } else if (read == -1) {
                            if (streamBuffer.isNotEmpty()) {
                                feedBytes(streamBuffer.toString().toByteArray())
                                streamBuffer.clear()
                                onScreenChanged?.invoke()
                            }
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

    fun reflowLines(newCols: Int) {
        if (newCols <= 0) return
        synchronized(fallbackLines) {
            val reflowed = ArrayList<StringBuilder>()
            for (sb in fallbackLines) {
                val str = sb.toString()
                if (str.length <= newCols) {
                    reflowed.add(StringBuilder(str))
                } else {
                    var offset = 0
                    while (offset < str.length) {
                        val end = (offset + newCols).coerceAtMost(str.length)
                        reflowed.add(StringBuilder(str.substring(offset, end)))
                        offset = end
                    }
                }
            }
            fallbackLines.clear()
            fallbackLines.addAll(reflowed)
            while (fallbackLines.size > maxScrollback + rows) {
                fallbackLines.removeAt(0)
            }
            fallbackCursorY = (fallbackLines.size - 1).coerceAtLeast(0)
            fallbackCursorX = fallbackLines.lastOrNull()?.length?.coerceIn(0, newCols - 1) ?: 0
        }
    }

    fun feedBytes(bytes: ByteArray) {
        val normalized = normalizeNewlines(bytes)
        feedFallback(normalized)
        if (nativePtr != 0L) {
            TerAppBridge.feedBytesSafe(nativePtr, normalized)
        }
    }

    private fun normalizeNewlines(bytes: ByteArray): ByteArray {
        var needed = 0
        for (i in bytes.indices) {
            if (bytes[i] == 0x0A.toByte() && (i == 0 || bytes[i - 1] != 0x0D.toByte())) {
                needed++
            }
        }
        if (needed == 0) return bytes
        val out = ByteArray(bytes.size + needed)
        var j = 0
        for (i in bytes.indices) {
            if (bytes[i] == 0x0A.toByte() && (i == 0 || bytes[i - 1] != 0x0D.toByte())) {
                out[j++] = 0x0D.toByte()
            }
            out[j++] = bytes[i]
        }
        return out
    }

    private fun feedFallback(bytes: ByteArray) {
        synchronized(fallbackLines) {
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
                    while (fallbackLines.size > maxScrollback + rows) {
                        fallbackLines.removeAt(0)
                        if (fallbackCursorY > 0) fallbackCursorY--
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
                    // Check right edge auto-wrap
                    if (fallbackCursorX >= cols) {
                        fallbackCursorX = 0
                        fallbackCursorY++
                        if (fallbackCursorY >= fallbackLines.size) {
                            fallbackLines.add(StringBuilder())
                        }
                        while (fallbackLines.size > maxScrollback + rows) {
                            fallbackLines.removeAt(0)
                            if (fallbackCursorY > 0) fallbackCursorY--
                        }
                    }

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
                        while (fallbackLines.size > maxScrollback + rows) {
                            fallbackLines.removeAt(0)
                            if (fallbackCursorY > 0) fallbackCursorY--
                        }
                    }
                }
                i++
            }
        }
    }

    private fun getFallbackLine(row: Int): StringBuilder {
        synchronized(fallbackLines) {
            while (fallbackLines.size <= row) {
                fallbackLines.add(StringBuilder())
            }
            return fallbackLines[row]
        }
    }

    fun getScrollbackLength(): Int {
        synchronized(fallbackLines) {
            return (fallbackLines.size - rows).coerceAtLeast(0)
        }
    }

    fun getLineText(row: Int, scrollOffset: Int = 0): String {
        synchronized(fallbackLines) {
            val total = fallbackLines.size
            val startRow = (total - rows).coerceAtLeast(0)
            val targetIndex = startRow + row - scrollOffset
            return if (targetIndex in 0 until total) {
                val raw = fallbackLines[targetIndex].toString()
                if (raw.length > cols) raw.substring(0, cols) else raw
            } else {
                ""
            }
        }
    }

    fun getVisibleText(): String {
        val sb = StringBuilder()
        synchronized(fallbackLines) {
            val total = fallbackLines.size
            val start = (total - rows).coerceAtLeast(0)
            for (i in start until total) {
                val raw = fallbackLines[i].toString().trimEnd()
                sb.append(if (raw.length > cols) raw.substring(0, cols) else raw)
                if (i < total - 1) sb.append("\n")
            }
        }
        return sb.toString().trim()
    }

    fun getCursorX(): Int {
        return if (nativePtr != 0L) {
            TerAppBridge.getCursorXSafe(nativePtr).coerceIn(0, cols - 1)
        } else {
            fallbackCursorX.coerceIn(0, cols - 1)
        }
    }

    fun getCursorY(): Int {
        return if (nativePtr != 0L) {
            TerAppBridge.getCursorYSafe(nativePtr).coerceIn(0, rows - 1)
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
                                if (nativePtr != 0L) {
                                    TerAppBridge.feedBytesSafe(nativePtr, "\u001b[2J\u001b[H".toByteArray())
                                }
                                feedBytes(currentPrompt.toByteArray())
                                onScreenChanged?.invoke()
                                return
                            }
                            "help" -> {
                                val engine = if (nativePtr != 0L) "Rust VT100 Core" else "Fallback JVM Buffer"
                                val helpMsg = "TerApp Terminal Commands [$engine]:\r\n" +
                                              "  Version: ${VersionManager.CURRENT_VERSION}\r\n" +
                                              "  POSIX: ls, grep, awk, sed, tar, vi, curl, wget, ps, top\r\n" +
                                              "  Package Manager: pkg (help, list, search, doctor, update)\r\n" +
                                              "  Built-in: clear, help, mem, version\r\n$currentPrompt"
                                feedBytes(helpMsg.toByteArray())
                                onScreenChanged?.invoke()
                                return
                            }
                            "version" -> {
                                val verMsg = "TerApp Mobile Terminal ${VersionManager.CURRENT_VERSION}\r\n" +
                                             "  Architecture: ${android.os.Build.SUPPORTED_ABIS.firstOrNull() ?: "arm64-v8a"}\r\n" +
                                             "  Engine: Rust VT100 Engine (Zero-Allocation Parser)\r\n" +
                                             "  Userland: BusyBox 1.36.1 POSIX\r\n" +
                                             "  Releases: ${VersionManager.REPO_URL}/releases\r\n" +
                                             "Checking for updates...\r\n"
                                feedBytes(verMsg.toByteArray())
                                VersionManager.checkForUpdates { isLatest, latestVer, releaseUrl ->
                                    val status = if (isLatest) {
                                        "\u001b[1;32m✔ Version is up-to-date (${VersionManager.CURRENT_VERSION})\u001b[0m\r\n$currentPrompt"
                                    } else {
                                        "\u001b[1;33m⚡ Update available: $latestVer\u001b[0m\r\n\u001b[0;36m  $releaseUrl\u001b[0m\r\n$currentPrompt"
                                    }
                                    feedBytes(status.toByteArray())
                                    onScreenChanged?.invoke()
                                }
                                onScreenChanged?.invoke()
                                return
                            }
                            "mem" -> {
                                val engine = if (nativePtr != 0L) "Rust VT100 Core (~324 KB)" else "Fallback JVM Buffer"
                                val memMsg = "Memory Status:\r\n  Terminal Engine: $engine\r\n  Circular Ring Buffer: Active\r\n  POSIX Userland: BusyBox 1.36.1\r\n$currentPrompt"
                                feedBytes(memMsg.toByteArray())
                                onScreenChanged?.invoke()
                                return
                            }
                            else -> {
                                try {
                                    val script = "$cmd; echo -n \"[TERAPP_DONE:\${PWD}]\"\n"
                                    processIn?.write(script.toByteArray())
                                    processIn?.flush()
                                } catch (e: Exception) {
                                    val err = "Error writing to shell: ${e.message}\r\n$currentPrompt"
                                    feedBytes(err.toByteArray())
                                    onScreenChanged?.invoke()
                                }
                            }
                        }
                    } else {
                        feedBytes(currentPrompt.toByteArray())
                        onScreenChanged?.invoke()
                    }
                }
                0x08, 0x7F -> { // Backspace
                    if (inputLineBuffer.isNotEmpty()) {
                        inputLineBuffer.deleteCharAt(inputLineBuffer.length - 1)
                        if (nativePtr != 0L) {
                            feedBytes(byteArrayOf(0x08, 0x20, 0x08))
                        } else {
                            feedBytes(byteArrayOf(0x08))
                        }
                        onScreenChanged?.invoke()
                    }
                }
                0x03 -> { // Ctrl+C
                    inputLineBuffer.clear()
                    feedBytes("^C\r\n$currentPrompt".toByteArray())
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
        val oldCols = cols
        cols = newCols.coerceAtLeast(10)
        rows = newRows.coerceAtLeast(5)

        if (cols != oldCols) {
            reflowLines(cols)
        }

        try {
            processIn?.write("export COLUMNS=$cols LINES=$rows; stty cols $cols rows $rows 2>/dev/null\n".toByteArray())
            processIn?.flush()
        } catch (ignored: Exception) {}

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
