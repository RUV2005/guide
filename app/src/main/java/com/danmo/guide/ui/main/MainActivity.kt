package com.danmo.guide.ui.main

import android.Manifest
import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.ImageAnalysis
import androidx.core.content.ContextCompat
import com.danmo.guide.databinding.ActivityMainBinding
import com.danmo.guide.feature.camera.CameraManager
import com.danmo.guide.feature.camera.ImageProxyUtils
import com.danmo.guide.feature.detection.ObjectDetectorHelper
import com.danmo.guide.feature.feedback.DetectionProcessor
import com.danmo.guide.feature.feedback.FeedbackManager
import com.danmo.guide.ui.components.OverlayView
import com.danmo.guide.ui.settings.SettingsActivity
import org.tensorflow.lite.support.image.TensorImage
import org.tensorflow.lite.task.vision.detector.Detection
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.math.abs

/**
 * 主活动，负责相机预览、物体检测和环境感知
 * Main Activity handling camera preview, object detection and environment sensing
 */
class MainActivity : ComponentActivity() {
    // 视图绑定实例 / View binding instance
    private lateinit var binding: ActivityMainBinding

    // 相机操作线程池 / Camera operation thread pool
    private lateinit var cameraExecutor: ExecutorService

    // 物体检测帮助类 / Object detection helper
    private lateinit var objectDetectorHelper: ObjectDetectorHelper

    // 反馈管理器 / Feedback manager
    private lateinit var feedbackManager: FeedbackManager

    // 相机管理器 / Camera manager
    private lateinit var cameraManager: CameraManager

    // 检测结果覆盖层视图 / Detection overlay view
    private lateinit var overlayView: OverlayView

    // 传感器管理器 / Sensor manager
    private lateinit var sensorManager: SensorManager

    // 光线传感器实例 / Light sensor instance
    private var lightSensor: Sensor? = null

    // 最后记录的光线值 / Last recorded light value
    private var lastLightValue = 0f

    // 光线传感器阈值（单位：lux）低于此值开启闪光灯
    // Light sensor threshold (in lux), below which the flashlight is enabled
    private companion object {
        const val LIGHT_THRESHOLD = 10.0f
    }

