package dev.pocketagent.safety

import java.text.Normalizer
import java.util.regex.Pattern

/**
 * The form of a label that matching works on: compatibility-normalised (NFKC, so fullwidth letters become plain
 * ones) with invisible format characters removed (zero-width spaces and joiners, soft hyphens, bidirectional
 * marks, byte-order marks). Without this, "Pa" + a zero-width space + "y" would not look like "Pay" to the
 * rules, though a person reads it as "Pay". Look-alike letters from other scripts are still not caught.
 */
internal fun matchForm(text: String): String =
    Normalizer.normalize(text, Normalizer.Form.NFKC).filter { Character.getType(it) != Character.FORMAT.toInt() }

/** Finds any of the terms at the start of a word, ignoring case, after [matchForm]. Letters and digits both count as word characters. */
internal class WordStartMatcher(terms: Collection<String>) {
    /**
     * Latin terms match at a word start only, so "pay" catches "Pay now" but not "prepay". Chinese runs words
     * together with no space in between, so a Chinese term is matched anywhere: without that, a label such as
     * "确认支付" would hold "支付" after a letter and never count.
     */
    private val pattern: Pattern = Pattern.compile(
        terms.joinToString("|") { term ->
            val quoted = Pattern.quote(matchForm(term))
            if (hasHan(term)) quoted else "(?<![\\p{L}\\p{N}])" + quoted
        },
        Pattern.CASE_INSENSITIVE or Pattern.UNICODE_CASE,
    )

    fun find(text: String): Boolean = pattern.matcher(matchForm(text)).find()

    private fun hasHan(text: String): Boolean =
        text.any { it.code in 0x3400..0x4DBF || it.code in 0x4E00..0x9FFF || it.code in 0xF900..0xFAFF }
}

internal fun wordStartPattern(terms: Collection<String>) = WordStartMatcher(terms)

/**
 * Buttons that spend money, send something, or destroy something. Matching is by word start, so a term also
 * matches longer words ("postal" matches "post"). That over-matching is accepted: the cost is one extra confirmation.
 */
object RiskyLabels {
    private val terms = listOf(
        "pay", "place order", "buy", "send", "post", "delete", "remove", "confirm", "transfer", "uninstall", "install",
        // A call costs money and reaches a real person, so the Call button needs approval like Send.
        "call", "dial",
        "भुगतान", "भेजें", "खरीदें", "हटाएं", "पुष्टि", "ऑर्डर करें",
    )
    /** The same idea in Chinese: buttons that spend money, send, submit anything to a server, or destroy. */
    private val localTerms = listOf(
        "支付", "付款", "付钱", "购买", "买下", "下单", "提交订单", "确认订单", "结算", "转账", "汇款", "提现", "充值",
        "发送", "发出", "发表", "发布", "发布到", "转发", "提交", "确认", "确定", "同意", "授权",
        "删除", "移除", "清空", "注销", "解绑", "卸载", "安装", "购买会员", "开通",
        "拨号", "拨打", "呼叫", "打电话",
    )
    private val pattern = wordStartPattern(terms + localTerms)

    fun isRisky(label: String?): Boolean = !label.isNullOrBlank() && pattern.find(label)
}
