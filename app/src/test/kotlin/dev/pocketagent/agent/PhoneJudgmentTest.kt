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

class PhoneJudgmentTest {
    private fun call(name:String,vararg args:Pair<String,Any>)=LlmResult.Call(name,name,buildJsonObject {
        args.forEach { (key,value)->when(value){is Boolean->put(key,value);is Int->put(key,value);else->put(key,value.toString())} }
    })
    private fun done(ok:Boolean=true)=call("done","summary" to "检查结果","success" to ok,"verify_text" to "明天十点见")
    private fun chat(prefix:String="a",focused:Boolean=false,sent:Boolean=false)=screenOf(
        node(prefix+"1",role="input",text=if(sent)null else "明天十点见",editable=true).copy(viewId="message",focused=focused,hint="输入消息"),
        node(prefix+"2",role="button",text="发送",clickable=true),
        node(prefix+"3",text=if(sent)"明天十点见 已发送" else "张三"),
    )
    private fun loop(llm:FakeLlm,device:FakeDevice,ui:AgentUi=FakeUi(),voter:LlmClient?=null)=AgentLoop(llm,device,
        SafetyGate(BlockedApps(),"dev.pocketagent"),PlaybookStore.load(emptyMap(),BlockedApps()),ScreenSerializer("dev.pocketagent"),ui,
        autoVision=false,voter=voter)

    @Test fun draftTextCannotClearPendingSendOrProveCompletion()=runTest {
        val llm=FakeLlm(listOf(call("tap_text","text" to "发送"),call("wait_for","text" to "明天十点见","seconds" to 1),done()))
        val device=FakeDevice(listOf(chat("b",focused=true)))
        val outcome=loop(llm,device).run("给张三发消息",chat())
        assertFalse(outcome.success);assertEquals(1,device.actions.size)
        assertTrue(llm.turns[2].toolResult!!.isError)
        assertTrue(outcome.summary.contains("发送结果尚未核验"))
    }
    @Test fun pendingSendIsNotRetriedWithAFreshNodeId()=runTest {
        val llm=FakeLlm(listOf(call("tap_text","text" to "发送"),call("read_screen"),call("tap_text","text" to "发送"),done(false)))
        val device=FakeDevice(listOf(chat("b",focused=true)))
        val ui=FakeUi();loop(llm,device,ui).run("给张三发消息",chat())
        assertEquals(1,device.actions.size);assertEquals(1,ui.confirmRequests.size)
        assertTrue(llm.turns[3].toolResult!!.text.contains("不能重复发送"))
    }
    @Test fun aRejectedNativeDispatchCanBeRetriedAfterAChangedObservation()=runTest {
        var attempt=0
        val device=FakeDevice(listOf(chat("b"),chat("c",focused=true),chat("d",sent=true)),resultFor={
            if(attempt++==0)ActionResult.Failed("界面变了") else ActionResult.Ok
        })
        val llm=FakeLlm(listOf(call("tap_text","text" to "发送"),call("read_screen"),call("tap_text","text" to "发送"),done()))
        assertTrue(loop(llm,device).run("发消息",chat()).success)
        assertEquals(2,device.actions.size)
    }
    @Test fun anInterruptedGestureIsNotTreatedAsAnUnsentMessage()=runTest {
        val device=FakeDevice(listOf(chat("b",focused=true)),resultFor={ActionResult.Failed("手势被打断",mayHaveEffect=true)})
        val llm=FakeLlm(listOf(call("tap_text","text" to "发送"),call("read_screen"),call("tap_text","text" to "发送"),done(false)))
        loop(llm,device).run("发消息",chat())
        assertEquals(1,device.actions.size)
    }
    @Test fun doubleTapCannotSendTwoMessages()=runTest {
        val llm=FakeLlm(listOf(call("double_tap","node_id" to "a2"),done(false)))
        val device=FakeDevice(listOf(chat()))
        loop(llm,device).run("发消息",chat())
        assertTrue(device.actions.isEmpty());assertTrue(llm.turns[1].toolResult!!.isError)
    }
    @Test fun semanticSelectorsAreValidatedAndFindTheBlankField()=runTest {
        val screen=screenOf(node("a1",role="input",editable=true).copy(viewId="app:id/composer"),node("a2",text="搜索"))
        val llm=FakeLlm(listOf(call("find_elements","editable" to true,"view_id" to "composer"),done(false)))
        loop(llm,FakeDevice(listOf(screen))).run("找输入框",screen)
        assertTrue(llm.turns[1].toolResult!!.text.contains("[a1]"))
        assertFalse(llm.turns[1].toolResult!!.text.contains("[a2]"))
        assertTrue(Tools.parse("find_elements",buildJsonObject{}).isFailure)
        assertTrue(Tools.parse("find_elements",buildJsonObject{put("role","anything")}).isFailure)
    }
    @Test fun twoFailuresObtainAnIndependentAlternativeAndCountItsUsage()=runTest {
        val first=screenOf(node("a1",text="入口一",clickable=true))
        val second=screenOf(node("b1",text="入口二",clickable=true))
        val third=screenOf(node("c1",text="其他入口",clickable=true))
        val llm=FakeLlm(listOf(call("tap","node_id" to "a1"),call("tap","node_id" to "b1"),done(false)),usage=TokenUsage(20,9))
        val voter=FakeLlm(listOf(call("suggest_recovery","next" to "改用顶部搜索","verify" to "完整姓名一致")),usage=TokenUsage(11,6))
        val device=FakeDevice(listOf(second,third),resultFor={ActionResult.Failed("模拟无效点击")})
        val result=loop(llm,device,voter=voter).run("找联系人",first)
        assertEquals(1,voter.sessions);assertEquals(TokenUsage(31,15),result.usage)
        assertTrue(llm.turns[2].systemNote.orEmpty().contains("顶部搜索"))
    }
    @Test fun textLookupCanSearchUpInsteadOfScrollingFurtherDown()=runTest {
        val initial=screenOf(node("a1",text="列表底部"))
        val target=screenOf(node("b1",text="目标",clickable=true))
        val llm=FakeLlm(listOf(call("tap_text","text" to "目标","direction" to "up","scrolls" to 1),done(false)))
        val device=FakeDevice(listOf(target,screenOf(node("c1",text="完成"))))
        loop(llm,device).run("向上找目标",initial)
        assertEquals(Direction.UP,(device.actions.first() as DeviceAction.Scroll).direction)
        assertIs<DeviceAction.Tap>(device.actions[1])
    }

