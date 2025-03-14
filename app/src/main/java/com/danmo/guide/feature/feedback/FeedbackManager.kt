package com.danmo.guide.feature.feedback

import android.content.Context
import android.content.Intent
import android.graphics.RectF
import android.media.AudioManager
import android.os.*
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import android.widget.Toast
import org.tensorflow.lite.task.vision.detector.Detection
import java.util.*
import java.util.concurrent.*
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import kotlin.math.max
import kotlin.math.min


private const val FAR_LEFT_BOUNDARY = 0.15f    // 最左侧区域
private const val NEAR_LEFT_BOUNDARY = 0.3f    // 左侧近区域
private const val CENTER_LEFT = 0.4f           // 中心偏左边界
private const val CENTER_RIGHT = 0.6f           // 中心偏右边界
private const val NEAR_RIGHT_BOUNDARY = 0.7f   // 右侧近区域
private const val FAR_RIGHT_BOUNDARY = 0.85f    // 最右侧区域

class FeedbackManager(context: Context) {
    private val ttsManager = TTSManager.getInstance(context)
    private val vibrationManager = VibrationManager.getInstance(context)
    private val messageQueueManager = MessageQueueManager.getInstance(context.applicationContext)
    private val detectionProcessor = DetectionProcessor.getInstance(context.applicationContext)

    companion object {
        @Volatile
        private var instance: FeedbackManager? = null
        private var _speechEnabled: Boolean = true
        private var _speechRate: Float = 1.2f
        private var _confidenceThreshold: Float = 0.4f
        private lateinit var context: Context

        var speechEnabled: Boolean
            get() = _speechEnabled
            set(value) {
                _speechEnabled = value
                TTSManager.getInstance(context).setSpeechEnabled(value)
            }

        var speechRate: Float
            get() = _speechRate
            set(value) {
                _speechRate = value
                TTSManager.getInstance(context).setSpeechRate(value)
            }

        var confidenceThreshold: Float
            get() = _confidenceThreshold
            set(value) {
                _confidenceThreshold = value
                DetectionProcessor.confidenceThreshold = value
            }

        fun getInstance(context: Context): FeedbackManager {
            this.context = context.applicationContext
            return instance ?: synchronized(this) {
                instance ?: FeedbackManager(context.applicationContext).also { instance = it }
            }
        }
    }

    fun handleDetectionResult(result: Detection) {
        detectionProcessor.handleDetectionResult(result)
    }

    fun updateLanguage(languageCode: String) {
        ttsManager.updateLanguage(languageCode)
        messageQueueManager.clearQueue()
    }

    fun clearQueue() {
        messageQueueManager.clearQueue()
        ttsManager.stop()
    }

    fun shutdown() {
        ttsManager.shutdown()
        vibrationManager.cancel()
        messageQueueManager.shutdown()
        detectionProcessor.shutdown()
    }
}