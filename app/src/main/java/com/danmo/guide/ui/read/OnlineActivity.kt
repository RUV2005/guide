package com.danmo.guide.ui.read

import android.Manifest
import android.annotation.SuppressLint
import android.content.ContentValues
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Base64
import android.util.Log
import android.widget.ImageView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import com.danmo.guide.BuildConfig
import com.danmo.guide.R
import com.google.common.util.concurrent.ListenableFuture
import com.google.gson.Gson
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

class ReadOnlineActivity : AppCompatActivity(), TextToSpeech.OnInitListener {

    private val tag = "OCR_DEMO"

    // 凭证信息
    private val apiKey = BuildConfig.OCR_API_KEY
    private val apiSecret = "YzVlMjcxMGNhMWQ5YzExMTBlOGY0OTdj"
    private val appId = "0a6d43e9"
    private val host = "api.xf-yun.com"
    private val path = "/v1/private/sf8e6aca1"

    // CameraX 相关变量
    private lateinit var previewView: PreviewView
    private lateinit var cameraProviderFuture: ListenableFuture<ProcessCameraProvider>
    private lateinit var cameraExecutor: ExecutorService
    private var imageCapture: ImageCapture? = null

    // TTS 相关变量
    private var textToSpeech: TextToSpeech? = null
    private var isTtsInitialized = false
    private var pendingSpeechText: String? = null
    private var lastRecognizedText: String? = null // 保存最后一次识别结果

    // 权限请求码
    private val permissionRequestCode = 1001

