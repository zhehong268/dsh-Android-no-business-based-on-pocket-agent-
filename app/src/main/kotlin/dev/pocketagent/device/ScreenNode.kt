package dev.pocketagent.device

/** A rectangle in screen pixels. Half-open: the left and top edges are inside, the right and bottom edges are not. */
data class Bounds(val left: Int, val top: Int, val right: Int, val bottom: Int) {
    fun contains(x: Int, y: Int): Boolean = x >= left && x < right && y >= top && y < bottom
    fun intersects(other: Bounds): Boolean =
        maxOf(left, other.left) < minOf(right, other.right) && maxOf(top, other.top) < minOf(bottom, other.bottom)
}

/** Only system-reported touch regions, never keyboard text or key labels. */
data class KeyboardRegion(val visible: Boolean = false, val bounds: List<Bounds> = emptyList())

/** One element of the screen. [id] is valid only for the observation that produced it. */
data class ScreenNode(
    val id: String,
    val role: String,
    val text: String?,
    val description: String?,
    val clickable: Boolean = false,
    val editable: Boolean = false,
    val scrollable: Boolean = false,
    val isPassword: Boolean = false,
    val packageName: String,
    val bounds: Bounds,
    val children: List<ScreenNode> = emptyList(),
    val checkable: Boolean = false,
    val checkedReliable: Boolean = true,
    val checked: Boolean = false,
    val selected: Boolean = false,
    val enabled: Boolean = true,
    val focused: Boolean = false,
    val stateDescription: String? = null,
    val viewId: String? = null,
    val hint: String? = null,
    val className: String? = null,
    val fontFamilies: List<String> = emptyList(),
) {
    /** Display label; [hint] is a fallback, never the actual value stored in [text]. */
    fun label(): String? = listOf(text, description, hint).firstNotNullOfOrNull { it?.trim()?.takeIf(String::isNotEmpty) }
}

/** One reading of the screen. [ownWindowBounds] are dsh's own windows, kept for the safety gate. */
data class Screen(
    val observation: Int,
    val packageName: String,
    val appLabel: String?,
    val root: ScreenNode,
    val ownWindowBounds: List<Bounds> = emptyList(),
    val keyboardVisible: Boolean = false,
    val windowId: Int? = null,
    val environment: String? = null,
    val keyboardBounds: List<Bounds> = emptyList(),
) {
    fun keyboardCovers(x: Int, y: Int): Boolean = keyboardBounds.any { it.contains(x, y) }
    /** Every node, depth-first, parents before children. */
    fun all(): Sequence<ScreenNode> = sequence {
        val pending = ArrayDeque<ScreenNode>()
        pending.addLast(root)
        while (pending.isNotEmpty()) {
            val node = pending.removeLast()
            yield(node)
            for (i in node.children.indices.reversed()) pending.addLast(node.children[i])
        }
    }

    fun find(id: String): ScreenNode? = all().firstOrNull { it.id == id }

    fun parentOf(node: ScreenNode): ScreenNode? = all().firstOrNull { parent -> parent.children.any { it.id == node.id } }

    /** The last node in depth-first order whose bounds contain the point, which is the most deeply nested one. */
    fun deepestAt(x: Int, y: Int): ScreenNode? = all().lastOrNull { it.bounds.contains(x, y) }
}

object NodeIds {
    /** Prefixes grow after z (aa, ab, ...), so a long task cannot reuse an old id. */
    fun idFor(observation: Int, index: Int): String {
        require(observation >= 0 && index >= 0)
        var n = observation.toLong() + 1
        var prefix = ""
        while (n > 0) { n--; prefix = ('a' + (n % 26).toInt()) + prefix; n /= 26 }
        return "$prefix$index"
    }
}
