package com.danmo.guide.ui.voicecall

import android.Manifest
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.danmo.guide.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

object Protocol {
    // 协议版本和头部大小 (固定值)
    const val VERSION_AND_HEADER_SIZE = 0x11 // 0b00010001

    // 消息类型
    const val FULL_CLIENT_REQUEST = 0x14 // 0b00010100 (文本事件)
    const val AUDIO_ONLY_REQUEST = 0x24 // 0b00100100 (音频事件)

    // 序列化方法
    const val SERIALIZATION_JSON = 0x10 // JSON序列化
    const val SERIALIZATION_RAW = 0x00 // RAW序列化

    // 压缩方法
    const val COMPRESSION_NONE = 0x00 // 无压缩
    const val COMPRESSION_GZIP = 0x01 // GZIP压缩

    // 标志位
    const val FLAG_EVENT = 0x04 // 携带事件ID

    // 客户端事件ID
    const val START_CONNECTION = 1
    const val FINISH_CONNECTION = 2
    const val START_SESSION = 100
    const val FINISH_SESSION = 102
    const val TASK_REQUEST = 200

    // 服务器事件ID
    const val SESSION_STARTED = 150
    const val SESSION_FINISHED = 152
    const val SESSION_FAILED = 153
    const val TTS_RESPONSE = 352
    const val ASR_RESPONSE = 451
    const val CHAT_RESPONSE = 550
}

class VoiceCallActivity : AppCompatActivity() {

    // 音频配置
    private val inputSampleRate = 24000
    private val outputSampleRate = 24000
    private val chunkSize = 960 // 20ms for 24000Hz, mono, 16bit: 24000/50*2

    // 会话管理
    private val sessionId = UUID.randomUUID().toString()
    private var audioRecord: AudioRecord? = null
    private var audioTrack: AudioTrack? = null
    private var webSocket: WebSocket? = null
    private var isRunning = true
    private var isSessionFinished = false
    private var isRecording = false
    private var isPlaying = false

    // 协程管理
    private val scope = CoroutineScope(Dispatchers.IO)
    private var recordJob: Job? = null
    private var playJob: Job? = null

    // 音频播放队列
    private val audioQueue = ArrayBlockingQueue<ByteArray>(10)

    // WebSocket配置
    private val wsConfig = mapOf(
        "base_url" to "wss://openspeech.bytedance.com/api/v3/realtime/dialogue",
        "headers" to mapOf(
            "X-Api-App-ID" to "5239069460",
            "X-Api-Access-Key" to "tY7B0c4T8A1_hprspqi-KnXeesh8G5sQ",
            "X-Api-Resource-Id" to "volc.speech.dialog",
            "X-Api-App-Key" to "PlgvMymc7f3tQnJ6",
            "X-Api-Connect-Id" to UUID.randomUUID().toString()
        )
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_call)

        // 设置音频模式
        val audioManager = getSystemService(AUDIO_SERVICE) as AudioManager
        audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
        audioManager.isSpeakerphoneOn = true

        // 返回按钮事件
        findViewById<View>(R.id.btn_back).setOnClickListener {
            finishCall()
        }

