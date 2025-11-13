package com.danmo.guide.core.manager

import android.app.Activity
import android.util.Size
import android.view.View
import android.widget.Toast
import androidx.lifecycle.LifecycleCoroutineScope
import com.danmo.guide.core.device.DeviceCapability
import com.danmo.guide.databinding.ActivityMainBinding
import com.danmo.guide.feature.detection.ObjectDetectorCache
import com.danmo.guide.feature.feedback.DetectionProcessor
import com.danmo.guide.feature.performancemonitor.PerformanceMonitor
import com.danmo.guide.feature.powermode.PowerMode
import com.danmo.guide.ui.components.OverlayView
import kotlinx.coroutines.launch
import org.tensorflow.lite.task.vision.detector.Detection

/**
 * UI 管理模块
 * 统一处理 UI 更新和状态显示
 */
class UIManager(
    private val activity: Activity,
    private val binding: ActivityMainBinding,
    private val overlayView: OverlayView,
    private val lifecycleScope: LifecycleCoroutineScope
) {

    /**
     * 更新性能指标显示
     */
    fun updatePerformanceMetrics(metrics: PerformanceMonitor.Metrics) {
        activity.runOnUiThread {
            binding.tvFps.text = "${metrics.fps}"
            binding.tvCpu.text = "${metrics.cpuApp}%"
            binding.tvMem.text = "${metrics.memUsedMB}MB"
            binding.tvTemp.text = "${String.format("%.1f", metrics.batteryTemp)}°C"
            binding.tvInference.text = "${metrics.tfliteMs}ms"
            binding.tvGpu.text = "${metrics.gpuFrameMs}ms"
        }
    }

    /**
     * 更新检测结果状态
     */
    fun updateDetectionStatus(results: List<Detection>) {
        activity.runOnUiThread {
            val names = results.joinToString {
                DetectionProcessor.getInstance(activity)
                    .getChineseLabel(it.categories.maxByOrNull { c -> c.score }?.label ?: "unknown")
            }
            binding.statusText.text = if (names.isEmpty()) {
                activity.getString(com.danmo.guide.R.string.no_objects_detected)
            } else {
                activity.getString(com.danmo.guide.R.string.detected_objects, names)
            }
        }
    }

    /**
     * 更新覆盖层视图
     */
    fun updateOverlayView(results: List<Detection>, rotation: Int) {
        activity.runOnUiThread {
            overlayView.updateDetections(results, rotation)
        }
    }

    /**
     * 显示 Toast 消息
     */
    fun showToast(message: String) {
        activity.runOnUiThread {
            Toast.makeText(activity, message, Toast.LENGTH_SHORT).show()
        }
    }

    /**
     * 设置状态文本
     */
    fun setStatusText(text: String) {
        activity.runOnUiThread {
            binding.statusText.text = text
        }
    }

    /**
     * 切换摄像头视图可见性
     */
    fun switchCameraView(isBuiltIn: Boolean) {
        activity.runOnUiThread {
            if (isBuiltIn) {
                binding.previewView.visibility = View.VISIBLE
                binding.streamView.visibility = View.GONE
                binding.timerText.visibility = View.GONE
            } else {
                binding.previewView.visibility = View.GONE
                binding.streamView.visibility = View.VISIBLE
                binding.timerText.visibility = View.VISIBLE
            }
        }
    }

    /**
     * 更新模式配置信息显示
     */
    fun updateModeConfig(
        powerMode: PowerMode,
        deviceInfo: DeviceCapability.DeviceInfo?,
        currentResolution: Size?,
        targetFps: Int
    ) {
        activity.runOnUiThread {
            // 模式名称（中文）
            val modeText = when (powerMode) {
                PowerMode.LOW_POWER -> "省电"
                PowerMode.BALANCED -> "平衡"
                PowerMode.HIGH_ACCURACY -> "高性能"
            }
            binding.tvMode.text = modeText

            // 设备等级（中文）
            val tierText = deviceInfo?.let {
                when (it.tier) {
                    DeviceCapability.PerformanceTier.LOW -> "低端"
                    DeviceCapability.PerformanceTier.MEDIUM -> "中端"
                    DeviceCapability.PerformanceTier.HIGH -> "高端"
                }
            } ?: "未知"
            binding.tvDeviceTier.text = tierText

            // 当前使用的delegate类型
            val delegateText = deviceInfo?.let { info ->
                val mode = powerMode
                val delegateType = when (mode) {
                    PowerMode.LOW_POWER -> {
                        if (info.hasNnapi) DeviceCapability.DelegateType.NNAPI else DeviceCapability.DelegateType.CPU
                    }
                    PowerMode.BALANCED -> {
                        DeviceCapability.getRecommendedDelegate(info.tier, info.hasGpu, info.hasNnapi)
                    }
                    PowerMode.HIGH_ACCURACY -> {
                        if (info.hasGpu) DeviceCapability.DelegateType.GPU
                        else if (info.hasNnapi) DeviceCapability.DelegateType.NNAPI
                        else DeviceCapability.DelegateType.CPU
                    }
                }
                when (delegateType) {
                    DeviceCapability.DelegateType.CPU -> "CPU"
                    DeviceCapability.DelegateType.GPU -> "GPU"
                    DeviceCapability.DelegateType.NNAPI -> "NNAPI"
                }
            } ?: "CPU"
            binding.tvDelegate.text = delegateText

            // 线程数
            val threads = deviceInfo?.let { info ->
                when (powerMode) {
                    PowerMode.LOW_POWER -> 1
                    PowerMode.BALANCED -> info.recommendedThreads.coerceAtMost(2)
                    PowerMode.HIGH_ACCURACY -> info.recommendedThreads
                }
            } ?: 2
            binding.tvThreads.text = "$threads"

            // 分辨率
            val resolutionText = currentResolution?.let { "${it.width}×${it.height}" } ?: "--"
            binding.tvResolution.text = resolutionText

            // 目标帧率
            binding.tvTargetFps.text = "${targetFps}fps"
        }
    }
}

