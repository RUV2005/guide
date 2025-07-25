package com.danmo.guide.feature.vosk

import android.content.Context
import android.util.Log
import org.json.JSONObject
import org.vosk.Model
import org.vosk.Recognizer
import org.vosk.android.RecognitionListener
import java.io.File
import java.util.Locale

object VoskRecognizerManager {

    private var recognizer: Recognizer? = null
    private var model: Model? = null
    private var speechService: org.vosk.android.SpeechService? = null
    var isInitialized = false

    fun init(context: Context, lang: String = Locale.getDefault().language): Boolean {
        if (isInitialized) return true

        val modelDir = "vosk-model-small-${if (lang == "zh") "cn-0.22" else "en-0.15"}"
        val modelPath = File(context.filesDir, modelDir)

        Log.d("VOSK", "模型路径: ${modelPath.absolutePath}")
        Log.d("VOSK", "模型存在? ${modelPath.exists()}  子文件: ${modelPath.list()?.joinToString()}")

        if (!modelPath.exists()) {
            copyAssetsFolder(context, modelDir, modelPath.absolutePath)
        }
        model = Model(modelPath.absolutePath)
        recognizer = Recognizer(model, 16000.0f)
        isInitialized = true
        return true
    }

    fun startListening(onResult: (String) -> Unit) {
        recognizer?.let { rec ->
            speechService = org.vosk.android.SpeechService(rec, 16000.0f)
            speechService?.startListening(object : RecognitionListener {
                override fun onPartialResult(result: String) {
                    // 处理部分识别结果
                }

                override fun onResult(result: String) {
                    Log.d("VOSK_RAW", "原始 JSON: $result")
                    try {
                        val text = JSONObject(result).optString("text", "").trim()
                        Log.d("VOSK_TEXT", "提取文本: '$text'")
                        onResult(text)
                    } catch (e: Exception) {
                        Log.e("VOSK_JSON", "解析出错", e)
                    }
                }

                override fun onFinalResult(result: String) {
                    // 最终结果处理
                }

                override fun onError(exception: Exception) {
                    // 错误处理
                }

                override fun onTimeout() {
                    // 超时处理
                }
            })
        }
    }

    fun stopListening() {
        speechService?.stop()
    }

    fun destroy() {
        speechService?.shutdown()
        recognizer?.close()
        model?.close()
        recognizer = null
        model = null
        speechService = null
        isInitialized = false
    }

    private fun copyAssetsFolder(context: Context, assetPath: String, targetPath: String) {
        val assetManager = context.assets
        val files = assetManager.list(assetPath) ?: return
        val targetDir = File(targetPath)
        if (!targetDir.exists()) targetDir.mkdirs()
        for (file in files) {
            val assetFile = "$assetPath/$file"
            val targetFile = File(targetDir, file)
            if (assetManager.list(assetFile)?.isNotEmpty() == true) {
                copyAssetsFolder(context, assetFile, targetFile.absolutePath)
            } else {
                assetManager.open(assetFile).use { input ->
                    targetFile.outputStream().use { output -> input.copyTo(output) }
                }
            }
        }
    }
}