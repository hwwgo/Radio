package com.radio.simple.playback

import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.UnstableApi
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.tan

/**
 * 一次测量结果。
 *
 * @param lufs 积分响度（LKFS），按 ITU-R BS.1770 算。数值越大越响，,-9 很响，-24 很轻。
 * @param seconds 累计参与统计的音频时长。
 * @param converged 时长够了没有。没够时 [lufs] 只是个粗略值，别拿去存。
 * @param peakDbfs 这段时间里最响的那个采样值（dBFS，0 就是满刻度）。
 *   积分响度只说明「平均多响」，要判断能把一个台放大多少，必须看峰值还剩多少余量 ——
 *   平均很轻但偶尔冒尖的源，一放大就削顶。
 */
data class LoudnessReading(
    val lufs: Float,
    val seconds: Float,
    val converged: Boolean,
    val peakDbfs: Float,
)

/**
 * 实时响度计。
 *
 * 挂在 ExoPlayer 的音频处理链里，**只读不写** —— 数据原样透传给 AudioTrack，
 * 唯一的副作用是把算出来的响度攒起来。这是整个方案里最关键的设计：
 * 不改波形，就不可能引入音染，最坏情况也只是「没测准」而不是「音质变差」。
 *
 * 算法按 ITU-R BS.1770-4 简化实现：
 * 1. K 加权 —— 一级高搁架（模拟头部效应）+ 一级 38Hz 高通（去掉人耳听不到的低频能量）
 * 2. 每 400ms 一个 block，算该段的均方能量
 * 3. 绝对门限 −70 LUFS（剔除静音）+ 相对门限 −10 LU（剔除相对很轻的段落）
 * 4. 通过门限的 block 取平均，`-0.691 + 10·log10(能量)` 就是积分响度
 *
 * 攒满 [TARGET_SECONDS] 秒后冻结，不再更新 —— 电台的响度档案只需要一个稳定值，
 * 一直滚动的数字反而会让音量跟着节目内容忽大忽小。
 */
@OptIn(UnstableApi::class)
class LoudnessMeter : BaseAudioProcessor() {

    /** 400ms 一个 block，和 BS.1770 规定的积分窗口一致 */
    private val blockSeconds = 0.4f

    /** 攒够这么久就算数。10 秒对直播流足够，太长用户等不起 */
    private val targetSeconds = 10f

    /** 冻结后保留的 block 数，12 秒不到，够统计了 */
    private val maxBlocks = 30

    private val blockMeanSquare = FloatArray(maxBlocks)

    /**
     * 音频线程写、界面线程读，必须 volatile。
     * 没有它，预听那边可能一直看到「还没测完」，白等到超时。
     */
    @Volatile
    private var blockCount = 0

    private var sampleRate = 0
    private var channelCount = 0
    private var encoding = C.ENCODING_INVALID
    private var framesPerBlock = 0

    private var shelf: Array<Biquad> = emptyArray()
    private var highPass: Array<Biquad> = emptyArray()

    private var blockFrameCount = 0
    private var blockEnergy = 0.0

    /** 冻结后就一直返回同一个结果，避免数字乱跳 */
    @Volatile
    private var frozen: Float? = null

    /** 冻结时一并记下的峰值，保证读数和响度是同一段时间的 */
    @Volatile
    private var frozenPeak = 0f

    /** 目前见过的最响采样（线性，0–1） */
    @Volatile
    private var peakLinear = 0f

    /**
     * 当前读数。还没收到任何音频时返回 null。
     */
    fun reading(): LoudnessReading? {
        val value = frozen ?: integrate() ?: return null
        val seconds = blockCount * blockSeconds
        val peak = if (frozen != null) frozenPeak else peakLinear
        return LoudnessReading(
            lufs = value,
            seconds = seconds,
            converged = frozen != null,
            peakDbfs = if (peak <= 0f) SILENCE_DBFS else (20.0 * log10(peak.toDouble())).toFloat(),
        )
    }

