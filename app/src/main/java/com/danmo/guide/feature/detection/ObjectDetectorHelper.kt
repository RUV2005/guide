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
 * 优化版物体检测帮助类 - 支持CPU占用控制和多电源模式
 *
 * @param context Android上下文
 * @param listener 检测事件监听器
 */
class ObjectDetectorHelper(
    private val context: Context,
    private val listener: DetectionListener? = null
) {
    companion object {
        @Volatile var confidenceThreshold: Float = 0.4f  // 默认置信度阈值
        private const val MODEL_FILE = "efficientdet_lite0.tflite"  // 模型文件路径
        private const val TARGET_INPUT_SIZE = 320  // 优化后的输入尺寸(需匹配模型输入尺寸)
    }

    /** 电源模式枚举 (低功耗/平衡/高性能) */
    enum class PowerMode { LOW_POWER, BALANCED, HIGH_ACCURACY }

    // 核心检测器实例
    private var objectDetector: ObjectDetector? = null

    // 运行时状态管理
    private var powerMode: PowerMode = PowerMode.BALANCED
    private var lastProcessTime = 0L  // 最后一次处理时间(用于帧率控制)
    private var isProcessing = false  // 处理状态锁

    // 协程相关
    private val detectionScope = CoroutineScope(Dispatchers.Default + Job())

    // 对象池 (减少内存分配)
    private val tensorImagePool = mutableListOf<TensorImage>()  // TensorImage复用池
    private val bitmapPool = mutableListOf<Bitmap>()            // Bitmap复用池

    init {
        initializeDetector()
    }

    /** 初始化物体检测器 (根据当前电源模式) */
    private fun initializeDetector() {
        try {
            // 根据电源模式构建不同配置选项
            val options = when (powerMode) {
                PowerMode.LOW_POWER -> buildLowPowerOptions()
                PowerMode.BALANCED -> buildBalancedOptions()
                PowerMode.HIGH_ACCURACY -> buildHighAccuracyOptions()
            }

            // 关闭旧检测器并创建新实例
            objectDetector?.close()
            objectDetector = ObjectDetector.createFromBufferAndOptions(
                loadModelFile(), options
            )
            listener?.onInitialized()
        } catch (e: Exception) {
            handleError("检测器初始化失败", e)
        }
    }

    /**
     * 同步检测接口 (适用于需要即时结果的场景)
     *
     * @param tensorImage 输入图像(Tensor格式)
     * @param rotationDegrees 图像旋转角度
     * @return 检测结果列表
     */
    fun detect(tensorImage: TensorImage, rotationDegrees: Int): List<Detection> {
        synchronized(this) {
            if (isProcessing) return emptyList()  // 防止重复处理
            isProcessing = true
            try {
                // 执行图像旋转预处理
                val processedImage = processImageRotation(tensorImage, rotationDegrees)
                return objectDetector?.detect(processedImage) ?: emptyList()
            } finally {
                isProcessing = false  // 确保状态重置
            }
        }
    }

    /** 构建低功耗模式配置选项 */
    private fun buildLowPowerOptions(): ObjectDetector.ObjectDetectorOptions {
        return ObjectDetector.ObjectDetectorOptions.builder()
            .setBaseOptions(
                BaseOptions.builder()
                    .setNumThreads(1)       // 单线程
                    .useNnapi()              // 启用NNAPI硬件加速
                    .build()
            )
            .setMaxResults(3)                // 限制最大检测结果数
            .setScoreThreshold(confidenceThreshold + 0.1f)  // 提高置信度阈值减少计算
            .build()
    }

    /** 构建平衡模式配置选项 */
    private fun buildBalancedOptions(): ObjectDetector.ObjectDetectorOptions {
        return ObjectDetector.ObjectDetectorOptions.builder()
            .setBaseOptions(
                BaseOptions.builder()
                    .setNumThreads(2)        // 双线程
                    .useNnapi()              // 启用硬件加速
                    .build()
            )
            .setMaxResults(5)                // 适中结果数量
            .setScoreThreshold(confidenceThreshold)
            .build()
    }

    /** 构建高性能模式配置选项 */
    private fun buildHighAccuracyOptions(): ObjectDetector.ObjectDetectorOptions {
        return ObjectDetector.ObjectDetectorOptions.builder()
            .setBaseOptions(
                BaseOptions.builder()
                    .setNumThreads(4)        // 多线程最大化CPU利用率
                    .build()
            )
            .setMaxResults(10)               // 最多检测结果
            .setScoreThreshold(confidenceThreshold - 0.05f)  // 降低阈值提高召回率
            .build()
    }

    /** 从Assets加载模型文件到ByteBuffer */
    private fun loadModelFile(): ByteBuffer {
        return context.assets.openFd(MODEL_FILE).use { fd ->
            FileInputStream(fd.fileDescriptor).use { fis ->
                fis.channel.map(
                    FileChannel.MapMode.READ_ONLY,
                    fd.startOffset,
                    fd.declaredLength
                ).apply {
                    fd.close()  // 确保文件描述符关闭
                }
            }
        }
    }

    /**
     * 异步检测接口 (推荐用于实时处理场景)
     *
     * @param bitmap 输入位图
     * @param rotationDegrees 图像旋转角度
     * @param callback 结果回调函数
     */
    fun detectAsync(
        bitmap: Bitmap,
        rotationDegrees: Int,
        callback: (List<Detection>) -> Unit
    ) {
        detectionScope.launch {
            val startTime = SystemClock.elapsedRealtime()

            // 帧率控制 (约10 FPS)
            if (startTime - lastProcessTime < 100) {
                return@launch  // 跳过过快请求
            }
            lastProcessTime = startTime

            // 设置线程优先级为后台级别
            Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND)

            try {
                // 图像预处理 (尺寸调整)
                val processedBitmap = preprocessImage(bitmap)

                // 从对象池获取可复用的TensorImage
                val tensorImage = getReusableTensorImage().apply {
                    load(processedBitmap)
                }

                // 执行检测并获取结果
                val result = objectDetector?.detect(
                    processImageRotation(tensorImage, rotationDegrees)
                ) ?: emptyList()

                // 主线程回调结果
                withContext(Dispatchers.Main) {
                    callback(result)
                }
            } catch (e: Exception) {
                handleError("检测失败", e)
            } finally {
                isProcessing = false
                // 性能日志 (调试用)
                Log.d("Perf", "检测耗时: ${SystemClock.elapsedRealtime() - startTime}ms")
            }
        }
    }

    /** 图像预处理 (尺寸优化和对象池管理) */
    private fun preprocessImage(bitmap: Bitmap): Bitmap {
        // 从对象池查找可复用Bitmap
        val recycled = bitmapPool.find {
            it.width == TARGET_INPUT_SIZE && it.height == TARGET_INPUT_SIZE
        }
        recycled?.eraseColor(0)  // 清空旧数据

        return recycled ?: run {
            // 计算缩放比例并创建新Bitmap
            val scale = TARGET_INPUT_SIZE.toFloat() / bitmap.width.coerceAtLeast(1)
            Bitmap.createScaledBitmap(
                bitmap,
                TARGET_INPUT_SIZE,
                (bitmap.height * scale).toInt(),
                true
            )
        }.also {
            bitmapPool.add(it)  // 将新Bitmap加入对象池
        }
    }

    /** 处理图像旋转 */
    private fun processImageRotation(image: TensorImage, degrees: Int): TensorImage {
        // 计算有效旋转步长 (每步90度)
        val validRotation = (degrees / 90).coerceIn(0..3)
        return ImageProcessor.Builder()
            .add(Rot90Op(-validRotation))  // 逆向旋转校正方向
            .build()
            .process(image)
    }

    /** 从对象池获取可复用的TensorImage */
    private fun getReusableTensorImage(): TensorImage {
        return tensorImagePool.find { true }?.also {
            tensorImagePool.remove(it)  // 从池中移除已使用的实例
        } ?: TensorImage()  // 池为空时创建新实例
    }

    /** 回收TensorImage到对象池 */
    private fun recycleTensorImage(image: TensorImage) {
        if (tensorImagePool.size < 3) {  // 控制池大小防止内存膨胀
            tensorImagePool.add(image)
        }
    }

    /** 动态切换电源模式 */
    fun setPowerMode(mode: PowerMode) {
        if (this.powerMode != mode) {
            this.powerMode = mode
            initializeDetector()  // 重新初始化检测器
        }
    }

    /** 释放资源 */
    fun close() {
        objectDetector?.close()          // 关闭检测器
        tensorImagePool.clear()          // 清空Tensor池
        bitmapPool.forEach { it.recycle() }  // 回收所有Bitmap
        bitmapPool.clear()
    }

    /** 统一错误处理 */
    private fun handleError(msg: String, e: Exception) {
        val errorMsg = "$msg: ${e.localizedMessage}"
        Log.e("ObjectDetector", errorMsg)
        listener?.onError(errorMsg)  // 通知监听器
    }

    /** 检测事件监听接口 */
    interface DetectionListener {
        fun onError(error: String)      // 错误回调
        fun onInitialized()             // 初始化完成回调
    }
}