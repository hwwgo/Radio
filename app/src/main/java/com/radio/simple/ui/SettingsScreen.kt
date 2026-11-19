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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
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
    onAddClick: () -> Unit,
    onBufferSecondsChange: (Int) -> Unit,
    onNormalizeVolumeChange: (Boolean) -> Unit,
    onImportClick: () -> Unit,
    onExportClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // 拖动时先动本地值，松手才落盘，避免每一帧都写一次 SharedPreferences
    var sliderValue by remember(settings.bufferSeconds) {
        mutableFloatStateOf(settings.bufferSeconds.toFloat())
    }
    val shownSeconds = sliderValue.roundToInt()

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

private fun bufferHint(seconds: Int): String = when {
    seconds <= 2 -> "出声最快，网络一抖就容易断"
    seconds <= 5 -> "快和稳比较平衡"
    seconds <= 12 -> "网络一般也扛得住"
    else -> "很抗卡顿，但点开要等一会儿"
}
