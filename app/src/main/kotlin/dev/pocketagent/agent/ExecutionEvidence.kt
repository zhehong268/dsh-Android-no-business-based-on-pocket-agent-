package dev.pocketagent.agent

import dev.pocketagent.device.Screen
import dev.pocketagent.device.ScreenNode

/** Compare what changed, rather than trusting the accessibility dispatch return value. */
object ExecutionEvidence {
    fun fingerprint(screen: Screen): String = buildString {
        append(screen.packageName).append('|').append(screen.keyboardVisible)
        screen.all().filterNot { it.isPassword }.forEach {
            append('|').append(it.role).append(':').append(it.label()).append(':').append(it.bounds)
            append(':').append(it.checkable).append(':').append(it.checked).append(':').append(it.selected)
            append(':').append(it.enabled).append(':').append(it.focused).append(':').append(it.stateDescription)
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
