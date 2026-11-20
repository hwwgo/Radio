package com.radio.simple.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.radio.simple.data.Station
import com.radio.simple.data.normalizeStreamUrl
import kotlinx.coroutines.delay

/**
 * 新增 / 编辑电台的对话框。station 为 null 表示新增。
 */
@Composable
fun StationEditorDialog(
    station: Station?,
    onDismiss: () -> Unit,
    onConfirm: (name: String, url: String) -> Unit,
    onDelete: (() -> Unit)? = null,
) {
    val isNew = station == null

    var name by rememberSaveable(station?.id) { mutableStateOf(station?.name.orEmpty()) }
    var url by rememberSaveable(station?.id) { mutableStateOf(station?.url.orEmpty()) }
    var nameError by rememberSaveable(station?.id) { mutableStateOf(false) }
    var urlError by rememberSaveable(station?.id) { mutableStateOf<String?>(null) }

    val focusRequester = remember { FocusRequester() }
    val focusManager = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current

    LaunchedEffect(station?.id) {
        if (isNew) {
            delay(150)
            focusRequester.requestFocus()
            keyboard?.show()
        }
    }

    fun submit() {
        val trimmedName = name.trim()
        val normalizedUrl = normalizeStreamUrl(url)

        nameError = trimmedName.isEmpty()
        urlError = when {
            url.isBlank() -> "请填写播放链接"
            normalizedUrl == null -> "这个地址看起来不对，示例：http://example.com/stream.mp3"
            else -> null
        }

        if (!nameError && normalizedUrl != null) {
            onConfirm(trimmedName, normalizedUrl)
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(if (isNew) "添加电台" else "编辑电台")
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = {
                        name = it
                        if (nameError && it.isNotBlank()) nameError = false
                    },
                    label = { Text("电台名称") },
                    placeholder = { Text("例如：城市音乐广播") },
                    singleLine = true,
                    isError = nameError,
                    supportingText = if (nameError) {
                        { Text("名称不能为空") }
                    } else {
                        null
                    },
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                    keyboardActions = KeyboardActions(
                        onNext = { focusManager.moveFocus(FocusDirection.Down) }
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .focusRequester(focusRequester)
                        .testTag("station_name"),
                )

                OutlinedTextField(
                    value = url,
                    onValueChange = {
                        url = it
                        if (urlError != null) urlError = null
                    },
                    label = { Text("播放链接") },
                    placeholder = { Text("http://example.com/stream.mp3") },
                    singleLine = true,
                    isError = urlError != null,
                    supportingText = {
                        Text(
                            text = urlError
                                ?: "支持 http / https 流地址，如 m3u8、mp3、aac，也可以只填域名",
                            color = if (urlError != null) {
                                MaterialTheme.colorScheme.error
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                        )
                    },
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Uri,
                        imeAction = ImeAction.Done,
                    ),
                    keyboardActions = KeyboardActions(onDone = { submit() }),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("station_url"),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { submit() }) {
                Text("保存")
            }
        },
        dismissButton = {
            Row {
                if (!isNew && onDelete != null) {
                    TextButton(onClick = onDelete) {
                        Text("删除", color = MaterialTheme.colorScheme.error)
                    }
                }
                TextButton(onClick = onDismiss) {
                    Text("取消")
                }
            }
        },
    )
}
