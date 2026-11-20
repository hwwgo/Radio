package com.radio.simple.playback

import android.content.Context
import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.source.MediaSource

/**
 * 进程内唯一的播放器，界面和服务共用同一个实例。
 *
 * 关键约定：**任何地方都不要长期缓存 ExoPlayer 引用**。缓冲时长变化时会重建
 * 播放器，旧实例会被释放；被释放的 ExoPlayer 对 setMediaItem / play 这类调用
 * 是「静默忽略」的，不会抛异常，于是界面看起来高亮了、声音却还是上一个电台。
 * 所以外部每次操作都应该调用 [player] 现取现用。
 */
@OptIn(UnstableApi::class)
object PlaybackEngine {

    private const val TAG = "PlaybackEngine"

    /**
     * 用浏览器 UA：部分电台 CDN 会按 UA 拦掉「非浏览器」的请求。
     */
    private const val USER_AGENT =
        "Mozilla/5.0 (Linux; Android 14; Mobile) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/131.0 Mobile Safari/537.36"

    private var exoPlayer: ExoPlayer? = null
    private var activeBufferSeconds: Int = -1
    private var currentReleased: Boolean = false
    private val swapListeners = mutableListOf<(ExoPlayer) -> Unit>()

    /**
     * 收听时长跟着**播放器**走，不跟界面走。
     *
     * 界面（ViewModel）会被返回键销毁，但播放和服务还在；如果计时挂在界面上，
     * 一是重建后归零，二是界面销毁期间从通知栏暂停会继续虚增。挂在这里两头都对。
     * [PlayedTime] 的 start / pause 是幂等的，界面那边重复调用也无妨。
     */
    private val playedTimeListener = object : Player.Listener {
        override fun onIsPlayingChanged(isPlaying: Boolean) {
            if (isPlaying) PlayedTime.start() else PlayedTime.pause()
        }

        override fun onTimelineChanged(timeline: Timeline, reason: Int) {
            // 服务里的停止会先 stop 再 clearMediaItems，state 可能早就是 IDLE 了，
            // 光等 onPlaybackStateChanged 会漏掉这一下
            if (exoPlayer?.mediaItemCount == 0) PlayedTime.reset()
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            // 只有「连媒体项都清空了」才算真的停止（服务里的停止按钮会 clearMediaItems）。
            // 重新装载 / 换缓冲档位都会短暂回到 IDLE，那些情况时长得留着，
            // 否则会从 00:00 重来。
            if (playbackState == Player.STATE_IDLE && exoPlayer?.mediaItemCount == 0) {
                PlayedTime.reset()
            }
        }
    }

    /** 跟当前播放器绑定的响度计。播放器重建时会换一个新的 */
    private var loudnessMeter: LoudnessMeter? = null

    /** 跟当前播放器绑定的增益级，播放器重建时同样会换 */
    private var gainProcessor: GainProcessor? = null

    /** 当前播放器用的缓冲档位（秒） */
    val bufferSeconds: Int
        get() = activeBufferSeconds

    /**
     * 取当前可用的播放器。缓冲档位变了会重建一个，并把当前电台和播放状态带过去。
     * 返回的实例一定是「活着」的。
     */
    fun player(context: Context, bufferSeconds: Int): ExoPlayer {
        val existing = exoPlayer
        // 先记住旧实例能不能用：下面会把 currentReleased 置回 false，
        // 之后再看这个标志位就永远是「能用」，等于对已释放的播放器做状态搬迁
        val existingUsable = existing != null && !currentReleased
        if (existingUsable && bufferSeconds == activeBufferSeconds) {
            return existing
        }

        val fresh = build(context.applicationContext, bufferSeconds)
        exoPlayer = fresh
        activeBufferSeconds = bufferSeconds
        currentReleased = false

        if (existingUsable) {
            transferState(existing, fresh)
            // 先把界面和 MediaSession 都换到新实例，最后才释放旧的，
            // 避免中间有谁还拿着旧实例去调用（那就是「点了没反应」）。
            swapListeners.toList().forEach { listener ->
                try {
                    listener(fresh)
                } catch (e: Exception) {
                    Log.w(TAG, "切换播放器时通知监听者失败", e)
                }
            }
            runCatching { existing.release() }
        }
        return fresh
    }

    /** 当前播放器；已释放时返回 null */
    fun current(): ExoPlayer? = if (currentReleased) null else exoPlayer

    /**
     * 起播（列表行尾、底部播放键、通知栏、自动重连都走这里）。
     *
     * **一律重新装载媒体项**，不区分是刚停止、暂停了一会儿还是上次出错：
     * 直播的缓冲是「按下停止那一刻的节目」，接着放只会先出声几秒、断掉、再回源，
     * 内容还接不上。重新 `setMediaItem` 会让 HLS 重新取一遍 playlist，从当前
     * 直播位置开始；代价只是多等一次起播缓冲。
     *
     * 不带位置参数很关键：`0` 对直播来说是窗口最开头（最旧的切片），不是最新位置。
     */
    fun resumeFromPause() {
        val p = current() ?: return
        val item = p.currentMediaItem
        if (item == null) {
            // 连电台都没有，没什么可装载的
            p.play()
            return
        }
        Log.i(TAG, "起播：重新拉流，从当前直播位置开始")
        p.setMediaItem(item)
        p.prepare()
        p.play()
    }

    /**
     * 停播并把这次的收听时长清零。
     *
     * 语音输入抢占麦克风时用：播放器只是 stop（媒体项还留着），
     * 而「停止」在用户看来就是这次收听结束了，时长不该挂在那儿。
     */
    fun stopAndReset() {
        current()?.stop()
        PlayedTime.reset()
    }

