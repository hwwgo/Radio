package com.radio.simple

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.radio.simple.data.StationStore
import com.radio.simple.data.normalizeStreamUrl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class StationStoreTest {

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    @Test
    fun urlIsNormalized() {
        assertEquals("http://example.com/a.mp3", normalizeStreamUrl("example.com/a.mp3"))
        assertEquals("http://192.168.1.5:8000/stream", normalizeStreamUrl("192.168.1.5:8000/stream"))
        assertEquals("https://example.com/live.m3u8", normalizeStreamUrl("  https://example.com/live.m3u8  "))
        assertEquals("rtsp://example.com/stream", normalizeStreamUrl("rtsp://example.com/stream"))

        assertNull(normalizeStreamUrl(""))
        assertNull(normalizeStreamUrl("   "))
        assertNull(normalizeStreamUrl("ftp://example.com/a.mp3"))
        assertNull(normalizeStreamUrl("不是个地址"))
        assertNull(normalizeStreamUrl("http://"))
    }

    @Test
    fun addUpdateRemoveAndPersist() {
        context.getSharedPreferences("radio_stations", Context.MODE_PRIVATE)
            .edit().clear().commit()

        val store = StationStore(context)
        assertTrue(store.stations.value.isEmpty())

        val first = store.add("城市音乐", "http://a.example.com/1.mp3")
        val second = store.add("深夜电台", "http://b.example.com/2.mp3")
        assertEquals(2, store.stations.value.size)
        assertEquals(listOf("城市音乐", "深夜电台"), store.stations.value.map { it.name })

        store.update(first.id, "城市音乐 FM", "http://a.example.com/9.mp3")
        val updated = store.stations.value.first { it.id == first.id }
        assertEquals("城市音乐 FM", updated.name)
        assertEquals("http://a.example.com/9.mp3", updated.url)

        store.remove(second.id)
        assertEquals(1, store.stations.value.size)

        // 换一个实例重新读，验证真的写进磁盘了
        val reopened = StationStore(context)
        assertEquals(1, reopened.stations.value.size)
        assertEquals("城市音乐 FM", reopened.stations.value.first().name)
    }

    @Test
    fun reorderFollowsGivenIdsAndKeepsEverything() {
        context.getSharedPreferences("radio_stations", Context.MODE_PRIVATE)
            .edit().clear().commit()

        val store = StationStore(context)
        val a = store.add("A", "http://a.example.com/1.mp3")
        val b = store.add("B", "http://b.example.com/2.mp3")
        val c = store.add("C", "http://c.example.com/3.mp3")

        store.reorder(listOf(c.id, a.id, b.id))
        assertEquals(listOf("C", "A", "B"), store.stations.value.map { it.name })

        // 落盘了：换个实例还是新顺序
        assertEquals(listOf("C", "A", "B"), StationStore(context).stations.value.map { it.name })

        // 漏掉的电台不能被丢掉，补在最后
        store.reorder(listOf(b.id))
        assertEquals(listOf("B", "C", "A"), store.stations.value.map { it.name })
        assertEquals(3, store.stations.value.size)

        // 不认识的 id 直接忽略，列表不变
        store.reorder(listOf("不存在", a.id, b.id, c.id))
        assertEquals(listOf("A", "B", "C"), store.stations.value.map { it.name })
    }

    @Test
    fun loudnessSurvivesRestartAndDiesWithTheUrl() {
        context.getSharedPreferences("radio_stations", Context.MODE_PRIVATE)
            .edit().clear().commit()

        val store = StationStore(context)
        val a = store.add("A", "http://a.example.com/1.mp3")
        val b = store.add("B", "http://b.example.com/2.mp3")

        store.setLoudness(a.id, -13.4f, -6.2f)
        val saved = store.stations.value.first { it.id == a.id }
        assertEquals(-13.4f, saved.loudnessLufs!!, 0.001f)
        // 峰值必须一起来，否则这个台只能压不能抬
        assertEquals(-6.2f, saved.peakDbfs!!, 0.001f)

        // 没测过的台保持 null，不该被顺手写成 0
        val untouched = store.stations.value.first { it.id == b.id }
        assertNull(untouched.loudnessLufs)
        assertNull(untouched.peakDbfs)

        // 换个实例重新读，档案还在
        val reopened = StationStore(context)
        val reloaded = reopened.stations.value.first { it.id == a.id }
        assertEquals(-13.4f, reloaded.loudnessLufs!!, 0.001f)
        assertEquals(-6.2f, reloaded.peakDbfs!!, 0.001f)

        // 改了链接就是另一个流，旧档案必须作废，否则会用错误的增益去播
        store.update(a.id, "A", "http://a.example.com/moved.mp3")
        val moved = store.stations.value.first { it.id == a.id }
        assertNull(moved.loudnessLufs)
        assertNull(moved.peakDbfs)

        // 只改名字要保留档案
        store.setLoudness(a.id, -18f, -8f)
        store.update(a.id, "A2", "http://a.example.com/moved.mp3")
        val renamed = store.stations.value.first { it.id == a.id }
        assertEquals(-18f, renamed.loudnessLufs!!, 0.001f)
        assertEquals(-8f, renamed.peakDbfs!!, 0.001f)
    }

    @Test
    fun loudnessFromOlderVersionLoadsWithoutPeak() {
        // 1.9 存下的 JSON 只有 lufs 没有 peak，必须能正常读出来（peak 为 null），
        // 否则升级一次用户的所有档案就全丢了
        context.getSharedPreferences("radio_stations", Context.MODE_PRIVATE)
            .edit().clear().commit()

        val legacy = """[{"id":"x1","name":"老数据","url":"http://a.example.com/1.mp3","lufs":-15.5}]"""
        context.getSharedPreferences("radio_stations", Context.MODE_PRIVATE)
            .edit().putString("stations_json", legacy).commit()

        val store = StationStore(context)
        val station = store.stations.value.first()
        assertEquals(-15.5f, station.loudnessLufs!!, 0.001f)
        assertNull(station.peakDbfs)
    }
}
