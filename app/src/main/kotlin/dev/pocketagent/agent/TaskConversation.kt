package dev.pocketagent.agent

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first

/** Only the visible conversation of the current phone task. */
class TaskConversation(private val onReply: suspend () -> Unit = {}) {
    data class Line(val fromUser: Boolean, val text: String)

    private val editing = MutableStateFlow(false)
    private val lines = ArrayDeque<Line>()
    private val pending = ArrayDeque<String>()

    fun setEditing(value: Boolean) { editing.value = value }
    suspend fun awaitClosed() { editing.first { !it } }

    @Synchronized fun messages() = lines.toList()
    @Synchronized fun submit(text: String): Boolean {
        val message = text.trim()
        if (message.isEmpty() || message.length > 1000 || pending.size >= 4) return false
        pending.addLast(message)
        append(Line(true, message))
        return true
    }
    @Synchronized fun hasPending() = pending.isNotEmpty()
    @Synchronized fun drain(): List<String> = pending.toList().also { pending.clear() }
    suspend fun reply(text: String) {
        synchronized(this) { append(Line(false, text.take(1500))) }
        onReply()
    }

    private fun append(line: Line) {
        lines.addLast(line)
        if (lines.size > 20) lines.removeFirst()
    }
}
