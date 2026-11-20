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
    /**
     * 在测算出的平均电平上再整体加减多少 dB。
     *
     * 自动对齐只保证「各台一样响」，不能保证「符合你的耳朵」—— 有人觉得 −18 LUFS 正好，
     * 有人觉得偏轻。所以给一个整体偏移，所有台一起挪，台与台的相对关系不变。
     */
    val volumeOffsetDb: Float = DEFAULT_VOLUME_OFFSET_DB,
) {
    companion object {
        const val MIN_BUFFER_SECONDS = 1
        const val MAX_BUFFER_SECONDS = 30
        const val DEFAULT_BUFFER_SECONDS = 3
        const val DEFAULT_NORMALIZE_VOLUME = true

        /** 整体偏移范围。再大就不是微调了，而且容易被单台的增益上限吃掉，拖了没反应 */
        const val MIN_VOLUME_OFFSET_DB = -10f
        const val MAX_VOLUME_OFFSET_DB = 10f

        /** 0.5 dB 一档：听觉上刚好能分辨，又不至于要拖很久 */
        const val VOLUME_OFFSET_STEP_DB = 0.5f

        /** 默认不偏移：自动对齐算出来多少就是多少 */
        const val DEFAULT_VOLUME_OFFSET_DB = 0f
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

    /**
     * 整体音量偏移（dB）。
     *
     * 拖动时会被调用很多次，所以只在值真的变了才写盘；`apply()` 本身是异步的，
     * 但每次都发一遍 StateFlow 会让整个界面跟着重组，没必要。
     */
    fun setVolumeOffset(db: Float) {
        val clamped = db.coerceIn(
            RadioSettings.MIN_VOLUME_OFFSET_DB,
            RadioSettings.MAX_VOLUME_OFFSET_DB,
        )
        if (clamped == _settings.value.volumeOffsetDb) return
        prefs.edit().putFloat(KEY_VOLUME_OFFSET, clamped).apply()
        _settings.value = _settings.value.copy(volumeOffsetDb = clamped)
    }

    private fun read(): RadioSettings {
        val seconds = prefs.getInt(KEY_BUFFER_SECONDS, RadioSettings.DEFAULT_BUFFER_SECONDS)
            .coerceIn(RadioSettings.MIN_BUFFER_SECONDS, RadioSettings.MAX_BUFFER_SECONDS)
        val normalize = prefs.getBoolean(
            KEY_NORMALIZE_VOLUME,
            RadioSettings.DEFAULT_NORMALIZE_VOLUME,
        )
        val offset = prefs.getFloat(KEY_VOLUME_OFFSET, RadioSettings.DEFAULT_VOLUME_OFFSET_DB)
            .coerceIn(
                RadioSettings.MIN_VOLUME_OFFSET_DB,
                RadioSettings.MAX_VOLUME_OFFSET_DB,
            )
        return RadioSettings(
            bufferSeconds = seconds,
            normalizeVolume = normalize,
            volumeOffsetDb = offset,
        )
    }

    private companion object {
        const val PREFS_NAME = "radio_settings"
        const val KEY_BUFFER_SECONDS = "buffer_seconds"
        const val KEY_NORMALIZE_VOLUME = "normalize_volume"
        const val KEY_VOLUME_OFFSET = "volume_offset_db"
    }
}
