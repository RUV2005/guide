package com.danmo.guide.ui.room

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Matrix
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.util.Base64
import android.util.Log
import android.widget.Toast
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.danmo.guide.databinding.ActivityArkBinding
import com.danmo.guide.feature.room.ArkViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import androidx.core.graphics.scale

class RoomActivity : AppCompatActivity(), TextToSpeech.OnInitListener {
    companion object {
        private const val TAG = "RoomActivity"
        private const val REQUEST_CODE_PERMISSIONS = 10
        private val REQUIRED_PERMISSIONS = arrayOf(
            Manifest.permission.CAMERA,
            Manifest.permission.RECORD_AUDIO
        )
    }

    private lateinit var binding: ActivityArkBinding
    private val viewModel: ArkViewModel by viewModels()

    private var imageCapture: ImageCapture? = null
    private lateinit var cameraExecutor: ExecutorService

    // TTS 相关变量
    private var tts: TextToSpeech? = null
    private var isTtsInitialized = false
    private var pendingSpeech: String? = null
    private var cameraProvider: ProcessCameraProvider? = null
    private var isActivityActive = false

    // 新增：返回播报专用标志
    private var isNavigatingBack = false

    // 新增：用于记录是否已经播报场景描述模式
    private var hasSceneModeAnnounced = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityArkBinding.inflate(layoutInflater)
        setContentView(binding.root)

        cameraExecutor = Executors.newSingleThreadExecutor()

        // 初始化TTS引擎
        tts = TextToSpeech(this, this)

        // 请求相机和音频权限
        if (allPermissionsGranted()) {
            startCamera()
        } else {
            ActivityCompat.requestPermissions(
                this, REQUIRED_PERMISSIONS, REQUEST_CODE_PERMISSIONS
            )
        }

        setupUI()
        observeViewModel()

