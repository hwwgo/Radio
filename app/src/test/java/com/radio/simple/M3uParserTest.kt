package com.radio.simple

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.radio.simple.data.M3uParser
import com.radio.simple.data.StationStore
import com.radio.simple.data.streamKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * M3U 解析 + 导入落库。解析部分是纯文本逻辑，顺带用 Robolectric 跑，
 * 因为导入去重会用到 android.net.Uri。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class M3uParserTest {

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    @Before
    fun clearStorage() {
        context.getSharedPreferences("radio_stations", Context.MODE_PRIVATE)
            .edit().clear().commit()
    }

    @Test
    fun readsExtinfNameAndPlainUrl() {
        val playlist = """
            #EXTM3U
            #EXTINF:-1 tvg-id="a" tvg-logo="http://x/1.png" group-title="音乐",城市音乐广播
            http://stream.example.com/city.mp3
            #EXTINF:-1,深夜电台
            http://stream.example.com/night
            http://stream.example.com/bare.aac
        """.trimIndent()

        val result = M3uParser.parse(playlist)

        assertFalse(result.isHlsPlaylist)
        assertEquals(3, result.entries.size)
        assertEquals("城市音乐广播", result.entries[0].name)
        assertEquals("http://stream.example.com/city.mp3", result.entries[0].url)
        assertEquals("深夜电台", result.entries[1].name)
        // 没有 #EXTINF 的行，用链接最后一段当名字
        assertEquals("bare", result.entries[2].name)
    }

    @Test
    fun handlesQuotedCommasAndWindowsLineEndings() {
        val playlist = "#EXTM3U\r\n#EXTINF:-1 tvg-name=\"Jazz, Blues\" group-title=\"音乐\",Jazz FM, 88.1\r\nhttp://a.example.com/jazz\r\n"

        val result = M3uParser.parse(playlist)

        assertEquals(1, result.entries.size)
        assertEquals("Jazz FM, 88.1", result.entries.first().name)
    }

    @Test
    fun detectsHlsMediaPlaylist() {
        val hls = """
            #EXTM3U
            #EXT-X-VERSION:3
            #EXT-X-TARGETDURATION:10
            #EXTINF:9.9,
            segment1.aac
        """.trimIndent()

        assertTrue(M3uParser.parse(hls).isHlsPlaylist)
        assertFalse(M3uParser.parse("#EXTM3U\n#EXTINF:-1,A\nhttp://a.example.com/a").isHlsPlaylist)
    }

    @Test
    fun ignoresBomAndComments() {
        val playlist = "\uFEFF#EXTM3U\n# 这是注释\n#EXTGRP:新闻\n#EXTINF:-1,新闻台\nhttp://a.example.com/news.mp3\n"

        val result = M3uParser.parse(playlist)

        assertEquals(1, result.entries.size)
        assertEquals("新闻台", result.entries.first().name)
    }

    @Test
    fun streamKeyIgnoresCaseAndDefaultPort() {
        assertEquals(
            streamKey("http://Example.com:80/live.mp3"),
            streamKey("http://example.com/live.mp3"),
        )
        assertEquals(
            streamKey("HTTPS://example.com/a"),
            streamKey("https://example.com/a"),
        )
    }

    @Test
    fun importSkipsDuplicatesAndInvalidLinks() {
        val playlist = """
            #EXTM3U
            #EXTINF:-1,音乐台
            http://a.example.com/live.mp3
            #EXTINF:-1,音乐台（重复）
            http://A.example.com/live.mp3
            #EXTINF:-1,深夜台
            http://b.example.com/night.mp3
            #EXTINF:-1,坏链接
            ftp://c.example.com/x.mp3
        """.trimIndent()

        val store = StationStore(context)
        store.add("已有电台", "http://b.example.com/night.mp3")

        val outcome = store.addAll(M3uParser.parse(playlist).entries)

        assertEquals(1, outcome.added)
        assertEquals(2, outcome.duplicated) // 文件内部的重复 + 和已有列表重复
        assertEquals(1, outcome.invalid)
        assertEquals(2, store.stations.value.size)

        // 重新读一份，确认真的落盘了
        val reopened = StationStore(context)
        assertEquals(listOf("已有电台", "音乐台"), reopened.stations.value.map { it.name })
    }
}
