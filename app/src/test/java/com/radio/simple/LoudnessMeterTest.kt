package com.radio.simple

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import com.radio.simple.playback.LoudnessMeter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * 响度计的正确性验证。
 *
 * 用的是 BS.1770 的标准测试信号：1kHz 正弦波。
 * 标准规定 −20 dBFS 的单声道 1kHz 正弦 = −23.0 LUFS，
 * 立体声两个通道都给同样电平则是 −20.0 LUFS（能量翻倍 +3dB）。
 * 如果 K 加权系数算错了，这两个数会明显对不上。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class LoudnessMeterTest {

    private val sampleRate = 44_100

    @Test
    fun monoMinus20dBfsReadsMinus23() {
        val meter = LoudnessMeter()
        feed(meter, channels = 1, dbfs = -20f, seconds = 11f)

        val reading = meter.reading()
        assertNotNull(reading)
        assertEquals(-23.0f, reading!!.lufs, 0.3f)
        assertTrue("喂了 11 秒应该已经定稿", reading.converged)
    }

    @Test
    fun stereoReadsThreeDbLouderThanMono() {
        val meter = LoudnessMeter()
        feed(meter, channels = 2, dbfs = -20f, seconds = 11f)

        val reading = meter.reading()
        assertNotNull(reading)
        assertEquals(-20.0f, reading!!.lufs, 0.3f)
    }

    @Test
    fun lowerLevelReadsLower() {
        val meter = LoudnessMeter()
        feed(meter, channels = 2, dbfs = -30f, seconds = 11f)

        val reading = meter.reading()!!
        // 比 −20 低 10dB，读数也该低 10 LU
        assertEquals(-30.0f, reading.lufs, 0.3f)
    }

    @Test
    fun sampleRateDoesNotShiftTheResult() {
        // 48kHz 和 44.1kHz 下同一段音频的响度必须一样，
        // 否则说明滤波系数没按采样率重算
        val at44 = LoudnessMeter()
        feed(at44, channels = 1, dbfs = -20f, seconds = 11f, rate = 44_100)

        val at48 = LoudnessMeter()
        feed(at48, channels = 1, dbfs = -20f, seconds = 11f, rate = 48_000)

        assertEquals(at44.reading()!!.lufs, at48.reading()!!.lufs, 0.2f)
    }

    @Test
    fun silenceIsIgnored() {
        val meter = LoudnessMeter()
        feed(meter, channels = 2, dbfs = 0f, seconds = 11f, amplitudeOverride = 0f)

        // 全静音应该什么都测不出来，而不是报一个假的响度
        assertNull(meter.reading())
    }

    @Test
    fun notEnoughAudioIsNotConverged() {
        val meter = LoudnessMeter()
        feed(meter, channels = 2, dbfs = -20f, seconds = 2f)

        val reading = meter.reading()
        assertNotNull(reading)
        assertTrue("只喂了 2 秒，不该认为已经测完", !reading!!.converged)
    }

    @Test
    fun startOverClearsTheMeasurement() {
        val meter = LoudnessMeter()
        feed(meter, channels = 2, dbfs = -20f, seconds = 11f)
        assertNotNull(meter.reading())

        meter.startOver()
        // 换台之后不能还拿着上一个台的数字
        assertNull(meter.reading())
    }

    @Test
    fun floatOutputIsAccepted() {
        val meter = LoudnessMeter()
        val format = AudioProcessor.AudioFormat(
            sampleRate,
            2,
            C.ENCODING_PCM_FLOAT,
        )
        meter.configure(format)

        feedChunks(meter, sampleRate, 2, -20f, 11f, float = true)

        val reading = meter.reading()
        assertNotNull(reading)
        assertEquals(-20.0f, reading!!.lufs, 0.3f)
    }

    @Test
    fun peakMatchesTheWaveform() {
        // 正弦波的峰值就是它的幅度，−20 dBFS 的输入必须读出 −20 dBFS
        val meter = LoudnessMeter()
        feed(meter, channels = 2, dbfs = -20f, seconds = 11f)

        assertEquals(-20f, meter.reading()!!.peakDbfs, 0.2f)
    }

    @Test
    fun peakSeesHotSources() {
        // 贴着满刻度的源要能读出来，这是「不许再放大」的依据
        val meter = LoudnessMeter()
        feed(meter, channels = 1, dbfs = -1f, seconds = 11f)

        val reading = meter.reading()!!
        assertEquals(-1f, reading.peakDbfs, 0.2f)
        // 同时响度也得是它自己的值，两者互不干扰
        assertEquals(-4f, reading.lufs, 0.5f)
    }

    // ------------------------------------------------------------------

    private fun feed(
        meter: LoudnessMeter,
        channels: Int,
        dbfs: Float,
        seconds: Float,
        rate: Int = sampleRate,
        amplitudeOverride: Float? = null,
    ) {
        val format = AudioProcessor.AudioFormat(rate, channels, C.ENCODING_PCM_16BIT)
        meter.configure(format)
        feedChunks(meter, rate, channels, dbfs, seconds, amplitudeOverride)
    }

    /**
     * 生成 1kHz 正弦的 PCM，按 250ms 一块投喂 —— 和 ExoPlayer 真实的喂法一致，
     * 顺便验证处理器在块边界上不会把帧算错。
     */
    private fun feedChunks(
        meter: LoudnessMeter,
        rate: Int,
        channels: Int,
        dbfs: Float,
        seconds: Float,
        amplitude: Float? = null,
        float: Boolean = false,
    ) {
        val peak = amplitude ?: 10.0.pow(dbfs / 20.0).toFloat()
        val totalFrames = (rate * seconds).roundToInt()
        val chunkFrames = (rate * 0.25f).roundToInt().coerceAtLeast(1)
        val bytesPerSample = if (float) 4 else 2

        var written = 0
        while (written < totalFrames) {
            val count = minOf(chunkFrames, totalFrames - written)
            val buffer = ByteBuffer
                .allocate(count * channels * bytesPerSample)
                .order(ByteOrder.LITTLE_ENDIAN)
            for (n in 0 until count) {
                // 相位要用全局帧号，否则每个块都从头开始会有咔哒声
                val frame = written + n
                val value = (peak * sin(2.0 * PI * 1000.0 * frame / rate)).toFloat()
                repeat(channels) {
                    if (float) {
                        buffer.putFloat(value)
                    } else {
                        buffer.putShort((value * 32767f).roundToInt().toShort())
                    }
                }
            }
            buffer.flip()
            meter.queueInput(buffer)
            meter.getOutput()
            written += count
        }
    }
}
