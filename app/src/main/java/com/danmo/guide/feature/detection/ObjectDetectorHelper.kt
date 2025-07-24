package com.danmo.guide.feature.detection

import android.content.Context
import android.graphics.Bitmap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.tensorflow.lite.support.image.ImageProcessor
import org.tensorflow.lite.support.image.TensorImage
import org.tensorflow.lite.support.image.ops.Rot90Op
import org.tensorflow.lite.task.core.BaseOptions
import org.tensorflow.lite.task.vision.detector.Detection
import org.tensorflow.lite.task.vision.detector.ObjectDetector
import java.util.concurrent.Executors

class ObjectDetectorHelper(
    private val context: Context,
    private val listener: DetectionListener? = null
) {
    companion object {
        const val MODEL_FILE = "efficientdet_lite0_int8.tflite"
        var confidenceThreshold: Float = 0.4f
    }

    enum class PowerMode { LOW_POWER, BALANCED, HIGH_ACCURACY }

    val gpuThread = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
    private val scope = CoroutineScope(gpuThread + SupervisorJob())

    private var detector: ObjectDetector? = null
    private var powerMode: PowerMode = PowerMode.BALANCED

    private var adaptiveSkip = 6

    fun setPowerMode(mode: PowerMode) {
        if (powerMode != mode) {
            powerMode = mode
            scope.launch { initDetector() }
        }
    }

    init { scope.launch { initDetector() } }

    private suspend fun initDetector() {
        try {
            detector?.close()
            val base = BaseOptions.builder().setNumThreads(1)
            if (isHighEndSoC()) base.useGpu() else base.useNnapi()
            val options = ObjectDetector.ObjectDetectorOptions.builder()
                .setBaseOptions(base.build())
                .setMaxResults(5)
                .setScoreThreshold(confidenceThreshold)
                .build()
            detector = ObjectDetector.createFromFileAndOptions(context, MODEL_FILE, options)
            withContext(Dispatchers.Main) { listener?.onInitialized() }
        } catch (e: Exception) { listener?.onError("初始化失败: ${e.message}") }
    }

    private fun isHighEndSoC(): Boolean {
        val board = android.os.Build.BOARD.lowercase()
        return board.contains("sdm8") || board.contains("kirin9") || board.contains("exynos")
    }

    private var frameCounter = 0
    suspend fun detect(bitmap: Bitmap, rotationDegrees: Int = 0): List<Detection> =
        withContext(gpuThread) {
            synchronized(this@ObjectDetectorHelper) {
                if ((++frameCounter % adaptiveSkip) != 0) return@withContext emptyList()

                // 官方 320×320 TensorImage
                val tensorImage = TensorImage.fromBitmap(bitmap)
                val processed = ImageProcessor.Builder()
                    .add(Rot90Op(-(rotationDegrees / 90).coerceIn(0..3)))
                    .build()
                    .process(tensorImage)

                detector?.detect(processed) ?: emptyList()
            }
        }

    interface DetectionListener {
        fun onError(error: String)
        fun onInitialized()
    }
}