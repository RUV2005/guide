package com.danmo.guide.core.manager

import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.lifecycle.LifecycleCoroutineScope
import com.danmo.guide.R
import com.danmo.guide.databinding.ActivityMainBinding
import com.danmo.guide.core.service.TtsService
import com.danmo.guide.feature.vosk.VoskRecognizerManager
import com.danmo.guide.ui.read.ReadOnlineActivity
import com.danmo.guide.ui.room.RoomActivity
import com.danmo.guide.ui.settings.SettingsActivity
import com.danmo.guide.ui.voicecall.VoiceCallActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

/**
 * 语音命令处理模块
 * 处理所有语音识别和命令执行逻辑
 */
class VoiceCommandHandler(
    private val activity: android.app.Activity,
    private val binding: ActivityMainBinding,
    private val lifecycleScope: LifecycleCoroutineScope,
    private val ttsService: TtsService?,
    private val onEmergencyCall: () -> Unit,
    private val onWeatherCommand: () -> Unit,
    private val onLocationCommand: () -> Unit
) {
    private var isRecognitionStarting = false
    private var isDetectionActive = true

    /**
     * 启动语音识别
     */
    fun startVoiceRecognition() {
        if (isRecognitionStarting || ttsService?.isSpeaking == true) return
        isRecognitionStarting = true

        lifecycleScope.launch(Dispatchers.Main) {
            try {
                VoskRecognizerManager.stopListening()
                VoskRecognizerManager.destroy()
                delay(300)

                val ok = withContext(Dispatchers.IO) {
                    VoskRecognizerManager.initWithDownload(activity)
                }

                if (!ok) {
                    showToast("语音模型下载失败")
                    return@launch
                }

                VoskRecognizerManager.startListening { result ->
                    activity.runOnUiThread {
                        binding.statusText.text = activity.getString(R.string.detected_objects, result)
                        processVoiceCommand(result)
                    }
                }

                binding.statusText.text = activity.getString(R.string.detected_objects, "正在聆听")
                ttsService?.speak(activity.getString(R.string.speak_enter_read_mode))
            } catch (e: Exception) {
                Log.e("Speech", "startVoiceRecognition", e)
                showToast("语音识别启动失败")
            } finally {
                isRecognitionStarting = false
            }
        }
    }

    /**
     * 处理语音命令
     */
    fun processVoiceCommand(command: String) {
        val trimmed = command.lowercase(Locale.getDefault())
            .replace(" ", "")
        Log.d("COMMAND", "处理后指令：'$trimmed'")

        if (trimmed.isBlank()) return

        when {
            trimmed.contains("我需要帮助") ||
            trimmed.contains("救命") ||
            trimmed.contains("sos") -> onEmergencyCall()

            trimmed.contains("天气") -> onWeatherCommand()

            trimmed.contains("位置") ||
            trimmed.contains("定位") -> onLocationCommand()

            trimmed.contains("语音通话") ||
            trimmed.contains("聊天") -> startVoiceCallActivity()

            trimmed.contains("场景描述") ||
            trimmed.contains("室内模式") -> startRoomActivity()

            trimmed.contains("阅读模式") -> startReadOnlineActivity()

            trimmed.contains("设置") -> openSettings()

            trimmed.contains("退出") -> activity.finish()

            trimmed.contains("开始检测") -> startDetection()

            trimmed.contains("暂停检测") -> pauseDetection()

            trimmed.contains("检测状态") -> {
                val status = if (isDetectionActive) "正在运行" else "已暂停"
                ttsService?.speak("障碍物检测$status")
            }

            trimmed.contains("帮助") -> showVoiceHelp()

            else -> ttsService?.speak("未识别指令，请说帮助查看可用指令")
        }
    }

    private fun startDetection() {
        isDetectionActive = true
        activity.runOnUiThread {
            binding.overlayView.visibility = android.view.View.VISIBLE
        }
        ttsService?.speak("已开启障碍物检测，请注意周围环境")

        binding.overlayView.animate()
            .alpha(0.8f)
            .setDuration(500)
            .withEndAction { binding.overlayView.alpha = 1f }
    }

    private fun pauseDetection() {
        isDetectionActive = false
        activity.runOnUiThread {
            binding.overlayView.visibility = android.view.View.INVISIBLE
        }
        ttsService?.speak("已暂停障碍物检测")

        binding.previewView.animate()
            .alpha(0.6f)
            .setDuration(500)
            .withEndAction { binding.previewView.alpha = 1f }
    }

    private fun startVoiceCallActivity() {
        activity.startActivity(Intent(activity, VoiceCallActivity::class.java))
    }

    private fun startRoomActivity() {
        Handler(Looper.getMainLooper()).postDelayed({
            activity.startActivity(Intent(activity, RoomActivity::class.java))
        }, 300)
    }

    private fun startReadOnlineActivity() {
        ttsService?.speak(activity.getString(R.string.speak_enter_read_mode))
        Handler(Looper.getMainLooper()).postDelayed({
            activity.startActivity(Intent(activity, ReadOnlineActivity::class.java))
        }, 300)
    }

    private fun openSettings() {
        ttsService?.speak("打开设置界面")
        binding.fabSettings.performClick()
    }

    private fun showVoiceHelp() {
        val helpSegments = listOf(
            "可用语音指令：",
            "· 天气 - 获取当前天气",
            "· 位置 - 获取当前位置",
            "· 设置 - 打开设置界面",
            "· 退出 - 退出应用",
            "· 开始检测 - 启动障碍物检测",
            "· 暂停检测 - 暂停障碍物检测",
            "· 我需要帮助 - 触发紧急求助",
            "· 语音通话 - 启动语音通话功能",
            "· 场景描述 - 切换到室内场景描述模式",
            "· 阅读模式 - 进入在线阅读模式"
        )

        lifecycleScope.launch {
            for (segment in helpSegments) {
                ttsService?.speak(segment)
                delay(1000)
            }
        }
    }

    private fun showToast(message: String) {
        activity.runOnUiThread {
            android.widget.Toast.makeText(activity, message, android.widget.Toast.LENGTH_SHORT).show()
        }
    }
}