    /** 换台时清空。滤波器状态另由 [onFlush] 处理 */
    fun startOver() {
        blockCount = 0
        blockEnergy = 0.0
        blockFrameCount = 0
        frozen = null
        frozenPeak = 0f
        peakLinear = 0f
        shelf.forEach { it.reset() }
        highPass.forEach { it.reset() }
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        val size = inputBuffer.remaining()
        if (size <= 0) return

        // 分析用副本读，原 buffer 原样透传，position 不受影响
        analyze(inputBuffer.duplicate().order(ByteOrder.LITTLE_ENDIAN))

        val out = replaceOutputBuffer(size)
        out.put(inputBuffer)
        out.flip()
    }

    override fun onConfigure(
        inputAudioFormat: AudioProcessor.AudioFormat,
    ): AudioProcessor.AudioFormat {
        if (inputAudioFormat.encoding != C.ENCODING_PCM_16BIT &&
            inputAudioFormat.encoding != C.ENCODING_PCM_FLOAT
        ) {
            throw AudioProcessor.UnhandledAudioFormatException(inputAudioFormat)
        }
        sampleRate = inputAudioFormat.sampleRate
        channelCount = inputAudioFormat.channelCount
        encoding = inputAudioFormat.encoding
        framesPerBlock = (sampleRate * blockSeconds).roundToInt().coerceAtLeast(1)

        // 采样率变了系数就得重算，否则滤波器的截止频率会跟着跑偏
        shelf = Array(channelCount) { Biquad().apply { setHighShelf(sampleRate.toFloat()) } }
        highPass = Array(channelCount) { Biquad().apply { setHighPass(sampleRate.toFloat()) } }
        blockFrameCount = 0
        blockEnergy = 0.0
        return inputAudioFormat
    }

    /**
     * 换了流段（seek / 格式变化 / 重连）时清掉滤波器状态。
     * 已经攒下的 block 不动：那是整段收听的统计，重连不该让前面的数据白费。
     */
    override fun onFlush(streamMetadata: AudioProcessor.StreamMetadata) {
        shelf.forEach { it.reset() }
        highPass.forEach { it.reset() }
        blockFrameCount = 0
        blockEnergy = 0.0
    }

    private fun analyze(buffer: ByteBuffer) {
        if (frozen != null || channelCount == 0) return

        while (true) {
            if (encoding == C.ENCODING_PCM_16BIT) {
                if (buffer.remaining() < 2 * channelCount) return
            } else {
                if (buffer.remaining() < 4 * channelCount) return
            }

            var energy = 0.0
            for (ch in 0 until channelCount) {
                val sample = if (encoding == C.ENCODING_PCM_16BIT) {
                    buffer.short / 32768f
                } else {
                    buffer.float
                }
                // 峰值取原始样本，不过滤波器：它代表的是「这一路流本身有多满」，
                // 决定还能往上抬多少，不能被 K 加权改过
                val magnitude = abs(sample)
                if (magnitude > peakLinear) peakLinear = magnitude
                val filtered = highPass[ch].process(shelf[ch].process(sample))
                energy += filtered.toDouble() * filtered
            }
            blockEnergy += energy
            blockFrameCount++

            if (blockFrameCount >= framesPerBlock) {
                record(blockEnergy / blockFrameCount)
                blockFrameCount = 0
                blockEnergy = 0.0
                if (frozen != null) return
            }
        }
    }

    private fun record(meanSquare: Double) {
        if (meanSquare <= 0.0) return
        if (blockCount >= maxBlocks) return
        blockMeanSquare[blockCount++] = meanSquare.toFloat()
        if (blockCount >= (targetSeconds / blockSeconds).roundToInt()) {
            // 攒够了就定下来，后面的音频不再影响结果
            frozen = integrate()
            frozenPeak = peakLinear
        }
    }

