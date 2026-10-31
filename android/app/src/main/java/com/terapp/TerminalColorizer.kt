package com.terapp

import java.io.File

object TerminalColors {
    const val PROMPT_PATH = 0xFF00E5FF.toInt()     // Vibrant Cyan
    const val PROMPT_SYMBOL = 0xFFFFD54F.toInt()   // Amber Gold
    const val COMMAND = 0xFF00E676.toInt()         // Vibrant Green
    const val EXECUTABLE = 0xFF2ECC71.toInt()      // Emerald Green
    const val FOLDER = 0xFF42A5F5.toInt()          // Sky Blue
    const val OPTION = 0xFFFFA726.toInt()          // Warm Orange
    const val STRING = 0xFFFFF176.toInt()          // Pale Yellow
    const val OPERATOR = 0xFFBA68C8.toInt()        // Lavender / Purple
    const val NUMBER = 0xFFFF8A65.toInt()          // Coral Orange
    const val ARCHIVE = 0xFFFF5252.toInt()         // Red
    const val ERROR = 0xFFFF5252.toInt()           // Red
    const val BANNER_BORDER = 0xFF00E5FF.toInt()   // Cyan
    const val BANNER_TITLE = 0xFF00E676.toInt()    // Bright Green
}

data class ColorSpan(
    val start: Int,
    val end: Int,
    val color: Int,
    val isBold: Boolean = false,
    val isUnderline: Boolean = false
)

object TerminalColorizer {
    private val KNOWN_COMMANDS = hashSetOf(
        "ls", "cd", "cat", "pwd", "echo", "clear", "help", "mem", "exit",
        "grep", "egrep", "fgrep", "awk", "sed", "tar", "gzip", "gunzip",
        "vi", "vim", "nano", "head", "tail", "wc", "sort", "uniq", "find",
        "xargs", "which", "whoami", "uname", "touch", "chmod", "chown",
        "df", "du", "ps", "kill", "top", "free", "uptime", "curl", "wget",
        "env", "export", "bzip2", "xz", "unzip", "base64", "md5sum",
        "sha256sum", "ping", "netstat", "tree", "diff", "patch", "more",
        "less", "stat", "strings", "sh", "bash", "ash", "su", "alias",
        "cp", "mv", "rm", "mkdir", "rmdir"
    )

    private val OPERATORS = hashSetOf("|", "||", "&&", ";", ">", ">>", "<", "&")

    // Cache of files in current workingDir to avoid disk I/O on every frame
    private var cachedWorkingDirPath: String? = null
    private var lastCacheTimestamp: Long = 0L
    private val cachedDirs = HashSet<String>()
    private val cachedExecutables = HashSet<String>()
    private val cachedArchives = HashSet<String>()

    @Synchronized
    fun refreshCache(workingDir: File?) {
        val now = System.currentTimeMillis()
        val path = workingDir?.absolutePath
        if (path == cachedWorkingDirPath && (now - lastCacheTimestamp < 1500L)) {
            return
        }
        cachedWorkingDirPath = path
        lastCacheTimestamp = now
        cachedDirs.clear()
        cachedExecutables.clear()
        cachedArchives.clear()

        // Default special folders
        cachedDirs.add("..")
        cachedDirs.add(".")

        if (workingDir != null && workingDir.isDirectory) {
            val list = workingDir.listFiles()
            if (list != null) {
                for (f in list) {
                    val name = f.name
                    if (f.isDirectory) {
                        cachedDirs.add(name)
                        cachedDirs.add("$name/")
                    } else {
                        if (name.endsWith(".sh") || f.canExecute()) {
                            cachedExecutables.add(name)
                        }
                        if (name.endsWith(".zip") || name.endsWith(".tar") || name.endsWith(".gz") ||
                            name.endsWith(".apk") || name.endsWith(".tgz") || name.endsWith(".bz2") ||
                            name.endsWith(".xz") || name.endsWith(".7z")) {
                            cachedArchives.add(name)
                        }
                    }
                }
            }
        }
    }

