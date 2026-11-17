package dev.pocketagent.device

/** Exact first, and ambiguous matches are returned to the caller instead of silently picking a person. */
object TextTargets {
    fun find(screen: Screen, text: String = "", container: String? = null,
             role: String? = null, editable: Boolean? = null, viewId: String? = null): List<ScreenNode> {
        val root = if (container == null) screen.root else screen.find(container) ?: return emptyList()
        fun walk(n: ScreenNode): Sequence<ScreenNode> = sequence {
            if (n.isPassword) return@sequence
            if (n.enabled) yield(n)
            n.children.forEach { yieldAll(walk(it)) }
        }
        val wanted = normalized(text)
        val candidates = walk(root).filter {
            (role == null || it.role == role) && (editable == null || it.editable == editable) &&
                (viewId == null || it.viewId == viewId || it.viewId?.substringAfterLast('/') == viewId) &&
                (wanted.isEmpty() || normalized(it.label().orEmpty()).contains(wanted))
        }.toList()
        val exact = if (wanted.isBlank()) emptyList() else candidates.filter { normalized(it.label().orEmpty()) == wanted }
        val matches = exact.ifEmpty { candidates }
        if (role != null || editable != null || viewId != null) return matches.sortedByDescending { it.focused }
        // A labelled child and its clickable row represent one target, not two choices.
        return matches.filter { n -> matches.none { other -> other != n && contains(other, n) && other.clickable } }
    }
    private fun contains(parent: ScreenNode, node: ScreenNode): Boolean =
        parent.children.any { it.id == node.id || contains(it, node) }

    internal fun normalized(text: String): String = java.text.Normalizer.normalize(text, java.text.Normalizer.Form.NFKC)
        .replace(WHITESPACE, " ").trim().lowercase(java.util.Locale.ROOT)
    private val WHITESPACE = Regex("[\\s\\u00a0\\u3000]+")
}
