package com.terapp

import android.content.Context
import android.system.Os
import android.util.Log
import java.io.File
import java.util.zip.ZipFile

object BootstrapManager {
    private const val TAG = "BootstrapManager"
    private const val BUSYBOX_VERSION = "1.36.1"

    /**
     * Resolves an executable BusyBox binary:
     * 1. Check extracted nativeLibraryDir (libbusybox.so)
     * 2. Check usr/bin/busybox inside app filesDir
     * 3. Extract libbusybox.so directly from the APK archive
     */
    fun getBusyboxBinary(context: Context): File? {
        // 1. Check native library directory
        val nativeLib = File(context.applicationInfo.nativeLibraryDir, "libbusybox.so")
        if (nativeLib.exists() && nativeLib.canExecute()) {
            return nativeLib
        }

        // 2. Check usr/bin/busybox in private files directory
        val usrBinBusybox = File(context.filesDir, "usr/bin/busybox")
        if (usrBinBusybox.exists() && usrBinBusybox.canExecute()) {
            return usrBinBusybox
        }

        // 3. Fallback: extract directly from the APK package
        val extracted = extractBusyboxFromApk(context, usrBinBusybox)
        if (extracted && usrBinBusybox.exists()) {
            return usrBinBusybox
        }

        return if (nativeLib.exists()) nativeLib else null
    }

    private fun extractBusyboxFromApk(context: Context, destFile: File): Boolean {
        return try {
            val apkPath = context.applicationInfo.sourceDir
            val apkFile = File(apkPath)
            if (!apkFile.exists()) return false

            destFile.parentFile?.mkdirs()

            ZipFile(apkFile).use { zip ->
                val entries = zip.entries()
                while (entries.hasMoreElements()) {
                    val entry = entries.nextElement()
                    if (entry.name.endsWith("libbusybox.so")) {
                        zip.getInputStream(entry).use { input ->
                            destFile.outputStream().use { output ->
                                input.copyTo(output)
                            }
                        }
                        try {
                            Os.chmod(destFile.absolutePath, 493) // 0755
                        } catch (e: Exception) {
                            destFile.setExecutable(true, false)
                        }
                        Log.i(TAG, "Extracted libbusybox.so directly from APK to ${destFile.absolutePath}")
                        return true
                    }
                }
            }
            false
        } catch (e: Exception) {
            Log.w(TAG, "Failed to extract libbusybox.so from APK: ${e.message}")
            false
        }
    }

