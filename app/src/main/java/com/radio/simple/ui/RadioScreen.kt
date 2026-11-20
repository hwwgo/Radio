package com.radio.simple.ui

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.radio.simple.R
import com.radio.simple.data.RadioSettings
import com.radio.simple.data.Station
import com.radio.simple.playback.StationMeasureQueue

/**
 * 系统文件选择器能选到的 M3U 类型。很多文件管理器给 .m3u 报的 MIME 各不相同，
 * 所以干脆把常见几种和「所有文件」都列上。
 */
private val M3U_MIME_TYPES = arrayOf(
    "audio/x-mpegurl",
    "audio/mpegurl",
    "application/vnd.apple.mpegurl",
    "application/x-mpegurl",
    "text/plain",
    "application/octet-stream",
    "*/*",
)

/** 排序模式下每行高度固定，拖拽时才能按「跨过了一行」来算 */
private val SORT_ROW_HEIGHT = 64.dp
private val SORT_ROW_SPACING = 10.dp

/**
 * 接上 ViewModel 的主界面。
 */
@Composable
fun RadioScreen(viewModel: PlayerViewModel = viewModel()) {
    val stations by viewModel.stations.collectAsStateWithLifecycle()
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val measureProgress by viewModel.measureProgress.collectAsStateWithLifecycle()

    RequestRuntimePermissions()

    RadioContent(
        stations = stations,
        settings = settings,
        currentId = viewModel.currentId,
        isPlaying = viewModel.isPlaying,
        isBuffering = viewModel.isBuffering,
        isImporting = viewModel.isImporting,
        isExporting = viewModel.isExporting,
        measureProgress = measureProgress,
        // 每次重组现算：电台档案变了（新台测完、重测完）要跟着更新
        measuredTargetLufs = viewModel.baseTargetLufs(),
        quality = viewModel.audioQuality,
        // 传取值函数而不是数值：秒表每跳一次只让底部条自己重组，列表不用跟着刷
        elapsedMs = { viewModel.elapsedMs },
        message = viewModel.message,
        onMessageShown = viewModel::consumeMessage,
        onToggle = viewModel::toggleStation,
        onAdd = viewModel::addStation,
        onUpdate = viewModel::updateStation,
        onRemove = viewModel::removeStation,
        onPlayStop = viewModel::playOrStop,
        onBufferSecondsChange = viewModel::setBufferSeconds,
        onNormalizeVolumeChange = viewModel::setNormalizeVolume,
        onVolumeOffsetChange = viewModel::setVolumeOffset,
        onRemeasure = viewModel::remeasureAll,
        onCancelMeasure = viewModel::cancelRemeasure,
        onImportPlaylist = viewModel::importPlaylist,
        onExportPlaylist = viewModel::exportPlaylist,
        onReorder = viewModel::reorderStations,
    )
}

