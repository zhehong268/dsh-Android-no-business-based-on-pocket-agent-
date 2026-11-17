package dev.pocketagent.safety

/**
 * What a tap on a quiz screen means. A timed paper puts one question per screen, so asking the user to approve
 * every「提交」would run the clock out; asking only for the final hand-in is what the user asked for.
 */
enum class QuizTap {
    /** Submits or advances one question: allowed without a card while answering. */
    AUTO,

    /** Hands in the whole paper: still confirmed, once. */
    FINAL,

    /** Not part of answering: the normal safety gate decides. */
    NONE,
}

/**
 * Reads the label of the button the model is about to tap. Anything that could spend money is never treated as part
 * of answering, however quiz-like the screen looks.
 */
object QuizLabels {
    /** Words that mark a task as answering questions rather than changing the phone. */
    private val WORDS = listOf(
        "答题", "答卷", "试卷", "考试", "做题", "测验", "答完", "卷子", "quiz", "exam", "questionnaire",
    )

    /** Whether this task is answering a paper, used for the per-question rule and for picking the stronger model. */
    fun looksLikeQuiz(task: String): Boolean {
        val text = task.lowercase()
        return WORDS.any { text.contains(it) }
    }

    private val NEVER = listOf(
        "订单", "支付", "付款", "购买", "下单", "缴费", "充值", "转账", "汇款",
        "立即支付", "立即购买", "确认支付", "支付成功", "打赏", "开通会员",
    )

    private val FINAL = listOf(
        "交卷", "提交答卷", "提交试卷", "结束考试", "完成答题", "结束答题", "提交全部答案",
    )

    private val PER_QUESTION = listOf(
        "提交答案", "确认答案", "下一题", "下一道", "下一步", "保存并继续", "继续答题",
        "确认选择", "提交本题", "提交", "确定", "确认", "保存",
    )

    /** Tabs and arrows that also count as moving to the next question. */
    private val ARROWS = listOf("next", "下一", ">", "›")

    fun classify(label: String): QuizTap {
        val text = matchForm(label).trim().lowercase()
        if (text.isEmpty()) return QuizTap.NONE
        if (NEVER.any { text.contains(it) }) return QuizTap.NONE
        if (FINAL.any { text.contains(it) }) return QuizTap.FINAL
        if (PER_QUESTION.any { text.contains(it) }) return QuizTap.AUTO
        if (ARROWS.any { text.contains(it) }) return QuizTap.AUTO
        return QuizTap.NONE
    }
}
