package com.danmo.guide.feature.update

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

class UpdateChecker {
    companion object {
        private const val VERSION_URL = "https://guangji.online/api/version"
        private const val CHANGELOG_URL = "https://guangji.online/api/changelog"
    }

    suspend fun getServerVersion(): String? {
        return withContext(Dispatchers.IO) {
            try {
                val connection = URL(VERSION_URL).openConnection() as HttpURLConnection
                connection.setRequestProperty("User-Agent", "Mozilla/5.0")
                connection.connectTimeout = 5000
                connection.readTimeout = 5000
                val response = connection.inputStream.bufferedReader().use { it.readText().trim() }
                // 解析 JSON
                val jsonObject = JSONObject(response)
                jsonObject.getString("content")
            } catch (e: Exception) {
                e.printStackTrace()
                null
            }
        }
    }

    suspend fun getChangelog(): String? {
        return withContext(Dispatchers.IO) {
            try {
                val connection = URL(CHANGELOG_URL).openConnection() as HttpURLConnection
                connection.setRequestProperty("User-Agent", "Mozilla/5.0")
                connection.connectTimeout = 5000
                connection.readTimeout = 5000
                val response = connection.inputStream.bufferedReader().use { it.readText().trim() }
                // 解析 JSON
                val jsonObject = JSONObject(response)
                jsonObject.getString("content")
            } catch (e: Exception) {
                e.printStackTrace()
                null
            }
        }
    }
}

object VersionComparator {
    fun compareVersions(versionA: String?, versionB: String?): Int {
        if (versionA == null || versionB == null) return -2
        val regex = """^(\d+)(\.\d+){0,2}$""".toRegex()
        if (!versionA.matches(regex)) return -2
        if (!versionB.matches(regex)) return -2

        fun normalize(ver: String): List<Int> {
            return ver.split(".").map { it.toInt() } + listOf(0, 0, 0)
        }

        val aParts = normalize(versionA).take(3)
        val bParts = normalize(versionB).take(3)

        return aParts.zip(bParts).fold(0) { acc, (a, b) ->
            if (acc != 0) acc else a.compareTo(b)
        }
    }
}