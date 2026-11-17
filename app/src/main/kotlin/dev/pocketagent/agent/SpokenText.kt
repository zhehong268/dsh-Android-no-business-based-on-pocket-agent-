package dev.pocketagent.agent

/** The exact sentences the phone speaks. Pure text rules; the speech engine lives elsewhere. */
object SpokenText {
    const val MAX = 200

    val started: String = "好的。"
    val reAsk: String = "抱歉，请说“是”或“不是”。"
    val noDetail: String = "没有可朗读的文字。"

    fun caption(caption: String): String = clean(caption)

    fun question(question: String): String = clean(question)

    /** The action already names the target and app in most cases, so each is added only when missing. The detail is never spoken here. */
    fun confirm(request: ConfirmRequest): String {
        val parts = StringBuilder(request.action)
        if (request.target.isNotBlank() && !request.action.contains(request.target)) parts.append("，").append(request.target)
        val needsApp = request.app.isNotBlank() && !request.action.contains(request.app)
        val joined = if (needsApp) (if (parts.contains(',')) "$parts，在 ${request.app} 中" else "$parts，在 ${request.app} 中") else parts.toString()
        return withSuffix(joined, "？请说“是”或“不是”。")
    }

    fun detail(request: ConfirmRequest): String {
        val d = request.detail?.takeIf { it.isNotBlank() } ?: return clean("$noDetail 请说“是”或“不是”。")
        return withSuffix("内容是：$d", "。请说“是”或“不是”。")
    }

    fun ending(outcome: TaskOutcome, includeSummary: Boolean): String {
        if (!outcome.success) return "没能完成。请检查屏幕。"
        return if (includeSummary && outcome.summary.isNotBlank()) clean("完成。${outcome.summary}") else "完成。"
    }

    /** Only [text] is cleaned and cut, so the fixed [suffix] is always heard and the whole sentence stays within [MAX]. */
    private fun withSuffix(text: String, suffix: String): String = clean(text, MAX - suffix.length) + suffix

    /** Control characters become spaces and the text is cut at [MAX] without splitting a surrogate pair. */
    private fun clean(text: String, max: Int = MAX): String {
        val flat = text.map { if (it.isISOControl()) ' ' else it }.joinToString("").trim()
        if (flat.length <= max) return flat
        var cut = max
        if (Character.isHighSurrogate(flat[cut - 1])) cut--
        return flat.substring(0, cut).trimEnd()
    }
}
