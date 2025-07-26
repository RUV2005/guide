package com.danmo.guide.feature.detection

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import com.danmo.guide.feature.powermode.PowerMode
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.withContext
import org.tensorflow.lite.support.image.ImageProcessor
import org.tensorflow.lite.support.image.TensorImage
import org.tensorflow.lite.support.image.ops.Rot90Op
import org.tensorflow.lite.task.vision.detector.Detection
import org.tensorflow.lite.task.vision.detector.ObjectDetector
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.util.concurrent.Executors

class ObjectDetectorHelper(
    private val context: Context,
    private val listener: DetectionListener? = null
) {
    companion object {
        const val MODEL_FILE = "efficientdet_lite0_int8.tflite"
        var confidenceThreshold: Float = 0.4f

        /**
         * 静态方法，用于预加载模型
         */
        fun preload(context: Context) {
            try {
                // 将模型文件从 assets 复制到文件系统
                val modelFile = File(context.filesDir, MODEL_FILE)
                if (!modelFile.exists()) {
                    val inputStream: InputStream = context.assets.open(MODEL_FILE)
                    val outputStream = FileOutputStream(modelFile)
                    inputStream.copyTo(outputStream)
                    inputStream.close()
                    outputStream.close()
                }

                // 加载模型
                val detector = ObjectDetector.createFromFileAndOptions(
                    modelFile, // 直接传递 File 对象
                    ObjectDetector.ObjectDetectorOptions.builder()
                        .setScoreThreshold(confidenceThreshold)
                        .build()
                )
                Log.d("ObjectDetectorHelper", "模型预加载完成")
                detector.close() // 关闭模型，避免内存泄漏
            } catch (e: Exception) {
                Log.e("ObjectDetectorHelper", "模型预加载失败", e)
            }
        }
    }

    val gpuThread = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
    private var adaptiveSkip = 6
    private var frameCounter = 0

    private val detector get() = ObjectDetectorCache.get(context, powerMode)

    private var powerMode: PowerMode = PowerMode.BALANCED

    fun setPowerMode(mode: PowerMode) {
        powerMode = mode
        listener?.onInitialized()
    }

    suspend fun detect(bitmap: Bitmap, rotationDegrees: Int = 0): List<Detection> =
        withContext(gpuThread) {
            synchronized(this) {
                if ((++frameCounter % adaptiveSkip) != 0) return@withContext emptyList()
                val tensorImage = TensorImage.fromBitmap(bitmap)
                val processed = ImageProcessor.Builder()
                    .add(Rot90Op(-(rotationDegrees / 90).coerceIn(0..3)))
                    .build()
                    .process(tensorImage)
                detector.detect(processed)
            }
        }

    fun getGpuThread(): CoroutineDispatcher {
        return gpuThread
    }

    interface DetectionListener {
        fun onInitialized()
    }
}