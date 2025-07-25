// TtsService.kt
package com.danmo.guide.feature.fall

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
    private val speechQueue = LinkedBlockingQueue<Pair<String, Boolean>>()
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
            if (result != TextToSpeech.LANG_COUNTRY_AVAILABLE && result != TextToSpeech.LANG_AVAILABLE) {
                Log.e("TtsService", "Language not supported")
            }
            isTtsReady = true
            processNextInQueue()
            Log.d("TtsService", "TTS initialized successfully")
        } else {
            Log.e("TtsService", "TTS initialization failed")
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

    fun speak(text: String, immediate: Boolean = false) {
        if (!isTtsReady) return

        if (immediate) {
            speechQueue.clear()
            tts.stop()
            speechQueue.add(Pair(text, true))
            processNextInQueue()
        } else {
            speechQueue.add(Pair(text, false))
            if (currentUtteranceId == null) {
                processNextInQueue()
            }
        }
    }

    fun processNextInQueue() {
        if (speechQueue.isEmpty() || !isTtsReady) return
        val (nextText, _) = speechQueue.poll()!!
        val utteranceId = UUID.randomUUID().toString()
        tts.speak(nextText, TextToSpeech.QUEUE_ADD, null, utteranceId)
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