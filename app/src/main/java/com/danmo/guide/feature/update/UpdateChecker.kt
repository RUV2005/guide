package com.danmo.guide.feature.update

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL

class UpdateChecker {

    companion object {
        private const val VERSION_URL = "http://47.120.4.209/update/metadata/version.txt"
        private const val CHANGELOG_URL = "http://47.120.4.209/update/metadata/changelog.txt"
    }

    suspend fun getServerVersion(): String? {
        return withContext(Dispatchers.IO) {
            try {
                val connection = URL(VERSION_URL).openConnection() as HttpURLConnection
                connection.setRequestProperty("User-Agent", "Mozilla/5.0") // 添加请求头
                connection.connectTimeout = 5000 // 设置连接超时时间
                connection.readTimeout = 5000 // 设置读取超时时间

                connection.inputStream.bufferedReader().use { it.readText().trim() }
            } catch (e: Exception) {
                e.printStackTrace() // 打印异常信息以便调试
                null // 请求失败时返回null
            }
        }
    }

    suspend fun getChangelog(): String? {
        return withContext(Dispatchers.IO) {
            try {
                val connection = URL(CHANGELOG_URL).openConnection() as HttpURLConnection
                connection.setRequestProperty("User-Agent", "Mozilla/5.0") // 添加请求头
                connection.connectTimeout = 5000 // 设置连接超时时间
                connection.readTimeout = 5000 // 设置读取超时时间

                connection.inputStream.bufferedReader().use { it.readText().trim() }
            } catch (e: Exception) {
                e.printStackTrace() // 打印异常信息以便调试
                null // 请求失败时返回null
            }
        }
    }
}
