package dev.pocketagent.agent

/**
 * The last few things the agent did and how each one turned out.
 *
 * Small models forget what they already tried two steps ago and keep tapping the same dead spot. Showing the
 * recent trail (the way Mobilerun passes `action_history` and Mobile-Agent v2's Reflector reports the previous
 * outcome) gives the model something to reason about without re-reading the whole screen.
 *
 * Pure Kotlin: no android imports, so it can be tested on the JVM.
 */
internal class ActionLog(private val limit: Int = 5) {

    internal data class Entry(val label: String, val outcome: String)

    private val entries = ArrayDeque<Entry>()

    val size: Int get() = entries.size

    /** Records one step. Older entries fall off the front so the trail stays small. */
    fun add(label: String, outcome: String) {
        val clean = label.trim()
        if (clean.isEmpty()) return
        entries.addLast(Entry(clean, outcome.trim()))
        while (entries.size > limit) entries.removeFirst()
    }

    /** True once the same action has the same outcome this many times in the trail. */
    fun repeated(label: String, outcome: String, times: Int = 2): Boolean {
        val clean = label.trim()
        if (clean.isEmpty()) return false
        return entries.count { it.label == clean && it.outcome == outcome.trim() } >= times
    }

    /** The trail as prompt lines, or "" when nothing has been done yet. */
    fun block(): String {
        if (entries.isEmpty()) return ""
        return entries.joinToString("\n") { "- ${it.label} → ${it.outcome}" }
    }

    /** True when the trail contains a step that did not work, which is the only case worth showing the model. */
    fun hasTrouble(): Boolean = entries.any { it.outcome == "失败" || it.outcome == "屏幕没变" }

    fun clear() = entries.clear()
}
