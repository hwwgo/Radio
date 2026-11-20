package com.radio.simple.playback

import android.content.Context
import android.media.AudioManager
import android.media.AudioRecordingConfiguration
import android.media.MediaRecorder
import android.os.Handler
import android.os.Looper
import android.util.Log

/**
 * 监听「有没有别人在用麦克风」，用来在用户语音输入时把电台停掉。
 *
 * 为什么要这么做：语音输入（输入法的听写、语音助手）走的是麦克风，不是音频焦点。
 * 播着的电台不会被系统自动让路，结果就是喇叭里的声音一起被录进去，识别率直线下降，
 * 用户还得手动暂停。音频焦点只覆盖「别的 App 要出声」的情况，管不到「别的 App 要听」。
 *
 * 系统给了 [AudioManager.AudioRecordingCallback]：只要有 App 开始 / 结束录音就会回调。
 * 普通应用拿到的配置是**匿名化**的（看不到包名），但「有几路录音、录音源是什么」还在，
 * 判断语音输入够用了 —— 我们也不需要知道是谁。
 *
 * 两个刻意的处理：
 * - **只认「新出现」的录音**。常驻热词、通话、无障碍之类的应用可能长期占着麦克风，
 *   要是见到就停，用户一按播放就会被停掉。所以启动时先把现有会话记成基线，
 *   之后只有「多出来一路」才算用户刚开始语音输入。
 * - **只认语音类的录音源**。系统内录、REMOTE_SUBMIX 这些不是语音输入，不该打断电台。
 */
class VoiceInputWatcher(
    context: Context,
    private val isPlaying: () -> Boolean,
    private val onVoiceInput: () -> Unit,
) {

    private val audioManager: AudioManager? =
        context.applicationContext.getSystemService(AudioManager::class.java)

    /** 基线 + 变化判断，纯逻辑，单独可测 */
    private val tracker = MicSessionTracker()

    private var registered = false

    private val callback = object : AudioManager.AudioRecordingCallback() {
        override fun onRecordingConfigChanged(configs: MutableList<AudioRecordingConfiguration>?) {
            handle(configs.orEmpty())
        }
    }

    fun start() {
        val manager = audioManager ?: return
        if (registered) return

        // 先看一眼现在谁在录音，把这些记成基线，避免把常驻占用当成「刚开始」
        val baseline = snapshot()
        tracker.reset(baseline.count, baseline.keys)
        try {
            // AudioManager 只有 Handler 版本的重载（Executor 那个在 AudioRecord 上）。
            // 回调走主线程，正好和播放器状态在同一个线程上判断
            @Suppress("DEPRECATION")
            manager.registerAudioRecordingCallback(callback, Handler(Looper.getMainLooper()))
            registered = true
        } catch (e: Exception) {
            // 个别 ROM 会拦这个回调；拦了就当没有这项能力，不影响播放
            Log.w(TAG, "注册录音状态回调失败，语音输入检测不可用", e)
        }
    }

    fun stop() {
        if (!registered) return
        registered = false
        runCatching { audioManager?.unregisterAudioRecordingCallback(callback) }
    }

    private fun handle(configs: List<AudioRecordingConfiguration>) {
        val current = snapshotOf(configs)
        if (!tracker.observe(current.count, current.keys)) return
        if (!isPlaying()) return

        Log.i(TAG, "检测到语音输入（${current.count} 路麦克风），停止播放")
        onVoiceInput()
    }

    private fun snapshot(): Sessions {
        val configs = try {
            audioManager?.activeRecordingConfigurations.orEmpty()
        } catch (e: Exception) {
            // 没有录音权限 / 被 ROM 限制时不要崩，静默降级
            Log.i(TAG, "读不到录音状态：${e.message}")
            emptyList()
        }
        return snapshotOf(configs)
    }

    private fun snapshotOf(configs: List<AudioRecordingConfiguration>): Sessions {
        val voice = configs.filter { it.clientAudioSource in VOICE_SOURCES }
        return Sessions(count = voice.size, keys = voice.map { it.clientAudioSessionId }.toSet())
    }

    /** 当前有几路语音类录音、分别是哪些会话 */
    private data class Sessions(val count: Int, val keys: Set<Int>)

    private companion object {
        const val TAG = "VoiceInputWatcher"

        /**
         * 算作「语音输入」的录音源。
         *
         * 刻意不含 `HOTWORD`：常驻热词检测长期占着麦克风，把它算进来就等于
         * 「只要装过语音助手，电台就永远起不来」。
         */
        val VOICE_SOURCES = setOf(
            MediaRecorder.AudioSource.DEFAULT,              // 0，不少输入法用的就是这个
            MediaRecorder.AudioSource.MIC,                  // 1
            MediaRecorder.AudioSource.CAMCORDER,            // 5
            MediaRecorder.AudioSource.VOICE_RECOGNITION,    // 6，听写 / 语音助手
            MediaRecorder.AudioSource.VOICE_COMMUNICATION,  // 7，通话 / 语音通话类
            MediaRecorder.AudioSource.UNPROCESSED,          // 9
            MediaRecorder.AudioSource.VOICE_PERFORMANCE,    // 10
        )
    }
}

/**
 * 判断「是不是刚多出来一路录音」。
 *
 * 光比数量不够：上一路结束、下一路开始，数量还是 1；所以同时比会话 id。
 * 反过来说，会话 id 在某些机型上会被匿名掉（都是 0），这时数量变化就是唯一的线索。
 */
internal class MicSessionTracker {

    private var count = 0
    private var keys: Set<Int> = emptySet()

    /** 记下当前状态但不触发，用于建立基线 */
    fun reset(sessions: Int, keys: Set<Int>) {
        count = sessions
        this.keys = keys
    }

    /**
     * @return true 表示相比上次多出了新的录音（用户可以认为「刚开始语音输入」）
     */
    fun observe(sessions: Int, keys: Set<Int>): Boolean {
        val appeared = sessions > count || keys.any { it !in this.keys }
        count = sessions
        this.keys = keys
        return appeared
    }
}
