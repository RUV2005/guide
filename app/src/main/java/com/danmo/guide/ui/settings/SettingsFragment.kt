package com.danmo.guide.ui.settings

import android.content.SharedPreferences
import android.os.Bundle
import androidx.preference.ListPreference
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import androidx.preference.PreferenceScreen
import androidx.preference.SeekBarPreference
import androidx.preference.SwitchPreferenceCompat

class SettingsFragment : PreferenceFragmentCompat(),
    SharedPreferences.OnSharedPreferenceChangeListener {

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        preferenceScreen = buildPreferenceScreen()
    }

    private fun buildPreferenceScreen(): PreferenceScreen {
        val ctx = preferenceManager.context
        val screen = preferenceManager.createPreferenceScreen(ctx)

        SettingsSchema.items.forEach { meta ->
            val pref: Preference = when {
                // Switch
                meta.default is Boolean -> SwitchPreferenceCompat(ctx).apply {
                    key = meta.key
                    title = meta.title
                    setDefaultValue(meta.default)
                }
                // List
                meta.entries != null && meta.entryValues != null -> ListPreference(ctx).apply {
                    key = meta.key
                    title = meta.title
                    summary = meta.summary
                    entries = meta.entries.toTypedArray()
                    entryValues = meta.entryValues.toTypedArray()
                    setDefaultValue(meta.default)
                }
                // SeekBar
                meta.min != null && meta.max != null -> SeekBarPreference(ctx).apply {
                    key = meta.key
                    title = meta.title
                    min = meta.min
                    max = meta.max
                    setDefaultValue(meta.default)
                    showSeekBarValue = true
                }
                else -> throw IllegalStateException("Unknown pref type for ${meta.key}")
            }
            screen.addPreference(pref)
        }

        return screen
    }

    override fun onResume() {
        super.onResume()
        preferenceManager.sharedPreferences
            ?.registerOnSharedPreferenceChangeListener(this)
    }

    override fun onPause() {
        super.onPause()
        preferenceManager.sharedPreferences
            ?.unregisterOnSharedPreferenceChangeListener(this)
    }

    override fun onSharedPreferenceChanged(sp: SharedPreferences, key: String?) {
        key ?: return
        val view = view ?: return
        val meta = SettingsSchema.items.firstOrNull { it.key == key } ?: return

        val announcement = when (meta.default) {
            is Boolean -> "${meta.title}已${if (sp.getBoolean(key, true)) "启用" else "禁用"}"
            is String -> "${meta.title}已更改为 ${sp.getString(key, "")}"
            is Int -> "${meta.title}已更改为 ${sp.getInt(key, 12) / 10f}"
            else -> ""
        }
        view.announceForAccessibility(announcement)
    }
}