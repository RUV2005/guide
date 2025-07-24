package com.danmo.guide.feature.camera

import android.content.Context
import android.util.Log
import android.util.Size
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit

class CameraManager(
    private val context: Context,
    private val executor: Executor,
    private val analyzer: ImageAnalysis.Analyzer
) {
    private var cameraProvider: ProcessCameraProvider? = null
    private var cameraControl: CameraControl? = null
    private var isShutdown = false

    fun initializeCamera(surfaceProvider: Preview.SurfaceProvider) {
        if (isShutdown) return
        val future = ProcessCameraProvider.getInstance(context)
        future.addListener({
            try {
                cameraProvider = future.get()
                val size = Size(480, 360)          // 清晰度↑
                val preview = Preview.Builder()
                    .setTargetResolution(size)
                    .build()
                    .apply { setSurfaceProvider(surfaceProvider) }

                val imageAnalysis = ImageAnalysis.Builder()
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .setTargetResolution(size)
                    .build()
                    .also { it.setAnalyzer(executor, ThrottledAnalyzer(analyzer, 8)) } // 8 fps

                val selector = CameraSelector.Builder()
                    .requireLensFacing(CameraSelector.LENS_FACING_BACK)
                    .build()

                cameraProvider?.bindToLifecycle(
                    context as LifecycleOwner,
                    selector,
                    preview,
                    imageAnalysis
                )?.let { camera ->
                    cameraControl = camera.cameraControl
                    Log.d("CameraManager", "480×360 @ 8 fps 已启用")
                }
            } catch (e: Exception) {
                Log.e("CameraManager", "初始化失败", e)
            }
        }, ContextCompat.getMainExecutor(context))
    }

    fun enableTorchMode(enabled: Boolean) {
        cameraControl?.enableTorch(enabled)
    }

    fun shutdown() {
        isShutdown = true
        cameraProvider?.unbindAll()
        cameraProvider = null
    }

    fun isShutdown(): Boolean = isShutdown

    /* 节流器支持任意帧率 */
    private class ThrottledAnalyzer(
        private val delegate: ImageAnalysis.Analyzer,
        targetFps: Int
    ) : ImageAnalysis.Analyzer {
        private val intervalNs = TimeUnit.SECONDS.toNanos(1) / targetFps
        private var lastFrameNs = 0L

        override fun analyze(imageProxy: ImageProxy) {
            val now = System.nanoTime()
            if (now - lastFrameNs >= intervalNs) {
                lastFrameNs = now
                delegate.analyze(imageProxy)
            } else {
                imageProxy.close()
            }
        }
    }
}