    /**
     * 当前播放器挂着的响度计。换台后要 [LoudnessMeter.startOver]，
     * 已经测完的台则直接读 [LoudnessMeter.reading] 的结果。
     */
    fun loudness(): LoudnessMeter? = if (currentReleased) null else loudnessMeter

    /**
     * 当前播放器挂着的增益级。设它是**唯一**调整输出电平的途径 ——
     * 不要再动 `player.volume`，那个只能衰减，而且和这里会相乘，两处一起改就乱了。
     */
    fun gain(): GainProcessor? = if (currentReleased) null else gainProcessor

    /**
     * 彻底释放播放器。正常使用中不需要调用（进程结束自然回收），
     * 主要给单元测试做隔离用。
     */
    fun release() {
        val existing = exoPlayer
        swapListeners.clear()
        exoPlayer = null
        loudnessMeter = null
        gainProcessor = null
        activeBufferSeconds = -1
        currentReleased = true
        PlayedTime.reset()
        runCatching { existing?.release() }
    }

    fun addSwapListener(listener: (ExoPlayer) -> Unit) {
        if (!swapListeners.contains(listener)) swapListeners += listener
    }

    fun removeSwapListener(listener: (ExoPlayer) -> Unit) {
        swapListeners -= listener
    }

    private fun build(context: Context, bufferSeconds: Int): ExoPlayer {
        // 缓冲时长 = 攒够多久才出声；稳定水位取它的两倍，最少 10 秒
        val targetMs = bufferSeconds.coerceAtLeast(1) * 1000
        val steadyMs = maxOf(targetMs * 2, 10_000)

        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(
                /* minBufferMs = */ steadyMs,
                /* maxBufferMs = */ steadyMs,
                /* bufferForPlaybackMs = */ targetMs,
                /* bufferForPlaybackAfterRebufferMs = */ targetMs,
            )
            .build()

        val meter = LoudnessMeter()
        loudnessMeter = meter
        val gain = GainProcessor()
        gainProcessor = gain

        return ExoPlayer.Builder(context)
            .setLoadControl(loadControl)
            .setMediaSourceFactory(mediaSourceFactory(context))
            // 音频处理链：先测（只读），再乘增益（可压可抬）
            .setRenderersFactory(
                ProcessorRenderersFactory(context, arrayOf<AudioProcessor>(meter, gain))
            )
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                    .build(),
                /* handleAudioFocus = */ true
            )
            .setHandleAudioBecomingNoisy(true)
            // 息屏后也要继续播：默认 WAKE_MODE_NONE 不持有任何唤醒锁，
            // 屏幕一关、设备睡着，网络就断了 —— 用户听到的就是「播着播着停了」。
            // 这个模式只在播放期间持有，暂停 / 停止会自动释放。
            .setWakeMode(C.WAKE_MODE_NETWORK)
            .build()
            .also { it.addListener(playedTimeListener) }
    }

    /**
     * 只改音频输出这一条路：其余渲染器保持默认，避免影响视频（虽然这里用不上）
     * 和扩展解码器的行为。
     *
     * @param processors 按顺序执行的音频处理器。顺序很重要：测量必须在增益之前，
     *   否则测到的是「已经调过音量」的信号，增益会越算越偏，最后自己追着自己跑。
     */
    internal class ProcessorRenderersFactory(
        context: Context,
        private val processors: Array<AudioProcessor>,
    ) : DefaultRenderersFactory(context) {

        override fun buildAudioSink(
            context: Context,
            enableFloatOutput: Boolean,
            enableAudioTrackPlaybackParams: Boolean,
        ): AudioSink = DefaultAudioSink.Builder(context)
            .setAudioProcessors(processors)
            .setEnableFloatOutput(enableFloatOutput)
            .setEnableAudioOutputPlaybackParameters(enableAudioTrackPlaybackParams)
            .build()
    }

    /**
     * 媒体源工厂。HLS / DASH / RTSP 的解析器是按 classpath 反射加载的，
     * 只要依赖里有对应模块（media3-exoplayer-hls 等）这里就自动支持 .m3u8。
     */
    internal fun mediaSourceFactory(context: Context): MediaSource.Factory =
        DefaultMediaSourceFactory(dataSourceFactory(context))
            // 直播流对短时断网要宽容：默认只重试 3 次、3 秒就放弃
            .setLoadErrorHandlingPolicy(RadioLoadErrorPolicy())

    /**
     * 网络层：不少电台 CDN 会挑 UA、会把 http 重定向到 https（跨协议），
     * 默认配置下这两种情况都会直接播不出来。
     */
    private fun dataSourceFactory(context: Context): DataSource.Factory {
        val http = DefaultHttpDataSource.Factory()
            .setUserAgent(USER_AGENT)
            .setConnectTimeoutMs(15_000)
            .setReadTimeoutMs(20_000)
            .setAllowCrossProtocolRedirects(true)
        // 包一层，顺手支持电台列表里可能出现的本地文件 / content:// 地址
        return DefaultDataSource.Factory(context, http)
    }

    /** 换播放器时别把正在听的电台弄丢了 */
    private fun transferState(from: Player, to: Player) {
        val item = from.currentMediaItem ?: return
        val wasPrepared = from.playbackState != Player.STATE_IDLE
        val wasPlaying = from.playWhenReady

        // 不带位置参数：对直播来说 0 是窗口最开头（最旧的切片）。
        // 改一次缓冲时间就会被退回到几分钟前的内容，比不重建还糟。
        to.setMediaItem(item)
        if (wasPrepared) to.prepare()
        to.playWhenReady = wasPlaying
    }
}
