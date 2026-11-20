package com.radio.simple

import com.radio.simple.playback.VolumeLevel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 增益换算的规则验证。
 *
 * 两条约束：
 * 1. 目标电平取「已测台的中位数」并夹在 −20 ~ −16 —— 向中间看齐，整体音量基本不变
 * 2. 放大必须看峰值余量 —— 平均轻但偶尔冒尖的源，抬几 dB 就会削顶
 */
class VolumeLevelTest {

    @Test
    fun needsAtLeastTwoStations() {
        // 只有一个台时谈不上「不一致」，保持原样最稳
        assertNull(VolumeLevel.target(emptyList()))
        assertNull(VolumeLevel.target(listOf(-9f)))
        assertNull(VolumeLevel.target(listOf(-9f, null)))
    }

    @Test
    fun targetIsTheMedianNotTheQuietest() {
        // 以前向最轻的台看齐（−26），那是在「只能压」的年代；
        // 现在能抬了，就向中间靠（−18），整体音量才不会被最轻的那个拖小
        assertEquals(-18f, VolumeLevel.target(listOf(-9f, -18f, -26f))!!, 0.01f)
        assertEquals(-16f, VolumeLevel.target(listOf(-9f, -16f, -16f))!!, 0.01f)
    }

    @Test
    fun evenCountAveragesTheMiddleTwo() {
        // 4 个值取第 2、3 个的平均
        assertEquals(-17f, VolumeLevel.target(listOf(-9f, -15f, -19f, -26f))!!, 0.01f)
    }

    @Test
    fun targetIsClampedBothWays() {
        // 全是轻台：目标不跟着掉到 −34，否则整体被拖垮
        assertEquals(
            VolumeLevel.MIN_TARGET_LUFS,
            VolumeLevel.target(listOf(-30f, -34f, -40f))!!,
            0.01f,
        )
        // 全是响台：压到 −16 就够，没必要更小
        assertEquals(
            VolumeLevel.MAX_TARGET_LUFS,
            VolumeLevel.target(listOf(-6f, -9f, -11f))!!,
            0.01f,
        )
    }

    @Test
    fun loudStationGetsAttenuated() {
        // 衰减不看峰值：往小里调不可能削顶
        assertEquals(-7f, VolumeLevel.gainDb(lufs = -9f, peakDbfs = -1f, target = -16f), 0.01f)
    }

    @Test
    fun quietStationIsBoosted() {
        // 比目标轻 6dB，峰值还有 8.5dB 余量，可以抬满
        assertEquals(
            6f,
            VolumeLevel.gainDb(lufs = -22f, peakDbfs = -9.5f, target = -16f),
            0.01f,
        )
    }

    @Test
    fun boostIsLimitedByPeakHeadroom() {
        // 想抬 6dB，但峰值已经到 −6，距天花板 −3 只剩 3dB，只能给 3
        assertEquals(
            3f,
            VolumeLevel.gainDb(lufs = -22f, peakDbfs = -6f, target = -16f),
            0.01f,
        )
    }

    @Test
    fun alreadyClippingSourceNeverGetsBoosted() {
        // 峰值已经超过 0dBFS（源自己就削顶了），一点都不能再抬
        assertEquals(
            0f,
            VolumeLevel.gainDb(lufs = -22f, peakDbfs = 2.7f, target = -16f),
            0.01f,
        )
    }

    @Test
    fun unknownPeakMeansAttenuateOnly() {
        // 1.9 时代留下的档案没有峰值：保守处理，只压不抬
        assertEquals(0f, VolumeLevel.gainDb(lufs = -22f, peakDbfs = null, target = -16f), 0.01f)
        // 但衰减照样生效
        assertEquals(-7f, VolumeLevel.gainDb(lufs = -9f, peakDbfs = null, target = -16f), 0.01f)
    }

    @Test
    fun boostIsCapped() {
        // 目标 −16、来了个 −40 的源，理论要抬 24dB，但只抬 12dB ——
        // 再高就把底噪一起放大了
        assertEquals(
            VolumeLevel.MAX_BOOST_DB,
            VolumeLevel.gainDb(lufs = -40f, peakDbfs = -60f, target = -16f),
            0.01f,
        )
    }

    @Test
    fun attenuationIsCapped() {
        assertEquals(
            VolumeLevel.MAX_ATTENUATION_DB,
            VolumeLevel.gainDb(lufs = 0f, peakDbfs = -1f, target = -16f),
            0.01f,
        )
    }

    @Test
    fun unknownLoudnessLeavesGainAlone() {
        assertEquals(0f, VolumeLevel.gainDb(lufs = null, peakDbfs = null, target = -16f), 0.01f)
        assertEquals(0f, VolumeLevel.gainDb(lufs = -9f, peakDbfs = -1f, target = null), 0.01f)
    }