    fun colorize(line: String, defaultColor: Int, workingDir: File?): List<ColorSpan> {
        if (line.isEmpty()) return emptyList()

        refreshCache(workingDir)
        val rawSpans = ArrayList<ColorSpan>()

        // 1. Check for Banner lines
        if (line.startsWith("====")) {
            rawSpans.add(ColorSpan(0, line.length, TerminalColors.BANNER_BORDER, isBold = true))
            return fillGaps(line.length, rawSpans, defaultColor)
        }
        if (line.contains("TerApp (Rust Core)")) {
            rawSpans.add(ColorSpan(0, line.length, TerminalColors.BANNER_TITLE, isBold = true))
            return fillGaps(line.length, rawSpans, defaultColor)
        }

        // 2. Check for Prompt line
        val promptRegex = Regex("""^(\s*[\w\.\~\-\/\@\:]+)\s*([\$#])(\s*)(.*)$""")
        val match = promptRegex.find(line)

        if (match != null) {
            val groups = match.groups
            val gPath = groups[1]
            val gSym = groups[2]
            val gCmd = groups[4]

            if (gPath != null) {
                rawSpans.add(ColorSpan(gPath.range.first, gPath.range.last + 1, TerminalColors.PROMPT_PATH, isBold = true))
            }
            if (gSym != null) {
                rawSpans.add(ColorSpan(gSym.range.first, gSym.range.last + 1, TerminalColors.PROMPT_SYMBOL, isBold = true))
            }
            if (gCmd != null && gCmd.value.isNotEmpty()) {
                colorizeCommandTokens(gCmd.value, gCmd.range.first, rawSpans, defaultColor)
            }
            return fillGaps(line.length, rawSpans, defaultColor)
        }

        // 3. Check for Error lines
        val lower = line.lowercase()
        if (lower.contains("error:") || lower.contains("failed") || lower.contains("not found") ||
            lower.contains("permission denied") || lower.contains("no such file")) {
            rawSpans.add(ColorSpan(0, line.length, TerminalColors.ERROR))
            return fillGaps(line.length, rawSpans, defaultColor)
        }

        // 4. Output / File listing lines (ls, cat, help, etc.)
        colorizeOutputLine(line, rawSpans, defaultColor)

        return fillGaps(line.length, rawSpans, defaultColor)
    }

    private fun colorizeCommandTokens(cmdText: String, baseOffset: Int, spans: MutableList<ColorSpan>, defaultColor: Int) {
        var idx = 0
        var isExpectCommand = true

        while (idx < cmdText.length) {
            // Skip whitespace
            if (cmdText[idx].isWhitespace()) {
                idx++
                continue
            }

            // String literals: "..." or '...'
            if (cmdText[idx] == '"' || cmdText[idx] == '\'') {
                val quote = cmdText[idx]
                val start = idx
                idx++
                while (idx < cmdText.length && cmdText[idx] != quote) {
                    if (cmdText[idx] == '\\' && idx + 1 < cmdText.length) {
                        idx += 2
                    } else {
                        idx++
                    }
                }
                if (idx < cmdText.length) idx++ // include closing quote
                spans.add(ColorSpan(baseOffset + start, baseOffset + idx, TerminalColors.STRING))
                isExpectCommand = false
                continue
            }

            // Operators: |, &&, ||, ;, >, >>
            val op = getOperatorAt(cmdText, idx)
            if (op != null) {
                spans.add(ColorSpan(baseOffset + idx, baseOffset + idx + op.length, TerminalColors.OPERATOR, isBold = true))
                idx += op.length
                isExpectCommand = true
                continue
            }

            // General token
            val start = idx
            while (idx < cmdText.length && !cmdText[idx].isWhitespace() && getOperatorAt(cmdText, idx) == null && cmdText[idx] != '"' && cmdText[idx] != '\'') {
                idx++
            }
            val token = cmdText.substring(start, idx)
            val tokenStart = baseOffset + start
            val tokenEnd = baseOffset + idx

            if (isExpectCommand) {
                if (token.endsWith(".sh") || token.startsWith("./") || cachedExecutables.contains(token)) {
                    spans.add(ColorSpan(tokenStart, tokenEnd, TerminalColors.EXECUTABLE, isBold = true))
                } else {
                    spans.add(ColorSpan(tokenStart, tokenEnd, TerminalColors.COMMAND, isBold = true))
                }
                isExpectCommand = false
            } else {
                // Argument
                if (token.startsWith("-") || token.startsWith("+")) {
                    spans.add(ColorSpan(tokenStart, tokenEnd, TerminalColors.OPTION))
                } else if (token.endsWith("/") || cachedDirs.contains(token) || token == "." || token == "..") {
                    spans.add(ColorSpan(tokenStart, tokenEnd, TerminalColors.FOLDER, isBold = true))
                } else if (token.endsWith(".sh") || cachedExecutables.contains(token) || token.startsWith("./")) {
                    spans.add(ColorSpan(tokenStart, tokenEnd, TerminalColors.EXECUTABLE, isUnderline = true))
                } else if (cachedArchives.contains(token) || token.endsWith(".zip") || token.endsWith(".tar") || token.endsWith(".gz") || token.endsWith(".apk")) {
                    spans.add(ColorSpan(tokenStart, tokenEnd, TerminalColors.ARCHIVE))
                } else if (token.all { it.isDigit() }) {
                    spans.add(ColorSpan(tokenStart, tokenEnd, TerminalColors.NUMBER))
                }
            }
        }
    }

