package com.radio.simple.playback

import android.os.SystemClock

/**
 * 这一次收听已经播了多久。
 *
 * 放在进程级而不是 ViewModel 里：按返回键会把 Activity 连同 ViewModel 一起销毁，
 * 但播放器（进程单例）和服务还在放。胶囊点回来时如果计时跟着 ViewModel 重建，
 * 底部条就会从 00:00 重新开始 —— 声音没断，秒数却归零，看着像刚开播。
 *
 * 规则：真正出声（`isPlaying`）才算；暂停、缓冲都不累加；换台 / 停止才清零。
 * 断流重连不清零：还是同一个台，用户听到的是连续的节目。
 */
object PlayedTime {

    private var accumulatedMs = 0L
    private var runningSince = 0L
    private var running = false

    /** 开始 / 继续计时。重复调用无副作用（多个 ViewModel 会重复触发） */
    @Synchronized
    fun start() {
        if (running) return
        running = true
        runningSince = SystemClock.elapsedRealtime()
    }

    /** 暂停计时，把正在跑的这一段并入累计值 */
    @Synchronized
    fun pause() {
        if (!running) return
        accumulatedMs += (SystemClock.elapsedRealtime() - runningSince).coerceAtLeast(0L)
        running = false
        runningSince = 0L
    }

    /** 换台 / 停止：彻底归零 */
    @Synchronized
    fun reset() {
        accumulatedMs = 0L
        runningSince = 0L
        running = false
    }

    /** 当前累计毫秒数（含正在跑的这一段） */
    @Synchronized
    fun elapsedMs(): Long {
        if (!running) return accumulatedMs
        val live = (SystemClock.elapsedRealtime() - runningSince).coerceAtLeast(0L)
        return accumulatedMs + live
    }

    /** `12:34` / `1:02:03`。通知栏和系统媒体卡片共用同一套写法 */
    fun clock(millis: Long = elapsedMs()): String {
        val total = millis.coerceAtLeast(0L) / 1000
        val h = total / 3600
        val m = (total % 3600) / 60
        val s = total % 60
        return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%02d:%02d".format(m, s)
    }
}
