package com.danmo.guide.feature.feedback
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.RectF
import android.util.Log
import org.tensorflow.lite.task.vision.detector.Detection
import java.util.concurrent.*
import kotlin.math.max
import kotlin.math.min
/**
 * 物体检测结果处理器 | Object Detection Result Processor
 * 主要功能：处理来自TFLite的检测结果，生成提示消息，管理消息队列
 * Main responsibilities: Process TFLite detection results, generate alerts, manage message queue
 */
class DetectionProcessor {
    // 线程安全的待处理检测队列 | Thread-safe queue for pending detections
    private val pendingDetections = ConcurrentLinkedQueue<Detection>()
    // 定时批处理执行器（单线程）| Scheduled executor for batch processing (single thread)
    private val batchProcessor = Executors.newSingleThreadScheduledExecutor()
    // 目标上下文记忆（用于控制消息频率）| Context memory for objects (controls message frequency)
    private val contextMemory = ConcurrentHashMap<String, ObjectContext>()
    // Android上下文（延迟初始化）| Android context (late-init)
    private lateinit var context: Context
    // 消息队列管理器（懒加载）| Message queue manager (lazy initialization)
    private val messageQueueManager: MessageQueueManager by lazy { MessageQueueManager.getInstance(context) }
    /**
     * 目标上下文数据类 | Object context data class
     * @property lastReportTime 最后报告时间 | Last reported timestamp
     * @property speedFactor 动态速度因子（控制消息频率）| Dynamic speed factor (controls message rate)
     */
    private data class ObjectContext(
        var lastReportTime: Long = 0,
        var speedFactor: Float = 1.0f
    )
    companion object {
        // 批处理间隔（毫秒）| Batch processing interval (ms)
        private const val BATCH_INTERVAL_MS = 500L
        // 最小报告间隔（毫秒）| Minimum reporting interval (ms)
        private const val MIN_REPORT_INTERVAL_MS = 1000L
        // 默认置信度阈值 | Default confidence threshold
        private const val DEFAULT_CONFIDENCE_THRESHOLD = 0.4f
        // 危险目标标签集合 | Dangerous object labels
        private val DANGEROUS_LABELS = setOf("car", "person", "bus", "truck", "motorcycle", "bicycle","traffic light")
        // 最小有效尺寸（屏幕宽高的20%）| Minimum valid object size
        private const val MIN_OBJECT_SIZE = 0.2f
        private const val COLUMN_1 = 1f / 3f    // 0.333
        private const val COLUMN_2 = 2f / 3f    // 0.666
        private const val ROW_1 = 0.5f          // 上半为远，下半为近
        // 单例相关 | Singleton related
        @SuppressLint("StaticFieldLeak")
        @Volatile
        private var instance: DetectionProcessor? = null
        private var _confidenceThreshold: Float = DEFAULT_CONFIDENCE_THRESHOLD  // 当前置信度阈值 | Current confidence threshold
        /**
         * 置信度阈值访问器 | Confidence threshold accessors
         */
        var confidenceThreshold: Float
            get() = _confidenceThreshold
            set(value) {
                _confidenceThreshold = value
            }
        /**
         * 获取单例实例 | Get singleton instance
         * @param context Android上下文 | Android context
         */
        fun getInstance(context: Context): DetectionProcessor {
            return instance ?: synchronized(this) {
                instance ?: DetectionProcessor().also {
                    instance = it
                    it.context = context.applicationContext  // 绑定应用上下文 | Bind application context
                }
            }
        }
    }
    // 大区域定义（将6个小区域合并为3个大区域）
    private val superRegions = mapOf(
        // 左侧大区（包含左远侧、左近侧）
        "左侧" to setOf("左远侧", "左近侧"),
        // 前方大区（包含正远方、正前方）
        "正前方" to setOf("正远方", "正前方"),
        // 右侧大区（包含右前侧、右近侧）
        "右侧" to setOf("右前侧", "右近侧")
    )
    /**
     * 4x4方向矩阵（基于屏幕区域划分）| 4x4 direction matrix (based on screen zones)
     * 列索引：| Column indexes:
     * 0 - 左半区（x < 0.25）| Left zone (x < 0.25)
     * 1 - 中左区（0.25 ≤ x < 0.5）| Middle-left zone (0.25 ≤ x < 0.5)
     * 2 - 中右区（0.5 ≤ x < 0.75）| Middle-right zone (0.5 ≤ x < 0.75)
     * 3 - 右半区（x ≥ 0.75）| Right zone (x ≥ 0.75)
     *
     * 行索引：| Row indexes:
     * 0 - 远距离（屏幕顶部25%）| Far distance (top 25%)
     * 1 - 中距离 | Medium distance
     * 2 - 近距离 | Close distance
     * 3 - 超近距离（屏幕底部25%）| Very close (bottom 25%)
     */
    private val directionNames = arrayOf(
        // 远
        arrayOf("左远侧", "正远方", "右前侧"),
        // 近
        arrayOf("左近侧", "正前方", "右近侧")
    )

