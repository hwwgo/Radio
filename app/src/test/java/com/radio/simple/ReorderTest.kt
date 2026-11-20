package com.radio.simple

import com.radio.simple.ui.dragStep
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

/**
 * 拖拽排序的算法测试：位移 → 顺序。界面画得好不好另说，
 * 至少「拖过一行就该换位、松手后的顺序必须保留」这条不能错。
 */
class ReorderTest {

    private val step = 74f // 64dp 行高 + 10dp 间距，按 1x 折算成像素

    private val items = listOf("A", "B", "C", "D")

    private fun assertOffset(expected: Float, actual: Float) =
        assertEquals(expected.toDouble(), actual.toDouble(), 0.01)

    @Test
    fun smallDragDoesNotReorder() {
        val result = dragStep(items, from = 1, offset = 20f, step = step)
        assertEquals(items, result.items)
        assertOffset(20f, result.offset)
    }

    @Test
    fun draggingDownPastOneRowSwaps() {
        // 往下拖过一整行 → B 和 C 换位，位移抵消 74
        val result = dragStep(items, from = 1, offset = 80f, step = step)
        assertEquals(listOf("A", "C", "B", "D"), result.items)
        assertOffset(6f, result.offset)
    }

    @Test
    fun draggingUpPastOneRowSwaps() {
        val result = dragStep(items, from = 2, offset = -80f, step = step)
        assertEquals(listOf("A", "C", "B", "D"), result.items)
        assertOffset(-6f, result.offset)
    }

    @Test
    fun jumpingSeveralRowsAtOnce() {
        val result = dragStep(items, from = 0, offset = 74f * 3, step = step)
        assertEquals(listOf("B", "C", "D", "A"), result.items)
        assertOffset(0f, result.offset)
    }

    @Test
    fun cannotDragAboveFirstRow() {
        val result = dragStep(items, from = 0, offset = -500f, step = step)
        assertEquals(items, result.items)
        // 边界处把位移钳在半行内，回拖时不用先空走一大段
        assertOffset(-step / 2f, result.offset)
    }

    @Test
    fun cannotDragBelowLastRow() {
        val result = dragStep(items, from = 3, offset = 500f, step = step)
        assertEquals(items, result.items)
        assertOffset(step / 2f, result.offset)
    }

    @Test
    fun incrementalDragMatchesOneShotDrag() {
        // 手指一帧一帧地拖（每帧 40px），最终顺序要和一次性拖 120px 一样，
        // 否则拖动中会「越拖越偏」。
        var list = items
        var offset = 0f
        repeat(3) {
            offset += 40f
            // 每帧重新定位被拖的项，和界面上 indexOf 的做法一致
            val result = dragStep(list, from = list.indexOf("A"), offset = offset, step = step)
            list = result.items
            offset = result.offset
        }

        val oneShot = dragStep(items, from = 0, offset = 120f, step = step).items
        assertEquals(oneShot, list)
        assertEquals(listOf("B", "C", "A", "D"), list)
    }

    @Test
    fun invalidInputIsIgnored() {
        assertSame(items, dragStep(items, from = -1, offset = 100f, step = step).items)
        assertSame(items, dragStep(items, from = 9, offset = 100f, step = step).items)
        assertEquals(
            emptyList<String>(),
            dragStep(emptyList<String>(), from = 0, offset = 1f, step = step).items,
        )
        // step 为 0 时不能除零
        assertSame(items, dragStep(items, from = 0, offset = 100f, step = 0f).items)
    }
}
