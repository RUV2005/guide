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

class ObjectDetectorHelper(
    private val context: Context,
    private val listener: DetectionListener? = null
) {
    companion object {
        @Volatile var confidenceThreshold: Float = 0.4f
        private const val MODEL_FILE = "efficientdet_lite0.tflite"
    }

    private var objectDetector: ObjectDetector? = null

    init {
        initializeDetector()
    }

    private fun initializeDetector() {
        try {
            val byteBuffer = loadModelFile()
            val options = buildDetectorOptions()

            objectDetector = ObjectDetector.createFromBufferAndOptions(byteBuffer, options)
            listener?.onInitialized()
        } catch (e: Exception) {
            val errorMsg = "检测器初始化失败: ${e.localizedMessage}"
            Log.e("ObjectDetector", errorMsg)
            listener?.onError(errorMsg)
        }
    }

    private fun loadModelFile(): ByteBuffer {
        val fileDescriptor = context.assets.openFd(MODEL_FILE)
        return FileInputStream(fileDescriptor.fileDescriptor).use { inputStream ->
            inputStream.channel.run {
                map(
                    FileChannel.MapMode.READ_ONLY,
                    fileDescriptor.startOffset,
                    fileDescriptor.declaredLength
                ).apply {
                    fileDescriptor.close()
                }
            }
        }
    }

    private fun buildDetectorOptions(): ObjectDetector.ObjectDetectorOptions {
        val baseOptions = BaseOptions.builder()
            .setNumThreads(Runtime.getRuntime().availableProcessors().coerceAtLeast(1))
            .build()

        return ObjectDetector.ObjectDetectorOptions.builder()
            .setBaseOptions(baseOptions)
            .setMaxResults(5)
            .setScoreThreshold(confidenceThreshold)
            .build()
    }

    fun detect(image: TensorImage, rotationDegrees: Int): List<Detection> {
        return try {
            val validRotation = (rotationDegrees / 90).coerceIn(0..3)
            val imageProcessor = ImageProcessor.Builder()
                .add(Rot90Op(-validRotation))
                .build()

            objectDetector?.detect(imageProcessor.process(image)) ?: emptyList()
        } catch (e: Exception) {
            Log.e("Detection", "检测失败: ${e.stackTraceToString()}")
            emptyList()
        }
    }

    fun close() {
        objectDetector?.close()
        objectDetector = null
    }

    interface DetectionListener {
        fun onError(error: String)
        fun onInitialized()
    }
}