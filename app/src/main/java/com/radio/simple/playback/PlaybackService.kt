package com.radio.simple.playback

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import androidx.annotation.OptIn
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaStyleNotificationHelper
import com.radio.simple.MainActivity
import com.radio.simple.R
import com.radio.simple.data.SettingsStore

/**
 * 后台播放服务。播放器本身由 [PlaybackEngine] 持有（界面直接用它，点了就能播），
 * 这里负责三件事：**常驻前台**、通知栏控制、MediaSession（锁屏 / 耳机 / 车机）。
 *
 * 为什么是普通 Service 而不是 Media3 的 `MediaSessionService`：
 * 后者只会在「有 MediaController 连上来」时才发通知、才进前台
 * （`MediaNotificationManager.shouldShowNotification` 第一步就查已连接的 controller）；
 * 本应用界面直接操作 ExoPlayer，没有 controller，于是服务永远停在「已启动但非前台」，
 * 屏幕一关，进程很快被判成 cached 并被 freezer 冻结，声音就断了 —— 表现就是
 * 黑屏几分钟后自动停。
 *
 * 更麻烦的是，MediaSessionService 在没有 controller 时收到任何播放状态更新都会走
 * `removeNotification()` → `stopForeground()`，把外面自己拉起的前台身份再撤销掉。
 * 所以这里退回 Transistor 用的老形态：普通 Service + 独立 MediaSession，
 * 前台通知完全自己管。播着一律 `startForeground`，系统才知道「这个进程正在放音频」。
 */
@OptIn(UnstableApi::class)
class PlaybackService : Service() {

    companion object {
        private const val TAG = "PlaybackService"

        /** 通知渠道。IMPORTANCE_LOW：常驻但不响、不弹 */
        private const val CHANNEL_ID = "radio_playback"

        /** 前台通知 id，退出前台时要按同一个 id 撤 */
        private const val NOTIFICATION_ID = 1001

        /** 通知上的按钮动作，走 startService 回来处理 */
        const val ACTION_TOGGLE = "com.radio.simple.action.TOGGLE_PLAYBACK"
        const val ACTION_STOP = "com.radio.simple.action.STOP_PLAYBACK"

        /** 播放器进入 IDLE 后先等一会儿再撤前台：重连 / 换台都会短暂经过 IDLE */
        private const val DEMOTE_DELAY_MS = 4_000L

        /**
         * 通知上「已听 12:34」的刷新节拍。
         *
         * 通知文字是我们自己拼的，播放器稳定出声时不会有任何事件，不主动刷就会停在
         * 点播放那一刻。指纹按分钟算，所以这个节拍只决定「最多迟多久」，不会白建通知。
         */
        private const val TICK_MS = 15_000L
    }

    private var mediaSession: MediaSession? = null
    private var sessionActivity: PendingIntent? = null
    private var swapListener: ((ExoPlayer) -> Unit)? = null

    /** 语音输入（听写 / 语音助手）占用麦克风时把电台停掉 */
    private var voiceInputWatcher: VoiceInputWatcher? = null

    /** 当前挂了监听器的播放器 */
    private var bound: ExoPlayer? = null

    /** 现在是不是前台服务 */
    private var inForeground = false

    /** 上一条通知的内容指纹，用来避免每次状态抖动都重建通知 */
    private var notificationKey: String? = null

    private val handler = Handler(Looper.getMainLooper())

    private val demoteTask = Runnable { demoteIfIdle() }

    /** 定时把「已听时长」刷到通知上。只在真正出声时运行，停了就不再排下一次 */
    private val tickTask = object : Runnable {
        override fun run() {
            if (!inForeground) return
            if (PlaybackEngine.current()?.isPlaying != true) return
            promote()
            handler.postDelayed(this, TICK_MS)
        }
    }

