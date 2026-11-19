package com.radio.simple.playback

import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.UnstableApi
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * PCM 域的增益。
 *
 * 为什么不用 `ExoPlayer.setVolume()`：那个接口的取值是 0–1，**只能衰减不能放大**。
 * 而国内电台和香港电台的实际响度能差 14 dB 以上，轻的那一头必须抬起来才能对齐。
 *
 * 这里改的是送给 AudioTrack 的样本幅度，和系统音量（STREAM_MUSIC）是两条独立的路径，
 * 所以调它不会动用户手机的音量条。
 *
 * 放大有削波风险，但调用方（[VolumeLevel]）会按实测峰值余量把增益卡在安全范围内，
 * 这里再兜一层 clamp，任何情况下都不会溢出。
 */
@OptIn(UnstableApi::class)
class GainProcessor : BaseAudioProcessor() {

    /** 目标增益（线性系数）。用 @Volatile：界面线程写，音频线程读 */
    @Volatile
    private var target = 1f

    /** 当前实际用的增益，用来做渐变 */
    private var ramped = 1f

    private var encoding = C.ENCODING_INVALID
    private var channelCount = 1

    /**
     * 设置增益。真正生效是渐变的，不会「啪」一下跳过去 ——
     * 听到一半音量突变很容易被当成卡顿。
     */
    fun setGain(linear: Float) {
        target = linear.coerceIn(MIN_GAIN, MAX_GAIN)
    }

    fun gain(): Float = target

    override fun onConfigure(
        inputAudioFormat: AudioProcessor.AudioFormat,
    ): AudioProcessor.AudioFormat {
        if (inputAudioFormat.encoding != C.ENCODING_PCM_16BIT &&
            inputAudioFormat.encoding != C.ENCODING_PCM_FLOAT
        ) {
            throw AudioProcessor.UnhandledAudioFormatException(inputAudioFormat)
        }
        encoding = inputAudioFormat.encoding
        channelCount = inputAudioFormat.channelCount
        // 新播放器第一次出声就用目标值，不要从 1.0 渐变上来
        ramped = target
        return inputAudioFormat
    }

    override fun onReset() {
        ramped = target
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        val size = inputBuffer.remaining()
        if (size <= 0) return

        val out = replaceOutputBuffer(size)
        inputBuffer.order(ByteOrder.LITTLE_ENDIAN)
        out.order(ByteOrder.LITTLE_ENDIAN)

        val goal = target
        if (abs(goal - ramped) < EPSILON) {
            // 稳态：整段乘同一个常数，最省事也最快
            ramped = goal
            scaleConstant(inputBuffer, out, goal)
        } else {
            ramped = scaleRamped(inputBuffer, out, ramped, goal)
        }
        out.flip()
    }

    private fun scaleConstant(input: ByteBuffer, out: ByteBuffer, gain: Float) {
        if (encoding == C.ENCODING_PCM_FLOAT) {
            val frames = input.remaining() / (channelCount * 4)
            repeat(frames) {
                repeat(channelCount) { out.putFloat(clampFloat(input.float * gain)) }
            }
        } else {
            val frames = input.remaining() / (channelCount * 2)
            repeat(frames) {
                repeat(channelCount) { out.putShort(clampShort(input.short * gain)) }
            }
        }
    }

    /**
     * 在一个 buffer 内把增益从 [from] 线性推到 [to]。
     * buffer 通常二三十毫秒，走完这个过程听不出台阶。
     */
    private fun scaleRamped(input: ByteBuffer, out: ByteBuffer, from: Float, to: Float): Float {
        val bytesPerSample = if (encoding == C.ENCODING_PCM_FLOAT) 4 else 2
        val frames = (input.remaining() / (channelCount * bytesPerSample)).coerceAtLeast(1)
        val step = (to - from) / frames
        var gain = from

        if (encoding == C.ENCODING_PCM_FLOAT) {
            repeat(frames) {
                gain += step
                repeat(channelCount) { out.putFloat(clampFloat(input.float * gain)) }
            }
        } else {
            repeat(frames) {
                gain += step
                repeat(channelCount) { out.putShort(clampShort(input.short * gain)) }
            }
        }
        return to
    }

    private fun clampShort(value: Float): Short =
        value.roundToInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()

    private fun clampFloat(value: Float): Float = value.coerceIn(-1f, 1f)

    private companion object {
        /** 最小增益：压到 0 就成静音了，留一点让用户还能听见 */
        const val MIN_GAIN = 0.05f

        /** 最大增益 +18 dB。真正用多少由 VolumeLevel 按峰值余量卡，这里只是兜底 */
        const val MAX_GAIN = 8f

        const val EPSILON = 1e-4f
    }
}
