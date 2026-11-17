package dev.pocketagent.agent

/** Words for the summary card; the model's own text only ever appears in [body], cut and cleaned. */
object SummaryText {
    private const val MAX_SUMMARY_CHARS = 300

    /** Stored in task history instead of the summary when the task read notifications. */
    const val HIDDEN_SUMMARY = "总结已隐藏（因为它使用了你的通知）"

    /** The summary to store on disk: a task that read notifications never keeps its summary. */
    fun forHistory(outcome: TaskOutcome): String =
        if (outcome.usedNotifications) HIDDEN_SUMMARY else outcome.summary

    fun title(state: AgentState.Finished): String {
        val outcome = state.outcome
        return when (outcome.reason) {
            EndReason.DONE -> if (outcome.success) "完成" else "未完成"
            EndReason.STEP_LIMIT, EndReason.STUCK, EndReason.INVALID_CALLS -> "未完成"
            EndReason.REFUSED -> "模型拒绝执行"
            EndReason.AUTH_ERROR -> "密钥被拒绝"
            EndReason.LLM_ERROR -> "模型错误"
            EndReason.BLOCKED_APP -> "应用被拦截"
            EndReason.DEVICE_UNAVAILABLE -> "手机不可用"
        }
    }

    fun stoppedTitle(): String = "已由你停止"

    /** What the card says under the title: the result in plain words, cleaned and cut. Nothing technical (no tokens, steps or timings). */
    fun body(outcome: TaskOutcome): String =
        outcome.summary.take(MAX_SUMMARY_CHARS).map { if (it.isISOControl()) ' ' else it }.joinToString("").trim()
}
