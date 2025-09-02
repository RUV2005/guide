package com.danmo.guide.ui.settings


/**
 * 一个配置项的完整元数据
 */
data class PrefMeta(
    val key: String,
    val title: String,
    val summary: String? = null,
    val entries: List<String>? = null,      // 仅 ListPreference 需要
    val entryValues: List<String>? = null,  // 同上
    val default: Any,
    val min: Int? = null,                   // 仅 SeekBarPreference 需要
    val max: Int? = null                    // 同上
)

/**
 * 所有设置项的“配置表”
 * 增删改只动这一处
 */
object SettingsSchema {
    val items = listOf(
        PrefMeta(
            key = "speech_enabled",
            title = "语音播报",
            summary = null,
            default = true
        ),
        PrefMeta(
            key = "speech_language",
            title = "语音语言",
            summary = "当前语言：%s",
            entries = listOf("中文", "English"),
            entryValues = listOf("zh", "en"),
            default = "zh"
        ),
        PrefMeta(
            key = "speech_rate",
            title = "语速",
            summary = "当前速度：%.1f",
            default = 12,
            min = 5,
            max = 20
        ),
        PrefMeta(
            key = "danger_sensitivity",
            title = "危险灵敏度",
            summary = "当前级别：%s",
            entries = listOf("高", "中", "低"),
            entryValues = listOf("high", "medium", "low"),
            default = "medium"
        ),
        PrefMeta(
            key = "vibration_enabled",
            title = "振动反馈",
            summary = null,
            default = true
        ),
        PrefMeta(
            key = "batch_processing",
            title = "批处理",
            summary = null,
            default = true
        )
    )
}