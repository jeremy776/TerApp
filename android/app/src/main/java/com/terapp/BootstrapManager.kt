package com.terapp

import android.content.Context
import android.util.Log
import java.io.File
import java.io.FileOutputStream

object BootstrapManager {
    private const val TAG = "BootstrapManager"
    private const val BUSYBOX_VERSION = "1.36.1"

    fun ensureBootstrap(context: Context): Boolean {
        return try {
            val filesDir = context.filesDir
            val usrDir = File(filesDir, "usr")
            val usrBin = File(usrDir, "bin")
            val homeDir = File(filesDir, "home")
            val versionFile = File(usrDir, ".version")

            usrBin.mkdirs()
            homeDir.mkdirs()

            val currentVersion = if (versionFile.exists()) versionFile.readText().trim() else ""
            val busyboxBin = File(usrBin, "busybox")

            if (busyboxBin.exists() && currentVersion == BUSYBOX_VERSION) {
                return true
            }

            Log.i(TAG, "Installing POSIX userland tools (BusyBox $BUSYBOX_VERSION)...")

            // 1. Copy busybox-arm64 from assets
            context.assets.open("busybox-arm64").use { input ->
                FileOutputStream(busyboxBin).use { output ->
                    input.copyTo(output)
                }
            }

            // 2. Set executable
            busyboxBin.setExecutable(true, false)
            busyboxBin.setReadable(true, false)

            // 3. Install all symlinks (ls, grep, awk, tar, vi, etc.)
            try {
                val pb = ProcessBuilder(busyboxBin.absolutePath, "--install", "-s", usrBin.absolutePath)
                pb.directory(usrBin)
                val p = pb.start()
                p.waitFor()
            } catch (e: Exception) {
                Log.w(TAG, "busybox --install failed, creating core symlinks manually: ${e.message}")
                createCoreSymlinks(busyboxBin, usrBin)
            }

            // 4. Mark version
            versionFile.writeText(BUSYBOX_VERSION)
            Log.i(TAG, "POSIX userland installed successfully.")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to bootstrap userland: ${e.message}", e)
            false
        }
    }

    private fun createCoreSymlinks(busybox: File, binDir: File) {
        val applets = listOf(
            "sh", "ash", "bash", "ls", "cat", "cp", "mv", "rm", "mkdir", "rmdir",
            "grep", "egrep", "fgrep", "awk", "sed", "tar", "gzip", "gunzip",
            "vi", "head", "tail", "wc", "sort", "uniq", "find", "xargs",
            "which", "whoami", "uname", "pwd", "date", "touch", "chmod", "df", "du",
            "ps", "kill", "top", "free", "uptime", "curl", "wget", "env"
        )
        for (applet in applets) {
            try {
                val link = File(binDir, applet)
                if (!link.exists()) {
                    val pb = ProcessBuilder(busybox.absolutePath, "ln", "-s", "busybox", applet)
                    pb.directory(binDir)
                    pb.start().waitFor()
                }
            } catch (ignored: Exception) {}
        }
    }

    fun getUsrBin(context: Context): File = File(context.filesDir, "usr/bin")
    fun getHomeDir(context: Context): File = File(context.filesDir, "home")
}
