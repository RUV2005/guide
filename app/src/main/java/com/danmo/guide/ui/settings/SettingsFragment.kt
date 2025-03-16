package com.danmo.guide.ui.settings

import android.annotation.SuppressLint
import android.content.SharedPreferences
import android.os.Bundle
import android.util.Log
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
                }
                "speech_language" -> {
                    val lang = sharedPreferences.getString(key, "zh") ?: "zh"
                    feedbackManager?.let { manager ->
                        manager.updateLanguage(lang)
                        manager.clearQueue()
                    }
                }
                "speech_rate" -> {
                    val rawValue = sharedPreferences.getInt(key, 12)
                    val rate = (rawValue / 10f).coerceIn(0.5f, 2.0f)
                    FeedbackManager.speechRate = rate
                }
                "danger_sensitivity" -> {
                    val level = sharedPreferences.getString(key, "medium") ?: "medium"
                    updateSensitivity(level)
                }
                "vibration_enabled" -> {
                    val enabled = sharedPreferences.getBoolean(key, true)
                    // TODO: 实现振动设置
                }
                "batch_processing" -> {
                    val enabled = sharedPreferences.getBoolean(key, true)
                    // TODO: 实现批处理设置
                }
            }
        } catch (e: Exception) {
            Log.e("SettingsFragment", "处理设置变更失败: $key", e)
        }
    }

    private fun updateSensitivity(level: String) {
        try {
            FeedbackManager.confidenceThreshold = when (level) {
                "high" -> 0.3f
                "medium" -> 0.4f
                "low" -> 0.5f
                else -> 0.4f
            }
        } catch (e: Exception) {
            Log.e("SettingsFragment", "更新灵敏度失败", e)
        }
    }
}