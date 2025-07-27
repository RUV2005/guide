package com.danmo.guide.feature.detection

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import com.danmo.guide.feature.powermode.PowerMode
import com.google.firebase.Firebase
import com.google.firebase.perf.performance
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.withContext
import org.tensorflow.lite.support.image.ImageProcessor
import org.tensorflow.lite.support.image.TensorImage
import org.tensorflow.lite.support.image.ops.Rot90Op
import org.tensorflow.lite.task.core.BaseOptions
import org.tensorflow.lite.task.vision.detector.Detection
import org.tensorflow.lite.task.vision.detector.ObjectDetector
import java.io.File
import java.io.FileOutputStream
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
            val trace = Firebase.performance.newTrace("tflite_model_loading")
            trace.start()
            try {
                // 1. 把模型拷到 filesDir
                val modelFile = File(context.filesDir, MODEL_FILE)
                if (!modelFile.exists()) {
                    context.assets.open(MODEL_FILE).use { ins ->
                        FileOutputStream(modelFile).use { outs ->
                            ins.copyTo(outs)
                        }
                    }
                }

                // 2. 最保守的 CPU-only 选项
                val options = ObjectDetector.ObjectDetectorOptions.builder()
                    .setScoreThreshold(confidenceThreshold)
                    .setBaseOptions(
                        BaseOptions.builder()
                            .setNumThreads(1)          // 单线程，省内存
                            // .useGpu()               // 显式关闭 GPU
                            // .useNnapi()             // 显式关闭 NNAPI
                            .build()
                    )
                    .build()

                // 3. 打开 TFLite 调试日志
                System.setProperty("tflite.native.level", "DEBUG")

                val detector = ObjectDetector.createFromFileAndOptions(modelFile, options)
                Log.d("ObjectDetectorHelper", "模型预加载完成")
                detector.close()
            } catch (e: Exception) {
                Log.e("ObjectDetectorHelper", "模型预加载失败", e)
            } finally {
                trace.stop()
            }
        }
    }

    // 单线程 + 单任务队列，防止 FTL 内存溢出
    private val cpuThread = Executors.newSingleThreadExecutor().asCoroutineDispatcher()

    private var adaptiveSkip = 6
    private var frameCounter = 0

    private val detector get() = ObjectDetectorCache.get(context, powerMode).also {
        val trace = Firebase.performance.newTrace("detector_initialization")
        trace.start()
        // 初始化逻辑
        trace.stop()
    }

    private var powerMode: PowerMode = PowerMode.BALANCED

    fun setPowerMode(mode: PowerMode) {
        val trace = Firebase.performance.newTrace("power_mode_switch")
        trace.start()
        powerMode = mode
        listener?.onInitialized()
        trace.stop()
    }

    suspend fun detect(bitmap: Bitmap, rotationDegrees: Int = 0): List<Detection> =
        withContext(cpuThread) {
            synchronized(this) {
                if ((++frameCounter % adaptiveSkip) != 0) return@withContext emptyList()

                val trace = Firebase.performance.newTrace("tflite_inference")
                trace.start()

                val tensorImage = TensorImage.fromBitmap(bitmap)
                val processed = ImageProcessor.Builder()
                    .add(Rot90Op(-(rotationDegrees / 90).coerceIn(0..3)))
                    .build()
                    .process(tensorImage)

                // 再次打开调试开关，保证这次 detect 也能打印
                System.setProperty("tflite.native.level", "DEBUG")

                val results = detector.detect(processed)
                trace.stop()
                results
            }
        }

    fun getGpuThread(): CoroutineDispatcher = cpuThread   // 名字保持兼容

    interface DetectionListener {
        fun onInitialized()
    }
}