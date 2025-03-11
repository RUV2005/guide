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
import com.danmo.guide.feature.feedback.FeedbackManager
import com.danmo.guide.ui.components.OverlayView
import com.danmo.guide.ui.settings.SettingsActivity
import org.tensorflow.lite.support.image.TensorImage
import org.tensorflow.lite.task.vision.detector.Detection
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.math.abs

class MainActivity : ComponentActivity() {
    private lateinit var binding: ActivityMainBinding
    private lateinit var cameraExecutor: ExecutorService
    private lateinit var objectDetectorHelper: ObjectDetectorHelper
    private lateinit var feedbackManager: FeedbackManager
    private lateinit var cameraManager: CameraManager
    private lateinit var overlayView: OverlayView
    private lateinit var sensorManager: SensorManager
    private var lightSensor: Sensor? = null
    private var lastLightValue = 0f

    // 光线传感器阈值（单位：lux）
    private companion object {
        const val LIGHT_THRESHOLD = 10.0f // 低于此值开启闪光灯
    }


    private val permissionsLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            startCamera()
        } else {
            // 根据用户是否选择"不再询问"显示不同提示
            if (shouldShowRequestPermissionRationale(Manifest.permission.CAMERA)) {
                showToast("需要相机权限来提供导览功能，请授予权限")
            } else {
                showToast("相机权限被永久拒绝，请到应用设置中启用")
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        cameraExecutor = Executors.newSingleThreadExecutor()
        overlayView = binding.overlayView
        objectDetectorHelper = ObjectDetectorHelper(this)
        feedbackManager = FeedbackManager(this)
        cameraManager = CameraManager(this, cameraExecutor, createAnalyzer())
        // 初始化光线传感器
        sensorManager = getSystemService(SENSOR_SERVICE) as SensorManager
        lightSensor = sensorManager.getDefaultSensor(Sensor.TYPE_LIGHT)

        checkCameraPermission()
        // 新增点击监听
        binding.fabSettings.setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }
    }


    // 传感器事件监听器
    private val lightSensorListener = object : SensorEventListener {
        override fun onSensorChanged(event: SensorEvent) {
            if (event.sensor.type == Sensor.TYPE_LIGHT) {
                val currentLight = event.values[0]

                // 防抖动处理
                if (abs(currentLight - lastLightValue) > 1) {
                    lastLightValue = currentLight

                    // 根据光线强度控制闪光灯
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
        lightSensor?.let {
            sensorManager.registerListener(
                lightSensorListener,
                it,
                SensorManager.SENSOR_DELAY_UI // 使用更高的延迟
            )
        }
    }
    override fun onPause() {
        super.onPause()
        // 注销传感器监听
        sensorManager.unregisterListener(lightSensorListener)
    }

    private fun checkCameraPermission() {
        when {
            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.CAMERA
            ) == PackageManager.PERMISSION_GRANTED -> startCamera()
            else -> permissionsLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    private fun startCamera() {
        cameraManager.initializeCamera(binding.previewView.surfaceProvider)
    }

    private fun createAnalyzer(): ImageAnalysis.Analyzer {
        return ImageAnalysis.Analyzer { imageProxy ->
            cameraExecutor.submit {
                try {
                    val bitmap = ImageProxyUtils.toBitmap(imageProxy) ?: return@submit
                    val tensorImage = TensorImage.fromBitmap(bitmap)
                    val rotationDegrees = imageProxy.imageInfo.rotationDegrees
                    val results = objectDetectorHelper.detect(tensorImage, rotationDegrees)

                    overlayView.setModelInputSize(tensorImage.width, tensorImage.height)
                    Log.d("OverlayView", "View Size: tensorImage.width=${tensorImage.width}, tensorImage.height=${tensorImage.height}")
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

    private fun handleDetectionResults(results: List<Detection>) {
        // 正确获取最高分结果
        results.maxByOrNull {
            it.categories.firstOrNull()?.score ?: 0f  // 获取第一个类别的分数
        }?.let { topResult ->
            feedbackManager.handleDetectionResult(
                topResult
            )
        }
    }

    private fun updateOverlayView(results: List<Detection>, rotationDegrees: Int) {
        runOnUiThread {
            overlayView.updateDetections(results, rotationDegrees)
        }
    }

    private var lastStatusUpdateTime = 0L // 上次更新状态的时间戳
    private val statusUpdateInterval = 500L // 状态更新的时间间隔（毫秒）

    @SuppressLint("DefaultLocale", "SetTextI18n")
    private fun updateStatusUI(results: List<Detection>) {
        val currentTime = System.currentTimeMillis()
        if (currentTime - lastStatusUpdateTime < statusUpdateInterval) return
        lastStatusUpdateTime = currentTime

        val filtered = results.filter { detection ->
            (detection.categories.maxByOrNull { it.score }?.let { it.score >= FeedbackManager.CONFIDENCE_THRESHOLD } ?: false)
        }

        runOnUiThread {
            binding.statusText.text = if (filtered.isEmpty()) {
                "未检测到有效障碍物"
            } else {
                "检测到: ${filtered.joinToString { detection ->
                    detection.categories.maxByOrNull { it.score }?.let { category ->
                        "${getChineseLabel(category.label)} (${String.format("%.1f%%", category.score * 100)})"
                    } ?: "未知物体"
                }}"
            }
            val lightStatus = if (lastLightValue < LIGHT_THRESHOLD) {
                "[低光环境]"
            } else {
                "[光线正常]"
            }
            binding.statusText.text = "$lightStatus ${binding.statusText.text}"
        }
    }

    private fun getChineseLabel(originalLabel: String): String {
        return feedbackManager.getChineseLabel(originalLabel)
    }

    private fun showToast(message: String) {
        runOnUiThread {
            Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
        }
    }

    override fun onDestroy() {
        cameraExecutor.shutdown()
        objectDetectorHelper.close()
        feedbackManager.shutdown()
        super.onDestroy()
    }
}