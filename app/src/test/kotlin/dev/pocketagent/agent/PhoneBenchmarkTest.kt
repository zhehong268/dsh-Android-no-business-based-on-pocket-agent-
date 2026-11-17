package dev.pocketagent.agent

import dev.pocketagent.device.*
import dev.pocketagent.fakes.*
import dev.pocketagent.llm.*
import dev.pocketagent.playbook.PlaybookStore
import dev.pocketagent.safety.*
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized
import kotlin.test.*

@RunWith(Parameterized::class)
class PhoneBenchmarkTest(private val number:Int, private val family:String) {
    private fun call(name:String,vararg args:Pair<String,Any>)=LlmResult.Call("b-$name",name,buildJsonObject { args.forEach { (k,v)->when(v){is Boolean->put(k,v);else->put(k,v.toString())} } })
    @Test fun taskReplayThreeTimes()=runTest {
        repeat(3) {
            val target="目标$number"
            val initial=when(family) {
                "exact" -> screenOf(node("a1",text=target+"其他",clickable=true),node("a2",text=target,clickable=true))
                "ambiguous" -> screenOf(node("a1",text=target,clickable=true),node("a2",text=target,clickable=true))
                "state" -> screenOf(node("a1",text=target,clickable=true).copy(checkable=true,checked=false))
                "denied" -> screenOf(node("a1",text="发送",clickable=true))
                else -> screenOf(node("a1",text=target,clickable=true))
            }
            val after=if(family=="state") screenOf(node("b1",text=target,clickable=true).copy(checkable=true,checked=true)) else screenOf(node("b1",text="已完成$number"))
            val first=if(family in setOf("exact","ambiguous"))call("tap_text","text" to target) else call("tap","node_id" to "a1")
            val llm=FakeLlm(listOf(first,call("done","summary" to "检查完成","success" to true)))
            val ui=FakeUi().apply { onConfirm={family!="denied"} }
            val device=FakeDevice(listOf(after),resultFor={if(family=="failed")ActionResult.Failed("模拟失效") else ActionResult.Ok})
            val outcome=AgentLoop(llm,device,SafetyGate(BlockedApps(),"dev.pocketagent"),PlaybookStore.load(emptyMap(),BlockedApps()),ScreenSerializer("dev.pocketagent"),ui,autoVision=false).run("基准任务$number",initial)
            when(family) {
                "exact"->{assertEquals("a2",(device.actions.single() as DeviceAction.Tap).node.id);assertTrue(outcome.success)}
                "ambiguous"->{assertTrue(device.actions.isEmpty());assertTrue(llm.turns[1].toolResult!!.isError)}
                "state"->{assertTrue(outcome.success);assertTrue(llm.turns[1].toolResult!!.text.contains("勾选状态"))}
                "denied"->{assertTrue(device.actions.isEmpty());assertFalse(outcome.success)}
                "failed"->{assertFalse(outcome.success);assertEquals(1,device.actions.size)}
            }
        }
    }
    companion object {
        @JvmStatic @Parameterized.Parameters(name="{index}: {1}-{0}") fun cases():List<Array<Any>> =
            (0 until 30).map { arrayOf(it,listOf("exact","ambiguous","state","denied","failed")[it%5]) }
    }
}
