package com.danmo.guide.feature.location

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.app.ActivityCompat
import com.amap.api.location.AMapLocation
import com.amap.api.location.AMapLocationClient
import com.amap.api.location.AMapLocationClientOption
import com.amap.api.location.AMapLocationListener
import java.lang.ref.WeakReference
import kotlin.concurrent.Volatile

class LocationManager private constructor() : AMapLocationListener {
    private var activityRef: WeakReference<Activity>? = null
    private var locationClient: AMapLocationClient? = null
    private var locationOption: AMapLocationClientOption? = null
    var callback: LocationCallback? = null

    // endregion
    // region 公开方法
    fun initialize(activity: Activity) {
        this.activityRef = WeakReference(activity)
        initLocationSettings()
    }

    fun startLocation(callback: LocationCallback) {
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

    // endregion
    // region 定位实现
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

    // endregion
    // region 权限处理
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

        if (!needRequest.isEmpty()) {
            ActivityCompat.requestPermissions(
                activity,
                needRequest.toTypedArray<String>(),
                REQUEST_CODE_PERMISSION
            )
            return false
        }
        return true
    }

    fun handlePermissionsResult(
        requestCode: Int,
        grantResults: IntArray
    ) {
        if (requestCode == REQUEST_CODE_PERMISSION) {
            var allGranted = true
            for (result in grantResults) {
                if (result != PackageManager.PERMISSION_GRANTED) {
                    allGranted = false
                    break
                }
            }
            if (allGranted) {
                startLocationService()
            } else {
                notifyError(12, "缺少必要的定位权限")
            }
        }
    }

    // endregion
    // region 回调处理
    override fun onLocationChanged(aMapLocation: AMapLocation?) {
        if (callback == null) return

        if (aMapLocation != null) {
            if (aMapLocation.getErrorCode() === 0) {
                notifySuccess(aMapLocation)
            } else {
                notifyError(
                    aMapLocation.getErrorCode(),
                    getErrorInfo(aMapLocation.getErrorCode())
                )
            }
        } else {
            notifyError(-3, "定位结果为空")
        }
    }

    private fun notifySuccess(location: AMapLocation?) {
        val activity = safeActivity
        activity?.runOnUiThread {
            if (callback != null) {
                callback!!.onLocationSuccess(location)
            }
        }
    }

    private fun notifyError(code: Int, msg: String) {
        val activity = safeActivity
        activity?.runOnUiThread {
            if (callback != null) {
                callback!!.onLocationFailure(code, msg)
            }
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
        // endregion
        get() = if ((activityRef != null)) activityRef!!.get() else null

    private val safeContext: Context?
        get() {
            val activity = safeActivity
            return if ((activity != null)) activity.applicationContext else null
        }

    // endregion
    // region 回调接口
    interface LocationCallback {
        fun onLocationSuccess(location: AMapLocation?)
        fun onLocationFailure(errorCode: Int, errorInfo: String?)
    } // endregion

    companion object {
        // region 单例模式
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

        // endregion
        // region 配置参数
        private const val REQUEST_CODE_PERMISSION = 1001
        private val REQUIRED_PERMISSIONS = arrayOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION,
            Manifest.permission.WRITE_EXTERNAL_STORAGE
        )
    }
}