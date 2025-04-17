package com.danmo.guide.feature.camera
import android.content.Context
import android.util.Log
import androidx.camera.core.CameraControl
import androidx.camera.core.CameraInfoUnavailableException
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import java.util.concurrent.Executor
/**
 * 相机管理类，处理相机初始化、预览和闪光灯控制
 * Camera manager class handling camera initialization, preview and torch control
 */
class CameraManager(
    private val context: Context,         // 上下文对象，用于获取系统服务 / Context for system services
    private val executor: Executor,       // 异步操作执行器 / Executor for async operations
    private val analyzer: ImageAnalysis.Analyzer // 图像分析器 / Image analysis processor
) {
    private var cameraControl: CameraControl? = null  // 相机控制接口 / Camera control interface
    private var isTorchActive = false    // 闪光灯状态标志 / Torch status flag
    /**
     * 初始化相机配置并启动预览
     * Initialize camera configuration and start preview
     * @param surfaceProvider 预览画面渲染的表面提供器 / Preview surface provider
     */
    fun initializeCamera(surfaceProvider: Preview.SurfaceProvider) {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(context)
        cameraProviderFuture.addListener({
            try {
                // 获取相机提供器实例 / Get camera provider instance
                val cameraProvider = cameraProviderFuture.get()
                // 配置预览用例 / Configure preview use case
                val preview = Preview.Builder().build().apply {
                    setSurfaceProvider(surfaceProvider)
                }
                // 配置图像分析用例 / Configure image analysis use case
                val imageAnalysis = ImageAnalysis.Builder()
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST) // 只保留最新帧 / Keep only latest frame
                    .build()
                    .also { it.setAnalyzer(executor, analyzer) }
                // 绑定生命周期并启动相机 / Bind lifecycle and start camera
                val camera = cameraProvider.bindToLifecycle(
                    context as LifecycleOwner,
                    CameraSelector.DEFAULT_BACK_CAMERA, // 使用后置摄像头 / Use back camera
                    preview,
                    imageAnalysis
                )
                // 保存相机控制实例 / Save camera control instance
                cameraControl = camera.cameraControl
            } catch (e: Exception) {
                Log.e("CameraManager", "相机初始化失败 Camera initialization failed", e)
            }
        }, ContextCompat.getMainExecutor(context)) // 使用主线程执行器 / Use main thread executor
    }
    /**
     * 控制闪光灯开关
     * Control torch mode
     * @param enabled 是否开启闪光灯 / Whether to enable torch
     * @param onComplete 操作完成回调（可选） / Completion callback (optional)
     */
    fun enableTorchMode(
        enabled: Boolean,
        onComplete: (() -> Unit)? = null
    ) {
        // 状态无变化时直接返回 / Return immediately if status unchanged
        if (isTorchActive == enabled) {
            onComplete?.invoke()
            return
        }
        try {
            // 异步执行闪光灯控制 / Async torch control
            cameraControl?.enableTorch(enabled)?.addListener({
                isTorchActive = enabled
                onComplete?.invoke() // 触发完成回调 / Trigger completion callback
                Log.d("CameraManager", "闪光灯状态更新 Torch status: $enabled")
            }, executor) // 使用指定线程池 / Use specified executor
        } catch (e: CameraInfoUnavailableException) {
            Log.e("CameraManager", "闪光灯控制失败 Torch control failed", e)
        }
    }
}