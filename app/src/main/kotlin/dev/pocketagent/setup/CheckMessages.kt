package dev.pocketagent.setup

import dev.pocketagent.llm.CheckResult

/** Plain-language results of the model check. None of them can contain the API key. */
object CheckMessages {
    fun messageFor(result: CheckResult): String = when (result) {
        CheckResult.Works -> "模型能正常回答，并且可以使用工具。你可以继续。"
        CheckResult.NoToolCalls ->
            "此模型能回答，但无法使用工具，因此智能体无法在此模型上运行。请选择其他模型。"
        CheckResult.BadKey -> "服务提供方拒绝了这个密钥。请检查它是否正确且仍然有效。"
        CheckResult.Unreachable ->
            "无法连接到服务提供方。请检查地址和你的网络连接。"
        is CheckResult.Other -> result.message.trim().ifEmpty { "测试因未知原因失败。请重试。" }
    }
}
