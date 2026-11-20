package com.radio.simple

import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import com.radio.simple.playback.AudioQuality
import com.radio.simple.ui.formatElapsed
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 底部播放条上那两行小字的数据来源：
 * 音质怎么拼、时长怎么格式化。都是纯计算，出错也不会崩，
 * 但写错了用户看到的就是「0 kbps」这种东西，所以单独测一下。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class PlaybackInfoTest {

    private fun format(
        mime: String?,
        codecs: String? = null,
        averageBitrate: Int = Format.NO_VALUE,
        peakBitrate: Int = Format.NO_VALUE,
        sampleRate: Int = Format.NO_VALUE,
    ) = Format.Builder()
        .setSampleMimeType(mime)
        .setCodecs(codecs)
        .setAverageBitrate(averageBitrate)
        .setPeakBitrate(peakBitrate)
        .setSampleRate(sampleRate)
        .build()

    @Test
    fun aacWithBitrateAndSampleRate() {
        val text = AudioQuality.describe(
            format(
                mime = MimeTypes.AUDIO_AAC,
                codecs = "mp4a.40.2",
                averageBitrate = 128_000,
                sampleRate = 44_100,
            )
        )
        assertEquals("AAC-LC · 128 kbps · 44.1 kHz", text)
    }

    @Test
    fun heAacIsNamedSeparately() {
        val text = AudioQuality.describe(
            format(mime = MimeTypes.AUDIO_AAC, codecs = "mp4a.40.5", averageBitrate = 64_000)
        )
        assertEquals("HE-AAC · 64 kbps", text)
    }

    @Test
    fun mp3WithoutBitrateStillShowsSampleRate() {
        val text = AudioQuality.describe(
            format(mime = MimeTypes.AUDIO_MPEG, sampleRate = 48_000)
        )
        assertEquals("MP3 · 48 kHz", text)
    }

    @Test
    fun peakBitrateIsNotShownAsTheRealOne() {
        // 峰值码率只是个上限，拿它当「音质」会虚高，宁可不显示
        val text = AudioQuality.describe(
            format(mime = MimeTypes.AUDIO_AAC, averageBitrate = 0, peakBitrate = 320_000)
        )
        assertEquals("AAC", text)
    }

    @Test
    fun nonsenseRatesAreSkipped() {
        val text = AudioQuality.describe(
            format(mime = MimeTypes.AUDIO_OPUS, averageBitrate = 0, sampleRate = 0)
        )
        assertEquals("Opus", text)
    }

    @Test
    fun unknownMimeGivesNothingInsteadOfGarbage() {
        assertNull(AudioQuality.describe(format(mime = "application/octet-stream")))
        assertNull(AudioQuality.describe(null))
    }

    @Test
    fun elapsedUnderOneHour() {
        assertEquals("00:00", formatElapsed(0))
        assertEquals("00:09", formatElapsed(9_400))
        assertEquals("01:05", formatElapsed(65_000))
        assertEquals("59:59", formatElapsed(3_599_000))
    }

    @Test
    fun elapsedOverOneHour() {
        assertEquals("1:00:00", formatElapsed(3_600_000))
        assertEquals("1:02:03", formatElapsed(3_723_000))
    }

    @Test
    fun elapsedNeverNegative() {
        assertEquals("00:00", formatElapsed(-5_000))
    }
}