    private fun colorizeOutputLine(line: String, spans: MutableList<ColorSpan>, defaultColor: Int) {
        var idx = 0
        while (idx < line.length) {
            if (line[idx].isWhitespace()) {
                idx++
                continue
            }
            val start = idx
            while (idx < line.length && !line[idx].isWhitespace()) {
                idx++
            }
            val rawToken = line.substring(start, idx)
            val cleanToken = rawToken.trimEnd(',', ':', ';', ')')
            val tokenStart = start
            val tokenEnd = start + cleanToken.length

            if (cleanToken.endsWith("/") || cachedDirs.contains(cleanToken) || cleanToken == "." || cleanToken == "..") {
                spans.add(ColorSpan(tokenStart, tokenEnd, TerminalColors.FOLDER, isBold = true))
            } else if (cleanToken.endsWith(".sh") || cachedExecutables.contains(cleanToken)) {
                spans.add(ColorSpan(tokenStart, tokenEnd, TerminalColors.EXECUTABLE, isBold = true))
            } else if (cachedArchives.contains(cleanToken) || cleanToken.endsWith(".zip") || cleanToken.endsWith(".tar") || cleanToken.endsWith(".gz") || cleanToken.endsWith(".apk")) {
                spans.add(ColorSpan(tokenStart, tokenEnd, TerminalColors.ARCHIVE))
            } else if (cleanToken.startsWith("http://") || cleanToken.startsWith("https://")) {
                spans.add(ColorSpan(tokenStart, tokenEnd, TerminalColors.FOLDER, isUnderline = true))
            } else if (cleanToken.startsWith("-")) {
                spans.add(ColorSpan(tokenStart, tokenEnd, TerminalColors.OPTION))
            } else if (KNOWN_COMMANDS.contains(cleanToken)) {
                spans.add(ColorSpan(tokenStart, tokenEnd, TerminalColors.COMMAND))
            }
        }
    }

    private fun getOperatorAt(s: String, idx: Int): String? {
        if (idx + 1 < s.length) {
            val two = s.substring(idx, idx + 2)
            if (OPERATORS.contains(two)) return two
        }
        val one = s.substring(idx, idx + 1)
        if (OPERATORS.contains(one)) return one
        return null
    }

    private fun fillGaps(totalLength: Int, spans: List<ColorSpan>, defaultColor: Int): List<ColorSpan> {
        if (spans.isEmpty()) {
            return listOf(ColorSpan(0, totalLength, defaultColor))
        }

        val sorted = spans.sortedBy { it.start }
        val result = ArrayList<ColorSpan>()
        var cursor = 0

        for (span in sorted) {
            if (span.start > cursor) {
                result.add(ColorSpan(cursor, span.start, defaultColor))
            }
            if (span.end > cursor) {
                val realStart = maxOf(cursor, span.start)
                result.add(span.copy(start = realStart))
                cursor = span.end
            }
        }

        if (cursor < totalLength) {
            result.add(ColorSpan(cursor, totalLength, defaultColor))
        }

        return result
    }
}

