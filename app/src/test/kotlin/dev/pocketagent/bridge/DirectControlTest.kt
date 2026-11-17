package dev.pocketagent.bridge

import dev.pocketagent.agent.*
import dev.pocketagent.llm.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import kotlinx.serialization.json.*
import org.junit.Test
import kotlin.test.*

class DirectControlTest {
    @Test fun directCallsAreValidatedBeforeThePhoneIsReached()=runTest {
        val calls=mutableListOf<String>()
        val handler=McpHandler(PhoneControl { name,args -> calls+=name;buildJsonObject { put("isError",false) } }) { TaskReply.Refused("unused") }
        suspend fun rpc(params:String)=Json.parseToJsonElement(handler.handle("""{"jsonrpc":"2.0","id":1,"method":"tools/call","params":$params}""")!!).jsonObject
        assertNotNull(rpc("""{"name":"tap","arguments":{"x":1}}""")["error"])
        assertNotNull(rpc("""{"name":"control_start","arguments":{"task":"","extra":true}}""")["error"])
        assertNotNull(rpc("""{"name":"control_stop","arguments":{"task":"x"}}""")["error"])
        assertTrue(calls.isEmpty())
        assertNotNull(rpc("""{"name":"read_screen","arguments":{}}""")["result"])
        assertEquals(listOf("read_screen"),calls)
    }
    @Test fun directToolsAreOnlyListedWhenThePortExists()=runTest {
        suspend fun names(handler:McpHandler):Set<String> {
            val reply=Json.parseToJsonElement(handler.handle("""{"jsonrpc":"2.0","id":1,"method":"tools/list"}""")!!).jsonObject
            return reply["result"]!!.jsonObject["tools"]!!.jsonArray.map { it.jsonObject["name"]!!.jsonPrimitive.content }.toSet()
        }
        assertEquals(setOf("run_task"),names(McpHandler { TaskReply.Refused("unused") }))
        val all=names(McpHandler(PhoneControl { _,_->buildJsonObject {} }) { TaskReply.Refused("unused") })
        assertTrue(all.containsAll(setOf("control_start","control_stop","read_screen","tap_mark","drag")))
    }
    @Test fun theComputerGetsTheResultAfterTheOrdinaryLoopExecutesIt()=runTest {
        val client=DirectControlClient();val session=client.newSession("",emptyList())
        val call=LlmResult.Call("r1","read_screen",buildJsonObject {})
        val worker=launch { assertEquals(call,session.send(Turn("first screen")));session.send(Turn("fresh screen",ToolResult("r1","observed",false))) }
        assertEquals("first screen",client.ready.await().text)
        val result=client.execute(call);assertEquals("fresh screen",result.text);assertEquals("observed",result.toolResult!!.text)
        client.close("stopped");worker.cancelAndJoin()
    }
    @Test fun stopReleasesAnOutstandingComputerRequest()=runTest {
        val client=DirectControlClient();val session=client.newSession("",emptyList())
        val worker=launch { session.send(Turn("screen"));awaitCancellation() }
        client.ready.await()
        val result=async { client.execute(LlmResult.Call("r","tap",buildJsonObject {})) }
        runCurrent();client.close("用户停止")
        assertTrue(result.await().toolResult!!.isError);worker.cancelAndJoin()
    }
}
