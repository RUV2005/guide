package com.danmo.guide.feature.fall

import android.app.Service
import android.content.Intent
import android.os.Binder
import android.os.IBinder
import android.speech.tts.TextToSpeech
import android.util.Log
import java.util.Locale

class TtsService : Service() {

    private lateinit var tts: TextToSpeech
    private var isTtsReady = false

    private val binder = TtsBinder()

    inner class TtsBinder : Binder() {
        fun getService(): TtsService = this@TtsService
    }

    override fun onBind(intent: Intent): IBinder {
        return binder
    }

    override fun onCreate() {
        super.onCreate()
        Log.d("TtsService", "Service created")
        tts = TextToSpeech(this) { status ->
            if (status == TextToSpeech.SUCCESS) {
                tts.language = Locale.CHINA
                isTtsReady = true
                Log.d("TtsService", "TTS initialized successfully")
            } else {
                Log.e("TtsService", "TTS initialization failed")
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.d("TtsService", "Service destroyed")
        if (::tts.isInitialized) {
            tts.stop()
            tts.shutdown()
        }
    }

    fun speak(text: String) {
        if (isTtsReady) {
            // 清除所有正在播报的内容
            tts.stop()
            // 播报新的内容
            tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, null)
        }
    }
}