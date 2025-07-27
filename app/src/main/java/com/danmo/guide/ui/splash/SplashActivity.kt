package com.danmo.guide.ui.splash

import android.annotation.SuppressLint
import android.content.Intent
import android.os.Bundle
import android.util.Log
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityOptionsCompat
import androidx.lifecycle.lifecycleScope
import com.amap.api.location.AMapLocationClient
import com.danmo.guide.R
import com.danmo.guide.feature.detection.ObjectDetectorHelper
import com.danmo.guide.feature.location.LocationManager
import com.danmo.guide.feature.vosk.VoskRecognizerManager
import com.danmo.guide.ui.main.MainActivity
import com.google.firebase.Firebase
import com.google.firebase.perf.performance
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.system.measureTimeMillis

@SuppressLint("CustomSplashScreen")
class SplashActivity : AppCompatActivity() {

    private companion object {
        private const val TIMEOUT = 2000L // 兜底超时 2 s，防止异常卡死
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_splash)


        // 插桩：监控应用启动时间
        val appStartTrace = Firebase.performance.newTrace("app_start")
        appStartTrace.start()

        // 1. 后台线程真正做初始化
        val initJob = lifecycleScope.launch {
            val cost = measureTimeMillis { doHeavyInit() }
            Log.d("SplashActivity", "初始化耗时: $cost ms")
            navigateToMain()
        }

        // 2. 兜底：最多 2 s
        lifecycleScope.launch {
            delay(TIMEOUT)
            if (initJob.isActive) {
                initJob.cancel()
                navigateToMain()
            }
        }

        // 结束插桩
        appStartTrace.stop()
    }

    private suspend fun doHeavyInit() = withContext(Dispatchers.IO) {
        // 高德定位 SDK 隐私合规初始化
        AMapLocationClient.updatePrivacyShow(this@SplashActivity, true, true)
        AMapLocationClient.updatePrivacyAgree(this@SplashActivity, true)

        listOf(
            async { ObjectDetectorHelper.preload(this@SplashActivity) },
            async { VoskRecognizerManager.initWithDownload(this@SplashActivity) },
            async { LocationManager.instance?.preloadCached(this@SplashActivity) }
        ).awaitAll()
    }

    private fun navigateToMain() {
        if (isFinishing) return
        val options = ActivityOptionsCompat.makeCustomAnimation(
            this,
            android.R.anim.fade_in,
            android.R.anim.fade_out
        )
        startActivity(
            Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
            },
            options.toBundle()
        )
        finish()
    }
}