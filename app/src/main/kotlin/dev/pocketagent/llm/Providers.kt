package dev.pocketagent.llm

object Providers {
    /** Builds the client for [config].kind. The message lists validation errors, which never contain the key. */
    fun create(config: ProviderConfig, caller: HttpCaller): LlmClient {
        val check = config.validate()
        require(check.ok) { "服务商配置无效：" + check.errors.joinToString(" ") }
        val effective = ModelCapabilities.normalize(config)
        return when (effective.kind) {
            ProviderKind.ANTHROPIC -> AnthropicClient(effective, caller)
            ProviderKind.OPENAI_COMPAT -> OpenAiCompatClient(effective, caller)
        }
    }
}
