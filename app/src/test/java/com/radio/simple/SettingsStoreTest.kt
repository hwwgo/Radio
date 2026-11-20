package com.radio.simple

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.radio.simple.data.RadioSettings
import com.radio.simple.data.SettingsStore
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 设置的读写。重点在「整体音量偏移」：它要能跨重启活着，
 * 而且从盘里读出来时不能被旧版本写坏的数值带跑。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class SettingsStoreTest {

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    @Before
    fun clearPrefs() {
        context.getSharedPreferences("radio_settings", Context.MODE_PRIVATE)
            .edit().clear().commit()
    }

    @Test
    fun offsetDefaultsToZero() {
        assertEquals(0f, SettingsStore(context).current.volumeOffsetDb, 0.0001f)
    }

    @Test
    fun offsetSurvivesRestart() {
        SettingsStore(context).setVolumeOffset(3.5f)
        // 新建一个实例 = 进程重启后重新读盘
        assertEquals(3.5f, SettingsStore(context).current.volumeOffsetDb, 0.0001f)
    }

    @Test
    fun offsetCanGoNegative() {
        SettingsStore(context).setVolumeOffset(-4f)
        assertEquals(-4f, SettingsStore(context).current.volumeOffsetDb, 0.0001f)
    }

    @Test
    fun offsetIsClampedOnWrite() {
        val store = SettingsStore(context)
        store.setVolumeOffset(99f)
        assertEquals(RadioSettings.MAX_VOLUME_OFFSET_DB, store.current.volumeOffsetDb, 0.0001f)

        store.setVolumeOffset(-99f)
        assertEquals(RadioSettings.MIN_VOLUME_OFFSET_DB, store.current.volumeOffsetDb, 0.0001f)
    }

    @Test
    fun corruptOffsetOnDiskIsClampedOnRead() {
        // 手改 prefs、或者旧版本写过越界值：读的时候就得收回来，
        // 不能等到算增益才发现目标电平已经跑到天外
        context.getSharedPreferences("radio_settings", Context.MODE_PRIVATE)
            .edit().putFloat("volume_offset_db", 60f).commit()

        assertEquals(
            RadioSettings.MAX_VOLUME_OFFSET_DB,
            SettingsStore(context).current.volumeOffsetDb,
            0.0001f,
        )
    }

    @Test
    fun offsetDoesNotDisturbOtherSettings() {
        val store = SettingsStore(context)
        store.setBufferSeconds(8)
        store.setVolumeOffset(2f)

        val reloaded = SettingsStore(context).current
        assertEquals(8, reloaded.bufferSeconds)
        assertEquals(2f, reloaded.volumeOffsetDb, 0.0001f)
        assertEquals(true, reloaded.normalizeVolume)
    }

    @Test
    fun switchingNormalizationKeepsTheOffset() {
        // 关掉再打开，用户拖好的整体音量不该被重置
        val store = SettingsStore(context)
        store.setVolumeOffset(-2.5f)
        store.setNormalizeVolume(false)
        store.setNormalizeVolume(true)

        assertEquals(-2.5f, store.current.volumeOffsetDb, 0.0001f)
    }
}
