package com.radio.simple.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID
import kotlin.math.abs

/**
 * 一次导入的结果，用来给用户一句准确的提示。
 */
data class ImportOutcome(
    val added: Int,
    val duplicated: Int,
    val invalid: Int,
)

/**
 * 电台列表的本地存储。用 SharedPreferences 存一份 JSON，够轻量，
 * 也不依赖数据库框架。列表通过 StateFlow 暴露给界面。
 */
class StationStore(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val _stations = MutableStateFlow(read())
    val stations: StateFlow<List<Station>> = _stations.asStateFlow()

    fun add(name: String, url: String): Station {
        val station = Station(
            id = UUID.randomUUID().toString(),
            name = name.trim(),
            url = url.trim(),
        )
        persist(_stations.value + station)
        return station
    }

    fun update(id: String, name: String, url: String) {
        persist(
            _stations.value.map { station ->
                if (station.id == id) {
                    val trimmedUrl = url.trim()
                    station.copy(
                        name = name.trim(),
                        url = trimmedUrl,
                        // 换了链接就是另一个流，旧的响度档案作废，下次重新测
                        loudnessLufs = if (trimmedUrl == station.url) {
                            station.loudnessLufs
                        } else {
                            null
                        },
                        peakDbfs = if (trimmedUrl == station.url) {
                            station.peakDbfs
                        } else {
                            null
                        },
                    )
                } else {
                    station
                }
            }
        )
    }

    /**
     * 记下某个台的实测响度与峰值。两者是一起测出来的，必须一起存 ——
     * 只有响度没有峰值的话，这个台就只能压不能抬。
     *
     * 响度变化不到 0.1 就不写盘：测量会有小幅抖动，没必要为这点差别反复改文件。
     */
    fun setLoudness(id: String, lufs: Float, peakDbfs: Float) {
        if (!lufs.isFinite()) return
        val list = _stations.value
        val index = list.indexOfFirst { it.id == id }
        if (index < 0) return

        val old = list[index]
        val peak = peakDbfs.takeIf { it.isFinite() }
        if (old.loudnessLufs != null && abs(old.loudnessLufs - lufs) < 0.1f && old.peakDbfs == peak) {
            return
        }

        persist(
            list.toMutableList().also {
                it[index] = it[index].copy(loudnessLufs = lufs, peakDbfs = peak)
            }
        )
    }

    fun remove(id: String) {
        persist(_stations.value.filterNot { it.id == id })
    }

    /**
     * 按传进来的 id 顺序重排。
     *
     * 不认识的 id 直接忽略，没提到的电台按原顺序补在后面 —— 排序只该调整顺序，
     * 绝不能因为一次拖拽把某个电台弄丢。
     */
    fun reorder(orderedIds: List<String>) {
        val current = _stations.value
        if (current.isEmpty()) return

        val byId = current.associateBy { it.id }
        val mentioned = orderedIds.toHashSet()
        val reordered = orderedIds.mapNotNull { byId[it] } +
            current.filterNot { it.id in mentioned }

        if (reordered == current) return
        persist(reordered)
    }

    /**
     * 批量导入电台，链接重复的直接跳过（和已有列表比、文件内部也比）。
     */
    fun addAll(entries: List<M3uParser.Entry>): ImportOutcome {
        if (entries.isEmpty()) return ImportOutcome(0, 0, 0)

        val list = _stations.value.toMutableList()
        val keys = list.mapTo(HashSet()) { streamKey(it.url) }
        var added = 0
        var duplicated = 0
        var invalid = 0

        entries.forEach { entry ->
            val url = normalizeStreamUrl(entry.url)
            if (url == null) {
                invalid++
                return@forEach
            }
            if (!keys.add(streamKey(url))) {
                duplicated++
                return@forEach
            }
            list += Station(
                id = UUID.randomUUID().toString(),
                name = entry.name.trim().ifBlank { fallbackStationName(url) },
                url = url,
            )
            added++
        }

        if (added > 0) persist(list)
        return ImportOutcome(added = added, duplicated = duplicated, invalid = invalid)
    }

    private fun persist(list: List<Station>) {
        _stations.value = list
        val array = JSONArray()
        list.forEach { station ->
            val obj = JSONObject()
                .put(KEY_ID, station.id)
                .put(KEY_NAME, station.name)
                .put(KEY_URL, station.url)
            // 没测过的台不写这两个字段，旧版本读到的 JSON 也照样能解析
            station.loudnessLufs?.let { obj.put(KEY_LOUDNESS, it.toDouble()) }
            station.peakDbfs?.let { obj.put(KEY_PEAK, it.toDouble()) }
            array.put(obj)
        }
        prefs.edit().putString(KEY_LIST, array.toString()).apply()
    }

    private fun read(): List<Station> {
        val raw = prefs.getString(KEY_LIST, null) ?: return emptyList()
        return try {
            val array = JSONArray(raw)
            buildList {
                for (index in 0 until array.length()) {
                    val obj = array.optJSONObject(index) ?: continue
                    val name = obj.optString(KEY_NAME).trim()
                    val url = obj.optString(KEY_URL).trim()
                    if (name.isEmpty() || url.isEmpty()) continue
                    val id = obj.optString(KEY_ID).ifBlank { UUID.randomUUID().toString() }
                    val loudness = obj.optDouble(KEY_LOUDNESS, Double.NaN)
                        .takeIf { it.isFinite() }
                        ?.toFloat()
                    // 1.9 及更早的档案只有响度没有峰值，读出来是 null，
                    // 补测之前按「只压不抬」处理
                    val peak = obj.optDouble(KEY_PEAK, Double.NaN)
                        .takeIf { it.isFinite() }
                        ?.toFloat()
                    add(
                        Station(
                            id = id,
                            name = name,
                            url = url,
                            loudnessLufs = loudness,
                            peakDbfs = peak,
                        )
                    )
                }
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    private companion object {
        const val PREFS_NAME = "radio_stations"
        const val KEY_LIST = "stations_json"
        const val KEY_ID = "id"
        const val KEY_NAME = "name"
        const val KEY_URL = "url"
        const val KEY_LOUDNESS = "lufs"
        const val KEY_PEAK = "peak"
    }
}
