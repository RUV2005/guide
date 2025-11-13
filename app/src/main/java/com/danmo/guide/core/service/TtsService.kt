// TtsService.kt
package com.danmo.guide.core.service

import android.app.Service
import android.content.Intent
import android.os.Binder
import android.os.IBinder
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import java.util.*
import java.util.concurrent.LinkedBlockingQueue

class TtsService : Service(), TextToSpeech.OnInitListener {

    // 1. 对外只读属性：TTS 是否正在说话
    var isSpeaking: Boolean = false
        private set

    private lateinit var tts: TextToSpeech
    private var isTtsReady = false
    private val speechQueue = LinkedBlockingQueue<Pair<String, Pair<Boolean, String?>>>()
    private var currentUtteranceId: String? = null

    inner class TtsBinder : Binder() {
        fun getService(): TtsService = this@TtsService
    }

    override fun onBind(intent: Intent): IBinder = TtsBinder()

    override fun onCreate() {
        super.onCreate()
        Log.d("TtsService", "Service created")
        tts = TextToSpeech(this, this)
        setupUtteranceListener()
    }


    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            val result = tts.setLanguage(Locale.CHINA)

            isTtsReady = when (result) {
                TextToSpeech.LANG_AVAILABLE,
                TextToSpeech.LANG_COUNTRY_AVAILABLE,
                TextToSpeech.LANG_COUNTRY_VAR_AVAILABLE -> true
                else -> {
                    Log.e("TtsService", "TTS语言不支持或缺失数据: $result")
                    false
                }
            }

            if (isTtsReady) {
                Log.d("TtsService", "TTS初始化成功，语言已设置")
                processNextInQueue()
            } else {
                Log.e("TtsService", "语言设置失败，TTS不可用")
            }
        } else {
            Log.e("TtsService", "TTS初始化失败")
        }
    }

    private fun setupUtteranceListener() {
        tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {
                // 2. 开始播报
                isSpeaking = true
                currentUtteranceId = utteranceId
                Log.d("TtsService", "TTS start speak: $utteranceId")
            }

            override fun onDone(utteranceId: String?) {
                // 3. 播报结束
                isSpeaking = false
                currentUtteranceId = null
                processNextInQueue()
            }

            override fun onError(utteranceId: String?) {
                isSpeaking = false
                currentUtteranceId = null
                processNextInQueue()
            }
        })
    }

    /**
     * 播报文本（兼容 TTSManager 接口）
     * @param text 要播报的文本
     * @param utteranceId 可选的播报ID，用于回调识别
     */
    fun speak(text: String, utteranceId: String? = null) {
        if (!isTtsReady) {
            Log.w("TtsService", "TTS未就绪，无法播报: $text")
            return
        }
        
        if (text.isBlank()) {
            Log.d("TTS_DEBUG", "TTS 尝试播报空文本")
            return
        }

        val id = utteranceId ?: UUID.randomUUID().toString()
        speechQueue.add(Pair(text, Pair(false, id)))
        if (currentUtteranceId == null) {
            processNextInQueue()
        }
    }
    
    /**
     * 立即播报文本（清除队列）
     * @param text 要播报的文本
     * @param immediate 是否立即播报（清除队列）
     */
    fun speakImmediate(text: String, immediate: Boolean = false) {
        if (!isTtsReady) return

        if (immediate) {
            speechQueue.clear()
            tts.stop()
            val utteranceId = UUID.randomUUID().toString()
            speechQueue.add(Pair(text, Pair(true, utteranceId)))
            processNextInQueue()
        } else {
            speak(text)
        }
    }
    
    /**
     * 设置语速
     */
    fun setSpeechRate(rate: Float) {
        val clampedRate = rate.coerceIn(0.5f, 3.0f)
        if (::tts.isInitialized) {
            tts.setSpeechRate(clampedRate)
        }
    }
    
    /**
     * 设置语音启用状态
     */
    fun setSpeechEnabled(enabled: Boolean) {
        if (!enabled && ::tts.isInitialized) {
            tts.stop()
            speechQueue.clear()
        }
    }
    
    /**
     * 更新语言
     */
    fun updateLanguage(languageCode: String) {
        if (::tts.isInitialized) {
            val result = tts.setLanguage(Locale(languageCode))
            when (result) {
                TextToSpeech.LANG_AVAILABLE,
                TextToSpeech.LANG_COUNTRY_AVAILABLE,
                TextToSpeech.LANG_COUNTRY_VAR_AVAILABLE -> {
                    Log.d("TtsService", "语言切换成功: $languageCode")
                }
                else -> {
                    Log.e("TtsService", "语言切换失败: $languageCode")
                }
            }
        }
    }

    fun processNextInQueue() {
        if (speechQueue.isEmpty() || !isTtsReady) return
        val (nextText, params) = speechQueue.poll()!!
        val (isImmediate, utteranceId) = params
        val id = utteranceId ?: UUID.randomUUID().toString()
        val queueMode = if (isImmediate) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD
        Log.d("TTS_DEBUG", "TTS 尝试播报：$nextText")
        tts.speak(nextText, queueMode, null, id)
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.d("TtsService", "Service destroyed")
        if (::tts.isInitialized) {
            tts.stop()
            tts.shutdown()
        }
    }
}