package dev.pocketagent.ui

import android.app.KeyguardManager
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Toast
import dev.pocketagent.R
import dev.pocketagent.ui.kit.Kit
import dev.pocketagent.ui.kit.ThemeMode

/**
 * Task results stay until dismissed or replaced. Other summaries expire after 15 seconds.
 * The overlay is removed while the keyguard is locked and restored after unlock. Main thread only.
 */
class SummaryCard(private val context: Context) {
    private val handler = Handler(Looper.getMainLooper())
    private val keyguard = context.getSystemService(KeyguardManager::class.java)
    private data class Content(val title: String, val body: String, val result: Boolean, val onContinue: (() -> Unit)?)

    private var card: View? = null
    private var shownNight: Boolean? = null
    private var pending: Content? = null
    private val autoClose = Runnable { close() }
    private val poll = Runnable { sync() }

    fun show(title: String, body: String) {
        show(Content(title, body, false, null))
    }

    fun showResult(title: String, body: String, onContinue: (() -> Unit)? = null) {
        show(Content(title, body, true, onContinue))
    }

    private fun show(content: Content) {
        close()
        pending = content
        sync()
    }

    fun close() {
        handler.removeCallbacks(poll)
        handler.removeCallbacks(autoClose)
        removeWindow()
        pending = null
    }

    /** Puts the window on screen only while unlocked, and keeps watching for lock changes until closed. */
    private fun sync() {
        val content = pending ?: return
        handler.removeCallbacks(poll)
        val locked = keyguard?.isKeyguardLocked == true
        if (locked) {
            if (card != null) {
                handler.removeCallbacks(autoClose)
                removeWindow()
            }
        } else {
            val night = ThemeMode.isNight(context)
            val replace = card != null && shownNight != night
            if (replace) {
                handler.removeCallbacks(autoClose)
                removeWindow()
            }
            if (card == null && addWindow(content, animate = !replace)) {
                shownNight = night
                if (!content.result) handler.postDelayed(autoClose, 15_000L)
            } else if (card == null) {
                close()
                if (content.result) Toast.makeText(context, content.title, Toast.LENGTH_LONG).show()
                return
            }
        }
        handler.postDelayed(poll, POLL_MS)
    }

    private fun removeWindow() {
        card?.let {
            it.animate().cancel()
            Overlays.remove(context, it)
        }
        card = null
        shownNight = null
    }

    private fun addWindow(content: Content, animate: Boolean): Boolean {
        val c = context
        val outer = LinearLayout(c)
        val root = LinearLayout(c).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.TOP
            setPadding(Overlays.dp(c, 16), Overlays.dp(c, 14), Overlays.dp(c, 16), Overlays.dp(c, 16))
            background = Kit.rounded(Kit.color(c, R.color.pa_card), Overlays.dp(c, 24).toFloat(), Kit.color(c, R.color.pa_line), Overlays.dp(c, 1))
            elevation = Overlays.dp(c, 14).toFloat()
            if (content.result) accessibilityPaneTitle = content.title
            else setOnClickListener { close() }
        }
        root.addView(
            ImageView(c).apply {
                setImageResource(R.drawable.logo_mark)
                layoutParams = LinearLayout.LayoutParams(Overlays.dp(c, 36), Overlays.dp(c, 36)).apply { marginEnd = Overlays.dp(c, 12) }
            },
        )
        val col = LinearLayout(c).apply { orientation = LinearLayout.VERTICAL }
        val top = LinearLayout(c).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        top.addView(Kit.text(c, if (content.result) "dsh · 任务结果" else "dsh", 13f, 500, R.color.pa_muted), LinearLayout.LayoutParams(0, -2, 1f))
        col.addView(top, LinearLayout.LayoutParams(-1, -2))
        col.addView(Kit.text(c, content.title, 20f, 600).apply {
            setPadding(0, Overlays.dp(c, 3), 0, 0)
            isAccessibilityHeading = true
        }, Kit.lp())
        if (content.body.isNotBlank()) {
            val body = Kit.text(c, content.body, 15f, 400, R.color.pa_ink).apply {
                setPadding(0, Overlays.dp(c, 6), 0, 0)
                setLineSpacing(Overlays.dp(c, 2).toFloat(), 1f)
            }
            val scroll = object : ScrollView(c) {
                override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
                    val limit = (resources.displayMetrics.heightPixels * 0.35f).toInt()
                    val height = if (MeasureSpec.getMode(heightMeasureSpec) == MeasureSpec.UNSPECIFIED) limit
                        else minOf(limit, MeasureSpec.getSize(heightMeasureSpec))
                    super.onMeasure(widthMeasureSpec, MeasureSpec.makeMeasureSpec(height, MeasureSpec.AT_MOST))
                }
            }.apply { addView(body) }
            col.addView(scroll, Kit.lp())
        }
        if (content.result) {
            val actions = Kit.hstack(c, 8)
            fun choose(action: (() -> Unit)?) {
                if (card !== outer || keyguard?.isKeyguardLocked == true) return
                close()
                action?.invoke()
            }
            val again = content.onContinue
            if (again == null) {
                actions.addView(Kit.primary(c, "知道了", 48) { choose(null) }, Kit.weight())
            } else {
                actions.addView(Kit.secondary(c, "关闭", 48) { choose(null) }, Kit.weight())
                actions.addView(Kit.primary(c, "继续任务", 48) { choose(again) }, Kit.weight())
            }
            col.addView(actions, Kit.lp().apply { topMargin = Overlays.dp(c, 14) })
        }
        root.addView(col, LinearLayout.LayoutParams(0, -2, 1f))
        val margin = Overlays.dp(c, 16)
        val params = Overlays.params(
            minOf(c.resources.displayMetrics.widthPixels - 2 * margin, Overlays.dp(c, 420)).coerceAtLeast(1),
            WindowManager.LayoutParams.WRAP_CONTENT,
            if (content.result) Gravity.CENTER else Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL,
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
        ).also {
            it.setTitle(if (content.result) "dsh-task-result" else "dsh-summary-card")
            it.flags = it.flags and WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS.inv()
            if (!content.result) it.y = margin
        }
        outer.addView(root, LinearLayout.LayoutParams(-1, -2))
        if (!Overlays.add(c, outer, params)) return false
        card = outer
        if (animate) {
            outer.alpha = 0f
            outer.translationY = Overlays.dp(c, 80).toFloat()
            outer.animate().alpha(1f).translationY(0f).setDuration(420).setInterpolator(Kit.ease).start()
        }
        return true
    }

    private companion object {
        const val POLL_MS = 500L
    }
}