        // 请求权限
        requestAudioPermission()
    }

    // 修复点1: 确保在权限获取后再初始化音频设备
    private fun requestAudioPermission() {
        when {
            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.RECORD_AUDIO
            ) == PackageManager.PERMISSION_GRANTED -> {
                // 权限已授予，初始化音频设备
                initAudioDevices()
                startVoiceCall()
            }

            ActivityCompat.shouldShowRequestPermissionRationale(
                this,
                Manifest.permission.RECORD_AUDIO
            ) -> {
                AlertDialog.Builder(this)
                    .setTitle("需要录音权限")
                    .setMessage("语音通话需要访问麦克风，请允许权限后重试")
                    .setPositiveButton("允许") { _, _ ->
                        ActivityCompat.requestPermissions(
                            this,
                            arrayOf(Manifest.permission.RECORD_AUDIO),
                            1
                        )
                    }
                    .setNegativeButton("取消") { _, _ -> finish() }
                    .show()
            }

            else -> {
                ActivityCompat.requestPermissions(
                    this,
                    arrayOf(Manifest.permission.RECORD_AUDIO),
                    1
                )
            }
        }
    }

    // 修复点2: 重构音频设备初始化，添加状态检查
    private fun initAudioDevices(): Boolean {
        return try {
            // 初始化录音设备
            val minBufferSize = AudioRecord.getMinBufferSize(
                inputSampleRate,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT
            ).coerceAtLeast(chunkSize * 2)

            audioRecord = AudioRecord(
                MediaRecorder.AudioSource.VOICE_COMMUNICATION,
                inputSampleRate,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                minBufferSize
            )

            // 检查录音设备是否初始化成功
            if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
                Log.e("AudioInit", "AudioRecord初始化失败")
                showToast("麦克风初始化失败")
                return false
            }

            // 初始化播放设备
            val trackBufferSize = AudioTrack.getMinBufferSize(
                outputSampleRate,
                AudioFormat.CHANNEL_OUT_MONO,
                AudioFormat.ENCODING_PCM_16BIT
            ).coerceAtLeast(chunkSize * 4)

            audioTrack = AudioTrack(
                AudioManager.STREAM_MUSIC,
                outputSampleRate,
                AudioFormat.CHANNEL_OUT_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                trackBufferSize,
                AudioTrack.MODE_STREAM
            ).apply {
                setVolume(AudioTrack.getMaxVolume())
            }

            // 检查播放设备是否初始化成功
            if (audioTrack?.state != AudioTrack.STATE_INITIALIZED) {
                Log.e("AudioInit", "AudioTrack初始化失败")
                showToast("扬声器初始化失败")
                return false
            }

            // 配置音频轨道
            configureAudioTrack()
            true
        } catch (e: SecurityException) {
            Log.e("AudioInit", "缺少录音权限", e)
            showToast("缺少录音权限")
            false
        } catch (e: Exception) {
            Log.e("AudioInit", "音频设备初始化失败", e)
            showToast("音频设备初始化失败")
            false
        }
    }

    private fun configureAudioTrack() {
        audioTrack?.apply {
            setBufferSizeInFrames(chunkSize * 2)
            setVolume(0.7f)
        }
    }

    private fun startVoiceCall() {
        // 连接WebSocket
        connectWebSocket()
    }

    private fun float32ToPCM16Bytes(floatData: ByteArray): ByteArray {
        val floatBuffer = ByteBuffer.wrap(floatData).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
        val pcm16Array = ByteArray(floatBuffer.remaining() * 2)
        var idx = 0
        while (floatBuffer.hasRemaining()) {
            val f = floatBuffer.get().coerceIn(-1f, 1f)
            val s = (f * Short.MAX_VALUE).toInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
            pcm16Array[idx++] = (s and 0xFF).toByte()
            pcm16Array[idx++] = ((s shr 8) and 0xFF).toByte()
        }
        return pcm16Array
    }

    private fun connectWebSocket() {
        try {
            Log.d("WebSocket", "尝试连接: ${wsConfig["base_url"]}")
            Log.d("WebSocket", "请求头: ${wsConfig["headers"]}")

            val client = OkHttpClient.Builder()
                .readTimeout(10, TimeUnit.SECONDS)
                .writeTimeout(10, TimeUnit.SECONDS)
                .build()

            val request = Request.Builder()
                .url(wsConfig["base_url"] as String)
                .apply {
                    (wsConfig["headers"] as Map<String, String>).forEach { (key, value) ->
                        addHeader(key, value)
                    }
                }
                .build()

            webSocket = client.newWebSocket(request, object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    Log.d("WebSocket", "连接已打开")
                    Log.d("WebSocket", "响应头: ${response.headers}")

                    // 连接成功后发送StartConnection事件
                    sendStartConnection()
                }

                override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                    Log.d("WebSocket", "收到二进制消息: ${bytes.size}字节")
                    handleServerResponse(bytes)
                }

                override fun onMessage(webSocket: WebSocket, text: String) {
                    Log.d("WebSocket", "收到文本消息: $text")
                }

                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                    Log.d("WebSocket", "连接已关闭: $code, $reason")
                    finishCall()
                }

                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    Log.e("WebSocket", "连接失败", t)
                    if (response != null) {
                        Log.e("WebSocket", "响应码: ${response.code}")
                        Log.e("WebSocket", "响应体: ${response.body?.string()}")
                    }
                    finishCall()
                }
            })
        } catch (e: Exception) {
            Log.e("WebSocket", "WebSocket连接异常", e)
            finishCall()
        }
    }

    private fun sendStartConnection() {
        try {
            // StartConnection事件payload为空对象
            val payload = "{}".toByteArray()

            // 创建消息
            val message = buildMessage(
                messageType = Protocol.FULL_CLIENT_REQUEST,
                serialization = Protocol.SERIALIZATION_JSON,
                compression = Protocol.COMPRESSION_NONE,
                flags = Protocol.FLAG_EVENT,
                eventId = Protocol.START_CONNECTION,
                sessionId = null,
                payload = payload
            )

            Log.d("WebSocket", "发送StartConnection事件: ${message.size}字节")
            webSocket?.send(ByteString.of(*message))

            // 发送StartSession事件
            sendStartSession()
        } catch (e: Exception) {
            Log.e("WebSocket", "发送StartConnection失败", e)
        }
    }

    private fun sendStartSession() {
        try {
            val startSessionReq = """
            {
                "tts": {
                    "audio_config": {
                        "channel": 1,
                        "format": "pcm",
                        "sample_rate": 24000
                    }
                },
                "dialog": {
                    "bot_name": "豆包"
                }
            }
            """.trimIndent()

            // 压缩payload
            val compressed = gzipCompress(startSessionReq.toByteArray())

            // 创建消息
            val message = buildMessage(
                messageType = Protocol.FULL_CLIENT_REQUEST,
                serialization = Protocol.SERIALIZATION_JSON,
                compression = Protocol.COMPRESSION_GZIP,
                flags = Protocol.FLAG_EVENT,
                eventId = Protocol.START_SESSION,
                sessionId = sessionId,
                payload = compressed
            )

            Log.d("WebSocket", "发送StartSession事件: ${message.size}字节")
            webSocket?.send(ByteString.of(*message))

            // 启动音频处理
            startAudioCoroutines()
        } catch (e: Exception) {
            Log.e("WebSocket", "发送StartSession失败", e)
        }
    }

    private fun buildMessage(
        messageType: Int,
        serialization: Int,
        compression: Int,
        flags: Int,
        eventId: Int,
        sessionId: String?,
        payload: ByteArray
    ): ByteArray {
        // 计算总长度
        val sessionIdBytes = sessionId?.toByteArray() ?: byteArrayOf()
        val sessionIdLength = sessionIdBytes.size

        // 消息结构:
        // 1. 4字节头部
        // 2. 4字节事件ID
        // 3. 4字节会话ID长度 (如果存在会话ID)
        // 4. 会话ID (如果存在)
        // 5. 4字节payload长度
        // 6. payload
        val totalLength = 4 + 4 + (if (sessionId != null) 4 + sessionIdLength else 0) + 4 + payload.size
        val buffer = ByteBuffer.allocate(totalLength)
        buffer.order(ByteOrder.BIG_ENDIAN)

        // 1. 头部 (4字节)
        buffer.put(Protocol.VERSION_AND_HEADER_SIZE.toByte()) // 协议版本和头部大小
        buffer.put(messageType.toByte()) // 消息类型和标志位
        buffer.put((serialization or compression).toByte()) // 序列化方法和压缩方法
        buffer.put(0) // 保留字节

        // 2. 事件ID (4字节, 大端序)
        buffer.putInt(eventId)

        // 3. 会话ID (如果存在)
        if (sessionId != null) {
            // 会话ID长度 (4字节, 大端序)
            buffer.putInt(sessionIdLength)
            // 会话ID内容
            buffer.put(sessionIdBytes)
        }

        // 4. payload长度 (4字节, 大端序)
        buffer.putInt(payload.size)

        // 5. payload
        buffer.put(payload)

        return buffer.array()
    }

    // 修复点3: 确保音频设备初始化成功后再启动协程
    private fun startAudioCoroutines() {
        // 检查音频设备是否初始化成功
        if (audioRecord == null || audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
            Log.e("Audio", "录音设备未初始化，无法启动协程")
            showToast("录音设备初始化失败")
            return
        }

        if (audioTrack == null || audioTrack?.state != AudioTrack.STATE_INITIALIZED) {
            Log.e("Audio", "播放设备未初始化，无法启动协程")
            showToast("播放设备初始化失败")
            return
        }

        // 启动播放协程
        playJob = scope.launch {
            try {
                Log.d("Audio", "启动音频播放")
                audioTrack?.play()
                isPlaying = true

                while (isRunning) {
                    try {
                        val audioData = audioQueue.poll(100, TimeUnit.MILLISECONDS)
                        if (audioData != null) {
                            var offset = 0
                            while (offset < audioData.size && isRunning) {
                                val writeSize = minOf(1024, audioData.size - offset)
                                audioTrack?.write(audioData, offset, writeSize)
                                offset += writeSize
                            }
                        }
                    } catch (e: InterruptedException) {
                        // 忽略超时
                    } catch (e: Exception) {
                        Log.e("AudioPlay", "播放失败", e)
                    }
                }
            } catch (e: Exception) {
                Log.e("AudioPlay", "播放协程错误", e)
            } finally {
                Log.d("Audio", "停止音频播放")
                try {
                    audioTrack?.stop()
                } catch (e: IllegalStateException) {
                    Log.e("Audio", "停止播放失败", e)
                }
                isPlaying = false
            }
        }

        // 启动录音协程
        recordJob = scope.launch {
            val buffer = ByteArray(chunkSize)
            try {
                Log.d("Audio", "启动音频录制")
                audioRecord?.startRecording()
                isRecording = true

                while (isRunning) {
                    try {
                        val bytesRead = audioRecord?.read(buffer, 0, chunkSize) ?: 0
                        if (bytesRead > 0) {
                            sendAudioData(buffer.copyOf(bytesRead))
                        }
                        delay(10)
                    } catch (e: Exception) {
                        if (!isRunning) {
                            Log.d("AudioRecord", "正常停止录音")
                        } else {
                            Log.e("AudioRecord", "录音失败", e)
                        }
                    }
                }
            } catch (e: Exception) {
                if (!isRunning) {
                    Log.d("AudioRecord", "正常停止录音")
                } else {
                    Log.e("AudioRecord", "录音协程错误", e)
                }
            } finally {
                Log.d("Audio", "停止音频录制")
                try {
                    if (isRecording) {
                        audioRecord?.stop()
                    }
                } catch (e: IllegalStateException) {
                    Log.e("Audio", "停止录制失败", e)
                }
                isRecording = false
            }
        }
    }

    private fun showToast(message: String) {
        runOnUiThread {
            Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
        }
    }

    private fun sendAudioData(audioData: ByteArray) {
        if (!isRunning || webSocket == null) return

        try {
            // 压缩音频数据
            val compressed = gzipCompress(audioData)

            // 创建消息
            val message = buildMessage(
                messageType = Protocol.AUDIO_ONLY_REQUEST,
                serialization = Protocol.SERIALIZATION_RAW,
                compression = Protocol.COMPRESSION_GZIP,
                flags = Protocol.FLAG_EVENT,
                eventId = Protocol.TASK_REQUEST,
                sessionId = sessionId,
                payload = compressed
            )

            webSocket?.send(ByteString.of(*message))
        } catch (e: Exception) {
            Log.e("WebSocket", "发送音频失败", e)
        }
    }

    private fun handleServerResponse(bytes: ByteString) {
        try {
            val data = bytes.toByteArray()
            if (data.size < 4) {
                Log.e("WebSocket", "响应数据过短: ${data.size}字节")
                return
            }

            val buffer = ByteBuffer.wrap(data)
            buffer.order(ByteOrder.BIG_ENDIAN)

            // 1. 解析头部 (4字节)
            val versionAndHeader = buffer.get().toInt() and 0xFF
            val messageTypeAndFlags = buffer.get().toInt() and 0xFF
            val serializationAndCompression = buffer.get().toInt() and 0xFF
            val reserved = buffer.get().toInt() and 0xFF

            // 2. 解析事件ID (4字节)
            val eventId = buffer.int

            // 3. 解析会话ID (如果存在)
            var sessionId: String? = null
            if (eventId in 100..599) { // Session类事件
                val sessionIdLength = buffer.int
                val sessionIdBytes = ByteArray(sessionIdLength)
                buffer.get(sessionIdBytes)
                sessionId = String(sessionIdBytes)
            }

            // 4. 解析payload长度 (4字节)
            val payloadSize = buffer.int

            // 检查数据长度
            if (buffer.remaining() < payloadSize) {
                Log.e("WebSocket", "响应数据不完整: 需要${payloadSize}字节, 剩余${buffer.remaining()}字节")
                return
            }

            // 5. 解析payload
            val payload = ByteArray(payloadSize)
            buffer.get(payload)

            // 处理payload
            val decompressed = when (serializationAndCompression and 0x0F) { // 低4位是压缩方法
                Protocol.COMPRESSION_GZIP -> gzipDecompress(payload)
                else -> payload
            }

            // 处理不同类型的事件
            when (eventId) {
                Protocol.SESSION_STARTED -> {
                    Log.d("WebSocket", "会话已启动")
                }
                Protocol.TTS_RESPONSE -> {
                    val pcm16 = float32ToPCM16Bytes(decompressed)
                    audioQueue.put(pcm16)
                }
                Protocol.SESSION_FINISHED -> {
                    Log.d("WebSocket", "会话已结束")
                    isSessionFinished = true
                    finishCall()
                }
                Protocol.SESSION_FAILED -> {
                    val errorMsg = String(decompressed)
                    Log.e("WebSocket", "会话失败: $errorMsg")
                    finishCall()
                }
                Protocol.ASR_RESPONSE, Protocol.CHAT_RESPONSE -> {
                    val message = String(decompressed)
                    Log.d("WebSocket", "收到服务器消息: $message")
                }
                else -> {
                    Log.d("WebSocket", "收到未知事件: $eventId")
                }
            }
        } catch (e: Exception) {
            Log.e("WebSocket", "处理响应失败", e)
        }
    }

    private fun shortArrayToByteArray(samples: ShortArray): ByteArray {
        val bytes = ByteArray(samples.size * 2)
        ByteBuffer.wrap(bytes)
            .order(ByteOrder.LITTLE_ENDIAN)
            .asShortBuffer()
            .put(samples)
        return bytes
    }

    private fun finishCall() {
        if (!isRunning) return

        isRunning = false

        try {
            // 发送FinishSession事件
            val finishMsg = "{}".toByteArray()
            val message = buildMessage(
                messageType = Protocol.FULL_CLIENT_REQUEST,
                serialization = Protocol.SERIALIZATION_JSON,
                compression = Protocol.COMPRESSION_NONE,
                flags = Protocol.FLAG_EVENT,
                eventId = Protocol.FINISH_SESSION,
                sessionId = sessionId,
                payload = finishMsg
            )
            webSocket?.send(ByteString.of(*message))

            // 发送FinishConnection事件
            val finishConnMsg = buildMessage(
                messageType = Protocol.FULL_CLIENT_REQUEST,
                serialization = Protocol.SERIALIZATION_JSON,
                compression = Protocol.COMPRESSION_NONE,
                flags = Protocol.FLAG_EVENT,
                eventId = Protocol.FINISH_CONNECTION,
                sessionId = null,
                payload = "{}".toByteArray()
            )
            webSocket?.send(ByteString.of(*finishConnMsg))
        } catch (e: Exception) {
            Log.e("WebSocket", "发送结束消息失败", e)
        }

        // 关闭连接
        try {
            webSocket?.close(1000, "正常关闭")
        } catch (e: Exception) {
            Log.e("WebSocket", "关闭连接失败", e)
        }

        // 释放资源
        try {
            if (isRecording) {
                audioRecord?.stop()
            }
            audioRecord?.release()
            audioRecord = null
        } catch (e: Exception) {
            Log.e("Audio", "释放录音设备失败", e)
        }

        try {
            if (isPlaying) {
                audioTrack?.stop()
            }
            audioTrack?.release()
            audioTrack = null
        } catch (e: Exception) {
            Log.e("Audio", "释放播放设备失败", e)
        }

        recordJob?.cancel()
        playJob?.cancel()

        finish()
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 1 && grantResults.isNotEmpty() &&
            grantResults[0] == PackageManager.PERMISSION_GRANTED
        ) {
            // 权限获取后重新初始化
            if (initAudioDevices()) {
                startVoiceCall()
            } else {
                showToast("音频设备初始化失败")
                finish()
            }
        } else {
            showToast("需要录音权限才能使用语音通话")
            finish()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        finishCall()
    }

    // GZIP压缩
    private fun gzipCompress(data: ByteArray): ByteArray {
        ByteArrayOutputStream().use { bos ->
            GZIPOutputStream(bos).use { gzip ->
                gzip.write(data)
                gzip.finish()
            }
            return bos.toByteArray()
        }
    }

    // GZIP解压
    private fun gzipDecompress(data: ByteArray): ByteArray {
        GZIPInputStream(data.inputStream()).use { gis ->
            return gis.readBytes()
        }
    }
}