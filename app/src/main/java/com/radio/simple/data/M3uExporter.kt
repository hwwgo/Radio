package com.radio.simple.data

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 把当前电台列表写成 M3U 播放列表。
 *
 * 输出的是最常见的写法（和导入器认的完全一致）：
 * ```
 * #EXTM3U
 * #EXTINF:-1,中国之声
 * http://ngcdn001.cnr.cn/live/zgzs/index.m3u8
 * ```
 * 文件按 UTF-8 编码，所以扩展名用 .m3u8 —— 这也是「中文名字不乱码」的约定。
 */
object M3uExporter {

    /** 生成播放列表文本。纯函数，方便直接写单元测试。 */
    fun build(stations: List<Station>): String = buildString {
        append("#EXTM3U\n")
        stations.forEach { station ->
            append("#EXTINF:-1,").append(singleLine(station.name)).append('\n')
            append(station.url.trim()).append('\n')
        }
    }

    /**
     * 名字里的换行会把一条记录拆成两条，先压平；
     * 逗号不用处理 —— 名字本来就在 #EXTINF 的最后一个逗号之后。
     */
    private fun singleLine(name: String): String = name
        .replace("\r\n", " ")
        .replace('\n', ' ')
        .replace('\r', ' ')
        .trim()

    /**
     * 写到用户通过系统文件选择器指定的位置。不需要任何存储权限。
     * @return 是否写成功
     */
    suspend fun write(context: Context, uri: Uri, stations: List<Station>): Boolean =
        withContext(Dispatchers.IO) {
            try {
                val bytes = build(stations).toByteArray(Charsets.UTF_8)
                val stream = context.contentResolver.openOutputStream(uri)
                    ?: return@withContext false
                stream.use { out ->
                    out.write(bytes)
                    out.flush()
                }
                true
            } catch (e: Exception) {
                false
            }
        }
}