    // 初始化定时批处理任务 | Initialize scheduled batch processing
    init {
        batchProcessor.scheduleWithFixedDelay(
            ::processBatch,  // 处理函数 | Processing function
            0,                // 初始延迟 | Initial delay
            BATCH_INTERVAL_MS, // 执行间隔 | Execution interval
            TimeUnit.MILLISECONDS
        )
    }
    /**
     * 接收检测结果（线程安全）| Receive detection results (thread-safe)
     * @param result 物体检测结果 | Object detection result
     */
    fun handleDetectionResult(result: Detection) {
        pendingDetections.add(result)
    }
    /**
     * 批处理核心方法 | Core batch processing method
     * 1. 取出队列中的所有检测结果 | Retrieve all queued detections
     * 2. 合并重叠的检测框 | Merge overlapping bounding boxes
     * 3. 逐个处理有效检测 | Process each valid detection
     */
    private fun processBatch() {
        try {
            val batch = mutableListOf<Detection>().apply {
                while (pendingDetections.isNotEmpty()) pendingDetections.poll()?.let { add(it) }
            }

            // 步骤1: 处理紧急目标（单独播报）
            val (criticalDetections, nonCriticalDetections) = batch.partition { isCriticalDetection(it) }
            criticalDetections.forEach { processSingleDetection(it) }

            // 步骤2: 非紧急目标按大区域合并
            if (nonCriticalDetections.isNotEmpty()) {
                val mergedMessages = mergeRegionDetections(nonCriticalDetections)
                mergedMessages.forEach { (superRegion, messages) ->
                    processSuperRegion(superRegion, messages)
                }
            }
        } catch (e: Exception) {
            Log.e("ProcessBatch", "批处理失败", e)
        }
    }

