package dev.pocketagent.device

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ScreenPaletteTest {
    @Test fun keyboardAndEditorAreExcludedFromAppColors() {
        val app = Bounds(0, 0, 400, 600)
        val keyboard = Bounds(80, 360, 360, 520)
        val editor = Bounds(0, 220, 400, 355)
        fun color(x: Int, y: Int) = when {
            keyboard.contains(x, y) -> 0x20204F
            editor.contains(x, y) -> error("must not sample editor pixels")
            else -> 0xEEF5F8
        }
        val background = ScreenPalette.sample(400, 600, listOf(app), listOf(keyboard, editor), ::color)!!
        val keys = ScreenPalette.sample(400, 600, listOf(keyboard), pixel = ::color)!!
        assertEquals(listOf(0xE0E0E0), background.colors)
        assertTrue(background.light)
        assertEquals(listOf(0x202040), keys.colors)
        assertFalse(keys.light)
    }

    @Test fun detectsMixedPaletteAndReturnsNullWithNoVisibleSamples() {
        val area = Bounds(0, 0, 300, 300)
        val palette = ScreenPalette.sample(300, 300, listOf(area)) { x, _ -> if (x < 150) 0xF1EFEF else 0x1F2E5A }
        assertEquals(true, palette?.patterned)
        assertNull(ScreenPalette.sample(300, 300, listOf(area), listOf(area)) { _, _ -> error("excluded") })
    }
}
