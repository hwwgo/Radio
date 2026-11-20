package com.radio.simple

import android.app.Application
import android.os.Looper
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import androidx.test.core.app.ApplicationProvider
import com.radio.simple.playback.PlaybackEngine
import com.radio.simple.playback.PlayedTime
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowSystemClock
import java.time.Duration

/**
 * 暂停久了再按播放，必须重新回源。
 *
 * 原来直接 `play()`：缓冲区里还是暂停那一刻的数据，先出声几秒、断流、再重连，
 * 内容还接不上。这里守住两条线 —— 长暂停要重拉，且重拉不能把收听时长清零。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class LiveResumeTest {

    private lateinit var app: Application

    @Before
    fun setUp() {
        app = ApplicationProvider.getApplicationContext()
        PlaybackEngine.release()
        PlayedTime.reset()
    }

    @After
    fun tearDown() {
        PlayedTime.reset()
        PlaybackEngine.release()
    }

    /**
     * 任何一次「起播」都必须重新装载媒体项。
     *
     * 界面上列表行尾和底部播放键现在都是「停止」，恢复时不能再区分
     * 「停了多久」—— 只要不是正在播，就重新回源。
     */
    @Test
    fun everyResumeReloadsTheStream() {
        val p = PlaybackEngine.player(app, 3)
        var timelineChanges = 0
        p.addListener(object : Player.Listener {
            override fun onTimelineChanged(timeline: Timeline, reason: Int) {
                timelineChanges++
            }
        })

        p.setMediaItem(MediaItem.fromUri("http://127.0.0.1:1/live.m3u8"))
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(300))

        val afterFirstLoad = timelineChanges
        PlaybackEngine.resumeFromPause()
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(300))
        assertTrue("第一次起播要重新装载", timelineChanges > afterFirstLoad)

        // 再停一次、再起一次，行为必须一样（不能有「短暂停就续播」的分支）
        val afterSecondLoad = timelineChanges
        p.stop()
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(300))
        PlaybackEngine.resumeFromPause()
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(300))
        assertTrue("第二次起播同样要重新装载", timelineChanges > afterSecondLoad)
    }

    /**
     * 停止播放（媒体项还在）不等于结束收听：时长要留着，
     * 否则用户点一下停止再点播放，底部条又从 00:00 开始。
     */
    @Test
    fun stopKeepsTimeUntilTheStationIsReallyGone() {
        val p = PlaybackEngine.player(app, 3)
        p.setMediaItem(MediaItem.fromUri("http://127.0.0.1:1/live.mp3"))
        p.prepare()
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(300))

        PlayedTime.start()
        ShadowSystemClock.advanceBy(Duration.ofSeconds(60))
        PlayedTime.pause()

        p.stop()
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(300))
        assertTrue("刚 stop 不该清时长", PlayedTime.elapsedMs() >= 60_000)

        p.clearMediaItems()
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(300))
        assertEquals("电台被清掉才算这次收听结束", 0L, PlayedTime.elapsedMs())
    }

    /** 语音输入抢麦：停播即结束，时长必须归零 */
    @Test
    fun voiceInputStopResetsTheClock() {
        val p = PlaybackEngine.player(app, 3)
        p.setMediaItem(MediaItem.fromUri("http://127.0.0.1:1/live.mp3"))
        PlayedTime.start()
        ShadowSystemClock.advanceBy(Duration.ofSeconds(45))

        PlaybackEngine.stopAndReset()
        assertEquals(0L, PlayedTime.elapsedMs())
    }

    /**
     * 列表行尾按钮和底部按钮必须走同一条路。
     *
     * 停止过的播放器（`stop()` / 语音停播 / 服务停播）位置不可信：只 `prepare()`
     * 会从旧位置续，直播就会接着放老内容。这里守住「起播一定重新装载媒体项」。
     */
    @Test
    fun resumingFromIdleReloadsTheMediaItem() {
        val p = PlaybackEngine.player(app, 3)
        var timelineChanges = 0
        p.addListener(object : Player.Listener {
            override fun onTimelineChanged(timeline: Timeline, reason: Int) {
                timelineChanges++
            }
        })

        p.setMediaItem(MediaItem.fromUri("http://127.0.0.1:1/live.m3u8"))
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(300))
        val before = timelineChanges
        assertTrue("装载媒体项本身就会改 timeline", before > 0)

        // 不 prepare，保持 IDLE，模拟「停止后再点播放」
        PlaybackEngine.resumeFromPause()
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(300))

        assertTrue("IDLE 起播必须重新 setMediaItem，不能只 prepare", timelineChanges > before)
        assertTrue("按播放后要真的进入播放态", p.playWhenReady)
    }
}