    // 区域合并核心方法
    private fun mergeRegionDetections(detections: List<Detection>): Map<String, MutableList<Pair<String, RectF>>> {
        val regionMap = mutableMapOf<String, MutableList<Pair<String, RectF>>>()

        detections.forEach { detection ->
            detection.categories.firstOrNull()?.let { category ->
                // 添加置信度过滤
                if (category.score < confidenceThreshold) return@let

                val originalLabel = category.label.lowercase()
                // 添加危险目标过滤
                if (originalLabel !in DANGEROUS_LABELS) return@let

                val label = getChineseLabel(originalLabel)
                val direction = calculateDirection(detection.boundingBox)

                // 查找所属大区域
                superRegions.forEach { (superRegion, subRegions) ->
                    if (subRegions.contains(direction)) {
                        regionMap.getOrPut(superRegion) { mutableListOf() }.add(label to detection.boundingBox)
                    }
                }
            }
        }
        return regionMap
    }
    /**
     * 处理单个检测结果 | Process single detection result
     * 主要逻辑：| Main logic:
     * 1. 筛选有效检测（置信度达标）| Filter valid detections (meet confidence threshold)
     * 2. 构建提示消息 | Build alert message
     * 3. 管理消息发送频率 | Manage message frequency
     */
    private fun processSingleDetection(result: Detection) {
        result.categories
            .takeIf { it.isNotEmpty() }
            ?.maxByOrNull { it.score }
            ?.takeIf { it.score >= confidenceThreshold }
            ?.let { category ->
                // 过滤非危险目标 | Filter non-dangerous objects
                val originalLabel = category.label.lowercase()
                if (originalLabel !in DANGEROUS_LABELS) return@let
                // 过滤小尺寸目标 | Filter small objects
                val box = result.boundingBox
                if (box.width() < MIN_OBJECT_SIZE || box.height() < MIN_OBJECT_SIZE) return@let
                // 生成提示消息 | Generate alert
                val label = getChineseLabel(originalLabel)
                buildDetectionMessage(result, label)?.let { (message, dir, pri, lbl) ->
                    messageQueueManager.enqueueMessage(
                        message = message,
                        direction = dir,
                        priority = pri,
                        label = lbl,
                        // 根据优先级设置震动模式 | Set vibration pattern based on priority
                        vibrationPattern = when (pri) {
                            MessageQueueManager.MsgPriority.CRITICAL -> longArrayOf(0, 500, 200, 300)
                            MessageQueueManager.MsgPriority.HIGH -> longArrayOf(0, 300, 100, 200)
                            MessageQueueManager.MsgPriority.NORMAL -> longArrayOf(0, 200)
                        }
                    )
                }
            }
    }
    /**
     * 构建检测消息四元组 | Build detection message quadruple
     * @return (消息内容, 方向, 优先级, 标签) 或 null（当需要抑制消息时）| (message, direction, priority, label) or null
     */
    private fun buildDetectionMessage(
        result: Detection,
        label: String
    ): Quadruple<String, String, MessageQueueManager.MsgPriority, String>? {
        val direction = calculateDirection(result.boundingBox)
        if (shouldSuppressMessage(label) || label.isEmpty()) return null
        val context = contextMemory.compute(label) { _, v ->
            v?.apply { speedFactor = max(0.5f, speedFactor * 0.9f) } ?: ObjectContext()
        }!!
        return when {
            isCriticalDetection(result) -> Quadruple(
                generateCriticalAlert(label, direction),
                direction,
                MessageQueueManager.MsgPriority.CRITICAL,
                label
            )
            shouldReport(context, generateDirectionMessage(label, direction), direction) -> {
                context.lastReportTime = System.currentTimeMillis()
                context.speedFactor = min(2.0f, context.speedFactor * 1.1f)
                Quadruple(
                    generateDirectionMessage(label, direction),
                    "general",
                    getDirectionPriority(direction),
                    label
                )
            }
            else -> null
        }
    }
    /**
     * 四元组数据类（用于封装消息要素）| Quadruple data class (encapsulates message elements)
     * @property first 消息内容 | Message content
     * @property second 方向分类 | Direction category
     * @property third 消息优先级 | Message priority
     * @property fourth 目标标签 | Object label
     */
    private data class Quadruple<out A, out B, out C, out D>(
        val first: A,
        val second: B,
        val third: C,
        val fourth: D
    )
    /**
     * 判断是否需要抑制消息 | Determine if message should be suppressed
     * @param label 目标标签 | Object label
     */
    private fun shouldSuppressMessage(label: String): Boolean {
        return label == "unknown" || label.contains("background")
    }
    /**
     * 判断是否为关键危险目标 | Determine if critical detection
     * @param result 检测结果 | Detection result
     */
    private fun isCriticalDetection(result: Detection): Boolean {
        return DANGEROUS_LABELS.any { label ->
            result.categories.any { it.label == label && it.score > 0.7 }
        } && result.boundingBox.width() > 0.25f  // 目标尺寸阈值 | Size threshold
    }
    /**
     * 判断是否应该报告 | Determine if should report
     * @param context 目标上下文 | Object context
     * @param newMessage 新消息内容 | New message content
     */
    private fun shouldReport(
        context: ObjectContext,
        newMessage: String,
        regionKey: String
    ): Boolean {
        // 使用整数运算替代浮点数运算
        val baseInterval = when (regionKey) {
            "正前方" -> MIN_REPORT_INTERVAL_MS / 2   // 整数除法
            "左侧", "右侧" -> (MIN_REPORT_INTERVAL_MS * 3) / 2  // 先乘后除保持整数
            else -> MIN_REPORT_INTERVAL_MS
        }

        return System.currentTimeMillis() - context.lastReportTime > (baseInterval / context.speedFactor).toLong()
    }
    // 构建大区域消息
    private fun buildSuperRegionMessage(superRegion: String, labels: Set<String>): String {
        return when {
            labels.size == 1 -> "$superRegion,有${labels.first()}"
            labels.size == 2 -> "$superRegion,有${labels.joinToString("和")}"
            else -> {
                val mainLabels = labels.take(2)
                "$superRegion,有${mainLabels.joinToString("、")}等${labels.size}种目标"
            }
        }
    }
    // 处理大区域内的多个检测结果
    private fun processSuperRegion(
        superRegion: String,
        detections: List<Pair<String, RectF>>
    ) {
        // 合并相同标签的目标
        val labelGroups = detections.groupBy { it.first }

        // 生成合并消息
        val message = buildSuperRegionMessage(superRegion, labelGroups.keys)
        val priority = getSuperRegionPriority(superRegion)

        // 检查是否应该报告
        val context = contextMemory.compute(superRegion) { _, v -> v ?: ObjectContext() }!!
        if (shouldReport(context, message, superRegion)) {
            context.lastReportTime = System.currentTimeMillis()
            messageQueueManager.enqueueMessage(
                message = message,
                direction = superRegion,
                priority = priority,
                label = "区域合并",
                vibrationPattern = when (priority) {
                    MessageQueueManager.MsgPriority.CRITICAL -> longArrayOf(0, 500, 200, 300)
                    MessageQueueManager.MsgPriority.HIGH -> longArrayOf(0, 300, 100, 200)
                    else -> longArrayOf(0, 200)
                }
            )
        }
    }
    // 大区域优先级
    private fun getSuperRegionPriority(superRegion: String): MessageQueueManager.MsgPriority {
        return when (superRegion) {
            "正前方" -> MessageQueueManager.MsgPriority.CRITICAL
            "左侧", "右侧" -> MessageQueueManager.MsgPriority.HIGH
            else -> MessageQueueManager.MsgPriority.NORMAL
        }
    }
    /**
     * 计算目标方向 | Calculate object direction
     * @param box 目标边界框 | Bounding box
     * @return 方向描述字符串 | Direction description string
     */
    private fun calculateDirection(box: RectF): String {
        if (!::context.isInitialized) {
            return "未知"
        }
        val displayMetrics = context.resources.displayMetrics
        val screenWidth = displayMetrics.widthPixels.toFloat()
        val screenHeight = displayMetrics.heightPixels.toFloat()

        val centerX = box.centerX() / screenWidth
        val centerY = box.centerY() / screenHeight

        // 横向分区：左中右
        val col = when {
            centerX < COLUMN_1 -> 0
            centerX < COLUMN_2 -> 1
            else -> 2
        }
        // 纵向分区：远近
        val row = if (centerY < ROW_1) 0 else 1
        return directionNames[row][col]
    }
    /**
     * 生成关键警报消息 | Generate critical alert message
     * @param label 目标标签 | Object label
     */
    private fun generateCriticalAlert(label: String, direction: String): String {
        val templates = listOf(
            "注意！${direction}发现$label",
            "危险！$label,正处于${direction}",
            "紧急！$label,靠近${direction}"
        )
        return templates.random()
    }
    /**
     * 根据方向确定消息优先级 | Determine message priority by direction
     * @param direction 方向描述 | Direction description
     */
    private fun getDirectionPriority(direction: String): MessageQueueManager.MsgPriority {
        return when {
            direction.contains("正前") -> MessageQueueManager.MsgPriority.CRITICAL  // 正前方最高优先级 | Direct front: highest
            direction.contains("正后") -> MessageQueueManager.MsgPriority.HIGH      // 正后方高优先级 | Direct back: high
            direction.contains("近") -> MessageQueueManager.MsgPriority.HIGH        // 近距离高优先级 | Close distance: high
            else -> MessageQueueManager.MsgPriority.NORMAL                          // 其他普通优先级 | Others: normal
        }
    }
    /**
     * 生成方向提示消息 | Generate directional alert message
     * @param label 目标标签 | Object label
     * @param direction 方向描述 | Direction description
     */
    private fun generateDirectionMessage(label: String, direction: String): String {
        val templates = mapOf(
            "左远侧" to listOf("左远侧发现$label", "$label,出现在左远侧"),
            "正远方" to listOf("正远方有$label", "$label,在正远方"),
            "右前侧" to listOf("右前侧出现$label", "$label,在右前侧"),
            "左近侧" to listOf("左近侧有$label，注意避让", "注意左近侧的$label"),
            "正前方" to listOf("正前方有$label", "注意正前方的$label"),
            "右近侧" to listOf("右近侧有$label，注意避让", "注意右近侧的$label")
        )
        return templates[direction]?.random() ?: "检测到$label"  // 默认消息 | Default message
    }
    /**
     * 标签本地化映射（英->中）| Label localization map (EN->CN)
     * @param original 原始标签 | Original label
     */
    fun getChineseLabel(original: String): String {
        return when (original.lowercase()) {
            "person" -> "行人"
            "car" -> "汽车"
            "bus" -> "公交车"
            "truck" -> "卡车"
            "motorcycle" -> "摩托车"
            "bicycle" -> "自行车"
            else -> "非关键预设障碍物"  // 非关键目标返回unknown | Return unknown for others
        }
    }
    /**
     * 关闭时清理资源 | Cleanup resources on shutdown
     */
    fun shutdown() {
        batchProcessor.shutdown()   // 关闭线程池 | Shutdown thread pool
        contextMemory.clear()       // 清空上下文记忆 | Clear context memory
    }
}
