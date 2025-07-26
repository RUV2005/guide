package com.danmo.guide.feature.camera

import android.content.Context
import android.util.Log
import android.util.Size
import androidx.camera.core.CameraControl
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
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

    /**
     * 初始化 CameraX 相机，使用最新 API
     */
    fun initializeCamera(surfaceProvider: Preview.SurfaceProvider) {
        if (isShutdown) return
        val future = ProcessCameraProvider.getInstance(context)
        future.addListener({
            try {
                cameraProvider = future.get()

                // 使用 ResolutionSelector 替代已废弃的 setTargetResolution
                val resolutionSelector = ResolutionSelector.Builder()
                    .setAspectRatioStrategy(
                        AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY
                    )
                    .setResolutionStrategy(
                        ResolutionStrategy(
                            Size(480, 360),
                            ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER
                        )
                    )
                    .build()

                val preview = Preview.Builder()
                    .setResolutionSelector(resolutionSelector)
                    .build()
                    .apply { setSurfaceProvider(surfaceProvider) }

                val imageAnalysis = ImageAnalysis.Builder()
                    .setResolutionSelector(resolutionSelector)
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .build()
                    .also {
                        it.setAnalyzer(executor, ThrottledAnalyzer(analyzer, 8))
                    }

                val selector = CameraSelector.Builder()
                    .requireLensFacing(CameraSelector.LENS_FACING_BACK)
                    .build()

                cameraProvider?.unbindAll()
                cameraProvider?.bindToLifecycle(
                    context as LifecycleOwner,
                    selector,
                    preview,
                    imageAnalysis
                )?.let { camera ->
                    cameraControl = camera.cameraControl
                    Log.d("CameraManager", "480×360 @ 8 fps 已启用（ResolutionSelector）")
                }
            } catch (e: Exception) {
                Log.e("CameraManager", "初始化失败", e)
            }
        }, ContextCompat.getMainExecutor(context))
    }

    /** 开关闪光灯 */
    fun enableTorchMode(enabled: Boolean) {
        cameraControl?.enableTorch(enabled)
    }

    /** 关闭相机并释放资源 */
    fun shutdown() {
        isShutdown = true
        cameraProvider?.unbindAll()
        cameraProvider = null
    }

    fun isShutdown(): Boolean = isShutdown

    /**
     * 帧率节流器
     */
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