/**
 * 纯界面部分，不依赖 ViewModel，方便预览和测试。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RadioContent(
    stations: List<Station>,
    settings: RadioSettings,
    currentId: String?,
    isPlaying: Boolean,
    isBuffering: Boolean,
    isImporting: Boolean,
    isExporting: Boolean,
    measureProgress: StationMeasureQueue.Progress,
    measuredTargetLufs: Float?,
    quality: String?,
    elapsedMs: () -> Long,
    message: String?,
    onMessageShown: () -> Unit,
    onToggle: (Station) -> Unit,
    onAdd: (name: String, url: String) -> Unit,
    onUpdate: (id: String, name: String, url: String) -> Unit,
    onRemove: (id: String) -> Unit,
    onPlayStop: () -> Unit,
    onBufferSecondsChange: (Int) -> Unit,
    onNormalizeVolumeChange: (Boolean) -> Unit,
    onVolumeOffsetChange: (Float) -> Unit,
    onRemeasure: () -> Unit,
    onCancelMeasure: () -> Unit,
    onImportPlaylist: (Uri) -> Unit,
    onExportPlaylist: (Uri) -> Unit,
    onReorder: (orderedIds: List<String>) -> Unit,
) {
    val snackbarHostState = remember { SnackbarHostState() }

    // 电池优化名单：用户去系统设置改完之后回来要刷新，所以挂在 ON_RESUME 上
    val context = LocalContext.current
    var batteryOptimized by remember { mutableStateOf(isBatteryOptimized(context)) }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                batteryOptimized = isBatteryOptimized(context)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // 提到 when 外面：从设置页返回、进出排序模式时滚动位置都还在
    val listState = rememberLazyListState()

    var showSettings by rememberSaveable { mutableStateOf(false) }
    var sorting by rememberSaveable { mutableStateOf(false) }
    var showAddDialog by rememberSaveable { mutableStateOf(false) }
    var editingStation by remember { mutableStateOf<Station?>(null) }
    var pendingDelete by remember { mutableStateOf<Station?>(null) }

    // 系统文件选择器，不需要任何存储权限
    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri != null) onImportPlaylist(uri)
    }
    val pickPlaylist = { importLauncher.launch(M3U_MIME_TYPES) }

    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("audio/x-mpegurl")
    ) { uri: Uri? ->
        if (uri != null) onExportPlaylist(uri)
    }
    val savePlaylist = { exportLauncher.launch(defaultExportFileName()) }

    BackHandler(enabled = showSettings || sorting) {
        if (showSettings) showSettings = false else sorting = false
    }

    LaunchedEffect(message) {
        val text = message ?: return@LaunchedEffect
        onMessageShown()
        snackbarHostState.showSnackbar(text)
    }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            when {
                showSettings -> TopAppBar(
                    title = { Text(text = "设置", fontWeight = FontWeight.SemiBold) },
                    navigationIcon = {
                        IconButton(
                            onClick = { showSettings = false },
                            modifier = Modifier.testTag("settings_back"),
                        ) {
                            Icon(BackIcon, contentDescription = "返回")
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.background,
                        titleContentColor = MaterialTheme.colorScheme.onBackground,
                        navigationIconContentColor = MaterialTheme.colorScheme.onBackground,
                    ),
                )

                sorting -> TopAppBar(
                    title = {
                        Text(
                            text = "调整顺序",
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.SemiBold,
                        )
                    },
                    navigationIcon = {
                        IconButton(onClick = { sorting = false }) {
                            Icon(BackIcon, contentDescription = "返回")
                        }
                    },
                    actions = {
                        TextButton(
                            onClick = { sorting = false },
                            modifier = Modifier.testTag("sorting_done"),
                        ) {
                            Text("完成", fontWeight = FontWeight.SemiBold)
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.background,
                        titleContentColor = MaterialTheme.colorScheme.onBackground,
                        navigationIconContentColor = MaterialTheme.colorScheme.onBackground,
                    ),
                )

                else -> TopAppBar(
                    title = {
                        Text(
                            text = "收音机",
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.SemiBold,
                        )
                    },
                    actions = {
                        if (stations.size > 1) {
                            IconButton(
                                onClick = { sorting = true },
                                modifier = Modifier.testTag("sort_button"),
                            ) {
                                Icon(SortIcon, contentDescription = "调整顺序")
                            }
                        }
                        IconButton(
                            onClick = { showSettings = true },
                            modifier = Modifier.testTag("settings_button"),
                        ) {
                            Icon(SettingsIcon, contentDescription = "设置")
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.background,
                        titleContentColor = MaterialTheme.colorScheme.onBackground,
                        actionIconContentColor = MaterialTheme.colorScheme.onBackground,
                    ),
                )
            }
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
        bottomBar = {
            if (!showSettings && !sorting && stations.isNotEmpty()) {
                PlaybackBar(
                    stationName = stations.firstOrNull { it.id == currentId }?.name,
                    isPlaying = isPlaying,
                    isBuffering = isBuffering,
                    enabled = currentId != null,
                    quality = quality,
                    elapsedMs = elapsedMs,
                    onPlayStop = onPlayStop,
                )
            }
        },
    ) { innerPadding ->
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            // 平板 / 折叠屏展开时别把内容拉成一条横线
            val contentWidth = if (maxWidth > 720.dp) 720.dp else maxWidth
            val contentModifier = Modifier
                .align(Alignment.TopCenter)
                .width(contentWidth)
                .fillMaxHeight()

            when {
                showSettings -> SettingsPane(
                    settings = settings,
                    stationCount = stations.size,
                    isImporting = isImporting,
                    isExporting = isExporting,
                    measureProgress = measureProgress,
                    measuredTargetLufs = measuredTargetLufs,
                    onAddClick = { showAddDialog = true },
                    onBufferSecondsChange = onBufferSecondsChange,
                    onNormalizeVolumeChange = onNormalizeVolumeChange,
                    onVolumeOffsetChange = onVolumeOffsetChange,
                    onRemeasureClick = onRemeasure,
                    onCancelMeasure = onCancelMeasure,
                    onImportClick = pickPlaylist,
                    onExportClick = savePlaylist,
                    batteryOptimized = batteryOptimized,
                    onBatterySettingsClick = { requestIgnoreBatteryOptimization(context) },
                    modifier = contentModifier,
                )

                stations.isEmpty() -> EmptyState(
                    onAdd = { showAddDialog = true },
                    onImport = pickPlaylist,
                    modifier = Modifier.align(Alignment.Center),
                )

                sorting -> SortableStationList(
                    stations = stations,
                    onReorder = onReorder,
                    modifier = contentModifier,
                )

                else -> LazyColumn(
                    modifier = contentModifier,
                    state = listState,
                    contentPadding = PaddingValues(
                        start = 16.dp,
                        end = 16.dp,
                        top = 4.dp,
                        bottom = 24.dp,
                    ),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    items(
                        items = stations,
                        key = { it.id },
                        contentType = { "station" },
                    ) { station ->
                        StationRow(
                            station = station,
                            isCurrent = currentId == station.id,
                            isPlaying = isPlaying,
                            isBuffering = isBuffering,
                            onToggle = { onToggle(station) },
                            onLongClick = { editingStation = station },
                            // 只保留淡入淡出，关掉「位移」动画：列表增删时会逐行
                            // 跑一次 placement 动画，几十行叠在一起就是滚动卡顿的来源
                            modifier = Modifier.animateItem(placementSpec = null),
                        )
                    }
                }
            }
        }
    }

    if (showAddDialog) {
        StationEditorDialog(
            station = null,
            onDismiss = { showAddDialog = false },
            onConfirm = { name, url ->
                onAdd(name, url)
                showAddDialog = false
            },
        )
    }

    editingStation?.let { station ->
        StationEditorDialog(
            station = station,
            onDismiss = { editingStation = null },
            onConfirm = { name, url ->
                onUpdate(station.id, name, url)
                editingStation = null
            },
            onDelete = {
                editingStation = null
                pendingDelete = station
            },
        )
    }

    pendingDelete?.let { station ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("删除这个电台？") },
            text = { Text("“${station.name}”会从列表里移除，链接也会一起删掉。") },
            confirmButton = {
                TextButton(
                    onClick = {
                        onRemove(station.id)
                        pendingDelete = null
                    }
                ) {
                    Text("删除", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) {
                    Text("取消")
                }
            },
        )
    }
}

@Composable
private fun PlaybackBar(
    stationName: String?,
    isPlaying: Boolean,
    isBuffering: Boolean,
    enabled: Boolean,
    quality: String?,
    elapsedMs: () -> Long,
    onPlayStop: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val showStop = isPlaying || isBuffering
    val elapsed = elapsedMs()

    Surface(
        modifier = modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        shadowElevation = 8.dp,
    ) {
        Row(
            modifier = Modifier
                .navigationBarsPadding()
                .padding(start = 20.dp, end = 16.dp, top = 12.dp, bottom = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                // 名字和计时同一行：音质那行需要整行宽度，否则「AAC-LC · 128 kbps ·
                // 44.1 kHz」会被挤成「44.1 …」
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = stationName ?: "还没选电台",
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = if (stationName != null) {
                            MaterialTheme.colorScheme.onSurface
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (elapsed > 0L) {
                        Spacer(Modifier.width(10.dp))
                        Text(
                            text = formatElapsed(elapsed),
                            style = MaterialTheme.typography.labelMedium.copy(
                                fontFeatureSettings = "tnum",
                            ),
                            fontWeight = FontWeight.Medium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                        )
                    }
                }
                Spacer(Modifier.height(3.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = when {
                            stationName == null -> "点电台右边的三角形就能听"
                            isBuffering -> "正在连接…"
                            isPlaying -> "正在播放"
                            // 停止会把计时清零，还留着秒数就说明只是暂停
                            elapsed > 0L -> "已暂停"
                            else -> "已停止"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                    )
                    if (quality != null) {
                        Text(
                            text = " · ",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                            maxLines = 1,
                        )
                        Text(
                            text = quality,
                            modifier = Modifier.weight(1f),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }

            Spacer(Modifier.width(12.dp))

            FilledIconButton(
                onClick = onPlayStop,
                enabled = enabled,
                modifier = Modifier
                    .size(56.dp)
                    .testTag("play_stop_button"),
                shape = CircleShape,
            ) {
                Icon(
                    imageVector = if (showStop) StopIcon else PlayIcon,
                    contentDescription = if (showStop) "停止" else "播放",
                    modifier = Modifier.size(26.dp),
                )
            }
        }
    }
}

/**
 * 已播放时长：一小时以内 `mm:ss`，超过就 `h:mm:ss`。
 * 时长是听众关心的「听了多久」，不是直播流那截没意义的进度。
 */
