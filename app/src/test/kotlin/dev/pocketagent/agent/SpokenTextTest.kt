package dev.pocketagent.agent

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SpokenTextTest {
    private fun outcome(success: Boolean, summary: String = "") =
        TaskOutcome(if (success) EndReason.DONE else EndReason.STUCK, success, summary)

    @Test fun fixedStrings() {
        assertEquals("好的。", SpokenText.started)
        assertEquals("抱歉，请说“是”或“不是”。", SpokenText.reAsk)
        assertEquals("没有可朗读的文字。", SpokenText.noDetail)
    }

    @Test fun confirmDoesNotRepeatTargetOrApp() {
        assertEquals("正在 WhatsApp 中回复 Rahul？请说“是”或“不是”。",
            SpokenText.confirm(ConfirmRequest("正在 WhatsApp 中回复 Rahul", "Rahul", "WhatsApp", "on my way")))
        assertEquals("正在点击 Send，在 Gmail 中？请说“是”或“不是”。",
            SpokenText.confirm(ConfirmRequest("正在点击 Send", "Send", "Gmail")))
        assertEquals("正在点击 某个项目，屏幕上的某个位置，在 Gmail 中？请说“是”或“不是”。",
            SpokenText.confirm(ConfirmRequest("正在点击 某个项目", "屏幕上的某个位置", "Gmail")))
    }

    @Test fun detail() {
        assertEquals("内容是：on my way。请说“是”或“不是”。", SpokenText.detail(ConfirmRequest("a", "b", "c", "on my way")))
        assertTrue(SpokenText.detail(ConfirmRequest("a", "b", "c")).startsWith(SpokenText.noDetail))
    }

    @Test fun ending() {
        assertEquals("完成。", SpokenText.ending(outcome(true, "running late"), false))
        assertEquals("完成。running late", SpokenText.ending(outcome(true, "running late"), true))
        assertEquals("没能完成。请检查屏幕。", SpokenText.ending(outcome(false, "x"), true))
    }

    @Test fun longAndControlTextIsCleaned() {
        val raw = "a\nb\u0007c" + "x".repeat(500)
        for (s in listOf(SpokenText.caption(raw), SpokenText.question(raw), SpokenText.detail(ConfirmRequest("a", "b", "c", raw)), SpokenText.ending(outcome(true, raw), true))) {
            assertTrue(s.length <= SpokenText.MAX, s.length.toString())
            assertFalse(s.any { it.isISOControl() })
        }
        assertEquals("a b c", SpokenText.caption("a\nb\u0007c"))
    }

    @Test fun longVariablePartKeepsTheSuffix() {
        val longDetail = SpokenText.detail(ConfirmRequest("a", "b", "c", "d".repeat(200)))
        assertTrue(longDetail.endsWith("。请说“是”或“不是”。"), longDetail)
        assertTrue(longDetail.startsWith("内容是：ddd"))
        assertTrue(longDetail.length <= SpokenText.MAX, longDetail.length.toString())

        val longConfirm = SpokenText.confirm(ConfirmRequest("Sending " + "w".repeat(300), "Rahul", "WhatsApp"))
        assertTrue(longConfirm.endsWith("？请说“是”或“不是”。"), longConfirm)
        assertTrue(longConfirm.startsWith("Sending www"))
        assertTrue(longConfirm.length <= SpokenText.MAX, longConfirm.length.toString())
    }
}
