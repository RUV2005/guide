package com.danmo.guide.feature.detection

import android.content.Context
import android.graphics.Bitmap
import android.os.Process
import android.os.SystemClock
import android.util.Log
import org.tensorflow.lite.support.image.ImageProcessor
import org.tensorflow.lite.support.image.TensorImage
import org.tensorflow.lite.support.image.ops.Rot90Op
import org.tensorflow.lite.task.core.BaseOptions
import org.tensorflow.lite.task.vision.detector.Detection
import org.tensorflow.lite.task.vision.detector.ObjectDetector
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 优化版物体检测帮助类 - 支持CPU占用控制
 * Optimi
 * zed object detector helper with CPU usage control
 */
class ObjectDetectorHelper(
    private val context: Context,
    private val listener: DetectionListener? = null
) {
    companion object {
        @Volatile var confidenceThreshold: Float = 0.4f
        private const val MODEL_FILE = "efficientdet_lite0.tflite"
        private const val TARGET_INPUT_SIZE = 320  // 优化输入尺寸
    }

    // 优化配置参数
    enum class PowerMode {
        LOW_POWER, BALANCED, HIGH_ACCURACY
    }

    private var objectDetector: ObjectDetector? = null
    private var powerMode: PowerMode = PowerMode.BALANCED
    private var lastProcessTime = 0L
    private var isProcessing = false
    private val detectionScope = CoroutineScope(Dispatchers.Default + Job())

    // 可复用对象池
    private val tensorImagePool = mutableListOf<TensorImage>()
    private val bitmapPool = mutableListOf<Bitmap>()

    init {
        initializeDetector()
    }

    private fun initializeDetector() {
        try {
            val options = when (powerMode) {
                PowerMode.LOW_POWER -> buildLowPowerOptions()
                PowerMode.BALANCED -> buildBalancedOptions()
                PowerMode.HIGH_ACCURACY -> buildHighAccuracyOptions()
            }

            objectDetector?.close()
            objectDetector = ObjectDetector.createFromBufferAndOptions(
                loadModelFile(), options
            )
            listener?.onInitialized()
        } catch (e: Exception) {
            handleError("Detector init failed", e)
        }
    }

    /**
     * 同步检测接口
     * Synchronous detection interface
     */
    fun detect(tensorImage: TensorImage, rotationDegrees: Int): List<Detection> {
        synchronized(this) {
            if (isProcessing) return emptyList()
            isProcessing = true

            try {
                val processedImage = processImageRotation(tensorImage, rotationDegrees)
                return objectDetector?.detect(processedImage) ?: emptyList()
            } finally {
                isProcessing = false
            }
        }
    }

    private fun buildLowPowerOptions(): ObjectDetector.ObjectDetectorOptions {
        return ObjectDetector.ObjectDetectorOptions.builder()
            .setBaseOptions(
                BaseOptions.builder()
                    .setNumThreads(1)
                    .useNnapi()  // 优先使用硬件加速
                    .build()
            )
            .setMaxResults(3)
            .setScoreThreshold(confidenceThreshold + 0.1f)
            .build()
    }

    private fun buildBalancedOptions(): ObjectDetector.ObjectDetectorOptions {
        return ObjectDetector.ObjectDetectorOptions.builder()
            .setBaseOptions(
                BaseOptions.builder()
                    .setNumThreads(2)
                    .useNnapi()
                    .build()
            )
            .setMaxResults(5)
            .setScoreThreshold(confidenceThreshold)
            .build()
    }

    private fun buildHighAccuracyOptions(): ObjectDetector.ObjectDetectorOptions {
        return ObjectDetector.ObjectDetectorOptions.builder()
            .setBaseOptions(
                BaseOptions.builder()
                    .setNumThreads(4)
                    .build()
            )
            .setMaxResults(10)
            .setScoreThreshold(confidenceThreshold - 0.05f)
            .build()
    }

    private fun loadModelFile(): ByteBuffer {
        return context.assets.openFd(MODEL_FILE).use { fd ->
            FileInputStream(fd.fileDescriptor).use { fis ->
                fis.channel.map(
                    FileChannel.MapMode.READ_ONLY,
                    fd.startOffset,
                    fd.declaredLength
                ).apply {
                    fd.close()
                }
            }
        }
    }

    /**
     * 异步检测接口（推荐使用）
     * Async detection interface (recommended)
     */
    fun detectAsync(
        bitmap: Bitmap,
        rotationDegrees: Int,
        callback: (List<Detection>) -> Unit
    ) {
        detectionScope.launch {
            val startTime = SystemClock.elapsedRealtime()

            // 帧率控制 (10 FPS)
            if (startTime - lastProcessTime < 100) {
                return@launch
            }
            lastProcessTime = startTime

            // 线程优先级控制
            Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND)

            try {
                val processedBitmap = preprocessImage(bitmap)
                val tensorImage = getReusableTensorImage().apply {
                    load(processedBitmap)
                }

                val result = objectDetector?.detect(
                    processImageRotation(tensorImage, rotationDegrees)
                ) ?: emptyList()

                withContext(Dispatchers.Main) {
                    callback(result)
                }
            } catch (e: Exception) {
                handleError("Detection failed", e)
            } finally {
                isProcessing = false
                Log.d("Perf", "Detection cost: ${SystemClock.elapsedRealtime() - startTime}ms")
            }
        }
    }

    /**
     * 图像预处理（尺寸优化）
     * Image preprocessing (size optimization)
     */
    private fun preprocessImage(bitmap: Bitmap): Bitmap {
        // 从对象池获取或创建Bitmap
        val recycled = bitmapPool.find {
            it.width == TARGET_INPUT_SIZE && it.height == TARGET_INPUT_SIZE
        }
        recycled?.eraseColor(0)

        return recycled ?: run {
            val scale = TARGET_INPUT_SIZE.toFloat() / bitmap.width.coerceAtLeast(1)
            Bitmap.createScaledBitmap(
                bitmap,
                TARGET_INPUT_SIZE,
                (bitmap.height * scale).toInt(),
                true
            )
        }.also {
            bitmapPool.add(it)
        }
    }

    private fun processImageRotation(image: TensorImage, degrees: Int): TensorImage {
        val validRotation = (degrees / 90).coerceIn(0..3)
        return ImageProcessor.Builder()
            .add(Rot90Op(-validRotation))
            .build()
            .process(image)
    }

    private fun getReusableTensorImage(): TensorImage {
        return tensorImagePool.find { true }?.also {
            tensorImagePool.remove(it)
        } ?: TensorImage()
    }

    private fun recycleTensorImage(image: TensorImage) {
        if (tensorImagePool.size < 3) {
            tensorImagePool.add(image)
        }
    }

    fun setPowerMode(mode: PowerMode) {
        if (this.powerMode != mode) {
            this.powerMode = mode
            initializeDetector()
        }
    }

    fun close() {
        objectDetector?.close()
        tensorImagePool.clear()
        bitmapPool.forEach { it.recycle() }
        bitmapPool.clear()
    }

    private fun handleError(msg: String, e: Exception) {
        val errorMsg = "$msg: ${e.localizedMessage}"
        Log.e("ObjectDetector", errorMsg)
        listener?.onError(errorMsg)
    }

    interface DetectionListener {
        fun onError(error: String)
        fun onInitialized()
    }
}