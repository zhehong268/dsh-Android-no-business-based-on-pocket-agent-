package dev.pocketagent.llm

/** Capabilities belong to a model, not to a provider account. Unknown endpoints keep the user's setting. */
object ModelCapabilities {
    fun normalize(config: ProviderConfig): ProviderConfig {
        if (config.host != "api.deepseek.com") return config
        return when (config.model.lowercase()) {
            "deepseek-v4-pro" -> config.copy(supportsImages = false)
            "deepseek-flash", "deepseek-v4-flash", "deepseek-v4-flash-vision-exp" -> config.copy(supportsImages = true)
            else -> config
        }
    }

    /** Keep eyes on the phone. A text-only Pro remains available as the independent consultant. */
    fun operator(config: ProviderConfig): ProviderConfig = normalize(
        if (config.host == "api.deepseek.com" && config.model.equals("deepseek-v4-pro", true))
            config.copy(model = "deepseek-flash") else config,
    )
}
