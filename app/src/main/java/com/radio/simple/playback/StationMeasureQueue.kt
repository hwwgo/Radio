package com.radio.simple.playback

import com.radio.simple.data.Station
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * 新电台的预听队列。
 *
 * 一次只测一个：十几个台同时静音拉流，既抢主播放器的带宽，也容易被服务器限速。
 * 测完一个再下一个，失败的直接跳过（不重试 —— 连不上的流再试三次还是连不上）。
 *
 * 「这次运行里已经试过」的台记在 [attempted] 里：失败过的台不会因为列表刷新、
 * 重新导入又被排一遍，免得每次操作都白烧流量。链接改过的台由调用方 [forget] 掉重新排队。
 */
class StationMeasureQueue(
    private val scope: CoroutineScope,
    private val measure: suspend (Station) -> LoudnessReading?,
    private val onMeasured: (Station, LoudnessReading) -> Unit,
    private val enabled: () -> Boolean = { true },
    private val shouldSkip: (Station) -> Boolean = { false },
) {

    private val pending = ArrayDeque<Station>()
    private val attempted = HashSet<String>()
    private var worker: Job? = null

    /** 还有几个台排队 / 正在测 */
    val remaining: Int
        get() = pending.size + if (worker?.isActive == true) 1 else 0

    /**
     * 排队。已经有完整档案（响度和峰值都在）的台跳过；
     * 只是档案不全（1.9 只存了响度）的台要补测，因为缺峰值就只能压不能抬。
     */
    fun enqueue(stations: List<Station>) {
        // 功能关着就别排队 —— 排了又不测，还会把这批 id 记成「试过」，开关再打开时反而漏掉
        if (!enabled()) return
        stations.forEach { station ->
            if (station.loudnessLufs != null && station.peakDbfs != null) return@forEach
            if (!attempted.add(station.id)) return@forEach
            pending.addLast(station)
        }
        if (pending.isEmpty()) return
        if (worker?.isActive == true) return
        worker = scope.launch { drain() }
    }

    /** 链接换了：旧档案已经作废，允许再排一次 */
    fun forget(id: String) {
        attempted.remove(id)
    }

    /** 电台被删了：从队列和「试过」名单里一起清掉 */
    fun drop(id: String) {
        pending.removeAll { it.id == id }
        attempted.remove(id)
    }

    /** 队列作废（比如用户关掉了自动音量） */
    fun cancel() {
        pending.clear()
        worker?.cancel()
        worker = null
    }

    private suspend fun drain() {
        while (pending.isNotEmpty()) {
            // 开关被关掉时停下但不清队，重新打开后接着测
            if (!enabled()) return
            val station = pending.removeFirst()
            if (shouldSkip(station)) continue

            val reading = try {
                measure(station)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                null
            }
            if (reading != null && reading.converged) onMeasured(station, reading)
        }
    }
}
