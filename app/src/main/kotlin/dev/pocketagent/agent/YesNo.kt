package dev.pocketagent.agent

enum class Answer { YES, NO, READ, UNCLEAR }

/** Turns a spoken answer into yes, no or read. The whole utterance must match an entry, so "yes send it to everyone" is unclear. */
object YesNo {
    private val yes = setOf("yes", "yeah", "yep", "yup", "sure", "ok", "okay", "confirm", "do it", "go ahead", "send it", "haan", "haa", "ha", "हाँ", "हां", "जी हाँ")
    private val no = setOf("no", "nope", "cancel", "stop", "don't", "do not", "wait", "nahi", "nahin", "नहीं", "ना")
    private val read = setOf("read it", "read", "what does it say", "padho", "पढ़ो")

    /** Chinese answers. The whole utterance must match one of these, so "不是" never counts as a yes. */
    private val yesZh = setOf("好", "好的", "好呀", "好啊", "可以", "行", "嗯", "是", "是的", "对", "对的", "确定", "确认", "没问题", "同意", "发吧", "继续")
    private val noZh = setOf("不", "不要", "不用", "不用了", "不行", "不是", "别", "算了", "取消", "停", "停下", "停止", "等等", "等一下", "先别")
    private val readZh = setOf("读一下", "读一遍", "读给我听", "念一下", "念给我听", "说说", "说什么")

    fun parse(transcript: String?): Answer {
        val text = normalise(transcript ?: return Answer.UNCLEAR)
        return when {
            text in yes || text in yesZh -> Answer.YES
            text in no || text in noZh -> Answer.NO
            text in read || text in readZh -> Answer.READ
            else -> Answer.UNCLEAR
        }
    }

    private fun normalise(raw: String): String =
        raw.lowercase().replace('’', '\'')
            .filterNot { it in ".,!?;:\"" || it == '।' }
            .trim('\'', ' ')
            .replace(Regex("\\s+"), " ")
}