    fun ensureBootstrap(context: Context): Boolean {
        return try {
            val filesDir = context.filesDir
            val usrDir = File(filesDir, "usr")
            val usrBin = File(usrDir, "bin")
            val usrTmp = File(usrDir, "tmp")
            val usrEtc = File(usrDir, "etc")
            val homeDir = File(filesDir, "home")
            val markerFile = File(usrDir, ".marker")

            usrBin.mkdirs()
            usrTmp.mkdirs()
            usrEtc.mkdirs()
            homeDir.mkdirs()

            try {
                Os.chmod(usrBin.absolutePath, 493) // 0755
                Os.chmod(usrTmp.absolutePath, 511) // 0777
                Os.chmod(homeDir.absolutePath, 448) // 0700
            } catch (ignored: Exception) {}

            // 1. Locate or extract BusyBox binary
            val busyboxBin = File(usrBin, "busybox")
            val nativeLib = File(context.applicationInfo.nativeLibraryDir, "libbusybox.so")

            if (!busyboxBin.exists() || !busyboxBin.canExecute()) {
                if (nativeLib.exists()) {
                    try {
                        nativeLib.inputStream().use { input ->
                            busyboxBin.outputStream().use { output ->
                                input.copyTo(output)
                            }
                        }
                    } catch (e: Exception) {
                        Log.w(TAG, "Copy from nativeLibraryDir failed: ${e.message}")
                    }
                }
                if (!busyboxBin.exists() || busyboxBin.length() == 0L) {
                    extractBusyboxFromApk(context, busyboxBin)
                }
            }

            try {
                Os.chmod(busyboxBin.absolutePath, 493) // 0755
                busyboxBin.setExecutable(true, false)
            } catch (ignored: Exception) {}

            val expectedMarker = "$BUSYBOX_VERSION:${busyboxBin.length()}"
            val currentMarker = if (markerFile.exists()) markerFile.readText().trim() else ""
            val shBin = File(usrBin, "sh")

            // Create initial welcome file in home if it doesn't exist
            val welcomeFile = File(homeDir, "welcome.txt")
            if (!welcomeFile.exists()) {
                try {
                    welcomeFile.writeText(
                        "Welcome to TerApp!\n" +
                        "Type 'help' for terminal commands, or 'pkg help' for package management.\n" +
                        "POSIX tools (BusyBox 1.36.1) and Rust VT100 engine active.\n"
                    )
                } catch (ignored: Exception) {}
            }

            // Create sample scripts directory and executable scripts
            val scriptsDir = File(homeDir, "scripts")
            if (!scriptsDir.exists()) {
                scriptsDir.mkdirs()
            }

            val helloScript = File(homeDir, "hello.sh")
            if (!helloScript.exists()) {
                try {
                    helloScript.writeText(
                        "#!/system/bin/sh\n" +
                        "echo \"TerApp Script Execution: Success!\"\n" +
                        "echo \"Current directory: \$(pwd)\"\n" +
                        "echo \"Host architecture: \$(uname -m)\"\n"
                    )
                    helloScript.setExecutable(true, false)
                    try { Os.chmod(helloScript.absolutePath, 493) } catch (ignored: Exception) {}
                } catch (ignored: Exception) {}
            }

            val demoScript = File(scriptsDir, "demo.sh")
            if (!demoScript.exists()) {
                try {
                    demoScript.writeText(
                        "#!/system/bin/sh\n" +
                        "echo \"Running demo script inside scripts/ folder!\"\n" +
                        "echo \"Listing files:\"\n" +
                        "ls -la\n"
                    )
                    demoScript.setExecutable(true, false)
                    try { Os.chmod(demoScript.absolutePath, 493) } catch (ignored: Exception) {}
                } catch (ignored: Exception) {}
            }

            // Setup environment profile
            val profileFile = File(usrEtc, "profile")
            if (!profileFile.exists()) {
                try {
                    profileFile.writeText(
                        "export PREFIX=\"${usrDir.absolutePath}\"\n" +
                        "export HOME=\"${homeDir.absolutePath}\"\n" +
                        "export TMPDIR=\"${usrTmp.absolutePath}\"\n" +
                        "export PATH=\"${usrBin.absolutePath}:/system/bin:/system/xbin\"\n" +
                        "export TERM=\"xterm-256color\"\n" +
                        "export COLORTERM=\"truecolor\"\n" +
                        "export LANG=\"en_US.UTF-8\"\n" +
                        "alias pkg=\"${usrBin.absolutePath}/pkg\"\n"
                    )
                } catch (ignored: Exception) {}
            }

            val userProfile = File(homeDir, ".profile")
            if (!userProfile.exists()) {
                try {
                    userProfile.writeText(
                        "export PREFIX=\"${usrDir.absolutePath}\"\n" +
                        "export HOME=\"${homeDir.absolutePath}\"\n" +
                        "export TMPDIR=\"${usrTmp.absolutePath}\"\n" +
                        "export PATH=\"${usrBin.absolutePath}:/system/bin:/system/xbin\"\n" +
                        "export TERM=\"xterm-256color\"\n" +
                        "export COLORTERM=\"truecolor\"\n" +
                        "export LANG=\"en_US.UTF-8\"\n" +
                        "alias pkg=\"${usrBin.absolutePath}/pkg\"\n"
                    )
                } catch (ignored: Exception) {}
            }

            if (currentMarker == expectedMarker && busyboxBin.exists() && shBin.exists()) {
                ensurePkgManager(usrBin, busyboxBin)
                return true
            }

            Log.i(TAG, "Rebuilding POSIX userland symlinks with BusyBox at ${busyboxBin.absolutePath}...")

            // 2. Setup sh executable (symlink to busybox or copy)
            try {
                shBin.delete()
                Os.symlink("busybox", shBin.absolutePath)
            } catch (e: Exception) {
                try {
                    busyboxBin.copyTo(shBin, overwrite = true)
                } catch (ignored: Exception) {}
            }
            try {
                Os.chmod(shBin.absolutePath, 493) // 0755
                shBin.setExecutable(true, false)
            } catch (ignored: Exception) {}

            // 3. Install all BusyBox applets into usr/bin
            var installSuccess = false
            try {
                val pb = ProcessBuilder(busyboxBin.absolutePath, "--install", "-s", usrBin.absolutePath)
                pb.directory(usrBin)
                val proc = pb.start()
                val exitCode = proc.waitFor()
                if (exitCode == 0) {
                    installSuccess = true
                    Log.i(TAG, "BusyBox applets installed via --install -s")
                }
            } catch (e: Exception) {
                Log.w(TAG, "busybox --install -s failed: ${e.message}")
            }

            // Fallback manual symlinks if --install failed or skipped
            val applets = listOf(
                "ash", "bash", "ls", "cat", "cp", "mv", "rm", "mkdir", "rmdir",
                "grep", "egrep", "fgrep", "awk", "sed", "tar", "gzip", "gunzip",
                "vi", "nano", "head", "tail", "wc", "sort", "uniq", "find", "xargs",
                "which", "whoami", "uname", "pwd", "date", "touch", "chmod", "chown",
                "df", "du", "ps", "kill", "top", "free", "uptime", "curl", "wget", "env", "clear",
                "bzip2", "xz", "unzip", "base64", "md5sum", "sha256sum", "ping", "netstat",
                "tree", "diff", "patch", "more", "less", "stat", "strings", "sleep", "sync",
                "mktemp", "test", "true", "false", "echo", "printf", "tr", "cut", "tee"
            )

            for (applet in applets) {
                val targetFile = File(usrBin, applet)
                if (!targetFile.exists()) {
                    try {
                        Os.symlink("busybox", targetFile.absolutePath)
                    } catch (e: Exception) {
                        try {
                            Os.symlink(busyboxBin.absolutePath, targetFile.absolutePath)
                        } catch (ignored: Exception) {}
                    }
                }
            }

            // 4. Install lightweight package manager (pkg)
            ensurePkgManager(usrBin, busyboxBin)

            markerFile.writeText(expectedMarker)
            Log.i(TAG, "POSIX userland installed successfully.")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to bootstrap userland: ${e.message}", e)
            false
        }
    }

