package dev.pocketagent.device

/**
 * Turns a screen into the text the model sees. Everything on the screen is untrusted, so this class
 * keeps it from escaping its wrapper or forging node lines, and leaves out password fields and
 * dsh's own windows.
 */
class ScreenSerializer(private val ownPackage: String, private val maxChars: Int = 24_000, private val geometry: Boolean = false) {

    fun serialize(screen: Screen): String {
        val lines = ArrayList<Row>()
        collect(screen.root, depth = 0, ancestorLabel = null, out = lines)

        val out = StringBuilder()
        out.append("<screen_data app=\"").append(cleanUntrusted(screen.packageName, MAX_LABEL))
            .append("\" label=\"").append(cleanUntrusted(screen.appLabel.orEmpty(), MAX_LABEL)).append("\">\n")
        if (screen.keyboardVisible) {
            out.append("[键盘已展开；不要点击键盘，输入使用应用正文框与粘贴。")
            if (screen.keyboardBounds.isNotEmpty()) {
                out.append("键盘位置（屏幕像素）：")
                out.append(screen.keyboardBounds.take(6).joinToString(" ") { "(${it.left},${it.top},${it.right},${it.bottom})" })
            }
            out.append("]\n")
        }
        screen.environment?.let { out.append("<display_context>").append(cleanUntrusted(it,2000)).append("</display_context>\n") }
        val budget = maxChars - out.length - 60
        val selected = if (lines.sumOf { it.text.length + 1 } <= budget) lines.indices.toSet() else {
            var remaining = budget
            lines.indices.sortedWith(compareBy({ lines[it].priority }, { it })).filter { index ->
                val size = lines[index].text.length + 1
                if (size <= remaining) { remaining -= size; true } else false
            }.toSet()
        }
        for (i in lines.indices) if (i in selected) out.append(lines[i].text).append('\n')
        if (selected.size < lines.size) out.append("[truncated: ").append(lines.size - selected.size).append(" more nodes]\n")
        return out.append("</screen_data>").toString()
    }

    private data class Row(val text: String, val priority: Int)

    /** Original Unicode strings survive decorative fonts; no visual OCR is involved. */
    fun source(screen: Screen): String = buildString {
        append("<screen_data app=\"").append(cleanUntrusted(screen.packageName,MAX_LABEL)).append("\" source=\"runtime_accessibility\">\n")
        var omitted=0
        fun visit(node:ScreenNode) {
            if(node.isPassword || node.packageName==ownPackage)return
            val row=buildString {
                append('[').append(node.id).append("] class=\"").append(cleanUntrusted(node.className.orEmpty(),160))
                append("\" view_id=\"").append(cleanUntrusted(node.viewId.orEmpty(),240)).append("\" editable=").append(node.editable)
                append(" bounds=").append(node.bounds)
                for((key,value) in listOf("text" to node.text,"description" to node.description,"hint" to node.hint))
                    if(value!=null)append(' ').append(key).append("=\"").append(cleanUntrustedBlock(value,4000)).append('"')
                if (node.fontFamilies.isNotEmpty()) append(" font_family=\"")
                    .append(cleanUntrusted(node.fontFamilies.joinToString(", "), 100)).append('"')
                append('\n')
            }
            if(length+row.length<48_000)append(row) else omitted++
            node.children.forEach(::visit)
        }
        visit(screen.root)
        if(omitted>0)append("[truncated: ").append(omitted).append(" nodes]\n")
        append("</screen_data>")
    }

    /** Depth-first. Unkept nodes are skipped and their children promoted one level up. */
    private fun collect(node: ScreenNode, depth: Int, ancestorLabel: String?, out: MutableList<Row>) {
        if (node.isPassword || node.packageName == ownPackage) return
        val label = node.label()
        val flags = buildList {
            if (node.clickable) add("clickable")
            if (node.editable) add("editable")
            if (node.scrollable) add("scrollable")
        }.toMutableList()
        if (node.checkable) flags += if (node.checkedReliable) "checked=${node.checked}" else "checked=unknown"
        if (node.selected) flags += "selected=true"
        if (!node.enabled) flags += "enabled=false"
        if (node.focused) flags += "focused=true"
        if (node.editable && node.text.isNullOrEmpty()) flags += "value_empty=true"
        val kept = label != null || flags.isNotEmpty()
        if (kept) out += Row(line(node, label, flags, borrowed = if (label == null) ancestorLabel else null, depth), when {
            node.editable || node.focused -> 0
            node.enabled && (node.role == "button" || node.checkable) -> 1
            node.enabled && node.clickable -> 2
            depth <= 1 -> 3
            else -> 4
        })
        for (child in node.children) {
            collect(child, if (kept) depth + 1 else depth, label ?: ancestorLabel, out)
        }
    }

    private fun line(node: ScreenNode, label: String?, flags: List<String>, borrowed: String?, depth: Int): String =
        buildString {
            repeat(depth) { append("  ") }
            append('[').append(node.id).append("] ").append(cleanUntrusted(node.role, MAX_ROLE))
            if (label != null) append(" \"").append(cleanUntrusted(label, MAX_LABEL)).append('"')
            flags.forEach { append(' ').append(it) }
            node.stateDescription?.let { append(" state=\"").append(cleanUntrusted(it, 120)).append('"') }
            node.viewId?.let { append(" view_id=\"").append(cleanUntrusted(it, 160)).append('"') }
            if (geometry) append(" bounds=(${node.bounds.left},${node.bounds.top},${node.bounds.right},${node.bounds.bottom})")
            if (borrowed != null) append(" in \"").append(cleanUntrusted(borrowed, MAX_BORROWED)).append('"')
        }

    private companion object {
        const val MAX_LABEL = 500
        const val MAX_ROLE = 20
        const val MAX_BORROWED = 60
    }
}
