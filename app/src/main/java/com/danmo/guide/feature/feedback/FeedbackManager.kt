package com.danmo.guide.feature.feedback
import android.annotation.SuppressLint
import android.content.Context
import com.danmo.guide.core.service.TtsService
import org.tensorflow.lite.task.vision.detector.Detection
class FeedbackManager(context: Context) {
    private var ttsService: TtsService? = null
    private val messageQueueManager = MessageQueueManager.getInstance(context.applicationContext)
    private val detectionProcessor = DetectionProcessor.getInstance(context.applicationContext)
    
    companion object {
        @SuppressLint("StaticFieldLeak")
        @Volatile
        private var instance: FeedbackManager? = null
        private var _speechEnabled: Boolean = true
        private var _speechRate: Float = 1.2f
        private var _confidenceThreshold: Float = 0.4f
        @SuppressLint("StaticFieldLeak")
        private lateinit var context: Context
        var speechEnabled: Boolean
            get() = _speechEnabled
            set(value) {
                _speechEnabled = value
                instance?.ttsService?.setSpeechEnabled(value)
            }
        var speechRate: Float
            get() = _speechRate
            set(value) {
                _speechRate = value
                instance?.ttsService?.setSpeechRate(value)
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
    
    /**
     * 设置 TTS 服务（由 MainActivity 在服务连接后调用）
     */
    fun setTtsService(ttsService: TtsService?) {
        this.ttsService = ttsService
        messageQueueManager.setTtsService(ttsService)
        // 应用已保存的设置
        ttsService?.setSpeechEnabled(_speechEnabled)
        ttsService?.setSpeechRate(_speechRate)
    }
    
    fun handleDetectionResult(result: Detection) {
        detectionProcessor.handleDetectionResult(result)
    }
    
    fun updateLanguage(languageCode: String) {
        ttsService?.updateLanguage(languageCode)
        messageQueueManager.clearQueue()
    }

    fun clearQueue() {
        messageQueueManager.clearQueue()
        ttsService?.setSpeechEnabled(false) // 停止播报
    }
}