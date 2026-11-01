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
    var maxScrollback: Int = 2000,
    var workingDir: File? = null,
    var context: Context? = null,
    val isFailSafe: Boolean = false
) {
    var nativePtr: Long = 0
        private set

    var ptyFd: Int = -1
        private set
    var pid: Int = 0
        private set

    private var process: Process? = null
    private var processIn: OutputStream? = null
    private var processOut: InputStream? = null
    private var isRunning = false
    var isFinished = false
        private set
    var exitCode = 0
        private set

    var onScreenChanged: (() -> Unit)? = null
    var onTitleChanged: ((String) -> Unit)? = null
    var onBell: (() -> Unit)? = null
    var onSessionFinished: ((TerminalSession) -> Unit)? = null

    private var lastBellCount = 0

    // Fallback in-memory buffer if native Rust engine or PTY is not linked
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
        val arch = "Arch: ${android.os.Build.SUPPORTED_ABIS.firstOrNull() ?: "arm64-v8a"} • Rust VT100 + PTY Core"
        val userland = if (isFailSafe) "Mode: FAIL-SAFE RECOVERY SHELL" else "Userland: BusyBox 1.36.1 POSIX"
        val pkg = if (isFailSafe) "System bin shell active" else "Tools: pkg • Type 'help' or 'pkg'"

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
        return sb.toString()
    }

    private fun startShellProcess() {
        try {
            val filesDir = context?.filesDir ?: workingDir?.parentFile ?: File("/data/data/com.terapp/files")
            val usrDir = File(filesDir, "usr")
            val usrBin = File(usrDir, "bin")
            val usrTmp = File(usrDir, "tmp")
            if (!usrTmp.exists()) usrTmp.mkdirs()

            val home = workingDir ?: File(filesDir, "home")
            if (!home.exists()) home.mkdirs()
            workingDir = home
            canonicalHome = try { home.canonicalPath } catch (e: Exception) { home.absolutePath }

            val shFile = File(usrBin, "sh")
            val busyboxFile = File(usrBin, "busybox")

            val shellCmd: String = if (isFailSafe) {
                if (File("/system/bin/sh").exists()) "/system/bin/sh" else "sh"
            } else if (shFile.exists() && shFile.canExecute()) {
                shFile.absolutePath
            } else if (busyboxFile.exists() && busyboxFile.canExecute()) {
                busyboxFile.absolutePath
            } else {
                val nativeLib = context?.let { BootstrapManager.getBusyboxBinary(it) }
                if (nativeLib != null && nativeLib.canExecute()) {
                    nativeLib.absolutePath
                } else if (File("/system/bin/sh").exists()) {
                    "/system/bin/sh"
                } else {
                    "sh"
                }
            }

            val shellArgs = if (!isFailSafe && shellCmd.endsWith("busybox")) {
                arrayOf(shellCmd, "sh", "-l")
            } else {
                arrayOf(shellCmd, "-l")
            }

            val envList = arrayOf(
                "TERM=xterm-256color",
                "COLORTERM=truecolor",
                "HOME=${home.absolutePath}",
                "PREFIX=${usrDir.absolutePath}",
                "TMPDIR=${usrTmp.absolutePath}",
                "SHELL=$shellCmd",
                "PATH=${if (isFailSafe) "/system/bin:/system/xbin" else "${usrBin.absolutePath}:/system/bin:/system/xbin"}",
                "BUSYBOX=${if (busyboxFile.exists()) busyboxFile.absolutePath else "busybox"}",
                "COLUMNS=$cols",
                "LINES=$rows",
                "LANG=en_US.UTF-8"
            )

            // Try spawning with native PTY first
            val pidArray = IntArray(1)
            val masterFd = TerAppBridge.createSubprocessSafe(
                cmd = shellCmd,
                cwd = home.absolutePath,
                args = shellArgs,
                envVars = envList,
                processIdArray = pidArray,
                rows = rows,
                cols = cols
            )

            if (masterFd >= 0) {
                ptyFd = masterFd
                pid = pidArray[0]
                isRunning = true

                // Feed initial banner
                feedBytes(createInitialBanner().toByteArray())

                // Start native PTY reader thread
                Thread({
                    val buffer = ByteArray(4096)
                    while (isRunning && ptyFd >= 0) {
                        val read = TerAppBridge.readPtySafe(ptyFd, buffer)
                        if (read > 0) {
                            val chunk = ByteArray(read)
                            System.arraycopy(buffer, 0, chunk, 0, read)
                            feedBytes(chunk)

                            // Title updates
                            val title = TerAppBridge.getTitleSafe(nativePtr)
                            if (title.isNotEmpty() && title != name) {
                                name = title
                                onTitleChanged?.invoke(title)
                            }

                            // Bell events
                            val bells = TerAppBridge.getBellCountSafe(nativePtr)
                            if (bells != lastBellCount) {
                                lastBellCount = bells
                                onBell?.invoke()
                            }

                            onScreenChanged?.invoke()
                        } else {
                            // Process ended
                            isFinished = true
                            exitCode = TerAppBridge.waitForSafe(pid)
                            feedBytes("\r\n\u001b[1;33m[Process completed (code $exitCode)]\u001b[0m\r\n".toByteArray())
                            onScreenChanged?.invoke()
                            onSessionFinished?.invoke(this)
                            break
                        }
                    }
                }, "PtyReader-$id").start()

                return
            }

            // Fallback to ProcessBuilder if PTY could not be spawned
            android.util.Log.w("TerminalSession", "PTY allocation failed, falling back to ProcessBuilder")
            val pb = ProcessBuilder(*shellArgs)
            pb.directory(home)
            val env = pb.environment()
            for (item in envList) {
                val idx = item.indexOf('=')
                if (idx != -1) {
                    env[item.substring(0, idx)] = item.substring(idx + 1)
                }
            }
            pb.redirectErrorStream(true)

            val p = pb.start()
            process = p
            processIn = p.outputStream
            processOut = p.inputStream
            isRunning = true

            feedBytes(createInitialBanner().toByteArray())
            feedBytes(currentPrompt.toByteArray())

            Thread({
                val buffer = ByteArray(4096)
                while (isRunning) {
                    try {
                        val read = processOut?.read(buffer) ?: -1
                        if (read > 0) {
                            val chunk = ByteArray(read)
                            System.arraycopy(buffer, 0, chunk, 0, read)
                            feedBytes(chunk)
                            onScreenChanged?.invoke()
                        } else if (read == -1) {
                            isFinished = true
                            exitCode = try { p.waitFor() } catch (e: Exception) { 0 }
                            feedBytes("\r\n\u001b[1;33m[Process completed (code $exitCode)]\u001b[0m\r\n".toByteArray())
                            onScreenChanged?.invoke()
                            onSessionFinished?.invoke(this)
                            break
                        }
                    } catch (e: Exception) {
                        break
                    }
                }
            }, "FallbackReader-$id").start()

        } catch (e: Exception) {
            val err = "\u001b[1;31mFailed to spawn shell: ${e.message}\u001b[0m\r\n"
            feedBytes(err.toByteArray())
        }
    }

    fun feedBytes(bytes: ByteArray) {
        val normalized = normalizeNewlines(bytes)
        if (nativePtr != 0L) {
            TerAppBridge.feedBytesSafe(nativePtr, normalized)
        }
        feedFallback(normalized)
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
                    // Skip ANSI escape sequence in fallback
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
        return if (nativePtr != 0L) {
            TerAppBridge.getScrollbackLengthSafe(nativePtr)
        } else {
            synchronized(fallbackLines) {
                (fallbackLines.size - rows).coerceAtLeast(0)
            }
        }
    }

    fun getLineText(row: Int, scrollOffset: Int = 0): String {
        if (nativePtr != 0L) {
            val text = TerAppBridge.getLineTextSafe(nativePtr, row, scrollOffset)
            if (text.isNotEmpty()) return text
        }
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

    fun getLineColors(
        row: Int,
        scrollOffset: Int = 0,
        defaultFg: Int,
        defaultBg: Int,
        palette16: IntArray
    ): IntArray? {
        if (nativePtr != 0L) {
            return TerAppBridge.getLineColorsSafe(nativePtr, row, scrollOffset, defaultFg, defaultBg, palette16)
        }
        return null
    }

    fun getVisibleText(): String {
        val sb = StringBuilder()
        for (r in 0 until rows) {
            val line = getLineText(r, 0).trimEnd()
            if (line.isNotEmpty() || r < rows - 1) {
                sb.append(line).append("\n")
            }
        }
        return sb.toString().trimEnd()
    }

    fun getCursorX(): Int {
        return if (nativePtr != 0L) {
            TerAppBridge.getCursorXSafe(nativePtr).coerceIn(0, (cols - 1).coerceAtLeast(0))
        } else {
            fallbackCursorX.coerceIn(0, (cols - 1).coerceAtLeast(0))
        }
    }

    fun getCursorY(): Int {
        return if (nativePtr != 0L) {
            TerAppBridge.getCursorYSafe(nativePtr).coerceIn(0, (rows - 1).coerceAtLeast(0))
        } else {
            val total = fallbackLines.size
            val startRow = (total - rows).coerceAtLeast(0)
            (fallbackCursorY - startRow).coerceIn(0, (rows - 1).coerceAtLeast(0))
        }
    }

    fun isCursorVisible(): Boolean {
        return if (nativePtr != 0L) {
            TerAppBridge.isCursorVisibleSafe(nativePtr)
        } else {
            true
        }
    }

    fun isAltScreen(): Boolean {
        return if (nativePtr != 0L) {
            TerAppBridge.isAltScreenSafe(nativePtr)
        } else {
            false
        }
    }

    fun writeInput(text: String) {
        writeBytes(text.toByteArray(StandardCharsets.UTF_8))
    }

    fun writeBytes(bytes: ByteArray) {
        if (ptyFd >= 0) {
            // Write directly to master PTY - shell echoes and processes
            TerAppBridge.writePtySafe(ptyFd, bytes)
        } else if (processIn != null) {
            // Fallback line buffered
            for (b in bytes) {
                when (b.toInt()) {
                    0x0A, 0x0D -> {
                        feedBytes(byteArrayOf(0x0D, 0x0A))
                        val cmd = inputLineBuffer.toString().trim()
                        inputLineBuffer.clear()
                        try {
                            processIn?.write("$cmd\n".toByteArray())
                            processIn?.flush()
                        } catch (ignored: Exception) {}
                        feedBytes(currentPrompt.toByteArray())
                        onScreenChanged?.invoke()
                    }
                    0x08, 0x7F -> {
                        if (inputLineBuffer.isNotEmpty()) {
                            inputLineBuffer.deleteCharAt(inputLineBuffer.length - 1)
                            feedBytes(byteArrayOf(0x08, 0x20, 0x08))
                            onScreenChanged?.invoke()
                        }
                    }
                    else -> {
                        inputLineBuffer.append(b.toInt().toChar())
                        feedBytes(byteArrayOf(b))
                        onScreenChanged?.invoke()
                    }
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
        if (ptyFd >= 0) {
            TerAppBridge.setPtyWindowSizeSafe(ptyFd, rows, cols)
        } else {
            try {
                processIn?.write("stty cols $cols rows $rows 2>/dev/null\n".toByteArray())
                processIn?.flush()
            } catch (ignored: Exception) {}
        }
        onScreenChanged?.invoke()
    }

    fun destroy() {
        isRunning = false
        if (ptyFd >= 0) {
            TerAppBridge.closePtySafe(ptyFd)
            ptyFd = -1
        }
        try { processIn?.close() } catch (ignored: Exception) {}
        try { processOut?.close() } catch (ignored: Exception) {}
        try { process?.destroy() } catch (ignored: Exception) {}
        if (nativePtr != 0L) {
            TerAppBridge.freeTerminalSafe(nativePtr)
            nativePtr = 0
        }
    }
}
