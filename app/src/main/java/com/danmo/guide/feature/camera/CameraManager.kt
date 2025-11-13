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
import com.danmo.guide.core.device.DeviceCapability
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
    private var deviceInfo: DeviceCapability.DeviceInfo? = null
    private var currentResolution: Size? = null
    private var throttledAnalyzer: ThrottledAnalyzer? = null

    /**
     * 初始化设备信息（应在应用启动时调用）
     */
    fun initDeviceInfo() {
        if (deviceInfo == null) {
            deviceInfo = DeviceCapability.detect(context)
        }
    }

    /**
     * 初始化 CameraX 相机，根据设备性能自动调整配置
     */
    fun initializeCamera(surfaceProvider: Preview.SurfaceProvider) {
        if (isShutdown) return
        
        // 确保设备信息已初始化
        initDeviceInfo()
        val info = deviceInfo ?: DeviceCapability.detect(context)
        
        // 根据设备性能选择分辨率和初始帧率
        val (targetSize, initialFps) = getRecommendedCameraConfig(info)
        currentResolution = targetSize
        
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
                            targetSize,
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
                        throttledAnalyzer = ThrottledAnalyzer(analyzer, initialFps)
                        it.setAnalyzer(executor, throttledAnalyzer!!)
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
                    Log.d("CameraManager", "${targetSize.width}×${targetSize.height} @ $initialFps fps 已启用（设备等级: ${info.tier}）")
                }
            } catch (e: Exception) {
                Log.e("CameraManager", "初始化失败", e)
            }
        }, ContextCompat.getMainExecutor(context))
    }
    
    /**
     * 根据设备性能推荐相机配置
     */
    private fun getRecommendedCameraConfig(info: DeviceCapability.DeviceInfo): Pair<Size, Int> {
        return when (info.tier) {
            DeviceCapability.PerformanceTier.LOW -> {
                // 低端机：低分辨率，低帧率
                Pair(Size(320, 240), 2)
            }
            DeviceCapability.PerformanceTier.MEDIUM -> {
                // 中端机：中等分辨率，中等帧率
                Pair(Size(480, 360), 6)
            }
            DeviceCapability.PerformanceTier.HIGH -> {
                // 高端机：高分辨率，高帧率
                Pair(Size(640, 480), 12)
            }
        }
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

    /** ★ 新增：运行时动态修改帧率 */
    fun setTargetFps(fps: Int) {
        throttledAnalyzer?.let {
            it.targetFps = fps.coerceIn(1, 15)
            Log.d("CameraManager", "帧率已设为 $fps fps")
        } ?: run {
            Log.w("CameraManager", "ThrottledAnalyzer未初始化，无法设置帧率")
        }
    }
    
    /**
     * 获取当前分辨率
     */
    fun getCurrentResolution(): Size? = currentResolution

    /**
     * 帧率节流器
     */
    private class ThrottledAnalyzer(
        private val delegate: ImageAnalysis.Analyzer,
        var targetFps: Int
    ) : ImageAnalysis.Analyzer {

        private val intervalNs: Long
            get() = TimeUnit.SECONDS.toNanos(1) / targetFps

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