internal fun formatElapsed(millis: Long): String {
    val totalSeconds = (millis / 1000L).coerceAtLeast(0L)
    val hours = totalSeconds / 3600L
    val minutes = (totalSeconds % 3600L) / 60L
    val seconds = totalSeconds % 60L
    return if (hours > 0) {
        String.format(java.util.Locale.ROOT, "%d:%02d:%02d", hours, minutes, seconds)
    } else {
        String.format(java.util.Locale.ROOT, "%02d:%02d", minutes, seconds)
    }
}


/**
 * 一行电台。
 *
 * 播放只认行尾那个圆形按钮 —— 点名字不再触发播放（容易误触，尤其在滚动列表时）。
 * 长按整行仍然可以编辑名字和链接。
 */
@Composable
private fun StationRow(
    station: Station,
    isCurrent: Boolean,
    isPlaying: Boolean,
    isBuffering: Boolean,
    onToggle: () -> Unit,
    onLongClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val haptics = LocalHapticFeedback.current
    val container = if (isCurrent) {
        MaterialTheme.colorScheme.primaryContainer
    } else {
        MaterialTheme.colorScheme.surfaceContainer
    }
    val content = if (isCurrent) {
        MaterialTheme.colorScheme.onPrimaryContainer
    } else {
        MaterialTheme.colorScheme.onSurface
    }

    Row(
        modifier = modifier
            .fillMaxWidth()
            // 用 background(shape) 而不是 clip + background：clip 会给每一行
            // 套一个 graphicsLayer，十几行一起滚动时白白多出一堆图层
            .background(container, RoundedCornerShape(24.dp))
            // 点名字不播放，只保留长按编辑；播放交给行尾按钮。
            // indication = null：整行本来就没有点击动作，不需要水波纹，
            // 也少画一层按压效果
            .combinedClickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onLongClickLabel = "编辑电台",
                onLongClick = {
                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                    onLongClick()
                },
                onClick = {},
            )
            .padding(start = 20.dp, end = 10.dp, top = 10.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // 选中标记：左边一条竖线，比只换底色更一眼看出来
        Box(
            modifier = Modifier
                .width(4.dp)
                .height(24.dp)
                .clip(CircleShape)
                .background(
                    if (isCurrent) MaterialTheme.colorScheme.primary else Color.Transparent
                )
        )
        Spacer(Modifier.width(14.dp))

        Text(
            text = station.name,
            modifier = Modifier
                .weight(1f)
                .padding(vertical = 8.dp),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = if (isCurrent) FontWeight.SemiBold else FontWeight.Medium,
            color = content,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )

        Spacer(Modifier.width(8.dp))

        // 唯一能触发播放的地方：自己的 44dp 点击区，事件不会穿透到整行
        FilledIconButton(
            onClick = onToggle,
            modifier = Modifier
                .size(44.dp)
                .testTag("row_toggle_${station.id}"),
            colors = IconButtonDefaults.filledIconButtonColors(
                containerColor = if (isCurrent) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.surfaceContainerHighest
                },
                contentColor = if (isCurrent) {
                    MaterialTheme.colorScheme.onPrimary
                } else {
                    MaterialTheme.colorScheme.primary
                },
            ),
        ) {
            when {
                isCurrent && isBuffering -> CircularProgressIndicator(
                    modifier = Modifier.size(20.dp),
                    strokeWidth = 2.dp,
                    color = if (isCurrent) {
                        MaterialTheme.colorScheme.onPrimary
                    } else {
                        MaterialTheme.colorScheme.primary
                    },
                )

                isCurrent && isPlaying -> Icon(
                    imageVector = StopIcon,
                    contentDescription = "停止",
                    modifier = Modifier.size(22.dp),
                )

                else -> Icon(
                    imageVector = PlayIcon,
                    contentDescription = if (isCurrent) "继续播放" else "播放",
                    modifier = Modifier.size(22.dp),
                )
            }
        }
    }
}

