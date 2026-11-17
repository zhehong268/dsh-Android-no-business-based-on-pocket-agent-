package dev.pocketagent.agent

import dev.pocketagent.device.ScreenNode

/**
 * The one-line caption shown next to the bubble. Built here from the action and the target's label, never from
 * text the model wrote about its own plan, so it cannot be made to say something misleading. Password fields
 * are never named.
 */
object Captions {
    private const val MAX_LABEL = 30

    val readingNotifications: String = "正在读取你的通知"

    /** Sender and app come from a notification, which any app or stranger can write, so both are cut to one short line. */
    fun forReply(sender: String, app: String): String = "正在 ${oneLine(app)} 中回复 ${oneLine(sender)}"

    fun forAction(call: ToolCall, target: ScreenNode?): String = when (call) {
        is ToolCall.ZoomScreen -> "正在放大屏幕区域"
        ToolCall.ReadScreen -> "正在重新读屏"
        is ToolCall.ReadElement -> "正在读取完整内容"
        is ToolCall.FindElements -> "正在查找候选"
        is ToolCall.PerformSteps -> "正在连续操作"
        ToolCall.ReadUiSource -> "正在读取控件原始文本"
        ToolCall.FindChatInput -> "正在定位聊天正文框"
        is ToolCall.FontReference -> "正在读取字体参照"
        is ToolCall.OpenContact -> "正在查找联系人 " + oneLine(call.name)
        is ToolCall.FillChat -> "正在向已核对的会话粘贴草稿"
        is ToolCall.TapMark -> "正在点击截图编号 ${call.index}"
        is ToolCall.WaitFor -> "正在等待目标出现"
        is ToolCall.Checkpoint -> "正在保存任务进度"
        is ToolCall.LongPress -> "正在长按 " + (labelOf(target) ?: "某个项目")
        is ToolCall.DoubleTap -> "正在双击 " + (labelOf(target) ?: "某个项目")
        is ToolCall.Drag -> "正在拖动"
        is ToolCall.Tap -> "正在点击 " + (labelOf(target) ?: "某个项目")
        is ToolCall.TapText -> "正在找并点击 " + oneLine(call.text)
        is ToolCall.TypeText -> (if (call.submit) "正在搜索 " else "正在输入到 ") + (labelOf(target) ?: "某个输入框")
        is ToolCall.Scroll -> "正在滚动（${call.direction.name.lowercase()}）"
        ToolCall.Back -> "正在返回"
        ToolCall.Home -> "正在回到主屏幕"
        is ToolCall.OpenApp -> "正在打开 " + oneLine(call.name).ifEmpty { "某个应用" }
        is ToolCall.Directions -> "正在启动导航"
        is ToolCall.Dial -> "正在打开电话应用"
        is ToolCall.TextMessage -> "正在编写短信"
        is ToolCall.WebSearch -> "正在搜索网络"
        is ToolCall.FetchUrl -> "正在读网页"
        is ToolCall.SecondOpinion -> "正在问另一个模型"
        is ToolCall.QuizRecord -> "正在记录答题卡"
        is ToolCall.SetAlarm -> "正在设置 %02d:%02d 的闹钟".format(call.hour, call.minute)
        is ToolCall.SetTimer -> "正在启动计时器"
        is ToolCall.OpenUrl -> "正在打开网页链接"
        is ToolCall.AddEvent -> "正在添加日历事件"
        ToolCall.ReadNotifications -> readingNotifications
        is ToolCall.ReplyNotification -> "正在回复消息"
        ToolCall.Screenshot -> "正在截图"
        is ToolCall.Wait -> "正在等待"
        is ToolCall.AskUser -> "正在询问你"
        is ToolCall.ListFiles -> "正在查看 " + oneLine(call.path).ifEmpty { "文件夹" }
        is ToolCall.ReadFile -> "正在读取 " + oneLine(call.path).ifEmpty { "文件" }
        is ToolCall.WriteFile -> "正在写入 " + oneLine(call.path).ifEmpty { "文件" }
        is ToolCall.Done -> "正在结束"
    }

    private fun labelOf(target: ScreenNode?): String? =
        target?.takeUnless { it.isPassword }?.label()?.let { oneLine(it) }?.takeIf { it.isNotEmpty() }

    /** One short line: line breaks and control characters become spaces, and long text is cut without splitting a surrogate pair. */
    internal fun oneLine(raw: String, max: Int = MAX_LABEL): String {
        val text = raw.map { if (it.isISOControl() || it == ' ' || it == ' ') ' ' else it }.joinToString("").trim()
        if (text.length <= max) return text
        var cut = max
        if (Character.isHighSurrogate(text[cut - 1])) cut--
        return text.substring(0, cut) + "…"
    }
}
