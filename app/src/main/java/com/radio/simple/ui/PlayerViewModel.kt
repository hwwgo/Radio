package com.radio.simple.ui

import android.app.Application
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.net.Uri
import android.os.SystemClock
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.Tracks
import androidx.media3.exoplayer.ExoPlayer
import com.radio.simple.R
import com.radio.simple.data.M3uExporter
import com.radio.simple.data.M3uImporter
import com.radio.simple.data.RadioSettings
import com.radio.simple.data.SettingsStore
import com.radio.simple.data.Station
import com.radio.simple.data.StationStore
import com.radio.simple.playback.AudioQuality
import com.radio.simple.playback.LoudnessProbe
import com.radio.simple.playback.LoudnessReading
import com.radio.simple.playback.PlaybackEngine
import com.radio.simple.playback.PlaybackService
import com.radio.simple.playback.StationMeasureQueue
import com.radio.simple.playback.VolumeLevel
import com.radio.simple.playback.streamMimeType
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.ByteArrayOutputStream
import kotlin.math.abs

/**
 * 界面与播放器之间的桥。
 *
 * 这里**不缓存** ExoPlayer：缓冲时长变动会重建播放器，缓存旧实例就会出现
 * 「点了不高亮、声音还是上一个电台」这种静默失效。每次操作都现取现用。
 */
class PlayerViewModel(application: Application) : AndroidViewModel(application) {

    private val store = StationStore(application)
    val stations: StateFlow<List<Station>> = store.stations

    private val settingsStore = SettingsStore(application)
    val settings: StateFlow<RadioSettings> = settingsStore.settings

    /** 当前电台 id（来自播放器本身，不靠界面猜） */
    var currentId by mutableStateOf<String?>(null)
        private set

    var isPlaying by mutableStateOf(false)
        private set

    var isBuffering by mutableStateOf(false)
        private set

    /** 当前音轨的格式说明，比如「AAC-LC · 128 kbps · 44.1 kHz」；没解出来就是 null */
    var audioQuality by mutableStateOf<String?>(null)
        private set

    /** 这一次收听已经播了多久（毫秒）。换台或停止归零，暂停时不再累加 */
    var elapsedMs by mutableStateOf(0L)
        private set

    /** 正在读 M3U 文件，界面上把导入按钮置灰 */
    var isImporting by mutableStateOf(false)
        private set

    /** 正在写导出文件，界面上把导出按钮置灰 */
    var isExporting by mutableStateOf(false)
        private set

    /** 一次性提示，界面拿到后调用 consumeMessage() 清掉 */
    var message by mutableStateOf<String?>(null)
        private set

    /** 已经挂上监听的播放器，用来避免重复添加 / 漏摘 */
    private var listened: ExoPlayer? = null

    /** 已播放时长的计时协程 */
    private var stopwatchJob: Job? = null

    /** 正在给哪个台测响度 */
    private var measureJob: Job? = null

    /**
     * 新电台的预听队列。
     *
     * 用 lazy 是因为它要拿 viewModelScope；而排队只发生在「加台 / 导入 / 改链接」这些
     * 用户动作之后，那时候实例早就构造完了。
     */
    private val measureQueue: StationMeasureQueue by lazy {
        StationMeasureQueue(
            scope = viewModelScope,
            measure = { station -> measureInBackground(station) },
            onMeasured = { station, reading ->
                store.setLoudness(station.id, reading.lufs, reading.peakDbfs)
                Log.i(
                    TAG,
                    "「${station.name}」预听建档 ${reading.lufs} LUFS、峰值 ${reading.peakDbfs} dBFS"
                )
                // 多了一个档案，中位数可能挪了；正在播的台跟着走
                applyGainForCurrent()
            },
            enabled = { settingsStore.current.normalizeVolume },
            shouldSkip = { station ->
                // 正在播的台交给播放链路去测，别重复拉流；已经有档案的不测
                station.id == currentId || hasProfile(station.id)
            },
        )
    }


    /** 最近一次请求播放的地址，出错时用来给一句有针对性的提示 */
    private var requestedUrl: String? = null

