package com.radio.simple.data

/**
 * M3U / M3U8 文本播放列表的解析。纯文本处理，不碰文件系统，
 * 所以可以直接写单元测试跑。
 *
 * 认得的写法：
 * ```
 * #EXTM3U
 * #EXTINF:-1 tvg-logo="..." group-title="音乐",城市音乐广播
 * http://example.com/live.mp3
 * http://example.com/plain-url.aac
 * ```
 * 没有 #EXTINF 的行直接用地址推一个名字；其余 # 开头的行当注释跳过。
 */
object M3uParser {

    data class Entry(val name: String, val url: String)

    data class Result(
        val entries: List<Entry>,
        /**
         * 文件里出现了 #EXT-X-* 标签，说明这是 HLS 的媒体清单（分片列表），
         * 不是电台列表，导入进来没有任何意义。
         */
        val isHlsPlaylist: Boolean,
    )

    fun parse(raw: String): Result {
        val text = raw.removePrefix("\uFEFF")
        val entries = mutableListOf<Entry>()
        var pendingName: String? = null
        var isHls = false

        text.lineSequence().forEach { rawLine ->
            val line = rawLine.trim()
            when {
                line.isEmpty() -> Unit

                line.startsWith("#") -> {
                    // 大小写都认，有些导出工具写的是 #extinf
                    val upper = line.uppercase()
                    if (upper.startsWith("#EXT-X-")) isHls = true
                    if (upper.startsWith("#EXTINF")) {
                        pendingName = extinfName(line)
                    }
                }

                else -> {
                    entries += Entry(
                        name = pendingName ?: fallbackStationName(line),
                        url = line,
                    )
                    pendingName = null
                }
            }
        }

        return Result(entries = entries, isHlsPlaylist = isHls)
    }

    /**
     * #EXTINF 行的名字在最后一个「引号外的逗号」后面，
     * 因为属性值里也可能带逗号（tvg-name="Jazz, Blues"）。
     */
    private fun extinfName(line: String): String? {
        var inQuotes = false
        for (index in line.indices) {
            val char = line[index]
            if (char == '"') {
                inQuotes = !inQuotes
            } else if (char == ',' && !inQuotes) {
                return line.substring(index + 1).trim().trim('"').ifBlank { null }
            }
        }
        return null
    }
}
