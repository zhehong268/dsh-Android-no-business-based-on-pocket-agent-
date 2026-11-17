package dev.pocketagent.safety

import java.util.regex.Pattern

/**
 * Recognises bank and payment messages so they never reach the AI provider. A message counts as money when the
 * sender is a bank-style header (two letters, a dash, 3 to 9 letters or digits, optional -S/-T suffix, any case)
 * whose name part is a known bank or matches the blocked-app words, when the sender is a known bank short name on
 * its own ("HDFCBK"), or when the text holds a transaction word or a rupee sign ("₹", "रु") before an amount.
 * Most words match by word start, so over-dropping is accepted. Three cues are stricter: "emi" and "upi" must be
 * whole words, "Rs" counts only before an amount, and "spent" counts only before a money cue (Rs, INR, a digit
 * or "on card"), so ordinary chat such as "I spent the weekend at Mom's" is kept.
 */
object MoneyMessages {
    private val header: Pattern = Pattern.compile("""^[A-Z]{2}-([A-Z0-9]{3,9})(?:-[ST])?$""", Pattern.CASE_INSENSITIVE)

    /** Short names banks send SMS from. A title that is one of these on its own (no "VM-" prefix) is a bank too. */
    private val bankShortNames: List<String> = listOf(
        "HDFCBK", "ICICIB", "SBIINB", "SBIPSG", "AXISBK", "KOTAKB", "PAYTMB", "PNBSMS", "BOBTXN", "CANBNK",
        "IDFCFB", "YESBNK", "INDUSB", "AMEXIN", "CITIBK", "SCBANK", "PHONPE", "GPAY", "AIRBNK", "JIOPBS",
        "BOIIND", "HDFCBN", "UCOBNK", "MAHABK", "FEDBNK", "RBLBNK", "IDBIBK", "KVBANK", "BANDHN", "AUBANK", "SIBSMS",
        "JKBANK", "CBSSBI", "SBICRD", "HDFCCC",
    )

    private val headerNames = wordStartPattern(BlockedApps.DEFAULT_PATTERNS + bankShortNames)

    private val bareHeader: Pattern = Pattern.compile(
        "^(?:" + bankShortNames.joinToString("|") { Pattern.quote(it) } + ")(?:-[ST])?$",
        Pattern.CASE_INSENSITIVE,
    )

    private val wordTerms: List<String> = listOf(

            "debit", "credit", "a/c", "acct", "account no", "avl bal", "available balance", "balance", "card ending",
            "txn", "transaction", "neft", "imps", "rtgs", "withdrawn", "withdrawal", "inr", "bank", "vpa",
            "cheque", "ifsc", "loan",
            "खाता", "खाते", "खातों", "खाताधारक", "जमा", "निकासी", "रुपये", "रुपए", "बैंक", "बैलेंस", "डेबिट", "क्रेडिट", "शेष",
            )

    /**
     * The same transaction cues in Chinese. Chinese writes the amount without spaces ("余额 1,234.56 元"), so
     * these match anywhere in the text, and the yuan sign is handled by [wholeWords] like ₹.
     */
    private val localTerms: List<String> = listOf(
        "余额", "可用余额", "账户余额", "转账", "转入", "转出", "汇款", "到账", "入账", "支出", "收入", "扣款", "消费",
        "交易", "账单", "还款", "应还", "分期", "信用额度", "尾号", "银行卡", "储蓄卡", "信用卡", "人民币",
        "支付成功", "付款成功", "提现", "充值", "红包",
    )

    /** Chinese matches anywhere (no word boundaries in Chinese), so a chat name such as "招商银行" counts too. */
    private val hanMoney = wordStartPattern(localTerms)

    private val words = wordStartPattern(wordTerms + localTerms)

    /** Names are matched by whole word only, so a contact called "Bankey" or "Loanna" is not mistaken for a bank. */
    private val nameWords: Pattern = Pattern.compile(
        """(?<![\p{L}\p{N}\p{M}])(?:""" + (wordTerms + BlockedApps.DEFAULT_PATTERNS).joinToString("|") { Pattern.quote(it) } +
            """)(?![\p{L}\p{N}\p{M}])""",
        Pattern.CASE_INSENSITIVE or Pattern.UNICODE_CASE,
    )

    /**
     * Whole-word terms ("emi" must not match "Emily"), "Rs" before an amount, "spent" before a money cue, and a
     * rupee sign before an amount. "₹" needs no word boundary before it ("spent₹450"); "रु" does, so "गुरु" is kept.
     */
    private val wholeWords: Pattern = Pattern.compile(
        """(?<![\p{L}\p{N}])(?:emi|upi|rs\.?\s*\p{N}|spent\s{1,3}(?:rs\.?|inr|₹|\p{N}|on card))(?![\p{L}])""" +
            """|₹\s*\p{N}|(?<![\p{L}\p{N}\p{M}])रु\.?\s*\p{N}|[¥￥]\s*\p{N}|\p{N}\s{0,3}元""",
        Pattern.CASE_INSENSITIVE or Pattern.UNICODE_CASE,
    )

    /** For a title or chat name: a bank-style header, or a money term as a whole word. */
    fun isMoneyName(name: String?): Boolean {
        if (name == null) return false
        val form = matchForm(name).trim()
        if (isBankHeader(form)) return true
        return nameWords.matcher(form).find() || wholeWords.matcher(form).find() || hanMoney.find(form)
    }

    fun isMoney(sender: String?, text: String): Boolean {
        if (sender != null && isBankHeader(matchForm(sender).trim())) return true
        return words.find(text) || wholeWords.matcher(matchForm(text)).find()
    }

    /** "VM-HDFCBK" style headers, or a bare bank short name such as "HDFCBK" as the whole name. */
    private fun isBankHeader(form: String): Boolean {
        val m = header.matcher(form)
        if (m.matches() && headerNames.find(m.group(1)!!)) return true
        return bareHeader.matcher(form).matches()
    }
}
