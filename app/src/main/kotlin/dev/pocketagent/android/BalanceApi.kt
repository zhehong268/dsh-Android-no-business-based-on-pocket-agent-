package dev.pocketagent.android

import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * Reads the account balance from the DeepSeek platform.
 *
 * The account token is the same one the model calls use, and it goes in `x-dsh-auth-token` — `Authorization:
 * Bearer` is answered with 401 on this endpoint (checked against the live API on 2026-11-16).
 */
internal object BalanceApi {

    private const val URL = "https://api.deepseek.com/user/balance"

    sealed interface Result {
        data class Ok(val total: String, val currency: String) : Result
        data class Failed(val reason: String) : Result
    }

    /** Blocks; call from a background dispatcher. */
    fun fetch(token: String): Result {
        if (token.isBlank()) return Result.Failed("还没登录账号。")
        var connection: HttpURLConnection? = null
        return try {
            connection = (URL(URL).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 15_000
                readTimeout = 20_000
                setRequestProperty("accept", "application/json")
                setRequestProperty("x-dsh-auth-token", token)
            }
            val code = connection.responseCode
            if (code == 401 || code == 403) return Result.Failed("登录过期了，重新登录一次。")
            if (code != 200) return Result.Failed("查不到余额（HTTP $code）。")
            val body = connection.inputStream.bufferedReader().use { it.readText() }
            parse(body)
        } catch (_: IOException) {
            Result.Failed("连不上 DeepSeek，检查一下网络。")
        } finally {
            connection?.disconnect()
        }
    }

    /** Pure parsing, so it can be tested without a network. */
    fun parse(body: String): Result {
        val infos = Regex("\"balance_infos\"\\s*:\\s*\\[(.*?)]", RegexOption.DOT_MATCHES_ALL).find(body)?.groupValues?.get(1)
            ?: return Result.Failed("余额格式不认识。")
        val total = Regex("\"total_balance\"\\s*:\\s*\"([^\"]*)\"").find(infos)?.groupValues?.get(1)
            ?: return Result.Failed("余额为空。")
        val currency = Regex("\"currency\"\\s*:\\s*\"([^\"]*)\"").find(infos)?.groupValues?.get(1) ?: "CNY"
        return Result.Ok(total.trim(), currency.trim())
    }

    /** "24.74" -> 24.74, so a threshold comparison never needs Double.parseDouble at the call site. */
    fun amount(total: String): Double? = total.trim().toDoubleOrNull()

    /** "24.74" + "CNY" -> "¥24.74". */
    fun show(total: String, currency: String): String = when (currency.uppercase()) {
        "CNY" -> "¥$total"
        "USD" -> "$$total"
        else -> "$total $currency"
    }
}
