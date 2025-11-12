package com.danmo.guide.core.manager

import android.app.Activity
import android.util.Log
import androidx.lifecycle.LifecycleCoroutineScope
import com.amap.api.location.AMapLocation
import com.danmo.guide.R
import com.danmo.guide.databinding.ActivityMainBinding
import com.danmo.guide.feature.fall.FallDetector
import com.danmo.guide.core.service.TtsService
import com.danmo.guide.feature.location.LocationManager
import com.danmo.guide.feature.weather.WeatherManager
import com.google.firebase.Firebase
import com.google.firebase.perf.performance
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 位置和天气管理模块
 * 统一处理位置获取和天气查询
 */
class LocationWeatherManager(
    private val activity: Activity,
    private val binding: ActivityMainBinding,
    private val lifecycleScope: LifecycleCoroutineScope,
    private val locationManager: LocationManager,
    private val weatherManager: WeatherManager,
    private val ttsService: TtsService?,
    private val uiManager: UIManager
) : LocationManager.LocationCallback, FallDetector.WeatherCallback {

    /**
     * 启动位置检测
     */
    fun startLocationDetection(isWeatherButton: Boolean = false) {
        Log.d("LocationWeatherManager", "尝试启动定位...")
        locationManager.startLocation(this, isWeatherButton)
    }

    override fun onLocationSuccess(location: AMapLocation?, isWeatherButton: Boolean) {
        if (location == null) {
            uiManager.setStatusText(activity.getString(R.string.location_failed))
            ttsService?.speak(activity.getString(R.string.location_failed))
            return
        }

        val pos = listOfNotNull(
            location.district,
            location.street,
            location.city,
            location.address
        ).firstOrNull { it.isNotBlank() } ?: activity.getString(R.string.unknown_location)

        if (!isWeatherButton) {
            ttsService?.speak(activity.getString(R.string.current_location, location.address))
        } else {
            getWeatherAndAnnounce(location.latitude, location.longitude, pos)
        }
    }

    override fun onLocationFailure(errorCode: Int, errorInfo: String?) {
        activity.runOnUiThread {
            val msg = activity.getString(R.string.location_failed) + " (错误码: $errorCode)"
            Log.e("LocationWeatherManager", msg)
            uiManager.showToast(msg)
            ttsService?.speak(activity.getString(R.string.speak_location_error))
            uiManager.setStatusText(activity.getString(R.string.location_failed))
        }
    }

    override fun getWeatherAndAnnounce(lat: Double, lon: Double, cityName: String) {
        val networkRequestTrace = Firebase.performance.newTrace("network_request_weather")
        networkRequestTrace.start()

        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val weatherData = weatherManager.getWeather(lat, lon)

                withContext(Dispatchers.Main) {
                    if (weatherData == null) {
                        uiManager.showToast("天气获取失败")
                        ttsService?.speak("获取天气信息失败")
                        return@withContext
                    }

                    val speechText = weatherManager.generateSpeechText(weatherData, cityName)
                    speakWeather(speechText)
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    Log.e("LocationWeatherManager", "获取天气失败", e)
                    uiManager.showToast("天气获取失败: ${e.message}")
                    ttsService?.speak("天气服务异常，请稍后重试")
                }
            } finally {
                networkRequestTrace.stop()
            }
        }
    }

    override fun speakWeather(message: String) {
        Log.d("LocationWeatherManager", "播报天气: $message")
        ttsService?.speak(message)
    }
}

