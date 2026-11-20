package dev.dsh.phonefixture

import android.inputmethodservice.InputMethodService
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView

/** Fixed synthetic skin for checking region masking. No candidate collection or input handling. */
class FixtureKeyboard : InputMethodService() {
    override fun onEvaluateInputViewShown() = true
    override fun onEvaluateFullscreenMode() = false

    override fun onCreateInputView(): View = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        val density = resources.displayMetrics.density
        setBackgroundColor(0xFF253155.toInt())
        setPadding(0, (12 * density).toInt(), 0, (12 * density).toInt())
        listOf("测试候选词", "A   B   C   D   E", "F   G   H   I   J").forEachIndexed { row, label ->
            addView(TextView(this@FixtureKeyboard).apply {
                text = label
                textSize = 20f
                setTextColor(0xFFFFFFFF.toInt())
                gravity = Gravity.CENTER
                setBackgroundColor(if (row == 0) 0xFF547AA5.toInt() else 0xFF253155.toInt())
            }, LinearLayout.LayoutParams(-1, (70 * density).toInt()))
        }
    }
}
