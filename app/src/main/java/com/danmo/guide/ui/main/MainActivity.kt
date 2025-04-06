package com.danmo.guide.ui.main

import com.danmo.guide.feature.fall.FallDetector
import android.Manifest
import android.content.ComponentName
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.RequiresApi
import androidx.camera.core.ImageAnalysis
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.danmo.guide.R
import com.danmo.guide.databinding.ActivityMainBinding
import com.danmo.guide.feature.camera.CameraManager
import com.danmo.guide.feature.camera.ImageProxyUtils
import com.danmo.guide.feature.detection.ObjectDetectorHelper
import com.danmo.guide.feature.fall.TtsService
import com.danmo.guide.feature.feedback.DetectionProcessor
import com.danmo.guide.feature.feedback.FeedbackManager
import com.danmo.guide.feature.weather.WeatherManager
import com.danmo.guide.ui.components.OverlayView
import com.danmo.guide.ui.settings.SettingsActivity
import kotlinx.coroutines.launch
import org.tensorflow.lite.support.image.TensorImage
import org.tensorflow.lite.task.vision.detector.Detection
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.math.abs

class MainActivity : ComponentActivity(), FallDetector.EmergencyCallback,
    SensorEventListener {

    // 基础功能模块
    private lateinit var binding: ActivityMainBinding
    private lateinit var cameraExecutor: ExecutorService
    private lateinit var objectDetectorHelper: ObjectDetectorHelper
    private lateinit var feedbackManager: FeedbackManager
    private lateinit var cameraManager: CameraManager
    private lateinit var overlayView: OverlayView
    private lateinit var weatherManager: WeatherManager
    private var isWeatherAnnounced = false

    // 跌落检测模块
    private lateinit var fallDetector: FallDetector
    private lateinit var sensorManager: SensorManager

    // TTS 服务
    private var ttsService: TtsService? = null
    private var isTtsBound = false

    // 环境光传感器的上次值
    private var lastLightValue = 0f

    // 误报处理计数器
    private var falseAlarmCancelCount = 0

    // 权限请求
    private val requestPermissions =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { grants ->
            val allGranted = grants.all { it.value }
            if (!allGranted) showToast("部分功能可能受限")
        }

    // 服务连接
    private val ttsConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val binder = service as TtsService.TtsBinder
            ttsService = binder.getService()
            Log.d("MainActivity", "TTS服务已连接")
            // 将 TTS 服务传递给 com.danmo.guide.feature.fall.FallDetector
            fallDetector.ttsService = ttsService
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            ttsService = null
            Log.d("MainActivity", "TTS服务已断开")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // 初始化基础模块
        initBasicComponents()

        // 初始化跌倒检测
        initFallDetection()

        // 绑定TTS服务
        bindTtsService()
    }

    private fun initBasicComponents() {
        cameraExecutor = Executors.newSingleThreadExecutor()
        overlayView = binding.overlayView
        objectDetectorHelper = ObjectDetectorHelper(this)
        feedbackManager = FeedbackManager(this)
        cameraManager = CameraManager(this, cameraExecutor, createAnalyzer())
        weatherManager = WeatherManager(this)

        checkCameraPermission()
        getWeatherAndAnnounce()

        binding.fabSettings.setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }
    }

    private fun initFallDetection() {
        sensorManager = getSystemService(SENSOR_SERVICE) as SensorManager

        // 初始化 com.danmo.guide.feature.fall.FallDetector
        fallDetector = FallDetector(context = this)
        fallDetector.init(this) // 调用 init 方法
        fallDetector.setEmergencyCallback(this) // 设置紧急回调

        checkFallPermissions()
    }

    private fun bindTtsService() {
        Intent(this, TtsService::class.java).also { intent ->
            bindService(intent, ttsConnection, BIND_AUTO_CREATE)
            isTtsBound = true
        }
    }

    override fun onResume() {
        super.onResume()
        // 启动 com.danmo.guide.feature.fall.FallDetector 的传感器监听
        fallDetector.startListening()
    }

    override fun onPause() {
        super.onPause()
        // 停止 com.danmo.guide.feature.fall.FallDetector 的传感器监听
        fallDetector.stopListening()
    }

    override fun onDestroy() {
        super.onDestroy()
        if (isTtsBound) {
            unbindService(ttsConnection)
            isTtsBound = false
        }
        cameraExecutor.shutdown()
    }

    // region 跌倒检测回调
    override fun onEmergencyDetected() {
        runOnUiThread {
            binding.statusText.text = getString(R.string.fall_detected_warning)
            ttsService?.speak(getString(R.string.fall_alert_voice))
            triggerEmergencyCall()
        }
    }

    private fun triggerEmergencyCall() {
        if (checkCallPermission()) {
            fallDetector.triggerEmergencyCall()
        } else {
            showToast(getString(R.string.permission_required))
        }
    }
    // endregion

    // region 权限管理
    private fun checkCameraPermission() {
        when {
            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.CAMERA
            ) == PackageManager.PERMISSION_GRANTED -> startCamera()
            else -> requestPermissions.launch(arrayOf(Manifest.permission.CAMERA))
        }
    }

    private fun checkFallPermissions() {
        val requiredPermissions = arrayOf(
            Manifest.permission.CALL_PHONE,
            Manifest.permission.BODY_SENSORS
        )

        val missingPermissions = requiredPermissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }

        if (missingPermissions.isNotEmpty()) {
            requestPermissions.launch(missingPermissions.toTypedArray())
        }
    }

    private fun checkCallPermission() = ContextCompat.checkSelfPermission(
        this,
        Manifest.permission.CALL_PHONE
    ) == PackageManager.PERMISSION_GRANTED
    // endregion

    // region 传感器数据处理
    @RequiresApi(Build.VERSION_CODES.O)
    override fun onSensorChanged(event: SensorEvent) {
        when (event.sensor.type) {
            Sensor.TYPE_ACCELEROMETER -> fallDetector.onSensorChanged(event)
            Sensor.TYPE_LIGHT -> handleLightSensor(event)
        }
    }

    private fun handleLightSensor(event: SensorEvent) {
        val currentLight = event.values[0]
        if (abs(currentLight - lastLightValue) > 1) {
            lastLightValue = currentLight
            cameraManager.enableTorchMode(currentLight < 10.0f)
            binding.statusText.text = if (currentLight < 10.0f) {
                getString(R.string.low_light_warning)
            } else {
                getString(R.string.normal_light_status)
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
    // endregion

    // region 误报处理
    override fun onBackPressed() {
        if (fallDetector.isFallDetected()) {
            handleFalseAlarm()
        } else {
            super.onBackPressed()
        }
    }

    private fun handleFalseAlarm() {
        if (++falseAlarmCancelCount > 2) {
            fallDetector.resetFallState()
            ttsService?.speak(getString(R.string.false_alarm_canceled))
            falseAlarmCancelCount = 0
            binding.statusText.text = getString(R.string.normal_status)
        }
    }
    // endregion

    // region 摄像头相关
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
                    updateOverlayView(results, rotationDegrees)
                    updateStatusUI(results)
                    handleDetectionResults(results)
                } finally {
                    imageProxy.close()
                }
            }
        }
    }
    // endregion

    // region 通用工具方法
    private fun getWeatherAndAnnounce() {
        lifecycleScope.launch {
            if (isWeatherAnnounced) return@launch
            isWeatherAnnounced = true

            weatherManager.getLocationByIP()?.let { location ->
                weatherManager.getWeather(location.latitude, location.longitude)?.let { data ->
                    FeedbackManager.getInstance(this@MainActivity)
                        .enqueueWeatherAnnouncement(
                            weatherManager.generateSpeechText(data)
                        )
                }
            } ?: showToast(getString(R.string.location_failed))
        }
    }

    private fun handleDetectionResults(results: List<Detection>) {
        results.maxByOrNull { it.categories.firstOrNull()?.score ?: 0f }?.let {
            feedbackManager.handleDetectionResult(it)
        }
    }

    private fun updateOverlayView(results: List<Detection>, rotation: Int) {
        runOnUiThread {
            overlayView.updateDetections(results, rotation)
        }
    }

    private var lastStatusUpdateTime = 0L
    private fun updateStatusUI(results: List<Detection>) {
        if (System.currentTimeMillis() - lastStatusUpdateTime < 500) return
        lastStatusUpdateTime = System.currentTimeMillis()

        val filtered = results.filter {
            (it.categories.maxByOrNull { c -> c.score }?.score
                ?: 0f) >= DetectionProcessor.confidenceThreshold
        }

        runOnUiThread {
            binding.statusText.text = when {
                filtered.isEmpty() -> getString(R.string.no_objects_detected)
                else -> "检测到: ${
                    filtered.joinToString {
                        DetectionProcessor.getInstance(this).getChineseLabel(
                            it.categories.maxByOrNull { c -> c.score }?.label ?: "unknown"
                        )
                    }
                }"
            }
        }
    }

    private fun showToast(message: String) {
        runOnUiThread { Toast.makeText(this, message, Toast.LENGTH_SHORT).show() }
    }
    // endregion
}