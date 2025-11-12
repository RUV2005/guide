package com.danmo.guide.feature.location

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.pm.PackageManager
import android.util.Log
import androidx.core.app.ActivityCompat
import com.amap.api.location.AMapLocation
import com.amap.api.location.AMapLocationClient
import com.amap.api.location.AMapLocationClientOption
import com.amap.api.location.AMapLocationListener
import com.google.firebase.Firebase
import com.google.firebase.perf.performance
import kotlinx.coroutines.*
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.lang.ref.WeakReference

class LocationManager private constructor() : AMapLocationListener {
    private var activityRef: WeakReference<Activity>? = null
    private var locationClient: AMapLocationClient? = null
    private var locationOption: AMapLocationClientOption? = null
    var callback: LocationCallback? = null
    private var isWeatherButton = false // 用于区分按钮来源
    // 新增：缓存定位
    private var cachedLocation: AMapLocation? = null


    fun initialize(activity: Activity) {
        this.activityRef = WeakReference(activity)
        initLocationSettings()
    }

    fun startLocation(callback: LocationCallback, isWeatherButton: Boolean = false) {
        this.isWeatherButton = isWeatherButton
        this.callback = callback
        if (checkPermissions()) {
            startLocationService()
        }
    }

    fun stopLocation() {
        locationClient?.stopLocation()
    }

    fun destroy() {
        if (locationClient != null) {
            locationClient!!.onDestroy()
            locationClient = null
        }
        activityRef = null
    }

    private fun initLocationSettings() {
        try {
            val context = safeContext ?: return
            locationClient = AMapLocationClient(context)
            locationOption = AMapLocationClientOption()
            // 配置定位参数
            locationOption!!.setLocationMode(AMapLocationClientOption.AMapLocationMode.Hight_Accuracy)
            locationOption!!.setOnceLocation(true)
            locationOption!!.setNeedAddress(true)
            locationOption!!.setHttpTimeOut(15000)
            locationOption!!.setLocationCacheEnable(false)
            locationClient!!.setLocationOption(locationOption)
            locationClient!!.setLocationListener(this)
        } catch (e: Exception) {
            notifyError(-1, "定位初始化失败: " + e.message)
        }
    }

    private fun startLocationService() {
        if (locationClient != null) {
            try {
                locationClient!!.startLocation()
            } catch (e: Exception) {
                notifyError(-2, "启动定位服务失败: " + e.message)
            }
        }
    }

    private fun checkPermissions(): Boolean {
        val activity = safeActivity ?: return false
        val needRequest: MutableList<String> = ArrayList()
        for (perm in REQUIRED_PERMISSIONS) {
            if (ActivityCompat.checkSelfPermission(activity, perm)
                != PackageManager.PERMISSION_GRANTED
            ) {
                needRequest.add(perm)
            }
        }
        if (needRequest.isNotEmpty()) {
            ActivityCompat.requestPermissions(
                activity,
                needRequest.toTypedArray(),
                REQUEST_CODE_PERMISSION
            )
            return false
        }
        return true
    }

    override fun onLocationChanged(aMapLocation: AMapLocation?) {
        if (callback == null) return
        if (aMapLocation != null) {
            if (aMapLocation.errorCode == 0) {
                // 自动兜底（如果高德SDK没有返回详细地址，则尝试逆地理API兜底）
                val hasAddress = !(aMapLocation.address.isNullOrBlank() &&
                        aMapLocation.district.isNullOrBlank() &&
                        aMapLocation.city.isNullOrBlank() &&
                        aMapLocation.street.isNullOrBlank())
                if (hasAddress) {
                    notifySuccess(aMapLocation)
                } else {
                    // 兜底：用高德逆地理API
                    safeActivity?.let { activity ->
                        // 启动协程（需主工程引入kotlinx-coroutines-android依赖）
                        CoroutineScope(Dispatchers.Main).launch {
                            val address = getAddressByLatLng(
                                aMapLocation.latitude,
                                aMapLocation.longitude
                            )
                            Log.d("定位兜底", "逆地理兜底得到: $address")
                            // 用一个虚拟的AMapLocation返回
                            val fallback = AMapLocation(aMapLocation)
                            fallback.address = address
                            notifySuccess(fallback)
                        }
                    } ?: run {
                        notifySuccess(aMapLocation)
                    }
                }
            } else {
                notifyError(
                    aMapLocation.errorCode,
                    getErrorInfo(aMapLocation.errorCode)
                )
            }
        } else {
            notifyError(-3, "定位结果为空")
        }
    }

    private fun notifySuccess(location: AMapLocation?) {
        val activity = safeActivity
        activity?.runOnUiThread {
            callback?.onLocationSuccess(location, isWeatherButton)
        }
    }

    private fun notifyError(code: Int, msg: String) {
        val activity = safeActivity
        activity?.runOnUiThread {
            callback?.onLocationFailure(code, msg)
        }
    }

