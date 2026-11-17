package dev.pocketagent.agent

import dev.pocketagent.device.Direction
import dev.pocketagent.device.node
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class CaptionsTest {
    private fun tap(label: String?, password: Boolean = false) =
        Captions.forAction(ToolCall.Tap("a1", null, null, false), node("a1", text = label, isPassword = password))

    @Test
    fun captionUsesTargetLabel() {
        assertEquals("正在点击 Add to cart", tap("Add to cart"))
    }

    @Test
    fun longLabelIsShortened() {
        assertEquals("正在点击 " + "x".repeat(30) + "…", tap("x".repeat(50)))
    }

    @Test
    fun labelOfExactlyTheLimitIsNotShortened() {
        assertEquals("正在点击 " + "x".repeat(30), tap("x".repeat(30)))
    }

    @Test
    fun passwordFieldIsNeverNamed() {
        val caption = Captions.forAction(ToolCall.TypeText("a1", "hunter2"), node("a1", text = "Password", isPassword = true))
        assertEquals("正在输入到 某个输入框", caption)
        assertEquals("正在点击 某个项目", tap("Password", password = true))
    }

    @Test
    fun typingNamesTheField() {
        assertEquals("正在输入到 Message", Captions.forAction(ToolCall.TypeText("a1", "hi"), node("a1", text = "Message")))
        assertEquals("正在搜索 Search", Captions.forAction(ToolCall.TypeText("a1", "milk", submit = true), node("a1", text = "Search")))
    }

    @Test
    fun missingLabelOrTargetFallsBackToGenericWords() {
        assertEquals("正在点击 某个项目", tap(null))
        assertEquals("正在点击 某个项目", tap("   "))
        assertEquals("正在点击 某个项目", Captions.forAction(ToolCall.Tap(null, 5, 5, false), null))
        assertEquals("正在输入到 某个输入框", Captions.forAction(ToolCall.TypeText("a1", "hi"), null))
    }

    @Test
    fun controlCharactersInLabelsBecomeSpaces() {
        val caption = tap("Pay\nnow please")
        assertEquals("正在点击 Pay now please", caption)
        assertFalse(caption.contains('\n'))
    }

    @Test
    fun shorteningNeverSplitsASurrogatePair() {
        val caption = tap("x".repeat(29) + "😀" + "tail")
        assertEquals(caption, String(caption.toByteArray(Charsets.UTF_8), Charsets.UTF_8), "no lone surrogate")
    }

    @Test
    fun fixedCaptions() {
        assertEquals("正在滚动（down）", Captions.forAction(ToolCall.Scroll(Direction.DOWN, null), null))
        assertEquals("正在滚动（left）", Captions.forAction(ToolCall.Scroll(Direction.LEFT, "a1"), null))
        assertEquals("正在返回", Captions.forAction(ToolCall.Back, null))
        assertEquals("正在回到主屏幕", Captions.forAction(ToolCall.Home, null))
        assertEquals("正在截图", Captions.forAction(ToolCall.Screenshot, null))
        assertEquals("正在等待", Captions.forAction(ToolCall.Wait(3), null))
        assertEquals("正在询问你", Captions.forAction(ToolCall.AskUser("Which Mom?"), null))
        assertEquals("正在结束", Captions.forAction(ToolCall.Done("ok", true), null))
    }

    @Test
    fun openingAnAppCleansAndShortensTheModelSuppliedName() {
        assertEquals("正在打开 WhatsApp", Captions.forAction(ToolCall.OpenApp("WhatsApp"), null))
        assertEquals("正在打开 " + "n".repeat(30) + "…", Captions.forAction(ToolCall.OpenApp("n".repeat(80)), null))
        assertEquals("正在打开 a b", Captions.forAction(ToolCall.OpenApp("a\nb"), null))
        assertEquals("正在打开 某个应用", Captions.forAction(ToolCall.OpenApp("   "), null))
    }

    @Test
    fun shortcutCaptionsAreFixedWordsWithoutTheModelsText() {
        assertEquals("正在启动导航", Captions.forAction(ToolCall.Directions("secret place"), null))
        assertEquals("正在打开电话应用", Captions.forAction(ToolCall.Dial("5551234"), null))
        assertEquals("正在编写短信", Captions.forAction(ToolCall.TextMessage("5551234", "secret"), null))
        assertEquals("正在搜索网络", Captions.forAction(ToolCall.WebSearch("secret"), null))
        assertEquals("正在设置 06:30 的闹钟", Captions.forAction(ToolCall.SetAlarm(6, 30), null))
        assertEquals("正在启动计时器", Captions.forAction(ToolCall.SetTimer(600), null))
        assertEquals("正在打开网页链接", Captions.forAction(ToolCall.OpenUrl("https://secret.example"), null))
        assertEquals("正在添加日历事件", Captions.forAction(ToolCall.AddEvent("secret", java.time.LocalDateTime.of(2026, 1, 1, 9, 0), 30), null))
    }
}
