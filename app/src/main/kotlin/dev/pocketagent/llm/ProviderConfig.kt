package dev.pocketagent.llm

import java.net.URI
import java.net.URISyntaxException

enum class ProviderKind { ANTHROPIC, OPENAI_COMPAT }

/** The header an API key travels in, for every provider that takes a key. */
const val AUTH_HEADER_API_KEY = "x-api-key"

/** The header a DeepSeek account token travels in; see `dev.pocketagent.auth.DeepSeekAccountLogin`. */
const val AUTH_HEADER_DSH = "x-dsh-auth-token"

/**
 * One configured LLM endpoint. The key is bring-your-own and is never printed: [toString] masks it and no
 * validation message includes it.
 */
data class ProviderConfig(
    val kind: ProviderKind,
    val baseUrl: String,
    val apiKey: String,
    val model: String,
    val supportsImages: Boolean,
    val contextTokens: Int = 128_000,
    val maxOutputTokens: Int = 4096,
    val effort: String? = null,
    /** Header that carries the credential. DeepSeek account tokens use [AUTH_HEADER_DSH], API keys `x-api-key`. */
    val authHeader: String = AUTH_HEADER_API_KEY,
) {
    private val parsed: URI? = try {
        URI(baseUrl)
    } catch (_: URISyntaxException) {
        null
    }

    /**
     * The host as parsed by [java.net.URI] (never by string prefix), lower-cased, for exact comparisons.
     * It is the empty string when [baseUrl] cannot be parsed or has no host, so it never equals a real host.
     * IPv6 literals keep their brackets (for example `[::1]`).
     */
    val host: String get() = parsed?.host?.lowercase().orEmpty()

    fun validate(): ConfigCheck {
        val errors = mutableListOf<String>()
        val warnings = mutableListOf<String>()
        val plainHttpLocal = checkUrl(errors, warnings)
        checkKey(errors, plainHttpLocal)
        if (model.isBlank() || model.length > MAX_MODEL_LENGTH || model.any { it.isISOControl() }) {
            errors += "模型名要有 1 到 $MAX_MODEL_LENGTH 个字符，且不能有控制字符。"
        }
        if (contextTokens !in CONTEXT_RANGE) {
            errors += "上下文大小要在 ${CONTEXT_RANGE.first} 到 ${CONTEXT_RANGE.last} 个 token 之间。"
        }
        if (maxOutputTokens !in OUTPUT_RANGE) {
            errors += "最大输出要在 ${OUTPUT_RANGE.first} 到 ${OUTPUT_RANGE.last} 个 token 之间。"
        }
        if (effort != null && effort !in EFFORTS) {
            errors += "Effort must be one of ${EFFORTS.joinToString(", ")}."
        }
        return ConfigCheck(errors, warnings)
    }

    /** Validates the base URL; returns true when it is a plain-http URL to a local server. */
    private fun checkUrl(errors: MutableList<String>, warnings: MutableList<String>): Boolean {
        val uri = parsed
        if (uri == null || host.isEmpty()) {
            errors += "Base URL 不是带主机名的合法地址。"
            return false
        }
        if (uri.rawUserInfo != null) errors += "Base URL 里不能带账号密码（user:password@）。"
        if (uri.rawQuery != null || uri.rawFragment != null) {
            errors += "Base URL 里不能带查询参数或锚点。"
        }
        return when (uri.scheme?.lowercase()) {
            "https" -> false
            "http" -> if (isLocalHost(host)) {
                warnings += "这条连接没有加密，只在你信任的网络里使用。"
                true
            } else {
                errors += "http 只允许用于 localhost、.local 域名和私有网段；请改用 https。"
                false
            }
            else -> {
                errors += "Base URL 必须用 https。"
                false
            }
        }
    }

    private fun checkKey(errors: MutableList<String>, plainHttpLocal: Boolean) {
        if (apiKey.isEmpty()) {
            if (!plainHttpLocal) errors += "除非是本机 http 服务，否则需要填 API 密钥。"
        } else if (apiKey.any { it !in '!'..'~' }) {
            errors += "API 密钥只能包含可打印的 ASCII 字符，不能有空格。"
        }
    }

    override fun toString() =
        "ProviderConfig(kind=$kind, baseUrl=$baseUrl, apiKey=***, model=$model, supportsImages=$supportsImages, " +
            "contextTokens=$contextTokens, maxOutputTokens=$maxOutputTokens, effort=$effort, authHeader=$authHeader)"

    private companion object {
        const val MAX_MODEL_LENGTH = 100
        val CONTEXT_RANGE = 4_000..2_000_000
        val OUTPUT_RANGE = 512..8_192
        val EFFORTS = listOf("low", "medium", "high", "xhigh", "max")
        val IPV4 = Regex("""(0|[1-9]\d{0,2})(\.(0|[1-9]\d{0,2})){3}""")

        fun isLocalHost(host: String): Boolean = host == "localhost" || host.endsWith(".local") || isPrivateIpv4(host)

        /** Only IPv4 literals are classified; IPv6 and every other hostname count as public. */
        fun isPrivateIpv4(host: String): Boolean {
            if (!IPV4.matches(host)) return false
            val o = host.split('.').map { it.toInt() }
            if (o.any { it > 255 }) return false
            return o[0] == 10 ||
                o[0] == 127 ||
                (o[0] == 172 && o[1] in 16..31) ||
                (o[0] == 192 && o[1] == 168) ||
                (o[0] == 100 && o[1] in 64..127)
        }
    }
}

data class ConfigCheck(val errors: List<String>, val warnings: List<String>) {
    val ok: Boolean get() = errors.isEmpty()
}
