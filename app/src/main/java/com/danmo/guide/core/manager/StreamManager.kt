package com.danmo.guide.core.manager

import android.graphics.Bitmap
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
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.SocketTimeoutException
import java.util.concurrent.Executors

/**
 * 视频流管理模块
 * 处理外置摄像头的 UDP 视频流（ESP32-CAM）
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
    private val tag = "UdpStream"
    private var streamingJob: Job? = null
    private var timerJob: Job? = null
    private val reconnectDelay = 5000L
    private val maxRetries = 5
    private var currentDetectJob: Job? = null
    
    // UDP 相关
    private var udpSocket: DatagramSocket? = null
    private val serverIP = InetAddress.getByName("192.168.4.1")
    private val serverPort = 5000
    private val localPort = 5000
    
    // JPEG 帧重组相关
    private val frameBuffer = mutableListOf<ByteArray>()
    private var lastPacketTime = 0L
    private val frameTimeout = 200L // 200ms 超时，如果超过这个时间没有收到新包，认为一帧结束
    
    // 性能优化：帧率限制和跳过机制
    private var lastFrameTime = 0L
    private val targetFrameInterval = 100L // 目标帧间隔 100ms (约 10 FPS) - 显示帧率
    private var skippedFrames = 0L
    private val maxSkippedFrames = 3 // 最多连续跳过 3 帧，然后必须处理一帧
    
    // 检测频率控制：分离显示帧率和检测帧率
    private var frameCounter = 0
    private val detectionInterval = 2 // 每 2 帧检测一次（约 5 FPS 检测率）
    private var lastDetectionTime = 0L
    private val minDetectionInterval = 200L // 最小检测间隔 200ms（约 5 FPS）
    
    // 图像解码优化：最大尺寸限制
    private val maxDisplayWidth = 1280
    private val maxDisplayHeight = 720
    private val maxDetectionWidth = 640  // 检测时使用更小的尺寸
    private val maxDetectionHeight = 480
    
    // 专用线程池用于图像处理
    private val imageProcessingDispatcher = Executors.newFixedThreadPool(2).asCoroutineDispatcher()
    
    // Bitmap 重用：保存当前显示的 Bitmap 以便回收
    private var currentDisplayBitmap: Bitmap? = null

    companion object {
        private val renderHandler = Handler(HandlerThread("RenderThread").apply { start() }.looper)
        private const val UDP_MAX_SIZE = 1200 // ESP32 发送的最大 UDP 包大小
        private const val UDP_BUFFER_SIZE = 1500 // 接收缓冲区大小
    }

    /**
     * 启动视频流
     */
    fun startStream() {
        if (isStreaming) {
            Log.w(tag, "流已在运行中，忽略重复启动")
            return
        }
        isStreaming = true

        perfMonitor.reset()
        frameBuffer.clear()
        lastPacketTime = 0L

        // 确保 streamView 可见
        activity.runOnUiThread {
            binding.streamView.visibility = android.view.View.VISIBLE
            Log.d(tag, "设置 streamView 可见")
        }

        streamingJob = lifecycleScope.launch(Dispatchers.IO) {
            var retryCount = 0
            while (isStreaming) {
                var socket: DatagramSocket? = null
                try {
                    updateConnectionState(ConnectionState.CONNECTING)
                    retryCount = 0
                    
                    // 创建 UDP Socket
                    socket = DatagramSocket(localPort)
                    socket.soTimeout = 1000 // 1秒超时，用于检查连接状态
                    udpSocket = socket
                    
                    Log.d(tag, "UDP Socket 已创建，本地端口: $localPort，等待来自 ${serverIP.hostAddress}:$serverPort 的数据")
                    updateConnectionState(ConnectionState.CONNECTED)

                    // 接收 UDP 数据包（这个函数会一直运行直到 isStreaming 为 false）
                    receiveUdpPackets(socket)
                    
                } catch (e: SocketTimeoutException) {
                    // 超时是正常的，继续接收（receiveUdpPackets 内部会处理）
                    Log.d(tag, "UDP 接收超时（正常），继续接收")
                    // 不要关闭 socket，继续循环
                } catch (e: java.net.BindException) {
                    Log.e(tag, "UDP 端口绑定失败: ${e.message}", e)
                    socket?.close()
                    udpSocket = null
                    showToast("端口 $localPort 已被占用，请检查其他应用")
                    if (++retryCount > maxRetries) {
                        updateConnectionState(ConnectionState.DISCONNECTED)
                        showToast("重连次数已达上限，停止尝试")
                        isStreaming = false
                        return@launch
                    }
                    delay(reconnectDelay)
                } catch (e: Exception) {
                    Log.e(tag, "UDP Stream error: ${e.message}", e)
                    socket?.close()
                    udpSocket = null
                    
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
                } finally {
                    // 确保 socket 被关闭
                    if (socket != null && socket != udpSocket) {
                        try {
                            socket.close()
                        } catch (e: Exception) {
                            Log.w(tag, "关闭 socket 时出错: ${e.message}")
                        }
                    }
                }
            }
            // 退出循环时关闭 socket
            udpSocket?.close()
            udpSocket = null
            Log.d(tag, "流已停止，UDP Socket 已关闭")
        }
    }
    
    /**
     * 接收 UDP 数据包并重组 JPEG 帧
     */
    private suspend fun receiveUdpPackets(socket: DatagramSocket) {
        val buffer = ByteArray(UDP_BUFFER_SIZE)
        var packetCount = 0L
        var lastReceivedTime = SystemClock.elapsedRealtime()
        val noDataTimeout = 5000L // 5秒没有收到数据，记录警告
        
        Log.d(tag, "开始接收 UDP 数据包，服务器: ${serverIP.hostAddress}:$serverPort，本地端口: $localPort")
        
        while (isStreaming && !Thread.currentThread().isInterrupted) {
            val packet = DatagramPacket(buffer, buffer.size)
            try {
                socket.receive(packet)
                packetCount++
                lastReceivedTime = SystemClock.elapsedRealtime()
                
                // 每收到 10 个包打印一次日志
                if (packetCount % 10 == 0L) {
                    Log.d(tag, "已收到 $packetCount 个 UDP 数据包")
                }
                
                // 检查数据包来源
                val sourceIP = packet.address?.hostAddress
                if (sourceIP == null || !sourceIP.equals(serverIP.hostAddress)) {
                    Log.w(tag, "收到来自未知源的数据包: $sourceIP (期望: ${serverIP.hostAddress})")
                    continue
                }
                
                val currentTime = SystemClock.elapsedRealtime()
                val data = ByteArray(packet.length)
                System.arraycopy(packet.data, packet.offset, data, 0, packet.length)
                
                Log.v(tag, "收到 UDP 包，大小: ${data.size} 字节，来源: $sourceIP")
                
                // 性能优化：通过检测 JPEG 帧头（0xFF 0xD8）判断新帧开始
                val isNewFrame = data.size >= 2 && data[0] == 0xFF.toByte() && data[1] == 0xD8.toByte()
                
                if (isNewFrame) {
                    Log.d(tag, "检测到新帧开始（JPEG 头），当前缓冲区大小: ${frameBuffer.size}")
                }
                
                // 如果是新帧开始，处理之前的帧
                if (isNewFrame && frameBuffer.isNotEmpty()) {
                    Log.d(tag, "新帧开始，处理之前的帧（${frameBuffer.size} 个包）")
                    processCompleteFrame()
                    frameBuffer.clear()
                }
                
                // 检查超时（如果距离上一包时间过长，认为上一帧已结束）
                if (lastPacketTime > 0 && (currentTime - lastPacketTime) > frameTimeout && frameBuffer.isNotEmpty()) {
                    Log.d(tag, "检测到超时，处理待处理的帧（${frameBuffer.size} 个包）")
                    processCompleteFrame()
                    frameBuffer.clear()
                }
                
                // 添加到帧缓冲区
                frameBuffer.add(data)
                lastPacketTime = currentTime
                
                // 检查是否可能是完整帧（最后一个包通常较小，且以 0xFF 0xD9 结尾）
                if (data.size >= 2 && data[data.size - 2] == 0xFF.toByte() && data[data.size - 1] == 0xD9.toByte()) {
                    // 检测到 JPEG 结束标记，立即处理帧
                    Log.d(tag, "检测到 JPEG 结束标记，立即处理帧（${frameBuffer.size} 个包）")
                    processCompleteFrame()
                    frameBuffer.clear()
                    lastPacketTime = 0L
                }
                
            } catch (e: SocketTimeoutException) {
                // 超时是正常的，用于检查连接状态
                val currentTime = SystemClock.elapsedRealtime()
                
                // 检查是否长时间没有收到数据
                if (currentTime - lastReceivedTime > noDataTimeout && packetCount == 0L) {
                    Log.w(tag, "已等待 ${(currentTime - lastReceivedTime) / 1000} 秒，仍未收到任何 UDP 数据包")
                    Log.w(tag, "请检查：1) ESP32 是否正在发送数据 2) WiFi 连接是否正常 3) IP 地址是否正确")
                }
                
                // 处理超时的帧
                if (frameBuffer.isNotEmpty() && lastPacketTime > 0 && 
                    (currentTime - lastPacketTime) > frameTimeout) {
                    Log.d(tag, "Socket 超时，处理待处理的帧（${frameBuffer.size} 个包）")
                    processCompleteFrame()
                    frameBuffer.clear()
                    lastPacketTime = 0L
                }
                // 继续接收
            } catch (e: Exception) {
                Log.e(tag, "接收 UDP 数据包时出错: ${e.message}", e)
                throw e // 重新抛出，让外层处理
            }
        }
        Log.d(tag, "UDP 接收循环结束，共收到 $packetCount 个数据包")
    }
    
    /**
     * 处理完整的 JPEG 帧
     * 优化：减少数组拷贝，直接使用 frameBuffer 中的数据
     */
    private fun processCompleteFrame() {
        if (frameBuffer.isEmpty()) return
        
        // 计算总大小
        val totalSize = frameBuffer.sumOf { it.size }
        if (totalSize < 100) {
            // 帧太小，可能是错误数据
            Log.w(tag, "帧太小，忽略: $totalSize 字节")
            return
        }
        
        // 优化：如果只有一个包，直接使用，避免拷贝
        val frameData = if (frameBuffer.size == 1) {
            frameBuffer[0]
        } else {
            // 多个包需要合并
            val merged = ByteArray(totalSize)
            var offset = 0
            for (chunk in frameBuffer) {
                System.arraycopy(chunk, 0, merged, offset, chunk.size)
                offset += chunk.size
            }
            merged
        }
        
        // 验证是否为有效的 JPEG
        if (isValidJpeg(frameData)) {
            Log.v(tag, "收到完整 JPEG 帧，大小: ${frameData.size} 字节，分包数: ${frameBuffer.size}")
            renderFrame(frameData)
        } else {
            Log.w(tag, "收到无效的 JPEG 帧，大小: ${frameData.size} 字节，分包数: ${frameBuffer.size}")
        }
    }

    /**
     * 停止视频流
     */
    fun stopStream() {
        isStreaming = false
        streamingJob?.cancel()
        currentDetectJob?.cancel()
        udpSocket?.close()
        udpSocket = null
        frameBuffer.clear()
        updateConnectionState(ConnectionState.DISCONNECTED)
        perfMonitor.reset()
        
        // 重置性能优化相关状态
        lastFrameTime = 0L
        skippedFrames = 0L
        lastPacketTime = 0L
        frameCounter = 0
        lastDetectionTime = 0L
        
        // 回收当前显示的 Bitmap
        activity.runOnUiThread {
            currentDisplayBitmap?.let {
                if (!it.isRecycled) {
                    it.recycle()
                }
            }
            currentDisplayBitmap = null
            binding.streamView.setImageBitmap(null)
        }
    }


    /**
     * 渲染帧：优化后的版本
     * - 分离显示帧率和检测帧率
     * - 优化 Bitmap 解码（使用 inSampleSize）
     * - 及时回收旧的 Bitmap
     * - 检测时使用更小的图像尺寸
     */
    private fun renderFrame(imgData: ByteArray) {
        renderHandler.post {
            val currentTime = SystemClock.elapsedRealtime()
            val timeSinceLastFrame = currentTime - lastFrameTime
            
            // 性能优化：帧率限制 - 如果距离上一帧时间太短，跳过此帧
            if (timeSinceLastFrame < targetFrameInterval && skippedFrames < maxSkippedFrames) {
                skippedFrames++
                return@post
            }
            
            // 如果检测任务还在运行，跳过此帧（除非已经跳过太多帧）
            if (currentDetectJob?.isActive == true && skippedFrames < maxSkippedFrames) {
                skippedFrames++
                return@post
            }
            
            skippedFrames = 0
            lastFrameTime = currentTime
            frameCounter++
            perfMonitor.resetFrameCounters()

            // 性能优化：使用专用线程池处理图像
            lifecycleScope.launch(imageProcessingDispatcher) {
                try {
                    // 1. 解码用于显示的 Bitmap（使用采样优化）
                    val displayBmp = decodeBitmapWithSampling(imgData, maxDisplayWidth, maxDisplayHeight)
                    
                    if (displayBmp == null) {
                        Log.e(tag, "Bitmap 解码失败，数据大小: ${imgData.size} 字节")
                        return@launch
                    }
                    
                    // 检查 Bitmap 是否有效
                    if (displayBmp.isRecycled) {
                        Log.e(tag, "解码后的 Bitmap 已被回收")
                        return@launch
                    }
                    
                    Log.v(tag, "Bitmap 解码成功，显示尺寸: ${displayBmp.width}x${displayBmp.height}")
                    
                    // 2. 更新 UI（在主线程）
                    try {
                        withContext(Dispatchers.Main) {
                            // 安全地获取旧的 Bitmap（不直接从 drawable 获取，避免崩溃）
                            val oldBitmap = currentDisplayBitmap
                            
                            // 设置新的 Bitmap
                            binding.streamView.setImageBitmap(displayBmp)
                            currentDisplayBitmap = displayBmp
                            
                            // 回收旧的 Bitmap（如果不同且未被回收）
                            if (oldBitmap != null && oldBitmap != displayBmp && !oldBitmap.isRecycled) {
                                try {
                                    oldBitmap.recycle()
                                } catch (e: Exception) {
                                    Log.w(tag, "回收旧 Bitmap 时出错: ${e.message}")
                                }
                            }
                            
                            // 确保 streamView 可见
                            if (binding.streamView.visibility != android.view.View.VISIBLE) {
                                binding.streamView.visibility = android.view.View.VISIBLE
                            }
                        }
                    } catch (e: Exception) {
                        Log.e(tag, "更新 UI 时出错: ${e.message}", e)
                        // 如果 UI 更新失败，回收刚创建的 Bitmap
                        if (!displayBmp.isRecycled) {
                            try {
                                displayBmp.recycle()
                            } catch (recycleException: Exception) {
                                Log.e(tag, "回收 Bitmap 时出错: ${recycleException.message}")
                            }
                        }
                        return@launch
                    }

                // 3. 决定是否进行目标检测
                val shouldDetect = shouldPerformDetection(currentTime)
                
                if (shouldDetect && currentDetectJob?.isActive != true) {
                    try {
                        perfMonitor.clearTfliteWindow()
                        
                        // 4. 创建用于检测的 Bitmap（必须使用 ARGB_8888 格式，TensorFlow Lite 要求）
                        val detectionBmp = if (displayBmp.config == Bitmap.Config.ARGB_8888 && 
                            displayBmp.width <= maxDetectionWidth && 
                            displayBmp.height <= maxDetectionHeight) {
                            // 如果显示 Bitmap 已经是 ARGB_8888 且尺寸合适，直接使用
                            displayBmp
                        } else {
                            // 需要创建 ARGB_8888 格式的 Bitmap 用于检测
                            val targetWidth = if (displayBmp.width > maxDetectionWidth) maxDetectionWidth else displayBmp.width
                            val targetHeight = if (displayBmp.height > maxDetectionHeight) maxDetectionHeight else displayBmp.height
                            
                            // 创建 ARGB_8888 格式的 Bitmap
                            val detectionBitmap = Bitmap.createBitmap(
                                targetWidth,
                                targetHeight,
                                Bitmap.Config.ARGB_8888
                            )
                            
                            // 将显示 Bitmap 绘制到检测 Bitmap（如果需要缩放）
                            val canvas = android.graphics.Canvas(detectionBitmap)
                            val paint = android.graphics.Paint(android.graphics.Paint.FILTER_BITMAP_FLAG)
                            
                            if (targetWidth != displayBmp.width || targetHeight != displayBmp.height) {
                                // 需要缩放
                                val srcRect = android.graphics.Rect(0, 0, displayBmp.width, displayBmp.height)
                                val dstRect = android.graphics.Rect(0, 0, targetWidth, targetHeight)
                                canvas.drawBitmap(displayBmp, srcRect, dstRect, paint)
                            } else {
                                // 直接复制
                                canvas.drawBitmap(displayBmp, 0f, 0f, paint)
                            }
                            
                            detectionBitmap
                        }
                        
                        // 检查 Bitmap 是否有效
                        if (detectionBmp.isRecycled) {
                            Log.w(tag, "检测 Bitmap 已被回收，跳过检测")
                            return@launch
                        }
                        
                        DetectionProcessor.getInstance(activity)
                            .updateImageDimensions(detectionBmp.width, detectionBmp.height)

                        val needsRecycle = detectionBmp != displayBmp
                        currentDetectJob = lifecycleScope.launch(imageProcessingDispatcher) {
                            try {
                                val t0 = SystemClock.elapsedRealtime()
                                val results = withContext(objectDetectorHelper.getGpuThread()) {
                                    // 再次检查 Bitmap 是否有效
                                    if (detectionBmp.isRecycled) {
                                        Log.w(tag, "检测时 Bitmap 已被回收")
                                        emptyList()
                                    } else {
                                        objectDetectorHelper.detect(detectionBmp, 0)
                                    }
                                }
                                val tfliteCost = SystemClock.elapsedRealtime() - t0

                                perfMonitor.recordTfliteNow(tfliteCost)

                                // 如果检测 Bitmap 是单独创建的，需要回收（在检测完成后）
                                if (needsRecycle && !detectionBmp.isRecycled) {
                                    try {
                                        detectionBmp.recycle()
                                    } catch (e: Exception) {
                                        Log.w(tag, "回收检测 Bitmap 时出错: ${e.message}")
                                    }
                                }

                                withContext(Dispatchers.Main) {
                                    if (!displayBmp.isRecycled) {
                                        overlayView.setModelInputSize(displayBmp.width, displayBmp.height)
                                        onDetectionResults(results)
                                    }
                                }
                            } catch (e: Exception) {
                                Log.e(tag, "目标检测时出错: ${e.message}", e)
                                // 确保回收检测 Bitmap
                                if (needsRecycle && !detectionBmp.isRecycled) {
                                    try {
                                        detectionBmp.recycle()
                                    } catch (recycleException: Exception) {
                                        Log.e(tag, "回收检测 Bitmap 时出错: ${recycleException.message}")
                                    }
                                }
                            }
                        }
                    } catch (e: Exception) {
                        Log.e(tag, "准备检测时出错: ${e.message}", e)
                    }
                }
                } catch (e: Exception) {
                    Log.e(tag, "处理帧时出错: ${e.message}", e)
                }
            }
        }
    }
    
    /**
     * 使用采样优化解码 Bitmap
     * 通过 inSampleSize 减少内存占用和解码时间
     * @param forDetection 如果为 true，使用 ARGB_8888 格式（TensorFlow Lite 要求）；否则使用 RGB_565（节省内存）
     */
    private fun decodeBitmapWithSampling(
        data: ByteArray,
        maxWidth: Int,
        maxHeight: Int,
        forDetection: Boolean = false
    ): Bitmap? {
        // 第一步：只读取尺寸信息
        val options = BitmapFactory.Options().apply {
            inJustDecodeBounds = true
        }
        BitmapFactory.decodeByteArray(data, 0, data.size, options)
        
        // 计算采样率
        options.inSampleSize = calculateInSampleSize(options, maxWidth, maxHeight)
        
        // 第二步：使用采样率解码
        options.inJustDecodeBounds = false
        // 检测时必须使用 ARGB_8888，显示时可以使用 RGB_565 节省内存
        options.inPreferredConfig = if (forDetection) Bitmap.Config.ARGB_8888 else Bitmap.Config.RGB_565
        options.inDither = false
        options.inScaled = false
        
        return BitmapFactory.decodeByteArray(data, 0, data.size, options)
    }
    
    /**
     * 计算合适的采样率
     */
    private fun calculateInSampleSize(options: BitmapFactory.Options, reqWidth: Int, reqHeight: Int): Int {
        val height = options.outHeight
        val width = options.outWidth
        var inSampleSize = 1
        
        if (height > reqHeight || width > reqWidth) {
            val halfHeight = height / 2
            val halfWidth = width / 2
            
            // 计算最大的 inSampleSize，使得宽高都大于等于请求的宽高
            while ((halfHeight / inSampleSize) >= reqHeight && (halfWidth / inSampleSize) >= reqWidth) {
                inSampleSize *= 2
            }
        }
        
        return inSampleSize
    }
    
    /**
     * 决定是否应该进行目标检测
     * 基于帧计数和时间间隔
     */
    private fun shouldPerformDetection(currentTime: Long): Boolean {
        // 检查时间间隔
        if (currentTime - lastDetectionTime < minDetectionInterval) {
            return false
        }
        
        // 检查帧计数间隔
        if (frameCounter % detectionInterval != 0) {
            return false
        }
        
        lastDetectionTime = currentTime
        return true
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

