package com.danmo.guide.feature.detection

import android.content.Context
import android.graphics.Bitmap
import android.os.Process
import android.os.SystemClock
import android.util.Log
import kotlinx.coroutines.*
import org.tensorflow.lite.support.image.ImageProcessor
import org.tensorflow.lite.support.image.TensorImage
import org.tensorflow.lite.support.image.ops.Rot90Op
import org.tensorflow.lite.task.core.BaseOptions
import org.tensorflow.lite.task.vision.detector.Detection
import org.tensorflow.lite.task.vision.detector.ObjectDetector
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.util.concurrent.Executors

/**
 * 优化版物体检测帮助类 - 支持 CPU 占用控制和多电源模式
 *
 * @param context Android 上下文
 * @param listener 检测事件监听器
 */
class ObjectDetectorHelper(
    private val context: Context,
    private val listener: DetectionListener? = null
) {
    companion object {
        @Volatile
        var confidenceThreshold: Float = 0.4f
        private const val MODEL_FILE = "efficientdet_lite0.tflite"
        private const val TARGET_INPUT_SIZE = 320
    }

    enum class PowerMode { LOW_POWER, BALANCED, HIGH_ACCURACY }

    // 1. 单线程协程调度器：保证 GPU Delegate 线程一致
    val gpuThread = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
    private val detectionScope = CoroutineScope(gpuThread + SupervisorJob())

    // 核心检测器实例
    private var objectDetector: ObjectDetector? = null

    private var powerMode: PowerMode = PowerMode.BALANCED
    private var lastProcessTime = 0L
    private var isProcessing = false

    // 对象池
    private val tensorImagePool = mutableListOf<TensorImage>()
    private val bitmapPool = mutableListOf<Bitmap>()

    init {
        detectionScope.launch { initializeDetector() }
    }

    /** 初始化检测器（已在 gpuThread 中执行） */
    private suspend fun initializeDetector() {
        try {
            objectDetector?.close()
            val options = when (powerMode) {
                PowerMode.LOW_POWER -> buildLowPowerOptions()
                PowerMode.BALANCED -> buildBalancedOptions()
                PowerMode.HIGH_ACCURACY -> buildHighAccuracyOptions()
            }
            objectDetector = ObjectDetector.createFromFileAndOptions(
                context, MODEL_FILE, options
            )
            withContext(Dispatchers.Main) { listener?.onInitialized() }
        } catch (e: Exception) {
            handleError("检测器初始化失败", e)
        }
    }

    /** 同步检测（已在 gpuThread 中调用） */
    suspend fun detect(
        tensorImage: TensorImage,
        rotationDegrees: Int
    ): List<Detection> = withContext(gpuThread) {
        synchronized(this@ObjectDetectorHelper) {
            if (isProcessing) return@withContext emptyList()
            isProcessing = true
            try {
                val processed = processImageRotation(tensorImage, rotationDegrees)

                // 🔍 记录开始时间
                val start = SystemClock.elapsedRealtime()

                val results = objectDetector?.detect(processed) ?: emptyList()

                // 🔍 记录耗时
                Log.d("GPU_CHECK", "推理耗时 = ${SystemClock.elapsedRealtime() - start}ms")

                results
            } finally {
                isProcessing = false
            }
        }
    }

    /** 异步检测（强制在 gpuThread 执行） */
    fun detectAsync(
        bitmap: Bitmap,
        rotationDegrees: Int,
        callback: (List<Detection>) -> Unit
    ) {
        detectionScope.launch {
            val startTime = SystemClock.elapsedRealtime()
            if (startTime - lastProcessTime < 100) return@launch
            lastProcessTime = startTime
            Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND)

            try {
                val processed = preprocessImage(bitmap)
                val tensorImage = getReusableTensorImage().apply { load(processed) }
                val result = objectDetector?.detect(
                    processImageRotation(tensorImage, rotationDegrees)
                ) ?: emptyList()
                withContext(Dispatchers.Main) { callback(result) }
            } catch (e: Exception) {
                handleError("检测失败", e)
            } finally {
                isProcessing = false
                Log.d("Perf", "检测耗时: ${SystemClock.elapsedRealtime() - startTime}ms")
            }
        }
    }

    /* ------------------ 以下与原文件一致 ------------------ */
    private fun buildLowPowerOptions(): ObjectDetector.ObjectDetectorOptions {
        val baseBuilder = BaseOptions.builder().setNumThreads(1)

        // 1) 先尝试 GPU
        try {
            baseBuilder.useGpu()
            Log.d("GPU_CHECK", "✅ GPU 已启用")
        } catch (t: Throwable) {
            Log.w("GPU_CHECK", "❌ GPU 不可用，降级 NNAPI/CPU")
        }

        return ObjectDetector.ObjectDetectorOptions.builder()
            .setBaseOptions(baseBuilder.build())
            .setMaxResults(3)
            .setScoreThreshold(confidenceThreshold + 0.1f)
            .build()
    }
    private fun buildBalancedOptions(): ObjectDetector.ObjectDetectorOptions {
        return ObjectDetector.ObjectDetectorOptions.builder()
            .setBaseOptions(BaseOptions.builder().useNnapi().build())
            .setMaxResults(5)
            .setScoreThreshold(confidenceThreshold)
            .build()
    }

    private fun buildHighAccuracyOptions(): ObjectDetector.ObjectDetectorOptions {
        return ObjectDetector.ObjectDetectorOptions.builder()
            .setBaseOptions(BaseOptions.builder().setNumThreads(4).build())
            .setMaxResults(10)
            .setScoreThreshold(confidenceThreshold - 0.05f)
            .build()
    }

    private fun loadModelFile(): ByteBuffer =
        context.assets.openFd(MODEL_FILE).use { fd ->
            FileInputStream(fd.fileDescriptor).channel.map(
                FileChannel.MapMode.READ_ONLY,
                fd.startOffset,
                fd.declaredLength
            )
        }

    private fun preprocessImage(bitmap: Bitmap): Bitmap {
        val recycled = bitmapPool.find {
            it.width == TARGET_INPUT_SIZE && it.height == TARGET_INPUT_SIZE
        }?.also { it.eraseColor(0) }
        return recycled ?: run {
            val scale = TARGET_INPUT_SIZE.toFloat() / bitmap.width.coerceAtLeast(1)
            Bitmap.createScaledBitmap(
                bitmap,
                TARGET_INPUT_SIZE,
                (bitmap.height * scale).toInt(),
                true
            )
        }.also { bitmapPool.add(it) }
    }

    private fun processImageRotation(image: TensorImage, degrees: Int): TensorImage {
        val validRotation = (degrees / 90).coerceIn(0..3)
        return ImageProcessor.Builder()
            .add(Rot90Op(-validRotation))
            .build()
            .process(image)
    }

    private fun getReusableTensorImage(): TensorImage =
        tensorImagePool.removeFirstOrNull() ?: TensorImage()

    fun setPowerMode(mode: PowerMode) {
        if (powerMode != mode) {
            powerMode = mode
            detectionScope.launch { initializeDetector() }
        }
    }

    fun close() {
        detectionScope.cancel()
        objectDetector?.close()
        tensorImagePool.clear()
        bitmapPool.forEach { it.recycle() }
        bitmapPool.clear()
    }

    private fun handleError(msg: String, e: Exception) {
        val errorMsg = "$msg: ${e.localizedMessage}"
        Log.e("ObjectDetector", errorMsg)
        CoroutineScope(Dispatchers.Main).launch { listener?.onError(errorMsg) }
    }

    interface DetectionListener {
        fun onError(error: String)
        fun onInitialized()
    }
}