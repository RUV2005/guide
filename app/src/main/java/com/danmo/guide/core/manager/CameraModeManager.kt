package com.danmo.guide.core.manager

import android.app.Activity
import android.util.Log
import androidx.lifecycle.LifecycleCoroutineScope
import com.danmo.guide.feature.camera.CameraManager
import com.danmo.guide.core.service.TtsService
import com.danmo.guide.feature.performancemonitor.PerformanceMonitor
import com.google.firebase.Firebase
import com.google.firebase.perf.performance
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * 摄像头模式管理模块
 * 处理内置/外置摄像头切换
 */
class CameraModeManager(
    private val activity: Activity,
    private val lifecycleScope: LifecycleCoroutineScope,
    private val perfMonitor: PerformanceMonitor,
    private val ttsService: TtsService?,
    private val uiManager: UIManager,
    private val onStartStream: () -> Unit,
    private val onStopStream: () -> Unit,
    private val onCameraInitialized: (CameraManager) -> Unit,
    private val onCheckPermission: () -> Unit
) {
    private var isCameraMode = true // true 表示内置摄像头模式，false 表示外置摄像头模式
    private var cameraExecutor: ExecutorService? = null
    private var cameraManager: CameraManager? = null

    /**
     * 切换摄像头模式
     */
    fun switchCameraMode() {
        lifecycleScope.launch(Dispatchers.Main) {
            if (isCameraMode) {
                // 切换到外置
                uiManager.switchCameraView(false)
                releaseCameraResources()
                isCameraMode = false
                onStartStream()
                ttsService?.speak("已切换到外置摄像头模式")
            } else {
                // 切换回内置
                uiManager.switchCameraView(true)
                onStopStream()
                perfMonitor.reset()
                // 注意：这里需要传入 createAnalyzer，但在这个上下文中无法获取
                // 应该在调用 switchCameraMode 之前确保摄像头已初始化
                onCheckPermission()
                isCameraMode = true
                ttsService?.speak("已切换到内置摄像头模式")
            }
        }
    }

    /**
     * 初始化摄像头资源
     */
    fun initCameraResources(createAnalyzer: () -> androidx.camera.core.ImageAnalysis.Analyzer, onInitialized: (CameraManager) -> Unit) {
        val trace = Firebase.performance.newTrace("camera_initialization")
        trace.start()

        if (cameraExecutor == null || cameraExecutor!!.isShutdown) {
            cameraExecutor = Executors.newSingleThreadExecutor()
        }

        if (cameraManager == null || cameraManager!!.isShutdown()) {
            cameraManager = CameraManager(activity, cameraExecutor!!, createAnalyzer())
            onInitialized(cameraManager!!)
        }

        Log.d("Camera", "摄像头资源初始化完成")
        trace.stop()
    }

    /**
     * 释放摄像头资源
     */
    fun releaseCameraResources() {
        try {
            cameraManager?.shutdown()
            cameraExecutor?.shutdown()
            cameraManager = null
            Log.d("Camera", "摄像头资源已释放")
        } catch (e: Exception) {
            Log.e("Camera", "释放摄像头资源失败", e)
        }
    }

    /**
     * 启动摄像头
     */
    fun startCamera(surfaceProvider: androidx.camera.core.Preview.SurfaceProvider) {
        cameraManager?.let { manager ->
            try {
                if (!activity.isFinishing && !activity.isDestroyed) {
                    manager.initializeCamera(surfaceProvider)
                    Log.d("Camera", "摄像头成功启动")
                } else {
                    Log.w("Camera", "Activity正在销毁，跳过摄像头启动")
                }
            } catch (e: Exception) {
                Log.e("Camera", "启动摄像头失败", e)
                uiManager.showToast("启动摄像头失败: ${e.message}")
            }
        } ?: run {
            Log.w("Camera", "尝试启动未初始化的摄像头管理器")
            uiManager.showToast("摄像头初始化失败，请重试")
        }
    }

    /**
     * 获取当前摄像头模式
     */
    fun isBuiltInMode(): Boolean = isCameraMode

    /**
     * 获取摄像头管理器
     */
    fun getCameraManager(): CameraManager? = cameraManager

    /**
     * 获取摄像头执行器
     */
    fun getCameraExecutor(): ExecutorService? = cameraExecutor
}

