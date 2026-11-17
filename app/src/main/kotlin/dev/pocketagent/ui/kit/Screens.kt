package dev.pocketagent.ui.kit

import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import dev.pocketagent.R
import dev.pocketagent.ui.kit.Kit.color
import dev.pocketagent.ui.kit.Kit.dp
import dev.pocketagent.ui.kit.Kit.dpf

data class RecentItem(val ok: Boolean, val title: String, val sub: String, val time: String, val id: Long = 0)

data class ActivityRow(val id: Long, val ok: Boolean, val title: String, val sub: String, val time: String, val day: String)

class ActivityDetail(
    val ok: Boolean, val title: String, val whenText: String, val result: String,
    val onRun: () -> Unit = {}, val onDelete: () -> Unit = {},
)

/** One thing that needs fixing, shown on Home as a single card with one button. */
class Attention(val title: String, val body: String, val action: String, val onAction: () -> Unit)

data class HomeState(
    val statusDetail: String,
    val attention: Attention?,
    val recent: List<RecentItem>,
    val usage: String?,
    val onRun: (String) -> Unit = {},
    val shortcuts: List<String> = DefaultShortcuts,
    val editingShortcuts: Boolean = false,
    val onEditShortcuts: () -> Unit = {},
    val onRemoveShortcut: (String) -> Unit = {},
    val onAddShortcut: () -> Unit = {},
    val micGranted: () -> Boolean = { false },
    val onNeedMic: () -> Unit = {},
)

val DefaultShortcuts = listOf("带我去某个地址", "回复最新消息", "总结这个屏幕")

data class SettingsState(
    val version: String,
    val provider: String,
    val keySaved: Boolean,
    val accessibilityOn: Boolean,
    val micOn: Boolean,
    val talkBackOn: Boolean = true,
    val onTalkBack: (Boolean) -> Unit = {},
    val linkOn: Boolean = false,
    val onLink: (Boolean) -> Unit = {},
    /** "192.168.1.5:8765" while the link is on and the phone has a local address, else null. */
    val linkAddress: String? = null,
    val onCopyLink: () -> Unit = {},
    val onCopyAddress: () -> Unit = {},
    val onResetLink: () -> Unit = {},
    val shortScreensOn: Boolean = false,
    val onShortScreens: (Boolean) -> Unit = {},
    val onProvider: () -> Unit = {},
    val onAccessibility: () -> Unit = {},
    val onMic: () -> Unit = {},
    /** Names of the folders the user granted for reading and writing files. */
    val fileFolders: List<String> = emptyList(),
    val onAddFolder: () -> Unit = {},
    val onRemoveFolder: (String) -> Unit = {},
    val showHistory: Boolean = true,
    val theme: String = "system",
    val onTheme: (String) -> Unit = {},
    val historyOn: Boolean = true,
    val onHistory: (Boolean) -> Unit = {},
    val onClearHistory: () -> Unit = {},
    val onRiskyInfo: () -> Unit = {},
    /** "¥24.74" once the platform has been asked, else null. */
    val balance: String? = null,
    /** Warn under this many yuan; zero turns the warning off. */
    val balanceAlert: Int = 10,
    val onRefreshBalance: () -> Unit = {},
    val onBalanceAlert: () -> Unit = {},
)

object Samples {
    val recent = listOf(
        RecentItem(true, "带我去这条消息里的地址", "Maps · 41 秒", "2 分钟"),
        RecentItem(true, "总结这个屏幕", "Chrome · 12 秒", "1 小时"),
        RecentItem(false, "用 App 点份老样子", "你已中止", "周一"),
    )
    val home = HomeState("模型正常 · 无障碍已开启", null, recent, "今天 3 个任务。")
    val fix = home.copy(
        attention = Attention("无障碍未开启", "在你重新开启之前，dsh 无法读取或点击屏幕。", "去开启") {},
    )
    val activity = listOf(
        ActivityRow(1, true, "带我去这条消息里的地址", "Maps", "2 分钟", "今天"),
        ActivityRow(2, true, "总结这个屏幕", "Chrome", "1 小时", "今天"),
        ActivityRow(3, false, "用 App 点份老样子", "你已中止", "周一", "昨天"),
    )
    val settings = SettingsState("版本 0.1.0 · 就绪", "OpenRouter", true, true, false, true)
}

/** The app's screens, drawn from the kit. The app and the debug preview feed them real or sample state. */
object Screens {
    private const val GUTTER = 16