    private fun getErrorInfo(errorCode: Int): String {
        return when (errorCode) {
            1 -> "一些重要参数为空"
            2 -> "定位结果解析失败"
            3 -> "网络连接异常"
            4 -> "定位失败"
            5 -> "定位结果解析失败"
            6 -> "定位服务返回异常"
            7 -> "KEY鉴权失败"
            8 -> "Android版本过低"
            9 -> "服务启动失败"
            10 -> "定位间隔设置过短"
            11 -> "定位请求被强制中断"
            12 -> "缺少定位权限"
            13 -> "定位失败，请检查手机设置"
            else -> "未知错误"
        }
    }

    private val safeActivity: Activity?
        get() = activityRef?.get()
    private val safeContext: Context?
        get() = safeActivity?.applicationContext

    interface LocationCallback {
        fun onLocationSuccess(location: AMapLocation?, isWeatherButton: Boolean)
        fun onLocationFailure(errorCode: Int, errorInfo: String?)
    }

    companion object {
        @Volatile
        var instance: LocationManager? = null
            get() {
                if (field == null) {
                    synchronized(LocationManager::class.java) {
                        if (field == null) {
                            field = LocationManager()
                        }
                    }
                }
                return field
            }
            private set
        private const val REQUEST_CODE_PERMISSION = 1001
        private val REQUIRED_PERMISSIONS = arrayOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION
        )
        // 你的高德Web服务Key（必须申请Web服务Key，和Android Key不是同一个）
        private const val GAODE_WEB_API_KEY = "80a79d5ca70a9f91f9881e74c335bf0b"
    }

    /**
     * 通过高德逆地理API获取详细地址信息（兜底逻辑）
     * 挂起函数，需在协程中调用
     */
    private suspend fun getAddressByLatLng(lat: Double, lon: Double): String = withContext(Dispatchers.IO) {
        try {
            val url =
                "https://restapi.amap.com/v3/geocode/regeo?location=$lon,$lat&key=$GAODE_WEB_API_KEY"
            Log.d("LocationFallbackUtil", "请求高德逆地理API: $url")
            val request = Request.Builder().url(url).get().build()
            val client = OkHttpClient()
            client.newCall(request).execute().use { response ->
                val body = response.body?.string()
                Log.d("LocationFallbackUtil", "高德逆地理API响应: $body")
                if (response.isSuccessful && body != null) {
                    val obj = JSONObject(body)
                    val status = obj.optString("status")
                    if (status == "1") {
                        val regeocode = obj.optJSONObject("regeocode")
                        // 优先用 formatted_address
                        val formatted = regeocode?.optString("formatted_address")
                        if (!formatted.isNullOrEmpty()) {
                            Log.d("LocationFallbackUtil", "逆地理结果: $formatted")
                            return@withContext formatted
                        }
                        // 或拼接 province/city/district/street
                        val addressComponent = regeocode?.optJSONObject("addressComponent")
                        val province = addressComponent?.optString("province") ?: ""
                        val city = addressComponent?.optString("city") ?: ""
                        val district = addressComponent?.optString("district") ?: ""
                        val township = addressComponent?.optString("township") ?: ""
                        val street = addressComponent?.optJSONArray("streetNumber")?.optJSONObject(0)?.optString("street") ?: ""
                        val fallback = listOf(province, city, district, township, street)
                            .filter { it.isNotEmpty() }
                            .joinToString(separator = "")
                        if (fallback.isNotEmpty()) {
                            Log.d("LocationFallbackUtil", "逆地理拼接结果: $fallback")
                            return@withContext fallback
                        } else {
                            Log.e("LocationFallbackUtil", "逆地理API没有返回有效地址信息")
                            return@withContext "未知位置"
                        }
                    } else {
                        Log.e("LocationFallbackUtil", "逆地理API status非1，message: ${obj.optString("info")}")
                    }
                } else {
                    Log.e("LocationFallbackUtil", "逆地理API请求失败: code=${response.code}, body=$body")
                }
            }
        } catch (e: Exception) {
            Log.e("LocationFallbackUtil", "逆地理API异常", e)
        }
        "未知位置"
    }

    /**
     * 仅用于 SplashActivity 预加载：同步拿一次缓存/上一次定位
     * 不启动真正定位，耗时 < 50 ms
     */
    suspend fun preloadCached(context: Context): AMapLocation? = withContext(Dispatchers.IO) {
        val trace = Firebase.performance.newTrace("location_data_loading")
        trace.start()
        if (cachedLocation != null) return@withContext cachedLocation

        // 1. 先合规，再 new AMapLocationClient
        AMapLocationClient.updatePrivacyShow(context, true, true)
        AMapLocationClient.updatePrivacyAgree(context, true)

        val client = AMapLocationClient(context)
        val option = AMapLocationClientOption().apply {
            isOnceLocation = true
            isLocationCacheEnable = true
        }
        client.setLocationOption(option)

        // 加载数据的代码
        trace.stop()
        try {
            val deferred = CompletableDeferred<AMapLocation?>()
            client.setLocationListener { loc ->
                cachedLocation = loc
                deferred.complete(loc)
                client.stopLocation()
                client.onDestroy()
            }
            client.startLocation()
            deferred.await()

        } catch (e: Exception) {
            null
        }

    }
}