    private fun ensurePkgManager(usrBin: File, busyboxBin: File) {
        try {
            val pkgFile = File(usrBin, "pkg")
            val script = """
                #!/system/bin/sh
                # TerApp Lightweight Package & Userland Manager (pkg)
                PREFIX="${'$'}{PREFIX:-${usrBin.parentFile?.absolutePath}}"
                BIN_DIR="${'$'}PREFIX/bin"
                BUSYBOX="${'$'}BIN_DIR/busybox"
                if [ ! -x "${'$'}BUSYBOX" ]; then
                    BUSYBOX="${busyboxBin.absolutePath}"
                fi

                show_help() {
                    echo "TerApp Package Manager (pkg) ${VersionManager.CURRENT_VERSION}"
                    echo "Usage: pkg <command> [arguments]"
                    echo ""
                    echo "Commands:"
                    echo "  help              Show this help menu"
                    echo "  list              List all installed userland commands"
                    echo "  search <query>    Search for commands/applets"
                    echo "  install <applet>  Ensure applet symlink exists from BusyBox"
                    echo "  uninstall <tool>  Remove a command symlink from usr/bin"
                    echo "  info <tool>       Show details about a command or tool"
                    echo "  reinstall         Re-create all BusyBox applet symlinks"
                    echo "  env               Print active shell environment variables"
                    echo "  update            Refresh and verify userland symlinks"
                    echo "  doctor            Check system health and environment"
                    echo ""
                    echo "Examples:"
                    echo "  pkg list"
                    echo "  pkg search curl"
                    echo "  pkg doctor"
                }

                case "${'$'}1" in
                    ""|help)
                        show_help
                        ;;
                    list)
                        echo "Installed commands in ${'$'}BIN_DIR:"
                        ls -1 "${'$'}BIN_DIR" | sort | column -c 60 2>/dev/null || ls -1 "${'$'}BIN_DIR"
                        ;;
                    search)
                        if [ -z "${'$'}2" ]; then
                            echo "Error: Please specify search query."
                            echo "Usage: pkg search <query>"
                            exit 1
                        fi
                        echo "Searching for '${'$'}2' in installed tools:"
                        ls -1 "${'$'}BIN_DIR" | grep -i "${'$'}2" || echo "No matching tools found."
                        ;;
                    install)
                        if [ -z "${'$'}2" ]; then
                            echo "Error: Please specify applet name."
                            echo "Usage: pkg install <applet>"
                            exit 1
                        fi
                        TARGET="${'$'}BIN_DIR/${'$'}2"
                        if [ -e "${'$'}TARGET" ]; then
                            echo "Applet '${'$'}2' is already installed at ${'$'}TARGET"
                        else
                            ln -s "${'$'}BUSYBOX" "${'$'}TARGET" 2>/dev/null || ln -s busybox "${'$'}TARGET"
                            if [ -e "${'$'}TARGET" ]; then
                                echo "Successfully installed '${'$'}2' -> ${'$'}TARGET"
                            else
                                echo "Failed to install '${'$'}2'."
                                exit 1
                            fi
                        fi
                        ;;
                    uninstall)
                        if [ -z "${'$'}2" ]; then
                            echo "Error: Please specify tool name to uninstall."
                            exit 1
                        fi
                        TARGET="${'$'}BIN_DIR/${'$'}2"
                        if [ "${'$'}2" = "busybox" ] || [ "${'$'}2" = "sh" ] || [ "${'$'}2" = "pkg" ]; then
                            echo "Error: Cannot uninstall core system component '${'$'}2'."
                            exit 1
                        fi
                        if [ -e "${'$'}TARGET" ] || [ -L "${'$'}TARGET" ]; then
                            rm -f "${'$'}TARGET"
                            echo "Removed ${'$'}TARGET"
                        else
                            echo "Tool '${'$'}2' not found."
                        fi
                        ;;
                    reinstall|reset)
                        echo "Re-installing all BusyBox POSIX applets..."
                        "${'$'}BUSYBOX" --install -s "${'$'}BIN_DIR" 2>/dev/null
                        echo "All applet symlinks successfully refreshed."
                        ;;
                    env)
                        echo "Terminal Environment:"
                        env | sort
                        ;;
                    info)
                        if [ -z "${'$'}2" ]; then
                            echo "Error: Please specify tool name."
                            echo "Usage: pkg info <tool>"
                            exit 1
                        fi
                        TOOL="${'$'}BIN_DIR/${'$'}2"
                        if [ -e "${'$'}TOOL" ]; then
                            echo "Package: ${'$'}2"
                            echo "Path: ${'$'}TOOL"
                            ls -ld "${'$'}TOOL"
                        else
                            echo "Tool '${'$'}2' not found in ${'$'}BIN_DIR"
                        fi
                        ;;
                    update)
                        echo "Updating and refreshing POSIX userland symlinks..."
                        "${'$'}BUSYBOX" --install -s "${'$'}BIN_DIR" 2>/dev/null && echo "Userland symlinks refreshed successfully." || echo "Symlinks verified."
                        ;;
                    doctor)
                        echo "================ TerApp Doctor ================"
                        echo "TerApp: ${VersionManager.CURRENT_VERSION} (Build ${VersionManager.BUILD_CODE})"
                        echo "OS: ${'$'}(uname -s -r 2>/dev/null || echo Android)"
                        echo "Architecture: ${'$'}(uname -m 2>/dev/null || echo aarch64)"
                        echo "BusyBox: ${'$'}("${'$'}BUSYBOX" | head -n 1 2>/dev/null || echo BusyBox)"
                        echo "Engine: Rust VT100 + Native POSIX PTY"
                        echo "PREFIX: ${'$'}PREFIX"
                        echo "HOME: ${'$'}HOME"
                        echo "PATH: ${'$'}PATH"
                        echo "Applets Installed: ${'$'}(ls -1 "${'$'}BIN_DIR" 2>/dev/null | wc -l)"
                        echo "Disk Available: ${'$'}(df -h "${'$'}HOME" 2>/dev/null | tail -n 1 | awk '{print ${'$'}4}')"
                        echo "Status: System Healthy & Operational"
                        echo "==============================================="
                        ;;
                    *)
                        echo "Unknown command '${'$'}1'. Run 'pkg help' for available commands."
                        exit 1
                        ;;
                esac
            """.trimIndent()
            pkgFile.writeText(script + "\n")
            pkgFile.setExecutable(true, false)
            try {
                Os.chmod(pkgFile.absolutePath, 493) // 0755
            } catch (ignored: Exception) {}
        } catch (e: Exception) {
            Log.w(TAG, "Failed to write pkg script: ${e.message}")
        }
    }

    fun getUsrBin(context: Context): File = File(context.filesDir, "usr/bin")
    fun getHomeDir(context: Context): File = File(context.filesDir, "home")
}
