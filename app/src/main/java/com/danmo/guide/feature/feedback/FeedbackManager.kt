package com.danmo.guide.feature.feedback

import android.content.Context
import android.content.Intent
import android.graphics.RectF
import android.media.AudioManager
import android.os.*
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import android.widget.Toast
import org.tensorflow.lite.task.vision.detector.Detection
import java.util.*
import java.util.concurrent.*
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import kotlin.math.max
import kotlin.math.min


private const val FAR_LEFT_BOUNDARY = 0.15f    // 最左侧区域
private const val NEAR_LEFT_BOUNDARY = 0.3f    // 左侧近区域
private const val CENTER_LEFT = 0.4f           // 中心偏左边界
private const val CENTER_RIGHT = 0.6f           // 中心偏右边界
private const val NEAR_RIGHT_BOUNDARY = 0.7f   // 右侧近区域
private const val FAR_RIGHT_BOUNDARY = 0.85f    // 最右侧区域

class FeedbackManager(context: Context) : TextToSpeech.OnInitListener {

    // 初始化上下文和硬件组件
    private val context: Context = context.applicationContext
    private var tts: TextToSpeech = TextToSpeech(context, this)
    private val vibrator: Vibrator? by lazy {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            (context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager).defaultVibrator
        } else {
            @Suppress("DEPRECATION") context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
        }
    }
    private var isTtsReady = false

    // 语音消息优先级系统
    private enum class MsgPriority { CRITICAL, HIGH, NORMAL }

    // 在SpeechItem的ageFactor计算中使用同步机制
    private data class SpeechItem(
        val text: String,
        val direction: String,
        val basePriority: MsgPriority,
        val label: String, // 新增label字段
        val timestamp: Long = System.currentTimeMillis(),
        val vibrationPattern: LongArray? = null,
        val id: String = UUID.randomUUID().toString()
    ) : Comparable<SpeechItem> {
        // 使用同步机制获取时间戳
        private val ageFactor: Float get() = synchronized(this) {
            1 - (System.currentTimeMillis() - timestamp).coerceAtMost(5000L) / 5000f
        }

        val dynamicPriority: Int get() = when (basePriority) {
            MsgPriority.CRITICAL -> (1000 * ageFactor).toInt()
            MsgPriority.HIGH -> (800 * ageFactor).toInt()
            MsgPriority.NORMAL -> (500 * ageFactor).toInt()
        }

        override fun compareTo(other: SpeechItem): Int = other.dynamicPriority.compareTo(this.dynamicPriority)
        // 修改1：增强消息唯一性判断条件
        override fun equals(other: Any?): Boolean = (other as? SpeechItem)?.let {
            // 同时校验label、direction和消息文本
            label == it.label && direction == it.direction && text == it.text
        } ?: false


        override fun hashCode(): Int = label.hashCode() + 31 * direction.hashCode()
    }

    // 并发管理组件

    private val speechQueue = PriorityBlockingQueue<SpeechItem>()
    private val executor = Executors.newFixedThreadPool(Runtime.getRuntime().availableProcessors())
    private val mainHandler = Handler(Looper.getMainLooper())
    private var currentSpeechId: String? = null

    // 批处理系统
    private val pendingDetections = ConcurrentLinkedQueue<Detection>()
    private val batchProcessor = Executors.newSingleThreadScheduledExecutor()



    // 上下文记忆系统
    private data class ObjectContext(
        var lastReportTime: Long = 0,
        var speedFactor: Float = 1.0f
    )
    private val contextMemory = ConcurrentHashMap<String, ObjectContext>()

    // 新增：最近消息记录和同步锁
    // 使用带TTL的ConcurrentHashMap
    private val recentMessages = object : ConcurrentHashMap<String, Long>() {
        private val cleaner = Executors.newSingleThreadScheduledExecutor()
        init {
            cleaner.scheduleWithFixedDelay(::removeExpiredEntries, 1, 1, TimeUnit.MINUTES)
        }

        private fun removeExpiredEntries() {
            val now = System.currentTimeMillis()
            keys().asSequence().filter { get(it)?.let { ts -> now - ts > 300_000 } ?: false }
                .forEach { remove(it) }
        }
    }

    private val queueLock = ReentrantLock()

    companion object {
        private const val BATCH_INTERVAL_MS = 50L
        private const val MIN_REPORT_INTERVAL_MS = 800L
        const val CONFIDENCE_THRESHOLD = 0.4f
        private val DANGEROUS_LABELS = setOf("car", "person", "bus", "truck")
        var speechEnabled: Boolean = true
        var speechRate: Float = 1.2f
        var confidenceThreshold: Float = CONFIDENCE_THRESHOLD
        private const val LEFT_BOUNDARY = 0.3f
        private const val RIGHT_BOUNDARY = 0.7f
        private const val CENTER_LEFT = 0.4f
        private const val CENTER_RIGHT = 0.6f

        @Volatile
        private var instance: FeedbackManager? = null

        fun getInstance(context: Context): FeedbackManager {
            return instance ?: synchronized(this) {
                instance ?: FeedbackManager(context.applicationContext).also { instance = it }
            }
        }

    }


    init {
        // 初始化设备特定设置
        executor.execute {
            // 华为设备引擎设置（需要API Level 21+）
            if (Build.MANUFACTURER.equals("HUAWEI", ignoreCase = true)) {
                try {
                    tts = TextToSpeech(context, this@FeedbackManager, "com.huawei.hiai.engineservice.tts")
                } catch (e: Exception) {
                    Log.e("TTS", "华为引擎初始化失败", e)
                }
            }
        }

        // 启动批处理任务
        batchProcessor.scheduleWithFixedDelay(
            ::processBatch,
            0, BATCH_INTERVAL_MS, TimeUnit.MILLISECONDS
        )
    }

    // 在onInit方法中添加资源释放处理
    override fun onInit(status: Int) {
        executor.submit {
            when {
                status == TextToSpeech.SUCCESS -> {
                    setupTTS()
                    processNextInQueue() // 初始化完成后触发队列处理
                }
                else -> {
                    showToast("语音功能初始化失败", true)
                    tts.stop() // 关闭语音引擎
                    tts.shutdown()
                }
            }
        }
    }

    private fun setupTTS() {
        when (tts.setLanguage(Locale.CHINESE)) {
            TextToSpeech.LANG_MISSING_DATA -> handleMissingLanguageData()
            TextToSpeech.LANG_NOT_SUPPORTED -> showToast("不支持中文语音", true)
            else -> {
                tts.setOnUtteranceProgressListener(utteranceListener)
                isTtsReady = true
                Log.d("TTS", "语音引擎初始化成功")
            }
        }
    }

    private fun handleMissingLanguageData() {
        showToast("缺少中文语音数据", true)
        try {
            val intent = Intent(TextToSpeech.Engine.ACTION_INSTALL_TTS_DATA)
            intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
            context.startActivity(intent)
        } catch (e: Exception) {
            Log.e("TTS", "无法启动语音数据安装", e)
        }
    }

    // region 语音处理逻辑
    private val utteranceListener = object : UtteranceProgressListener() {
        override fun onStart(utteranceId: String?) {
            currentSpeechId = utteranceId
        }

        override fun onDone(utteranceId: String?) {
            executor.submit {
                if (currentSpeechId == utteranceId) { // 增加ID匹配校验
                    currentSpeechId = null
                    processNextInQueue()
                }
            }
        }

        @Deprecated("Deprecated in Java")
        override fun onError(utteranceId: String?) {
            executor.submit {
                currentSpeechId = null // 清除当前ID
                processNextInQueue()
            }
        }
    }

    fun handleDetectionResult(result: Detection) {
        pendingDetections.add(result)
    }

    private fun processBatch() {
        try {
            val batch = mutableListOf<Detection>().apply {
                while (pendingDetections.isNotEmpty()) pendingDetections.poll()?.let { add(it) }
            }
            mergeDetections(batch).forEach {
                try {
                    processSingleDetection(it)
                } catch (e: Exception) {
                    Log.e("ProcessBatch", "处理单个检测失败", e)
                }
            }
        } catch (e: Exception) {
            Log.e("ProcessBatch", "批处理失败", e)
        }
    }
    // endregion

    // region 核心业务逻辑
    private fun mergeDetections(detections: List<Detection>): List<Detection> {
        // 增加面积重叠率计算
        fun overlapRatio(a: RectF, b: RectF): Float {
            val interArea = max(0f, min(a.right, b.right) - max(a.left, b.left)) *
                    max(0f, min(a.bottom, b.bottom) - max(a.top, b.top))
            val unionArea = a.width() * a.height() + b.width() * b.height() - interArea
            return if (unionArea > 0) interArea / unionArea else 0f
        }

        val merged = mutableListOf<Detection>()  // 修复1：显式声明合并列表
        detections.sortedByDescending { it.boundingBox.width() }.forEach { detection ->
            // 修复2：正确引用merged变量
            if (merged.none { existing ->
                    overlapRatio(existing.boundingBox, detection.boundingBox) > 0.6 &&
                            existing.categories.any { it.label == detection.categories.first().label }
                }) {
                merged.add(detection)
            }
        }
        return merged
    }

    // 在processSingleDetection方法中添加空值检查
    private fun processSingleDetection(result: Detection) {
        if (!checkTtsHealth()) return

        result.categories.takeIf { it.isNotEmpty() }?.maxByOrNull { it.score }
            ?.takeIf { it.score >= CONFIDENCE_THRESHOLD }
            ?.let { category ->
                val label = getChineseLabel(category.label)
                buildDetectionMessage(result, label)?.let { (message, dir, pri, lbl) ->
                    mainHandler.post { enqueueMessage(message, dir, pri, lbl) } // 添加第四个参数
                }
            }
    }

    // 在buildDetectionMessage方法中添加标签合法性检查
    private fun buildDetectionMessage(result: Detection, label: String): Quadruple<String, String, MsgPriority, String>? {
        if (shouldSuppressMessage(label) || label.isEmpty()) return null // 添加标签合法性检查

        val context = contextMemory.compute(label) { _, v ->
            v?.apply { speedFactor = max(0.5f, speedFactor * 0.9f) } ?: ObjectContext()
        }!!

        return when {
            isCriticalDetection(result) -> Quadruple(
                generateCriticalAlert(label),
                "center",
                MsgPriority.CRITICAL,
                label
            )
            shouldReport(context, generateDirectionMessage(label, calculateDirection(result.boundingBox))) -> {
                context.lastReportTime = System.currentTimeMillis()
                context.speedFactor = min(2.0f, context.speedFactor * 1.1f)
                Quadruple(
                    generateDirectionMessage(label, calculateDirection(result.boundingBox)),
                    "general",
                    MsgPriority.HIGH,
                    label
                )
            }
            else -> null
        }
    }

    // 定义四元组类型
    private data class Quadruple<out A, out B, out C, out D>(
        val first: A,
        val second: B,
        val third: C,
        val fourth: D
    )

    private fun checkTtsHealth(): Boolean {
        val isHealthy = isTtsReady && tts.voices?.any { it.locale == Locale.CHINESE } == true
        if (!isHealthy) showToast("语音引擎未就绪", true)
        return isHealthy
    }
    // endregion

    // region 语音生成逻辑
    private fun generateCriticalAlert(label: String): String {
        val templates = listOf("注意！正前方发现$label", "危险！$label,接近中", "紧急！$label,靠近")
        return templates.random()
    }

    private fun generateDirectionMessage(label: String, direction: String): String {
        val templates = mapOf(
            "最左侧" to listOf("注意！最左侧发现$label", "$label,位于最左边区域"),
            "左侧" to listOf("您的左侧有$label", "检测到左侧存在$label"),
            "左侧偏右" to listOf("左侧偏右位置检测到$label", "$label,在左侧靠右区域"),
            "左前方偏左" to listOf("左前方偏左位置有$label", "检测到左前方左侧存在$label"),
            "左前方" to listOf("左前方发现$label", "$label,位于左前方"),
            "左前方偏右" to listOf("左前方偏右位置检测到$label", "$label,在左前方靠右区域"),
            "正前方(大范围)" to listOf("正前方检测到大型$label", "大面积$label,位于正前方"),
            "正前方偏左" to listOf("正前方偏左位置有$label", "$label,在正前方靠左区域"),
            "正前方" to listOf("正前方发现$label", "检测到正前方存在$label"),
            "正前方偏右" to listOf("正前方偏右位置检测到$label", "$label,在正前方靠右区域"),
            "右前方偏左" to listOf("右前方偏左位置有$label", "检测到右前方左侧存在$label"),
            "右前方" to listOf("右前方发现$label", "$label,位于右前方"),
            "右前方偏右" to listOf("右前方偏右位置检测到$label", "$label,在右前方靠右区域"),
            "右侧偏左" to listOf("右侧偏左位置有$label", "检测到右侧靠左区域存在$label"),
            "右侧" to listOf("您的右侧有$label", "检测到右侧存在$label"),
            "最右侧" to listOf("注意！最右侧发现$label", "$label,位于最右边区域")
        )

        return templates[direction]?.random() ?: "检测到$label"
    }

    fun getChineseLabel(original: String): String {
        return mapOf(
            "person" to "行人",
            "bicycle" to "自行车",
            "car" to "汽车",
            "motorcycle" to "摩托车",
            "airplane" to "飞机",
            "bus" to "公交车",
            "train" to "火车",
            "truck" to "卡车",
            "boat" to "船只",
            "traffic light" to "交通灯",
            "fire hydrant" to "消防栓",
            "stop sign" to "停车标志",
            "parking meter" to "停车计时器",
            "bench" to "长椅",
            "bird" to "鸟类",
            "cat" to "猫",
            "dog" to "狗",
            "horse" to "马",
            "sheep" to "羊",
            "cow" to "牛",
            "elephant" to "大象",
            "bear" to "熊",
            "zebra" to "斑马",
            "giraffe" to "长颈鹿",
            "backpack" to "背包",
            "umbrella" to "雨伞",
            "handbag" to "手提包",
            "tie" to "领带",
            "suitcase" to "行李箱",
            "frisbee" to "飞盘",
            "skis" to "滑雪板",
            "snowboard" to "滑雪单板",
            "sports ball" to "运动球类",
            "kite" to "风筝",
            "baseball bat" to "棒球棒",
            "baseball glove" to "棒球手套",
            "skateboard" to "滑板",
            "surfboard" to "冲浪板",
            "tennis racket" to "网球拍",
            "bottle" to "瓶子",
            "wine glass" to "酒杯",
            "cup" to "杯子",
            "fork" to "叉子",
            "knife" to "刀具",
            "spoon" to "勺子",
            "bowl" to "碗",
            "banana" to "香蕉",
            "apple" to "苹果",
            "sandwich" to "三明治",
            "orange" to "橙子",
            "broccoli" to "西兰花",
            "carrot" to "胡萝卜",
            "hot dog" to "热狗",
            "pizza" to "披萨",
            "donut" to "甜甜圈",
            "cake" to "蛋糕",
            "chair" to "椅子",
            "couch" to "沙发",
            "potted plant" to "盆栽",
            "bed" to "床",
            "dining table" to "餐桌",
            "toilet" to "马桶",
            "tv" to "电视",
            "laptop" to "笔记本",
            "mouse" to "鼠标",
            "remote" to "遥控器",
            "keyboard" to "键盘",
            "cell phone" to "手机",
            "microwave" to "微波炉",
            "oven" to "烤箱",
            "toaster" to "烤面包机",
            "sink" to "水槽",
            "refrigerator" to "冰箱",
            "book" to "书籍",
            "clock" to "时钟",
            "vase" to "花瓶",
            "scissors" to "剪刀",
            "teddy bear" to "玩偶",
            "hair drier" to "吹风机",
            "toothbrush" to "牙刷",
            "door" to "门",
            "window" to "窗户",
            "stairs" to "楼梯",
            "curtain" to "窗帘",
            "mirror" to "镜子"
        )[original.lowercase()] ?: original
    }
    // endregion

    // region 辅助判断方法
    private fun shouldSuppressMessage(label: String): Boolean {
        return label == "unknown" || label.contains("background")
    }

    private fun isCriticalDetection(result: Detection): Boolean {
        return DANGEROUS_LABELS.any { label ->
            result.categories.any { it.label == label && it.score > 0.7 }
        } && result.boundingBox.width() > 0.25f
    }

    private fun shouldReport(context: ObjectContext, newMessage: String): Boolean {
        val baseInterval = when {
            newMessage.contains("注意！") -> MIN_REPORT_INTERVAL_MS / 2
            newMessage.contains("危险！") -> MIN_REPORT_INTERVAL_MS * 2 / 3
            else -> MIN_REPORT_INTERVAL_MS
        }
        return System.currentTimeMillis() - context.lastReportTime > (baseInterval / context.speedFactor).toLong()
    }

    // 更精确的方向判断

    private fun calculateDirection(box: RectF): String {
        val centerX = box.centerX()
        val widthFactor = box.width() * 0.35f  // 考虑物体宽度的影响因子

        // 区域划分（从左到右）：
        // [0.0]----[FAR_LEFT]----[NEAR_LEFT]----[C_LEFT]----[C_RIGHT]----[NEAR_RIGHT]----[FAR_RIGHT]----[1.0]
        return when {
            // 左侧区域判断
            centerX - widthFactor < FAR_LEFT_BOUNDARY -> "最左侧"
            centerX < NEAR_LEFT_BOUNDARY -> when {
                box.right > NEAR_LEFT_BOUNDARY -> "左侧偏右"
                else -> "左侧"
            }

            // 左前方区域判断
            centerX < CENTER_LEFT -> when {
                box.right > CENTER_LEFT + 0.05f -> "左前方偏右"
                box.left < NEAR_LEFT_BOUNDARY - 0.05f -> "左前方偏左"
                else -> "左前方"
            }

            // 正前方区域判断
            centerX < CENTER_RIGHT -> when {
                box.width() > 0.3f -> "正前方(大范围)"
                box.left < CENTER_LEFT - 0.05f -> "正前方偏左"
                box.right > CENTER_RIGHT + 0.05f -> "正前方偏右"
                else -> "正前方"
            }

            // 右前方区域判断
            centerX < NEAR_RIGHT_BOUNDARY -> when {
                box.left < CENTER_RIGHT - 0.05f -> "右前方偏左"
                box.right > NEAR_RIGHT_BOUNDARY + 0.05f -> "右前方偏右"
                else -> "右前方"
            }

            // 右侧区域判断
            centerX + widthFactor > FAR_RIGHT_BOUNDARY -> "最右侧"
            else -> when {
                box.left < NEAR_RIGHT_BOUNDARY -> "右侧偏左"
                else -> "右侧"
            }
        }
    }

    // endregion

    // 修改2：强化入队过滤逻辑 增强线程安全性
    private fun enqueueMessage(message: String, direction: String, priority: MsgPriority, label: String) {
        // 组合更多维度作为唯一键
        val messageKey = "${label}_${direction}_${message.hashCode()}"
        // 延长抑制时间为基本间隔的3倍
        val suppressDuration = when (priority) {
            MsgPriority.CRITICAL -> 5000
            MsgPriority.HIGH -> 3000
            MsgPriority.NORMAL -> 2000
        }

        // 严格的三重过滤
        if (recentMessages[messageKey]?.let {
                System.currentTimeMillis() - it < suppressDuration
            } == true) return

        // 增加内存队列检查
        queueLock.withLock {
            if (!speechQueue.any { it.label == label && it.direction == direction && it.text == message }) {
                recentMessages[messageKey] = System.currentTimeMillis()

                val item = SpeechItem(
                    text = message,
                    direction = direction,
                    basePriority = priority,
                    label = label,  // 添加label参数
                    vibrationPattern = when (priority) {
                        MsgPriority.CRITICAL -> longArrayOf(0, 500, 200, 300)
                        MsgPriority.HIGH -> longArrayOf(0, 300, 100, 200)
                        MsgPriority.NORMAL -> longArrayOf(0, 200)
                    }
                )

                if (!speechQueue.any { it.label == label && it.direction == direction }) {
                    speechQueue.put(item)
                    triggerVibration(item)
                }
            }
        }
        processNextInQueue()
    }


    private fun processNextInQueue() {
        queueLock.withLock {
            if (currentSpeechId != null || speechQueue.isEmpty()) return@withLock
            speechQueue.poll()?.let { item ->
                currentSpeechId = item.id
                executor.submit { speak(item) } // 在锁内完成出队和状态更新
            }
        }
    }

    private fun speak(item: SpeechItem) {
        try {
            if (Build.MANUFACTURER.equals("Xiaomi", ignoreCase = true)) {
                // 小米设备需要延迟触发
                Handler(Looper.getMainLooper()).postDelayed({
                    doSpeak(item)
                }, 200)
            } else {
                doSpeak(item)
            }
        } catch (e: Exception) {
            Log.e("TTS", "播报失败: ${e.message}")
            processNextInQueue()
        }
    }

    // 修改speak方法
    private fun doSpeak(item: SpeechItem) {
        if (!speechEnabled) return

        try {
            val params = Bundle().apply {
                putString(TextToSpeech.Engine.KEY_PARAM_UTTERANCE_ID, item.id)
                putInt(TextToSpeech.Engine.KEY_PARAM_STREAM, AudioManager.STREAM_SYSTEM)
                putFloat("rate", speechRate) // 使用全局语速设置
            }

            tts.speak(item.text, TextToSpeech.QUEUE_ADD, params, item.id)
        } catch (e: Exception) {
            Log.e("TTS", "播报失败: ${e.message}")
        }
    }
    // endregion

    // 添加语言更新方法
    fun updateLanguage(languageCode: String) {
        executor.execute {
            when (tts.setLanguage(Locale(languageCode))) {
                TextToSpeech.LANG_AVAILABLE -> {
                    Log.d("TTS", "语言切换成功")
                    clearQueue()
                }
                else -> showToast("语言切换失败", true)
            }
        }
    }

    // 添加队列清除方法
    fun clearQueue() {
        queueLock.withLock {
            speechQueue.clear()
            currentSpeechId = null
            tts.stop()
        }
    }

    // region 振动反馈
    // 在triggerVibration方法中添加设备兼容性检查
    private fun triggerVibration(item: SpeechItem) {
        vibrator?.let {
            try {
                item.vibrationPattern?.let { pattern ->
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        it.vibrate(VibrationEffect.createWaveform(pattern, -1))
                    } else {
                        @Suppress("DEPRECATION") it.vibrate(pattern, -1)
                    }
                }
            } catch (e: Exception) {
                Log.e("Vibration", "振动失败: ${e.message}")
            }
        } ?: Log.d("Vibration", "设备不支持振动功能")
    }

    // region 工具方法
    private fun showToast(message: String, isLong: Boolean = false) {
        mainHandler.post {
            Toast.makeText(context, message, if (isLong) Toast.LENGTH_LONG else Toast.LENGTH_SHORT).show()
        }
    }

    fun shutdown() {
        batchProcessor.shutdown()
        executor.shutdown()
        vibrator?.cancel()
        tts.stop()
        tts.shutdown()
        speechQueue.clear()
        contextMemory.clear()
    }
    // endregion
}