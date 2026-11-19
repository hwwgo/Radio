package com.radio.simple.ui

import kotlin.math.roundToInt

/**
 * 拖拽排序的一步。
 *
 * @property items 这一步之后的顺序
 * @property offset 被拖项还需要额外挪动的位移（已经抵消掉「跨行」的距离）
 */
internal data class DragStep<T>(val items: List<T>, val offset: Float)

/**
 * 根据手指的累计位移算下一帧的列表顺序。
 *
 * 之所以按「跨过几行」而不是逐像素交换，是因为逐像素会有两个问题：
 * 一行只挪了 1px 就被判定换位，列表会疯狂抖动；换位后如果不抵消位移，
 * 被拖的行会「跳」到手指下面，手感立刻散掉。
 *
 * @param from 被拖项当前所在下标
 * @param offset 相对原位累计移动的像素（向下为正）
 * @param step 一行的步长 = 行高 + 行间距
 */
internal fun <T> dragStep(items: List<T>, from: Int, offset: Float, step: Float): DragStep<T> {
    if (items.isEmpty() || from !in items.indices || step <= 0f) return DragStep(items, offset)

    val to = (from + (offset / step).roundToInt()).coerceIn(0, items.lastIndex)
    if (to == from) {
        // 已经在头 / 尾或者还没跨过半行，把偏移钳在 ±半行，
        // 免得一直累积到边界外，回拖的时候要空走一大段
        return DragStep(items, offset.coerceIn(-step / 2f, step / 2f))
    }

    val moved = items.toMutableList().apply { add(to, removeAt(from)) }
    return DragStep(moved, offset - (to - from) * step)
}
