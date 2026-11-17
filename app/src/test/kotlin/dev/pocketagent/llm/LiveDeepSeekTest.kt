package dev.pocketagent.llm

import dev.pocketagent.agent.ToolDefinition
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import kotlin.test.*

/** Opt-in: a real image and two tool rounds, with credentials supplied only through the environment. */
class LiveDeepSeekTest {
    @Test fun chatCompletionsVisionAndThinkingToolHistory()=check(ProviderKind.OPENAI_COMPAT)
    @Test fun messagesVisionAndThinkingToolHistory()=check(ProviderKind.ANTHROPIC)
    private fun check(kind:ProviderKind) {
        val key=System.getenv("POCKET_AGENT_LIVE_KEY").orEmpty()
        assumeTrue("real DeepSeek key not configured",key.isNotBlank())
        val proxy=System.getenv("POCKET_AGENT_LIVE_PROXY")?.split(':')
        val names=listOf("https.proxyHost","https.proxyPort","http.proxyHost","http.proxyPort")
        val saved=names.associateWith { System.getProperty(it) }
        try {
            if(proxy?.size==2)for(prefix in listOf("https","http")) {
                System.setProperty("$prefix.proxyHost",proxy[0]);System.setProperty("$prefix.proxyPort",proxy[1])
            }
            val config=ProviderConfig(kind,if(kind==ProviderKind.ANTHROPIC)"https://api.deepseek.com/anthropic" else "https://api.deepseek.com",key,"deepseek-flash",true,effort="high")
            val client=Providers.create(config,HttpCaller(OkHttpTransport()))
            val tool=ToolDefinition("probe","回传一个简短结果。",buildJsonObject {
                put("type","object");put("additionalProperties",false);putJsonObject("properties"){putJsonObject("value"){put("type","string")}};putJsonArray("required"){add("value")}
            })
            val image=java.util.Base64.getDecoder().decode("iVBORw0KGgoAAAANSUhEUgAAACAAAAAgCAIAAAD8GO2jAAAAKUlEQVR4nO3NsQ0AAAzCMP5/mj5RNkuZ4zSZtr0DAAAAAAAAAACA/gEHMsP8LofXz44AAAAASUVORK5CYII=")
            runBlocking {
                val session=client.newSession("只调用 probe 工具。先识别图片主色并用英文小写回传；下轮按用户要求计算后回传。不要猜图。",listOf(tool))
                val first=assertIs<LlmResult.Call>(session.send(Turn("识别图片主色。",image=image)),"vision/tool request failed for $kind")
                assertEquals("probe",first.name);assertEquals("red",first.input["value"]?.jsonPrimitive?.content?.lowercase())
                val second=assertIs<LlmResult.Call>(session.send(Turn("现在计算 20+22，用十进制数字回传。",ToolResult(first.callId,"图片已核验为红色。",false))),"thinking history failed for $kind")
                assertEquals("42",second.input["value"]?.jsonPrimitive?.content)
                assertTrue(session.usage.inputTokens+session.usage.cachedInputTokens>0);assertTrue(session.usage.outputTokens>0)
            }
        } finally {
            saved.forEach { (name,value)->if(value==null)System.clearProperty(name) else System.setProperty(name,value) }
        }
    }
}