    override fun onCreate() {
        super.onCreate()

        createChannel()

        val player = PlaybackEngine.current()
            ?: PlaybackEngine.player(this, SettingsStore(this).current.bufferSeconds)

        sessionActivity = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        // 包一层：直播没有总长，MediaSession 会因此把 position 置成未知，
        // 灵动岛 / 锁屏就拿不到时长。SessionPlayer 用「已收听时长」冒充播放位置。
        mediaSession = MediaSession.Builder(this, SessionPlayer(player))
            .setSessionActivity(sessionActivity!!)
            .build()

        // 缓冲参数改了会重建播放器，这里把 session 和监听都换到新的上面，通知不断
        val listener: (ExoPlayer) -> Unit = { fresh ->
            mediaSession?.player = SessionPlayer(fresh)
            attach(fresh)
        }
        swapListener = listener
        PlaybackEngine.addSwapListener(listener)

        attach(player)

        // 语音输入一开口就停：麦克风被别的 App 拿走时，继续播只会被一起录进去。
        // 用 stop 而不是 pause —— 输入结束后不该自己又响起来，得让用户决定。
        voiceInputWatcher = VoiceInputWatcher(
            context = this,
            isPlaying = { PlaybackEngine.current()?.isPlaying == true },
            onVoiceInput = {
                if (PlaybackEngine.current()?.isPlaying == true) {
                    PlaybackEngine.stopAndReset()
                }
            },
        ).also { it.start() }
    }

    /** 只用 startService 起，不接受 bind */
    override fun onBind(intent: Intent?): IBinder? = null

