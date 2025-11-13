package com.danmo.guide.core.manager

import android.app.Activity
import androidx.lifecycle.LifecycleCoroutineScope
import com.amap.api.location.AMapLocationClient
import com.danmo.guide.databinding.ActivityMainBinding
import com.danmo.guide.feature.detection.ObjectDetectorHelper
import com.danmo.guide.feature.fall.FallDetector
import com.danmo.guide.feature.feedback.FeedbackManager
import com.amap.api.location.AMapLocation
import com.danmo.guide.core.manager.InitState
import com.danmo.guide.feature.location.LocationManager
import com.danmo.guide.feature.weather.WeatherManager
import com.danmo.guide.ui.components.OverlayView
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * 初始化管理模块
 * 统一管理所有组件的初始化逻辑
 */
class InitializationManager(
    private val activity: Activity,
    private val binding: ActivityMainBinding,
    private val lifecycleScope: LifecycleCoroutineScope,
    private val uiManager: UIManager?
) {
    lateinit var overlayView: OverlayView
    lateinit var objectDetectorHelper: ObjectDetectorHelper
    lateinit var feedbackManager: FeedbackManager
    lateinit var weatherManager: WeatherManager
    lateinit var locationManager: LocationManager
    lateinit var fallDetector: FallDetector
    
    // 从 InitState 同步状态
    val voskReady: Boolean
        get() = InitState.voskReady
    
    val cachedLocation: AMapLocation?
        get() = InitState.cachedLocation

    /**
     * 初始化基础组件
     */
    fun initializeBasicComponents() {
        // 隐私合规
        AMapLocationClient.updatePrivacyShow(activity, true, true)
        AMapLocationClient.updatePrivacyAgree(activity, true)

        // 初始化核心组件
        overlayView = binding.overlayView as OverlayView
        objectDetectorHelper = ObjectDetectorHelper(activity)
        feedbackManager = FeedbackManager(activity)
        weatherManager = WeatherManager()
        locationManager = LocationManager.instance!!
        locationManager.initialize(activity)
    }

    /**
     * 初始化跌倒检测
     */
    fun initializeFallDetection(sosNumber: String) {
        val sensorManager = activity.getSystemService(Activity.SENSOR_SERVICE) as android.hardware.SensorManager
        fallDetector = FallDetector(
            locationManager = locationManager,
            context = activity,
            sosNumber = sosNumber
        )
        fallDetector.init(activity)
    }

    /**
     * 设置 Vosk 就绪状态
     */
    fun setVoskReady(ready: Boolean) {
        InitState.voskReady = ready
    }
    
    /**
     * 设置缓存的定位信息
     */
    fun setCachedLocation(location: AMapLocation?) {
        InitState.cachedLocation = location
    }

    /**
     * 检查 Vosk 模型状态
     */
    fun checkVoskModelStatus(uiManager: UIManager) {
        lifecycleScope.launch {
            delay(3000)
            if (!voskReady) {
                uiManager.showToast("语音模型加载中，请稍后重试语音功能")
            }
        }
    }

    /**
     * 处理缓存的定位信息
     */
    fun handleCachedLocation(onLocationSuccess: (AMapLocation) -> Unit) {
        cachedLocation?.let {
            onLocationSuccess(it)
        }
    }
}

