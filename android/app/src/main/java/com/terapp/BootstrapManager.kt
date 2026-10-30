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
                    welcomeFile.writeText("Welcome to TerApp!\nType 'help' for commands.\nPOSIX tools (BusyBox 1.36.1) and Rust VT100 engine active.\n")
                } catch (ignored: Exception) {}
            }

            val nativeLib = getBusyboxBinary(context)
            if (nativeLib == null) {
                Log.w(TAG, "libbusybox.so not found in nativeLibraryDir")
                return false
            }

            // Path marker includes both the BusyBox version and the physical library path
            // to automatically detect and repair broken symlinks whenever the APK is updated
            val expectedMarker = "$BUSYBOX_VERSION:${nativeLib.absolutePath}"
            val currentMarker = if (markerFile.exists()) markerFile.readText().trim() else ""
            val busyboxLink = File(usrBin, "busybox")
            val shLink = File(usrBin, "sh")

            if (currentMarker == expectedMarker && busyboxLink.exists() && shLink.exists()) {
                return true
            }

            Log.i(TAG, "Rebuilding POSIX userland symlinks from ${nativeLib.absolutePath}...")

            // Clean all old/broken symlinks. Calling delete() directly unlinks the file/symlink
            // even if its target is missing (dangling symlink).
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

            markerFile.writeText(expectedMarker)
            Log.i(TAG, "POSIX userland installed successfully.")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to bootstrap userland: ${e.message}", e)
            false
        }
    }

    fun getUsrBin(context: Context): File = File(context.filesDir, "usr/bin")
    fun getHomeDir(context: Context): File = File(context.filesDir, "home")
}
