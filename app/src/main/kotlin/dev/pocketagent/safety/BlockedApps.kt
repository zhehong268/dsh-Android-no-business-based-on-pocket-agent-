package dev.pocketagent.safety

import java.util.Locale

/**
 * Apps the agent never opens or acts in: banking, payments, password managers, and the system screens that
 * could switch off its own safety. Matches by package, by app name, and by word-start patterns on the
 * name or on each dot-separated part of the package. Over-blocking is accepted.
 */
class BlockedApps(
    private val packages: Set<String> = DEFAULT_PACKAGES,
    private val patterns: List<String> = DEFAULT_PATTERNS,
    private val names: Set<String> = DEFAULT_NAMES,
) {
    private val labelPattern = if (patterns.isEmpty()) null else wordStartPattern(patterns)

    fun isBlocked(packageName: String, label: String?): Boolean {
        if (packageName in packages) return true
        val name = matchForm(label.orEmpty()).trim()
        if (name.isNotEmpty()) {
            if (name.lowercase(Locale.ROOT) in names) return true
            if (labelPattern?.find(name) == true) return true
        }
        return packageName.split('.').any { part -> part.isNotEmpty() && patterns.any { part.startsWith(it, ignoreCase = true) } }
    }

    companion object {
        val DEFAULT_PACKAGES: Set<String> = setOf(
            "com.google.android.apps.nbu.paisa.user", "net.one97.paytm", "com.phonepe.app", "in.org.npci.upiapp",
            "com.google.android.apps.walletnfcrel", "com.x8bit.bitwarden", "com.agilebits.onepassword",
            "com.lastpass.lpandroid", "com.google.android.apps.authenticator2",
            "com.android.settings", "com.android.systemui", "com.google.android.packageinstaller",
            "com.android.packageinstaller", "com.google.android.permissioncontroller", "com.android.permissioncontroller",
            // Banking and card apps whose names and packages hold none of the patterns below.
            "com.sbi.lotusintouch", "com.sbi.SBIFreedomPlus", "com.snapwork.hdfc", "com.axis.mobile", "com.msf.kbank.mobile",
            "com.kotak811mobilebankingapp.instantsavingsbankaccount", "com.csam.icici.bank.imobile", "com.dreamplug.androidapp",
            // Chinese banking, payment and wallet apps: Alipay, UnionPay, and the big state banks.
            "com.eg.android.AlipayGphone", "com.unionpay", "com.icbc", "com.icbc.iphoneclient", "com.chinamworld.main",
            "com.chinamworld.bocmbci", "com.android.bankabc", "com.bankcomm.Bankcomm", "com.cmbchina.ccd.pluto.cmbActivity",
            "com.pingan.paces.ccms", "com.spdb.mobilebank.per", "com.cebbank.mobile.cemb", "com.citic.bank.mobile",
            "cn.com.cmbc.newmbank", "com.ecitic.bank.mobile", "com.zxyc.xmbank", "com.tencent.tcr", "com.jd.jrapp",
        )
        val DEFAULT_PATTERNS: List<String> = listOf(
            "bank", "pay", "wallet", "upi", "password",
            "银行", "支付", "钱包", "转账", "汇款", "证券", "基金", "信用卡", "账单", "还款", "网银", "手机银行",
        )

        /** Lower-case app names that the word patterns miss, used when only the name is known (open_app). */
        val DEFAULT_NAMES: Set<String> = setOf(
            "phonepe", "paytm", "google pay", "gpay", "bhim", "bitwarden", "1password", "lastpass", "authenticator",
            "google authenticator", "settings",
            // Bank names as whole names only: a word pattern "sbi" or "axis" would also block unrelated apps.
            "yono", "yono sbi", "yono lite sbi", "sbi", "hdfc", "icici", "imobile", "axis", "axis mobile", "kotak",
            "kotak 811", "cred",
            // Chinese names whose label does not start with a pattern word (支付宝 starts with 支, 云闪付 with 云).
            "支付宝", "云闪付", "微信支付", "京东金融", "度小满", "招行", "招商银行", "工商银行", "建设银行", "农业银行",
            "中国银行", "交通银行", "储蓄", "网商银行", "微众银行", "数字人民币",
        )
    }
}
