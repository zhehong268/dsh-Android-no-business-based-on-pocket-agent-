package dev.pocketagent.ui

import android.content.Context
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import dev.pocketagent.R
import dev.pocketagent.agent.ConfirmRequest
import dev.pocketagent.ui.kit.Kit
import dev.pocketagent.ui.kit.Kit.color
import dev.pocketagent.ui.kit.Kit.dp
import dev.pocketagent.ui.kit.Kit.dpf

/**
 * Asks the user to allow or decline one action: a floating card that slides up from the bottom, showing what the
 * agent wants to do, in which app, and (for sending) the exact text. Allow and Decline are built from one helper so they
 * can never differ in size, weight or colour.
 */
class ConfirmCard(private val context: Context) {
    private var card: View? = null

    /** Puts the card on screen as a window over every app. */
    fun show(request: ConfirmRequest, onResult: (Boolean) -> Unit) {
        close()
        val outer = content(request, onResult)
        val sheet = outer.getChildAt(0)
        val params = Overlays.params(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            Gravity.BOTTOM,
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
        )
        params.setTitle("dsh-confirm-card")
        if (attach(outer, params)) {
            card = outer
            // the card slides up and settles
            sheet.alpha = 0f
            sheet.translationY = dpf(context, 120)
            sheet.animate().alpha(1f).translationY(0f).setDuration(420).setInterpolator(Kit.ease).start()
            outer.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
        } else {
            // Right after the service reconnects the window token can be stale for a moment; one retry is enough.
            outer.postDelayed(
                {
                    if (attach(outer, params)) {
                        card = outer
                        sheet.alpha = 1f
                        sheet.translationY = 0f
                        outer.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                    } else {
                        onResult(false)
                    }
                },
                220,
            )
        }
    }

    private fun attach(view: View, params: WindowManager.LayoutParams): Boolean =
        card == null && Overlays.add(context, view, params)

    /** The card's views, so the debug preview can show exactly what the window shows. */
    fun content(request: ConfirmRequest, onResult: (Boolean) -> Unit): FrameLayout {
        val c = context
        val outer = FrameLayout(c).apply {
            setPadding(dp(c, 12), dp(c, 12), dp(c, 12), dp(c, 12))
            clipChildren = false
            clipToPadding = false
        }
        val sheet = Kit.vstack(c).apply {
            background = Kit.rounded(color(c, R.color.pa_card), dpf(c, 28), color(c, R.color.pa_line), dp(c, 1))
            elevation = dpf(c, 18)
            setPadding(dp(c, 20), dp(c, 20), dp(c, 20), dp(c, 16))
        }

        // who is asking, and what
        val head = Kit.hstack(c, 12)
        head.addView(
            ImageView(c).apply {
                setImageResource(R.drawable.logo_mark)
                layoutParams = LinearLayout.LayoutParams(dp(c, 40), dp(c, 40))
            },
        )
        val titles = Kit.vstack(c)
        titles.addView(Kit.text(c, "dsh 想要", 13f, 400, R.color.pa_muted))
        titles.addView(
            Kit.text(c, c.getString(R.string.confirm_title), 20f, 600).apply {
                letterSpacing = -0.01f
                setPadding(0, dp(c, 2), 0, 0)
            },
        )
        head.addView(titles, Kit.weight())
        sheet.addView(head, Kit.lp())

        // the details, as hairline-separated rows
        val rows = ArrayList<Pair<String, String>>()
        rows.add("应用" to request.app)
        rows.add("操作" to request.action)
        rows.add("对象" to request.target)
        request.detail?.let { rows.add("将发送" to it) }
        val details = Kit.vstack(c).apply {
            background = Kit.rounded(color(c, R.color.pa_bg), dpf(c, 16))
            setPadding(dp(c, 14), dp(c, 2), dp(c, 14), dp(c, 2))
        }
        rows.forEachIndexed { i, (label, value) ->
            val r = Kit.hstack(c, 14).apply { setPadding(0, dp(c, 11), 0, dp(c, 11)) }
            r.addView(Kit.text(c, label, 13f, 400, R.color.pa_muted), LinearLayout.LayoutParams(dp(c, 62), -2))
            r.addView(Kit.text(c, value, 15f, if (label == "将发送") 500 else 400), Kit.weight())
            details.addView(r, Kit.lp())
            if (i < rows.lastIndex) details.addView(View(c).apply { setBackgroundColor(color(c, R.color.pa_line)) }, Kit.lp(h = 1))
        }
        val scroll=object:ScrollView(c) {
            override fun onMeasure(widthMeasureSpec:Int,heightMeasureSpec:Int) {
                val maximum=(resources.displayMetrics.heightPixels*0.42f).toInt()
                val given=View.MeasureSpec.getSize(heightMeasureSpec)
                val cap=if(View.MeasureSpec.getMode(heightMeasureSpec)==View.MeasureSpec.UNSPECIFIED)maximum else minOf(given,maximum)
                super.onMeasure(widthMeasureSpec,View.MeasureSpec.makeMeasureSpec(cap,View.MeasureSpec.AT_MOST))
            }
        }.apply { isFillViewport=false;addView(details) }
        sheet.addView(scroll, Kit.lp().apply { topMargin = dp(c, 16) })

        // two identical buttons: nothing nudges the choice
        val buttons = Kit.hstack(c, 12)
        buttons.addView(choice("拒绝", false, outer, onResult), Kit.weight())
        buttons.addView(choice("允许", true, outer, onResult), Kit.weight())
        sheet.addView(buttons, Kit.lp().apply { topMargin = dp(c, 16) })
        sheet.addView(
            Kit.text(c, "在任务页点「停止」可以中止任何任务。", 12f, 400, R.color.pa_muted).apply {
                gravity = Gravity.CENTER
                setPadding(0, dp(c, 12), 0, 0)
            },
            Kit.lp(),
        )

        outer.addView(sheet, FrameLayout.LayoutParams(-1, -2))
        return outer
    }

    fun close() {
        card?.let { Overlays.remove(context, it) }
        card = null
    }

    /** One style for both buttons. A touch that arrives through another window drawn on top is ignored. */
    private fun choice(label: String, result: Boolean, outer: View, onResult: (Boolean) -> Unit) =
        Kit.secondary(context, label, 54).also { b ->
            b.filterTouchesWhenObscured = true
            Kit.pressable(b, 0.97f, blockObscured = true) {
                b.performHapticFeedback(if (result) HapticFeedbackConstants.CONFIRM else HapticFeedbackConstants.REJECT)
                if (card === outer) card = null
                outer.animate().alpha(0f).translationY(dpf(context, 40)).setDuration(200).setInterpolator(Kit.ease)
                    .withEndAction {
                        // The next gesture may target the bottom of the app, underneath this window.
                        Overlays.remove(context, outer)
                        onResult(result)
                    }.start()
            }
        }
}
