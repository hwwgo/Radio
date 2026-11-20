package com.radio.simple

import android.content.Context
import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UriUtil
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.hls.HlsMediaSource
import androidx.media3.exoplayer.hls.playlist.HlsMediaPlaylist
import androidx.media3.exoplayer.hls.playlist.HlsPlaylistParser
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.test.core.app.ApplicationProvider
import com.radio.simple.playback.PlaybackEngine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayInputStream

/**
 * HLS 支持回归测试。
 *
 * 之前只依赖了 media3-exoplayer，没有 media3-exoplayer-hls，
 * 于是所有 .m3u8 直播源（国内电台基本全是）都会报「不是可识别的音频流」。
 * 这里直接问媒体源工厂：给一个 .m3u8 地址，到底产出什么 MediaSource。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class HlsSupportTest {

    private val context: Context
        get() = ApplicationProvider.getApplicationContext()

    private fun sourceFor(item: MediaItem) =
        PlaybackEngine.mediaSourceFactory(context).createMediaSource(item)

    @Test
    fun m3u8WithQueryIsRoutedToHls() {
        // 用户实际报的那个地址
        val item = MediaItem.fromUri("http://ngcdn001.cnr.cn/live/zgzs/index.m3u8?_res_tag_=audio")
        assertTrue(
            "带查询参数的 .m3u8 必须走 HLS，实际是 ${sourceFor(item)::class.java.simpleName}",
            sourceFor(item) is HlsMediaSource,
        )
    }

    @Test
    fun httpsM3u8IsRoutedToHls() {
        val item = MediaItem.fromUri("https://example.com/live/stream.m3u8")
        assertTrue(sourceFor(item) is HlsMediaSource)
    }

    @Test
    fun mimeTypeHintRoutesExtensionlessUrlToHls() {
        // 有些电台地址不带 .m3u8，靠 MIME 提示
        val item = MediaItem.Builder()
            .setUri("http://example.com/hls/live?channel=1")
            .setMimeType(MimeTypes.APPLICATION_M3U8)
            .build()
        assertTrue(sourceFor(item) is HlsMediaSource)
    }

    @Test
    fun mp3StreamStaysProgressive() {
        val item = MediaItem.fromUri("http://example.com/stream.mp3")
        assertTrue(
            "普通 mp3 流不该被当成 HLS",
            sourceFor(item) is ProgressiveMediaSource,
        )
    }

    /**
     * 用用户实际报的那个地址返回的真实清单，交给 Media3 的 HLS 解析器跑一遍。
     * 这一步才能说明「不是链接坏了，是之前客户端缺模块」。
     */
    @OptIn(UnstableApi::class)
    @Test
    fun realManifestFromCnrParses() {
        val manifest = """
            #EXTM3U
            #EXT-X-VERSION:3
            #EXT-X-MEDIA-SEQUENCE:95795
            #EXT-X-TARGETDURATION:10
            #EXTINF:10.005,
            95795.ts
            #EXTINF:10.006,
            95796.ts
            #EXTINF:10.005,
            95797.ts
            #EXTINF:10.005,
            95798.ts
            #EXTINF:10.006,
            95799.ts
            #EXTINF:10.005,
            95800.ts
        """.trimIndent()

        val baseUri = Uri.parse("http://ngcdn001.cnr.cn/live/zgzs/index.m3u8?_res_tag_=audio")
        val parsed = HlsPlaylistParser()
            .parse(baseUri, ByteArrayInputStream(manifest.toByteArray()))

        assertTrue("应解析成媒体清单，实际 ${parsed::class.java.simpleName}", parsed is HlsMediaPlaylist)
        val playlist = parsed as HlsMediaPlaylist
        assertEquals(6, playlist.segments.size)
        assertEquals(
            "http://ngcdn001.cnr.cn/live/zgzs/95795.ts",
            UriUtil.resolveToUri(baseUri.toString(), playlist.segments[0].url).toString(),
        )
    }
}
