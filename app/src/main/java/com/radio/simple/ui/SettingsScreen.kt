package com.radio.simple.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.radio.simple.data.RadioSettings
import com.radio.simple.playback.StationMeasureQueue
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * 设置页：添加电台 + 缓冲时长 + M3U 导入 / 导出。
 */
@Composable
fun SettingsPane(
    settings: RadioSettings,
    stationCount: Int,
    isImporting: Boolean,
    isExporting: Boolean,
    measureProgress: StationMeasureQueue.Progress,
    /** 测算出的基准电平（不含用户偏移）；还没测出来是 null */
    measuredTargetLufs: Float?,
    onAddClick: () -> Unit,
    onBufferSecondsChange: (Int) -> Unit,
    onNormalizeVolumeChange: (Boolean) -> Unit,
    onVolumeOffsetChange: (Float) -> Unit,
    onRemeasureClick: () -> Unit,
    onCancelMeasure: () -> Unit,
    onImportClick: () -> Unit,
    onExportClick: () -> Unit,
    /** 是否还在系统的电池优化名单里（true = 还没放行） */
    batteryOptimized: Boolean,
    onBatterySettingsClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // 拖动时先动本地值，松手才落盘，避免每一帧都写一次 SharedPreferences
    var sliderValue by remember(settings.bufferSeconds) {
        mutableFloatStateOf(settings.bufferSeconds.toFloat())
    }
    val shownSeconds = sliderValue.roundToInt()

    // 整体音量偏移：同样本地先行，值变了才往上报
    var offsetValue by remember(settings.volumeOffsetDb) {
        mutableFloatStateOf(settings.volumeOffsetDb)
    }

    Column(
        modifier = modifier
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        SectionLabel("电台")

        SettingsCard {
            Column(modifier = Modifier.padding(20.dp)) {
                Text(
                    text = "添加电台",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    text = "自己填电台名字和播放链接。加好之后回到主界面点一下就能听，" +
                        "长按可以改名字、换链接或者删掉。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    text = if (stationCount > 0) "当前有 $stationCount 个电台" else "还没有电台",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                Spacer(Modifier.height(16.dp))

                Button(
                    onClick = onAddClick,
                    modifier = Modifier.testTag("settings_add_button"),
                ) {
                    Icon(
                        imageVector = AddIcon,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(Modifier.width(8.dp))
                    Text("添加电台")
                }
            }
        }

        SectionLabel("播放")

        SettingsCard {
            Column(modifier = Modifier.padding(20.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "缓冲时间",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        Spacer(Modifier.height(2.dp))
                        Text(
                            text = bufferHint(shownSeconds),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Text(
                        text = "$shownSeconds 秒",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }

                Slider(
                    value = sliderValue,
                    onValueChange = { sliderValue = it },
                    onValueChangeFinished = { onBufferSecondsChange(sliderValue.roundToInt()) },
                    valueRange = RadioSettings.MIN_BUFFER_SECONDS.toFloat()..
                        RadioSettings.MAX_BUFFER_SECONDS.toFloat(),
                    steps = RadioSettings.MAX_BUFFER_SECONDS - RadioSettings.MIN_BUFFER_SECONDS - 1,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 4.dp)
                        .testTag("settings_buffer_slider"),
                )

                Text(
                    text = "点开电台后先攒够这么久的数据再出声。调大更抗卡顿，调小出声更快，" +
                        "改完立刻生效，正在播的电台会重连一次。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                HorizontalDivider(
                    modifier = Modifier.padding(vertical = 18.dp),
                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f),
                )

                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "自动音量",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        Spacer(Modifier.height(2.dp))
                        Text(
                            text = "各台响度对齐，切台不再忽大忽小",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(
                        checked = settings.normalizeVolume,
                        onCheckedChange = onNormalizeVolumeChange,
                        modifier = Modifier
                            .padding(start = 12.dp)
                            .testTag("settings_normalize_switch"),
                    )
                }

                Spacer(Modifier.height(10.dp))
                Text(
                    text = "第一次听某个台时会先测 10 秒，之后这个台的音量就定下来了，" +
                        "下次再听直接生效。响的台压下来、轻的台提上去，都对齐到同一个电平；" +
                        "提多少会按峰值余量自动限住，不会破音。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                if (settings.normalizeVolume) {
                    Spacer(Modifier.height(18.dp))

                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = "整体音量",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.weight(1f),
                        )
                        Text(
                            text = offsetLabel(offsetValue),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier
                                .padding(start = 12.dp)
                                .testTag("settings_volume_offset_value"),
                        )
                    }
                    Spacer(Modifier.height(2.dp))
                    // 说明单独占整行：和右侧数值挤在一行会被压成「…对齐」孤零零一个字
                    Text(
                        text = offsetHint(measuredTargetLufs, offsetValue),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )

                    Slider(
                        value = offsetValue,
                        onValueChange = { raw ->
                            // 吸附到 0.5 dB：手感是一格一格，不会停在 2.37 dB 这种数
                            val step = RadioSettings.VOLUME_OFFSET_STEP_DB
                            val snapped = (raw / step).roundToInt() * step
                            val clamped = snapped.coerceIn(
                                RadioSettings.MIN_VOLUME_OFFSET_DB,
                                RadioSettings.MAX_VOLUME_OFFSET_DB,
                            )
                            if (clamped != offsetValue) {
                                offsetValue = clamped
                                // 实时生效：正在播的台立刻跟着变，边听边调
                                onVolumeOffsetChange(clamped)
                            }
                        },
                        valueRange = RadioSettings.MIN_VOLUME_OFFSET_DB..
                            RadioSettings.MAX_VOLUME_OFFSET_DB,
                        // 不画刻度点：40 个点排成一排太花；值本身还是吸附到 0.5 dB
                        steps = 0,
                        enabled = measuredTargetLufs != null,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 4.dp)
                            .testTag("settings_volume_offset_slider"),
                    )

                    Row(modifier = Modifier.fillMaxWidth()) {
                        Text(
                            text = "轻 10 dB",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.weight(1f))
                        Text(
                            text = "默认",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.weight(1f))
                        Text(
                            text = "响 10 dB",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }

                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = "各台的相对音量不变，只把整体一起调。抬得太多时，" +
                            "本来就贴顶的台会被峰值余量挡住，听着可能跟不上。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                HorizontalDivider(
                    modifier = Modifier.padding(vertical = 18.dp),
                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f),
                )

                Text(
                    text = "重新测量所有电台",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    text = if (stationCount > 0) {
                        "直播节目的响度会变（换时段、换源）。这里把 $stationCount 个台逐个" +
                            "静音预听 10 秒重新建档，再按新的中位数重算增益和衰减，大约需要 " +
                            "${estimateMinutes(stationCount)} 分钟，流量每个台约 160 KB。"
                    } else {
                        "加几个电台之后，可以在这里一次性重新测量。"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                Spacer(Modifier.height(12.dp))

                if (measureProgress.running) {
                    Column(modifier = Modifier.testTag("settings_remeasure_progress")) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(18.dp),
                                strokeWidth = 2.dp,
                            )
                            Spacer(Modifier.width(10.dp))
                            Text(
                                text = "已测 ${measureProgress.done} / ${measureProgress.total}",
                                style = MaterialTheme.typography.bodyMedium,
                                modifier = Modifier.weight(1f),
                            )
                            TextButton(
                                onClick = onCancelMeasure,
                                modifier = Modifier.testTag("settings_remeasure_cancel"),
                            ) {
                                Text("停止")
                            }
                        }
                        LinearProgressIndicator(
                            progress = {
                                measureProgress.done.toFloat() /
                                    measureProgress.total.coerceAtLeast(1)
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(4.dp),
                        )
                        measureProgress.current?.let { name ->
                            Spacer(Modifier.height(8.dp))
                            Text(
                                text = "正在测「$name」",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                } else {
                    FilledTonalButton(
                        onClick = onRemeasureClick,
                        enabled = stationCount > 0,
                        modifier = Modifier.testTag("settings_remeasure_button"),
                    ) {
                        Text("重新测量")
                    }
                }
            }
        }

        SectionLabel("后台播放")

        SettingsCard {
            Column(modifier = Modifier.padding(20.dp)) {
                Text(
                    text = "后台播放保护",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    text = if (batteryOptimized) {
                        "还没有加入电池优化白名单。息屏后厂商的省电策略可能把进程冻结，" +
                            "表现为听几分钟就自己停了。加入白名单后配合前台播放服务，" +
                            "锁屏也能一直听。"
                    } else {
                        "已加入电池优化白名单，锁屏后不会再被系统冻结。" +
                            "部分手机（小米、OPPO、vivo 等）还需要在系统设置里允许" +
                            "「自启动」和「后台运行」，那个开关第三方应用改不了。"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                Spacer(Modifier.height(16.dp))

                FilledTonalButton(
                    onClick = onBatterySettingsClick,
                    enabled = batteryOptimized,
                    modifier = Modifier.testTag("settings_battery_button"),
                ) {
                    Text(if (batteryOptimized) "加入白名单" else "已允许后台运行")
                }
            }
        }

        SectionLabel("播放列表")

        SettingsCard {
            Column(modifier = Modifier.padding(20.dp)) {
                Text(
                    text = "从 M3U 播放列表导入",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    text = "选一个 .m3u 或 .m3u8 文本列表，里面每个电台都会加进列表。" +
                        "名字取 #EXTINF 里写的那个，没写就用链接凑一个。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    text = if (stationCount > 0) {
                        "当前已有 $stationCount 个电台，重复的链接会自动跳过。"
                    } else {
                        "重复的链接会自动跳过。"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                Spacer(Modifier.height(16.dp))

                FilledTonalButton(
                    onClick = onImportClick,
                    enabled = !isImporting,
                    modifier = Modifier.testTag("settings_import_button"),
                ) {
                    if (isImporting) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            strokeWidth = 2.dp,
                        )
                        Spacer(Modifier.width(10.dp))
                        Text("正在读取…")
                    } else {
                        Icon(
                            imageVector = ImportIcon,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                        )
                        Spacer(Modifier.width(8.dp))
                        Text("选择 M3U 文件")
                    }
                }
            }
        }

        SettingsCard {
            Column(modifier = Modifier.padding(20.dp)) {
                Text(
                    text = "导出为 M3U",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    text = "把当前列表里每个电台的名字和链接写成一个 .m3u8 文件，" +
                        "可以留作备份，也能直接拿到别的播放器里用。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    text = if (stationCount > 0) {
                        "会导出 $stationCount 个电台，顺序和主界面一致。"
                    } else {
                        "还没有电台可以导出。"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                Spacer(Modifier.height(16.dp))

                FilledTonalButton(
                    onClick = onExportClick,
                    enabled = !isExporting && stationCount > 0,
                    modifier = Modifier.testTag("settings_export_button"),
                ) {
                    if (isExporting) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            strokeWidth = 2.dp,
                        )
                        Spacer(Modifier.width(10.dp))
                        Text("正在写入…")
                    } else {
                        Icon(
                            imageVector = ExportIcon,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                        )
                        Spacer(Modifier.width(8.dp))
                        Text("导出 M3U 文件")
                    }
                }
            }
        }

        Spacer(Modifier.height(4.dp))
        Text(
            text = "电台和设置都只存在这台手机上，卸载应用会一起清掉。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 8.dp),
        )
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 8.dp, top = 12.dp, bottom = 2.dp),
    )
}

@Composable
private fun SettingsCard(content: @Composable () -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
        content = content,
    )
}

