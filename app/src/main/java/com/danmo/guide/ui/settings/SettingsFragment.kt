package com.danmo.guide.ui.settings

import android.annotation.SuppressLint
import android.content.SharedPreferences
import android.os.Bundle
import android.util.Log
import android.view.View
import androidx.preference.PreferenceFragmentCompat
import com.danmo.guide.R
import com.danmo.guide.feature.feedback.FeedbackManager

class SettingsFragment : PreferenceFragmentCompat(),
    SharedPreferences.OnSharedPreferenceChangeListener {

    private var feedbackManager: FeedbackManager? = null

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        try {
            setPreferencesFromResource(R.xml.preferences, rootKey)
            context?.let { ctx ->
                feedbackManager = FeedbackManager.getInstance(ctx)
            }
        } catch (e: Exception) {
            Log.e("SettingsFragment", "初始化设置失败", e)
        }
    }

    override fun onResume() {
        super.onResume()
        try {
            preferenceManager.sharedPreferences?.registerOnSharedPreferenceChangeListener(this)
        } catch (e: Exception) {
            Log.e("SettingsFragment", "注册设置监听器失败", e)
        }
    }

    override fun onPause() {
        super.onPause()
        try {
            preferenceManager.sharedPreferences?.unregisterOnSharedPreferenceChangeListener(this)
        } catch (e: Exception) {
            Log.e("SettingsFragment", "注销设置监听器失败", e)
        }
    }

    @SuppressLint("RestrictedApi")
    override fun onSharedPreferenceChanged(sharedPreferences: SharedPreferences?, key: String?) {
        if (sharedPreferences == null || key == null) return

        try {
            when (key) {
                "speech_enabled" -> {
                    val enabled = sharedPreferences.getBoolean(key, true)
                    feedbackManager?.let { manager ->
                        FeedbackManager.speechEnabled = enabled
                        if (!enabled) {
                            manager.clearQueue()
                        }
                    }

                    // 通知读屏器设置已变更
                    activity?.findViewById<View>(android.R.id.content)?.announceForAccessibility(
                        "语音功能已${if (enabled) "启用" else "禁用"}"
                    )
                }
                "speech_language" -> {
                    val lang = sharedPreferences.getString(key, "zh") ?: "zh"
                    feedbackManager?.let { manager ->
                        manager.updateLanguage(lang)
                        manager.clearQueue()
                    }

                    // 通知读屏器设置已变更
                    activity?.findViewById<View>(android.R.id.content)?.announceForAccessibility(
                        "语音语言已更改为${lang}"
                    )
                }
                "speech_rate" -> {
                    val rawValue = sharedPreferences.getInt(key, 12)
                    val rate = (rawValue / 10f).coerceIn(0.5f, 2.0f)
                    FeedbackManager.speechRate = rate

                    // 通知读屏器设置已变更
                    activity?.findViewById<View>(android.R.id.content)?.announceForAccessibility(
                        "语音语速已更改为${rate}"
                    )
                }
                "danger_sensitivity" -> {
                    val level = sharedPreferences.getString(key, "medium") ?: "medium"
                    updateSensitivity(level)

                    // 通知读屏器设置已变更
                    activity?.findViewById<View>(android.R.id.content)?.announceForAccessibility(
                        "危险灵敏度已更改为${level}"
                    )
                }
                "vibration_enabled" -> {
                    val enabled = sharedPreferences.getBoolean(key, true)
                    // TODO: 实现振动设置

                    // 通知读屏器设置已变更
                    activity?.findViewById<View>(android.R.id.content)?.announceForAccessibility(
                        "振动功能已${if (enabled) "启用" else "禁用"}"
                    )
                }
                "batch_processing" -> {
                    val enabled = sharedPreferences.getBoolean(key, true)
                    // TODO: 实现批处理设置

                    // 通知读屏器设置已变更
                    activity?.findViewById<View>(android.R.id.content)?.announceForAccessibility(
                        "批处理功能已${if (enabled) "启用" else "禁用"}"
                    )
                }
            }
        } catch (e: Exception) {
            Log.e("SettingsFragment", "处理设置变更失败: $key", e)
        }
    }

    private fun updateSensitivity(level: String) {
        FeedbackManager.confidenceThreshold = when (level) {
            "high" -> 0.3f
            "medium" -> 0.4f
            "low" -> 0.5f
            else -> 0.4f
        }
    }
}