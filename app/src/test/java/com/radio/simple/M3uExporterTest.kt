package com.radio.simple

import com.radio.simple.data.M3uExporter
import com.radio.simple.data.M3uParser
import com.radio.simple.data.Station
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 导出的文本要能被自己的解析器原样读回来 —— 这是备份 / 迁移能不能用的底线。
 */
class M3uExporterTest {

    private fun station(name: String, url: String) =
        Station(id = name, name = name, url = url)

    @Test
    fun writesExtm3uHeaderAndExtinf() {
        val text = M3uExporter.build(
            listOf(
                station("中国之声", "http://ngcdn001.cnr.cn/live/zgzs/index.m3u8?_res_tag_=audio"),
                station("城市音乐", "http://example.com/live.mp3"),
            )
        )

        assertEquals(
            """
            #EXTM3U
            #EXTINF:-1,中国之声
            http://ngcdn001.cnr.cn/live/zgzs/index.m3u8?_res_tag_=audio
            #EXTINF:-1,城市音乐
            http://example.com/live.mp3
            """.trimIndent() + "\n",
            text,
        )
    }

    @Test
    fun roundTripKeepsNamesAndUrls() {
        val original = listOf(
            station("中国之声", "http://ngcdn001.cnr.cn/live/zgzs/index.m3u8"),
            station("Jazz, Blues", "http://example.com/jazz.mp3"),
            station("县城 FM 88.7", "http://192.168.1.9:8000/stream"),
        )

        val parsed = M3uParser.parse(M3uExporter.build(original))

        assertTrue("不该被当成 HLS 清单", !parsed.isHlsPlaylist)
        assertEquals(original.map { it.name }, parsed.entries.map { it.name })
        assertEquals(original.map { it.url }, parsed.entries.map { it.url })
    }

    @Test
    fun emptyListStillWritesHeader() {
        assertEquals("#EXTM3U\n", M3uExporter.build(emptyList()))
    }

    @Test
    fun lineBreaksInNameAreFlattened() {
        val text = M3uExporter.build(listOf(station("深夜\n电台\r\nFM", "http://example.com/a.mp3")))

        // 名字里的换行会把一条记录拆成两条，必须压成空格
        val extinfLines = text.lines().filter { it.startsWith("#EXTINF") }
        assertEquals(1, extinfLines.size)
        assertEquals("#EXTINF:-1,深夜 电台 FM", extinfLines.single())
    }
}
