package com.radio.simple.playback

import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.Tracks
import java.util.Locale
import kotlin.math.roundToInt

/**
 * 把播放器解出来的音频格式翻成一行能看懂的音质说明，例如
 * `AAC-LC · 128 kbps · 44.1 kHz`。
 *
 * 直播流不一定同时给出码率和采样率，缺哪项就不写哪项，宁可短一点，
 * 也不要为了凑字数写个「0 kbps」。
 */
internal object AudioQuality {

    /** 从当前音轨里取格式；没有音轨时返回 null */
    fun selectedFormat(tracks: Tracks?): Format? {
        if (tracks == null) return null
        val audio = tracks.groups.filter { it.type == C.TRACK_TYPE_AUDIO }
        // 正常情况有且只有一组被选中；万一 track selector 还没标记选中，就退到第一组可用的
        val group = audio.firstOrNull { it.isSelected } ?: audio.firstOrNull { it.isSupported }
            ?: return null
        for (index in 0 until group.length) {
            if (group.isTrackSupported(index)) return group.getTrackFormat(index)
        }
        return null
    }

    /** 一行音质文案；完全没信息时返回 null，界面上就不显示 */
    fun describe(format: Format?): String? {
        if (format == null) return null
        val codec = codecName(format.sampleMimeType, format.codecs) ?: return null

        // 只用平均码率：media3 里的 bitrate / peakBitrate 是「上限」，
        // 拿它当音质会虚高（比如 320 kbps 的峰值其实平均只有 128）。
        val kbps = format.averageBitrate.takeIf { it > 0 }
            ?.let { (it / 1000f).roundToInt() }
            ?.takeIf { it > 0 }

        return buildList {
            add(codec)
            if (kbps != null) add("$kbps kbps")
            sampleRateLabel(format.sampleRate)?.let { add(it) }
        }.joinToString(" · ")
    }

    private fun codecName(mimeType: String?, codecs: String?): String? {
        val mime = mimeType?.lowercase(Locale.ROOT) ?: return null
        return when {
            mime.contains("mp4a") || mime == "audio/aac" -> when {
                codecs == null -> "AAC"
                codecs.contains("40.5") || codecs.contains("40.29") -> "HE-AAC"
                codecs.contains("40.2") -> "AAC-LC"
                else -> "AAC"
            }

            mime.contains("mpeg") -> "MP3"
            mime.contains("opus") -> "Opus"
            mime.contains("vorbis") -> "Vorbis"
            mime.contains("flac") -> "FLAC"
            mime.contains("alac") -> "ALAC"
            mime.contains("eac3") -> "E-AC-3"
            mime.contains("ac3") -> "AC-3"
            mime.contains("wma") -> "WMA"
            mime.contains("pcm") || mime == "audio/raw" -> "PCM"
            else -> null
        }
    }

    private fun sampleRateLabel(sampleRate: Int): String? {
        if (sampleRate <= 0) return null
        val khz = sampleRate / 1000f
        val text = if (khz == khz.roundToInt().toFloat()) {
            khz.roundToInt().toString()
        } else {
            String.format(Locale.ROOT, "%.1f", khz)
        }
        return "$text kHz"
    }
}
