package com.radio.simple.playback

import com.radio.simple.data.Station
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * 电台的静音预听队列。
 *
 * 一次只测一个：十几个台同时静音拉流，既抢主播放器的带宽，也容易被服务器限速。
 * 测完一个再下一个，失败的直接跳过（不重试 —— 连不上的流再试三次还是连不上）。
 *
 * 「这次运行里已经试过」的台记在 [attempted] 里：失败过的台不会因为列表刷新、
 * 重新导入又被排一遍，免得每次操作都白烧流量。链接改过的台由调用方 [forget] 掉重新排队。
 *
 * [enqueue] 带 `force = true` 时是用户手动「重新测量所有电台」：档案不再作为跳过的理由，
 * 已有的读数会被新测的覆盖。这时连正在播的台也测 —— 用户要的是「全部重来一遍」。
 */
class StationMeasureQueue(
    private val scope: CoroutineScope,
    private val measure: suspend (Station) -> LoudnessReading?,
    private val onMeasured: (Station, LoudnessReading) -> Unit,
    private val enabled: () -> Boolean = { true },
    private val shouldSkip: (Station) -> Boolean = { false },
    private val onFinished: (Progress) -> Unit = {},
) {

    /**
     * 一批测量的进度。界面拿它显示「已测 3 / 18」。
     *
     * [total] 是这批打算测的数量；中途被跳过（比如那个台开始播了、交给播放链路去测）
     * 会把它减掉，所以正常情况下跑完时 done == total。
     */
    data class Progress(
        val running: Boolean = false,
        val done: Int = 0,
        val total: Int = 0,
        val failed: Int = 0,
        val current: String? = null,
    )

    private val _progress = MutableStateFlow(Progress())
    val progress: StateFlow<Progress> = _progress.asStateFlow()

    private val pending = ArrayDeque<Station>()
    private val attempted = HashSet<String>()

    /** 手动重测的台：测的时候不看档案、也不看 [shouldSkip] */
    private val forced = HashSet<String>()

    /** 正在测的那个，避免重测时把同一条流又排一遍 */
    private var inFlightId: String? = null

    private var worker: Job? = null

    /** 还有几个台排队 / 正在测 */
    val remaining: Int
        get() = pending.size + if (worker?.isActive == true) 1 else 0

    /**
     * 排队。
     *
     * 默认只排「档案不全」的台：已经有响度和峰值的跳过，只存了响度的（老版本）要补测。
     * [force] 为 true 时忽略档案，全部重测。
     */
    fun enqueue(stations: List<Station>, force: Boolean = false) {
        // 功能关着就别排队 —— 排了又不测，还会把这批 id 记成「试过」，开关再打开时反而漏掉。
        // 手动重测是用户明确要求的，即使开关关着也照测（测完不生效而已）。
        if (!force && !enabled()) return
        if (stations.isEmpty()) return

        val added = ArrayList<Station>(stations.size)
        stations.forEach { station ->
            if (station.id == inFlightId) return@forEach
            if (pending.any { it.id == station.id }) return@forEach
            if (force) {
                forced += station.id
                attempted += station.id
            } else {
                if (station.loudnessLufs != null && station.peakDbfs != null) return@forEach
                if (!attempted.add(station.id)) return@forEach
            }
            pending.addLast(station)
            added += station
        }
        if (added.isEmpty()) return

        val before = _progress.value
        _progress.value = if (before.running) {
            before.copy(total = before.total + added.size)
        } else {
            Progress(running = true, total = added.size)
        }

        if (worker?.isActive != true) worker = scope.launch { drain() }
    }

    /** 链接换了：旧档案已经作废，允许再排一次 */
    fun forget(id: String) {
        attempted.remove(id)
    }

    /** 电台被删了：从队列和「试过」名单里一起清掉 */
    fun drop(id: String) {
        val removed = pending.count { it.id == id }
        if (removed > 0) {
            pending.removeAll { it.id == id }
            val p = _progress.value
            _progress.value = p.copy(total = (p.total - removed).coerceAtLeast(p.done))
        }
        attempted.remove(id)
        forced.remove(id)
    }

    /** 队列作废（比如用户关掉了自动音量，或者手动停止了重测） */
    fun cancel() {
        pending.clear()
        forced.clear()
        inFlightId = null
        worker?.cancel()
        worker = null
        _progress.value = Progress()
    }

    private suspend fun drain() {
        while (pending.isNotEmpty()) {
            val station = pending.removeFirst()
            val forcedItem = forced.remove(station.id)

            if (!forcedItem) {
                // 开关被关掉时停下但不清队，重新打开后接着测
                if (!enabled()) {
                    pending.addFirst(station)
                    _progress.value = _progress.value.copy(running = false, current = null)
                    return
                }
                if (shouldSkip(station)) {
                    // 没测就少一个，别让进度条永远差一格
                    val p = _progress.value
                    _progress.value = p.copy(total = (p.total - 1).coerceAtLeast(p.done))
                    continue
                }
            }

            inFlightId = station.id
            _progress.value = _progress.value.copy(current = station.name)

            val reading = try {
                measure(station)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                null
            }
            inFlightId = null

            if (reading != null && reading.converged) {
                onMeasured(station, reading)
                val p = _progress.value
                _progress.value = p.copy(done = p.done + 1, current = null)
            } else {
                val p = _progress.value
                _progress.value = p.copy(done = p.done + 1, failed = p.failed + 1, current = null)
            }
        }
        finish()
    }

    private fun finish() {
        worker = null
        val done = _progress.value.copy(running = false, current = null)
        _progress.value = done
        onFinished(done)
    }
}
