package dev.pocketagent.device

/** Exact first, and ambiguous matches are returned to the caller instead of silently picking a person. */
object TextTargets {
    fun find(screen: Screen, text: String, container: String? = null): List<ScreenNode> {
        val root = if (container == null) screen.root else screen.find(container) ?: return emptyList()
        fun walk(n: ScreenNode): Sequence<ScreenNode> = sequence { yield(n); n.children.forEach { yieldAll(walk(it)) } }
        val candidates = walk(root).filter { !it.isPassword && it.enabled && it.label()?.contains(text, true) == true }.toList()
        val exact = candidates.filter { it.label()?.equals(text, true) == true }
        val matches = exact.ifEmpty { candidates }
        // A labelled child and its clickable row represent one target, not two choices.
        return matches.filter { n -> matches.none { other -> other != n && contains(other, n) && other.clickable } }
    }
    private fun contains(parent: ScreenNode, node: ScreenNode): Boolean =
        parent.children.any { it.id == node.id || contains(it, node) }
}
