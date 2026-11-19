package com.radio.simple.playback

import android.app.PendingIntent
import android.content.Intent
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.DefaultMediaNotificationProvider
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import com.radio.simple.MainActivity
import com.radio.simple.R
import com.radio.simple.data.SettingsStore

/**
 * 后台播放服务。播放器本身由 [PlaybackEngine] 持有（界面直接用它，点了就能播），
 * 这里只负责把 MediaSession 挂上去——通知栏、锁屏、耳机按键，以及 ColorOS
 * 灵动岛 / 流体云读的都是这个 MediaSession。
 */
@OptIn(UnstableApi::class)
class PlaybackService : MediaSessionService() {

    private var mediaSession: MediaSession? = null
    private var swapListener: ((ExoPlayer) -> Unit)? = null

    /** 语音输入（听写 / 语音助手）占用麦克风时把电台停掉 */
    private var voiceInputWatcher: VoiceInputWatcher? = null

    override fun onCreate() {
        super.onCreate()

        val notificationProvider = DefaultMediaNotificationProvider.Builder(this)
            .setChannelName(R.string.notification_channel_name)
            .build()
            .apply { setSmallIcon(R.drawable.ic_notification) }
        setMediaNotificationProvider(notificationProvider)

        val player = PlaybackEngine.current()
            ?: PlaybackEngine.player(this, SettingsStore(this).current.bufferSeconds)

        val sessionActivity = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        mediaSession = MediaSession.Builder(this, player)
            .setSessionActivity(sessionActivity)
            .build()

        // 缓冲参数改了会重建播放器，这里把 session 换到新的上面，通知不断
        val listener: (ExoPlayer) -> Unit = { fresh -> mediaSession?.player = fresh }
        swapListener = listener
        PlaybackEngine.addSwapListener(listener)

        // 语音输入一开口就停：麦克风被别的 App 拿走时，继续播只会被一起录进去。
        // 用 stop 而不是 pause —— 输入结束后不该自己又响起来，得让用户决定。
        voiceInputWatcher = VoiceInputWatcher(
            context = this,
            isPlaying = { PlaybackEngine.current()?.isPlaying == true },
            onVoiceInput = {
                PlaybackEngine.current()?.let { player ->
                    if (player.isPlaying) player.stop()
                }
            },
        ).also { it.start() }
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = mediaSession

    override fun onTaskRemoved(rootIntent: Intent?) {
        // 正在播放就别把服务杀掉，从最近任务里划掉也继续播
        if (PlaybackEngine.current()?.playWhenReady != true) {
            stopSelf()
        }
    }

    override fun onDestroy() {
        voiceInputWatcher?.stop()
        voiceInputWatcher = null
        swapListener?.let(PlaybackEngine::removeSwapListener)
        swapListener = null
        mediaSession?.release()
        mediaSession = null
        // 播放器归 PlaybackEngine 管，这里不释放：界面还要用它，播着的时候也不能断
        super.onDestroy()
    }
}
