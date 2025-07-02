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

class CameraManager(
    private val context: Context,
    private val executor: Executor,
    private val analyzer: ImageAnalysis.Analyzer
) {
    private var cameraControl: CameraControl? = null
    private var isTorchActive = false
    private var cameraProvider: ProcessCameraProvider? = null
    private var isShutdown = false

    fun initializeCamera(surfaceProvider: Preview.SurfaceProvider) {
        if (isShutdown) {
            Log.w("CameraManager", "尝试初始化已关闭的摄像头管理器")
            return
        }

        val cameraProviderFuture = ProcessCameraProvider.getInstance(context)
        cameraProviderFuture.addListener({
            try {
                cameraProvider = cameraProviderFuture.get()
                val preview = Preview.Builder().build().apply {
                    setSurfaceProvider(surfaceProvider)
                }

                val imageAnalysis = ImageAnalysis.Builder()
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .build()
                    .also { it.setAnalyzer(executor, analyzer) }

                cameraProvider?.bindToLifecycle(
                    context as LifecycleOwner,
                    CameraSelector.DEFAULT_BACK_CAMERA,
                    preview,
                    imageAnalysis
                )?.let { camera ->
                    cameraControl = camera.cameraControl
                    Log.d("CameraManager", "摄像头初始化成功")
                }
            } catch (e: Exception) {
                Log.e("CameraManager", "初始化摄像头失败", e)
            }
        }, ContextCompat.getMainExecutor(context))
    }

    // 修复1: 添加enableTorchMode函数
    fun enableTorchMode(enabled: Boolean, onComplete: (() -> Unit)? = null) {
        if (isTorchActive == enabled) {
            onComplete?.invoke()
            return
        }

        try {
            cameraControl?.enableTorch(enabled)?.addListener({
                isTorchActive = enabled
                onComplete?.invoke()
                Log.d("CameraManager", "闪光灯状态更新: $enabled")
            }, executor)
        } catch (e: CameraInfoUnavailableException) {
            Log.e("CameraManager", "闪光灯控制失败", e)
        }
    }

    // 修复2: 添加shutdown函数
    fun shutdown() {
        isShutdown = true
        try {
            cameraProvider?.unbindAll()
            cameraProvider = null
            Log.d("CameraManager", "摄像头已关闭")
        } catch (e: Exception) {
            Log.e("CameraManager", "关闭摄像头失败", e)
        }
    }

    // 修复3: 添加公共访问方法
    fun isShutdown(): Boolean = isShutdown
}