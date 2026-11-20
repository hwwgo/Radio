package com.radio.simple

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import com.radio.simple.playback.GainProcessor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * PCM 增益处理的正确性。
 *
 * 这里验证的是「乘得对、不会溢出、换增益时不炸音」——
 * 数值对不对由 `VolumeLevelTest` 管，这个类只管执行层。
 */
class GainProcessorTest {

    private fun format(channels: Int = 1, encoding: Int = C.ENCODING_PCM_16BIT) =
        AudioProcessor.AudioFormat(44100, channels, encoding)

    /** 先设增益再 configure，走「常量」快路径，输出可以逐样本断言 */
    private fun run16(samples: ShortArray, gain: Float, channels: Int = 1): ShortArray {
        val processor = GainProcessor()
        processor.setGain(gain)
        processor.configure(format(channels))
        val input = ByteBuffer.allocate(samples.size * 2).order(ByteOrder.LITTLE_ENDIAN)
        samples.forEach { input.putShort(it) }
        input.flip()
        processor.queueInput(input)
        val out = processor.getOutput()
        return ShortArray(out.remaining() / 2) { out.short }
    }

    @Test
    fun appliesAttenuation() {
        val out = run16(shortArrayOf(10000, -10000, 20000), 0.5f)
        assertArrayEquals(shortArrayOf(5000, -5000, 10000), out)
    }

    @Test
    fun appliesBoost() {
        val out = run16(shortArrayOf(1000, -2000), 2f)
        assertArrayEquals(shortArrayOf(2000, -4000), out)
    }

    @Test
    fun gainOfOneIsPassthrough() {
        val input = shortArrayOf(0, 1234, -5678, 32767, -32768)
        assertArrayEquals(input, run16(input, 1f))
    }

    @Test
    fun clampsPositiveOverflow() {
        // 30000 × 2 = 60000，超出 int16 上限，必须削到 32767 而不是回绕成负数
        val out = run16(shortArrayOf(30000), 2f)
        assertEquals(Short.MAX_VALUE, out[0])
    }

    @Test
    fun clampsNegativeOverflow() {
        val out = run16(shortArrayOf(-30000), 2f)
        assertEquals(Short.MIN_VALUE, out[0])
    }

    @Test
    fun handlesStereoInterleaved() {
        // 立体声：左右交错，增益要作用到每一个样本，不能只处理一半
        val out = run16(shortArrayOf(1000, -1000, 2000, -2000), 0.5f, channels = 2)
        assertArrayEquals(shortArrayOf(500, -500, 1000, -1000), out)
    }

    @Test
    fun processesFloatSamples() {
        val processor = GainProcessor()
        processor.setGain(1.5f)
        processor.configure(format(encoding = C.ENCODING_PCM_FLOAT))
        val input = ByteBuffer.allocate(12).order(ByteOrder.LITTLE_ENDIAN)
        input.putFloat(0.2f).putFloat(-0.4f).putFloat(0.9f)
        input.flip()
        processor.queueInput(input)
        val out = processor.getOutput()
        assertEquals(0.3f, out.float, 0.001f)
        assertEquals(-0.6f, out.float, 0.001f)
        // 0.9 × 1.5 = 1.35，浮点也要夹在 ±1 之内
        assertEquals(1f, out.float, 0.001f)
    }

    @Test
    fun gainChangeIsRampedNotJumped() {
        // 听到一半把增益从 1.0 推到 0.5，不能第一个样本就跳过去
        val processor = GainProcessor()
        processor.configure(format())
        processor.setGain(0.5f)

        fun feed(count: Int): ShortArray {
            val input = ByteBuffer.allocate(count * 2).order(ByteOrder.LITTLE_ENDIAN)
            repeat(count) { input.putShort(1000) }
            input.flip()
            processor.queueInput(input)
            val out = processor.getOutput()
            return ShortArray(out.remaining() / 2) { out.short }
        }

        val first = feed(441)   // 10ms
        val second = feed(441)
        val third = feed(441)

        // 第一个 buffer 内部是渐变的：开头接近 1000，结尾接近 500
        assertTrue("渐变起点应接近原值，实际 ${first.first()}", first.first() in 980..1000)
        assertTrue("渐变终点应接近目标，实际 ${first.last()}", first.last() in 500..530)

        // 两个 buffer 之后已经稳定
        assertTrue("稳态应稳定在 500，实际 ${third.first()}", third.first() in 495..505)
        assertTrue("稳态整段都该是 500", second.all { it in 495..560 })
    }

    @Test
    fun configureTakesEffectImmediately() {
        // 换播放器 / 换台时 AudioSink 会重新 configure，
        // 这时不该再渐一遍 —— 第一个声音就得是对齐后的电平
        val processor = GainProcessor()
        processor.setGain(0.25f)
        processor.configure(format())
        val input = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN)
        input.putShort(1000).putShort(1000)
        input.flip()
        processor.queueInput(input)
        val out = processor.getOutput()
        assertEquals(250, out.short.toInt())
        assertEquals(250, out.short.toInt())
    }

    @Test(expected = AudioProcessor.UnhandledAudioFormatException::class)
    fun rejectsUnsupportedEncoding() {
        GainProcessor().configure(
            AudioProcessor.AudioFormat(44100, 1, C.ENCODING_PCM_24BIT)
        )
    }

    private fun assertArrayEquals(expected: ShortArray, actual: ShortArray) {
        assertEquals("长度不一致", expected.size, actual.size)
        expected.indices.forEach { i ->
            assertEquals("第 $i 个样本", expected[i].toInt(), actual[i].toInt())
        }
    }
}
