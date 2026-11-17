package dev.pocketagent.llm

import dev.pocketagent.agent.ToolDefinition
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import org.junit.Test
import kotlin.test.*

class DeepSeekProtocolTest {
    private class Transport(private val responses:List<Pair<Int,String>>):HttpTransport {
        val requests=mutableListOf<JsonObject>();val headers=mutableListOf<Map<String,String>>()
        override suspend fun post(url:String,headers:Map<String,String>,body:String):HttpResponse {
            requests+=Json.parseToJsonElement(body).jsonObject;this.headers+=headers
            val reply=responses[minOf(requests.lastIndex,responses.lastIndex)]
            return HttpResponse(reply.first,emptyMap(),reply.second)
        }
    }
    private fun config(kind:ProviderKind=ProviderKind.OPENAI_COMPAT)=ProviderConfig(kind,
        if(kind==ProviderKind.ANTHROPIC)"https://api.deepseek.com/anthropic" else "https://api.deepseek.com",
        "fixture-key","deepseek-flash",true,effort="high")
    private val tools=listOf(ToolDefinition("back","返回",buildJsonObject{put("type","object")}))
    private fun openai(reason:String="complete",finish:String="tool_calls",tool:Boolean=true)=buildJsonObject {
        putJsonArray("choices") { addJsonObject {
            put("finish_reason",finish);putJsonObject("message") {
                put("role","assistant");put("content",JsonNull);put("reasoning_content",reason)
                if(tool)putJsonArray("tool_calls"){addJsonObject{put("id","c");put("type","function");putJsonObject("function"){put("name","back");put("arguments","{}")}}}
            }
        } }
        putJsonObject("usage"){put("prompt_tokens",2);put("completion_tokens",5)}
    }.toString()
    private fun anthropic(stop:String="tool_use")="""{"stop_reason":"$stop","content":[{"type":"thinking","thinking":"opaque","signature":"exact=="},{"type":"tool_use","id":"c","name":"back","input":{}}],"usage":{"input_tokens":2,"output_tokens":5}}"""

    @Test fun officialOpenaiRequestsExplicitThinkingAndEnoughOutput()=runTest {
        val t=Transport(listOf(200 to openai()));val s=OpenAiCompatClient(config(),HttpCaller(t)).newSession("规则",tools)
        assertIs<LlmResult.Call>(s.send(Turn("观察")))
        assertEquals("enabled",t.requests.single()["thinking"]!!.jsonObject["type"]!!.jsonPrimitive.content)
        assertEquals("high",t.requests.single()["reasoning_effort"]!!.jsonPrimitive.content)
        assertTrue(t.requests.single()["max_tokens"]!!.jsonPrimitive.int>=32768)
    }
    @Test fun officialAnthropicRequestsThinkingEvenWhenOptionalFieldsAreAbsent()=runTest {
        val t=Transport(listOf(200 to anthropic()));val s=AnthropicClient(config(ProviderKind.ANTHROPIC).copy(effort=null),HttpCaller(t)).newSession("规则",tools)
        s.send(Turn("观察"))
        assertEquals("enabled",t.requests.single()["thinking"]!!.jsonObject["type"]!!.jsonPrimitive.content)
        assertEquals("high",t.requests.single()["output_config"]!!.jsonObject["effort"]!!.jsonPrimitive.content)
    }
    @Test fun aTruncatedOpenaiToolIsRetriedWithoutAppendingOrExecutingIt()=runTest {
        val t=Transport(listOf(200 to openai("partial","length"),200 to openai("complete")))
        val s=OpenAiCompatClient(config(),HttpCaller(t)).newSession("规则",tools)
        assertIs<LlmResult.Call>(s.send(Turn("观察")))
        assertEquals(2,t.requests.size);assertEquals(t.requests[0]["messages"],t.requests[1]["messages"])
        assertEquals(65536,t.requests[1]["max_tokens"]!!.jsonPrimitive.int)
        assertEquals(TokenUsage(4,10),s.usage)
    }
    @Test fun anAnthropicTruncationAlsoRetriesWithoutReplayingPartialThinking()=runTest {
        val t=Transport(listOf(200 to anthropic("max_tokens"),200 to anthropic()))
        val s=AnthropicClient(config(ProviderKind.ANTHROPIC),HttpCaller(t)).newSession("规则",tools)
        assertIs<LlmResult.Call>(s.send(Turn("观察")))
        assertEquals(t.requests[0]["messages"],t.requests[1]["messages"])
        assertEquals(TokenUsage(4,10),s.usage)
    }
    @Test fun repeatedTruncationStopsWithNoCallableResult()=runTest {
        val t=Transport(listOf(200 to openai("partial","length")))
        val s=OpenAiCompatClient(config(),HttpCaller(t)).newSession("规则",tools)
        assertIs<LlmResult.Failed>(s.send(Turn("观察")));assertEquals(2,t.requests.size)
    }
    @Test fun reasoningWithoutAToolCallIsStillReplayed()=runTest {
        val t=Transport(listOf(200 to openai("opaque-first","stop",false),200 to openai()))
        val s=OpenAiCompatClient(config(),HttpCaller(t)).newSession("规则",tools)
        assertIs<LlmResult.NoCall>(s.send(Turn("观察")));s.send(Turn("请给工具调用"))
        val replay=t.requests[1]["messages"]!!.jsonArray.map{it.jsonObject}.first{it["role"]?.jsonPrimitive?.content=="assistant"}
        assertEquals("opaque-first",replay["reasoning_content"]!!.jsonPrimitive.content)
    }
    @Test fun accountCredentialsUseTheAccountHeader()=runTest {
        val t=Transport(listOf(200 to openai()));OpenAiCompatClient(config().copy(authHeader=AUTH_HEADER_DSH),HttpCaller(t)).newSession("规则",tools).send(Turn("观察"))
        assertEquals("fixture-key",t.headers.single()[AUTH_HEADER_DSH]);assertFalse("Authorization" in t.headers.single())
    }
    @Test fun aBadRequestDoesNotSilentlyDisableThinking()=runTest {
        val t=Transport(listOf(400 to "{}"));AnthropicClient(config(ProviderKind.ANTHROPIC),HttpCaller(t)).newSession("规则",tools).send(Turn("观察"))
        assertEquals(1,t.requests.size);assertNotNull(t.requests.single()["thinking"])
    }
    @Test fun aliasesAndConsultantProfilesFollowOfficialCapabilities() {
        val legacy=config().copy(model="deepseek-v4-flash-vision-exp")
        assertEquals("deepseek-flash",ModelCapabilities.operator(legacy).model)
        val pro=ModelCapabilities.consultant(config().copy(model="deepseek-v4-pro"))
        assertEquals("max",pro.effort);assertFalse(pro.supportsImages);assertTrue(pro.maxOutputTokens>=65536)
        assertEquals(1048576,pro.contextTokens)
    }
    @Test fun base64LengthDoesNotPretendToBeTextTokens() {
        fun image(size:Int)=buildJsonObject{put("type","image_url");putJsonObject("image_url"){put("url","data:image/png;base64,"+"a".repeat(size))}}
        assertEquals(ContextBudget.estimate(image(100),1024),ContextBudget.estimate(image(1000000),1024))
        assertEquals(1024,ContextBudget.estimate(image(1000000),1024))
    }
    @Test fun similarHostsDoNotGetDeepSeekSpecificOptions() {
        val unknown=config().copy(baseUrl="https://api.deepseek.com.example/v1")
        assertEquals(unknown,ModelCapabilities.normalize(unknown));assertEquals(4096,DeepSeekOptions.budget(unknown))
    }
}