/**
 * 排序模式：长按任意一行拖动，松手就落盘。
 *
 * 行高固定 + 按「跨过几行」换算目标位置，避免逐像素判断导致的抖动。
 */
@Composable
private fun SortableStationList(
    stations: List<Station>,
    onReorder: (List<String>) -> Unit,
    modifier: Modifier = Modifier,
) {
    // 拖拽期间用本地副本，松手才写回存储
    var working by remember(stations) { mutableStateOf(stations) }
    var draggingId by remember { mutableStateOf<String?>(null) }
    var dragOffset by remember { mutableFloatStateOf(0f) }

    val haptics = LocalHapticFeedback.current
    val stepPx = with(LocalDensity.current) {
        (SORT_ROW_HEIGHT + SORT_ROW_SPACING).toPx()
    }

    LazyColumn(
        modifier = modifier,
        contentPadding = PaddingValues(
            start = 16.dp,
            end = 16.dp,
            top = 4.dp,
            bottom = 24.dp,
        ),
        verticalArrangement = Arrangement.spacedBy(SORT_ROW_SPACING),
    ) {
        itemsIndexed(
            items = working,
            key = { _, station -> station.id },
            contentType = { _, _ -> "sort" },
        ) { index, station ->
            val isDragging = station.id == draggingId

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(SORT_ROW_HEIGHT)
                    // 只有被拖的那一行才需要图层和抬升，其他行不必付这个成本
                    .then(
                        if (isDragging) {
                            Modifier
                                .zIndex(1f)
                                .graphicsLayer { translationY = dragOffset }
                        } else {
                            Modifier
                        }
                    )
                    .background(
                        if (isDragging) {
                            MaterialTheme.colorScheme.primaryContainer
                        } else {
                            MaterialTheme.colorScheme.surfaceContainer
                        },
                        RoundedCornerShape(24.dp),
                    )
                    .pointerInput(station.id) {
                        detectDragGesturesAfterLongPress(
                            onDragStart = {
                                haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                draggingId = station.id
                                dragOffset = 0f
                            },
                            onDrag = { change, amount ->
                                change.consume()
                                dragOffset += amount.y
                                val from = working.indexOfFirst { it.id == station.id }
                                if (from >= 0) {
                                    val step = dragStep(working, from, dragOffset, stepPx)
                                    working = step.items
                                    dragOffset = step.offset
                                }
                            },
                            onDragEnd = {
                                draggingId = null
                                dragOffset = 0f
                                onReorder(working.map { it.id })
                            },
                            onDragCancel = {
                                draggingId = null
                                dragOffset = 0f
                            },
                        )
                    }
                    .padding(start = 18.dp, end = 20.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "${index + 1}",
                    modifier = Modifier.width(26.dp),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
                Spacer(Modifier.width(12.dp))
                Text(
                    text = station.name,
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.width(12.dp))
                Icon(
                    imageVector = DragHandleIcon,
                    contentDescription = "拖动排序",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(22.dp),
                )
            }
        }
    }
}

