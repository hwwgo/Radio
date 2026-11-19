package com.radio.simple

import androidx.media3.common.Player
import androidx.test.core.app.ApplicationProvider
import com.radio.simple.playback.PlaybackEngine
import com.radio.simple.playback.PlaybackService
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 播放链路的冒烟测试：服务能起来、播放器能用、缓冲参数合法、换参数时播放器会重建。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class PlaybackServiceTest {

    private val context: android.content.Context
        get() = ApplicationProvider.getApplicationContext()

    // 播放器是进程级单例，每个用例前后都要释放，否则会串到下一个用例的 Application 上
    @Before
    fun setUp() = PlaybackEngine.release()

    @After
    fun tearDown() = PlaybackEngine.release()

    @Test
    fun serviceOnCreateDoesNotThrow() {
        val service = Robolectric.buildService(PlaybackService::class.java).create().get()
        assertNotNull(service)
        service.onDestroy()
    }

    @Test
    fun engineReusesPlayerForSameBuffer() {
        val first = PlaybackEngine.player(context, 5)
        val second = PlaybackEngine.player(context, 5)
        assertSame(first, second)
        assertEquals(5, PlaybackEngine.bufferSeconds)
    }

    @Test
    fun engineRebuildsPlayerWhenBufferChanges() {
        val before = PlaybackEngine.player(context, 6)
        val after = PlaybackEngine.player(context, 7)

        assertNotSame(before, after)
        assertSame(after, PlaybackEngine.current())
        assertEquals(7, PlaybackEngine.bufferSeconds)
    }

    @Test
    fun everyBufferStepBuildsAPlayablePlayer() {
        for (seconds in intArrayOf(1, 3, 12, 30)) {
            val player = PlaybackEngine.player(context, seconds)
            assertEquals(seconds, PlaybackEngine.bufferSeconds)
            assertEquals(Player.STATE_IDLE, player.playbackState)
        }
    }
}
