package com.radio.simple

import android.content.Context
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.test.core.app.ApplicationProvider
import com.radio.simple.playback.PlaybackEngine
import com.radio.simple.playback.PlayedTime
import com.radio.simple.playback.SessionPlayer
import java.time.Duration
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowSystemClock

/**
 * 灵动岛 / 锁屏 / 通知栏能显示时长，靠的是 MediaSession 对外报的 position 与 duration。
 * 这里验证包装层真的把「直播」改成了「有时长的点播」。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class SessionPlayerTest {

    private val app = ApplicationProvider.getApplicationContext<Context>()

    @Before
    fun setUp() {
        // PlaybackEngine 是进程单例，测试之间要清干净，否则上一个用例的电台还挂着
        PlaybackEngine.current()?.let {
            it.stop()
            it.clearMediaItems()
        }
        PlayedTime.reset()
    }

    @After
    fun tearDown() {
        PlayedTime.reset()
        // 播放器是进程单例：不释放就会带着这个 Application 的 receiver 串到下一个测试类，
        // ExoPlayer 的 playback 线程在 unregisterReceiver 时抛 "Receiver not registered" 后
        // 会直接死掉，下一次 release() 就永远等在那儿。
        PlaybackEngine.release()
    }

    private fun playerWithStation(): Player {
        val p = PlaybackEngine.player(app, 3)
        val item = MediaItem.Builder()
            .setUri("https://example.com/live.m3u8")
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle("测试电台")
                    .setArtist("网络电台")
                    .build()
            )
            .build()
        p.setMediaItem(item)
        return p
    }

    /** 关键一条：只要还被认成直播，Media3 就把 position 置成 -1，时长永远出不来 */
    @Test
    fun sessionDoesNotSeeADynamicLiveStream() {
        val wrapper = SessionPlayer(playerWithStation())
        assertFalse(wrapper.isCurrentMediaItemLive())
        assertFalse(wrapper.isCurrentMediaItemDynamic())
    }

    @Test
    fun positionFollowsListenedTime() {
        val wrapper = SessionPlayer(playerWithStation())
        PlayedTime.start()
        ShadowSystemClock.advanceBy(Duration.ofSeconds(95))

        assertEquals(95_000L, wrapper.currentPosition)
        assertEquals(95_000L, wrapper.bufferedPosition)
    }

    @Test
    fun durationIsAlwaysAheadOfPosition() {
        val wrapper = SessionPlayer(playerWithStation())

        PlayedTime.start()
        assertEquals(3_600_000L, wrapper.duration)

        // 跨过整点：这一段结束，进入下一段，进度条不会「满格卡死」
        ShadowSystemClock.advanceBy(Duration.ofMinutes(61))
        assertEquals(7_200_000L, wrapper.duration)
        assertTrue(wrapper.duration > wrapper.currentPosition)
    }

    @Test
    fun metadataCarriesTheDurationForSystemUi() {
        val wrapper = SessionPlayer(playerWithStation())
        PlayedTime.start()
        ShadowSystemClock.advanceBy(Duration.ofSeconds(30))

        assertEquals(
            "系统 UI 的总时长是从 metadata 的 duration 读的，只改 getDuration 不够",
            3_600_000L,
            wrapper.mediaMetadata.durationMs,
        )
        assertEquals("包装层不能把电台名弄丢", "网络电台", wrapper.mediaMetadata.artist)
    }

    @Test
    fun noStationMeansNoPosition() {
        val wrapper = SessionPlayer(PlaybackEngine.player(app, 3))
        assertEquals(0L, wrapper.currentPosition)
        assertTrue(wrapper.duration < 0)
    }
}
