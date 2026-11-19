package com.radio.simple

import com.radio.simple.data.Station
import com.radio.simple.playback.LoudnessReading
import com.radio.simple.playback.StationMeasureQueue
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 预听队列的行为。
 *
 * 这个队列决定了「新加的台什么时候有响度档案」，几条底线：
 * 一次只测一个（不跟主播放抢带宽）、已经有档案的不重复测、
 * 一个失败不能把后面的全堵死、链接改过要能重新排。
 */
class StationMeasureQueueTest {

    private fun station(id: String, lufs: Float? = null, peak: Float? = null) =
        Station(id = id, name = "台$id", url = "http://example.com/$id.mp3", loudnessLufs = lufs, peakDbfs = peak)

    private fun reading(lufs: Float = -18f) =
        LoudnessReading(lufs = lufs, seconds = 10f, converged = true, peakDbfs = -6f)

    /** 等队列跑空 */
    private suspend fun StationMeasureQueue.drain() {
        withTimeout(5_000) {
            while (remaining > 0) delay(5)
        }
    }

    @Test
    fun measuresOneAtATime() = runBlocking {
        val order = mutableListOf<String>()
        var inFlight = 0
        var maxInFlight = 0

        val queue = StationMeasureQueue(
            scope = this,
            measure = { s ->
                inFlight++
                maxInFlight = maxOf(maxInFlight, inFlight)
                order += s.id
                delay(10)
                inFlight--
                reading()
            },
            onMeasured = { _, _ -> },
        )

        queue.enqueue(listOf(station("a"), station("b"), station("c")))
        queue.drain()

        assertEquals(listOf("a", "b", "c"), order)
        assertEquals("一次只能测一个", 1, maxInFlight)
    }

    @Test
    fun reportsEachResult() = runBlocking {
        val measured = mutableListOf<String>()
        val queue = StationMeasureQueue(
            scope = this,
            measure = { reading(-17f) },
            onMeasured = { s, r -> measured += "${s.id}:${r.lufs}" },
        )

        queue.enqueue(listOf(station("a"), station("b")))
        queue.drain()

        assertEquals(listOf("a:-17.0", "b:-17.0"), measured)
    }

    @Test
    fun skipsStationsThatAlreadyHaveAProfile() = runBlocking {
        val measured = mutableListOf<String>()
        val queue = StationMeasureQueue(
            scope = this,
            measure = { reading() },
            onMeasured = { s, _ -> measured += s.id },
        )

        queue.enqueue(
            listOf(
                station("done", lufs = -18f, peak = -6f),
                station("half", lufs = -18f),        // 1.9 的老档案：缺峰值，要补测
                station("fresh"),
            )
        )
        queue.drain()

        assertEquals(listOf("half", "fresh"), measured)
    }

    @Test
    fun failedMeasurementDoesNotBlockTheRest() = runBlocking {
        val attempted = mutableListOf<String>()
        val measured = mutableListOf<String>()
        val queue = StationMeasureQueue(
            scope = this,
            measure = { s ->
                attempted += s.id
                if (s.id == "broken") null else reading()
            },
            onMeasured = { s, _ -> measured += s.id },
        )

        queue.enqueue(listOf(station("broken"), station("ok")))
        queue.drain()

        assertEquals(listOf("broken", "ok"), attempted)
        assertEquals(listOf("ok"), measured)
    }

    @Test
    fun exceptionIsTreatedAsFailure() = runBlocking {
        val measured = mutableListOf<String>()
        val queue = StationMeasureQueue(
            scope = this,
            measure = { s -> if (s.id == "boom") error("网络炸了") else reading() },
            onMeasured = { s, _ -> measured += s.id },
        )

        queue.enqueue(listOf(station("boom"), station("ok")))
        queue.drain()

        assertEquals(listOf("ok"), measured)
    }

    @Test
    fun sameStationIsNotQueuedTwice() = runBlocking {
        val attempted = mutableListOf<String>()
        val queue = StationMeasureQueue(
            scope = this,
            measure = { s -> attempted += s.id; delay(5); reading() },
            onMeasured = { _, _ -> },
        )

        queue.enqueue(listOf(station("a")))
        queue.enqueue(listOf(station("a")))
        queue.drain()

        assertEquals("失败 / 测过都不该再排一遍", listOf("a"), attempted)
    }

    @Test
    fun forgetAllowsRetryAfterUrlChange() = runBlocking {
        val attempted = mutableListOf<String>()
        val queue = StationMeasureQueue(
            scope = this,
            measure = { s -> attempted += s.id; reading() },
            onMeasured = { _, _ -> },
        )

        queue.enqueue(listOf(station("a")))
        queue.drain()

        queue.forget("a")
        queue.enqueue(listOf(station("a")))
        queue.drain()

        assertEquals(listOf("a", "a"), attempted)
    }

    @Test
    fun skipPredicateWins() = runBlocking {
        val tried = mutableListOf<String>()
        val queue = StationMeasureQueue(
            scope = this,
            measure = { s -> tried += s.id; reading() },
            onMeasured = { _, _ -> },
            // 正在播的台交给播放链路去测，队列别重复拉流
            shouldSkip = { it.id == "playing" },
        )

        queue.enqueue(listOf(station("playing"), station("idle")))
        queue.drain()

        assertEquals(listOf("idle"), tried)
    }

    @Test
    fun cancelStopsTheQueue() = runBlocking {
        val queue = StationMeasureQueue(
            scope = this,
            measure = { delay(50); reading() },
            onMeasured = { _, _ -> },
        )

        queue.enqueue((1..20).map { station("s$it") })
        delay(10)
        queue.cancel()

        withTimeout(1_000) {
            while (queue.remaining > 0) delay(5)
        }
        assertTrue("取消之后队列必须立刻空掉", queue.remaining == 0)
    }

    @Test
    fun queueDisabledDoesNotBurnTheAttempt() = runBlocking {
        // 自动音量关着的时候加台：不能排队，更不能把 id 记成「试过」——
        // 否则用户打开开关后这批台永远等不到档案
        var enabled = false
        val measured = mutableListOf<String>()
        val queue = StationMeasureQueue(
            scope = this,
            measure = { reading() },
            onMeasured = { s, _ -> measured += s.id },
            enabled = { enabled },
        )

        queue.enqueue(listOf(station("a")))
        assertEquals("关着开关不该排队", 0, queue.remaining)

        enabled = true
        queue.enqueue(listOf(station("a")))
        queue.drain()

        assertEquals(listOf("a"), measured)
    }

    @Test
    fun deletedStationLeavesTheQueue() = runBlocking {
        val measured = mutableListOf<String>()
        val queue = StationMeasureQueue(
            scope = this,
            measure = { delay(20); reading() },
            onMeasured = { s, _ -> measured += s.id },
        )

        queue.enqueue(listOf(station("a"), station("b")))
        queue.drop("b")
        queue.drain()

        assertEquals("删掉的台不该还被拉流", listOf("a"), measured)
    }
}
