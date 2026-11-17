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

class SequenceExecutionTest {
    private fun call(steps:String)=LlmResult.Call("batch","perform_steps",Json.parseToJsonElement("{\"steps\":$steps}").jsonObject)
    private fun done(ok:Boolean)=LlmResult.Call("end","done",buildJsonObject{put("summary","草稿已填写");put("success",ok)})
    private val list=screenOf(node("a1",text="张三",clickable=true))
    private fun chat(prefix:String,text:String?=null)=screenOf(node(prefix+"1",text="张三"),node(prefix+"2",role="input",text=text,editable=true).copy(hint="消息输入框"),node(prefix+"3",role="button",text="发送",clickable=true))
    private fun loop(llm:FakeLlm,device:DevicePort,ui:AgentUi=FakeUi(),maxSteps:Int=10)=AgentLoop(llm,device,SafetyGate(BlockedApps(),"dev.pocketagent"),PlaybookStore.load(emptyMap(),BlockedApps()),ScreenSerializer("dev.pocketagent"),ui,maxSteps=maxSteps,autoVision=false)
    @Test fun aPlanFindsFreshTargetsBetweenActionsWithoutAnotherModelCall()=runTest {
        val llm=FakeLlm(listOf(call("""[{"action":"tap","target":{"text":"张三"},"expect_text":"消息输入框"},{"action":"type","target":{"editable":true},"text":"明天见"}]"""),done(true)))
        val device=FakeDevice(listOf(list,chat("b"),chat("c"),chat("d","明天见")))
        assertTrue(loop(llm,device).run("打开张三并填写草稿",list).success)
        assertEquals(2,device.actions.size);assertEquals("c2",(device.actions[1] as DeviceAction.TypeText).node.id)
        assertEquals(2,llm.turns.size);assertTrue(llm.turns[1].toolResult!!.text.contains("2/2"))
    }
    @Test fun sendIsNotExecutedOrApprovedInsideASequence()=runTest {
        val llm=FakeLlm(listOf(call("""[{"action":"tap","target":{"text":"发送"}}]"""),done(true)))
        val device=FakeDevice(listOf(chat("b","明天见")));val ui=FakeUi()
        assertFalse(loop(llm,device,ui).run("发送消息",chat("a","明天见")).success)
        assertTrue(device.actions.isEmpty());assertTrue(ui.confirmRequests.isEmpty())
    }
    @Test fun ambiguousTargetsStopBeforeAnyTouch()=runTest {
        val screen=screenOf(node("a1",text="同名",clickable=true),node("a2",text="同名",clickable=true))
        val llm=FakeLlm(listOf(call("""[{"action":"tap","target":{"text":"同名"}}]"""),done(false)))
        val device=FakeDevice(listOf(screen));loop(llm,device).run("找同名联系人",screen)
        assertTrue(device.actions.isEmpty());assertTrue(llm.turns[1].toolResult!!.text.contains("多个匹配"))
    }
    @Test fun aMissingExpectedPageStopsTheRemainingSteps()=runTest {
        val llm=FakeLlm(listOf(call("""[{"action":"tap","target":{"text":"张三"},"expect_text":"不会出现的页面"},{"action":"back"}]"""),done(false)))
        val device=FakeDevice(listOf(list,chat("b")))
        loop(llm,device).run("查联系人",list);assertEquals(1,device.actions.size)
    }
    @Test fun failedActionsDoNotFallThroughToTheNextStep()=runTest {
        val llm=FakeLlm(listOf(call("""[{"action":"tap","target":{"text":"张三"}},{"action":"back"}]"""),done(false)))
        val device=FakeDevice(listOf(list),resultFor={ActionResult.Failed("模拟失败")})
        loop(llm,device).run("查联系人",list);assertEquals(1,device.actions.size)
    }
    @Test fun macroActionsStillRespectTheNativeActionBudget()=runTest {
        val llm=FakeLlm(listOf(call("""[{"action":"tap","target":{"text":"张三"}},{"action":"type","target":{"editable":true},"text":"明天见"}]""")))
        val device=FakeDevice(listOf(list,chat("b"),chat("c")))
        assertEquals(EndReason.STEP_LIMIT,loop(llm,device,maxSteps=1).run("填写草稿",list).reason)
        assertEquals(1,device.actions.size)
    }
    @Test fun staleIdsCoordinatesWritesAndOversizedPlansAreRejected() {
        for(steps in listOf("[]","""[{"action":"tap","target":{"node_id":"a1"}}]""","""[{"action":"tap","target":{"x":10,"y":10}}]""","""[{"action":"write_file","text":"bad"}]""","""[{"action":"type","target":{"editable":"true"},"text":"bad"}]""","["+List(5){"{\"action\":\"back\"}"}.joinToString(",")+"]")) {
            val raw=call(steps);assertTrue(Tools.parse(raw.name,raw.input).isFailure,steps)
        }
    }
}
