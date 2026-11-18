package dev.pocketagent.agent

import dev.pocketagent.device.*
import dev.pocketagent.fakes.*
import dev.pocketagent.llm.*
import dev.pocketagent.playbook.PlaybookStore
import dev.pocketagent.safety.*
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import org.junit.Test
import kotlin.test.*

class ResumeExecutionTest {
    private val input=node("input",text="明天见",editable=true).copy(viewId="body")
    private val send=node("send",text="发送",clickable=true)
    private val initial=screenOf(input,send)
    private val sent=screenOf(input.copy(text=null),send,node("bubble",text="明天见"))
    private fun call(name:String,args:String="{}")=LlmResult.Call(name,name,Json.parseToJsonElement(args).jsonObject)
    private fun loop(llm:LlmClient,device:DevicePort,progress:ProgressStore)=AgentLoop(llm,device,SafetyGate(BlockedApps(),"dev.pocketagent"),PlaybookStore.load(emptyMap(),BlockedApps()),ScreenSerializer("dev.pocketagent"),FakeUi(),progress=progress,autoVision=false)
    @Test fun aFailedSendTaskResumesByCheckingTheReceiptAndNeverResends()=runTest {
        val progress=ProgressStore(MemoryValues())
        progress.save(TaskProgress("发送消息",completed="草稿已填",uncertainAction=true,pendingMessage=true,messageReceipt=MessageEffect.begin(initial,send,"明天见")!!.receipt))
        val llm=FakeLlm(listOf(call("done","""{"summary":"消息已发出","success":true}""")))
        val device=FakeDevice(listOf(sent))
        assertTrue(loop(llm,device,progress).run("发送消息",sent).success)
        assertTrue(device.actions.isEmpty());assertNull(progress.load("发送消息"))
        assertTrue(llm.turns.first().text.contains("草稿已填"))
        assertTrue(llm.turns.first().text.contains("上步结果不明=false"))
        assertTrue(llm.turns.first().systemNote.orEmpty().contains("不要重发"))
    }
    @Test fun aDelayedBubbleIsVerifiedLocallyBeforeAnotherModelRequest()=runTest {
        val device=FakeDevice(listOf(initial,sent))
        val llm=FakeLlm(listOf(call("tap","""{"node_id":"send","finish_if_verified":true}""")))
        val result=loop(llm,device,ProgressStore(MemoryValues())).run("发送消息",initial)
        assertTrue(result.success);assertEquals(1,device.actions.size);assertEquals(1,llm.turns.size)
    }
    @Test fun anExistingBubbleInTheRestoredPageCannotClearAnUncertainReceipt()=runTest {
        val old=screenOf(input,send,node("old",text="明天见"))
        val progress=ProgressStore(MemoryValues())
        progress.save(TaskProgress("发送消息",pendingMessage=true,messageReceipt=MessageEffect.begin(old,send,"明天见")!!.receipt))
        val llm=FakeLlm(listOf(call("tap_text","""{"text":"发送"}"""),call("done","""{"summary":"未核验","success":false}""")))
        val device=FakeDevice(listOf(sent))
        assertFalse(loop(llm,device,progress).run("发送消息",sent).success)
        assertTrue(device.actions.isEmpty());assertTrue(progress.load()!!.pendingMessage)
    }
}
