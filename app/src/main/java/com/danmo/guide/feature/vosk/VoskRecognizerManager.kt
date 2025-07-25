package com.danmo.guide.feature.vosk

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import org.vosk.Model
import org.vosk.Recognizer
import org.vosk.android.RecognitionListener
import org.vosk.android.SpeechService
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.zip.ZipFile

object VoskRecognizerManager {

    private const val SIGN_URL = "https://dl.guangji.online/"
    private const val MODEL_DIR_NAME = "vosk-model-small-cn-0.22"

    private var model: Model? = null
    private var recognizer: Recognizer? = null
    private var speechService: SpeechService? = null

    var isInitialized: Boolean = false
        private set

    /* 主入口：保证模型下载、校验、初始化 */
    suspend fun initWithDownload(context: Context): Boolean = withContext(Dispatchers.IO) {
        if (isInitialized) return@withContext true

        val modelDir = File(context.filesDir, MODEL_DIR_NAME)
        val readyFile = File(modelDir, ".ready")

        if (readyFile.exists()) return@withContext loadModel(modelDir.absolutePath)

        val (url, etag) = fetchSignedUrl() ?: run {
            Log.e("VOSK", "无法获取签名 URL 或 ETag")
            return@withContext false
        }

        val tmpZip = File(context.filesDir, "model.zip")
        try {
            downloadFile(url, tmpZip)
            if (!verifyETag(tmpZip, etag)) {
                Log.e("VOSK", "ETag 校验失败")
                return@withContext false
            }
            unzip(tmpZip, context.filesDir)
            readyFile.createNewFile()
            tmpZip.delete()
        } catch (e: Exception) {
            Log.e("VOSK", "模型下载或解压异常", e)
            return@withContext false
        }
        return@withContext loadModel(modelDir.absolutePath)
    }

    /* ------------------ 网络 ------------------ */
    private suspend fun fetchSignedUrl(): Pair<String, String>? = withContext(Dispatchers.IO) {
        val client = OkHttpClient()
        val request = Request.Builder().url(SIGN_URL).build()
        client.newCall(request).execute().use { resp ->
            if (!resp.isSuccessful) return@withContext null
            val json = JSONObject(resp.body?.string() ?: return@withContext null)
            val url = json.optString("url")
            val etag = json.optString("etag")
            if (url.isNotBlank() && etag.isNotBlank()) url to etag else null
        }
    }

    private suspend fun downloadFile(url: String, dest: File) = withContext(Dispatchers.IO) {
        val client = OkHttpClient()
        val request = Request.Builder().url(url).build()
        client.newCall(request).execute().use { resp ->
            if (!resp.isSuccessful) throw IOException("下载失败 ${resp.code}")
            resp.body?.byteStream()?.use { input ->
                dest.outputStream().use { output -> input.copyTo(output) }
            }
        }
    }

    private fun verifyETag(file: File, expectedETag: String): Boolean {
        val md5 = MessageDigest.getInstance("MD5")
            .digest(file.readBytes())
            .joinToString("") { "%02x".format(it) }
        return md5.equals(expectedETag, ignoreCase = true)
    }

    private fun unzip(zipFile: File, targetDir: File) {
        ZipFile(zipFile).use { zip ->
            zip.entries().asSequence()
                .filter { !it.isDirectory }
                .forEach { entry ->
                    val outFile = File(targetDir, entry.name)
                    outFile.parentFile?.mkdirs()
                    zip.getInputStream(entry).use { input ->
                        outFile.outputStream().use { output -> input.copyTo(output) }
                    }
                }
        }
    }

    private fun loadModel(modelPath: String): Boolean {
        if (isInitialized) return true
        model = Model(modelPath)
        recognizer = Recognizer(model, 16000.0f)
        isInitialized = true
        return true
    }

    /* ------------------ 识别控制 ------------------ */
    fun startListening(onResult: (String) -> Unit) {
        recognizer?.let { rec ->
            speechService = SpeechService(rec, 16000.0f)
            speechService?.startListening(object : RecognitionListener {
                override fun onPartialResult(hypothesis: String?) {}
                override fun onResult(hypothesis: String?) {
                    hypothesis?.let {
                        val text = JSONObject(it).optString("text", "").trim()
                        if (text.isNotEmpty()) onResult(text)
                    }
                }
                override fun onFinalResult(hypothesis: String?) {}
                override fun onError(exception: Exception?) {}
                override fun onTimeout() {}
            })
        }
    }

    fun stopListening() = speechService?.stop()
    fun destroy() = speechService?.shutdown().also {
        recognizer?.close()
        model?.close()
        recognizer = null
        model = null
        speechService = null
        isInitialized = false
    }
}