package com.danmo.guide.feature.detection

import android.annotation.SuppressLint
import android.content.Context
import android.app.ActivityManager
import android.content.pm.PackageManager
import android.os.Build
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

class ObjectDetectorHelper(
    private val context: Context,
    private val listener: DetectionListener? = null
) {
    companion object {
        @Volatile var confidenceThreshold: Float = 0.4f
        private val MODEL_FILES = listOf(
            "efficientdet_lite0.tflite",
            "efficientdet_lite1.tflite",
            "efficientdet_lite2.tflite",
            "efficientdet_lite3.tflite",
            "efficientdet_lite4.tflite"
        )
        private const val TAG = "ObjDetector"
    }

    private var objectDetector: ObjectDetector? = null
    private var currentModelIndex = 0

    init {
        initializeDetector()
    }

    // region 模型初始化逻辑
    private fun initializeDetector() {
        try {
            currentModelIndex = selectOptimalModelIndex()
            Log.i(TAG, "Initializing model: ${MODEL_FILES[currentModelIndex]}")

            val byteBuffer = loadModelFile(MODEL_FILES[currentModelIndex])
            val options = buildDetectorOptions()

            objectDetector = ObjectDetector.createFromBufferAndOptions(byteBuffer, options)
            listener?.onInitialized()
        } catch (e: Exception) {
            handleInitError(e)
        }
    }

    private fun handleInitError(e: Exception) {
        val errorMsg = "初始化失败: ${e.localizedMessage}"
        Log.e(TAG, errorMsg)

        if (currentModelIndex > 0) {
            Log.w(TAG, "尝试回退到更轻量模型...")
            currentModelIndex--
            initializeDetector()
        } else {
            listener?.onError("$errorMsg\n无法加载任何模型")
        }
    }
    // endregion

    // region 设备性能评估系统
    private fun selectOptimalModelIndex(): Int {
        val performanceScore = calculateDevicePerformanceScore()
        return when {
            performanceScore >= 9 -> 4
            performanceScore >= 7 -> 3
            performanceScore >= 5 -> 2
            performanceScore >= 3 -> 1
            else -> 0
        }
    }

    private fun calculateDevicePerformanceScore(): Int {
        var score = 0
        score += calculateCpuScore()
        score += calculateMemoryScore()
        score += calculateSystemFeatureScore()
        return score.coerceIn(0..10)
    }

    private fun calculateCpuScore(): Int {
        val cores = Runtime.getRuntime().availableProcessors()
        return when {
            cores >= 8 -> 4
            cores >= 6 -> 3
            cores >= 4 -> 2
            cores >= 2 -> 1
            else -> 0
        }
    }

    private fun calculateMemoryScore(): Int {
        val totalGB = getTotalMemoryGB()
        return when {
            totalGB >= 8 -> 4
            totalGB >= 6 -> 3
            totalGB >= 4 -> 2
            totalGB >= 2 -> 1
            else -> 0
        }
    }

    private fun calculateSystemFeatureScore(): Int {
        var score = 0
        if (is64BitSupported()) score += 1
        if (isVulkanSupported()) score += 1
        return score
    }

    private fun is64BitSupported(): Boolean {
        return Build.SUPPORTED_64_BIT_ABIS.isNotEmpty()
    }

    @SuppressLint("ObsoleteSdkInt")
    private fun isVulkanSupported(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            context.packageManager.hasSystemFeature(PackageManager.FEATURE_VULKAN_HARDWARE_LEVEL)
        } else {
            false
        }
    }

    private fun getTotalMemoryGB(): Float {
        return try {
            val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            val memInfo = ActivityManager.MemoryInfo()
            activityManager.getMemoryInfo(memInfo)
            memInfo.totalMem / 1024.0f / 1024 / 1024
        } catch (e: Exception) {
            Log.w(TAG, "获取内存信息失败: ${e.message}")
            2.0f
        }
    }
    // endregion

    // region 核心功能实现
    private fun loadModelFile(modelFile: String): ByteBuffer {
        val fd = context.assets.openFd(modelFile)
        return FileInputStream(fd.fileDescriptor).use { stream ->
            stream.channel.map(
                FileChannel.MapMode.READ_ONLY,
                fd.startOffset,
                fd.declaredLength
            ).also { fd.close() }
        }
    }

    private fun buildDetectorOptions(): ObjectDetector.ObjectDetectorOptions {
        val threads = when (currentModelIndex) {
            4 -> 4.coerceAtMost(Runtime.getRuntime().availableProcessors())
            3 -> 3
            2 -> 2
            else -> 1
        }

        return ObjectDetector.ObjectDetectorOptions.builder()
            .setBaseOptions(BaseOptions.builder().setNumThreads(threads).build())
            .setMaxResults(5)
            .setScoreThreshold(confidenceThreshold)
            .build()
    }

    fun detect(image: TensorImage, rotationDegrees: Int): List<Detection> {
        return try {
            val validRotation = (rotationDegrees / 90).coerceIn(0..3)
            val processor = ImageProcessor.Builder()
                .add(Rot90Op(-validRotation))
                .build()

            objectDetector?.detect(processor.process(image)) ?: emptyList()
        } catch (e: Exception) {
            Log.e(TAG, "检测失败: ${e.stackTraceToString()}")
            emptyList()
        }
    }

    fun close() {
        objectDetector?.close()
        objectDetector = null
        Log.i(TAG, "资源已释放")
    }
    // endregion

    interface DetectionListener {
        fun onError(error: String)
        fun onInitialized()
    }
}