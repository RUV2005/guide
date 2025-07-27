package com.danmo.guide.feature.detection

import android.content.Context
import com.danmo.guide.feature.powermode.PowerMode
import org.tensorflow.lite.task.core.BaseOptions
import org.tensorflow.lite.task.vision.detector.ObjectDetector

object ObjectDetectorCache {
    private val cache = mutableMapOf<Int, ObjectDetector>()

    fun get(context: Context, mode: PowerMode = PowerMode.BALANCED): ObjectDetector {
        val key = mode.ordinal
        return cache[key] ?: synchronized(this) {
            cache[key] ?: create(context, mode).also { cache[key] = it }
        }
    }

    private fun create(context: Context, mode: PowerMode): ObjectDetector {
        val base = BaseOptions.builder().apply {
            setNumThreads(
                when (mode) {
                    PowerMode.LOW_POWER -> 1
                    PowerMode.BALANCED -> 2
                    PowerMode.HIGH_ACCURACY -> 4
                }
            )
            // 可选：动态选择 GPU / NNAPI
//            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
//                when (mode) {
//                    PowerMode.LOW_POWER -> useNnapi()
//                    PowerMode.HIGH_ACCURACY -> useGpu()
//                    else -> {}
//                }
//            }
        }.build()

        return ObjectDetector.createFromFileAndOptions(
            context,
            ObjectDetectorHelper.MODEL_FILE,
            ObjectDetector.ObjectDetectorOptions.builder()
                .setBaseOptions(base)
                .setMaxResults(5)
                .setScoreThreshold(ObjectDetectorHelper.confidenceThreshold)
                .build()
        )
    }
}