package dev.pocketagent.ui

import android.content.Context
import android.text.InputFilter
import android.view.Gravity
import android.view.View
import android.view.WindowInsets
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import android.widget.ScrollView
import dev.pocketagent.R
import dev.pocketagent.agent.TaskConversation
import dev.pocketagent.ui.kit.Kit

internal class TaskChatWindow(private val context: Context) {
    private var view: View? = null
    private var conversation: TaskConversation? = null

    fun show(chat: TaskConversation) {
        if (view != null) return
        val c = context
        val root = Kit.vstack(c, 10).apply {
            val p = Kit.dp(c, 16)
            setPadding(p, p, p, p)
            background = Kit.rounded(Kit.color(c, R.color.pa_card), Kit.dpf(c, 20))
            accessibilityPaneTitle = "任务对话"
        }
        root.addView(Kit.text(c, "任务对话", 20f, 600), Kit.lp())
        val lines = Kit.vstack(c, 8)
        val messages = chat.messages()
        if (messages.isEmpty()) lines.addView(Kit.text(c, "可以提问或补充当前任务。", 14f, 400, R.color.pa_muted))
        messages.forEach { line -> lines.addView(Kit.text(c, (if (line.fromUser) "你：" else "dsh：") + line.text, 14f)) }
        val log = ScrollView(c).apply { addView(lines) }
        root.addView(log, Kit.lp(h = Kit.dp(c, 106)))
        log.post { log.fullScroll(View.FOCUS_DOWN) }
        val input = Kit.field(c, "补充当前任务").apply { filters = arrayOf(InputFilter.LengthFilter(1000)) }
        root.addView(input, Kit.lp())
        val buttons = Kit.hstack(c, 8)
        buttons.addView(Kit.secondary(c, "收起", 48) { close() }, Kit.weight())
        buttons.addView(Kit.primary(c, "发送", 48) { if (chat.submit(input.text.toString())) close() }, Kit.weight())
        root.addView(buttons, Kit.lp())
        val params = Overlays.params(
            minOf(Kit.dp(c, 420), c.resources.displayMetrics.widthPixels - Kit.dp(c, 24)).coerceAtLeast(1),
            WindowManager.LayoutParams.WRAP_CONTENT,
            Gravity.CENTER, WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
        ).apply {
            flags = flags and (WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS).inv()
            softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
            setFitInsetsTypes(WindowInsets.Type.systemBars() or WindowInsets.Type.ime())
            setTitle("dsh-task-conversation")
        }
        if (Overlays.add(c, root, params)) {
            view = root
            conversation = chat
            chat.setEditing(true)
            root.isFocusableInTouchMode = true
            root.requestFocus()
        }
    }

    fun close() {
        view?.let {
            context.getSystemService(InputMethodManager::class.java)?.hideSoftInputFromWindow(it.windowToken, 0)
            Overlays.remove(context, it)
        }
        view = null
        conversation?.setEditing(false)
        conversation = null
    }
}
