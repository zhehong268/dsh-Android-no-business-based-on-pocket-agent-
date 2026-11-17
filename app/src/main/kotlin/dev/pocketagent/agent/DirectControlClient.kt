package dev.pocketagent.agent

import dev.pocketagent.llm.*
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withTimeoutOrNull

/** A computer chooses the actions, but the ordinary AgentLoop remains the only executor and safety gate. */
class DirectControlClient : LlmClient {
    override val supportsImages = true
    private data class Request(val call: LlmResult.Call, val reply: CompletableDeferred<Turn>)
    private val requests = Channel<Request>(Channel.RENDEZVOUS)
    private val lock = Mutex()
    private var active: Request? = null
    private var stopped = false
    val ready = CompletableDeferred<Turn>()
    var latest: Turn? = null
        private set

    override fun newSession(systemPrompt: String, tools: List<ToolDefinition>): LlmSession = object : LlmSession {
        override val usage = TokenUsage()
        override suspend fun send(turn: Turn): LlmResult {
            latest = turn
            if (!ready.isCompleted) ready.complete(turn)
            active?.reply?.complete(turn)
            active = null
            val next = withTimeoutOrNull(120_000) { requests.receiveCatching().getOrNull() }
                ?: return LlmResult.Call("control_end","done",kotlinx.serialization.json.JsonObject(mapOf("summary" to kotlinx.serialization.json.JsonPrimitive("电脑控制已结束或超时。"),"success" to kotlinx.serialization.json.JsonPrimitive(false))))
            active = next
            return next.call
        }
    }

    suspend fun execute(call: LlmResult.Call): Turn {
        if (!lock.tryLock()) return Turn("",ToolResult(call.callId,"已有电脑操作在执行。",true))
        try {
            if (stopped) return Turn("",ToolResult(call.callId,"控制会话已结束。",true))
            val reply=CompletableDeferred<Turn>()
            val result=withTimeoutOrNull(130_000) {
                requests.send(Request(call,reply))
                reply.await()
            }
            return result ?: Turn("",ToolResult(call.callId,"操作超时，结果不明；请先核对屏幕，不要重试发送。",true))
        } catch (_: kotlinx.coroutines.channels.ClosedSendChannelException) {
            return Turn("",ToolResult(call.callId,"控制会话已结束。",true))
        } finally { lock.unlock() }
    }

    fun close(reason: String) {
        stopped=true
        val turn=Turn("",ToolResult(active?.call?.callId ?: "control_end",reason,true))
        active?.reply?.complete(turn)
        active=null
        if (!ready.isCompleted) ready.complete(turn)
        requests.close()
    }
}
