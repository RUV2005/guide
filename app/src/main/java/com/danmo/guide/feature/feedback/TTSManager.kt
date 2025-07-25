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

class TTSManager(context: Context) : TextToSpeech.OnInitListener {
    private val context: Context = context.applicationContext
    private var tts: TextToSpeech = TextToSpeech(context, this)
    private var isTtsReady = false
    private var speechEnabled: Boolean = true
    private var speechRate: Float = 4.0f // 默认语速加快 150%

    companion object {
        @SuppressLint("StaticFieldLeak")
        @Volatile
        private var instance: TTSManager? = null

        fun getInstance(context: Context): TTSManager {
            return instance ?: synchronized(this) {
                instance ?: TTSManager(context.applicationContext).also { instance = it }
            }
        }
    }

    override fun onInit(status: Int) {
        when {
            status == TextToSpeech.SUCCESS -> {
                setupTTS()
            }
            else -> {
                showToast("语音功能初始化失败 TTS init failed")
                tts.stop()
                tts.shutdown()
            }
        }
    }

    private fun setupTTS() {
        when (tts.setLanguage(Locale.CHINESE)) {
            TextToSpeech.LANG_MISSING_DATA -> handleMissingLanguageData()
            TextToSpeech.LANG_NOT_SUPPORTED -> showToast("不支持中文语音 Chinese not supported", true)
            else -> {
                tts.setOnUtteranceProgressListener(utteranceListener)
                tts.setSpeechRate(speechRate) // 在初始化成功后设置语速
                isTtsReady = true
                Log.d("TTS", "语音引擎初始化成功 TTS initialized")
            }
        }
    }

    private fun handleMissingLanguageData() {
        showToast("缺少中文语音数据 Missing Chinese data", true)
        try {
            val intent = Intent(TextToSpeech.Engine.ACTION_INSTALL_TTS_DATA)
            intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
            context.startActivity(intent)
        } catch (e: Exception) {
            Log.e("TTS", "无法启动语音数据安装 Failed to launch TTS install", e)
        }
    }

    private val utteranceListener = object : UtteranceProgressListener() {
        override fun onStart(utteranceId: String?) {}
        override fun onDone(utteranceId: String?) {}
        @Deprecated("Deprecated in Java")
        override fun onError(utteranceId: String?) {}
    }

    fun speak(text: String, utteranceId: String) {
        if (!speechEnabled || !isTtsReady) return
        try {
            val params = Bundle().apply {
                putString(TextToSpeech.Engine.KEY_PARAM_UTTERANCE_ID, utteranceId)
                putInt(TextToSpeech.Engine.KEY_PARAM_STREAM, AudioManager.STREAM_SYSTEM)
            }
            tts.speak(text, TextToSpeech.QUEUE_ADD, params, utteranceId)
        } catch (e: Exception) {
            Log.e("TTS", "播报失败 Speech failed: ${e.message}")
        }
    }

    fun updateLanguage(languageCode: String) {
        when (tts.setLanguage(Locale(languageCode))) {
            TextToSpeech.LANG_AVAILABLE -> {
                Log.d("TTS", "语言切换成功 Language changed")
            }
            else -> showToast("语言切换失败 Language change failed", true)
        }
    }

    fun setSpeechEnabled(enabled: Boolean) {
        speechEnabled = enabled
        if (!enabled) {
            tts.stop()
        }
    }

    fun setSpeechRate(rate: Float) {
        speechRate = rate.coerceIn(0.5f, 3.0f) // 限制语速范围，最高 3.0
        if (isTtsReady) {
            tts.setSpeechRate(speechRate) // 动态调整语速
        }
    }

    fun stop() {
        tts.stop()
    }

    private fun showToast(message: String, isLong: Boolean = true) {
        Toast.makeText(context, message, if (isLong) Toast.LENGTH_LONG else Toast.LENGTH_SHORT).show()
    }
}