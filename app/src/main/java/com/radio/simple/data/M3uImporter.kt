package com.radio.simple.data

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction

/**
 * 从用户选的 M3U 文件里读出电台。文件通过系统文件选择器（SAF）给到 Uri，
 * 所以不需要任何存储权限。
 */
object M3uImporter {

    /** 播放列表是纯文本，超过这个大小基本可以肯定是选错文件了 */
    private const val MAX_BYTES = 4 * 1024 * 1024

    /** 读不出来（文件走丢、没有读取权限、太大）时返回 null */
    suspend fun read(context: Context, uri: Uri): M3uParser.Result? = withContext(Dispatchers.IO) {
        try {
            val bytes = context.contentResolver.openInputStream(uri)?.use { input ->
                readLimited(input, MAX_BYTES)
            } ?: return@withContext null

            M3uParser.parse(decode(bytes))
        } catch (e: Exception) {
            null
        }
    }

    private fun readLimited(input: InputStream, limit: Int): ByteArray? {
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(8 * 1024)
        var total = 0
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            out.write(buffer, 0, read)
            total += read
            if (total > limit) return null
        }
        return out.toByteArray()
    }

    /**
     * 先按 UTF-8 严格解码，解不通再退回 GBK —— 国内不少老的电台列表是 GBK 存的，
     * 直接当 UTF-8 读会出来一堆乱码名字。
     */
    private fun decode(bytes: ByteArray): String {
        if (bytes.isEmpty()) return ""
        try {
            val decoder = Charsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
            return decoder.decode(ByteBuffer.wrap(bytes)).toString()
        } catch (e: CharacterCodingException) {
            // 继续往下试
        }
        return try {
            String(bytes, Charset.forName("GBK"))
        } catch (e: Exception) {
            String(bytes, Charsets.ISO_8859_1)
        }
    }
}
