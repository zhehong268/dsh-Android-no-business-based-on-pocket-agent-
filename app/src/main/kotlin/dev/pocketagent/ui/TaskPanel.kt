package dev.pocketagent.ui

import android.annotation.SuppressLint
import android.content.Context
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import dev.pocketagent.R
import dev.pocketagent.ui.kit.Kit

/** Drag any non-button area. A stop tap has its own hit target, never the drag listener. */
@SuppressLint("ClickableViewAccessibility")
class TaskPanel(private val context: Context, private val onChat: () -> Unit = {}, onStop: () -> Unit) {
    private val chat = Button(context).apply {
        text = "对话"
        textSize = 13f
        setOnClickListener { onChat() }
    }
    private val title = Kit.text(context, "dsh · 运行中 · 可拖动", 13f, 600, R.color.pa_ink).apply {
        minimumHeight=Overlays.dp(context,32)
        gravity=Gravity.CENTER_VERTICAL
    }
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
        addView(chat, LinearLayout.LayoutParams(-1, Overlays.dp(context, 48)))
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
    private var chatAvailable = false

    fun setChatAvailable(value: Boolean) { chatAvailable = value; refresh() }

    init {
        var startX = 0
        var startY = 0
        var touchX = 0f
        var touchY = 0f
        var dragging=false
        val slop=ViewConfiguration.get(context).scaledTouchSlop
        val drag=View.OnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    startX = params.x; startY = params.y
                    touchX = event.rawX; touchY = event.rawY
                    dragging=false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    if(!dragging && kotlin.math.abs(event.rawX-touchX)<slop && kotlin.math.abs(event.rawY-touchY)<slop)return@OnTouchListener true
                    dragging=true
                    val display = context.resources.displayMetrics
                    params.x = (startX + (event.rawX - touchX).toInt()).coerceIn(0, (display.widthPixels - params.width).coerceAtLeast(0))
                    params.y = (startY + (event.rawY - touchY).toInt()).coerceIn(0, (display.heightPixels - view.height).coerceAtLeast(0))
                    Overlays.update(context, view, params)
                    true
                }
                MotionEvent.ACTION_UP,MotionEvent.ACTION_CANCEL -> { dragging=false;true }
                else -> false
            }
        }
        listOf(view,title,plan,action).forEach { it.setOnTouchListener(drag) }
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
        title.text = if (waiting) "dsh · 等待确认 · 可拖动" else "dsh · 运行中 · 可拖动"
        action.text = lastText
        chat.visibility = if (chatAvailable) View.VISIBLE else View.GONE
        chat.isEnabled = !waiting
        view.visibility = if (visible) View.VISIBLE else View.INVISIBLE
        if (!shown) shown = Overlays.add(context, view, params)
    }
}
