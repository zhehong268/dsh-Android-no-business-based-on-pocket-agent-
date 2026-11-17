package dev.pocketagent.ui

import android.app.Activity
import android.app.Dialog
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.view.Gravity
import android.view.WindowManager
import android.widget.FrameLayout
import dev.pocketagent.R
import dev.pocketagent.ui.kit.Kit
import dev.pocketagent.ui.kit.Kit.color
import dev.pocketagent.ui.kit.Kit.dp
import dev.pocketagent.ui.kit.Kit.dpf

/**
 * The agent's question, asked inside the app instead of in a task of its own. Leaving a separate task after answering
 * looked like the app had quit, so the question now rises over Home and the answer goes straight back to the task.
 */
object AnswerDialog {
    /** The dialog on screen, if any. The session closes it when the wait ends for another reason. */
    @Volatile
    private var shown: Dialog? = null

    fun close() {
        val dialog = shown ?: return
        shown = null
        if (dialog.isShowing) dialog.dismiss()
    }

    /**
     * Shows [question] and calls [onAnswer] with the text, or null when the user skips. [onAnswer] runs once: the
     * buttons close the dialog first, and a dialog dismissed by the back gesture counts as a skip.
     */
    fun show(activity: Activity, question: String, onAnswer: (String?) -> Unit) {
        close()
        val c = activity
        val dialog = Dialog(c)
        shown = dialog
        var answered = false
        val answer: (String?) -> Unit = { text ->
            if (!answered) {
                answered = true
                if (shown === dialog) shown = null
                if (dialog.isShowing) dialog.dismiss()
                onAnswer(text)
            }
        }
        dialog.setOnDismissListener { answer(null) }

        val card = Kit.vstack(c).apply {
            background = Kit.rounded(color(c, R.color.pa_card), dpf(c, 28))
            setPadding(dp(c, 20), dp(c, 22), dp(c, 20), dp(c, 18))
        }
        card.addView(Kit.text(c, "要你确认一下", 20f, 600), Kit.lp())
        card.addView(
            Kit.text(c, question, 15f, 400, R.color.pa_ink).apply { setPadding(0, dp(c, 10), 0, 0) },
            Kit.lp(),
        )
        val field = Kit.field(c, "在这里回答").apply {
            filters = arrayOf(android.text.InputFilter.LengthFilter(500))
        }
        card.addView(field, Kit.lp().apply { topMargin = dp(c, 14) })
        card.addView(
            Kit.primary(c, "提交") {
                val text = field.text.toString().trim()
                if (text.isNotEmpty()) answer(text)
            },
            Kit.lp().apply { topMargin = dp(c, 14) },
        )
        card.addView(
            Kit.text(c, "跳过", 16f, 500, R.color.pa_muted).apply {
                gravity = Gravity.CENTER
                setPadding(0, dp(c, 14), 0, dp(c, 2))
                Kit.pressable(this, 1f) { answer(null) }
            },
            Kit.lp(),
        )

        val outer = FrameLayout(c).apply { setPadding(dp(c, 12), dp(c, 12), dp(c, 12), dp(c, 12)) }
        outer.addView(card, FrameLayout.LayoutParams(-1, -2))
        dialog.setContentView(outer)
        dialog.window?.apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            setGravity(Gravity.BOTTOM)
            setLayout(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.WRAP_CONTENT)
            setSoftInputMode(
                WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE or WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE,
            )
            setDimAmount(0.45f)
        }
        dialog.show()
        card.alpha = 0f
        card.translationY = dpf(c, 60)
        card.animate().alpha(1f).translationY(0f).setDuration(320).interpolator = Kit.ease
        field.requestFocus()
    }
}
