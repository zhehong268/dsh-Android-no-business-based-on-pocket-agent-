package dev.pocketagent.device

/** Uses runtime text and geometry; the rendered typeface is never a contact identifier. */
object ChatTargets {
    data class Composer(val node: ScreenNode, val score: Int, val reasons: List<String>)
    data class Contact(val target: ScreenNode, val name: String, val context: String)
    private val SEARCH = Regex("搜索|查找|search|find", RegexOption.IGNORE_CASE)
    private val MESSAGE = Regex("消息|正文|聊天|message|compose", RegexOption.IGNORE_CASE)
    private val SEND = setOf("发送", "发送消息", "send", "send message")
    private val VOICE = setOf("切换到键盘", "切换到文字输入", "键盘", "switch to keyboard", "keyboard")

    fun composers(screen: Screen): List<Composer> {
        val height = (screen.root.bounds.bottom - screen.root.bounds.top).coerceAtLeast(1)
        val controls = screen.all().filter { !it.isPassword && it.enabled }.toList()
        val send = controls.filter { !it.editable && TextTargets.normalized(it.label().orEmpty()) in SEND }
        return controls.filter { it.editable && it.bounds.bottom > it.bounds.top }.mapNotNull { n ->
            val label = listOfNotNull(n.hint, n.description, n.viewId).joinToString(" ")
            if (SEARCH.containsMatchIn(label)) return@mapNotNull null
            val reasons = mutableListOf<String>()
            var score = 0
            if (n.bounds.top >= screen.root.bounds.top + height * 0.45) { score += 4; reasons += "位于内容区下部" }
            if (MESSAGE.containsMatchIn(label)) { score += 4; reasons += "原始提示或资源名指向消息正文" }
            if (send.any { kotlin.math.abs(it.bounds.top - n.bounds.top) <= maxOf(160, n.bounds.bottom - n.bounds.top) }) {
                score += 5; reasons += "与发送控件同一行"
            }
            if (n.focused) { score += 1; reasons += "当前输入焦点" }
            Composer(n, score, reasons)
        }.sortedByDescending { it.score }
    }

    fun composer(screen: Screen): ScreenNode? {
        val candidates = composers(screen)
        val first = candidates.firstOrNull() ?: return null
        return first.node.takeIf { first.score >= 4 && (candidates.size == 1 || first.score - candidates[1].score >= 3) }
    }

    fun keyboardSwitch(screen: Screen): ScreenNode? = screen.all().filter {
        !it.isPassword && it.enabled && !it.editable && isControl(it) && TextTargets.normalized(it.label().orEmpty()) in VOICE
    }.toList().singleOrNull()

    fun searchField(screen: Screen): ScreenNode? = screen.all().filter {
        it.editable && it.enabled && !it.isPassword && SEARCH.containsMatchIn(listOfNotNull(it.hint,it.description,it.viewId).joinToString(" "))
    }.toList().singleOrNull()

    fun navigation(screen:Screen,text:String):ScreenNode? = TextTargets.find(screen,text).filter { n ->
        if(TextTargets.normalized(n.label().orEmpty())!=TextTargets.normalized(text))return@filter false
        var ancestor:ScreenNode?=n
        while(ancestor!=null) {
            if(ancestor.clickable && ancestor.bounds.bottom-ancestor.bounds.top<(screen.root.bounds.bottom-screen.root.bounds.top)*0.35)return@filter true
            ancestor=screen.parentOf(ancestor)
        }
        false
    }.singleOrNull()

    /** Titles must be above the conversation, not a message bubble or a result row. */
    fun title(screen: Screen, expected: String): ScreenNode? {
        val top = screen.root.bounds.top
        val height = (screen.root.bounds.bottom - top).coerceAtLeast(1)
        val headers=screen.all().filter { n ->
            !n.isPassword && !n.editable && n.children.isEmpty() &&
                !n.text.isNullOrBlank() && n.role !in setOf("button","image") &&
                n.bounds.top < top + height * 0.22 && n.bounds.bottom <= top + height * 0.30
        }.toList()
        val first=headers.minOfOrNull { it.bounds.top } ?: return null
        return headers.filter { TextTargets.normalized(it.text.orEmpty())==TextTargets.normalized(expected) &&
            it.bounds.top-first<=maxOf(24,(height*0.03).toInt()) }.singleOrNull()
    }

    fun isConversation(screen: Screen) = composer(screen) != null || keyboardSwitch(screen) != null ||
        screen.all().any { !it.editable && isControl(it) && TextTargets.normalized(it.label().orEmpty()) in SEND+setOf("按住说话","hold to talk","表情","更多功能") }

    private fun isControl(n:ScreenNode)=n.role in setOf("button","image") || (n.text.isNullOrBlank() && !n.description.isNullOrBlank())

    fun contacts(screen: Screen, name: String, hint: String = ""): List<Contact> {
        if (isConversation(screen)) return emptyList()
        val wanted = TextTargets.normalized(name)
        return screen.all().filter { !it.isPassword && !it.editable && it.enabled && TextTargets.normalized(it.text.orEmpty()) == wanted }
            .mapNotNull { label ->
                var row = label
                while (!row.clickable) row = screen.parentOf(row) ?: return@mapNotNull null
                val height = screen.root.bounds.bottom - screen.root.bounds.top
                if (row.bounds.bottom - row.bounds.top > height * 0.35) return@mapNotNull null
                fun labels(n: ScreenNode): List<String> = if(n.isPassword) emptyList() else listOfNotNull(n.text,n.description) + n.children.flatMap(::labels)
                val context = labels(row).distinct().joinToString("；")
                if (hint.isNotBlank() && !TextTargets.normalized(context).contains(TextTargets.normalized(hint))) return@mapNotNull null
                Contact(row, label.text.orEmpty(), context)
            }.distinctBy { it.target.id }.toList()
    }

    fun summary(screen: Screen): String {
        val candidates = composers(screen)
        return buildString {
            append("输入识别依据：运行时控件原文与位置，不按字体字形猜测。\n")
            if (candidates.isEmpty()) append("未发现可输入的聊天正文框；检查语音模式或读取控件原文、放大底部。\n")
            candidates.take(6).forEach { append("[").append(it.node.id).append("] 分数=").append(it.score).append(" ").append(it.reasons.joinToString("，")).append(" bounds=").append(it.node.bounds).append('\n') }
            if (candidates.size > 1 && composer(screen) == null) append("存在多个接近的候选，尚未选定输入框。\n")
            keyboardSwitch(screen)?.let { append("文字输入切换控件：[").append(it.id).append("]\n") }
        }
    }
}
