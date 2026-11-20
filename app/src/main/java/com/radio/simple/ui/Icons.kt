package com.radio.simple.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathBuilder
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

/**
 * 就这几个图标，自己画比拖进来一整个图标库干净。
 */
internal val AddIcon: ImageVector by lazy {
    ImageVector.Builder(
        name = "Add",
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f,
    ).apply {
        path(fill = SolidColor(Color.Black)) {
            moveTo(11f, 5f)
            horizontalLineTo(13f)
            verticalLineTo(11f)
            horizontalLineTo(19f)
            verticalLineTo(13f)
            horizontalLineTo(13f)
            verticalLineTo(19f)
            horizontalLineTo(11f)
            verticalLineTo(13f)
            horizontalLineTo(5f)
            verticalLineTo(11f)
            horizontalLineTo(11f)
            close()
        }
    }.build()
}

internal val PlayIcon: ImageVector by lazy {
    ImageVector.Builder(
        name = "Play",
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f,
    ).apply {
        path(fill = SolidColor(Color.Black)) {
            moveTo(8.5f, 5.6f)
            lineTo(18.6f, 12f)
            lineTo(8.5f, 18.4f)
            close()
        }
    }.build()
}

/**
 * 设置用「滑杆」造型，比齿轮好画，也更像调参数。
 */
internal val SettingsIcon: ImageVector by lazy {
    ImageVector.Builder(
        name = "Tune",
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f,
    ).apply {
        path(fill = SolidColor(Color.Black)) {
            addBar(3f, 6.6f, 18f, 1.8f)
            addBar(3f, 11.1f, 18f, 1.8f)
            addBar(3f, 15.6f, 18f, 1.8f)
            addDot(8.2f, 7.5f, 2.8f)
            addDot(16f, 12f, 2.8f)
            addDot(10.4f, 16.5f, 2.8f)
        }
    }.build()
}

internal val StopIcon: ImageVector by lazy {
    ImageVector.Builder(
        name = "Stop",
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f,
    ).apply {
        path(fill = SolidColor(Color.Black)) {
            addBar(6.8f, 6.8f, 10.4f, 10.4f, 2.4f)
        }
    }.build()
}

internal val BackIcon: ImageVector by lazy {
    ImageVector.Builder(
        name = "ArrowBack",
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f,
    ).apply {
        path(fill = SolidColor(Color.Black)) {
            moveTo(15.1f, 4.7f)
            lineTo(13.6f, 3.2f)
            lineTo(6.3f, 12f)
            lineTo(13.6f, 20.8f)
            lineTo(15.1f, 19.3f)
            lineTo(9.3f, 12f)
            close()
        }
    }.build()
}

internal val ImportIcon: ImageVector by lazy {
    ImageVector.Builder(
        name = "Import",
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f,
    ).apply {
        path(fill = SolidColor(Color.Black)) {
            // 向下的箭头
            moveTo(11f, 3.5f)
            horizontalLineTo(13f)
            verticalLineTo(10.4f)
            horizontalLineTo(16.4f)
            lineTo(12f, 15.4f)
            lineTo(7.6f, 10.4f)
            horizontalLineTo(11f)
            close()
            // 托底
            addBar(4.5f, 17.6f, 15f, 2f)
        }
    }.build()
}

internal val ExportIcon: ImageVector by lazy {
    ImageVector.Builder(
        name = "Export",
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f,
    ).apply {
        path(fill = SolidColor(Color.Black)) {
            // 向上的箭头
            moveTo(11f, 15.4f)
            horizontalLineTo(13f)
            verticalLineTo(8.5f)
            horizontalLineTo(16.4f)
            lineTo(12f, 3.5f)
            lineTo(7.6f, 8.5f)
            horizontalLineTo(11f)
            close()
            // 托底
            addBar(4.5f, 17.6f, 15f, 2f)
        }
    }.build()
}

/** 两条上下箭头，进入「调整顺序」用 */
internal val SortIcon: ImageVector by lazy {
    ImageVector.Builder(
        name = "Sort",
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f,
    ).apply {
        path(fill = SolidColor(Color.Black)) {
            // 上箭头（长杆）
            moveTo(9f, 4.6f)
            lineTo(13.6f, 9.2f)
            horizontalLineTo(10.7f)
            verticalLineTo(12.2f)
            horizontalLineTo(7.3f)
            verticalLineTo(9.2f)
            horizontalLineTo(4.4f)
            close()
            // 下箭头（短杆）
            moveTo(14.7f, 11.8f)
            horizontalLineTo(18.1f)
            verticalLineTo(14.8f)
            horizontalLineTo(21f)
            lineTo(16.4f, 19.4f)
            lineTo(11.8f, 14.8f)
            horizontalLineTo(14.7f)
            close()
        }
    }.build()
}

/** 拖动把手：两列各三个点 */
internal val DragHandleIcon: ImageVector by lazy {
    ImageVector.Builder(
        name = "DragHandle",
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f,
    ).apply {
        path(fill = SolidColor(Color.Black)) {
            listOf(7.7f, 16.3f).forEach { x ->
                listOf(6.6f, 12f, 17.4f).forEach { y -> addDot(x, y, 1.7f) }
            }
        }
    }.build()
}

/** 圆角横条，用来画滑杆轨道和托底 */
private fun PathBuilder.addBar(left: Float, top: Float, width: Float, height: Float, corner: Float = 1f) {
    val r = minOf(corner, height / 2f)
    moveTo(left + r, top)
    lineTo(left + width - r, top)
    curveTo(left + width, top, left + width, top + r, left + width, top + r)
    lineTo(left + width, top + height - r)
    curveTo(left + width, top + height, left + width - r, top + height, left + width - r, top + height)
    lineTo(left + r, top + height)
    curveTo(left, top + height, left, top + height - r, left, top + height - r)
    lineTo(left, top + r)
    curveTo(left, top, left + r, top, left + r, top)
    close()
}

/** 用四段三次曲线凑一个圆 */
private fun PathBuilder.addDot(centerX: Float, centerY: Float, radius: Float) {
    val k = 0.5523f * radius
    moveTo(centerX - radius, centerY)
    curveTo(centerX - radius, centerY - k, centerX - k, centerY - radius, centerX, centerY - radius)
    curveTo(centerX + k, centerY - radius, centerX + radius, centerY - k, centerX + radius, centerY)
    curveTo(centerX + radius, centerY + k, centerX + k, centerY + radius, centerX, centerY + radius)
    curveTo(centerX - k, centerY + radius, centerX - radius, centerY + k, centerX - radius, centerY)
    close()
}