/** 每个台连流加测量按 12 秒估，向上取整到分钟 */
private fun estimateMinutes(count: Int): Int = ((count * 12 + 59) / 60).coerceAtLeast(1)

/** 右侧的偏移读数：0 显示「默认」，其余带正负号，负号用真正的减号 */
private fun offsetLabel(db: Float): String = when {
    abs(db) < 0.01f -> "默认"
    else -> String.format(Locale.US, "%+.1f dB", db).replace('-', '\u2212')
}

/**
 * 说明文字要回答两件事：测出来的平均电平是多少、现在实际对齐到哪。
 *
 * 只显示偏移量不够——用户不知道「+3 dB」是从哪加起来的；
 * 只显示目标也不够——他不知道自己动了多少。两个都给。
 */
private fun offsetHint(measured: Float?, offsetDb: Float): String {
    if (measured == null) {
        return "还没有测出平均电平，播放或重新测量之后才能整体调节"
    }
    val base = String.format(Locale.US, "%.1f", measured).replace('-', '\u2212')
    if (abs(offsetDb) < 0.01f) {
        return "测算出的平均电平 $base LUFS，所有台都朝它对齐"
    }
    val now = String.format(Locale.US, "%.1f", measured + offsetDb).replace('-', '\u2212')
    return "测算出的平均电平 $base LUFS，现在按 $now LUFS 对齐"
}

private fun bufferHint(seconds: Int): String = when {
    seconds <= 2 -> "出声最快，网络一抖就容易断"
    seconds <= 5 -> "快和稳比较平衡"
    seconds <= 12 -> "网络一般也扛得住"
    else -> "很抗卡顿，但点开要等一会儿"
}
