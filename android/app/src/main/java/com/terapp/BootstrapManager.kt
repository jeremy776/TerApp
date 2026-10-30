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
            val versionFile = File(usrDir, ".version")

            usrBin.mkdirs()
            homeDir.mkdirs()

            val nativeLib = getBusyboxBinary(context)
            if (nativeLib == null) {
                Log.w(TAG, "libbusybox.so not found in nativeLibraryDir")
                return false
            }

            val currentVersion = if (versionFile.exists()) versionFile.readText().trim() else ""
            val busyboxLink = File(usrBin, "busybox")
            val shLink = File(usrBin, "sh")

            if (currentVersion == BUSYBOX_VERSION && busyboxLink.exists() && shLink.exists()) {
                return true
            }

            Log.i(TAG, "Setting up POSIX userland symlinks from ${nativeLib.absolutePath}...")

            // Always create busybox and sh symlinks first
            try {
                if (busyboxLink.exists()) busyboxLink.delete()
                Os.symlink(nativeLib.absolutePath, busyboxLink.absolutePath)
            } catch (e: Exception) {
                Log.w(TAG, "Failed to symlink busybox: ${e.message}")
            }

            try {
                if (shLink.exists()) shLink.delete()
                Os.symlink(nativeLib.absolutePath, shLink.absolutePath)
            } catch (e: Exception) {
                Log.w(TAG, "Failed to symlink sh: ${e.message}")
            }

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
                    if (targetFile.exists()) targetFile.delete()
                    Os.symlink(nativeLib.absolutePath, targetFile.absolutePath)
                } catch (ignored: Exception) {}
            }

            versionFile.writeText(BUSYBOX_VERSION)
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
