package com.radio.simple.playback

import kotlin.math.pow

/**
 * 把「各台测到的响度」换算成 PCM 增益（线性系数）。
 *
 * 两条约束决定了这套规则：
 *
 * 1. **目标电平不能钉死。** 如果固定对齐到 −18 LUFS，那用户手里全是响台时，
 *    一开功能整体就凭空小一截，用户只会觉得「这功能让声音变小了」。
 *    所以目标取所有已测台的**中位数**，再夹在 [MIN_TARGET_LUFS, MAX_TARGET_LUFS]：
 *    向「中间那个台」看齐，整体音量基本不变，只把两头的差异收拢。
 *
 * 2. **放大必须看峰值。** 积分响度只说明「平均多响」，一个平均很轻但偶尔冒尖的源，
 *    放大几 dB 就会削顶破音。所以正增益要卡在 `−1 dBFS − 实测峰值` 以内，
 *    负增益（衰减）则不受限制。这也是 [LoudnessMeter] 必须同时记峰值的原因。
 *
 * 峰值未知（老版本留下的档案）时保守处理：只压不抬，等下一次重新测。
 */
object VolumeLevel {

    /** 目标电平下限：再轻的台也不往这个值以下对齐，否则整体被拖垮 */
    const val MIN_TARGET_LUFS = -20f

    /** 目标电平上限：全是响台时不必压太狠 */
    const val MAX_TARGET_LUFS = -16f

    /** 最多压 15dB。再往下就不是「调音量」而是「听不见了」 */
    const val MAX_ATTENUATION_DB = -15f

    /** 最多抬 12dB。再高会把源的底噪一起放大，得不偿失 */
    const val MAX_BOOST_DB = 12f

    /**
     * 冷启动（还没攒满 10 秒）时允许的调整范围。
     *
     * 头几秒的读数只是三五段音频的平均值，可能是台标、片头或者一句特别响的话，
     * 拿它一步到位地调 −15 dB 会明显「抽一下」。所以先只往这个区间里靠，
     * 等正式结果出来了再走正常规则 —— 宁可前两秒差一点，也别让用户听见音量自己乱跳。
     */
    const val EARLY_MIN_DB = -8f
    const val EARLY_MAX_DB = 3f

    /** 冷启动读数从第几秒开始可用。至少 3 个 block，避免被半个字 / 一段静音带偏 */
    const val EARLY_SECONDS = 2.4f

    /** 用户手动整体加减的幅度上限（dB）。和设置页滑块的范围一致 */
    const val MAX_OFFSET_DB = 10f

    /**
     * 给削波留的天花板（dBFS）。
     *
     * 留 3 dB 不是随手写的：实测香港电台第一台的**采样峰值**是 −10.2 dBFS，
     * 但**真峰值**（inter-sample peak）是 −7.0 dBFS，两者差 3.2 dB —— 高频成分丰富的
     * 语音经过有损编码后，采样点之间的波形会鼓出去。我们测的是采样峰值（便宜、够用），
     * 所以必须多留这 3 dB，否则抬完听着「没爆表」其实已经顶穿了 DAC 的重建波形。
     */
    const val PEAK_CEILING_DBFS = -3f

    /**
     * 目标电平：所有已测台的中位数，夹在上下限之间。
     *
     * 少于两个有效值返回 null —— 只有一个台就没有「不一致」可言，
     * 保持原样最稳妥。
     */
    fun target(measured: Collection<Float?>): Float? {
        val values = measured.filterNotNull().sorted()
        if (values.size < 2) return null
        val mid = values.size / 2
        val median = if (values.size % 2 == 1) {
            values[mid]
        } else {
            (values[mid - 1] + values[mid]) / 2f
        }
        return median.coerceIn(MIN_TARGET_LUFS, MAX_TARGET_LUFS)
    }

    /**
     * 把用户拖的整体偏移叠到目标电平上。
     *
     * 这里**不再**夹 [MIN_TARGET_LUFS] / [MAX_TARGET_LUFS]：那两个值约束的是
     * 「自动算出来的目标」，让整套电台不至于被极值拖走；而用户手动拖的意思恰恰是
     * 「我要比这个再响 / 再轻一点」，夹回去就等于拖了没反应。
     * 单台的最终增益仍受 [MAX_ATTENUATION_DB] / [MAX_BOOST_DB] 和峰值余量约束，
     * 所以这里放开是安全的。
     *
     * @return 叠加后的目标；基准为 null（还没测出任何电平）时返回 null
     */
    fun offsetTarget(target: Float?, offsetDb: Float): Float? =
        target?.plus(offsetDb.coerceIn(-MAX_OFFSET_DB, MAX_OFFSET_DB))

    /**
     * 某个台相对目标电平需要多少 dB 的增益。可正可负。
     *
     * @param lufs 该台的积分响度
     * @param peakDbfs 该台的峰值；null 表示没测过峰值，此时不允许放大
     */
    fun gainDb(lufs: Float?, peakDbfs: Float?, target: Float?): Float {
        if (lufs == null || target == null) return 0f

        val raw = target - lufs
        // 要压：随便压，只是别压过头
        if (raw < 0f) return raw.coerceAtLeast(MAX_ATTENUATION_DB)

        // 要抬：得先知道峰值还剩多少空间
        if (peakDbfs == null) return 0f
        val headroom = (PEAK_CEILING_DBFS - peakDbfs)
            .coerceAtMost(MAX_BOOST_DB)
            .coerceAtLeast(0f)
        return raw.coerceAtMost(headroom)
    }

    /**
     * 只有**一个**台测过时，把它当作参照电平。
     *
     * [target] 要求至少两个样本，否则「向中间看齐」无从谈起。但刚装好、刚加了第二台的时候，
     * 唯一那个已测台就是用户耳朵里的「正常音量」—— 新台朝它对齐完全说得通，
     * 比放着不调、让新台原样播要好得多。
     *
     * 超过一个样本时返回 null：那时该用 [target] 的中位数，不该拿单个台当基准。
     */
    fun anchor(measured: Collection<Float?>): Float? {
        val values = measured.filterNotNull()
        return if (values.size == 1) values.first() else null
    }

    /**
     * 冷启动用的增益：拿还没攒满的短窗读数先对一版。
     *
     * @return 要应用的 dB 值；没有参照电平可对齐时返回 NaN（调用方按「不调」处理）
     */
    fun earlyGainDb(lufs: Float?, peakDbfs: Float?, target: Float?): Float {
        if (lufs == null || target == null) return Float.NaN
        return gainDb(lufs, peakDbfs, target).coerceIn(EARLY_MIN_DB, EARLY_MAX_DB)
    }

    /** dB 转线性系数 */
    fun linear(gainDb: Float): Float = 10.0.pow(gainDb / 20.0).toFloat()

    /**
     * 一步到位：给定当前台和全部已测台，返回该用的线性增益。
     * 没测过、或者还不够两个台时返回 1.0，等于不做处理。
     */
    fun gainFor(lufs: Float?, peakDbfs: Float?, all: Collection<Float?>): Float {
        val target = target(all) ?: return 1f
        return linear(gainDb(lufs, peakDbfs, target))
    }
}