    @Test
    fun decibelToLinear() {
        assertEquals(1f, VolumeLevel.linear(0f), 0.001f)
        assertEquals(0.5f, VolumeLevel.linear(-6.0206f), 0.001f)
        assertEquals(0.1f, VolumeLevel.linear(-20f), 0.001f)
        assertEquals(2f, VolumeLevel.linear(6.0206f), 0.001f)
    }

    @Test
    fun realRadioSetGetsAligned() {
        // 用户实际那 18 个流的真实测量值（见 README 的实测表）
        val all = listOf(
            -11.9f, -13.0f, -13.6f, -14.1f, -15.4f, -15.6f, -15.9f,
            -17.7f, -17.8f, -18.1f, -19.8f, -19.9f, -20.2f, -20.9f,
            -22.7f, -22.7f, -25.1f, -26.1f,
        )
        val target = VolumeLevel.target(all)!!
        assertEquals(-17.95f, target, 0.05f)

        // 最轻的香港一台：−26.1 LUFS，采样峰值 −10.2 → 想抬 8.15dB，
        // 但 −3 的天花板只给 7.2dB，宁可差一点也不能顶穿
        val quietest = VolumeLevel.gainDb(lufs = -26.1f, peakDbfs = -10.2f, target = target)
        assertEquals(7.2f, quietest, 0.05f)
        assertTrue(
            "抬完峰值必须还在天花板以下",
            -10.2f + quietest <= VolumeLevel.PEAK_CEILING_DBFS + 0.01f,
        )

        // 最响的新城采讯：−11.9，压 6.05dB
        assertEquals(
            -6.05f,
            VolumeLevel.gainDb(lufs = -11.9f, peakDbfs = 2.7f, target = target),
            0.05f,
        )
    }

    @Test
    fun alignedSetHasNoResidualSpread() {
        // 真正的验收标准：只要每个台的峰值余量够，对齐之后所有台的输出电平必须一致。
        // 广播流压缩很重，波峰因数（峰值比平均响度高多少）通常在 6dB 上下。
        val all = listOf(
            -10.6f, -12.7f, -13.3f, -14.2f, -14.9f, -15.3f, -15.5f,
            -17.7f, -18.0f, -18.2f, -19.0f, -19.3f, -19.6f, -19.9f,
            -20.6f, -21.1f, -21.8f, -21.8f,
        )
        val target = VolumeLevel.target(all)!!

        all.forEach { lufs ->
            val peak = lufs + 6f
            val gain = VolumeLevel.gainDb(lufs, peak, target)
            assertEquals("「$lufs LUFS」没对齐", target, lufs + gain, 0.05f)
            assertTrue(
                "峰值溢出：$peak + $gain",
                peak + gain <= VolumeLevel.PEAK_CEILING_DBFS + 0.01f,
            )
        }

        // 对齐前后放一起看：原来撑开 11.2dB，现在全落在同一个数上
        assertEquals(11.2f, all.max() - all.min(), 0.05f)
    }

    @Test
    fun unmeasuredStationIsNotTouched() {
        assertEquals(1f, VolumeLevel.gainFor(null, null, listOf(-9f, -16f)), 0.001f)
    }

    @Test
    fun onlyOneMeasuredMeansNoProcessing() {
        assertEquals(1f, VolumeLevel.gainFor(-9f, -1f, listOf(-9f, null)), 0.001f)
    }

    @Test
    fun gainStaysInSaneRange() {
        val all = listOf(-9f, -21f, -30f)
        all.forEach { lufs ->
            val g = VolumeLevel.gainFor(lufs, -6f, all)
            assertTrue("增益必须落在合理区间：$lufs -> $g", g in 0.1f..4f)
        }
    }

    // ------------------------------------------------------------------
    // 新加电台：参照电平 + 冷启动快对齐
    // ------------------------------------------------------------------

    @Test
    fun exactlyOneMeasuredStationBecomesTheAnchor() {
        // 刚装好只有一台测过，中位数算不出来；新台就朝这台对齐，而不是放着不管
        assertEquals(-19.5f, VolumeLevel.anchor(listOf(-19.5f))!!, 0.001f)
        assertEquals(-19.5f, VolumeLevel.anchor(listOf(-19.5f, null))!!, 0.001f)
    }

    @Test
    fun anchorNeedsExactlyOneSample() {
        assertNull("一个都没测过，没有基准", VolumeLevel.anchor(emptyList()))
        assertNull("已经有两个了，该用中位数", VolumeLevel.anchor(listOf(-19f, -12f)))
    }

    @Test
    fun earlyGainNeedsAReference() {
        // 没有任何参照，短窗读数也不该乱调
        assertTrue(VolumeLevel.earlyGainDb(-12f, -5f, null).isNaN())
        assertTrue(VolumeLevel.earlyGainDb(null, null, -18f).isNaN())
    }

