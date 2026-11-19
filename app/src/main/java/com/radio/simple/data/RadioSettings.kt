package com.radio.simple.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 应用设置：播放前的缓冲时长、是否自动对齐各台音量。
 */
data class RadioSettings(
    val bufferSeconds: Int = DEFAULT_BUFFER_SECONDS,
    /** 关掉就完全按原始电平播，不做任何增益处理 */
    val normalizeVolume: Boolean = DEFAULT_NORMALIZE_VOLUME,
) {
    companion object {
        const val MIN_BUFFER_SECONDS = 1
        const val MAX_BUFFER_SECONDS = 30
        const val DEFAULT_BUFFER_SECONDS = 3
        const val DEFAULT_NORMALIZE_VOLUME = true
    }
}

/**
 * 设置项的本地存储。和电台列表分开存，互不影响。
 */
class SettingsStore(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val _settings = MutableStateFlow(read())
    val settings: StateFlow<RadioSettings> = _settings.asStateFlow()

    /** 当前值，给播放服务这种非界面场景用 */
    val current: RadioSettings
        get() = _settings.value

    fun setBufferSeconds(seconds: Int) {
        val clamped = seconds.coerceIn(
            RadioSettings.MIN_BUFFER_SECONDS,
            RadioSettings.MAX_BUFFER_SECONDS,
        )
        if (clamped == _settings.value.bufferSeconds) return
        prefs.edit().putInt(KEY_BUFFER_SECONDS, clamped).apply()
        _settings.value = _settings.value.copy(bufferSeconds = clamped)
    }

    fun setNormalizeVolume(enabled: Boolean) {
        if (enabled == _settings.value.normalizeVolume) return
        prefs.edit().putBoolean(KEY_NORMALIZE_VOLUME, enabled).apply()
        _settings.value = _settings.value.copy(normalizeVolume = enabled)
    }

    private fun read(): RadioSettings {
        val seconds = prefs.getInt(KEY_BUFFER_SECONDS, RadioSettings.DEFAULT_BUFFER_SECONDS)
            .coerceIn(RadioSettings.MIN_BUFFER_SECONDS, RadioSettings.MAX_BUFFER_SECONDS)
        val normalize = prefs.getBoolean(
            KEY_NORMALIZE_VOLUME,
            RadioSettings.DEFAULT_NORMALIZE_VOLUME,
        )
        return RadioSettings(bufferSeconds = seconds, normalizeVolume = normalize)
    }

    private companion object {
        const val PREFS_NAME = "radio_settings"
        const val KEY_BUFFER_SECONDS = "buffer_seconds"
        const val KEY_NORMALIZE_VOLUME = "normalize_volume"
    }
}
