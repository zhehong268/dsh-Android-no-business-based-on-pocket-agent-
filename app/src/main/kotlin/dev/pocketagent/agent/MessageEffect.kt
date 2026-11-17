package dev.pocketagent.agent

import dev.pocketagent.device.Bounds
import dev.pocketagent.device.Screen
import dev.pocketagent.device.ScreenNode
import dev.pocketagent.device.TextTargets

/** A Send tap is verified by a new message and a cleared composer, not by an arbitrary screen change. */
internal class MessageEffect private constructor(
    private val packageName: String,
    private val body: String,
    private val inputId: String?,
    private val inputBounds: Bounds?,
    private val beforeMessages: Int,
    private val beforeErrors: Int,
) {
    fun verified(screen: Screen): Boolean {
        if (body.isBlank() || screen.packageName != packageName || errors(screen) > beforeErrors) return false
        val fields = screen.all().filter { it.editable && !it.isPassword }.toList()
        val input = if (inputId != null) fields.filter { it.viewId == inputId }.singleOrNull()
            else fields.firstOrNull { it.bounds == inputBounds } ?: fields.filter { it.focused }.singleOrNull()
        return input != null && input.text.isNullOrBlank() && messages(screen, body) > beforeMessages
    }

    companion object {
        private val FAILURE = Regex("发送失败|未发送|重新发送|not sent|failed to send", RegexOption.IGNORE_CASE)
        fun isSend(target: ScreenNode?): Boolean = target?.editable == false && TextTargets.normalized(target.label().orEmpty()) in
            setOf("发送", "发送消息", "send", "send message")

        fun begin(screen: Screen, target: ScreenNode?, lastTyped: String?): MessageEffect? {
            if (!isSend(target)) return null
            val drafts = screen.all().filter { it.editable && !it.isPassword && !it.text.isNullOrBlank() }.toList()
            val input = drafts.filter { it.focused }.singleOrNull()
                ?: drafts.filter { lastTyped != null && it.text?.startsWith(lastTyped) == true }.singleOrNull()
                ?: drafts.singleOrNull()
            val body = input?.text ?: lastTyped.orEmpty()
            return MessageEffect(screen.packageName, body, input?.viewId, input?.bounds, messages(screen, body), errors(screen))
        }

        fun submission(screen: Screen, field: ScreenNode, body: String): MessageEffect? {
            val nearbySend = screen.all().any { button ->
                isSend(button) && kotlin.math.abs(button.bounds.top - field.bounds.top) <=
                    3 * maxOf(1, field.bounds.bottom - field.bounds.top, button.bounds.bottom - button.bounds.top)
            }
            if (!nearbySend || field.isPassword) return null
            return MessageEffect(screen.packageName,body,field.viewId,field.bounds,messages(screen,body),errors(screen))
        }

        private fun messages(screen: Screen, body: String): Int {
            val text = TextTargets.normalized(body)
            if (text.isBlank()) return 0
            val matches = screen.all().filter { !it.editable && !it.isPassword &&
                TextTargets.normalized(it.label().orEmpty()).contains(text) }.toList()
            // A container and its text child describe a single bubble.
            fun contains(n: ScreenNode, id: String): Boolean = n.children.any { it.id == id || contains(it, id) }
            return matches.count { n -> matches.none { it.id != n.id && contains(n, it.id) } }
        }

        private fun errors(screen: Screen): Int = screen.all().count {
            !it.editable && !it.isPassword && FAILURE.containsMatchIn(it.label().orEmpty())
        }
    }
}
