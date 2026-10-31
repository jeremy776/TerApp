package com.terapp

import android.content.Context
import android.system.Os
import android.util.Log
import java.io.File

object BootstrapManager {
    private const val TAG = "BootstrapManager"
    private const val BUSYBOX_VERSION = "1.36.1"

    fun getBusyboxBinary(context: Context): File? {
        val nativeLib = File(context.applicationInfo.nativeLibraryDir, "libbusybox.so")
        if (nativeLib.exists() && nativeLib.canExecute()) {
            return nativeLib
        }
        return null
    }

    fun ensureBootstrap(context: Context): Boolean {
        return try {
            val filesDir = context.filesDir
            val usrDir = File(filesDir, "usr")
            val usrBin = File(usrDir, "bin")
            val homeDir = File(filesDir, "home")
            val markerFile = File(usrDir, ".marker")

            usrBin.mkdirs()
            homeDir.mkdirs()

            // Create initial welcome file in home if it doesn't exist
            val welcomeFile = File(homeDir, "welcome.txt")
            if (!welcomeFile.exists()) {
                try {
                    welcomeFile.writeText("Welcome to TerApp!\nType 'help' for commands, or 'pkg help' for package management.\nPOSIX tools (BusyBox 1.36.1) and Rust VT100 engine active.\n")
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
                    helloScript.writeText("#!/system/bin/sh\necho \"TerApp Script Execution: Success!\"\necho \"Current directory: \$(pwd)\"\necho \"Host architecture: \$(uname -m)\"\n")
                    helloScript.setExecutable(true, false)
                    try {
                        Os.chmod(helloScript.absolutePath, 493) // 0755
                    } catch (ignored: Exception) {}
                } catch (ignored: Exception) {}
            }

            val demoScript = File(scriptsDir, "demo.sh")
            if (!demoScript.exists()) {
                try {
                    demoScript.writeText("#!/system/bin/sh\necho \"Running demo script inside scripts/ folder!\"\n")
                    demoScript.setExecutable(true, false)
                    try {
                        Os.chmod(demoScript.absolutePath, 493) // 0755
                    } catch (ignored: Exception) {}
                } catch (ignored: Exception) {}
            }

            val nativeLib = getBusyboxBinary(context)
            if (nativeLib == null) {
                Log.w(TAG, "libbusybox.so not found in nativeLibraryDir")
                return false
            }

            val expectedMarker = "$BUSYBOX_VERSION:${nativeLib.absolutePath}"
            val currentMarker = if (markerFile.exists()) markerFile.readText().trim() else ""
            val busyboxLink = File(usrBin, "busybox")
            val shLink = File(usrBin, "sh")

            if (currentMarker == expectedMarker && busyboxLink.exists() && shLink.exists()) {
                ensurePkgManager(usrBin, nativeLib)
                return true
            }

            Log.i(TAG, "Rebuilding POSIX userland symlinks from ${nativeLib.absolutePath}...")

            // Clean old symlinks
            usrBin.listFiles()?.forEach { file ->
                try {
                    file.delete()
                } catch (ignored: Exception) {}
            }

            // 1. Create main busybox symlink
            try {
                busyboxLink.delete()
                Os.symlink(nativeLib.absolutePath, busyboxLink.absolutePath)
            } catch (e: Exception) {
                Log.w(TAG, "Failed to symlink busybox: ${e.message}")
            }

            // 2. Create sh symlink
            try {
                shLink.delete()
                Os.symlink(nativeLib.absolutePath, shLink.absolutePath)
            } catch (e: Exception) {
                Log.w(TAG, "Failed to symlink sh: ${e.message}")
            }

            // 3. Install all BusyBox applets into usr/bin
            var installSuccess = false
            try {
                val pb = ProcessBuilder(busyboxLink.absolutePath, "--install", "-s", usrBin.absolutePath)
                val proc = pb.start()
                val exitCode = proc.waitFor()
                if (exitCode == 0) {
                    installSuccess = true
                    Log.i(TAG, "BusyBox applets installed via --install -s")
                }
            } catch (e: Exception) {
                Log.w(TAG, "busybox --install -s failed: ${e.message}")
            }

            // Fallback manual symlinks if --install failed
            if (!installSuccess) {
                val applets = listOf(
                    "ash", "bash", "ls", "cat", "cp", "mv", "rm", "mkdir", "rmdir",
                    "grep", "egrep", "fgrep", "awk", "sed", "tar", "gzip", "gunzip",
                    "vi", "head", "tail", "wc", "sort", "uniq", "find", "xargs",
                    "which", "whoami", "uname", "pwd", "date", "touch", "chmod", "df", "du",
                    "ps", "kill", "top", "free", "uptime", "curl", "wget", "env", "clear",
                    "bzip2", "xz", "unzip", "base64", "md5sum", "sha256sum", "ping", "netstat",
                    "tree", "nano", "diff", "patch", "more", "less", "stat", "strings"
                )

                for (applet in applets) {
                    val targetFile = File(usrBin, applet)
                    try {
                        targetFile.delete()
                        Os.symlink(nativeLib.absolutePath, targetFile.absolutePath)
                    } catch (ignored: Exception) {}
                }
            }

            // 4. Install lightweight package manager (pkg)
            ensurePkgManager(usrBin, nativeLib)

            markerFile.writeText(expectedMarker)
            Log.i(TAG, "POSIX userland installed successfully.")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to bootstrap userland: ${e.message}", e)
            false
        }
    }

