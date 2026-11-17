package dev.pocketagent.llm

import dev.pocketagent.agent.ToolDefinition
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** The outcome of the setup check. [Other] carries a fixed phrase, never provider text or the key. */
sealed interface CheckResult {
    data object Works : CheckResult
    data object NoToolCalls : CheckResult
    data object BadKey : CheckResult
    data object Unreachable : CheckResult
    data class Other(val message: String) : CheckResult
}

/** One real request proving the chosen model answers with a tool call. */
object ProviderCheck {
    private const val PING = "ping"
    private const val VISION_PNG = "iVBORw0KGgoAAAANSUhEUgAAACAAAAAgCAIAAAD8GO2jAAAAKUlEQVR4nO3NsQ0AAAzCMP5/mj5RNkuZ4zSZtr0DAAAAAAAAAACA/gEHMsP8LofXz44AAAAASUVORK5CYII="

    private val pingTool = ToolDefinition(
        name = PING,
        description = "什么都不做。调用它来证明工具调用可用。",
        inputSchema = JsonObject(
            mapOf(
                "type" to JsonPrimitive("object"),
                "properties" to JsonObject(mapOf("color" to JsonObject(mapOf("type" to JsonPrimitive("string"))))),
                "required" to JsonArray(emptyList()),
                "additionalProperties" to JsonPrimitive(false),
            ),
        ),
    )

    suspend fun run(client: LlmClient): CheckResult {
        val session = client.newSession("调用 ping 工具。若附有图片，必须看图并把主色（英文小写）填入 color，不能猜测。", listOf(pingTool))
        return when (val result = session.send(Turn("现在调用 ping 工具。", image = if (client.supportsImages) java.util.Base64.getDecoder().decode(VISION_PNG) else null))) {
            is LlmResult.Call -> if (result.name != PING) CheckResult.NoToolCalls else if (client.supportsImages && (result.input["color"] as? JsonPrimitive)?.content?.lowercase() != "red") CheckResult.Other("图片识别测试未通过；请检查模型视觉能力。") else CheckResult.Works
            is LlmResult.NoCall -> CheckResult.NoToolCalls
            is LlmResult.Refused -> CheckResult.Other("模型拒绝了这次测试请求。")
            is LlmResult.Failed -> when (result.kind) {
                FailureKind.AUTH -> CheckResult.BadKey
                FailureKind.NETWORK -> CheckResult.Unreachable
                else -> CheckResult.Other("测试请求失败了。")
            }
        }
    }
}
