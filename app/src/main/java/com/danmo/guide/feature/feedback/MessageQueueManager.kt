@file:Suppress(
    "PrivatePropertyName"
)

package com.danmo.guide.feature.feedback

import android.annotation.SuppressLint
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import java.util.*
import java.util.concurrent.*
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

@Suppress("PrivatePropertyName")
class MessageQueueManager {
    // 优先级阻塞队列（按动态优先级排序）
    private val speechQueue = PriorityBlockingQueue<SpeechItem>()
    // 固定线程池（CPU核心数）
    private val executor = Executors.newFixedThreadPool(Runtime.getRuntime().availableProcessors())
    // 主线程Handler
    private val mainHandler = Handler(Looper.getMainLooper())
    // 当前播放项ID
    private var currentSpeechId: String? = null
    // 队列操作锁
    private val queueLock = ReentrantLock()
    // 应用上下文
    private lateinit var context: Context
    // 最近消息记录（防重复）
    private val recentMessages = object : ConcurrentHashMap<String, Long>() {
        // 过期条目清理器
        private val cleaner = Executors.newSingleThreadScheduledExecutor()
        init {
            // 每分钟清理一次过期条目
            cleaner.scheduleWithFixedDelay(::removeExpiredEntries, 1, 1, TimeUnit.MINUTES)
        }
        // 清理5分钟前的条目
        private fun removeExpiredEntries() {
            val now = System.currentTimeMillis()
            keys().asSequence()
                .filter { get(it)?.let { ts -> now - ts > 300_000 } ?: false }
                .forEach { remove(it) }
        }
    }

    // 新增：最大队列长度
    private val MAX_QUEUE_SIZE = 15
    // 新增：队列状态监控
    private val queueSizeMonitor = Executors.newSingleThreadScheduledExecutor()
    private var lastProcessTime = System.currentTimeMillis()

    init {
        // 每2秒检查队列状态
        queueSizeMonitor.scheduleWithFixedDelay({
            val currentTime = System.currentTimeMillis()
            val staleness = currentTime - lastProcessTime

            queueLock.withLock {
                // 当队列积压且超过1秒未处理时强制触发
                if (speechQueue.size > 5 && staleness > 1000) {
                    processNextInQueue(TTSManager.getInstance(context), VibrationManager.getInstance(context))
                }

                // 丢弃过时消息 (超过3秒未处理)
                if (speechQueue.isNotEmpty()) {
                    val now = System.currentTimeMillis()
                    val iterator = speechQueue.iterator()
                    while (iterator.hasNext()) {
                        val item = iterator.next()
                        if (now - item.timestamp > 3000 && item.basePriority != MsgPriority.CRITICAL) {
                            iterator.remove()
                            Log.w("MessageQueue", "丢弃过期消息: ${item.text}")
                        }
                    }
                }
            }
        }, 2, 2, TimeUnit.SECONDS)
    }

    /**
     * 语音消息项数据结构
     * @property text 播报文本
     * @property direction 方位信息
     * @property basePriority 基础优先级
     * @property label 物体标签
     * @property timestamp 时间戳
     * @property vibrationPattern 振动模式
     * @property id 唯一标识
     */
    private data class SpeechItem(
        val text: String,
        val direction: String,
        val basePriority: MsgPriority,
        val label: String,
        val timestamp: Long = System.currentTimeMillis(),
        val vibrationPattern: LongArray? = null,
        val id: String = UUID.randomUUID().toString()
    ) : Comparable<SpeechItem> {
        // 时效衰减因子（8秒内线性衰减）
        private val ageFactor: Float get() = synchronized(this) {
            1 - (System.currentTimeMillis() - timestamp).coerceAtMost(8000L) / 8000f
        }

        // 动态优先级计算（基础优先级 * 时效因子 + 滞留加成）
        val dynamicPriority: Int get() {
            val base = when (basePriority) {
                MsgPriority.CRITICAL -> 1000
                MsgPriority.HIGH -> 800
                MsgPriority.NORMAL -> 500
            }
            // 滞留超过1.5秒的消息提升优先级
            val ageBonus = if (System.currentTimeMillis() - timestamp > 1500) 200 else 0
            return (base * ageFactor).toInt() + ageBonus
        }

        // 优先级比较（降序排列）
        override fun compareTo(other: SpeechItem): Int = other.dynamicPriority.compareTo(this.dynamicPriority)

        // 相等性判断（基于标签+方向+文本）
        override fun equals(other: Any?): Boolean = (other as? SpeechItem)?.let {
            label == it.label && direction == it.direction && text == it.text
        } ?: false

        // 哈希值计算
        override fun hashCode(): Int = label.hashCode() + 31 * direction.hashCode()
    }

    // 消息优先级枚举
    enum class MsgPriority { CRITICAL, HIGH, NORMAL }

