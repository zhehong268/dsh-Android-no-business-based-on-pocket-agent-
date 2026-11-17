package dev.pocketagent.agent

import dev.pocketagent.device.Screen
import dev.pocketagent.device.ScreenNode
import dev.pocketagent.device.TypedText

/** Compare what changed, rather than trusting the accessibility dispatch return value. */
object ExecutionEvidence {
    fun typed(before:Screen,after:Screen,target:ScreenNode?,call:ToolCall.TypeText?):Boolean {
        if(target==null || call==null || call.submit || before.packageName!=after.packageName)return false
        if(before.windowId!=null && before.windowId!=after.windowId)return false
        val fields=after.all().filter { it.editable && it.enabled && !it.isPassword }.toList()
        val candidates=if(target.viewId!=null)fields.filter { it.viewId==target.viewId }
            else fields.filter { it.bounds==target.bounds }.ifEmpty { fields.filter { it.focused }.ifEmpty { fields } }
        val field=candidates.singleOrNull()?:return false
        val expected=when(call.mode){"append"->target.text.orEmpty()+call.text;"clear"->"";else->call.text}
        return if(expected.isEmpty())field.text.isNullOrBlank() else TypedText.matches(expected,field.text)
    }
    fun fingerprint(screen: Screen): String = buildString {
        append(screen.packageName).append('|').append(screen.keyboardVisible)
        screen.all().filterNot { it.isPassword }.forEach {
            append('|').append(it.role).append(':').append(it.label()).append(':').append(it.bounds)
            append(':').append(it.checkable).append(':').append(it.checked).append(':').append(it.selected)
            append(':').append(it.enabled).append(':').append(it.focused).append(':').append(it.stateDescription)
            append(':').append(it.checkedReliable).append(':').append(it.viewId)
        }
    }
    fun inspect(before: Screen, after: Screen, target: ScreenNode?): String? {
        if (target == null || !target.checkable || !target.checkedReliable) return null
        val candidates = after.all().filter { it.checkable && it.checkedReliable && !it.isPassword &&
            (if (target.viewId != null) it.viewId == target.viewId else it.label() == target.label() && it.bounds == target.bounds) }.toList()
        val next = candidates.singleOrNull() ?: return null
        return if (target.checked != next.checked) "已核验：勾选状态变为 ${next.checked}。" else null
    }
}
