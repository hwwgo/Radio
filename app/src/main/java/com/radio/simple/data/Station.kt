package com.radio.simple.data

import android.net.Uri

/**
 * 一个电台：名字、播放链接，外加一个「有多响」的档案。
 *
 * [loudnessLufs] 是第一次收听时实测出来的积分响度（LKFS，越负越轻）。
 * [peakDbfs] 是同一次测量里最响的采样值（dBFS），决定这个台还能往上抬多少 ——
 * 没有它就只能压不能抬，所以老版本留下的 null 档案会在下次播放时补测。
 *
 * 两个字段都是 null 说明还没测过，不影响使用。
 */
data class Station(
    val id: String,
    val name: String,
    val url: String,
    val loudnessLufs: Float? = null,
    val peakDbfs: Float? = null,
)

/**
 * 把用户随手粘进来的地址整理成可用的播放地址。
 * 支持 http / https / rtsp / mms / rtmp，省略协议时默认补 http://
 * 返回 null 表示这串东西不像一个地址。
 */
fun normalizeStreamUrl(raw: String): String? {
    val text = raw.trim()
    if (text.isEmpty()) return null

    val withScheme = if (text.contains("://")) text else "http://$text"
    return try {
        val uri = Uri.parse(withScheme)
        val scheme = uri.scheme?.lowercase()
        val host = uri.host
        val allowed = setOf("http", "https", "rtsp", "mms", "rtmp")
        if (scheme == null || scheme !in allowed) return null
        if (host.isNullOrBlank()) return null

        // 主机名至少得像个域名、IP 或者 localhost，别把随手打的一串字当成地址
        val isLocalhost = host.equals("localhost", ignoreCase = true)
        if (!host.contains('.') && !isLocalhost && host != "::1") return null

        withScheme
    } catch (e: Exception) {
        null
    }
}

/**
 * 判断两个链接是不是同一个流。大小写差异、默认端口、结尾斜杠都不算差别，
 * 导入时用它去重。
 */
fun streamKey(url: String): String {
    val text = url.trim()
    return try {
        val uri = Uri.parse(text)
        val scheme = uri.scheme?.lowercase().orEmpty()
        val host = uri.host?.lowercase().orEmpty()
        val port = uri.port
        val isDefaultPort = (scheme == "http" && port == 80) || (scheme == "https" && port == 443)
        val portPart = if (port == -1 || isDefaultPort) "" else ":$port"
        val path = uri.path.orEmpty()
        val query = uri.query?.let { "?$it" }.orEmpty()
        "$scheme://$host$portPart$path$query"
    } catch (e: Exception) {
        text.lowercase()
    }
}

/**
 * 播放列表里没写名字时，从地址里凑一个能看的名字出来。
 */
fun fallbackStationName(url: String): String {
    return try {
        val uri = Uri.parse(url)
        val lastSegment = uri.path.orEmpty().substringAfterLast('/', "")
        val stem = lastSegment.substringBeforeLast('.').trim()
        if (stem.isNotEmpty() && stem != "/") {
            Uri.decode(stem).replace('_', ' ').trim()
        } else {
            uri.host.orEmpty().ifBlank { "未命名电台" }
        }
    } catch (e: Exception) {
        "未命名电台"
    }
}
