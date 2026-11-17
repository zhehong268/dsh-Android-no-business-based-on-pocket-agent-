package dev.pocketagent.llm

/** Capabilities belong to a model, not to a provider account. Unknown endpoints keep the user's setting. */
object ModelCapabilities {
    fun normalize(config: ProviderConfig): ProviderConfig {
        if (config.host != "api.deepseek.com") return config
        val tuned = config.copy(effort = DeepSeekOptions.effort(config), maxOutputTokens = DeepSeekOptions.budget(config))
        return when (config.model.lowercase()) {
            "deepseek-v4-pro" -> tuned.copy(supportsImages = false, contextTokens = 1_048_576)
            "deepseek-flash", "deepseek-v4-flash", "deepseek-v4-flash-vision-exp" -> tuned.copy(model = "deepseek-flash", supportsImages = true, contextTokens = 1_048_576)
            else -> config
        }
    }

    /** Keep eyes on the phone. A text-only Pro remains available as the independent consultant. */
    fun operator(config: ProviderConfig): ProviderConfig = normalize(
        if (config.host == "api.deepseek.com" && config.model.equals("deepseek-v4-pro", true))
            config.copy(model = "deepseek-flash") else config,
    )

    fun consultant(config: ProviderConfig): ProviderConfig = normalize(
        if (DeepSeekOptions.applies(config)) config.copy(effort = "max") else config,
    )
}