@Composable
private fun EmptyState(
    onAdd: () -> Unit,
    onImport: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 40.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            modifier = Modifier
                .size(120.dp)
                .background(MaterialTheme.colorScheme.surfaceContainer, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                painter = painterResource(R.drawable.ic_notification),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(48.dp),
            )
        }

        Spacer(Modifier.height(24.dp))
        Text(
            text = "还没有电台",
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onBackground,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = "把电台名字和播放链接加进来，点右边的三角形就能听",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(20.dp))
        FilledTonalButton(onClick = onAdd, modifier = Modifier.testTag("empty_add")) {
            Icon(AddIcon, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text("添加第一个电台")
        }
        Spacer(Modifier.height(4.dp))
        TextButton(onClick = onImport, modifier = Modifier.testTag("empty_import")) {
            Icon(ImportIcon, contentDescription = null, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(6.dp))
            Text("从 M3U 导入")
        }
    }
}

/** 导出时给系统保存界面一个带日期的默认文件名 */
private fun defaultExportFileName(): String {
    val today = try {
        java.time.LocalDate.now()
            .format(java.time.format.DateTimeFormatter.BASIC_ISO_DATE)
    } catch (e: Exception) {
        ""
    }
    return if (today.isEmpty()) "电台列表.m3u8" else "电台列表-$today.m3u8"
}

