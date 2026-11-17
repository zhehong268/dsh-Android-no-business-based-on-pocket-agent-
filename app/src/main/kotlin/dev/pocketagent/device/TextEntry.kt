package dev.pocketagent.device

/** Text goes through a clipboard/editor transaction, never simulated keyboard characters. */
interface TextEditor {
    fun value(): String?
    fun sameTarget(): Boolean
    fun focus(): Boolean
    fun selectAll(length: Int): Boolean
    /** True means dispatched, not that the platform reported success. False means no write was attempted. */
    fun paste(text: String): Boolean
    fun setText(text: String): Boolean
    fun commit(text: String, previousLength: Int): Boolean = false
}

object TextEntry {
    fun isPasswordType(inputType:Int):Boolean = when(inputType and 0xF) {
        1 -> inputType and 0xFF0 in setOf(0x80,0x90,0xE0)
        2 -> inputType and 0xFF0 == 0x10
        else -> false
    }
    suspend fun enter(editor: TextEditor, text: String, mode: String, pause: suspend () -> Unit): ActionResult {
        if (!editor.sameTarget()) return ActionResult.Failed("输入目标已变化，请重新读取屏幕。")
        val before = editor.value().orEmpty()
        val desired = when(mode) { "clear" -> ""; "append" -> before + text; else -> text }
        if (TypedText.matches(desired,before)) return ActionResult.Ok
        editor.focus()
        if (!editor.sameTarget()) return ActionResult.Failed("聚焦后输入目标已变化，未写入文字。")
        val selected = before.isEmpty() || editor.selectAll(before.length)
        var dispatched = selected && editor.paste(desired)
        if(!dispatched)dispatched=editor.setText(desired)
        if(!dispatched)dispatched=editor.commit(desired,before.length)
        repeat(8) {
            pause()
            if (!editor.sameTarget()) return ActionResult.Failed("输入期间页面或焦点已变化，请核对草稿。",true)
            if (TypedText.matches(desired,editor.value())) return ActionResult.Ok
        }
        // Never try a second write after dispatch: a late paste could otherwise duplicate the draft.
        return ActionResult.Failed(if(editor.value().orEmpty()!=before)"输入框已有变化，但与要求不符；请核对草稿，不重复粘贴。"
            else "尚未核验输入结果，未重复写入；请读取正文框核对，不能在键盘上逐字猜点。",dispatched)
    }
}
