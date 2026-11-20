package com.radio.simple.playback

import android.content.Context
import android.os.SystemClock
import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/**
 * 新电台的「预听」测量。
 *
 * 为什么要单独干这件事：响度档案是**听出来的**，没听过就没有。用户刚加进来的台如果等
 * 播放时才测，前 10 秒只能按原样播 —— 遇到一个特别响的源，切过去那一下还是会炸。
 * 所以新台一入库就先在后台**静音**拉 10 秒流，把档案建好；等用户真点它的时候，
 * 它和那些听过的老台完全一样，第一声就是对齐的。
 *
 * 几个刻意的设计：
 * - `volume = 0f`：只改这个播放器自己的 AudioTrack 音量，不动系统音量，也听不见。
 * - `handleAudioFocus = false`：绝不能去抢主播放器的音频焦点，否则正在听的那个台会被打断。
 * - 独立的处理器链（只有响度计，没有增益）：预听不需要调音量，测到的就是源本身的电平。
 * - 拿不到就返回 null，不重试。这类失败多是流本身有问题，反复试只是白烧流量。
 *
 * 只能在主线程调用（ExoPlayer 的要求），[measure] 内部自己切过去。
 */
@OptIn(UnstableApi::class)
class LoudnessProbe(private val context: Context) {

    /**
     * 静音听一段，返回测到的响度与峰值；超时、出错或者一直没声音就是 null。
     *
     * 10 秒的音频大约 160 KB（128 kbps），比一次封面图的流量还少。
     */
    suspend fun measure(url: String, mimeType: String? = streamMimeType(url)): LoudnessReading? =
        withContext(Dispatchers.Main.immediate) {
            val meter = LoudnessMeter()
            val player = build(meter)
            var failure: PlaybackException? = null
            val listener = object : Player.Listener {
                override fun onPlayerError(error: PlaybackException) {
                    failure = error
                }
            }
            player.addListener(listener)

            try {
                val item = MediaItem.Builder()
                    .setUri(url)
                    .apply { mimeType?.let { setMimeType(it) } }
                    .build()
                player.setMediaItem(item)
                player.prepare()
                player.play()

                val deadline = SystemClock.elapsedRealtime() + TIMEOUT_MS
                while (SystemClock.elapsedRealtime() < deadline) {
                    delay(POLL_MS)
                    val failed = failure
                    if (failed != null) {
                        Log.i(TAG, "预听失败 $url：${failed.errorCodeName}")
                        return@withContext null
                    }
                    val reading = meter.reading() ?: continue
                    if (reading.converged) {
                        Log.i(
                            TAG,
                            "预听完成 $url：${reading.lufs} LUFS、峰值 ${reading.peakDbfs} dBFS"
                        )
                        return@withContext reading
                    }
                }
                Log.i(TAG, "预听超时，放弃 $url")
                null
            } catch (e: CancellationException) {
                // 队列被取消（关掉自动音量 / 界面销毁）要如实往上抛，不能当成「测失败」
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "预听异常 $url", e)
                null
            } finally {
                player.removeListener(listener)
                runCatching { player.release() }
            }
        }

    private fun build(meter: LoudnessMeter): ExoPlayer {
        // 只需要 10 秒数据，缓冲开小一点，出声更快、占内存更少
        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(
                /* minBufferMs = */ 3_000,
                /* maxBufferMs = */ 6_000,
                /* bufferForPlaybackMs = */ 500,
                /* bufferForPlaybackAfterRebufferMs = */ 1_000,
            )
            .build()

        return ExoPlayer.Builder(context)
            .setLoadControl(loadControl)
            .setMediaSourceFactory(PlaybackEngine.mediaSourceFactory(context))
            .setRenderersFactory(
                PlaybackEngine.ProcessorRenderersFactory(context, arrayOf(meter))
            )
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                    .build(),
                /* handleAudioFocus = */ false,
            )
            .setHandleAudioBecomingNoisy(false)
            .build()
            .apply {
                // 静音听：链路上的处理器照常跑，只是送进 AudioTrack 的音量是 0
                volume = 0f
            }
    }

    private companion object {
        const val TAG = "LoudnessProbe"

        /** 连不上的流不要一直挂着后台 */
        const val TIMEOUT_MS = 20_000L

        /** 400ms 一个 block，半秒看一次足够 */
        const val POLL_MS = 400L
    }
}
