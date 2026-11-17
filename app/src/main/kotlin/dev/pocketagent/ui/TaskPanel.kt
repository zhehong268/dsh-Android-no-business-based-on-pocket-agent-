package dev.pocketagent.ui

import android.annotation.SuppressLint
import android.content.Context
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import dev.pocketagent.R
import dev.pocketagent.ui.kit.Kit

/** Task-only panel. Drag the header to move it; screen reads and gestures temporarily hide it. */
@SuppressLint("ClickableViewAccessibility")
class TaskPanel(private val context: Context, onStop: () -> Unit) {
    private val title = Kit.text(context, "dsh · 运行中", 13f, 600, R.color.pa_ink)
    private val plan = Kit.text(context, "正在读取屏幕", 12f, 400, R.color.pa_ink)
    private val action = Kit.text(context, "准备操作", 12f, 500, R.color.pa_ink)
    private val view = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        val p = Overlays.dp(context, 10)
        setPadding(p, p, p, p)
        background = Kit.rounded(Kit.color(context, R.color.pa_card), p.toFloat(), Kit.color(context, R.color.pa_line), Overlays.dp(context, 1))
        elevation = Overlays.dp(context, 6).toFloat()
        addView(title)
        plan.maxLines = 4
        action.maxLines = 3
        addView(plan)
        addView(action)
        addView(Button(context).apply {
            text = "停止"
            textSize = 13f
            minHeight = Overlays.dp(context, 48)
            setOnClickListener { onStop() }
        }, LinearLayout.LayoutParams(-1, Overlays.dp(context, 48)))
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
    }
    private val params = Overlays.params(
        Overlays.dp(context, 164), WindowManager.LayoutParams.WRAP_CONTENT,
        Gravity.TOP or Gravity.LEFT, WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
    ).also {
        it.setTitle("dsh-task-panel")
        it.x = (context.resources.displayMetrics.widthPixels - Overlays.dp(context, 172)).coerceAtLeast(0)
        it.y = Overlays.dp(context, 104)
        it.flags = it.flags and WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS.inv()
    }
    private var shown = false
    private var running = false
    private var visible = true
    private var lastText = "准备操作"
    private var waiting = false

    init {
        var startX = 0
        var startY = 0
        var touchX = 0f
        var touchY = 0f
        title.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    startX = params.x; startY = params.y
                    touchX = event.rawX; touchY = event.rawY
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val display = context.resources.displayMetrics
                    params.x = (startX + (event.rawX - touchX).toInt()).coerceIn(0, (display.widthPixels - params.width).coerceAtLeast(0))
                    params.y = (startY + (event.rawY - touchY).toInt()).coerceIn(0, (display.heightPixels - view.height).coerceAtLeast(0))
                    Overlays.update(context, view, params)
                    true
                }
                else -> true
            }
        }
    }

    fun setRunning(value: Boolean) {
        if (running == value) return
        running = value
        if (!value) {
            lastText = "准备操作"; waiting = false
            plan.text = "正在读取屏幕"
        }
        refresh()
    }

    fun setText(text: String?) {
        lastText = text?.take(200).orEmpty()
        refresh()
    }

    fun setPlan(goal: String, next: String) {
        plan.text = listOf(goal.take(90), next.take(90).takeIf { it.isNotBlank() }?.let { "下一步：$it" }).filterNotNull().filter { it.isNotBlank() }.joinToString("\n")
    }

    fun setWaiting(value: Boolean) { waiting = value; refresh() }
    fun setVisible(value: Boolean) { visible = value; refresh() }

    fun close() {
        if (shown) Overlays.remove(context, view)
        shown = false
    }

    private fun refresh() {
        if (!running) { close(); return }
        title.text = if (waiting) "dsh · 等待确认" else "dsh · 运行中"
        action.text = lastText
        view.visibility = if (visible) View.VISIBLE else View.INVISIBLE
        if (!shown) shown = Overlays.add(context, view, params)
    }
}
