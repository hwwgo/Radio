package com.radio.simple

import com.radio.simple.playback.MicSessionTracker
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「是不是刚多出来一路录音」的判断。
 *
 * 这条逻辑决定了电台什么时候会被语音输入停掉，宁可漏一次也别误停 ——
 * 常驻占着麦克风的 App（热词、通话、无障碍）不该让用户一点播放就被停。
 */
class MicSessionTrackerTest {

    @Test
    fun baselineIsNotTreatedAsNewRecording() {
        val tracker = MicSessionTracker()
        // 启动时已经有一路常驻录音（比如语音助手的热词）
        tracker.reset(sessions = 1, keys = setOf(100))

        assertFalse("同一路录音还在继续，不算新开始", tracker.observe(1, setOf(100)))
    }

    @Test
    fun secondRecordingIsDetected() {
        val tracker = MicSessionTracker()
        tracker.reset(sessions = 0, keys = emptySet())

        assertTrue("从没有到有一路，就是用户开始说话了", tracker.observe(1, setOf(200)))
    }

    @Test
    fun anotherSessionReplacingTheOldOneIsDetected() {
        val tracker = MicSessionTracker()
        tracker.reset(sessions = 1, keys = setOf(100))

        // 上一路结束、下一路开始，数量没变，但会话换了
        assertTrue("热词那一路结束后用户开口，数量不变也得认出来", tracker.observe(1, setOf(300)))
    }

    @Test
    fun sameCountAndSameSessionsIsNotDetected() {
        val tracker = MicSessionTracker()
        tracker.observe(1, setOf(100))

        assertFalse(tracker.observe(1, setOf(100)))
        assertFalse("录音源变了但会话没变，也不该反复停", tracker.observe(1, setOf(100)))
    }

    @Test
    fun anonymizedSessionsFallBackToCounting() {
        // 有些机型把会话 id 匿名成 0：两路录音的 keys 都是 {0}，
        // 这时候只能靠数量变化判断
        val tracker = MicSessionTracker()
        tracker.reset(sessions = 1, keys = setOf(0))

        assertFalse(tracker.observe(1, setOf(0)))
        assertTrue("多出第二路，即使 id 都被匿名也要认出来", tracker.observe(2, setOf(0)))
    }

    @Test
    fun recordingEndingIsNotNewInput() {
        val tracker = MicSessionTracker()
        tracker.reset(sessions = 2, keys = setOf(100, 200))

        assertFalse("少了一路只是结束，不是新开始", tracker.observe(1, setOf(100)))
        assertFalse(tracker.observe(0, emptySet()))
    }
}