    /**
     * 通知按钮 / 系统重启动服务都会走到这里。
     */
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_TOGGLE -> togglePlayback()
            ACTION_STOP -> stopPlayback()
            null -> if (PlaybackEngine.current() == null) {
                // 系统按 START_STICKY 把服务拉起来了，但播放器已经随进程没了，
                // 没有可恢复的东西，别留一个空服务占着内存
                demote()
                stopSelf()
                return START_NOT_STICKY
            }
        }
        refreshForeground()
        return START_STICKY
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        // 正在播放就别把服务杀掉，从最近任务里划掉也继续播
        if (PlaybackEngine.current()?.playWhenReady != true) {
            demote()
            stopSelf()
        }
    }

    override fun onDestroy() {
        handler.removeCallbacks(demoteTask)
        handler.removeCallbacks(tickTask)
        bound?.removeListener(playerListener)
        bound = null
        voiceInputWatcher?.stop()
        voiceInputWatcher = null
        swapListener?.let(PlaybackEngine::removeSwapListener)
        swapListener = null
        demote()
        mediaSession?.release()
        mediaSession = null
        // 播放器归 PlaybackEngine 管，这里不释放：界面还要用它，播着的时候也不能断
        super.onDestroy()
    }

    // ------------------------------------------------------------------
    // 前台状态
    // ------------------------------------------------------------------

    private val playerListener = object : Player.Listener {
        override fun onEvents(player: Player, events: Player.Events) {
            refreshForeground()
        }
    }

    private fun attach(player: ExoPlayer) {
        if (bound === player) return
        bound?.removeListener(playerListener)
        bound = player
        player.addListener(playerListener)
        refreshForeground()
    }

    /**
     * 播放器还挂着电台（播放 / 缓冲 / 暂停）就留在前台，彻底 IDLE 了再撤。
     * 暂停也留着 —— 用户锁屏后往往还要从通知或耳机键接着听。
     */
    private fun refreshForeground() {
        val p = PlaybackEngine.current()
        val hasStation = p != null && p.mediaItemCount > 0 && p.playbackState != Player.STATE_IDLE
        if (hasStation) {
            handler.removeCallbacks(demoteTask)
            promote()
        } else {
            scheduleDemote()
        }
    }

    private fun promote() {
        // 先比指纹再建通知：onEvents 触发很勤，没必要每次都拼一遍 Notification
        val key = notificationKey()
        if (inForeground && key == notificationKey) return
        val notification = buildNotification() ?: return

        try {
            ServiceCompat.startForeground(
                this,
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK,
            )
            inForeground = true
            notificationKey = key
            handler.removeCallbacks(tickTask)
            handler.postDelayed(tickTask, TICK_MS)
            Log.i(TAG, "已进入前台（$key）")
        } catch (e: Exception) {
            // Android 12+ 在后台启动前台服务会抛 ForegroundServiceStartNotAllowedException。
            // 这里不能崩：通知发不出去，但播放本身还在，下次回到前台会再试。
            Log.w(TAG, "进入前台失败，播放继续但可能被系统回收", e)
        }
    }

    private fun scheduleDemote() {
        handler.removeCallbacks(demoteTask)
        handler.postDelayed(demoteTask, DEMOTE_DELAY_MS)
    }

    private fun demoteIfIdle() {
        val p = PlaybackEngine.current()
        if (p != null && p.mediaItemCount > 0 && p.playbackState != Player.STATE_IDLE) return
        demote()
    }

    private fun demote() {
        if (!inForeground) return
        handler.removeCallbacks(tickTask)
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        inForeground = false
        notificationKey = null
        Log.i(TAG, "已退出前台")
    }

    private fun notificationKey(): String {
        val p = PlaybackEngine.current()
        return listOf(
            p?.mediaMetadata?.title?.toString() ?: "",
            p?.isPlaying == true,
            p?.playbackState ?: Player.STATE_IDLE,
            // 按分钟参与指纹：秒数变了没必要重建，但分钟要跟上
            PlayedTime.elapsedMs() / 60_000,
        ).joinToString("|")
    }

    // ------------------------------------------------------------------
    // 通知
    // ------------------------------------------------------------------

    private fun createChannel() {
        val manager = getSystemService(NotificationManager::class.java) ?: return
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.notification_channel_name),
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = "显示正在播放的电台；它是息屏后继续播放的凭证，别关掉"
            setShowBadge(false)
            lockscreenVisibility = Notification.VISIBILITY_PUBLIC
        }
        manager.createNotificationChannel(channel)
    }

    private fun buildNotification(): Notification? {
        val p = PlaybackEngine.current() ?: return null
        val session = mediaSession ?: return null

        val title = p.mediaMetadata.title?.toString()?.takeIf { it.isNotBlank() }
            ?: getString(R.string.app_name)
        val playing = p.playWhenReady && p.playbackState != Player.STATE_IDLE
        val listened = PlayedTime.clock()
        val status = when {
            p.playbackState == Player.STATE_BUFFERING -> "缓冲中… · 已听 $listened"
            playing -> "正在播放 · 已听 $listened"
            else -> "已暂停 · 已听 $listened"
        }

        val openIntent = sessionActivity
        val toggleIntent = PendingIntent.getService(
            this,
            10,
            Intent(this, PlaybackService::class.java).setAction(ACTION_TOGGLE),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val stopIntent = PendingIntent.getService(
            this,
            11,
            Intent(this, PlaybackService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(status)
            .setContentIntent(openIntent)
            .setDeleteIntent(stopIntent)
            .setCategory(NotificationCompat.CATEGORY_TRANSPORT)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setSilent(true)
            .setOngoing(playing)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .addAction(
                if (playing) R.drawable.ic_pause else R.drawable.ic_play,
                if (playing) "暂停" else "播放",
                toggleIntent,
            )
            .addAction(R.drawable.ic_stop, "停止", stopIntent)
            .setStyle(
                MediaStyleNotificationHelper.MediaStyle(session)
                    .setShowActionsInCompactView(0)
            )

        return builder.build()
    }

    // ------------------------------------------------------------------
    // 通知按钮
    // ------------------------------------------------------------------

    private fun togglePlayback() {
        val p = PlaybackEngine.current() ?: return
        if (p.playWhenReady) {
            p.pause()
        } else {
            // 和界面上的播放按钮走同一条路：暂停久了要重新拉流
            PlaybackEngine.resumeFromPause()
        }
    }

    private fun stopPlayback() {
        val p = PlaybackEngine.current() ?: return
        p.stop()
        p.clearMediaItems()
        demote()
        stopSelf()
    }
}
