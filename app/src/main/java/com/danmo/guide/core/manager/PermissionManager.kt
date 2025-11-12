package com.danmo.guide.core.manager

import android.Manifest
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

/**
 * 权限管理模块
 * 统一处理所有权限请求和检查
 */
class PermissionManager(private val activity: ComponentActivity) {

    interface PermissionCallback {
        fun onPermissionGranted(permission: String)
        fun onPermissionDenied(permission: String)
    }

    private var cameraCallback: (() -> Unit)? = null
    private var locationCallback: (() -> Unit)? = null
    private var audioCallback: (() -> Unit)? = null
    private var callCallback: (() -> Unit)? = null

    // 摄像头权限请求
    private val requestCameraPermission =
        activity.registerForActivityResult(ActivityResultContracts.RequestPermission()) { isGranted ->
            if (isGranted) {
                cameraCallback?.invoke()
            } else {
                showPermissionDeniedDialog("需要摄像头权限才能使用视觉功能")
            }
        }

    // 位置权限请求
    private val requestLocationPermission =
        activity.registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { permissions ->
            val allGranted = permissions.all { it.value }
            if (allGranted) {
                locationCallback?.invoke()
            } else {
                showPermissionDeniedDialog("需要位置权限才能使用定位功能")
            }
        }

    // 录音权限请求
    private val requestAudioPermission =
        activity.registerForActivityResult(ActivityResultContracts.RequestPermission()) { isGranted ->
            if (isGranted) {
                audioCallback?.invoke()
            } else {
                showPermissionDeniedDialog("需要麦克风权限才能使用语音功能")
            }
        }

    // 电话权限请求
    private val requestCallPermission =
        activity.registerForActivityResult(ActivityResultContracts.RequestPermission()) { isGranted ->
            if (isGranted) {
                callCallback?.invoke()
            } else {
                showPermissionDeniedDialog("需要电话权限进行紧急呼叫")
            }
        }

    /**
     * 检查摄像头权限
     */
    fun checkCameraPermission(onGranted: () -> Unit) {
        cameraCallback = onGranted
        when {
            ContextCompat.checkSelfPermission(
                activity,
                Manifest.permission.CAMERA
            ) == PackageManager.PERMISSION_GRANTED -> {
                onGranted()
            }
            else -> {
                requestCameraPermission.launch(Manifest.permission.CAMERA)
            }
        }
    }

    /**
     * 检查位置权限
     */
    fun checkLocationPermission(onGranted: () -> Unit): Boolean {
        locationCallback = onGranted
        val hasPermission = ContextCompat.checkSelfPermission(
            activity,
            Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED

        if (hasPermission) {
            onGranted()
            return true
        } else {
            if (ActivityCompat.shouldShowRequestPermissionRationale(
                    activity,
                    Manifest.permission.ACCESS_FINE_LOCATION
                )
            ) {
                showLocationPermissionRationale {
                    requestLocationPermission.launch(
                        arrayOf(
                            Manifest.permission.ACCESS_FINE_LOCATION,
                            Manifest.permission.ACCESS_COARSE_LOCATION
                        )
                    )
                }
            } else {
                requestLocationPermission.launch(
                    arrayOf(
                        Manifest.permission.ACCESS_FINE_LOCATION,
                        Manifest.permission.ACCESS_COARSE_LOCATION
                    )
                )
            }
            return false
        }
    }

    /**
     * 检查录音权限
     */
    fun checkAudioPermission(onGranted: () -> Unit) {
        audioCallback = onGranted
        when {
            ContextCompat.checkSelfPermission(
                activity,
                Manifest.permission.RECORD_AUDIO
            ) == PackageManager.PERMISSION_GRANTED -> {
                onGranted()
            }
            ActivityCompat.shouldShowRequestPermissionRationale(
                activity,
                Manifest.permission.RECORD_AUDIO
            ) -> {
                showAudioPermissionRationale {
                    requestAudioPermission.launch(Manifest.permission.RECORD_AUDIO)
                }
            }
            else -> {
                requestAudioPermission.launch(Manifest.permission.RECORD_AUDIO)
            }
        }
    }

    /**
     * 检查电话权限
     */
    fun checkCallPermission(onGranted: () -> Unit): Boolean {
        callCallback = onGranted
        val hasPermission = ContextCompat.checkSelfPermission(
            activity,
            Manifest.permission.CALL_PHONE
        ) == PackageManager.PERMISSION_GRANTED

        if (hasPermission) {
            onGranted()
            return true
        } else {
            if (ActivityCompat.shouldShowRequestPermissionRationale(
                    activity,
                    Manifest.permission.CALL_PHONE
                )
            ) {
                showCallPermissionRationale {
                    requestCallPermission.launch(Manifest.permission.CALL_PHONE)
                }
            } else {
                requestCallPermission.launch(Manifest.permission.CALL_PHONE)
            }
            return false
        }
    }

    private fun showPermissionDeniedDialog(message: String) {
        AlertDialog.Builder(activity)
            .setTitle("权限被拒绝")
            .setMessage(message)
            .setPositiveButton("确定", null)
            .show()
    }

    private fun showLocationPermissionRationale(onRequest: () -> Unit) {
        AlertDialog.Builder(activity)
            .setTitle("需要位置权限")
            .setMessage("定位功能需要访问位置信息，请允许权限后重试")
            .setPositiveButton("去设置") { _, _ ->
                openAppSettings()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun showAudioPermissionRationale(onRequest: () -> Unit) {
        AlertDialog.Builder(activity)
            .setTitle("需要麦克风权限")
            .setMessage("语音功能需要访问麦克风，请允许权限后重试")
            .setPositiveButton("去设置") { _, _ ->
                openAppSettings()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun showCallPermissionRationale(onRequest: () -> Unit) {
        AlertDialog.Builder(activity)
            .setTitle("需要电话权限")
            .setMessage("紧急呼叫功能需要电话权限，请允许权限后重试")
            .setPositiveButton("去设置") { _, _ ->
                openAppSettings()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun openAppSettings() {
        Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
            data = Uri.fromParts("package", activity.packageName, null)
            activity.startActivity(this)
        }
    }
}

