package dev.pocketagent.chat

import dev.pocketagent.agent.MemoryValues
import dev.pocketagent.fakes.FakeLlm
import dev.pocketagent.llm.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import kotlinx.serialization.json.buildJsonObject
import org.junit.Test
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class ChatControllerTest {
    @Test fun followUpUsesOneNativeSessionAndNeverExposesPhoneTools()=runTest {
        val llm=FakeLlm(listOf(LlmResult.NoCall("42"),LlmResult.NoCall("40")))
        val chat=ChatController(backgroundScope,{llm},ChatStore(MemoryValues()))
        assertTrue(chat.send("17+25"));runCurrent()
        assertTrue(chat.send("减去2"));runCurrent()
        assertEquals(1,llm.sessions);assertTrue(llm.tools.isEmpty())
        assertEquals(listOf("17+25","42","减去2","40"),chat.state.value.messages.map { it.text })
        assertEquals("减去2",llm.turns.last().text)
    }
    @Test fun blankOrOversizedQuestionsDoNotCreateARequest()=runTest {
        var made=0;val chat=ChatController(backgroundScope,{made++;error("unused")},ChatStore(MemoryValues()))
        assertFalse(chat.send("  "));assertFalse(chat.send("a".repeat(8001)));assertEquals(0,made)
    }
    @Test fun aBusyConversationRejectsADoubleSendAndCanBeStopped()=runTest {
        val client=object:LlmClient {
            override val supportsImages=false
            override fun newSession(systemPrompt:String,tools:List<dev.pocketagent.agent.ToolDefinition>)=object:LlmSession {
                override val usage=TokenUsage()
                override suspend fun send(turn:Turn):LlmResult { awaitCancellation() }
            }
        }
        val chat=ChatController(backgroundScope,{client},ChatStore(MemoryValues()))
        assertTrue(chat.send("问题"));runCurrent();assertFalse(chat.send("再点一次"))
        chat.stop();runCurrent()
        assertFalse(chat.state.value.busy);assertTrue(chat.state.value.error!!.contains("已停止"))
        assertEquals(1,chat.state.value.messages.size)
    }
    @Test fun aFailedAnswerCanBeRetriedWithoutDuplicatingTheQuestion()=runTest {
        var attempts=0
        val chat=ChatController(backgroundScope,{FakeLlm(listOf(if(attempts++==0)LlmResult.Failed(FailureKind.NETWORK,"网络不可用") else LlmResult.NoCall("答案")))},ChatStore(MemoryValues()))
        chat.send("原问题");runCurrent();assertEquals("网络不可用",chat.state.value.error)
        assertTrue(chat.retry());runCurrent()
        assertEquals(listOf("原问题","答案"),chat.state.value.messages.map { it.text })
    }
    @Test fun aFailedQuestionCanBeEditedBeforeResending()=runTest {
        var attempts=0
        val chat=ChatController(backgroundScope,{FakeLlm(listOf(if(attempts++==0)LlmResult.Failed(FailureKind.OTHER,"失败") else LlmResult.NoCall("答案")))},ChatStore(MemoryValues()))
        chat.send("旧问题");runCurrent();chat.send("修改后的问题");runCurrent()
        assertEquals(listOf("修改后的问题","答案"),chat.state.value.messages.map { it.text })
    }
    @Test fun restartRestoresVisibleContextAndAnInterruptedQuestionCanRetry()=runTest {
        val values=MemoryValues();val store=ChatStore(values)
        store.save(listOf(ChatMessage("user","原问题"),ChatMessage("assistant","旧答案"),ChatMessage("user","追问")))
        val llm=FakeLlm(listOf(LlmResult.NoCall("新答案")))
        val chat=ChatController(backgroundScope,{llm},ChatStore(values))
        assertNotNull(chat.state.value.error);assertTrue(chat.retry());runCurrent()
        assertTrue(llm.turns.single().text.contains("旧答案"));assertTrue(llm.turns.single().text.endsWith("追问"))
        assertEquals(4,store.load().size)
    }
    @Test fun clearingCancelsAnInFlightReplyAndLeavesNoHistory()=runTest {
        val waiting=CompletableDeferred<LlmResult>()
        val client=object:LlmClient {
            override val supportsImages=false
            override fun newSession(systemPrompt:String,tools:List<dev.pocketagent.agent.ToolDefinition>)=object:LlmSession {
                override val usage=TokenUsage()
                override suspend fun send(turn:Turn)=withContext(NonCancellable){waiting.await()}
            }
        }
        val store=ChatStore(MemoryValues());val chat=ChatController(backgroundScope,{client},store)
        chat.send("问题");runCurrent();chat.clear();waiting.complete(LlmResult.NoCall("迟到的回答"));runCurrent()
        assertTrue(chat.state.value.messages.isEmpty());assertTrue(store.load().isEmpty())
    }
    @Test fun unsolicitedToolCallsAreRejectedWithoutExecution()=runTest {
        val llm=FakeLlm(listOf(LlmResult.Call("bad","tap",buildJsonObject{})))
        val chat=ChatController(backgroundScope,{llm},ChatStore(MemoryValues()))
        chat.send("解释一下");runCurrent()
        assertTrue(chat.state.value.error!!.contains("不会执行手机操作"));assertEquals(1,chat.state.value.messages.size)
    }
    @Test fun rawSetupExceptionsAreNotShownInTheConversation()=runTest {
        val chat=ChatController(backgroundScope,{throw IllegalArgumentException("secret-config-value")},ChatStore(MemoryValues()))
        chat.send("问题");runCurrent()
        assertFalse(chat.state.value.error!!.contains("secret-config-value"));assertFalse(chat.state.value.busy)
    }
    @Test fun aLongReasoningHistoryStartsAFreshSessionWithVisibleContext()=runTest {
        val made=mutableListOf<FakeLlm>()
        val chat=ChatController(backgroundScope,{FakeLlm(listOf(LlmResult.NoCall("答案")),usage=TokenUsage(outputTokens=50_000)).also { made+=it }},ChatStore(MemoryValues()))
        chat.send("问题一");runCurrent();chat.send("追问");runCurrent()
        assertEquals(2,made.size);assertTrue(made.last().turns.single().text.contains("问题一"))
        assertTrue(made.last().turns.single().text.contains("答案"))
    }
    @Test fun switchingModelsResetsTheConnectionAndKeepsVisibleHistory()=runTest {
        var made=0
        val chat=ChatController(backgroundScope,{made++;FakeLlm(listOf(LlmResult.NoCall("答案")))},ChatStore(MemoryValues()))
        chat.send("问题一");runCurrent();chat.resetConnection();chat.send("追问");runCurrent()
        assertEquals(2,made);assertEquals(4,chat.state.value.messages.size)
    }
}