    private val playerListener = object : Player.Listener {
        override fun onIsPlayingChanged(playing: Boolean) {
            isPlaying = playing
            if (playing) isBuffering = false
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            isBuffering = playbackState == Player.STATE_BUFFERING
            // 停止（包括语音输入自动停）要把计时清零，
            // 否则底部条会因为「还有秒数」把它显示成「已暂停」
            if (playbackState == Player.STATE_IDLE) elapsedMs = 0L
        }

        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            // 只认播放器自己报的当前项，避免界面和服务两份状态打架
            currentId = mediaItem?.mediaId
        }

        override fun onTracksChanged(tracks: Tracks) {
            // 直播流解析出音轨后才能知道码率 / 采样率，这里跟着刷新
            audioQuality = AudioQuality.describe(AudioQuality.selectedFormat(tracks))
        }

        override fun onPlayerError(error: PlaybackException) {
            isBuffering = false
            val scheme = requestedUrl?.substringBefore("://")?.lowercase()
            message = when {
                scheme == "mms" || scheme == "rtmp" ->
                    "播放失败：MMS / RTMP 这类老式地址已经播不了，请换成 http(s) 的 .m3u8 或 mp3 直播地址"

                error.errorCode == PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS ->
                    "播放失败：服务器拒绝访问（403/404），链接可能过期或需要换 UA"

                error.errorCode == PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND ->
                    "播放失败：链接找不到（404）"

                error.errorCode == PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED ||
                    error.errorCode == PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT ->
                    "播放失败：连不上这个地址，检查网络或链接"

                error.errorCode == PlaybackException.ERROR_CODE_PARSING_MANIFEST_UNSUPPORTED ||
                    error.errorCode == PlaybackException.ERROR_CODE_PARSING_MANIFEST_MALFORMED ->
                    "播放失败：直播清单解析不了，这个链接可能已经失效"

                error.errorCode == PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED ||
                    error.errorCode == PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED ->
                    "播放失败：拿到的不是音频流（可能是网页、错误页或加密流）"

                else -> "播放失败（${error.errorCodeName}），检查一下链接是否正确"
            }
        }
    }

    /** 缓冲参数变了会换播放器，监听要跟着挪过去 */
    private val swapListener: (ExoPlayer) -> Unit = { fresh -> attach(fresh) }

    init {
        attach(PlaybackEngine.player(getApplication(), settingsStore.current.bufferSeconds))
        PlaybackEngine.addSwapListener(swapListener)
        // 让 MediaSession 尽早起来，通知栏 / 灵动岛才有东西可挂
        startPlaybackService()
        startStopwatch()
    }

    /**
     * 已播放时长。
     *
     * 用 [SystemClock.elapsedRealtime] 累加而不是「当前时间 - 开始时间」：
     * 直播流没有进度条，暂停 / 缓冲期间的时间不该算进去。
     * 半秒一跳，秒数变化看起来才跟得上。
     */
    private fun startStopwatch() {
        stopwatchJob?.cancel()
        stopwatchJob = viewModelScope.launch {
            var last = SystemClock.elapsedRealtime()
            while (isActive) {
                delay(500)
                val now = SystemClock.elapsedRealtime()
                val delta = now - last
                last = now
                if (isPlaying) elapsedMs += delta
            }
        }
    }

    /** 现取一个可用的播放器，并确保监听挂在它身上 */
    private fun player(): ExoPlayer {
        val p = PlaybackEngine.player(getApplication(), settingsStore.current.bufferSeconds)
        attach(p)
        return p
    }

    private fun attach(p: ExoPlayer) {
        if (listened === p) return
        listened?.let { old -> runCatching { old.removeListener(playerListener) } }
        p.addListener(playerListener)
        listened = p
        syncState(p)

        // 换播放器（改缓冲时间会重建）之后增益会掉回 1.0，
        // 这里把当前台的补上，否则改一次缓冲所有对齐就白做了。
        // 响度计也是新的，没档案的台要从头测，不然这个台就一直没有档案
        p.currentMediaItem?.mediaId?.let { id ->
            stations.value.firstOrNull { it.id == id }?.let { station ->
                PlaybackEngine.gain()?.setGain(gainFor(station))
                if (!hasProfile(id)) beginLoudnessMeasurement(station, freshStart = true)
            }
        }
    }

    private fun syncState(source: ExoPlayer) {
        currentId = source.currentMediaItem?.mediaId
        isPlaying = source.isPlaying
        isBuffering = source.playbackState == Player.STATE_BUFFERING
        if (currentId == null) {
            audioQuality = null
        } else {
            AudioQuality.describe(AudioQuality.selectedFormat(source.currentTracks))
                ?.let { audioQuality = it }
        }
    }

    /**
     * 点整行：切到这个电台并播放。已经在这个电台就不打断，
     * 想暂停请点行尾按钮或底部按钮。
     */
    fun playStation(station: Station) {
        val p = player()
        if (p.currentMediaItem?.mediaId == station.id &&
            (p.isPlaying || p.playbackState == Player.STATE_BUFFERING)
        ) {
            return
        }
        start(station, p)
    }

    /**
     * 行尾按钮：当前电台就播放 / 暂停，不是当前电台就切过去播。
     */
    fun toggleStation(station: Station) {
        val p = player()
        if (p.currentMediaItem?.mediaId != station.id) {
            start(station, p)
            return
        }
        when {
            p.playbackState == Player.STATE_IDLE -> {
                startPlaybackService()
                // 停止过再播，目标电平可能因为新增电台变了，重新套一次
                PlaybackEngine.gain()?.setGain(gainFor(station))
                p.prepare()
                p.play()
                beginLoudnessMeasurement(station, freshStart = false)
            }

            p.isPlaying || p.playbackState == Player.STATE_BUFFERING -> p.pause()

            else -> {
                startPlaybackService()
                p.play()
            }
        }
    }

    private fun start(station: Station, p: ExoPlayer) {
        currentId = station.id
        isBuffering = true
        requestedUrl = station.url
        // 换台就是一次新的收听：时长归零，音质等播放器解析出音轨再填
        elapsedMs = 0L
        audioQuality = null
        startPlaybackService()
        Log.i(TAG, "开始播放「${station.name}」 ${station.url}")

        try {
            // 先设好增益再装载：第一个声音出来就是对齐的，
            // 不会出现「先炸一下再变小」那种突兀感
            PlaybackEngine.gain()?.setGain(gainFor(station))

            // 无条件重新装载：同一个电台再点一次（上次可能失败了）也要重来
            p.setMediaItem(mediaItem(station), 0L)
            p.prepare()
            p.play()
        } catch (e: Exception) {
            isBuffering = false
            message = "播放失败：${e.message ?: "播放器没能启动"}"
        }

        // 回读校验：播放器真的换了才算数，避免界面假装在播
        if (p.currentMediaItem?.mediaId != station.id) {
            isBuffering = false
            Log.w(TAG, "媒体项没有切换成功：期望 ${station.id}，实际 ${p.currentMediaItem?.mediaId}")
            syncState(p)
            message = "没能切换到「${station.name}」，请再试一次"
        } else {
            // 换台之后重新开始测：上一个台的累计不能混进来
            beginLoudnessMeasurement(station, freshStart = true)
        }
    }

    /**
     * 底部那个播放 / 停止按钮。
     * 停止 = player.stop()，直接把流断开（不再偷偷缓冲），再点播放会重新连。
     */
    fun playOrStop() {
        val p = player()
        if (p.currentMediaItem == null) {
            stations.value.firstOrNull()?.let { start(it, p) }
            return
        }

        when {
            p.playbackState == Player.STATE_IDLE -> {
                startPlaybackService()
                stations.value.firstOrNull { it.id == currentId }?.let { station ->
                    PlaybackEngine.gain()?.setGain(gainFor(station))
                    beginLoudnessMeasurement(station, freshStart = false)
                }
                p.prepare()
                p.play()
            }

            p.isPlaying || p.playbackState == Player.STATE_BUFFERING -> stopNow(p)

            else -> {
                startPlaybackService()
                p.play()
            }
        }
    }

    fun addStation(name: String, url: String): Station {
        val station = store.add(name, url)
        // 刚加进来的台没有档案，先去后台静音听一段把它测出来：
        // 等用户点播时它和听过的老台一样，第一声就是对齐的
        measureQueue.enqueue(listOf(station))
        return station
    }

    fun updateStation(id: String, name: String, url: String) {
        val before = stations.value.firstOrNull { it.id == id }
        store.update(id, name, url)
        val after = stations.value.firstOrNull { it.id == id }

        // 换了链接就是另一个流，旧档案已经作废，重新预听一遍
        if (before != null && after != null && before.url != after.url) {
            measureQueue.forget(id)
            measureQueue.enqueue(listOf(after))
        }

        val p = player()
        val current = p.currentMediaItem ?: return
        if (current.mediaId != id) return

        val sameUrl = current.localConfiguration?.uri?.toString() == url
        if (sameUrl) {
            // 只改了名字：更新通知栏和锁屏上的信息，不打断播放
            val refreshed = current.buildUpon()
                .setMediaMetadata(
                    current.mediaMetadata.buildUpon()
                        .setTitle(name)
                        .build()
                )
                .build()
            p.replaceMediaItem(p.currentMediaItemIndex, refreshed)
        } else {
            // 链接换了，只能停下重新加载，等用户再点一次
            p.stop()
            p.clearMediaItems()
            resetPlayedInfo()
            syncState(p)
        }
    }

    fun removeStation(id: String) {
        store.remove(id)
        measureQueue.drop(id)
        val p = player()
        if (p.currentMediaItem?.mediaId == id) {
            p.stop()
            p.clearMediaItems()
            resetPlayedInfo()
            syncState(p)
        }
    }

    /** 停止并清空：底部条上的时长回到 0，音质信息也一起清掉 */
    private fun stopNow(p: ExoPlayer) {
        p.stop()
        resetPlayedInfo()
    }

    private fun resetPlayedInfo() {
        elapsedMs = 0L
        audioQuality = null
    }

    // ------------------------------------------------------------------
    // 音量对齐
    // ------------------------------------------------------------------

    /** 所有已测台的响度，算目标电平用 */
    private fun allLoudness(): List<Float?> = stations.value.map { it.loudnessLufs }

    /**
     * 目标电平：已测台的中位数；只有一个已测台时以它为参照。
     *
     * 第二种情况专门照顾「刚装好 / 刚加第二台」：这时候中位数算不出来，
     * 但用户耳朵里已经有了「正常音量」的基准，新台朝它对齐是对的。
     */
    private fun targetLufs(): Float? {
        val all = allLoudness()
        return VolumeLevel.target(all) ?: VolumeLevel.anchor(all)
    }

    private fun hasProfile(id: String): Boolean =
        stations.value.firstOrNull { it.id == id }?.let {
            it.loudnessLufs != null && it.peakDbfs != null
        } == true

    /**
     * 这个台该用多大增益（线性系数）。关掉开关、或者这个台还没测过，就是 1.0（原样播）。
     */
    private fun gainFor(station: Station): Float {
        if (!settingsStore.current.normalizeVolume) return 1f
        return VolumeLevel.linear(
            VolumeLevel.gainDb(station.loudnessLufs, station.peakDbfs, targetLufs())
        )
    }

    /**
     * 跟着音频跑一遍响度测量。每个台只需要成功测一次，
     * 结果存进电台档案，之后每次播放直接查表，不再耗 CPU。
     *
     * 新加的台正常情况下已经有预听档案，走不到这里；走到的都是「刚加就点播」
     * 或者预听没成功的情况 —— 所以这里不光要等正式结果，前几秒就得先用短窗读数
     * 把明显不对的电平拉回来，别让用户先听十秒原声。
     *
     * @param freshStart 换台时把累计值清零；暂停后接着播则保留上次的数据
     */
    private fun beginLoudnessMeasurement(station: Station, freshStart: Boolean) {
        measureJob?.cancel()

        val meter = PlaybackEngine.loudness() ?: return
        if (freshStart) meter.startOver()

        // 测过（响度和峰值都有）就别再测；功能关着也不测。
        // 1.9 留下的档案只有响度没有峰值，要补测一次才能参与放大
        if (station.loudnessLufs != null && station.peakDbfs != null) return
        if (!settingsStore.current.normalizeVolume) return

        measureJob = viewModelScope.launch {
            // 上一次冷启动用的值，用来判断「这次要不要动」——频繁小改会比不调更烦人
            var appliedDb = Float.NaN

            while (isActive) {
                delay(400)
                if (currentId != station.id) return@launch

                // 后台预听可能先一步把档案建好了，那就别再用播放中的读数覆盖它
                if (hasProfile(station.id)) {
                    applyGainFor(station.id)
                    return@launch
                }

                // 缓冲时长变了会重建播放器，meter 跟着换，所以每次现取
                val reading = PlaybackEngine.loudness()?.reading() ?: continue

                if (reading.converged) {
                    Log.i(
                        TAG,
                        "「${station.name}」响度 ${reading.lufs} LUFS、峰值 ${reading.peakDbfs} dBFS，" +
                            "用时 ${reading.seconds}s"
                    )
                    store.setLoudness(station.id, reading.lufs, reading.peakDbfs)
                    applyGainFor(station.id)
                    return@launch
                }

                // 冷启动：攒够 2.4 秒就先对一版，范围收紧（−8 ~ +3 dB），
                // 变化小于 1.5 dB 就不动，免得前几秒音量一点点地爬
                if (reading.seconds < VolumeLevel.EARLY_SECONDS) continue
                val early = VolumeLevel.earlyGainDb(
                    reading.lufs,
                    reading.peakDbfs,
                    targetLufs(),
                )
                if (early.isNaN()) continue
                if (!appliedDb.isNaN() && abs(early - appliedDb) < EARLY_STEP_DB) continue

                appliedDb = early
                PlaybackEngine.gain()?.setGain(VolumeLevel.linear(early))
                Log.i(TAG, "「${station.name}」先用短窗读数对到 ${early} dB（${reading.seconds}s）")
            }
        }
    }

    /**
     * 后台静音预听一个台。等主播放器缓冲完再动手 ——
     * 两条流同时拉，先出声的那个会被拖慢。
     */
    private suspend fun measureInBackground(station: Station): LoudnessReading? {
        var waited = 0L
        while (isBuffering && waited < PROBE_WAIT_MS) {
            delay(500)
            waited += 500
        }
        if (!settingsStore.current.normalizeVolume) return null
        return LoudnessProbe(getApplication()).measure(station.url)
    }

    /**
     * 把算好的增益套到正在播的台上。
     *
     * 不用在这里写渐变：增益在音频线程里按 buffer 线性推过去，
     * 听到一半音量变了也不会「啪」一下。
     */
    private fun applyGainFor(id: String) {
        val p = PlaybackEngine.current() ?: return
        if (p.currentMediaItem?.mediaId != id) return
        val station = stations.value.firstOrNull { it.id == id } ?: return
        PlaybackEngine.gain()?.setGain(gainFor(station))
    }

    /** 目标电平会随着新档案微调，正在播的台跟着重新对齐一次 */
    private fun applyGainForCurrent() {
        val id = PlaybackEngine.current()?.currentMediaItem?.mediaId ?: return
        applyGainFor(id)
    }

    /** 设置页的「自动音量」开关 */
    fun setNormalizeVolume(enabled: Boolean) {
        settingsStore.setNormalizeVolume(enabled)

        if (!enabled) {
            // 关掉就立刻回到原始电平，别再让队列在后台偷偷拉流
            measureQueue.cancel()
            PlaybackEngine.gain()?.setGain(1f)
            return
        }

        // 打开：正在播的台立刻对齐，库里还没建档的台全部排进预听队列
        val station = PlaybackEngine.current()?.currentMediaItem?.mediaId
            ?.let { id -> stations.value.firstOrNull { it.id == id } }
        if (station != null) {
            applyGainFor(station.id)
            if (!hasProfile(station.id)) {
                beginLoudnessMeasurement(station, freshStart = true)
            }
        }
        measureQueue.enqueue(stations.value)
    }

    /** 拖拽排序松手后调用，按新顺序落盘 */
    fun reorderStations(orderedIds: List<String>) {
        store.reorder(orderedIds)
    }

    fun consumeMessage() {
        message = null
    }

    fun setBufferSeconds(seconds: Int) {
        settingsStore.setBufferSeconds(seconds)
        // 参数变了就重建播放器，正在播的电台会带过去重连
        player()
    }

    /**
     * 导入 M3U / M3U8 播放列表。文件是系统选择器给的 Uri，
     * 不需要存储权限，读完就完事。
     */
    fun importPlaylist(uri: Uri) {
        if (isImporting) return
        isImporting = true

        viewModelScope.launch {
            val result = try {
                M3uImporter.read(getApplication(), uri)
            } catch (e: Exception) {
                null
            }
            isImporting = false

            message = when {
                result == null -> "这个文件读不出来，换一个试试"
                result.isHlsPlaylist ->
                    "这是 HLS 流的清单文件，不是电台列表，直接把它的网址加进来就行"

                result.entries.isEmpty() -> "文件里没找到电台链接"

                else -> {
                    val known = stations.value.mapTo(HashSet()) { it.id }
                    val outcome = store.addAll(result.entries)
                    // 新加进来的这批同样要预听：导入完就建档，点谁都是对齐的
                    measureQueue.enqueue(stations.value.filterNot { it.id in known })
                    when {
                        outcome.added > 0 -> buildString {
                            append("导入 ${outcome.added} 个电台")
                            if (outcome.duplicated > 0) append("，跳过 ${outcome.duplicated} 个重复的")
                            if (outcome.invalid > 0) append("，忽略 ${outcome.invalid} 个无效链接")
                            // 后台要静音听一遍才知道每个台多响，说一声免得用户奇怪在跑流量
                            if (settingsStore.current.normalizeVolume) {
                                append("，正在后台校准音量")
                            }
                        }

                        outcome.duplicated > 0 -> "这些电台都已经在列表里了"
                        else -> "文件里没有可用的电台链接"
                    }
                }
            }
        }
    }

    /**
     * 导出当前列表为 M3U。位置由系统文件选择器指定，不需要存储权限。
     */
    fun exportPlaylist(uri: Uri) {
        if (isExporting) return
        val list = stations.value
        if (list.isEmpty()) {
            message = "还没有电台可以导出"
            return
        }
        isExporting = true

        viewModelScope.launch {
            val ok = M3uExporter.write(getApplication(), uri, list)
            isExporting = false
            message = if (ok) {
                "已导出 ${list.size} 个电台"
            } else {
                "导出失败，换个保存位置试试"
            }
        }
    }

    private fun mediaItem(station: Station): MediaItem {        val metadata = MediaMetadata.Builder()
            .setTitle(station.name)
            .setArtist("网络电台")
            .setIsBrowsable(false)
            .setIsPlayable(true)

        // 有封面，通知栏 / 灵动岛上才有图可显示
        artwork()?.let { metadata.setArtworkData(it, MediaMetadata.PICTURE_TYPE_FRONT_COVER) }

        val builder = MediaItem.Builder()
            .setMediaId(station.id)
            .setUri(station.url)
            .setMediaMetadata(metadata.build())
        // 明确告诉播放器这是 HLS 清单：地址带 token / 不带扩展名时也能正确路由
        streamMimeType(station.url)?.let { builder.setMimeType(it) }
        return builder.build()
    }

    private fun artwork(): ByteArray? = cachedArtwork ?: createArtwork()?.also { cachedArtwork = it }

    private fun createArtwork(): ByteArray? = try {
        val size = 512
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        // 和桌面图标同一套渐变 + 白弧，通知栏 / 灵动岛里才对得上。
        // 封面是满幅方形，前景稍微放大一点，符号才不至于缩在中间。
        ContextCompat.getDrawable(getApplication(), R.drawable.ic_launcher_background)?.let { bg ->
            bg.setBounds(0, 0, size, size)
            bg.draw(canvas)
        }
        ContextCompat.getDrawable(getApplication(), R.drawable.ic_launcher_foreground)?.let { fg ->
            canvas.save()
            canvas.scale(1.28f, 1.28f, size / 2f, size / 2f)
            fg.setBounds(0, 0, size, size)
            fg.draw(canvas)
            canvas.restore()
        }
        val bytes = ByteArrayOutputStream().use { out ->
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
            out.toByteArray()
        }
        bitmap.recycle()
        bytes
    } catch (e: Exception) {
        null
    }

    private fun startPlaybackService() {
        try {
            getApplication<Application>().startService(
                Intent(getApplication(), PlaybackService::class.java)
            )
        } catch (e: Exception) {
            // 服务起不来也能播，只是没有通知栏和锁屏控制
        }
    }

    override fun onCleared() {
        stopwatchJob?.cancel()
        stopwatchJob = null
        measureJob?.cancel()
        measureJob = null
        measureQueue.cancel()
        PlaybackEngine.removeSwapListener(swapListener)
        listened?.let { runCatching { it.removeListener(playerListener) } }
        listened = null
        // 播放器本身留给 PlaybackEngine，后台播放和通知都不受影响
        super.onCleared()
    }

    private companion object {
        const val TAG = "PlayerViewModel"

        /** 冷启动时两次调整之间至少差这么多才动手，避免前几秒音量一点点爬 */
        const val EARLY_STEP_DB = 1.5f

        /** 主播放器在缓冲时最多等这么久，之后照测不误 */
        const val PROBE_WAIT_MS = 10_000L

        var cachedArtwork: ByteArray? = null
    }
}
