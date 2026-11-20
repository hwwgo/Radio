package com.radio.simple

import android.app.Application
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import com.radio.simple.playback.PlaybackEngine
import com.radio.simple.playback.PlayedTime
import com.radio.simple.ui.PlayerViewModel
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
 * 播放时长必须是「进程级」的：按返回键销毁 Activity 后，胶囊点回来不能从 00:00 重来。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class PlayedTimeTest {

    private lateinit var app: Application

    @Before
    fun setUp() {
        app = ApplicationProvider.getApplicationContext()
        PlaybackEngine.release()
        PlayedTime.reset()
        app.getSharedPreferences("radio_stations", 0).edit().clear().commit()
        app.getSharedPreferences("radio_settings", 0).edit().clear().commit()
    }

    @After
    fun tearDown() {
        PlayedTime.reset()
        PlaybackEngine.release()
    }

    @Test
    fun onlyPlayingTimeCounts() {
        PlayedTime.start()
        ShadowSystemClock.advanceBy(Duration.ofSeconds(30))

        PlayedTime.pause()
        // 暂停的 5 分钟不算
        ShadowSystemClock.advanceBy(Duration.ofMinutes(5))
        assertEquals(30_000L, PlayedTime.elapsedMs())

        // 继续播，接着累加
        PlayedTime.start()
        ShadowSystemClock.advanceBy(Duration.ofSeconds(10))
        assertEquals(40_000L, PlayedTime.elapsedMs())
    }

    @Test
    fun repeatedStartDoesNotRestartTheClock() {
        PlayedTime.start()
        ShadowSystemClock.advanceBy(Duration.ofSeconds(20))
        // 多个 ViewModel / 多次状态回调都会调 start，不能把起点重置到现在
        PlayedTime.start()
        PlayedTime.start()
        ShadowSystemClock.advanceBy(Duration.ofSeconds(10))
        assertEquals(30_000L, PlayedTime.elapsedMs())
    }

    @Test
    fun resetClearsEverything() {
        PlayedTime.start()
        ShadowSystemClock.advanceBy(Duration.ofSeconds(50))
        PlayedTime.reset()
        assertEquals(0L, PlayedTime.elapsedMs())
    }

    /**
     * 真实场景：播放中按返回键 → Activity + ViewModel 销毁 → 胶囊重新打开 → 新 ViewModel。
     * 新界面接上来的必须还是原来那段时长。
     */
    @Test
    fun newViewModelShowsTheSameElapsedTime() {
        val first = PlayerViewModel(app)
        PlayedTime.start()
        ShadowSystemClock.advanceBy(Duration.ofSeconds(95))
        PlayedTime.pause()

        // 让第一个界面的 500ms 轮询跑一次
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(600))
        assertEquals(95_000L, first.elapsedMs)

        // 模拟 Activity 被销毁后又从胶囊打开：全新的 ViewModel
        val second = PlayerViewModel(app)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(600))

        assertEquals(95_000L, second.elapsedMs)
        assertTrue("重建后不该回到 0", second.elapsedMs > 60_000L)
    }

    /** 暂停状态重建也一样，显示暂停时的累计值 */
    @Test
    fun pausedPlaybackKeepsItsTimeAfterRecreation() {
        PlayedTime.start()
        ShadowSystemClock.advanceBy(Duration.ofSeconds(42))
        PlayedTime.pause()

        val vm = PlayerViewModel(app)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(600))
        assertEquals(42_000L, vm.elapsedMs)
    }
}