    /**
     * 两遍门限。先剔除绝对静音，再用剩下的块算出「相对门限」，
     * 把明显低于整体水平的段落（过人声间隙、台标音乐）也排除掉。
     */
    private fun integrate(): Float? {
        if (blockCount == 0) return null

        var absoluteSum = 0.0
        var absoluteCount = 0
        for (i in 0 until blockCount) {
            val l = loudnessOf(blockMeanSquare[i])
            if (l > ABSOLUTE_GATE) {
                absoluteSum += blockMeanSquare[i]
                absoluteCount++
            }
        }
        if (absoluteCount == 0) return null

        val relativeGate = loudnessOf((absoluteSum / absoluteCount).toFloat()) + RELATIVE_GATE
        var gatedSum = 0.0
        var gatedCount = 0
        for (i in 0 until blockCount) {
            val ms = blockMeanSquare[i]
            val l = loudnessOf(ms)
            if (l > ABSOLUTE_GATE && l > relativeGate) {
                gatedSum += ms
                gatedCount++
            }
        }
        if (gatedCount == 0) return null
        return loudnessOf((gatedSum / gatedCount).toFloat())
    }

    private fun loudnessOf(meanSquare: Float): Float =
        if (meanSquare <= 0f) -70f else (-0.691 + 10.0 * log10(meanSquare.toDouble())).toFloat()

    /**
     * 二阶 IIR，直接 I 型。BS.1770 的两级滤波都是这个形状，
     * 只是系数不同，所以合成一个类。
     */
    private class Biquad {
        private var b0 = 1f
        private var b1 = 0f
        private var b2 = 0f
        private var a1 = 0f
        private var a2 = 0f

        private var x1 = 0f
        private var x2 = 0f
        private var y1 = 0f
        private var y2 = 0f

        /** 一级：高搁架，模拟头部对高频的放大约 +4dB（1681Hz 以上） */
        fun setHighShelf(fs: Float) {
            val f0 = 1681.974450955533
            val gainDb = 3.999843853973347
            val q = 0.7071752369554196
            val k = tan(PI * f0 / fs)
            val vh = 10.0.pow(gainDb / 20.0)
            val vb = vh.pow(0.4996667741545416)
            val a0 = 1.0 + k / q + k * k
            b0 = ((vh + vb * k / q + k * k) / a0).toFloat()
            b1 = (2.0 * (k * k - vh) / a0).toFloat()
            b2 = ((vh - vb * k / q + k * k) / a0).toFloat()
            a1 = (2.0 * (k * k - 1.0) / a0).toFloat()
            a2 = ((1.0 - k / q + k * k) / a0).toFloat()
            reset()
        }

        /** 二级：38Hz 高通，压掉次声频段那些听不见但会虚高能量的成分 */
        fun setHighPass(fs: Float) {
            val f0 = 38.13547087602444
            val q = 0.5003270373238773
            val k = tan(PI * f0 / fs)
            val a0 = 1.0 + k / q + k * k
            b0 = (1.0 / a0).toFloat()
            b1 = (-2.0 / a0).toFloat()
            b2 = (1.0 / a0).toFloat()
            a1 = (2.0 * (k * k - 1.0) / a0).toFloat()
            a2 = ((1.0 - k / q + k * k) / a0).toFloat()
            reset()
        }

        fun process(x: Float): Float {
            val y = b0 * x + b1 * x1 + b2 * x2 - a1 * y1 - a2 * y2
            x2 = x1
            x1 = x
            y2 = y1
            y1 = y
            return y
        }

        fun reset() {
            x1 = 0f
            x2 = 0f
            y1 = 0f
            y2 = 0f
        }
    }

    private companion object {
        /** 静音门限：低于这个就当没声音，不计入统计 */
        const val ABSOLUTE_GATE = -70f

        /** 相对门限：比整体水平低 10 LU 以上的段落丢掉 */
        const val RELATIVE_GATE = -10f

        /** 全程静音时的峰值读数，避免 log10(0) 变成 -Inf */
        const val SILENCE_DBFS = -120f
    }
}
