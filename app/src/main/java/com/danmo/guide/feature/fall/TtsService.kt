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
    private lateinit var tts: TextToSpeech
    private var isTtsReady = false
    private val speechQueue = LinkedBlockingQueue<Pair<String, Boolean>>() // Pair<text, immediate>
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
                currentUtteranceId = utteranceId
            }
            override fun onDone(utteranceId: String?) {
                currentUtteranceId = null
                processNextInQueue()
            }
            override fun onError(utteranceId: String?) {
                currentUtteranceId = null
                processNextInQueue()
            }
        })
    }
    fun speak(text: String, immediate: Boolean = false) {
        if (!isTtsReady) return

        if (immediate) {
            // 立即播报模式：清空队列并立即播放
            speechQueue.clear()
            tts.stop() // 停止当前播报
            speechQueue.add(Pair(text, true))
            processNextInQueue()
        } else {
            // 普通模式：加入队列
            speechQueue.add(Pair(text, false))
            if (currentUtteranceId == null) {
                processNextInQueue()
            }
        }
    }
    fun processNextInQueue() {
        if (speechQueue.isEmpty() || !isTtsReady) return
        val (nextText, isImmediate) = speechQueue.poll()!!
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