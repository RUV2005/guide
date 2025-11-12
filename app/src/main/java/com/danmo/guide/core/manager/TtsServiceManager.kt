package com.danmo.guide.core.manager

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import com.danmo.guide.feature.fall.FallDetector
import com.danmo.guide.core.service.TtsService

/**
 * TTS 服务管理模块
 * 统一处理 TTS 服务的绑定、解绑和播报管理
 */
class TtsServiceManager(
    private val context: Context,
    private val fallDetector: FallDetector,
    private val onServiceConnected: (TtsService) -> Unit
) {
    var ttsService: TtsService? = null
        private set

    private var isTtsBound = false
    private var hasOutdoorModeAnnounced = false

    private val announcementHandler = Handler(Looper.getMainLooper())
    private val announcementRunnable = Runnable {
        announceOutdoorMode()
    }

    private val ttsConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val binder = service as TtsService.TtsBinder
            ttsService = binder.getService()
            Log.d("TtsServiceManager", "TTS服务已连接")
            fallDetector.ttsService = ttsService

            // 确保在服务连接后播报
            ensureOutdoorModeAnnouncement()

            // 服务连接后处理可能存在的队列
            ttsService?.processNextInQueue()

            // 通知外部服务已连接
            ttsService?.let { onServiceConnected(it) }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            ttsService = null
            Log.d("TtsServiceManager", "TTS服务已断开")
        }
    }

    /**
     * 绑定 TTS 服务
     */
    fun bindService() {
        Intent(context, TtsService::class.java).also { intent ->
            context.bindService(intent, ttsConnection, Context.BIND_AUTO_CREATE)
            isTtsBound = true
        }
    }

    /**
     * 解绑 TTS 服务
     */
    fun unbindService() {
        if (isTtsBound) {
            context.unbindService(ttsConnection)
            isTtsBound = false
        }
    }

    /**
     * 确保户外模式播报
     */
    fun ensureOutdoorModeAnnouncement() {
        announcementHandler.removeCallbacks(announcementRunnable)
        announcementHandler.postDelayed(announcementRunnable, 0)
    }

    /**
     * 播报户外模式
     */
    private fun announceOutdoorMode() {
        if (!hasOutdoorModeAnnounced && isTtsBound && ttsService != null) {
            ttsService?.speak("当前为户外模式", true)
            hasOutdoorModeAnnounced = true
            Log.d("TtsServiceManager", "户外模式播报完成")
        }
    }

    /**
     * 清理资源
     */
    fun cleanup() {
        announcementHandler.removeCallbacks(announcementRunnable)
        unbindService()
    }
}