    companion object {
        // 单例实例（双重校验锁）
        @SuppressLint("StaticFieldLeak")
        @Volatile
        private var instance: MessageQueueManager? = null

        // 获取单例实例
        fun getInstance(context: Context): MessageQueueManager {
            return instance ?: synchronized(this) {
                instance ?: MessageQueueManager().also {
                    instance = it
                    it.context = context.applicationContext  // 使用应用上下文
                }
            }
        }
    }

    /**
     * 将消息加入队列
     * @param message 播报文本
     * @param direction 方位信息
     * @param priority 消息优先级
     * @param label 物体标签
     * @param vibrationPattern 振动模式（可选）
     */
    fun enqueueMessage(
        message: String,
        direction: String,
        priority: MsgPriority,
        label: String,
        vibrationPattern: LongArray? = null
    ) {
        // 生成消息唯一键
        val messageKey = "${label}_${direction}_${message.hashCode()}"
        // 设置消息抑制时长（根据优先级）
        val suppressDuration = when (priority) {
            MsgPriority.CRITICAL -> 2000  // 紧急消息2秒内不重复
            MsgPriority.HIGH -> 1500       // 高优先级1.5秒
            MsgPriority.NORMAL -> 1000    // 普通优先级1秒
        }

        // 检查重复消息
        if (recentMessages[messageKey]?.let {
                System.currentTimeMillis() - it < suppressDuration
            } == true) return

        queueLock.withLock {
            // 关键优化1：合并同区域同类型消息
            val existingIndex = speechQueue.indexOfFirst {
                it.label == label && it.direction == direction
            }

            if (existingIndex != -1) {
                val existing = speechQueue.elementAt(existingIndex)
                // 合并策略：保留最高优先级或最新消息
                if (priority.ordinal < existing.basePriority.ordinal ||
                    (priority == existing.basePriority && System.currentTimeMillis() > existing.timestamp)) {
                    speechQueue.remove(existing)
                } else {
                    return@withLock  // 已有更优消息，跳过
                }
            }

            // 关键优化2：队列满时丢弃低优先级消息
            if (speechQueue.size >= MAX_QUEUE_SIZE) {
                // 优先丢弃最旧的普通优先级消息
                val toRemove = speechQueue.filter {
                    it.basePriority == MsgPriority.NORMAL
                }.minByOrNull { it.timestamp }

                toRemove?.let {
                    speechQueue.remove(it)
                    Log.w("MessageQueue", "队列已满，丢弃消息: ${it.text}")
                }
            }

            // 关键优化3：创建消息项
            val item = SpeechItem(
                text = message,
                direction = direction,
                basePriority = priority,
                label = label,
                vibrationPattern = vibrationPattern
            )

            // 关键优化4：紧急消息直接插入队列
            if (priority == MsgPriority.CRITICAL) {
                // 创建高优先级副本确保插队
                val criticalItem = item.copy(basePriority = MsgPriority.CRITICAL)
                speechQueue.add(criticalItem)
            } else {
                speechQueue.add(item)
            }

            recentMessages[messageKey] = System.currentTimeMillis()

            // 立即触发处理
            processNextInQueue(TTSManager.getInstance(context), VibrationManager.getInstance(context))
        }
    }

    /**
     * 处理队列中的下一条消息
     * @param ttsManager 语音管理器
     * @param vibrationManager 振动管理器
     */
    private fun processNextInQueue(ttsManager: TTSManager, vibrationManager: VibrationManager) {
        queueLock.withLock {
            lastProcessTime = System.currentTimeMillis()  // 更新处理时间

            if (currentSpeechId != null || speechQueue.isEmpty()) return@withLock

            speechQueue.poll()?.let { item ->
                currentSpeechId = item.id
                executor.submit {
                    try {
                        // 缩短小米设备延迟
                        val delay = if (Build.MANUFACTURER.equals("Xiaomi", ignoreCase = true)) 100L else 0L

                        if (delay > 0) {
                            mainHandler.postDelayed({
                                processItem(item, ttsManager, vibrationManager)
                            }, delay)
                        } else {
                            processItem(item, ttsManager, vibrationManager)
                        }
                    } catch (e: Exception) {
                        Log.e("MessageQueue", "处理消息失败: ${e.message}")
                        queueLock.withLock {
                            currentSpeechId = null
                        }
                        processNextInQueue(ttsManager, vibrationManager)
                    }
                }
            }
        }
    }

    // 实际处理消息项
    private fun processItem(
        item: SpeechItem,
        ttsManager: TTSManager,
        vibrationManager: VibrationManager
    ) {
        try {
            // 执行振动反馈
            item.vibrationPattern?.let { pattern ->
                vibrationManager.vibrate(pattern)
            }
            // 执行语音播报
            ttsManager.speak(item.text, item.id)
        } finally {
            queueLock.withLock {
                currentSpeechId = null
                // 继续处理下一条
                processNextInQueue(ttsManager, vibrationManager)
            }
        }
    }

    // 清空消息队列
    fun clearQueue() {
        queueLock.withLock {
            speechQueue.clear()
            currentSpeechId = null
        }
    }

}