    @Test
    fun earlyGainIsClampedTighterThanTheFinalOne() {
        // 一个很响的台：正式规则要压 12dB，冷启动先只压 8dB，别让声音在第一秒塌下去
        assertEquals(
            VolumeLevel.EARLY_MIN_DB,
            VolumeLevel.earlyGainDb(-6f, -1f, -18f),
            0.001f,
        )
        // 一个很轻的台：正式规则要抬 12dB，冷启动先抬 3dB
        assertEquals(
            VolumeLevel.EARLY_MAX_DB,
            VolumeLevel.earlyGainDb(-30f, -20f, -18f),
            0.001f,
        )
    }

    @Test
    fun earlyGainKeepsModestCorrectionsAsTheyAre() {
        // 差得不多就直接用，不必等 10 秒
        assertEquals(2f, VolumeLevel.earlyGainDb(-20f, -12f, -18f), 0.01f)
    }

    @Test
    fun earlyGainStillRespectsPeakHeadroom() {
        // 想抬 3dB，但源已经贴到天花板，只能抬 1dB
        assertEquals(1f, VolumeLevel.earlyGainDb(-19f, -4f, -16f), 0.01f)
    }

    @Test
    fun newStationAlignsToTheAnchorEvenBeforeItIsCounted() {
        // 已测：一个 −19 的台。新台实测 −11（偏响 8dB）
        val anchor = VolumeLevel.anchor(listOf(-19f, null))!!
        assertEquals(-8f, VolumeLevel.earlyGainDb(-11f, -3f, anchor), 0.001f)
    }

    // ------------------------------------------------------------------
    // 用户手动整体加减
    // ------------------------------------------------------------------

    @Test
    fun offsetShiftsTheTargetUpAndDown() {
        val base = VolumeLevel.target(listOf(-9f, -18f, -26f))!!
        assertEquals(-18f, base, 0.01f)
        assertEquals(-15f, VolumeLevel.offsetTarget(base, 3f)!!, 0.01f)
        assertEquals(-21f, VolumeLevel.offsetTarget(base, -3f)!!, 0.01f)
        assertEquals("0 dB 就是不动", -18f, VolumeLevel.offsetTarget(base, 0f)!!, 0.01f)
    }

    @Test
    fun offsetIsClampedToTheSliderRange() {
        // 设置页最多 ±10 dB；即使有人传了更大的值也不能失控
        // 基准 −18 加满 +10 就是 −8，减满 −10 就是 −28
        assertEquals(-8f, VolumeLevel.offsetTarget(-18f, 99f)!!, 0.01f)
        assertEquals(-28f, VolumeLevel.offsetTarget(-18f, -99f)!!, 0.01f)
        assertEquals(VolumeLevel.MAX_OFFSET_DB, 10f, 0.01f)
    }

    @Test
    fun offsetDoesNothingWithoutABaseline() {
        assertNull("还没测出电平，拖了也没依据", VolumeLevel.offsetTarget(null, 3f))
    }

    @Test
    fun offsetMovesEveryStationTogether() {
        // 这是这个功能的核心：台与台的相对关系不能变
        // 峰值留足余量（−20），这样每台都不受天花板限制，能看清「整体平移」
        val all = listOf(-11f, -18f, -25f)
        val before = all.map { VolumeLevel.gainDb(it, -20f, VolumeLevel.offsetTarget(-18f, 0f)) }
        val after = all.map { VolumeLevel.gainDb(it, -20f, VolumeLevel.offsetTarget(-18f, 3f)) }

        before.zip(after).forEach { (b, a) ->
            assertEquals("每台都该正好多 3dB", b + 3f, a, 0.001f)
        }
    }

    @Test
    fun offsetUpIsStillLimitedByPeakHeadroom() {
        // 目标抬到 −15，但这个台峰值已经 −4：理论上抬 3dB，实际只剩 1dB 空间
        val target = VolumeLevel.offsetTarget(-18f, 3f)!!
        assertEquals(1f, VolumeLevel.gainDb(-18f, -4f, target), 0.01f)
    }

    @Test
    fun offsetDownIsStillLimitedByMaxAttenuation() {
        // 目标压到 −28，对 −13 的响台要压 15dB —— 正好是封顶值，再多就听不见了
        val target = VolumeLevel.offsetTarget(-18f, -10f)!!
        assertEquals(
            VolumeLevel.MAX_ATTENUATION_DB,
            VolumeLevel.gainDb(-13f, -3f, target),
            0.01f,
        )
    }

    @Test
    fun offsetUpIsStillLimitedByMaxBoost() {
        // 目标抬到 −8，对 −30 的轻台要抬 22dB，只能给到 12dB
        val target = VolumeLevel.offsetTarget(-18f, 10f)!!
        assertEquals(
            VolumeLevel.MAX_BOOST_DB,
            VolumeLevel.gainDb(-30f, -20f, target),
            0.01f,
        )
    }
}
