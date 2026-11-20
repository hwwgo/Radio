package com.radio.simple.playback

import androidx.media3.common.MimeTypes

/**
 * 从地址猜流的类型。
 *
 * 有些电台的链接带 token、或者干脆没有扩展名（`/live/1234`），
 * 播放器自己嗅探不出来就会按渐进式下载处理，HLS 直接播不了。
 * 地址里写了 `.m3u8` / `.mpd` 就明确告诉它。
 */
fun streamMimeType(url: String): String? {
    val path = url.substringBefore('?').substringBefore('#').lowercase()
    return when {
        path.contains(".m3u8") -> MimeTypes.APPLICATION_M3U8
        path.endsWith(".mpd") -> MimeTypes.APPLICATION_MPD
        else -> null
    }
}
