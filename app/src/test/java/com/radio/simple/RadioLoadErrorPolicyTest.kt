package com.radio.simple

import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.HttpDataSource
import com.radio.simple.playback.RadioLoadErrorPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.IOException

/**
 * 重试策略：哪些错误值得等，等多久，什么时候该放弃。
 *
 * 背景：喜马拉雅这类 HLS 直播流，CDN 会在 8～10 分钟时主动关掉长连接。
 * 默认策略只重试 3 次、3 秒内就放弃，于是就变成「播着播着停了」。
 */
@OptIn(UnstableApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class RadioLoadErrorPolicyTest {

    private val policy = RadioLoadErrorPolicy()

    @Test
    fun forbiddenIsNeverRetried() {
        // 403 = 防盗链 / token 过期，同一个地址再试也没用
        assertEquals(C.TIME_UNSET, retry(403, 1))
        assertEquals(C.TIME_UNSET, retry(403, 2))
    }

    @Test
    fun notFoundGetsTwoChances() {
        // 404 可能是 HLS 分片刚刷新、还没上传完
        assertEquals(2_000L, retry(404, 1))
        assertEquals(4_000L, retry(404, 2))
        assertEquals(C.TIME_UNSET, retry(404, 3))
        assertEquals(C.TIME_UNSET, retry(410, 3))
    }

    @Test
    fun networkErrorsBackOffAndGiveUpAfterEight() {
        assertEquals(2_000L, retry(null, 1))
        assertEquals(15_000L, retry(null, 8))
        assertEquals("第 9 次不再试", C.TIME_UNSET, retry(null, 9))
    }

    @Test
    fun delayIsCapped() {
        // 就算 Loader 报了很多次，间隔也不会无限拉长
        assertEquals(RadioLoadErrorPolicy.MAX_DELAY_MS, retry(503, 8))
    }

    @Test
    fun serverErrorsAreTreatedLikeNetworkErrors() {
        assertEquals(2_000L, retry(500, 1))
        assertEquals(6_000L, retry(503, 3))
    }

    @Test
    fun responseCodeIsFoundDeepInTheCauseChain() {
        // 实际异常的嵌套：HttpDataSourceException -> ConnectException
        val inner = HttpDataSource.InvalidResponseCodeException(
            /* responseCode = */ 404,
            /* responseMessage = */ "Not Found",
            /* cause = */ IOException("read failed"),
            /* headerFields = */ mapOf("Content-Type" to listOf("text/plain")),
            /* dataSpec = */ DataSpec(Uri.parse("https://example.com/a.ts")),
            /* responseBody = */ ByteArray(0),
        )
        val outer = IOException("wrapped", inner)

        assertEquals(404, RadioLoadErrorPolicy.responseCodeOf(outer) ?: -1)
        assertNull(RadioLoadErrorPolicy.responseCodeOf(IOException("no http here")))
        assertNull(RadioLoadErrorPolicy.responseCodeOf(null))
    }

    /** 直接测纯逻辑，不经过 Loader */
    private fun retry(responseCode: Int?, errorCount: Int): Long =
        RadioLoadErrorPolicy.retryDelayMs(responseCode, errorCount)
}
