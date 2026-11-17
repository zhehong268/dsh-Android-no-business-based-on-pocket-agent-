package dev.pocketagent.llm

/** Explicit thinking settings for the official DeepSeek endpoints; never applied to look-alike hosts. */
internal object DeepSeekOptions {
    fun applies(config: ProviderConfig) = config.host == "api.deepseek.com"
    fun effort(config: ProviderConfig): String = when (config.effort) {
        "low" -> "low"
        "max" -> "max"
        else -> "high"
    }
    fun budget(config: ProviderConfig): Int = if (applies(config))
        maxOf(config.maxOutputTokens, if (effort(config) == "max") 65_536 else 32_768)
        else config.maxOutputTokens
    fun expanded(current: Int): Int = (current * 2).coerceAtMost(131_072)
}
