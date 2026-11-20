package com.radio.simple

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.radio.simple.playback.LoudnessMeter
import com.radio.simple.playback.PlaybackEngine
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 响度计真的挂进播放器了吗。
 *
 * 光测 `LoudnessMeter` 本身不够 —— 它要是没被塞进音频链，测出来的数再准也没用。
 * 这几条用真实的 `PlaybackEngine` 构造路径验证：播放器能带着自定义 AudioSink 起来，
 * 而且换播放器时响度计跟着换新的（不能拿上一台的累计值继续用）。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class LoudnessWiringTest {

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    @Before
    fun setUp() = PlaybackEngine.release()

    @After
    fun tearDown() = PlaybackEngine.release()

    @Test
    fun playerBuildsWithMeterAttached() {
        val player = PlaybackEngine.player(context, 3)
        assertNotNull("带自定义 AudioSink 的播放器应该能正常创建", player)
        assertNotNull("响度计应该跟着播放器一起就位", PlaybackEngine.loudness())
    }

    @Test
    fun samePlayerKeepsSameMeter() {
        PlaybackEngine.player(context, 5)
        val first = PlaybackEngine.loudness()
        PlaybackEngine.player(context, 5)
        assertSame("没重建播放器就不该换响度计", first, PlaybackEngine.loudness())
    }

    @Test
    fun rebuildingPlayerCreatesFreshMeter() {
        PlaybackEngine.player(context, 3)
        val first: LoudnessMeter = PlaybackEngine.loudness()!!
        PlaybackEngine.player(context, 8)
        val second: LoudnessMeter = PlaybackEngine.loudness()!!
        assertNotSame("缓冲参数变了要重建播放器，响度计也必须换新的", first, second)
    }

    @Test
    fun releaseClearsMeter() {
        PlaybackEngine.player(context, 3)
        PlaybackEngine.release()
        assertNull("释放之后不能再交出一个已经死掉的响度计", PlaybackEngine.loudness())
    }

    @Test
    fun gainStageIsAttachedToTheSamePlayer() {
        PlaybackEngine.player(context, 3)
        assertNotNull("增益级应该跟着播放器一起就位", PlaybackEngine.gain())
        assertSame("没重建播放器就不该换增益级", PlaybackEngine.gain(), PlaybackEngine.gain())
    }

    @Test
    fun rebuildingPlayerCreatesFreshGain() {
        PlaybackEngine.player(context, 3)
        val first = PlaybackEngine.gain()
        PlaybackEngine.player(context, 8)
        assertNotSame("重建播放器后增益级必须换新的（旧的可能还挂着 0.4 的增益）", first, PlaybackEngine.gain())
    }

    @Test
    fun gainSurvivesRoundTripThroughPlayer() {
        PlaybackEngine.player(context, 3)
        PlaybackEngine.gain()!!.setGain(0.42f)
        assertEquals(0.42f, PlaybackEngine.gain()!!.gain(), 0.0001f)
    }
}
