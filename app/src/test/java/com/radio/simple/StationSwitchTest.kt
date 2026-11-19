package com.radio.simple

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.radio.simple.data.StationStore
import com.radio.simple.playback.PlaybackEngine
import com.radio.simple.ui.PlayerViewModel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNotSame
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 电台切换的核心回归测试：
 * 点不同的电台，播放器里的媒体项必须真的换成对应的那个。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class StationSwitchTest {

    private lateinit var app: Application

    @Before
    fun setUp() {
        app = ApplicationProvider.getApplicationContext()
        PlaybackEngine.release()
        app.getSharedPreferences("radio_stations", 0).edit().clear().commit()
        app.getSharedPreferences("radio_settings", 0).edit().clear().commit()
    }

    @After
    fun tearDown() {
        PlaybackEngine.release()
    }

    private fun urlOfCurrent(): String? =
        PlaybackEngine.current()?.currentMediaItem?.localConfiguration?.uri?.toString()

    @Test
    fun tappingSecondStationSwitchesMediaItem() {
        val store = StationStore(app)
        val first = store.add("第一台", "http://first.example.com/a.mp3")
        val second = store.add("第二台", "http://second.example.com/b.mp3")

        val vm = PlayerViewModel(app)

        vm.playStation(first)
        val p = PlaybackEngine.current()
        assertNotNull(p)
        assertEquals(first.id, p!!.currentMediaItem?.mediaId)
        assertEquals("http://first.example.com/a.mp3", urlOfCurrent())

        vm.playStation(second)
        assertEquals(second.id, PlaybackEngine.current()?.currentMediaItem?.mediaId)
        assertEquals("http://second.example.com/b.mp3", urlOfCurrent())
        assertEquals(second.id, vm.currentId)
    }

    @Test
    fun everyStationPointsToItsOwnUrl() {
        val store = StationStore(app)
        val list = (1..5).map { store.add("电台$it", "http://radio$it.example.com/live.mp3") }
        val vm = PlayerViewModel(app)

        list.forEach { station ->
            vm.playStation(station)
            assertEquals(station.id, PlaybackEngine.current()?.currentMediaItem?.mediaId)
            assertEquals(station.url, urlOfCurrent())
        }
    }

    @Test
    fun rowButtonPausesAndResumesCurrentStation() {
        val store = StationStore(app)
        val a = store.add("A", "http://a.example.com/1.mp3")
        val b = store.add("B", "http://b.example.com/2.mp3")
        val vm = PlayerViewModel(app)

        vm.playStation(a)
       // 行尾按钮点一次 = 暂停（这里只验证不会把电台切走）
        vm.toggleStation(a)
        assertEquals(a.id, PlaybackEngine.current()?.currentMediaItem?.mediaId)
        assertEquals(a.url, urlOfCurrent())

        // 对别的电台按行尾按钮 = 切过去播
        vm.toggleStation(b)
        assertEquals(b.id, PlaybackEngine.current()?.currentMediaItem?.mediaId)
        assertEquals(b.url, urlOfCurrent())
    }

    @Test
    fun survivesPlayerBeingReleased() {
        val store = StationStore(app)
        val a = store.add("A", "http://a.example.com/1.mp3")
        val b = store.add("B", "http://b.example.com/2.mp3")
        val vm = PlayerViewModel(app)

        vm.playStation(a)
        val old = PlaybackEngine.current()

        // 模拟播放器被重建（缓冲时长变化 / 服务重启）后旧实例已释放
        PlaybackEngine.release()

        vm.playStation(b)
        val fresh = PlaybackEngine.current()
        assertNotNull(fresh)
        assertNotSame(old, fresh)
        assertEquals(b.id, fresh?.currentMediaItem?.mediaId)
        assertEquals(b.url, urlOfCurrent())
    }

    @Test
    fun playerIdStaysUniqueWhenVmRecreated() {
        val store = StationStore(app)
        val a = store.add("A", "http://a.example.com/1.mp3")
        val b = store.add("B", "http://b.example.com/2.mp3")

        PlayerViewModel(app).playStation(a)
        // 模拟旋转屏幕 / 重进界面：换一个 ViewModel，播放器不该被换掉
        val vm2 = PlayerViewModel(app)
        val playerBefore = PlaybackEngine.current()
        vm2.playStation(b)

        assertEquals(playerBefore, PlaybackEngine.current())
        assertEquals(b.id, PlaybackEngine.current()?.currentMediaItem?.mediaId)
    }
}
