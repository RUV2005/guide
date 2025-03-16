package com.danmo.guide.feature.feedback

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import android.widget.Toast
import java.util.*

/**
 * 文本到语音（TTS）管理器，封装语音合成功能
 * Text-to-Speech manager encapsulating speech synthesis features
 */
class TTSManager(context: Context) : TextToSpeech.OnInitListener {
    private val context: Context = context.applicationContext // 应用上下文 / Application context
    private var tts: TextToSpeech = TextToSpeech(context, this) // TTS引擎实例 / TTS engine instance
    private var isTtsReady = false // TTS初始化状态 / TTS initialization status
    private var speechEnabled: Boolean = true // 语音开关状态 / Speech enabled state
    private var speechRate: Float = 1.2f // 默认语速（0.5-2.0） / Default speech rate (0.5-2.0)

    companion object {
        @SuppressLint("StaticFieldLeak")
        @Volatile
        private var instance: TTSManager? = null // 单例实例 / Singleton instance

        // 获取单例实例 / Get singleton instance
        fun getInstance(context: Context): TTSManager {
            return instance ?: synchronized(this) {
                instance ?: TTSManager(context.applicationContext).also { instance = it }
            }
        }
    }

    init {
        // 华为设备特殊初始化 / Huawei device specific initialization
        if (android.os.Build.MANUFACTURER.equals("HUAWEI", ignoreCase = true)) {
            try {
                // 尝试使用华为专用引擎 / Try Huawei specific engine
                tts = TextToSpeech(context, this, "com.huawei.hiai.engineservice.tts")
            } catch (e: Exception) {
                Log.e("TTS", "华为引擎初始化失败 Huawei engine init failed", e)
            }
        }
    }

    // TTS初始化回调 / TTS initialization callback
    override fun onInit(status: Int) {
        when {
            status == TextToSpeech.SUCCESS -> {
                setupTTS()
            }
            else -> {
                showToast("语音功能初始化失败 TTS init failed", true)
                tts.stop()
                tts.shutdown()
            }
        }
    }

    // 配置TTS参数 / Configure TTS parameters
    private fun setupTTS() {
        when (tts.setLanguage(Locale.CHINESE)) { // 设置中文语音 / Set Chinese language
            TextToSpeech.LANG_MISSING_DATA -> handleMissingLanguageData()
            TextToSpeech.LANG_NOT_SUPPORTED -> showToast("不支持中文语音 Chinese not supported", true)
            else -> {
                tts.setOnUtteranceProgressListener(utteranceListener) // 设置语音进度监听 / Set progress listener
                isTtsReady = true
                Log.d("TTS", "语音引擎初始化成功 TTS initialized")
            }
        }
    }

    // 处理缺失语音数据 / Handle missing language data
    private fun handleMissingLanguageData() {
        showToast("缺少中文语音数据 Missing Chinese data", true)
        try {
            // 引导用户安装语音数据 / Guide user to install TTS data
            val intent = Intent(TextToSpeech.Engine.ACTION_INSTALL_TTS_DATA)
            intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
            context.startActivity(intent)
        } catch (e: Exception) {
            Log.e("TTS", "无法启动语音数据安装 Failed to launch TTS install", e)
        }
    }

    // 语音进度监听器 / Utterance progress listener
    private val utteranceListener = object : UtteranceProgressListener() {
        override fun onStart(utteranceId: String?) {} // 语音开始 / Speech start
        override fun onDone(utteranceId: String?) {}  // 语音完成 / Speech complete

        @Deprecated("Deprecated in Java")
        override fun onError(utteranceId: String?) {} // 错误处理（已弃用） / Error handling (deprecated)
    }

    /**
     * 执行语音播报
     * Execute speech synthesis
     * @param text 要播报的文本 / Text to speak
     * @param utteranceId 唯一标识符（用于回调跟踪） / Unique ID for callback tracking
     */
    fun speak(text: String, utteranceId: String) {
        if (!speechEnabled || !isTtsReady) return

        try {
            val params = Bundle().apply {
                putString(TextToSpeech.Engine.KEY_PARAM_UTTERANCE_ID, utteranceId) // 唯一标识
                putInt(TextToSpeech.Engine.KEY_PARAM_STREAM, AudioManager.STREAM_SYSTEM) // 系统音频通道
                putFloat("rate", speechRate) // 设置语速 / Set speech rate
            }

            // 加入播放队列 / Add to speech queue
            tts.speak(text, TextToSpeech.QUEUE_ADD, params, utteranceId)
        } catch (e: Exception) {
            Log.e("TTS", "播报失败 Speech failed: ${e.message}")
        }
    }

    /**
     * 更新语音语言
     * Update speech language
     * @param languageCode 语言代码（如"zh_CN"） / Language code (e.g. "zh_CN")
     */
    fun updateLanguage(languageCode: String) {
        when (tts.setLanguage(Locale(languageCode))) {
            TextToSpeech.LANG_AVAILABLE -> {
                Log.d("TTS", "语言切换成功 Language changed")
            }
            else -> showToast("语言切换失败 Language change failed", true)
        }
    }

    // 设置语音开关 / Set speech enabled state
    fun setSpeechEnabled(enabled: Boolean) {
        speechEnabled = enabled
        if (!enabled) {
            tts.stop() // 立即停止播报 / Stop immediately
        }
    }

    /**
     * 设置语速
     * Set speech rate
     * @param rate 语速（0.5-2.0） / Speech rate (0.5-2.0)
     */
    fun setSpeechRate(rate: Float) {
        speechRate = rate.coerceIn(0.5f, 2.0f) // 限制有效范围 / Constrain valid range
    }

    // 停止当前播报 / Stop current speech
    fun stop() {
        tts.stop()
    }

    // 关闭TTS引擎 / Shutdown TTS engine
    fun shutdown() {
        tts.stop()
        tts.shutdown()
        instance = null // 清除单例实例 / Clear singleton instance
    }

    // 显示Toast提示 / Show toast message
    private fun showToast(message: String, isLong: Boolean = false) {
        Toast.makeText(context, message, if (isLong) Toast.LENGTH_LONG else Toast.LENGTH_SHORT).show()
    }
}