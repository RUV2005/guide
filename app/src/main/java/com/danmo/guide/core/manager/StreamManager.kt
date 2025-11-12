package com.danmo.guide.core.manager

import android.graphics.BitmapFactory
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.util.Log
import androidx.lifecycle.LifecycleCoroutineScope
import com.danmo.guide.databinding.ActivityMainBinding
import com.danmo.guide.feature.detection.ObjectDetectorHelper
import com.danmo.guide.feature.feedback.DetectionProcessor
import com.danmo.guide.feature.performancemonitor.PerformanceMonitor
import com.danmo.guide.ui.components.OverlayView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.io.InputStream

/**
 * 视频流管理模块
 * 处理外置摄像头的 MJPEG 视频流
 */
class StreamManager(
    private val activity: android.app.Activity,
    private val binding: ActivityMainBinding,
    private val lifecycleScope: LifecycleCoroutineScope,
    private val overlayView: OverlayView,
    private val objectDetectorHelper: ObjectDetectorHelper,
    private val perfMonitor: PerformanceMonitor,
    private val onDetectionResults: (List<org.tensorflow.lite.task.vision.detector.Detection>) -> Unit
) {
    private enum class ConnectionState { CONNECTING, CONNECTED, DISCONNECTED }

    private var connectionState = ConnectionState.DISCONNECTED
    private var connectionStartTime = 0L
    private var isStreaming = false
    private val client = OkHttpClient()
    private lateinit var boundary: String
    private val tag = "MjpegStream"
    private var leftoverData = ByteArray(0)
    private var streamingJob: Job? = null
    private var timerJob: Job? = null
    private val reconnectDelay = 5000L
    private val maxRetries = 5
    private var currentDetectJob: Job? = null

    companion object {
        private const val STREAM_URL = "http://192.168.4.1:81/stream"
        private val renderHandler = Handler(HandlerThread("RenderThread").apply { start() }.looper)
    }

    /**
     * 启动视频流
     */
    fun startStream() {
        if (isStreaming) return
        isStreaming = true

        perfMonitor.reset()

        streamingJob = lifecycleScope.launch(Dispatchers.IO) {
            var retryCount = 0
            while (isStreaming) {
                try {
                    updateConnectionState(ConnectionState.CONNECTING)
                    retryCount = 0
                    val request = Request.Builder().url(STREAM_URL).build()

                    client.newCall(request).execute().use { response ->
                        if (!response.isSuccessful) throw IOException("Unexpected code ${response.code}")
                        updateConnectionState(ConnectionState.CONNECTED)

                        leftoverData = ByteArray(0)
                        val contentType = response.header("Content-Type") ?: ""
                        boundary = contentType.split("boundary=").last().trim()

                        response.body?.byteStream()?.let { stream ->
                            parseMjpegStream(stream)
                        }
                    }
                } catch (e: Exception) {
                    Log.e(tag, "Stream error: ${e.message}")
                    if (++retryCount > maxRetries) {
                        updateConnectionState(ConnectionState.DISCONNECTED)
                        showToast("重连次数已达上限，停止尝试")
                        isStreaming = false
                        return@launch
                    }
                    if (isStreaming) {
                        showToast("正在重连... $retryCount/$maxRetries")
                        delay(reconnectDelay)
                    }
                }
            }
        }
    }

    /**
     * 停止视频流
     */
    fun stopStream() {
        isStreaming = false
        streamingJob?.cancel()
        updateConnectionState(ConnectionState.DISCONNECTED)
        perfMonitor.reset()
    }

    private fun parseMjpegStream(stream: InputStream) {
        val buffer = ByteArray(4096)
        leftoverData = ByteArray(0)
        while (isStreaming && !Thread.currentThread().isInterrupted) {
            val len = stream.read(buffer)
            if (len == -1) break
            val data = leftoverData + buffer.copyOfRange(0, len)
            leftoverData = processFrameBuffer(data)
        }
    }

    private fun processFrameBuffer(data: ByteArray): ByteArray {
        var pos = 0
        while (true) {
            val boundaryIdx = findBoundary(data, pos)
            if (boundaryIdx == -1) return data.copyOfRange(pos, data.size)

            val section = data.copyOfRange(boundaryIdx, data.size)
            val headerEnd = findHeaderEnd(section)
            if (headerEnd == -1) return section

            val headers = String(section, 0, headerEnd)
            val contentLen = extractContentLength(headers)
            if (contentLen <= 0) return section

            val imgStart = headerEnd + 4
            val imgEnd = imgStart + contentLen
            if (imgEnd > section.size) return section

            val imgData = section.copyOfRange(imgStart, imgEnd)
            if (isValidJpeg(imgData)) renderFrame(imgData)

            pos = boundaryIdx + imgEnd
        }
    }

    private fun renderFrame(imgData: ByteArray) {
        renderHandler.post {
            perfMonitor.resetFrameCounters()

            val bmp = BitmapFactory.decodeByteArray(imgData, 0, imgData.size) ?: return@post
            activity.runOnUiThread {
                binding.streamView.setImageBitmap(bmp)
            }

            if (currentDetectJob?.isActive == true) return@post

            perfMonitor.clearTfliteWindow()

            DetectionProcessor.getInstance(activity)
                .updateImageDimensions(bmp.width, bmp.height)

            currentDetectJob = lifecycleScope.launch(Dispatchers.IO) {
                val t0 = SystemClock.elapsedRealtime()
                val results = withContext(objectDetectorHelper.getGpuThread()) {
                    objectDetectorHelper.detect(bmp, 0)
                }
                val tfliteCost = SystemClock.elapsedRealtime() - t0

                perfMonitor.recordTfliteNow(tfliteCost)

                withContext(Dispatchers.Main) {
                    overlayView.setModelInputSize(bmp.width, bmp.height)
                    onDetectionResults(results)
                }
            }
        }
    }

    private fun updateConnectionState(newState: ConnectionState) {
        if (connectionState == newState) return
        connectionState = newState

        activity.runOnUiThread {
            when (newState) {
                ConnectionState.CONNECTING -> {
                    binding.CamStatusText.text = "正在连接..."
                }
                ConnectionState.CONNECTED -> {
                    binding.CamStatusText.text = "已连接"
                    connectionStartTime = System.currentTimeMillis()
                    startTimer()
                }
                ConnectionState.DISCONNECTED -> {
                    binding.CamStatusText.text = "未连接"
                    timerJob?.cancel()
                }
            }
        }
    }

    private fun startTimer() {
        timerJob?.cancel()
        timerJob = lifecycleScope.launch(Dispatchers.Main) {
            while (isStreaming && connectionState == ConnectionState.CONNECTED) {
                val elapsed = System.currentTimeMillis() - connectionStartTime
                val min = elapsed / 1000 / 60
                val sec = elapsed / 1000 % 60
                binding.timerText.text = activity.getString(com.danmo.guide.R.string.timer_format, min, sec)
                delay(1000)
            }
        }
    }

    private fun findBoundary(data: ByteArray, startIndex: Int): Int {
        val boundaryPattern = "--$boundary".toByteArray()
        for (i in startIndex..data.size - boundaryPattern.size) {
            if (data.copyOfRange(i, i + boundaryPattern.size)
                    .contentEquals(boundaryPattern)
            ) return i
        }
        return -1
    }

    private fun findHeaderEnd(data: ByteArray): Int {
        for (i in 0..data.size - 4) {
            if (data[i] == '\r'.code.toByte() &&
                data[i + 1] == '\n'.code.toByte() &&
                data[i + 2] == '\r'.code.toByte() &&
                data[i + 3] == '\n'.code.toByte()
            ) return i
        }
        return -1
    }

    private fun extractContentLength(headers: String): Int {
        return Regex("Content-Length:\\s*(\\d+)", RegexOption.IGNORE_CASE)
            .find(headers)
            ?.groupValues
            ?.get(1)
            ?.toInt() ?: -1
    }

    private fun isValidJpeg(data: ByteArray): Boolean {
        return data.size >= 2 &&
                data[0] == 0xFF.toByte() &&
                data[1] == 0xD8.toByte() &&
                data.last() == 0xD9.toByte()
    }

    private fun showToast(message: String) {
        activity.runOnUiThread {
            android.widget.Toast.makeText(activity, message, android.widget.Toast.LENGTH_SHORT).show()
        }
    }
}

