package dev.pocketagent.agent

import dev.pocketagent.device.*
import dev.pocketagent.fakes.*
import dev.pocketagent.llm.*
import dev.pocketagent.playbook.PlaybookStore
import dev.pocketagent.safety.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import org.junit.Test
import kotlin.test.*

class VerifiedCompletionTest {
    private val body = "明天十点见"
    private val field = node("a1", role="input", editable=true).copy(viewId="message", hint="消息输入框")
    private val send = node("a2", role="button", text="发送", clickable=true)
    private fun chat(text:String?=null, vararg extra:ScreenNode) = screenOf(field.copy(text=text), send, *extra)
    private fun call(name:String, json:String) = LlmResult.Call("call", name, Json.parseToJsonElement(json).jsonObject)
    private fun done() = call("done", """{"summary":"未完成","success":false}""")
    private fun loop(llm:FakeLlm, device:DevicePort, ui:FakeUi=FakeUi()) = AgentLoop(
        llm, device, SafetyGate(BlockedApps(),"dev.pocketagent"), PlaybookStore.load(emptyMap(),BlockedApps()),
        ScreenSerializer("dev.pocketagent"), ui, autoVision=false,
    )

    @Test fun verifiedDraftFinishesWithoutAnotherModelRequest() = runTest {
        val llm = FakeLlm(listOf(call("type", """{"node_id":"a1","text":"$body","finish_if_verified":true}""")))
        val outcome = loop(llm,FakeDevice(listOf(chat(body)))).run("填写草稿",chat())
        assertTrue(outcome.success)
        assertEquals(1, outcome.stats.steps)
        assertEquals(1, outcome.stats.actions)
    }

    @Test fun aDispatchWithoutTheExpectedInputDoesNotFinish() = runTest {
        val llm = FakeLlm(listOf(call("type", """{"node_id":"a1","text":"$body","finish_if_verified":true}"""),done()))
        assertFalse(loop(llm,FakeDevice(listOf(chat("错误正文")))).run("填写草稿",chat()).success)
        assertEquals(2,llm.turns.size)
    }

    @Test fun aVerifiedSendStillRequiresOneConfirmation() = runTest {
        val llm = FakeLlm(listOf(call("tap_text", """{"text":"发送","scrolls":0,"finish_if_verified":true}""")))
        val device = FakeDevice(listOf(chat(null,node("b3",text=body))))
        val ui = FakeUi()
        assertTrue(loop(llm,device,ui).run("发送已有草稿",chat(body)).success)
        assertEquals(1,ui.confirmRequests.size)
        assertEquals(1,device.actions.size)
        assertEquals(1,llm.turns.size)
    }

    @Test fun refusingASendNeverDispatchesOrAutomaticallyFinishes() = runTest {
        val llm = FakeLlm(listOf(call("tap_text", """{"text":"发送","scrolls":0,"finish_if_verified":true}"""),done()))
        val device = FakeDevice(listOf(chat(body)))
        val ui = FakeUi().apply { onConfirm={false} }
        assertFalse(loop(llm,device,ui).run("发送草稿",chat(body)).success)
        assertTrue(device.actions.isEmpty())
    }

    @Test fun aGestureInterruptedAfterSendingUsesTheMatchingMessageEvidence() = runTest {
        val llm = FakeLlm(listOf(call("tap_text", """{"text":"发送","scrolls":0,"finish_if_verified":true}""")))
        val device = FakeDevice(listOf(chat(null, node("b3", text = body))), resultFor = {
            ActionResult.Failed("手势中断", mayHaveEffect = true)
        })
        val ui = FakeUi()
        assertTrue(loop(llm, device, ui).run("发送已有草稿", chat(body)).success)
        assertEquals(1, device.actions.size)
        assertEquals(1, ui.confirmRequests.size)
    }

    @Test fun anUnverifiedSendCannotUseTheFastFinishFlag() = runTest {
        val llm = FakeLlm(listOf(call("tap_text", """{"text":"发送","scrolls":0,"finish_if_verified":true}"""),done()))
        assertFalse(loop(llm,FakeDevice(listOf(chat(body)))).run("发送草稿",chat(body)).success)
        assertEquals(2,llm.turns.size)
    }

    @Test fun aVerifiedSequenceFinishesInOneRequest() = runTest {
        val llm = FakeLlm(listOf(call("perform_steps", """{"steps":[{"action":"type","target":{"view_id":"message","editable":true},"text":"$body","expect_text":"$body"}],"finish_if_verified":true,"summary":"草稿已填写"}""")))
        val outcome = loop(llm,FakeDevice(listOf(chat(),chat(body)))).run("填写草稿",chat())
        assertTrue(outcome.success)
        assertEquals(1,llm.turns.size)
    }

    @Test fun aSequenceCannotFastFinishWithoutItsFinalExpectationAndSummary() {
        for(json in listOf(
            """{"steps":[{"action":"back"}],"finish_if_verified":true,"summary":"返回了"}""",
            """{"steps":[{"action":"back","expect_text":"首页"}],"finish_if_verified":true}""",
        )) assertTrue(Tools.parse("perform_steps",Json.parseToJsonElement(json).jsonObject).isFailure)
    }

    @Test fun aHintOrADifferentWindowCannotVerifyTyping() {
        val before=chat()
        val tool=ToolCall.TypeText("a1",body)
        assertFalse(ExecutionEvidence.typed(before,screenOf(field.copy(hint=body),send),field,tool))
        assertFalse(ExecutionEvidence.typed(before.copy(windowId=1),chat(body).copy(windowId=2),field,tool))
        assertFalse(ExecutionEvidence.typed(before,chat(body).copy(packageName="other.app"),field,tool))
    }

    @Test fun unknownCheckboxStopsASequenceBeforeTouchingIt() = runTest {
        val unknown=screenOf(node("a1",role="checkbox",text="测试提醒",clickable=true).copy(checkable=true,checkedReliable=false))
        val llm=FakeLlm(listOf(call("perform_steps","""{"steps":[{"action":"tap","target":{"text":"测试提醒"}},{"action":"back"}]}"""),done()))
        val device=FakeDevice(listOf(unknown))
        loop(llm,device).run("检查提醒",unknown)
        assertTrue(device.actions.isEmpty())
        assertTrue(llm.turns[1].toolResult!!.text.contains("状态未知"))
    }

    @Test fun cancellationDuringASequenceDoesNotDispatchItsRemainingSteps() = runTest {
        val initial=screenOf(node("a1",text="联系人",clickable=true))
        val llm=FakeLlm(listOf(call("perform_steps","""{"steps":[{"action":"tap","target":{"text":"联系人"}},{"action":"back"}]}""")))
        val device=FakeDevice(listOf(initial),resultFor={throw CancellationException("用户停止")})
        assertFailsWith<CancellationException> { loop(llm,device).run("查看联系人",initial) }
        assertEquals(1,device.actions.size)
    }
}
