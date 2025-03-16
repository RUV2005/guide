package com.danmo.guide.feature.detection

import android.content.Context
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

/**
 * 物体检测帮助类，封装TensorFlow Lite物体检测功能
 * Object detection helper class encapsulating TensorFlow Lite detection features
 */
class ObjectDetectorHelper(
    private val context: Context,                   // 上下文用于访问资源 / Context for resource access
    private val listener: DetectionListener? = null // 状态监听器（可选） / State listener (optional)
) {
    companion object {
        @Volatile var confidenceThreshold: Float = 0.4f  // 动态置信度阈值（线程安全） / Dynamic confidence threshold (thread-safe)
        private const val MODEL_FILE = "efficientdet_lite0.tflite"  // 模型文件路径 / Model file path
    }

    private var objectDetector: ObjectDetector? = null  // TFLite检测器实例 / TFLite detector instance

    // 初始化块：创建检测器 / Initialization block: create detector
    init {
        initializeDetector()
    }

    /**
     * 初始化物体检测器实例
     * Initialize object detector instance
     */
    private fun initializeDetector() {
        try {
            val byteBuffer = loadModelFile()        // 加载模型文件 / Load model file
            val options = buildDetectorOptions()    // 构建配置选项 / Build configuration options

            objectDetector = ObjectDetector.createFromBufferAndOptions(byteBuffer, options)
            listener?.onInitialized()  // 通知初始化完成 / Notify initialization completion
        } catch (e: Exception) {
            val errorMsg = "检测器初始化失败: ${e.localizedMessage}"
            Log.e("ObjectDetector", errorMsg)
            listener?.onError(errorMsg) // 错误回调 / Error callback
        }
    }

    /**
     * 从assets加载模型文件到ByteBuffer
     * Load model file from assets to ByteBuffer
     */
    private fun loadModelFile(): ByteBuffer {
        val fileDescriptor = context.assets.openFd(MODEL_FILE)
        return FileInputStream(fileDescriptor.fileDescriptor).use { inputStream ->
            inputStream.channel.run {
                map(  // 内存映射提高读取效率 / Memory mapping for efficient reading
                    FileChannel.MapMode.READ_ONLY,
                    fileDescriptor.startOffset,
                    fileDescriptor.declaredLength
                ).apply {
                    fileDescriptor.close()  // 及时关闭文件描述符 / Close file descriptor promptly
                }
            }
        }
    }

    /**
     * 构建检测器配置选项
     * Build detector configuration options
     */
    private fun buildDetectorOptions(): ObjectDetector.ObjectDetectorOptions {
        // 基础配置：使用所有可用CPU核心 / Base config: use all available CPU cores
        val baseOptions = BaseOptions.builder()
            .setNumThreads(Runtime.getRuntime().availableProcessors().coerceAtLeast(1))
            .build()

        return ObjectDetector.ObjectDetectorOptions.builder()
            .setBaseOptions(baseOptions)
            .setMaxResults(5)                   // 最大检测结果数 / Maximum detection results
            .setScoreThreshold(confidenceThreshold) // 置信度过滤阈值 / Confidence filter threshold
            .build()
    }

    /**
     * 执行物体检测
     * Perform object detection
     * @param image 输入图像张量 / Input image tensor
     * @param rotationDegrees 图像旋转角度（0/90/180/270） / Image rotation degrees (0/90/180/270)
     * @return 检测结果列表 / List of detection results
     */
    fun detect(image: TensorImage, rotationDegrees: Int): List<Detection> {
        return try {
            // 计算有效旋转次数（支持90度倍数） / Calculate valid rotation steps (90-degree multiples)
            val validRotation = (rotationDegrees / 90).coerceIn(0..3)

            // 构建图像处理器（自动旋转校正） / Build image processor (auto-rotation)
            val imageProcessor = ImageProcessor.Builder()
                .add(Rot90Op(-validRotation))  // 逆向旋转补偿设备方向 / Compensate device orientation
                .build()

            objectDetector?.detect(imageProcessor.process(image)) ?: emptyList()
        } catch (e: Exception) {
            Log.e("Detection", "检测失败: ${e.stackTraceToString()}")
            emptyList()  // 返回空列表防止崩溃 / Return empty list to prevent crash
        }
    }

    /**
     * 释放检测器资源
     * Release detector resources
     */
    fun close() {
        objectDetector?.close()  // 显式释放Native资源 / Explicitly release native resources
        objectDetector = null    // 帮助GC回收 / Assist GC collection
    }

    /**
     * 检测状态监听接口
     * Detection status listener interface
     */
    interface DetectionListener {
        fun onError(error: String)       // 错误发生时回调 / Callback on error
        fun onInitialized()              // 初始化完成时回调 / Callback when initialized
    }
}