/**
 * 通知权限（Android 13+）和局域网权限（Android 17+）各要一次。
 * 局域网权限是给放在家里 / 公司内网里的自建流媒体用的。
 */
/** true = 还在电池优化名单里，息屏后可能被厂商省电策略冻结 */
private fun isBatteryOptimized(context: Context): Boolean {
    val powerManager = context.getSystemService(PowerManager::class.java) ?: return false
    return !powerManager.isIgnoringBatteryOptimizations(context.packageName)
}

private fun requestIgnoreBatteryOptimization(context: Context) {
    // 标准入口：直接弹「是否允许后台运行」的对话框
    val direct = Intent(
        Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
        Uri.parse("package:${context.packageName}"),
    )
    if (runCatching { context.startActivity(direct) }.isSuccess) return

    // 有些 ROM 不认上面那个 action，退到电池优化列表，让用户自己找
    runCatching {
        context.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
    }
}

@Composable
private fun RequestRuntimePermissions() {
    val context = LocalContext.current
    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { }

    LaunchedEffect(Unit) {
        val wanted = buildList {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                add(Manifest.permission.POST_NOTIFICATIONS)
            }
            if (Build.VERSION.SDK_INT >= 37) {
                add("android.permission.ACCESS_LOCAL_NETWORK")
            }
        }.filter {
            ContextCompat.checkSelfPermission(context, it) != PackageManager.PERMISSION_GRANTED
        }

        if (wanted.isNotEmpty()) {
            launcher.launch(wanted.toTypedArray())
        }
    }
}