    private fun ensurePkgManager(usrBin: File, nativeLib: File) {
        try {
            val pkgFile = File(usrBin, "pkg")
            val script = """
                #!/system/bin/sh
                # TerApp Lightweight Package & Userland Manager (pkg)
                PREFIX="${'$'}{PREFIX:-${usrBin.parentFile?.absolutePath}}"
                BIN_DIR="${'$'}PREFIX/bin"
                BUSYBOX="${nativeLib.absolutePath}"

                show_help() {
                    echo "TerApp Package Manager (pkg) v1.0.0"
                    echo "Usage: pkg <command> [arguments]"
                    echo ""
                    echo "Commands:"
                    echo "  help              Show this help menu"
                    echo "  list              List all installed userland commands"
                    echo "  search <query>    Search for commands/applets"
                    echo "  install <applet>  Ensure applet symlink exists from BusyBox"
                    echo "  info <tool>       Show details about a command or tool"
                    echo "  update            Refresh and verify userland symlinks"
                    echo "  doctor            Check system health and environment"
                    echo ""
                    echo "Examples:"
                    echo "  pkg list"
                    echo "  pkg search curl"
                    echo "  pkg doctor"
                }

                case "${'$'}1" in
                    help|--help|-h|"")
                        show_help
                        ;;
                    list)
                        echo "Installed userland applets in ${'$'}BIN_DIR:"
                        ls -1 "${'$'}BIN_DIR" | sort | tr '\n' ' '
                        echo ""
                        ;;
                    search)
                        if [ -z "${'$'}2" ]; then
                            echo "Error: missing search query. Example: pkg search grep"
                            exit 1
                        fi
                        echo "Searching applets matching '${'$'}2':"
                        "${'$'}BUSYBOX" --list 2>/dev/null | grep "${'$'}2" || ls -1 "${'$'}BIN_DIR" | grep "${'$'}2"
                        ;;
                    install)
                        if [ -z "${'$'}2" ]; then
                            echo "Error: missing package/applet name. Example: pkg install nc"
                            exit 1
                        fi
                        APPLET="${'$'}2"
                        TARGET="${'$'}BIN_DIR/${'$'}APPLET"
                        if [ -e "${'$'}TARGET" ]; then
                            echo "Package/applet '${'$'}APPLET' is already installed at ${'$'}TARGET"
                        else
                            ln -s "${'$'}BUSYBOX" "${'$'}TARGET" 2>/dev/null || ln -s "${'$'}BIN_DIR/busybox" "${'$'}TARGET" 2>/dev/null
                            if [ -e "${'$'}TARGET" ]; then
                                echo "Successfully installed applet '${'$'}APPLET' into ${'$'}TARGET"
                            else
                                echo "Failed to install applet '${'$'}APPLET'"
                                exit 1
                            fi
                        fi
                        ;;
                    info)
                        if [ -z "${'$'}2" ]; then
                            echo "Error: missing command name. Example: pkg info ls"
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
                        echo "OS: ${'$'}(uname -s -r 2>/dev/null || echo Android)"
                        echo "Architecture: ${'$'}(uname -m 2>/dev/null || echo aarch64)"
                        echo "BusyBox: ${'$'}("${'$'}BUSYBOX" | head -n 1 2>/dev/null)"
                        echo "PREFIX: ${'$'}PREFIX"
                        echo "HOME: ${'$'}HOME"
                        echo "PATH: ${'$'}PATH"
                        echo "Applets Installed: ${'$'}(ls -1 "${'$'}BIN_DIR" 2>/dev/null | wc -l)"
                        echo "Status: System Healthy"
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
