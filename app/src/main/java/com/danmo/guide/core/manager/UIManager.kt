package com.danmo.guide.core.manager

import android.app.Activity
import android.view.View
import android.widget.Toast
import androidx.lifecycle.LifecycleCoroutineScope
import com.danmo.guide.databinding.ActivityMainBinding
import com.danmo.guide.feature.feedback.DetectionProcessor
import com.danmo.guide.feature.performancemonitor.PerformanceMonitor
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
            binding.tvFps.text = "FPS:${metrics.fps}"
            binding.tvCpu.text = "CPU:${metrics.cpuApp}%"
            binding.tvMem.text = "MEM:当前:${metrics.memUsedMB}/极限:${metrics.memMaxMB}MB"
            binding.tvTemp.text = "BAT:${metrics.batteryTemp}°C"
            binding.tvInference.text = "AI推理延迟:${metrics.tfliteMs}ms"
            binding.tvGpu.text = "GPU渲染时长:${metrics.gpuFrameMs}ms"
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
}

