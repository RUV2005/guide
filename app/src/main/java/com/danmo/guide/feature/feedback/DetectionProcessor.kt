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
        private const val BATCH_INTERVAL_MS = 50L

        // 最小报告间隔（毫秒）| Minimum reporting interval (ms)
        private const val MIN_REPORT_INTERVAL_MS = 500L

        // 默认置信度阈值 | Default confidence threshold
        private const val DEFAULT_CONFIDENCE_THRESHOLD = 0.4f

        // 危险目标标签集合 | Dangerous object labels
        private val DANGEROUS_LABELS = setOf("car", "person", "bus", "truck")

        // 屏幕区域划分常量（横向）| Screen division constants (horizontal)
        private const val COLUMN_1 = 0.25f
        private const val COLUMN_2 = 0.5f
        private const val COLUMN_3 = 0.75f

        // 屏幕区域划分常量（纵向）| Screen division constants (vertical)
        private const val ROW_1 = 0.25f
        private const val ROW_2 = 0.5f
        private const val ROW_3 = 0.75f

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
        // 列0 | Column 0
        arrayOf("左后远", "左后方", "左前近", "左前远"),    // Far back-left, Back-left, Close front-left, Far front-left
        // 列1 | Column 1
        arrayOf("后偏左", "正后方", "正前方", "前偏左"),    // Back-left, Direct back, Direct front, Front-left
        // 列2 | Column 2
        arrayOf("后偏右", "正后方", "正前方", "前偏右"),    // Back-right, Direct back, Direct front, Front-right
        // 列3 | Column 3
        arrayOf("右后远", "右后方", "右前近", "右前远")     // Far back-right, Back-right, Close front-right, Far front-right
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
                // 清空队列并收集所有待处理结果 | Drain queue and collect all pending results
                while (pendingDetections.isNotEmpty()) pendingDetections.poll()?.let { add(it) }
            }

            // 合并重叠检测后逐个处理 | Process merged detections
            mergeDetections(batch).forEach {
                try {
                    processSingleDetection(it)
                } catch (e: Exception) {
                    Log.e("ProcessBatch", "处理单个检测失败 | Failed to process single detection", e)
                }
            }
        } catch (e: Exception) {
            Log.e("ProcessBatch", "批处理失败 | Batch processing failed", e)
        }
    }

    /**
     * 合并重叠检测框算法 | Algorithm for merging overlapping detections
     * 策略：| Strategy:
     * 1. 按检测框面积从大到小排序 | Sort by bounding box area (descending)
     * 2. 重叠率超过60%且同类目标则合并 | Merge if overlap >60% and same label
     * @return 合并后的检测结果列表 | Merged detection list
     */
    private fun mergeDetections(detections: List<Detection>): List<Detection> {
        /**
         * 计算两个矩形的重叠比例 | Calculate overlap ratio between two rectangles
         * @param a 第一个矩形 | First rectangle
         * @param b 第二个矩形 | Second rectangle
         * @return 重叠比例（0.0-1.0）| Overlap ratio (0.0-1.0)
         */
        fun overlapRatio(a: RectF, b: RectF): Float {
            val interWidth = max(0f, min(a.right, b.right) - max(a.left, b.left))
            val interHeight = max(0f, min(a.bottom, b.bottom) - max(a.top, b.top))
            val interArea = interWidth * interHeight
            val unionArea = a.width() * a.height() + b.width() * b.height() - interArea
            return if (unionArea > 0) interArea / unionArea else 0f
        }

        val merged = mutableListOf<Detection>()
        // 按检测框面积降序处理 | Process in descending order of bounding box area
        detections.sortedByDescending { it.boundingBox.width() }.forEach { detection ->
            // 检查是否与已合并目标重叠 | Check overlap with existing detections
            if (merged.none { existing ->
                    overlapRatio(existing.boundingBox, detection.boundingBox) > 0.6 &&
                            existing.categories.any { it.label == detection.categories.first().label }
                }) {
                merged.add(detection)
            }
        }
        return merged
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
            .takeIf { it.isNotEmpty() }  // 过滤空分类 | Filter empty categories
            ?.maxByOrNull { it.score }   // 取最高分分类 | Get highest score category
            ?.takeIf { it.score >= confidenceThreshold }  // 置信度检查 | Confidence check
            ?.let { category ->
                val label = getChineseLabel(category.label)  // 获取本地化标签 | Get localized label
                buildDetectionMessage(result, label)?.let { (message, dir, pri, lbl) ->
                    // 将消息加入队列 | Enqueue message
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
        // 过滤无效标签 | Filter invalid labels
        if (shouldSuppressMessage(label) || label.isEmpty()) return null

        // 获取或创建目标上下文（用于频率控制）| Get or create object context (for rate control)
        val context = contextMemory.compute(label) { _, v ->
            v?.apply {
                // 每次访问时降低报告频率 | Decrease report frequency on each access
                speedFactor = max(0.5f, speedFactor * 0.9f)
            } ?: ObjectContext()  // 初始上下文 | Initial context
        }!!

        return when {
            // 处理关键危险目标 | Handle critical detections
            isCriticalDetection(result) -> Quadruple(
                generateCriticalAlert(label),
                "center",
                MessageQueueManager.MsgPriority.CRITICAL,
                label
            )
            // 常规目标处理 | Handle normal detections
            shouldReport(context, generateDirectionMessage(label, calculateDirection(result.boundingBox))) -> {
                val direction = calculateDirection(result.boundingBox)
                context.lastReportTime = System.currentTimeMillis()
                // 动态加快报告频率 | Dynamically increase report frequency
                context.speedFactor = min(2.0f, context.speedFactor * 1.1f)
                Quadruple(
                    generateDirectionMessage(label, direction),
                    "general",
                    getDirectionPriority(direction),  // 根据方向确定优先级 | Determine priority by direction
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
    private fun shouldReport(context: ObjectContext, newMessage: String): Boolean {
        val baseInterval = when {
            newMessage.contains("注意！") -> MIN_REPORT_INTERVAL_MS / 2      // 紧急消息缩短间隔 | Shorten interval for urgent messages
            newMessage.contains("危险！") -> MIN_REPORT_INTERVAL_MS * 2 / 3  // 危险消息中等间隔 | Medium interval for dangerous messages
            else -> MIN_REPORT_INTERVAL_MS                                  // 普通消息标准间隔 | Standard interval for normal messages
        }
        return System.currentTimeMillis() - context.lastReportTime > (baseInterval / context.speedFactor).toLong()
    }

    /**
     * 计算目标方向 | Calculate object direction
     * @param box 目标边界框 | Bounding box
     * @return 方向描述字符串 | Direction description string
     */
    private fun calculateDirection(box: RectF): String {
        val centerX = box.centerX()
        val centerY = box.centerY()

        // 列判断（横向分区）| Column determination (horizontal)
        val col = when {
            centerX < COLUMN_1 -> 0    // 左半区 | Left zone
            centerX < COLUMN_2 -> 1    // 中左区 | Middle-left
            centerX < COLUMN_3 -> 2    // 中右区 | Middle-right
            else -> 3                  // 右半区 | Right zone
        }

        // 行判断（纵向分区）| Row determination (vertical)
        val row = when {
            centerY < ROW_1 -> 0       // 远距离 | Far
            centerY < ROW_2 -> 1       // 中距离 | Medium
            centerY < ROW_3 -> 2       // 近距离 | Close
            else -> 3                  // 超近距离 | Very close
        }

        return directionNames[col][row]
    }

    /**
     * 生成关键警报消息 | Generate critical alert message
     * @param label 目标标签 | Object label
     */
    private fun generateCriticalAlert(label: String): String {
        val templates = listOf(
            "注意！正前方发现$label",   // Warning! $label ahead
            "危险！$label,接近中",     // Danger! $label approaching
            "紧急！$label,靠近"        // Emergency! $label nearby
        )
        return templates.random()  // 随机选择模板增加多样性 | Random selection for variety
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
            "左后远" to listOf("左后方远距离检测到$label", "$label,在左后方较远位置"),  // Far back-left detections
            "左后方" to listOf("左后方有$label", "注意左后方的$label"),               // Back-left presence
            "左前近" to listOf("左前方近距离发现$label", "注意！左前方有$label,接近"),   // Close front-left
            "左前远" to listOf("左前方远距离存在$label", "$label,在左前方较远位置"),    // Far front-left

            "后偏左" to listOf("左后方检测到$label", "$label,位于您的左后方"),        // Back-left detection
            "正后方" to listOf("正后方发现$label", "请注意身后有$label"),              // Direct back detection
            "正前方" to listOf("正前方有$label", "检测到正前方存在$label"),            // Direct front detection
            "前偏左" to listOf("左前方检测到$label", "$label,位于您的前方左侧"),       // Front-left detection

            "后偏右" to listOf("右后方检测到$label", "$label,位于您的右后方"),        // Back-right detection
            "前偏右" to listOf("右前方检测到$label", "$label,位于您的前方右侧"),       // Front-right detection

            "右后远" to listOf("右后方远距离检测到$label", "$label,在右后方较远位置"),  // Far back-right
            "右后方" to listOf("右后方有$label", "注意右后方的$label"),               // Back-right presence
            "右前近" to listOf("右前方近距离发现$label", "注意！右前方有$label,接近"),   // Close front-right
            "右前远" to listOf("右前方远距离存在$label", "$label,在右前方较远位置")    // Far front-right
        )
        return templates[direction]?.random() ?: "检测到$label"  // 默认消息 | Default message
    }

    /**
     * 标签本地化映射（英->中）| Label localization map (EN->CN)
     * @param original 原始标签 | Original label
     */
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
        )[original.lowercase()] ?: original  // 未知标签返回原文 | Return original for unknown labels
    }

    /**
     * 关闭时清理资源 | Cleanup resources on shutdown
     */
    fun shutdown() {
        batchProcessor.shutdown()   // 关闭线程池 | Shutdown thread pool
        contextMemory.clear()       // 清空上下文记忆 | Clear context memory
    }
}