    // 权限请求回调 / Permission request callback
    private val permissionsLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            startCamera()
        } else {
            // 根据用户是否选择"不再询问"显示不同提示
            // Show different prompts based on "Don't ask again" selection
            if (shouldShowRequestPermissionRationale(Manifest.permission.CAMERA)) {
                showToast("需要相机权限来提供导览功能，请授予权限")
            } else {
                showToast("相机权限被永久拒绝，请到应用设置中启用")
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 初始化视图绑定 / Initialize view binding
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // 初始化线程池和组件 / Initialize thread pool and components
        cameraExecutor = Executors.newSingleThreadExecutor()
        overlayView = binding.overlayView
        objectDetectorHelper = ObjectDetectorHelper(this)
        feedbackManager = FeedbackManager(this)
        cameraManager = CameraManager(this, cameraExecutor, createAnalyzer())

        // 初始化光线传感器 / Initialize light sensor
        sensorManager = getSystemService(SENSOR_SERVICE) as SensorManager
        lightSensor = sensorManager.getDefaultSensor(Sensor.TYPE_LIGHT)

        checkCameraPermission()

        // 设置按钮点击监听 / Set button click listener
        binding.fabSettings.setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }
    }

    // 光线传感器监听器 / Light sensor listener
    private val lightSensorListener = object : SensorEventListener {
        override fun onSensorChanged(event: SensorEvent) {
            if (event.sensor.type == Sensor.TYPE_LIGHT) {
                val currentLight = event.values[0]

                // 防抖动处理 / Debounce handling
                if (abs(currentLight - lastLightValue) > 1) {
                    lastLightValue = currentLight

                    // 根据光线强度控制闪光灯 / Control flashlight based on light intensity
                    if (currentLight < LIGHT_THRESHOLD) {
                        cameraManager.enableTorchMode(true)
                        binding.statusText.text = "低光环境，已开启辅助照明"
                    } else {
                        cameraManager.enableTorchMode(false)
                        binding.statusText.text = "环境光线正常"
                    }
                }
            }
        }

        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
    }

    override fun onResume() {
        super.onResume()
        // 注册传感器监听 / Register sensor listener
        lightSensor?.let {
            sensorManager.registerListener(
                lightSensorListener,
                it,
                SensorManager.SENSOR_DELAY_UI // 使用UI级别的更新频率 / Use UI-level update frequency
            )
        }
    }

    override fun onPause() {
        super.onPause()
        // 注销传感器监听 / Unregister sensor listener
        sensorManager.unregisterListener(lightSensorListener)
    }

    // 检查相机权限 / Check camera permission
    private fun checkCameraPermission() {
        when {
            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.CAMERA
            ) == PackageManager.PERMISSION_GRANTED -> startCamera()
            else -> permissionsLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    // 启动相机预览 / Start camera preview
    private fun startCamera() {
        cameraManager.initializeCamera(binding.previewView.surfaceProvider)
    }

    // 创建图像分析器 / Create image analyzer
    private fun createAnalyzer(): ImageAnalysis.Analyzer {
        return ImageAnalysis.Analyzer { imageProxy ->
            cameraExecutor.submit {
                try {
                    // 转换图像格式 / Convert image format
                    val bitmap = ImageProxyUtils.toBitmap(imageProxy) ?: return@submit
                    val tensorImage = TensorImage.fromBitmap(bitmap)
                    val rotationDegrees = imageProxy.imageInfo.rotationDegrees

                    // 执行物体检测 / Perform object detection
                    val results = objectDetectorHelper.detect(tensorImage, rotationDegrees)

                    // 更新覆盖层尺寸 / Update overlay dimensions
                    overlayView.setModelInputSize(tensorImage.width, tensorImage.height)
                    Log.d("OverlayView", "View Size: tensorImage.width=${tensorImage.width}, tensorImage.height=${tensorImage.height}")

                    // 更新界面 / Update UI
                    updateOverlayView(results, rotationDegrees)
                    updateStatusUI(results)
                    handleDetectionResults(results)
                } catch (e: Exception) {
                    Log.e("ImageAnalysis", "Error in image analysis", e)
                } finally {
                    imageProxy.close()
                }
            }
        }
    }

    // 处理检测结果 / Process detection results
    private fun handleDetectionResults(results: List<Detection>) {
        // 获取最高置信度结果 / Get highest confidence result
        results.maxByOrNull {
            it.categories.firstOrNull()?.score ?: 0f
        }?.let { topResult ->
            feedbackManager.handleDetectionResult(topResult)
        }
    }

    // 更新覆盖层检测结果 / Update overlay with detection results
    private fun updateOverlayView(results: List<Detection>, rotationDegrees: Int) {
        runOnUiThread {
            overlayView.updateDetections(results, rotationDegrees)
        }
    }

    // 状态更新节流控制 / Status update throttling control
    private var lastStatusUpdateTime = 0L
    private val statusUpdateInterval = 500L

    // 更新状态栏信息 / Update status bar information
    @SuppressLint("DefaultLocale", "SetTextI18n")
    private fun updateStatusUI(results: List<Detection>) {
        val currentTime = System.currentTimeMillis()
        if (currentTime - lastStatusUpdateTime < statusUpdateInterval) return
        lastStatusUpdateTime = currentTime

        // 过滤低置信度结果 / Filter low confidence results
        val filtered = results.filter { detection ->
            detection.categories.maxByOrNull { it.score }?.let { category ->
                category.score >= DetectionProcessor.confidenceThreshold
            } ?: false
        }

        runOnUiThread {
            // 构建状态文本 / Build status text
            val statusText = if (filtered.isEmpty()) {
                "未检测到有效障碍物"
            } else {
                "检测到: ${filtered.joinToString { detection ->
                    detection.categories.maxByOrNull { it.score }?.let { category ->
                        "${getChineseLabel(category.label)} (${String.format("%.1f%%", category.score * 100)})"
                    } ?: "未知物体"
                }}"
            }

            // 添加光线状态信息 / Add light status information
            val lightStatus = if (lastLightValue < LIGHT_THRESHOLD) {
                "[低光环境]"
            } else {
                "[光线正常]"
            }
            binding.statusText.text = "$lightStatus $statusText"
        }
    }

    // 获取本地化标签 / Get localized label
    private fun getChineseLabel(originalLabel: String): String {
        return DetectionProcessor.getInstance(this).getChineseLabel(originalLabel)
    }

    // 显示Toast提示 / Show toast message
    private fun showToast(message: String) {
        runOnUiThread {
            Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
        }
    }

    override fun onDestroy() {
        // 清理资源 / Clean up resources
        cameraExecutor.shutdown()
        objectDetectorHelper.close()
        feedbackManager.shutdown()
        super.onDestroy()
    }
}