    /** Wraps a scrolling screen with the tab bar pinned to the bottom, and an optional top bar that gains a hairline once content scrolls under it. */
    private fun withTabs(c: Context, content: View, selected: Int, nav: Nav, topBar: View? = null): View = FrameLayout(c).apply {
        setBackgroundColor(color(c, R.color.pa_bg))
        val scroll = Kit.scroll(c, content, bottomPadDp = 96)
        if (topBar == null) {
            addView(scroll, FrameLayout.LayoutParams(-1, -1))
        } else {
            val column = Kit.vstack(c)
            val line = View(c).apply { setBackgroundColor(color(c, R.color.pa_line)); alpha = 0f }
            column.addView(topBar, Kit.lp())
            column.addView(line, Kit.lp(h = 1))
            column.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
            scroll.setOnScrollChangeListener { _, _, y, _, _ ->
                line.animate().alpha(if (y > dp(c, 6)) 1f else 0f).setDuration(160).start()
            }
            addView(column, FrameLayout.LayoutParams(-1, -1))
        }
        addView(Kit.tabBar(c, selected, nav.tab), FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM))
    }

    /** A screen with a fixed bottom action area (welcome, notice): content scrolls, buttons never move. */
    private fun withActions(c: Context, content: View, actions: View): View = FrameLayout(c).apply {
        setBackgroundColor(color(c, R.color.pa_bg))
        addView(Kit.scroll(c, content, bottomPadDp = 150), FrameLayout.LayoutParams(-1, -1))
        addView(
            Kit.vstack(c).apply {
                setBackgroundColor(color(c, R.color.pa_bg))
                setPadding(dp(c, 24), dp(c, 14), dp(c, 24), dp(c, 20))
                addView(actions, Kit.lp())
            },
            FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM),
        )
    }

    private fun page(c: Context, topDp: Int = 28): LinearLayout = Kit.vstack(c).apply {
        setPadding(dp(c, GUTTER), dp(c, topDp), dp(c, GUTTER), dp(c, 24))
    }

    private fun gap(c: Context, v: Int) = View(c).apply { layoutParams = Kit.lp(h = dp(c, v)) }

    private fun okTile(c: Context) =
        Kit.tile(c, R.drawable.ic_check, color(c, R.color.pa_good) and 0x00FFFFFF or 0x24000000, color(c, R.color.pa_good), 32)

    private fun failTile(c: Context) =
        Kit.tile(c, R.drawable.ic_close, color(c, R.color.pa_bad) and 0x00FFFFFF or 0x24000000, color(c, R.color.pa_bad), 32)

    /** A rounded square in one of the system hues with a white glyph, as in iOS Settings. */
    private fun colorTile(c: Context, icon: Int, hue: Int) =
        Kit.tile(c, icon, color(c, hue), 0xFFFFFFFF.toInt(), 30)

    private fun blueTile(c: Context, icon: Int) =
        Kit.tile(c, icon, color(c, R.color.pa_tile), color(c, R.color.pa_tile_icon), 30)

    private fun sectionTitle(c: Context, title: String, action: String? = null, onAction: (() -> Unit)? = null): View {
        val h = Kit.hstack(c).apply { setPadding(dp(c, 4), 0, dp(c, 4), dp(c, 10)) }
        h.addView(Kit.text(c, title, 19f, 600).apply { letterSpacing = -0.01f }, Kit.weight())
        if (action != null) {
            h.addView(Kit.text(c, action, 16f, 500, R.color.pa_blue).apply {
                setPadding(dp(c, 14), dp(c, 8), 0, dp(c, 8))
                if (onAction != null) Kit.pressable(this, 1f, onClick = onAction)
            })
        }
        return h
    }

    private fun shortcutIcon(title: String): Int {
        val t = title.lowercase()
        return when {
            "address" in t || "map" in t || "direction" in t -> R.drawable.ic_pin
            "reply" in t || "message" in t || "text" in t -> R.drawable.ic_chat
            else -> R.drawable.ic_doc
        }
    }

    // ---------------------------------------------------------------- Home

    /** The app bar: the mark and name on the left, a live status pill on the right. */
    private fun appBar(c: Context, ok: Boolean, nav: Nav): View {
        val bar = Kit.hstack(c).apply {
            setPadding(dp(c, 18), dp(c, 8), dp(c, 14), dp(c, 8))
            minimumHeight = dp(c, 56)
            setBackgroundColor(color(c, R.color.pa_bg))
        }
        bar.addView(ImageView(c).apply {
            setImageResource(R.drawable.logo_mark)
            layoutParams = LinearLayout.LayoutParams(dp(c, 30), dp(c, 30)).apply { marginEnd = dp(c, 10) }
        })
        bar.addView(Kit.text(c, "dsh", 18f, 600).apply { letterSpacing = -0.01f }, Kit.weight())
        val pill = Kit.hstack(c, 7).apply {
            background = Kit.rounded(color(c, R.color.pa_fill), dpf(c, 16))
            setPadding(dp(c, 12), dp(c, 7), dp(c, 12), dp(c, 7))
        }
        pill.addView(View(c).apply {
            background = Kit.rounded(color(c, if (ok) R.color.pa_good else R.color.pa_bad), dpf(c, 4))
            layoutParams = LinearLayout.LayoutParams(dp(c, 8), dp(c, 8))
        })
        pill.addView(Kit.text(c, if (ok) "就绪" else "需要设置", 14f, 600))
        Kit.pressable(pill, 0.94f) { nav.tab(2) }
        bar.addView(pill)
        return bar
    }

    fun home(c: Context, state: HomeState = Samples.home, nav: Nav = Nav()): View {
        val needsAttention = state.attention != null
        val p = page(c, 12)
        val blocks = ArrayList<View>()
        fun add(v: View, lp: LinearLayout.LayoutParams = Kit.lp()) { p.addView(v, lp); blocks.add(v) }

        // one calm banner when something needs fixing: tinted, with a small pill button
        state.attention?.let { at ->
            val banner = Kit.hstack(c, 12).apply {
                background = Kit.rounded(color(c, R.color.pa_bad) and 0x00FFFFFF or 0x1A000000, dpf(c, 20))
                setPadding(dp(c, 14), dp(c, 14), dp(c, 12), dp(c, 14))
            }
            banner.addView(Kit.icon(c, R.drawable.ic_alert, 22, R.color.pa_bad).apply {
                background = Kit.rounded(color(c, R.color.pa_bad) and 0x00FFFFFF or 0x26000000, dpf(c, 18))
                setPadding(dp(c, 7), dp(c, 7), dp(c, 7), dp(c, 7))
                layoutParams = LinearLayout.LayoutParams(dp(c, 36), dp(c, 36))
            })
            val col = Kit.vstack(c)
            col.addView(Kit.text(c, at.title, 15f, 600))
            col.addView(Kit.text(c, at.body, 13f, 400, R.color.pa_muted).apply { setPadding(0, dp(c, 2), 0, 0) })
            banner.addView(col, Kit.weight())
            banner.addView(
                Kit.text(c, at.action, 14f, 600, R.color.pa_on_accent).apply {
                    gravity = Gravity.CENTER
                    background = Kit.rounded(color(c, R.color.pa_accent), dpf(c, 18))
                    setPadding(dp(c, 14), 0, dp(c, 14), 0)
                    minimumHeight = dp(c, 36)
                    Kit.pressable(this, 0.94f, onClick = at.onAction)
                },
            )
            add(banner, Kit.lp().apply { bottomMargin = dp(c, 14) })
        }

        // composer: the hero. The send button wakes up (grey to orange, with a pop) once there is something to send.
        val composer = Kit.vstack(c).apply {
            background = Kit.rounded(color(c, R.color.pa_card), dpf(c, 28))
            setPadding(dp(c, 18), dp(c, 16), dp(c, 12), dp(c, 12))
            elevation = dpf(c, 3)
            alpha = if (needsAttention) .5f else 1f
        }
        val input = Kit.input(c, if (needsAttention) "请先处理上方的提示" else "要我做什么？").apply {
            isEnabled = !needsAttention
            minimumHeight = dp(c, 44)
        }
        composer.addView(input, Kit.lp())
        val controls = Kit.hstack(c).apply { setPadding(0, dp(c, 6), 0, 0) }
        val micButton = Kit.icon(c, R.drawable.ic_mic, 22, R.color.pa_ink).apply {
            background = Kit.rounded(color(c, R.color.pa_fill), dpf(c, 22))
            setPadding(dp(c, 11), dp(c, 11), dp(c, 11), dp(c, 11))
            layoutParams = LinearLayout.LayoutParams(dp(c, 44), dp(c, 44))
            contentDescription = "说出你的任务"
        }
        var voice: dev.pocketagent.ui.VoiceInput? = null
        Kit.pressable(micButton, 0.9f) {
            if (needsAttention) return@pressable
            if (!dev.pocketagent.ui.VoiceInput.isAvailable(c)) {
                android.widget.Toast.makeText(c, "这台手机不支持语音输入。", android.widget.Toast.LENGTH_SHORT).show()
                return@pressable
            }
            if (!state.micGranted()) {
                // Not allowed yet: the host asks for the permission (or opens the app's permission page).
                state.onNeedMic()
                return@pressable
            }
            val v = voice ?: dev.pocketagent.ui.VoiceInput(
                context = c,
                onPartial = { input.setText(it.take(2000)); input.setSelection(input.text.length) },
                onFinal = { input.setText(it.take(2000)); input.setSelection(input.text.length) },
                onError = { android.widget.Toast.makeText(c, it, android.widget.Toast.LENGTH_SHORT).show() },
                onListening = { on ->
                    (micButton.background as GradientDrawable).setColor(color(c, if (on) R.color.pa_accent else R.color.pa_fill))
                    micButton.setColorFilter(color(c, if (on) R.color.pa_on_accent else R.color.pa_ink))
                    input.hint = if (on) "正在听" else "要我做什么？"
                },
            ).also { voice = it }
            v.toggle()
        }
        micButton.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v: View) {}
            override fun onViewDetachedFromWindow(v: View) { voice?.destroy(); voice = null }
        })
        controls.addView(micButton)
        controls.addView(View(c), LinearLayout.LayoutParams(0, 1, 1f))
        val send = Kit.icon(c, R.drawable.ic_send, 20, R.color.pa_muted).apply {
            val bg = Kit.rounded(color(c, R.color.pa_fill), dpf(c, 22))
            background = bg
            setPadding(dp(c, 12), dp(c, 12), dp(c, 12), dp(c, 12))
            layoutParams = LinearLayout.LayoutParams(dp(c, 44), dp(c, 44))
            Kit.pressable(this, 0.9f) {
                val t = input.text.toString().trim()
                if (t.isNotEmpty() && !needsAttention) { state.onRun(t); input.setText("") }
            }
        }
        controls.addView(send)
        var armed = false
        input.addTextChangedListener(object : android.text.TextWatcher {
            override fun afterTextChanged(s: android.text.Editable?) {
                val has = !s.isNullOrBlank()
                if (has == armed) return
                armed = has
                val from = if (has) color(c, R.color.pa_fill) else color(c, R.color.pa_accent)
                val to = if (has) color(c, R.color.pa_accent) else color(c, R.color.pa_fill)
                android.animation.ValueAnimator.ofObject(android.animation.ArgbEvaluator(), from, to).apply {
                    duration = 220
                    addUpdateListener { (send.background as GradientDrawable).setColor(it.animatedValue as Int) }
                }.start()
                send.setColorFilter(color(c, if (has) R.color.pa_on_accent else R.color.pa_muted))
                if (has) send.animate().scaleX(1.14f).scaleY(1.14f).setDuration(110).withEndAction {
                    send.animate().scaleX(1f).scaleY(1f).setDuration(260).setInterpolator(android.view.animation.OvershootInterpolator(3f)).start()
                }.start()
            }
            override fun beforeTextChanged(s: CharSequence?, st: Int, c1: Int, a: Int) {}
            override fun onTextChanged(s: CharSequence?, st: Int, b: Int, c2: Int) {}
        })
        input.setOnFocusChangeListener { _, focused ->
            composer.animate().translationZ(if (focused) dpf(c, 6) else 0f).setDuration(220).setInterpolator(Kit.ease).start()
        }
        composer.addView(controls, Kit.lp())
        add(composer)

        // shortcuts: compact pills in one scrolling row. Edit turns them into removable pills and adds nothing else.
        val editing = state.editingShortcuts
        add(
            sectionTitle(c, "快捷指令", if (editing) "完成" else "编辑", state.onEditShortcuts),
            Kit.lp().apply { topMargin = dp(c, 28) },
        )
        val pills = Kit.hstack(c, 8).apply { setPadding(0, dp(c, 4), dp(c, 16), dp(c, 4)) }
        state.shortcuts.forEachIndexed { i, title ->
            val pill = Kit.hstack(c, 8).apply {
                background = Kit.rounded(color(c, R.color.pa_tile), dpf(c, 22))
                setPadding(dp(c, 14), 0, dp(c, if (editing) 8 else 16), 0)
                minimumHeight = dp(c, 44)
            }
            pill.addView(Kit.icon(c, shortcutIcon(title), 18, R.color.pa_tile_icon))
            pill.addView(Kit.text(c, title, 14f, 600, R.color.pa_on_tile).apply { maxLines = 1 })
            if (editing) {
                pill.addView(
                    Kit.icon(c, R.drawable.ic_close, 14, R.color.pa_tile_icon).apply {
                        background = Kit.rounded(0x33FFFFFF, dpf(c, 11))
                        setPadding(dp(c, 4), dp(c, 4), dp(c, 4), dp(c, 4))
                        layoutParams = LinearLayout.LayoutParams(dp(c, 22), dp(c, 22))
                    },
                )
                // a gentle wiggle says "these can be changed"
                pill.rotation = if (i % 2 == 0) -1.6f else 1.6f
                pill.animate().rotation(if (i % 2 == 0) 1.6f else -1.6f).setDuration(120).withEndAction(object : Runnable {
                    var flip = false
                    override fun run() {
                        if (!pill.isAttachedToWindow) return
                        flip = !flip
                        val target = if ((i % 2 == 0) == flip) -1.6f else 1.6f
                        pill.animate().rotation(target).setDuration(120).withEndAction(this).start()
                    }
                }).start()
                Kit.pressable(pill, 0.94f) {
                    pill.animate().scaleX(0f).scaleY(0f).alpha(0f).setDuration(200).withEndAction { state.onRemoveShortcut(title) }.start()
                }
            } else {
                Kit.pressable(pill, 0.95f) { if (!needsAttention) state.onRun(title) }
            }
            pills.addView(pill)
        }
        if (!editing) {
            val add = Kit.hstack(c, 6).apply {
                background = GradientDrawable().apply {
                    setColor(0); cornerRadius = dpf(c, 22); setStroke(dp(c, 2), color(c, R.color.pa_line), dpf(c, 6), dpf(c, 5))
                }
                setPadding(dp(c, 14), 0, dp(c, 16), 0)
                minimumHeight = dp(c, 44)
            }
            add.addView(Kit.icon(c, R.drawable.ic_plus, 16, R.color.pa_muted))
            add.addView(Kit.text(c, "新建", 14f, 500, R.color.pa_muted))
            Kit.pressable(add, 0.95f, onClick = state.onAddShortcut)
            pills.addView(add)
        }
        val scroller = Kit.hscroll(c, pills).apply { clipChildren = false }
        add(scroller)

        // recent: a plain list, no card
        add(sectionTitle(c, "最近", if (state.recent.isEmpty()) null else "查看全部"), Kit.lp().apply { topMargin = dp(c, 32) })
        if (state.recent.isEmpty()) {
            add(Kit.text(c, "还没有记录。你运行的任务会显示在这里，仅保存在本机。", 15f, 400, R.color.pa_muted).apply {
                setPadding(dp(c, 4), 0, dp(c, 4), 0); setLineSpacing(dpf(c, 3), 1f)
            })
        } else {
            val list = Kit.vstack(c)
            state.recent.forEachIndexed { i, it ->
                val r = Kit.hstack(c, 14).apply { minimumHeight = dp(c, 68); setPadding(dp(c, 4), 0, dp(c, 4), 0) }
                r.addView(Kit.icon(c, if (it.ok) R.drawable.ic_check else R.drawable.ic_close, 22, if (it.ok) R.color.pa_good else R.color.pa_bad))
                val col = Kit.vstack(c)
                col.addView(Kit.text(c, it.title, 17f, 500).apply { maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END })
                col.addView(Kit.text(c, it.sub, 13f, 400, R.color.pa_muted).apply { setPadding(0, dp(c, 2), 0, 0) })
                r.addView(col, Kit.weight())
                r.addView(Kit.text(c, it.time, 13f, 400, R.color.pa_muted))
                Kit.pressable(r, 1f) { nav.push(if (it.id == 0L) "detail" else "detail:${it.id}") }
                list.addView(r, Kit.lp())
                if (i < state.recent.lastIndex) list.addView(View(c).apply { setBackgroundColor(color(c, R.color.pa_line)) },
                    Kit.lp(h = 1).apply { marginStart = dp(c, 40) })
            }
            add(list)
        }
        state.usage?.let { u -> add(Kit.footnote(c, u).apply { setPadding(dp(c, 4), dp(c, 16), 0, 0) }) }

        p.post { Kit.reveal(blocks) }
        return withTabs(c, p, 0, nav, topBar = appBar(c, !needsAttention, nav))
    }

    // ------------------------------------------------------------ Activity

    fun activity(c: Context, nav: Nav = Nav(), empty: Boolean = false, rows: List<ActivityRow>? = null): View {
        val all = if (empty) emptyList() else rows ?: Samples.activity
        val p = page(c)
        p.addView(Kit.largeTitle(c, "记录").apply { setPadding(dp(c, 4), 0, 0, 0) })
        if (all.isEmpty()) {
            val e = Kit.vstack(c).apply { gravity = Gravity.CENTER_HORIZONTAL; setPadding(dp(c, 24), dp(c, 120), dp(c, 24), 0) }
            e.addView(Kit.icon(c, R.drawable.ic_activity, 44, R.color.pa_muted).apply {
                layoutParams = LinearLayout.LayoutParams(dp(c, 44), dp(c, 44)).apply { gravity = Gravity.CENTER_HORIZONTAL }
            })
            e.addView(Kit.text(c, "还没有记录", 19f, 600).apply { gravity = Gravity.CENTER; setPadding(0, dp(c, 16), 0, 0) }, Kit.lp())
            e.addView(Kit.text(c, "你运行的任务会显示在这里，包括执行结果，仅保存在本机。", 15f, 400, R.color.pa_muted).apply {
                gravity = Gravity.CENTER; setPadding(0, dp(c, 8), 0, 0); setLineSpacing(dpf(c, 3), 1f)
            }, Kit.lp())
            p.addView(e, Kit.lp())
            return withTabs(c, p, 1, nav)
        }
        p.addView(gap(c, 14))
        val search = Kit.hstack(c, 8).apply {
            background = Kit.rounded(color(c, R.color.pa_fill), dpf(c, 12))
            setPadding(dp(c, 12), 0, dp(c, 12), 0)
            minimumHeight = dp(c, 40)
        }
        search.addView(Kit.icon(c, R.drawable.ic_search, 18, R.color.pa_muted))
        val query = Kit.input(c, "搜索任务", 16f).apply { minLines = 1; maxLines = 1; setSingleLine(true) }
        search.addView(query, Kit.weight())
        p.addView(search, Kit.lp())
        p.addView(gap(c, 12))
        val list = Kit.vstack(c)
        var filter = 0
        var firstRender = true
        fun render() {
            list.removeAllViews()
            // Changing the filter or the search softly cross-fades the list instead of snapping it.
            if (!firstRender) {
                list.alpha = 0.35f
                list.animate().alpha(1f).setDuration(200).setInterpolator(Kit.ease).start()
            }
            firstRender = false
            val q = query.text.toString().trim()
            val shown = all.filter {
                (filter == 0 || (filter == 1 && it.ok) || (filter == 2 && !it.ok)) && (q.isEmpty() || it.title.contains(q, ignoreCase = true))
            }
            if (shown.isEmpty()) {
                list.addView(Kit.text(c, "没有匹配的结果。", 15f, 400, R.color.pa_muted).apply { setPadding(dp(c, 20), dp(c, 24), 0, 0) })
                return
            }
            shown.groupBy { it.day }.forEach { (day, items) ->
                list.addView(Kit.section(c, day), Kit.lp().apply { topMargin = dp(c, 22) })
                list.addView(
                    Kit.group(
                        c,
                        items.map { r ->
                            Kit.row(c, r.title, r.sub, if (r.ok) okTile(c) else failTile(c), Kit.text(c, r.time, 13f, 400, R.color.pa_muted), 64) {
                                nav.push(if (r.id <= 5) "detail" else "detail:${r.id}")
                            }
                        },
                        sepInsetDp = 62,
                    ),
                    Kit.lp(),
                )
            }
        }
        p.addView(Kit.segmented(c, listOf("全部", "完成", "已中止"), 0) { filter = it; render() }, Kit.lp())
        query.addTextChangedListener(object : android.text.TextWatcher {
            override fun afterTextChanged(s: android.text.Editable?) = render()
            override fun beforeTextChanged(s: CharSequence?, st: Int, c1: Int, a: Int) {}
            override fun onTextChanged(s: CharSequence?, st: Int, b: Int, c2: Int) {}
        })
        render()
        p.addView(list, Kit.lp())
        p.addView(Kit.footnote(c, "只保存在本机：你输入的任务和执行结果，绝不包含屏幕内容。可在设置里关闭或清除。"), Kit.lp().apply { topMargin = dp(c, 12) })
        return withTabs(c, p, 1, nav)
    }

    fun activityDetail(c: Context, nav: Nav = Nav(), d: ActivityDetail? = null): View {
        val detail = d ?: ActivityDetail(true, "带我去这条消息里的地址", "今天 18:42", "Maps 正在显示前往公园路 14 号的路线。")
        val p = page(c, 20)
        val back = Kit.hstack(c, 2).apply { setPadding(0, 0, 0, dp(c, 14)) }
        back.addView(Kit.icon(c, R.drawable.ic_back, 22, R.color.pa_blue))
        back.addView(Kit.text(c, "记录", 17f, 400, R.color.pa_blue))
        Kit.pressable(back, 1f) { nav.back() }
        p.addView(back, Kit.lp(ViewGroup.LayoutParams.WRAP_CONTENT))
        p.addView(Kit.text(c, detail.title, 24f, 600).apply { letterSpacing = -0.02f; setLineSpacing(dpf(c, 2), 1f) })
        p.addView(Kit.text(c, detail.whenText, 15f, 400, R.color.pa_muted).apply { setPadding(0, dp(c, 6), 0, dp(c, 18)) })

        val res = Kit.card(c, 22, 16)
        val head = Kit.hstack(c)
        head.addView(if (detail.ok) okTile(c) else failTile(c))
        head.addView(Kit.text(c, if (detail.ok) "完成" else "已中止", 17f, 600))
        res.addView(head)
        if (detail.result.isNotBlank()) {
            res.addView(Kit.text(c, detail.result, 15f, 400).apply { setPadding(0, dp(c, 10), 0, dp(c, 14)); setLineSpacing(dpf(c, 3), 1f) })
        } else {
            res.addView(View(c), Kit.lp(h = dp(c, 12)))
        }
        p.addView(res, Kit.lp())
        p.addView(gap(c, 22))
        p.addView(Kit.primary(c, "重新运行", onClick = detail.onRun), Kit.lp())
        p.addView(Kit.text(c, "从记录中删除", 17f, 400, R.color.pa_bad).apply {
            gravity = Gravity.CENTER; setPadding(0, dp(c, 16), 0, dp(c, 8))
            Kit.pressable(this, 1f, onClick = detail.onDelete)
        }, Kit.lp())
        return FrameLayout(c).apply {
            setBackgroundColor(color(c, R.color.pa_bg))
            addView(Kit.scroll(c, p), FrameLayout.LayoutParams(-1, -1))
        }
    }

    /** A pushed screen's back link and large title. */
    private fun pushed(c: Context, nav: Nav, back: String, title: String): LinearLayout {
        val p = page(c, 20)
        val row = Kit.hstack(c, 2).apply { setPadding(0, 0, 0, dp(c, 10)) }
        row.addView(Kit.icon(c, R.drawable.ic_back, 22, R.color.pa_blue))
        row.addView(Kit.text(c, back, 17f, 400, R.color.pa_blue))
        Kit.pressable(row, 1f) { nav.back() }
        p.addView(row, Kit.lp(ViewGroup.LayoutParams.WRAP_CONTENT))
        p.addView(Kit.largeTitle(c, title).apply { setPadding(dp(c, 4), 0, 0, dp(c, 8)) })
        return p
    }

    private fun plain(c: Context, p: View): View = FrameLayout(c).apply {
        setBackgroundColor(color(c, R.color.pa_bg))
        addView(Kit.scroll(c, p), FrameLayout.LayoutParams(-1, -1))
    }

    /** The apps the agent will never open or act in. Mirrors the rules in [dev.pocketagent.safety.BlockedApps]. */
    fun blockedApps(c: Context, nav: Nav = Nav()): View {
        val p = pushed(c, nav, "设置", "永不触碰的应用")
        p.addView(Kit.text(c, "即使你要求，dsh 也不会打开、读取或点击这些应用。这是一条无法关闭的安全规则。", 15f, 400, R.color.pa_muted).apply {
            setPadding(dp(c, 4), 0, dp(c, 4), dp(c, 8)); setLineSpacing(dpf(c, 3), 1f)
        }, Kit.lp())
        fun block(title: String, icon: Int, hue: Int, vararg names: String) {
            p.addView(Kit.section(c, title), Kit.lp().apply { topMargin = dp(c, 18) })
            p.addView(Kit.group(c, names.map { n -> Kit.row(c, n, leading = colorTile(c, icon, hue), minHeight = 52) }, 60), Kit.lp())
        }
        block("银行与支付", R.drawable.ic_lock, R.color.pa_t_red, "Google Pay", "PhonePe", "Paytm", "BHIM 及其他 UPI 应用", "任何银行或钱包应用")
        block("密码与登录验证码", R.drawable.ic_key, R.color.pa_t_orange, "Bitwarden", "1Password", "LastPass", "Google Authenticator")
        block("手机控制", R.drawable.ic_settings, R.color.pa_t_gray, "设置", "系统控制与权限页面", "应用安装器")
        p.addView(Kit.footnote(c, "它还会跳过名称里含 bank、pay、wallet、UPI 或 password 的应用。"))
        return plain(c, p)
    }

    /** What the app does with what it reads. */
    fun licences(c: Context, nav: Nav = Nav()): View {
        val p = pushed(c, nav, "设置", "隐私说明")
        p.addView(Kit.text(c, "dsh 在这台手机上做什么、不做什么。", 15f, 400, R.color.pa_muted).apply {
            setPadding(dp(c, 4), 0, dp(c, 4), dp(c, 8)); setLineSpacing(dpf(c, 3), 1f)
        }, Kit.lp())
        p.addView(Kit.group(c, listOf(
            Kit.row(c, "屏幕内容", "只在任务运行时读取，任务结束就丢弃", colorTile(c, R.drawable.ic_doc, R.color.pa_t_gray), null, 64),
            Kit.row(c, "发给了谁", "只有你设置的 AI 服务商", colorTile(c, R.drawable.ic_doc, R.color.pa_t_gray), null, 64),
            Kit.row(c, "本机保存", "服务商配置、快捷指令、任务记录、答题卡", colorTile(c, R.drawable.ic_doc, R.color.pa_t_gray), null, 64),
            Kit.row(c, "不保存", "屏幕原文、截图、密码与验证码", colorTile(c, R.drawable.ic_doc, R.color.pa_t_gray), null, 64),
        ), 60), Kit.lp().apply { topMargin = dp(c, 14) })
        p.addView(Kit.footnote(c, "任务记录随时可以在设置里关闭或清空。"))
        return plain(c, p)
    }

    // ------------------------------------------------------------ Settings

    private fun settingsGroup(c: Context, vararg rows: View) = Kit.group(c, rows.toList(), 60)

    fun settings(c: Context, state: SettingsState = Samples.settings, nav: Nav = Nav()): View {
        val p = page(c)
        p.addView(Kit.largeTitle(c, "设置").apply { setPadding(dp(c, 4), 0, 0, 0) })
        p.addView(gap(c, 22))

        val about = Kit.hstack(c, 14).apply {
            background = Kit.rounded(color(c, R.color.pa_hero), dpf(c, 22))
            setPadding(dp(c, 18), dp(c, 18), dp(c, 18), dp(c, 18))
        }
        about.addView(ImageView(c).apply {
            setImageResource(R.drawable.logo_mark)
            layoutParams = LinearLayout.LayoutParams(dp(c, 52), dp(c, 52))
        })
        val aboutText = Kit.vstack(c)
        aboutText.addView(Kit.text(c, "dsh", 19f, 600, R.color.pa_on_hero))
        aboutText.addView(Kit.text(c, state.version, 14f, 400, R.color.pa_hero_muted).apply { setPadding(0, dp(c, 3), 0, 0) })
        about.addView(aboutText, Kit.weight())
        p.addView(about, Kit.lp())
        p.addView(gap(c, 26))

        p.addView(Kit.section(c, "AI"))
        p.addView(settingsGroup(c,
            Kit.row(c, "服务商与模型", leading = colorTile(c, R.drawable.ic_globe, R.color.pa_t_blue), trailing = Kit.value(c, state.provider), onClick = state.onProvider),
            Kit.row(c, "API 密钥", leading = colorTile(c, R.drawable.ic_key, R.color.pa_t_orange), trailing = Kit.value(c, if (state.keySaved) "已保存" else "未设置"), onClick = state.onProvider),
        ), Kit.lp())
        p.addView(gap(c, 26))

        p.addView(Kit.section(c, "权限"))
        p.addView(settingsGroup(c,
            Kit.row(c, "无障碍", leading = colorTile(c, R.drawable.ic_access, R.color.pa_t_indigo),
                trailing = Kit.value(c, if (state.accessibilityOn) "已开启" else "已关闭", tint = if (state.accessibilityOn) R.color.pa_good else R.color.pa_bad),
                onClick = state.onAccessibility),
            Kit.row(c, "麦克风", leading = colorTile(c, R.drawable.ic_mic, R.color.pa_t_red), trailing = Kit.value(c, if (state.micOn) "已开启" else "已关闭"), onClick = state.onMic),
        ), Kit.lp())
        p.addView(Kit.footnote(c, "dsh 靠无障碍读取屏幕、执行点击，只在任务运行时生效。"))
        p.addView(gap(c, 26))

        p.addView(Kit.section(c, "文件访问"))
        val fileRows = arrayListOf<View>(Kit.row(c, "添加文件夹", trailing = Kit.chevron(c), onClick = state.onAddFolder))
        state.fileFolders.forEach { name ->
            fileRows.add(
                Kit.row(
                    c, name,
                    trailing = Kit.value(c, "移除", tint = R.color.pa_bad),
                    onClick = { state.onRemoveFolder(name) },
                ),
            )
        }
        p.addView(Kit.group(c, fileRows, 16), Kit.lp())
        p.addView(Kit.footnote(c, "仅能读取这里列出的文件夹，不会创建、修改或删除文件。授权后的读取不再逐次确认。"))
        p.addView(gap(c, 26))

        p.addView(Kit.section(c, "语音"))
        p.addView(Kit.group(c, listOf(
            Kit.row(c, "语音播报", trailing = Kit.toggle(c, state.talkBackOn, state.onTalkBack)),
        ), 16), Kit.lp())
        p.addView(Kit.footnote(c, "用麦克风开始任务时，dsh 会开口，也会听你说。"))
        p.addView(gap(c, 26))

        p.addView(Kit.section(c, "电脑连接"))
        val linkRows = arrayListOf<View>(Kit.row(c, "允许我的电脑发任务", trailing = Kit.toggle(c, state.linkOn, state.onLink)))
        if (state.linkOn) {
            linkRows.add(
                Kit.row(
                    c, "地址",
                    trailing = Kit.value(c, state.linkAddress ?: "无 Wi-Fi", withChevron = false),
                    // Tapping copies the full address, so it can be pasted into a browser, a config file or a terminal.
                    onClick = if (state.linkAddress != null) state.onCopyAddress else null,
                ),
            )
            linkRows.add(Kit.row(c, "复制连接命令", trailing = Kit.chevron(c), onClick = state.onCopyLink))
            linkRows.add(Kit.row(c, "重置配对码", trailing = Kit.chevron(c), onClick = state.onResetLink))
        }
        p.addView(Kit.group(c, linkRows, 16), Kit.lp())
        p.addView(Kit.footnote(c, "电脑上的 Claude Code、Cursor 等 MCP 应用可以请这台手机做事，但只能在同一个 Wi-Fi 下。每个任务都要先在手机上经你批准，涉及发送、通话或付款的操作还会再确认一次。"))
        p.addView(gap(c, 26))

        p.addView(Kit.section(c, "实验性功能"))
        p.addView(Kit.group(c, listOf(
            Kit.row(c, "轻量模式", trailing = Kit.toggle(c, state.shortScreensOn, state.onShortScreens)),
        ), 16), Kit.lp())
        p.addView(Kit.footnote(c, "每一步少给 AI 一些信息，任务能更快。如果任务开始犯迷糊，就关掉它。"))
        p.addView(gap(c, 26))

        p.addView(Kit.section(c, "外观"))
        val modes = listOf("system", "light", "dark")
        val appearance = Kit.vstack(c).apply { setPadding(dp(c, 14), dp(c, 14), dp(c, 14), dp(c, 14)) }
        appearance.addView(
            Kit.segmented(c, listOf("跟随系统", "浅色", "深色"), modes.indexOf(state.theme).coerceAtLeast(0)) { i -> state.onTheme(modes[i]) },
            Kit.lp(),
        )
        appearance.addView(
            Kit.text(c, "跟随系统会使用手机的浅色或深色设置。", 13f, 400, R.color.pa_muted).apply { setPadding(dp(c, 2), dp(c, 10), 0, 0) },
            Kit.lp(),
        )
        p.addView(Kit.group(c, listOf(appearance), 16), Kit.lp())
        p.addView(gap(c, 26))

        p.addView(Kit.section(c, "安全与隐私"))
        val safety = ArrayList<View>()
        safety.add(Kit.row(c, "永不触碰的应用", "银行、支付、密码", colorTile(c, R.drawable.ic_shield, R.color.pa_t_green), Kit.chevron(c), 64) { nav.push("blocked") })
        safety.add(Kit.row(c, "危险操作前询问", leading = colorTile(c, R.drawable.ic_check, R.color.pa_t_green), trailing = Kit.value(c, "始终"), onClick = state.onRiskyInfo))
        if (state.showHistory) {
            safety.add(Kit.row(c, "保留历史记录", leading = colorTile(c, R.drawable.ic_activity, R.color.pa_t_indigo), trailing = Kit.toggle(c, state.historyOn, state.onHistory)))
            safety.add(Kit.row(c, "清除历史记录",
                leading = Kit.tile(c, R.drawable.ic_trash, color(c, R.color.pa_bad) and 0x00FFFFFF or 0x24000000, color(c, R.color.pa_bad), 30),
                titleTint = R.color.pa_bad, onClick = state.onClearHistory))
        }
        p.addView(Kit.group(c, safety, 60), Kit.lp())
        p.addView(Kit.footnote(c, "发送的内容：你屏幕上的文字，只发给你的服务商。屏幕内容不会被保存。"))
        p.addView(gap(c, 26))

        p.addView(Kit.section(c, "账户"))
        p.addView(settingsGroup(c,
            Kit.row(
                c, "账户余额",
                trailing = Kit.value(c, state.balance ?: "点一下查询"),
                onClick = state.onRefreshBalance,
            ),
            Kit.row(
                c, "余额提醒",
                trailing = Kit.value(c, if (state.balanceAlert > 0) "低于 ¥" + state.balanceAlert else "已关闭"),
                onClick = state.onBalanceAlert,
            ),
        ), Kit.lp())
        p.addView(Kit.footnote(c, "余额从 DeepSeek 平台读取，只存在这台手机上。低于你设的金额时，首页会提醒。"))
        p.addView(gap(c, 26))

        p.addView(Kit.section(c, "关于"))
        p.addView(settingsGroup(c,
            Kit.row(c, "隐私说明", leading = colorTile(c, R.drawable.ic_doc, R.color.pa_t_gray), trailing = Kit.chevron(c)) { nav.push("licences") },
        ), Kit.lp())
        p.addView(Kit.footnote(c, "屏幕内容不落地；任务记录只存在这台手机上。").apply { gravity = Gravity.CENTER })
        return withTabs(c, p, 2, nav)
    }

    // ----------------------------------------------------- Welcome and notice

    fun welcome(c: Context, nav: Nav = Nav()): View {
        val p = Kit.vstack(c).apply { setPadding(dp(c, 28), dp(c, 56), dp(c, 28), dp(c, 24)) }
        val blocks = ArrayList<View>()
        val logo = ImageView(c).apply {
            setImageResource(R.drawable.logo_mark)
            elevation = dpf(c, 10)
            background = Kit.rounded(0x00000000, dpf(c, 26))
            outlineProvider = android.view.ViewOutlineProvider.BACKGROUND
        }
        p.addView(logo, Kit.lp(dp(c, 104), dp(c, 104)).apply { gravity = Gravity.CENTER_HORIZONTAL })
        blocks.add(logo)
        val title = Kit.text(c, "欢迎使用\ndsh", 30f, 600).apply {
            gravity = Gravity.CENTER; letterSpacing = -0.025f; setLineSpacing(dpf(c, 2), 1f)
        }
        p.addView(title, Kit.lp().apply { topMargin = dp(c, 28) })
        blocks.add(title)

        val feats = Kit.vstack(c, 26).apply { setPadding(0, dp(c, 40), 0, 0) }
        listOf(
            Triple(R.drawable.ic_mic, "说一句就行", "打字或说话交代任务，dsh 会打开应用、替你点完。"),
            Triple(R.drawable.ic_shield, "始终由你掌控", "凡是发送、付款、发布或删除，它都会先问过你。在任务页点「停止」就能让它立刻停下。"),
            Triple(R.drawable.ic_key, "你的 AI，你的密钥", "用你已经在付费的服务，或者直接登录 DeepSeek 账号。没有 dsh 自己的服务器。"),
        ).forEach { (ic, t, s) ->
            val r = Kit.hstack(c, 18).apply { gravity = Gravity.TOP }
            r.addView(Kit.icon(c, ic, 32, R.color.pa_icon).apply { setPadding(0, dp(c, 2), 0, 0) })
            val col = Kit.vstack(c)
            col.addView(Kit.text(c, t, 17f, 600))
            col.addView(Kit.text(c, s, 15f, 400, R.color.pa_muted).apply { setPadding(0, dp(c, 3), 0, 0); setLineSpacing(dpf(c, 3), 1f) })
            r.addView(col, Kit.weight())
            feats.addView(r, Kit.lp())
            blocks.add(r)
        }
        p.addView(feats, Kit.lp())
        p.post { Kit.reveal(blocks, 70) }

        val actions = Kit.vstack(c)
        actions.addView(Kit.text(c, "你的数据如何处理", 15f, 500, R.color.pa_blue).apply {
            gravity = Gravity.CENTER; setPadding(0, dp(c, 4), 0, dp(c, 14))
        }, Kit.lp())
        actions.addView(Kit.primary(c, "继续", 56) { nav.push("notice") }, Kit.lp())
        return withActions(c, p, actions)
    }

    fun notice(c: Context, nav: Nav = Nav(), onAccept: () -> Unit = {}): View {
        val p = Kit.vstack(c).apply { setPadding(dp(c, 28), dp(c, 48), dp(c, 28), dp(c, 24)) }
        val badge = FrameLayout(c).apply {
            background = Kit.rounded(color(c, R.color.pa_tile), dpf(c, 20))
            addView(Kit.icon(c, R.drawable.ic_shield, 32, R.color.pa_tile_icon), FrameLayout.LayoutParams(dp(c, 32), dp(c, 32), Gravity.CENTER))
        }
        p.addView(badge, Kit.lp(dp(c, 64), dp(c, 64)).apply { gravity = Gravity.CENTER_HORIZONTAL })
        p.addView(Kit.text(c, "开始之前", 28f, 600).apply { gravity = Gravity.CENTER; letterSpacing = -0.02f },
            Kit.lp().apply { topMargin = dp(c, 22) })
        p.addView(Kit.text(c, "四件值得知道的事，用大白话说。", 16f, 400, R.color.pa_muted).apply { gravity = Gravity.CENTER },
            Kit.lp().apply { topMargin = dp(c, 10) })

        val card = Kit.card(c, 22, 18)
        listOf(
            "直接发送给你的 AI 服务。" to "你屏幕上的文字，必要时加上截图，不会发往别处。",
            "屏幕内容不会被保存。" to "任务结束后，只保留你输入的任务和执行结果。",
            "它会先问过你。" to "任何发送、付款、发布或删除都需要你同意，银行和密码类应用一律跳过。",
            "随时可以叫停。" to "在任务页点「停止」，它立刻停下。",
        ).forEachIndexed { i, (b, rest) ->
            val r = Kit.hstack(c, 14).apply { gravity = Gravity.TOP; setPadding(0, dp(c, 14), 0, dp(c, 14)) }
            r.addView(Kit.text(c, "${i + 1}", 14f, 600, R.color.pa_on_tile).apply {
                gravity = Gravity.CENTER
                background = Kit.rounded(color(c, R.color.pa_tile), dpf(c, 14))
                layoutParams = LinearLayout.LayoutParams(dp(c, 28), dp(c, 28))
            })
            val col = Kit.vstack(c)
            col.addView(Kit.text(c, b, 15f, 600))
            col.addView(Kit.text(c, rest, 15f, 400, R.color.pa_muted).apply { setLineSpacing(dpf(c, 3), 1f); setPadding(0, dp(c, 3), 0, 0) })
            r.addView(col, Kit.weight())
            card.addView(r, Kit.lp())
            if (i < 3) card.addView(View(c).apply { setBackgroundColor(color(c, R.color.pa_line)) }, Kit.lp(h = 1))
        }
        p.addView(card, Kit.lp().apply { topMargin = dp(c, 28) })

        val actions = Kit.vstack(c)
        actions.addView(Kit.primary(c, "我明白了", 56, onAccept), Kit.lp())
        actions.addView(Kit.text(c, "返回", 16f, 500, R.color.pa_blue).apply {
            gravity = Gravity.CENTER; setPadding(0, dp(c, 14), 0, dp(c, 4))
            Kit.pressable(this, 1f) { nav.back() }
        }, Kit.lp())
        return withActions(c, p, actions)
    }
}
