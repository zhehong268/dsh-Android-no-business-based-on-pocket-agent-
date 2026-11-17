package dev.pocketagent.agent

import dev.pocketagent.fakes.FakeLlm
import dev.pocketagent.llm.*
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import org.junit.Test
import kotlin.test.*

class RecoveryAdvisorTest {
    private fun advice(name:String="suggest_recovery")=LlmResult.Call("r",name,buildJsonObject { put("next","返回后用顶部搜索");put("verify","核对联系人完整名字") })
    @Test fun independentReviewIsBoundedAndCountsItsUsage()=runTest {
        val llm=FakeLlm(listOf(advice(),advice()),usage=TokenUsage(10,3))
        val advisor=RecoveryAdvisor(llm)
        assertNotNull(advisor.review("发微信","页一","点击无效","受阻"))
        assertNull(advisor.review("发微信","页一","点击无效","受阻"))
        assertNotNull(advisor.review("发微信","页二","来回切换","循环"))
        assertNull(advisor.review("发微信","页三","循环","循环"))
        assertEquals(2,llm.sessions);assertEquals(TokenUsage(20,6),advisor.usage)
    }
    @Test fun aReviewerCannotExecuteItsOwnDeviceTool()=runTest {
        assertNull(RecoveryAdvisor(FakeLlm(listOf(advice("tap")))).review("任务","页","轨迹","受阻"))
    }
    @Test fun missingReviewerIsANormalFallback()=runTest {
        assertNull(RecoveryAdvisor(null).review("任务","页","轨迹","受阻"))
    }
    @Test fun repeatedNavigationIsRecognizedEvenWhenPagesChange() {
        val tracker=RecoveryTracker()
        assertNull(tracker.record("A","B","open",false));assertNull(tracker.record("B","A","back",false))
        assertNotNull(tracker.record("A","B","open",false))
    }
    @Test fun separateSuccessfulTransitionsDoNotAccumulateFailures() {
        val tracker=RecoveryTracker()
        assertNull(tracker.record("A","B","tap",true));assertNull(tracker.record("B","C","tap",false))
        assertNull(tracker.record("C","D","tap",true));assertNotNull(tracker.record("D","E","tap",true))
    }
    @Test fun memoryPrefersRelevantRoutesOverRecentUnrelatedTasks() {
        val store=ExperienceStore(MemoryValues())
        store.remember(Experience("app","发微信给张三","旧的微信路线","完成"))
        repeat(4) { store.remember(Experience("app","查询天气$it","天气路线$it","完成")) }
        val context=store.context("app","发微信给李四")
        assertTrue(context.contains("微信路线"));assertFalse(context.contains("天气路线"))
        assertEquals("",store.context("app","设置闹钟"))
    }

    @Test fun cancellationDoesNotTurnAnInterruptedReviewIntoAdvice()=runTest {
        val entered=CompletableDeferred<Unit>()
        val client=object:LlmClient {
            override val supportsImages=false
            override fun newSession(systemPrompt:String,tools:List<ToolDefinition>)=object:LlmSession {
                override val usage=TokenUsage()
                override suspend fun send(turn:Turn):LlmResult { entered.complete(Unit);awaitCancellation() }
            }
        }
        var returned=false
        val job=launch { RecoveryAdvisor(client).review("任务","页","轨迹","受阻");returned=true }
        entered.await();job.cancelAndJoin();assertFalse(returned)
    }
}
