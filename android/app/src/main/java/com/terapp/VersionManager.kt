package com.terapp

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import kotlin.concurrent.thread

object VersionManager {
    const val CURRENT_VERSION = "v1.1.1"
    const val BUILD_CODE = 3
    const val REPO_URL = "https://github.com/jeremy776/TerApp"
    const val RELEASES_API = "https://api.github.com/repos/jeremy776/TerApp/releases/latest"

    var latestVersion: String? = null
        private set

    fun checkForUpdates(onResult: (isLatest: Boolean, latestVer: String, releaseUrl: String?) -> Unit) {
        thread(isDaemon = true) {
            try {
                val url = URL(RELEASES_API)
                val conn = url.openConnection() as HttpURLConnection
                conn.connectTimeout = 3500
                conn.readTimeout = 3500
                conn.setRequestProperty("User-Agent", "TerApp-Android/$CURRENT_VERSION")
                conn.setRequestProperty("Accept", "application/vnd.github.v3+json")

                if (conn.responseCode == 200) {
                    val body = conn.inputStream.bufferedReader().use { it.readText() }
                    val json = JSONObject(body)
                    val tagName = json.optString("tag_name", "").trim()
                    val htmlUrl = json.optString("html_url", "$REPO_URL/releases")
                    if (tagName.isNotEmpty()) {
                        latestVersion = tagName
                        val isLatest = tagName.equals(CURRENT_VERSION, ignoreCase = true)
                        onResult(isLatest, tagName, htmlUrl)
                        return@thread
                    }
                }
            } catch (ignored: Exception) {}
            onResult(true, CURRENT_VERSION, null)
        }
    }
}