    // 视图元素
    private lateinit var btnRecognize: ImageView
    private lateinit var btnRepeat: ImageView // 重复播报按钮

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_read_online)

        previewView = findViewById(R.id.preview_view)
        btnRecognize = findViewById(R.id.btnRecognize)
        btnRepeat = findViewById(R.id.btnRepeat) // 新添加的重复播报按钮

        // 初始化线程池
        cameraExecutor = Executors.newSingleThreadExecutor()

        // 初始化TTS
        textToSpeech = TextToSpeech(this, this)

        // 请求摄像头权限
        if (allPermissionsGranted()) {
            startCamera()
        } else {
            ActivityCompat.requestPermissions(
                this,
                REQUIRED_PERMISSIONS,
                permissionRequestCode
            )
        }

        // 设置拍照按钮点击事件
        btnRecognize.setOnClickListener {
            takePhoto()
        }

        // 设置重复播报按钮点击事件
        btnRepeat.setOnClickListener {
            repeatLastSpeech()
        }
    }

    private fun repeatLastSpeech() {
        lastRecognizedText?.let {
            if (it.isNotEmpty()) {
                // 显示Toast提示正在重复播报
                Toast.makeText(this, "正在重复播报识别结果", Toast.LENGTH_SHORT).show()
                speakText(it)
            } else {
                Toast.makeText(this, "没有可播报的内容", Toast.LENGTH_SHORT).show()
            }
        } ?: run {
            Toast.makeText(this, "请先拍照识别内容", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            // 设置语言为中文
            val result = textToSpeech?.setLanguage(Locale.CHINESE)

            if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
                Log.e(tag, "中文语言包不支持或缺失")
            } else {
                isTtsInitialized = true
                Log.i(tag, "TTS初始化成功")

                // 如果有等待朗读的文本
                pendingSpeechText?.let {
                    speakText(it)
                    pendingSpeechText = null
                }
            }
        } else {
            Log.e(tag, "TTS初始化失败")
        }
    }

    private fun speakText(text: String) {
        if (isTtsInitialized) {
            // 停止当前语音（如果有）
            textToSpeech?.stop()

            // 设置语速和音调
            textToSpeech?.setSpeechRate(1.0f) // 正常语速
            textToSpeech?.setPitch(1.0f)      // 正常音调

            // 添加语音进度监听器
            textToSpeech?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) {
                    Log.i(tag, "语音开始播放")
                }

                override fun onDone(utteranceId: String?) {
                    Log.i(tag, "语音播放完成")
                    runOnUiThread {
                        // 播放完成恢复按钮颜色
                        btnRepeat.clearColorFilter()
                    }
                }

                override fun onError(utteranceId: String?) {
                    Log.e(tag, "语音播放出错")
                    runOnUiThread {
                        btnRepeat.clearColorFilter()
                        Toast.makeText(this@ReadOnlineActivity, "语音播放失败", Toast.LENGTH_SHORT).show()
                    }
                }
            })

            // 使用参数设置，确保在Android 21+上正常播放
            val params = Bundle().apply {
                putString(TextToSpeech.Engine.KEY_PARAM_UTTERANCE_ID, "OCR_RESULT")
            }

            // 播放语音
            textToSpeech?.speak(text, TextToSpeech.QUEUE_FLUSH, params, "OCR_RESULT")
        } else {
            // TTS尚未初始化完成，保存文本稍后朗读
            pendingSpeechText = text
            Log.w(tag, "TTS未初始化，保存文本稍后朗读")
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        // 释放TTS资源
        textToSpeech?.stop()
        textToSpeech?.shutdown()
        textToSpeech = null
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == permissionRequestCode) {
            if (allPermissionsGranted()) {
                startCamera()
            } else {
                Toast.makeText(
                    this,
                    "需要摄像头权限才能使用该功能",
                    Toast.LENGTH_SHORT
                ).show()
                finish()
            }
        }
    }

    private fun allPermissionsGranted() = REQUIRED_PERMISSIONS.all {
        ContextCompat.checkSelfPermission(
            baseContext, it
        ) == PackageManager.PERMISSION_GRANTED
    }

    @SuppressLint("MissingPermission")
    private fun startCamera() {
        cameraProviderFuture = ProcessCameraProvider.getInstance(this)

        cameraProviderFuture.addListener({
            val cameraProvider = cameraProviderFuture.get()

            // 预览用例
            val preview = Preview.Builder()
                .build()
                .also {
                    it.setSurfaceProvider(previewView.surfaceProvider)
                }

            // 拍照用例
            imageCapture = ImageCapture.Builder()
                .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                .build()

            // 选择后置摄像头
            val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA

            try {
                // 解绑所有用例
                cameraProvider.unbindAll()

                // 绑定用例到相机
                cameraProvider.bindToLifecycle(
                    this as LifecycleOwner,
                    cameraSelector,
                    preview,
                    imageCapture
                )

            } catch (exc: Exception) {
                Log.e(tag, "相机绑定失败", exc)
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun takePhoto() {
        val imageCapture = imageCapture ?: return

        // 创建文件保存选项
        val contentValues = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, "ocr_image_${System.currentTimeMillis()}")
            put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
            if (Build.VERSION.SDK_INT > Build.VERSION_CODES.P) {
                put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/OCR-Images")
            }
        }

        val outputOptions = ImageCapture.OutputFileOptions.Builder(
            contentResolver,
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            contentValues
        ).build()

        imageCapture.takePicture(
            outputOptions,
            ContextCompat.getMainExecutor(this),
            object : ImageCapture.OnImageSavedCallback {
                override fun onImageSaved(outputFileResults: ImageCapture.OutputFileResults) {
                    // 获取保存的照片URI
                    val savedUri = outputFileResults.savedUri
                    if (savedUri != null) {
                        // 将图片转换为Base64
                        val inputStream = contentResolver.openInputStream(savedUri)
                        val bitmap = BitmapFactory.decodeStream(inputStream)
                        inputStream?.close()

                        if (bitmap != null) {
                            val base64Image = bitmapToBase64(bitmap)
                            // 发送OCR请求
                            recognizeTextFromImage(base64Image)
                        } else {
                            Log.e(tag, "Bitmap解码失败")
                        }
                    }
                }

                override fun onError(exception: ImageCaptureException) {
                    Log.e(tag, "拍照失败: ${exception.message}", exception)
                }
            }
        )
    }

    private fun bitmapToBase64(bitmap: Bitmap): String {
        val outputStream = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, 90, outputStream)
        val byteArray = outputStream.toByteArray()
        return Base64.encodeToString(byteArray, Base64.DEFAULT)
    }

    private fun recognizeTextFromImage(base64Image: String) {
        Thread {
            try {
                // 2. 构建请求JSON
                val requestJson = buildRequestJson(base64Image)

                // 3. 生成鉴权参数
                val authParams = generateAuthorization()

                // 4. 发送请求
                val client = OkHttpClient()
                val mediaType = "application/json".toMediaType()
                val requestBody = requestJson.toRequestBody(mediaType)

                val request = Request.Builder()
                    .url("https://$host$path?$authParams")
                    .post(requestBody)
                    .build()

                client.newCall(request).enqueue(object : Callback {
                    override fun onFailure(call: Call, e: IOException) {
                        Log.e(tag, "请求失败: ${e.message}")
                    }

                    override fun onResponse(call: Call, response: Response) {
                        val responseBody = response.body?.string()
                        Log.d(tag, "响应: $responseBody")

                        // 5. 解析结果
                        parseResponse(responseBody).let { resultText ->
                            Log.i(tag, "最终识别结果: $resultText")

                            // 保存最后一次识别结果
                            lastRecognizedText = resultText

                            runOnUiThread {
                                Toast.makeText(
                                    this@ReadOnlineActivity,
                                    "识别结果: $resultText",
                                    Toast.LENGTH_LONG
                                ).show()

                                // 使用TTS朗读识别结果
                                speakText(resultText)
                            }
                        }
                    }
                })
            } catch (e: Exception) {
                Log.e(tag, "处理异常: ${e.message}")
            }
        }.start()
    }


    private fun buildRequestJson(base64Image: String): String {
        val payload = HashMap<String, Any>().apply {
            put("sf8e6aca1_data_1", mapOf(
                "encoding" to "jpg",
                "status" to 3,
                "image" to base64Image
            ))
        }

        val parameter = HashMap<String, Any>().apply {
            put("sf8e6aca1", mapOf(
                "category" to "ch_en_public_cloud",
                "result" to mapOf(
                    "encoding" to "utf8",
                    "compress" to "raw",
                    "format" to "json"
                )
            ))
        }

        val header = mapOf(
            "app_id" to appId,
            "status" to 3
        )

        return Gson().toJson(mapOf(
            "header" to header,
            "parameter" to parameter,
            "payload" to payload
        ))
    }

    private fun generateAuthorization(): String {
        // 生成RFC1123格式时间
        val sdf = SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss z", Locale.US)
        sdf.timeZone = TimeZone.getTimeZone("GMT")
        val date = sdf.format(Date())

        // 构建签名原始字符串
        val signatureOrigin = """
            host: $host
            date: $date
            POST $path HTTP/1.1
        """.trimIndent()

        // 计算HMAC-SHA256签名
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(apiSecret.toByteArray(), "HmacSHA256"))
        val signatureSha = mac.doFinal(signatureOrigin.toByteArray())
        val signature = Base64.encodeToString(signatureSha, Base64.NO_WRAP)

        // 构建认证原始字符串
        val authOrigin = """
            api_key="$apiKey", 
            algorithm="hmac-sha256", 
            headers="host date request-line", 
            signature="$signature"
        """.trimIndent().replace("\n", "")

        // URL编码参数
        return "authorization=${Base64.encodeToString(authOrigin.toByteArray(), Base64.NO_WRAP)}" +
                "&host=$host" +
                "&date=${URLEncoder.encode(date, "UTF-8")}"
    }

    private fun parseResponse(response: String?): String {
        if (response.isNullOrEmpty()) return "空响应"

        return try {
            val json = Gson().fromJson(response, Map::class.java)
            val payload = json["payload"] as? Map<*, *> ?: return "payload缺失"
            val result = payload["result"] as? Map<*, *> ?: return "result缺失"
            val textBase64 = result["text"] as? String ?: return "text缺失"

            // 解码Base64结果
            val decodedBytes = Base64.decode(textBase64, Base64.DEFAULT)
            val decodedResult = String(decodedBytes, Charsets.UTF_8)

            // 提取识别文本
            val resultJson = Gson().fromJson(decodedResult, Map::class.java)

            // 安全获取pages
            val pages = resultJson["pages"] as? List<*> ?: return "pages缺失"

            val textBuilder = StringBuilder()

            pages.forEach { pageObj ->
                val page = pageObj as? Map<*, *> ?: return@forEach
                val lines = page["lines"] as? List<*> ?: return@forEach

                lines.forEach { lineObj ->
                    val line = lineObj as? Map<*, *> ?: return@forEach

                    // 尝试获取words数组
                    val words = line["words"] as? List<*>
                    if (words != null) {
                        words.forEach { wordObj ->
                            val word = wordObj as? Map<*, *>
                            val content = word?.get("content") as? String
                            if (!content.isNullOrEmpty()) {
                                textBuilder.append(content).append(" ")
                            }
                        }
                    }
                    // 如果words不存在，尝试直接获取content
                    else {
                        val content = line["content"] as? String
                        if (!content.isNullOrEmpty()) {
                            textBuilder.append(content).append(" ")
                        }
                    }
                }
            }

            textBuilder.toString().trim()

        } catch (e: Exception) {
            Log.e(tag, "解析错误: ${e.message}", e)
            "解析错误: ${e.message}"
        }
    }

    companion object {
        // 所需权限列表
        private val REQUIRED_PERMISSIONS = arrayOf(Manifest.permission.CAMERA)
    }
}