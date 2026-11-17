package dev.pocketagent.device

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MarkLegendTest {
    @Test
    fun noMarksMeansNoLegend() {
        assertEquals("", MarkLegend.text(emptyList()))
    }

    @Test
    fun eachMarkGetsItsNumberAndRealPixelCentre() {
        val text = MarkLegend.text(
            listOf(
                Mark(1, "搜索", 120, 340),
                Mark(2, "发送", 640, 1180),
            ),
        )
        assertTrue(text, text.contains("1. 搜索 → (120, 340)"))
        assertTrue(text, text.contains("2. 发送 → (640, 1180)"))
    }

    @Test
    fun longScreensSayHowManyWereLeftOut() {
        val marks = (1..MarkLegend.MAX_MARKS + 3).map { Mark(it, "项 $it", it * 10, it * 20) }
        val text = MarkLegend.text(marks)
        assertTrue(text, text.contains("另有 3 个没标"))
        assertEquals(false, text.contains("${MarkLegend.MAX_MARKS + 1}. 项"))
    }
}
