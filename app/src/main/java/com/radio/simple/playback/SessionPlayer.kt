package com.radio.simple.playback

import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi

/**
 * 给 MediaSession 用的播放器外壳：把「直播」包装成「有总时长的点播」。
 *
 * 为什么要包：Media3 在生成 `PlaybackStateCompat` 时先看 `isCurrentMediaItemLive()`，
 * 为真就把 position 写成 `-1`（未知）、speed 写成 0。直播流全是这种情况，于是
 * 锁屏、通知栏、各家「灵动岛」拿到的位置永远未知 —— 只能显示电台名，显示不出时长。
 * 这里换成「已收听时长」当播放位置，系统 UI 就会按 speed=1 自己往下走秒。
 *
 * 直播没有总长度，这里按整点给一个分段时长（见 [segmentEndMs]）：
 * 0–59 分钟算第 1 段（总长 1:00:00），1 小时以上算第 2 段（2:00:00），依此类推。
 * 它只影响进度条的比例，不改变任何播放行为。
 *
 * 只给 MediaSession 用；界面和服务里的真实播放仍走原始 ExoPlayer。
 */
@OptIn(UnstableApi::class)
class SessionPlayer(player: Player) : ForwardingPlayer(player) {

    companion object {
        private const val HOUR_MS = 3_600_000L

        /** 已收听 [elapsedMs] 时，这一「段」的总长 */
        internal fun segmentEndMs(elapsedMs: Long): Long =
            (elapsedMs.coerceAtLeast(0L) / HOUR_MS + 1) * HOUR_MS

        /** 副标题那一行：`已听 12:34` */
        internal fun listeningLabel(elapsedMs: Long): String = "已听 ${PlayedTime.clock(elapsedMs)}"
    }

    /** 直播标记一旦上报，系统就会把 position 丢掉 —— 这里必须说「不是直播」 */
    override fun isCurrentMediaItemLive(): Boolean = false

    override fun isCurrentMediaItemDynamic(): Boolean = false

    override fun getCurrentPosition(): Long =
        if (currentMediaItem == null) 0L else PlayedTime.elapsedMs()

    override fun getBufferedPosition(): Long = getCurrentPosition()

    override fun getDuration(): Long =
        if (currentMediaItem == null) C.TIME_UNSET else segmentEndMs(PlayedTime.elapsedMs())

    /** 缓存上一次拼好的 metadata，避免系统频繁来取时反复分配 */
    private var cachedKey: String? = null
    private var cachedMetadata: MediaMetadata = MediaMetadata.EMPTY

    /**
     * 系统 UI 的总时长是从 metadata 的 `METADATA_KEY_DURATION` 读的，
     * 只改 [getDuration] 还不够。
     *
     * 副标题（artist / subtitle）也放上「已听 12:34」：有些 ROM 的灵动岛模板不认
     * position，只显示两行文字，那里就是唯一能看见时长的地方。
     *
     * 基准优先取播放器解析出来的（可能带 ICY 动态标题），没有就退回媒体项自带的，
     * 否则还没 prepare 时会把标题弄成空字符串。
     */
    override fun getMediaMetadata(): MediaMetadata {
        val item = currentMediaItem ?: return super.getMediaMetadata()
        val resolved = super.getMediaMetadata()
        val base = if (resolved.title != null) resolved else item.mediaMetadata

        val elapsed = PlayedTime.elapsedMs()
        // 只按分钟变：秒数跳变没必要重建对象，也免得系统 UI 一直在刷
        val key = "${item.mediaId}|${base.title}|${elapsed / 60_000}"
        if (key == cachedKey) return cachedMetadata

        val label = listeningLabel(elapsed)
        cachedKey = key
        cachedMetadata = base.buildUpon()
            .setDurationMs(segmentEndMs(elapsed))
            .setArtist(label)
            .setSubtitle(label)
            .build()
        return cachedMetadata
    }
}