    @Test fun aPendingSendSurvivesProcessRestartAndCannotBeClearedByDraftText()=runTest {
        val progress=ProgressStore(MemoryValues())
        progress.save(TaskProgress("发消息",uncertainAction=true,pendingMessage=true))
        val llm=FakeLlm(listOf(call("wait_for","text" to "明天十点见","seconds" to 1),call("tap_text","text" to "发送"),done()))
        val device=FakeDevice(listOf(chat()))
        val runner=AgentLoop(llm,device,SafetyGate(BlockedApps(),"dev.pocketagent"),PlaybookStore.load(emptyMap(),BlockedApps()),ScreenSerializer("dev.pocketagent"),FakeUi(),progress=progress,autoVision=false)
        assertFalse(runner.run("发消息",chat()).success)
        assertTrue(device.actions.isEmpty());assertTrue(llm.turns[1].toolResult!!.isError)
        assertTrue(progress.load()!!.pendingMessage)
    }

    @Test fun anExplicitNotSentAnswerRequiresARealSendBeforeCompletion()=runTest {
        val progress=ProgressStore(MemoryValues());progress.save(TaskProgress("发消息",uncertainAction=true,pendingMessage=true))
        val llm=FakeLlm(listOf(call("ask_user","question" to "上次消息是否已发送？请回答已发送或未发送"),done()))
        val ui=FakeUi().apply { onAsk={"未发送"} }
        val device=FakeDevice(listOf(chat()))
        val runner=AgentLoop(llm,device,SafetyGate(BlockedApps(),"dev.pocketagent"),PlaybookStore.load(emptyMap(),BlockedApps()),ScreenSerializer("dev.pocketagent"),ui,progress=progress,autoVision=false)
        assertFalse(runner.run("发消息",chat()).success)
        assertTrue(device.actions.isEmpty())
    }

    @Test fun vagueUserAgreementCannotAuthorizeAnUncertainResend()=runTest {
        val progress=ProgressStore(MemoryValues());progress.save(TaskProgress("发消息",uncertainAction=true,pendingMessage=true))
        val llm=FakeLlm(listOf(call("ask_user","question" to "上次消息是否已发送？"),call("tap_text","text" to "发送"),done(false)))
        val ui=FakeUi().apply { onAsk={"好的"} };val device=FakeDevice(listOf(chat()))
        val runner=AgentLoop(llm,device,SafetyGate(BlockedApps(),"dev.pocketagent"),PlaybookStore.load(emptyMap(),BlockedApps()),ScreenSerializer("dev.pocketagent"),ui,progress=progress,autoVision=false)
        runner.run("发消息",chat());assertTrue(device.actions.isEmpty())
    }

    @Test fun imeSendAlsoRequiresMessageEvidence()=runTest {
        val llm=FakeLlm(listOf(call("type","node_id" to "a1","text" to "明天十点见","submit" to true),done()))
        val device=FakeDevice(listOf(chat("b",focused=true)))
        assertFalse(loop(llm,device).run("发消息",chat()).success)
    }

    @Test fun aDisabledSendDoesNotAskOrDispatch()=runTest {
        val screen=chat().copy(root=chat().root.copy(children=chat().root.children.map { if(it.id=="a2")it.copy(enabled=false) else it }))
        val llm=FakeLlm(listOf(call("tap","node_id" to "a2"),done(false)))
        val device=FakeDevice(listOf(screen));val ui=FakeUi()
        loop(llm,device,ui).run("发消息",screen)
        assertTrue(device.actions.isEmpty());assertTrue(ui.confirmRequests.isEmpty())
    }

    @Test fun anUnlabelledTargetCannotBypassThePendingSendGuard()=runTest {
        val base=chat("b",focused=true)
        val unknown=base.copy(root=base.root.copy(children=base.root.children.map { if(it.id=="b2")it.copy(text=null) else it }))
        val llm=FakeLlm(listOf(call("tap_text","text" to "发送"),call("tap","node_id" to "b2"),done(false)))
        val device=FakeDevice(listOf(unknown));loop(llm,device).run("发消息",chat())
        assertEquals(1,device.actions.size);assertTrue(llm.turns[2].toolResult!!.isError)
    }

    @Test fun appendAndSubmitMustVerifyTheEntireMessageRatherThanOnlyTheSuffix()=runTest {
        val base=chat();val initial=base.copy(root=base.root.copy(children=base.root.children.map { if(it.id=="a1")it.copy(text="明天") else it }))
        val after=chat("b",sent=true);val wrong=after.copy(root=after.root.copy(children=after.root.children.map { if(it.id=="b3")it.copy(text="十点见") else it }))
        val llm=FakeLlm(listOf(call("type","node_id" to "a1","text" to "十点见","mode" to "append","submit" to true),done()))
        assertFalse(loop(llm,FakeDevice(listOf(wrong))).run("发消息",initial).success)
    }
}