        // 立即尝试播报场景描述模式
        announceSceneDescriptionMode()
    }

    // 新增：播报场景描述模式的方法
    private fun announceSceneDescriptionMode() {

        // 如果TTS已经初始化，立即播报
        if (isTtsInitialized) {
            tts?.speak("当前为场景描述模式", TextToSpeech.QUEUE_FLUSH, null, "scene_mode_announcement")
            Log.d(TAG, "播报场景描述模式")
        } else {
            // 否则设置待播报内容
            pendingSpeech = "当前为场景描述模式"
            Log.d(TAG, "TTS未初始化，设置待播报场景描述模式")
        }
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            // 设置语言为中文
            val result = tts?.setLanguage(Locale.CHINESE)

            if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
                Log.e(TAG, "TTS不支持中文")
                Toast.makeText(this, "TTS不支持中文", Toast.LENGTH_LONG).show()
            } else {
                isTtsInitialized = true
                Log.d(TAG, "TTS初始化成功")

                // 如果有待朗读的内容，立即朗读
                pendingSpeech?.let {
                    speakText(it)
                    pendingSpeech = null
                }

                // 确保播报场景描述模式
                if (!hasSceneModeAnnounced) {
                    announceSceneDescriptionMode()
                    hasSceneModeAnnounced = true
                }
            }
        } else {
            Log.e(TAG, "TTS初始化失败")
            Toast.makeText(this, "语音引擎初始化失败", Toast.LENGTH_LONG).show()
        }
    }

    private fun setupUI() {
        // 拍照按钮
        binding.btnCapture.setOnClickListener {
            takePhoto()
        }

        // 朗读按钮
        binding.btnSpeak.setOnClickListener {
            val response = viewModel.apiResponse.value
            if (!response.isNullOrEmpty()) {
                speakText(response)
            } else {
                tts?.speak("没有可朗读的内容,请先拍摄一张图片", TextToSpeech.QUEUE_FLUSH, null, "intro_${System.currentTimeMillis()}")
                Toast.makeText(this, "没有可朗读的内容", Toast.LENGTH_SHORT).show()
            }
        }

        // 返回按钮
        binding.btnBack.setOnClickListener {
            navigateBackToMain()
        }
    }

    // 修复：确保播报完成后再返回
    private fun navigateBackToMain() {
        if (isNavigatingBack) return // 防止重复点击
        isNavigatingBack = true

        // 停止所有当前语音
        stopSpeech()
    }


    // 覆盖返回键行为
    @Deprecated("This method has been deprecated in favor of using the\n      {@link OnBackPressedDispatcher} via {@link #getOnBackPressedDispatcher()}.\n      The OnBackPressedDispatcher controls how back button events are dispatched\n      to one or more {@link OnBackPressedCallback} objects.")
    override fun onBackPressed() {
        super.onBackPressed()
        // 触发返回播报逻辑
        navigateBackToMain()
    }

    private fun takePhoto() {
        val imageCapture = imageCapture ?: return

        stopSpeech() // 停止任何正在进行的语音

        // 显示处理中的Toast
        tts?.speak("场景理解中...", TextToSpeech.QUEUE_FLUSH, null, "intro_${System.currentTimeMillis()}")
        Toast.makeText(this, "场景理解中...", Toast.LENGTH_SHORT).show()

        // 使用位图方式捕获图像
        imageCapture.takePicture(
            ContextCompat.getMainExecutor(this),
            object : ImageCapture.OnImageCapturedCallback() {
                override fun onCaptureSuccess(imageProxy: androidx.camera.core.ImageProxy) {
                    super.onCaptureSuccess(imageProxy)

                    val matrix = Matrix().apply {
                        postRotate(imageProxy.imageInfo.rotationDegrees.toFloat())
                    }

                    val bitmap = imageProxy.toBitmap().let {
                        Bitmap.createBitmap(
                            it,
                            0,
                            0,
                            it.width,
                            it.height,
                            matrix,
                            true
                        )
                    }

                    imageProxy.close()

                    processCapturedImage(bitmap)
                }

                override fun onError(exception: ImageCaptureException) {
                    Log.e(TAG, "拍照失败: ${exception.message}", exception)
                    Toast.makeText(this@RoomActivity, "拍照失败: ${exception.message}", Toast.LENGTH_LONG).show()
                }
            }
        )
    }

    private fun processCapturedImage(bitmap: Bitmap) {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                // 压缩图片
                val compressedBytes = compressBitmap(bitmap)   // 不再写 300

                // 转换为Base64
                val base64Image = "data:image/webp;base64,${Base64.encodeToString(compressedBytes, Base64.NO_WRAP)}"

                Log.d(TAG, "压缩结果: ${compressedBytes.size / 1024}KB → Base64: ${base64Image.length / 1024}KB")

                withContext(Dispatchers.Main) {
                    // 发送到视觉模型
                    viewModel.sendVisionRequestWithImage(
                        base64Image,
                        "简要描述一下图片的物体摆放情况，以方位与物体名称为主"
                    )
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@RoomActivity, "图片处理失败: ${e.message}", Toast.LENGTH_LONG).show()
                    Log.e(TAG, "图片处理失败", e)
                }
            }
        }
    }

    private fun startCamera() {
        // 检查 Activity 是否处于活动状态
        if (!isActivityActive) {
            Log.d(TAG, "Activity is not active, skipping camera start")
            return
        }

        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)

        cameraProviderFuture.addListener({
            try {
                // 再次检查状态
                if (!isActivityActive) {
                    Log.d(TAG, "Activity became inactive during camera initialization")
                    return@addListener
                }

                val cameraProvider: ProcessCameraProvider = cameraProviderFuture.get()
                this.cameraProvider = cameraProvider

                // 预览设置
                val preview = Preview.Builder()
                    .build()
                    .also {
                        it.setSurfaceProvider(binding.cameraPreview.surfaceProvider)
                    }

                imageCapture = ImageCapture.Builder()
                    .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                    .build()

                // 选择后置摄像头
                val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA

                try {
                    // 解除绑定后重新绑定
                    cameraProvider.unbindAll()

                    // 绑定到生命周期
                    cameraProvider.bindToLifecycle(
                        this, cameraSelector, preview, imageCapture
                    )
                } catch (exc: Exception) {
                    Log.e(TAG, "相机绑定失败", exc)
                    Toast.makeText(this@RoomActivity, "相机初始化失败: ${exc.message}", Toast.LENGTH_LONG).show()
                }
            } catch (e: Exception) {
                Log.e(TAG, "获取相机提供者失败", e)
                Toast.makeText(this@RoomActivity, "相机初始化失败: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun allPermissionsGranted() = REQUIRED_PERMISSIONS.all {
        ContextCompat.checkSelfPermission(
            baseContext, it
        ) == PackageManager.PERMISSION_GRANTED
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_CODE_PERMISSIONS) {
            if (allPermissionsGranted()) {
                startCamera()
            } else {
                Toast.makeText(this, "需要相机和麦克风权限才能使用此功能", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun observeViewModel() {
        viewModel.apiResponse.observe(this) { response ->
            if (response.isNotEmpty()) {
                Log.d(TAG, "API响应: $response")

                // 显示结果Toast
                Toast.makeText(this, response.take(50) + "...", Toast.LENGTH_LONG).show()

                // 自动朗读分析结果
                speakText(response)
            }
        }

        viewModel.errorMessage.observe(this) { error ->
            if (error.isNotEmpty()) {
                Toast.makeText(this, error, Toast.LENGTH_LONG).show()
                Log.e(TAG, "API请求错误: $error")
            }
        }
    }

    private fun speakText(text: String) {
        if (isTtsInitialized) {
            // 停止任何正在进行的语音
            stopSpeech()

            // 先朗读"开始朗读"提示
            tts?.speak("开始朗读", TextToSpeech.QUEUE_FLUSH, null, "intro_${System.currentTimeMillis()}")

            // 然后朗读实际内容
            tts?.speak(text, TextToSpeech.QUEUE_ADD, null, "content_${System.currentTimeMillis()}")

            Log.d(TAG, "开始朗读: ${text.take(50)}...")
        } else {
            // 如果TTS尚未初始化，保存文本待初始化后朗读
            pendingSpeech = text
            Log.d(TAG, "TTS未初始化，保存待朗读文本")
        }
    }

    private fun stopSpeech() {
        tts?.stop()
    }

    // 图片压缩方法
    /**
     * 将 Bitmap 压缩到指定大小以内，默认最大 300 KB。
     * 优先降低质量，仍过大时再等比缩小尺寸。
     *
     * @param bitmap     原始 Bitmap
     * @param maxSizeKB  期望的最大体积（KB），默认 300
     * @return           压缩后的字节数组（WebP 格式）
     */
    private fun compressBitmap(
        bitmap: Bitmap,
        maxSizeKB: Int = 300
    ): ByteArray {

        val outputStream = ByteArrayOutputStream()
        var quality = 90

        // 1. 先尝试质量压缩
        bitmap.compress(Bitmap.CompressFormat.WEBP, quality, outputStream)
        var bytes = outputStream.toByteArray()

        while (bytes.size > maxSizeKB * 1024 && quality > 40) {
            outputStream.reset()
            quality -= 10
            bitmap.compress(Bitmap.CompressFormat.WEBP, quality, outputStream)
            bytes = outputStream.toByteArray()
        }

        // 2. 如果仍然超标，再缩小尺寸
        if (bytes.size > maxSizeKB * 1024) {
            val scaleFactor = 0.8f
            val scaledBitmap = bitmap.scale(
                (bitmap.width * scaleFactor).toInt(),
                (bitmap.height * scaleFactor).toInt()
            )

            outputStream.reset()
            scaledBitmap.compress(Bitmap.CompressFormat.WEBP, 80, outputStream)
            bytes = outputStream.toByteArray()
            scaledBitmap.recycle()
        }

        Log.d(TAG, "压缩后大小: ${bytes.size / 1024} KB")
        return bytes
    }

    override fun onDestroy() {
        super.onDestroy()

        // 停止所有相机操作
        releaseCamera()

        // 关闭执行器
        if (::cameraExecutor.isInitialized && !cameraExecutor.isShutdown) {
            cameraExecutor.shutdown()
        }

        // 释放TTS资源
        tts?.stop()
        tts?.shutdown()
        tts = null
    }

    private fun releaseCamera() {
        try {
            if (cameraProvider != null) {
                cameraProvider?.unbindAll()
                cameraProvider = null
                Log.d(TAG, "Camera resources released")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to release camera resources", e)
        }
    }

    override fun onResume() {
        super.onResume()
        isActivityActive = true

        // 只有在活动状态时才启动相机
        if (allPermissionsGranted()) {
            startCamera()
        }

        // 确保播报场景描述模式（如果之前未播报）
        if (!hasSceneModeAnnounced) {
            announceSceneDescriptionMode()
        }
    }

    override fun onPause() {
        super.onPause()
        isActivityActive = false
        releaseCamera() // 在暂停时立即释放相机
    }
}