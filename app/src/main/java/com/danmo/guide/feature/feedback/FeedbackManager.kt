package com.danmo.guide.feature.feedback
import android.annotation.SuppressLint
import android.content.Context
import org.tensorflow.lite.task.vision.detector.Detection
class FeedbackManager(context: Context) {
    private val ttsManager = TTSManager.getInstance(context)
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
}