package dev.pocketagent.ui

import android.Manifest
import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.InputFilter
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.WindowInsets
import android.view.WindowManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import dev.pocketagent.R
import dev.pocketagent.android.PocketAccessibilityService
import dev.pocketagent.android.PocketNotificationListener
import dev.pocketagent.android.SetupStores
import dev.pocketagent.auth.DeepSeekAccountLogin
import dev.pocketagent.llm.AUTH_HEADER_API_KEY
import dev.pocketagent.llm.AUTH_HEADER_DSH
import dev.pocketagent.llm.CheckResult
import dev.pocketagent.llm.HttpCaller
import dev.pocketagent.llm.OkHttpTransport
import dev.pocketagent.llm.ProviderCheck
import dev.pocketagent.llm.ProviderConfig
import dev.pocketagent.llm.ProviderKind
import dev.pocketagent.llm.ProviderPreset
import dev.pocketagent.llm.ProviderPresets
import dev.pocketagent.llm.Providers
import dev.pocketagent.setup.AccessibilityState
import dev.pocketagent.setup.CheckMessages
import dev.pocketagent.setup.GuidanceStore
import dev.pocketagent.setup.MicState
import dev.pocketagent.setup.SaveResult
import dev.pocketagent.setup.SetupFacts
import dev.pocketagent.setup.SetupModel
import dev.pocketagent.setup.SetupStep
import dev.pocketagent.setup.StepStatus
import dev.pocketagent.setup.isListenerEnabled
import dev.pocketagent.ui.kit.Kit
import dev.pocketagent.ui.kit.Kit.color
import dev.pocketagent.ui.kit.Kit.dp
import dev.pocketagent.ui.kit.Kit.dpf
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

/**
 * The setup checklist (S4 spec §5): provider and key, model test, accessibility, microphone, bubble and a first task.
 * It gathers plain facts, hands them to [SetupModel], and draws one section per step with the same kit as the rest of
 * the app. The whole screen is secure so the key field cannot be captured by a screenshot or screen recording.
 */
class SetupActivity : ThemedActivity() {
    private val repository by lazy { SetupStores.repository(this) }
    private val settings by lazy { SetupStores.settings(this) }
    private val guidance by lazy { loadGuidance() }

    private lateinit var column: LinearLayout
    private var scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var checkJob: Job? = null

    // Drafts survive a redraw. The key draft is only what the user typed; the stored key is never put in a view.
    private var presetId: String? = null
    private var customUrlDraft = "https://"
    private var keyDraft = ""
    private var replacingKey = false
    private var modelDraft: String? = null
    private var imagesDraft: Boolean? = null
    private var testTaskDraft = TEST_TASK_TEXT
    private var expandedOverride: SetupStep? = null
    private var checkMessage: String? = null
    private var checking = false
    private var providerError: String? = null
    private var pendingHost: String? = null
    private var micAskedAt = 0L
    private var accountBusy = false
    private var accountError: String? = null
    private var accountJob: Job? = null
    private var accountPending: DeepSeekAccountLogin.Pending? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Release builds block screenshots of this screen (the key field). Debug builds allow them so the design can be checked.
        if (applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE == 0) {
            window.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)
        }
        window.setDecorFitsSystemWindows(false)
        column = Kit.vstack(this)
        val scroll = Kit.scroll(this, column)
        scroll.setOnApplyWindowInsetsListener { v, insets ->
            val bars = insets.getInsets(WindowInsets.Type.systemBars())
            val ime = insets.getInsets(WindowInsets.Type.ime())
            v.setPadding(0, bars.top, 0, maxOf(bars.bottom, ime.bottom))
            insets
        }
        setContentView(FrameLayout(this).apply {
            setBackgroundColor(color(this@SetupActivity, R.color.pa_bg))
            addView(scroll, FrameLayout.LayoutParams(-1, -1))
        })
        // Opened from Settings just to ask for the microphone: ask straight away, then go back.
        if (savedInstanceState == null && intent.getBooleanExtra(EXTRA_REQUEST_MIC, false)) {
            micAskedAt = System.currentTimeMillis()
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), REQUEST_MIC)
        }
    }

    override fun onResume() {
        super.onResume()
        if (!scope.isActive()) scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        render()
    }

    override fun onStop() {
        // Leaving the screen cancels a running check; its result is dropped. A sign-in keeps going: the browser
        // covers this screen, and cancelling here would throw away a token the user just approved.
        checkJob?.cancel()
        checkJob = null
        checking = false
        if (!accountBusy) scope.cancel()
        super.onStop()
    }

    private fun CoroutineScope.isActive(): Boolean = coroutineContext[Job]?.isActive == true

    // --- Facts ---

    private fun accessibilityOn(): Boolean = AccessibilityState.isEnabled(
        Settings.Secure.getString(contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES),
        packageName,
        PocketAccessibilityService::class.java.name,
    )

    private fun notificationsOn(): Boolean = isListenerEnabled(
        Settings.Secure.getString(contentResolver, "enabled_notification_listeners"),
        ComponentName(this, PocketNotificationListener::class.java).flattenToString(),
    )

    private fun micState(): MicState = when {
        checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED -> MicState.GRANTED
        settings.microphoneAsked -> MicState.DENIED
        else -> MicState.NOT_ASKED
    }

    private fun facts(config: ProviderConfig?): SetupFacts = SetupFacts(
        noticeAccepted = settings.noticeAccepted,
        acceptedCustomHost = settings.acceptedCustomHost,
        config = config,
        modelCheck = if (repository.modelCheckPassed && config != null) CheckResult.Works else null,
        accessibilityOn = accessibilityOn(),
        microphone = micState(),
        testTaskDone = repository.testTaskDone,
        notificationsOn = notificationsOn(),
    )

    // --- Drawing ---

    private fun render() {
        val config = repository.load()
        val eval = SetupModel.evaluate(facts(config))
        column.removeAllViews()
        val c = this

        // header: back, title, progress
        val back = Kit.hstack(c, 2).apply { setPadding(dp(c, 14), dp(c, 14), dp(c, 14), dp(c, 6)) }
        back.addView(Kit.icon(c, R.drawable.ic_back, 22, R.color.pa_blue))
        back.addView(Kit.text(c, "返回", 17f, 400, R.color.pa_blue))
        Kit.pressable(back, 1f) { finish() }
        column.addView(back, Kit.lp(FrameLayout.LayoutParams.WRAP_CONTENT))
        val required = eval.steps.filter { it.step != SetupStep.MICROPHONE && it.step != SetupStep.NOTIFICATIONS }
        val done = required.count { it.status == StepStatus.DONE }
        val head = Kit.vstack(c).apply { setPadding(dp(c, 22), dp(c, 4), dp(c, 22), dp(c, 18)) }
        head.addView(Kit.largeTitle(c, if (eval.isReady) "全部就绪" else "设置"))
        head.addView(
            Kit.text(
                c,
                if (eval.isReady) "全部就绪。点任一步的“编辑”即可修改。" else "已完成 $done / ${required.size} 步",
                15f, 400, R.color.pa_muted,
            ).apply { setPadding(0, dp(c, 6), 0, 0) },
        )
        column.addView(head, Kit.lp())

        val list = Kit.vstack(c).apply {
            background = Kit.rounded(color(c, R.color.pa_card), dpf(c, 20))
            elevation = dpf(c, 1)
        }
        val visible = eval.steps.filter { !(it.step == SetupStep.NOTICE && it.status == StepStatus.DONE) }
        visible.forEachIndexed { i, state ->
            val expanded = !eval.isReady && state.status == StepStatus.CURRENT ||
                expandedOverride == state.step ||
                (state.step == SetupStep.MICROPHONE && state.status != StepStatus.DONE && !settings.microphoneAsked &&
                    eval.of(SetupStep.ACCESSIBILITY).status == StepStatus.DONE && !eval.isReady) ||
                // optional and never CURRENT, so it opens on its own once accessibility is on, and collapses once setup is ready
                (state.step == SetupStep.NOTIFICATIONS && state.status != StepStatus.DONE && !eval.isReady &&
                    eval.of(SetupStep.ACCESSIBILITY).status == StepStatus.DONE)
            if (i > 0) list.addView(View(c).apply { setBackgroundColor(color(c, R.color.pa_line)) }, Kit.lp(h = 1).apply { marginStart = dp(c, 58) })

            val header = Kit.hstack(c, 14).apply { minimumHeight = dp(c, 64); setPadding(dp(c, 16), 0, dp(c, 16), 0) }
            header.addView(mark(state.status))
            val titles = Kit.vstack(c)
            titles.addView(
                Kit.text(
                    c, titleFor(state.step), 17f, if (state.status == StepStatus.TODO) 400 else 500,
                    if (state.status == StepStatus.TODO) R.color.pa_muted else R.color.pa_ink,
                ),
            )
            val summary = when {
                state.status == StepStatus.DONE -> summaryFor(state.step, config)
                state.step == SetupStep.MICROPHONE && settings.microphoneAsked -> "已跳过，语音输入未开启"
                state.needsFirst != null && !expanded -> "请先完成“${titleFor(state.needsFirst!!)}”"
                else -> null
            }
            if (summary != null) titles.addView(Kit.text(c, summary, 13f, 400, R.color.pa_muted).apply { setPadding(0, dp(c, 2), 0, 0) })
            header.addView(titles, Kit.weight())
            if (!expanded && state.status == StepStatus.DONE && state.step != SetupStep.NOTICE) {
                header.addView(Kit.text(c, "编辑", 16f, 500, R.color.pa_blue).apply { setPadding(dp(c, 10), dp(c, 12), 0, dp(c, 12)) })
                Kit.pressable(header, 1f) {
                    expandedOverride = state.step
                    if (state.step == SetupStep.PROVIDER) replacingKey = false
                    render()
                }
            }
            list.addView(header, Kit.lp())

            if (expanded) {
                val body = Kit.vstack(c).apply { setPadding(dp(c, 16), 0, dp(c, 16), dp(c, 18)) }
                fillBody(body, state.step, config, state.needsFirst)
                list.addView(body, Kit.lp())
            }
        }
        column.addView(list, Kit.lp().apply { marginStart = dp(c, 16); marginEnd = dp(c, 16); bottomMargin = dp(c, 24) })
        Kit.reveal(listOf(head, list), 70)
    }

    private fun mark(status: StepStatus): View {
        val c = this
        val size = dp(c, 28)
        return FrameLayout(c).apply {
            layoutParams = LinearLayout.LayoutParams(size, size)
            background = when (status) {
                StepStatus.DONE -> Kit.rounded(color(c, R.color.pa_blue), dpf(c, 14))
                StepStatus.CURRENT -> Kit.rounded(color(c, R.color.pa_card), dpf(c, 14), color(c, R.color.pa_blue), dp(c, 2))
                StepStatus.TODO -> Kit.rounded(color(c, R.color.pa_card), dpf(c, 14), color(c, R.color.pa_line), dp(c, 2))
            }
            if (status == StepStatus.DONE) {
                addView(Kit.icon(c, R.drawable.ic_check, 16, R.color.pa_on_blue), FrameLayout.LayoutParams(dp(c, 16), dp(c, 16), Gravity.CENTER))
            } else if (status == StepStatus.CURRENT) {
                addView(
                    View(c).apply { background = Kit.rounded(color(c, R.color.pa_accent), dpf(c, 5)) },
                    FrameLayout.LayoutParams(dp(c, 10), dp(c, 10), Gravity.CENTER),
                )
            }
        }
    }

    private fun titleFor(step: SetupStep): String = when (step) {
        SetupStep.NOTICE -> "条款须知"
        SetupStep.PROVIDER -> "AI 服务商与密钥"
        SetupStep.MODEL_CHECK -> "模型"
        SetupStep.ACCESSIBILITY -> "无障碍"
        SetupStep.MICROPHONE -> "麦克风（可选）"
        SetupStep.NOTIFICATIONS -> "通知（可选）"
        SetupStep.TEST_TASK -> "首个任务"
    }

    private fun summaryFor(step: SetupStep, config: ProviderConfig?): String? = when (step) {
        SetupStep.NOTICE -> "已了解"
        SetupStep.PROVIDER -> config?.let { "${it.host}，密钥已保存" }
        SetupStep.MODEL_CHECK -> config?.let { "${it.model} 支持工具调用" }
        SetupStep.ACCESSIBILITY -> "dsh 已开启"
        SetupStep.MICROPHONE -> "已允许使用麦克风"
        SetupStep.NOTIFICATIONS -> "已开启"
        SetupStep.TEST_TASK -> "首个任务已完成"
    }

    private fun fillBody(body: LinearLayout, step: SetupStep, config: ProviderConfig?, needsFirst: SetupStep?) {
        when (step) {
            SetupStep.NOTICE -> noticeBody(body)
            SetupStep.PROVIDER -> providerBody(body, config)
            SetupStep.MODEL_CHECK -> modelBody(body, config, needsFirst)
            SetupStep.ACCESSIBILITY -> accessibilityBody(body)
            SetupStep.MICROPHONE -> microphoneBody(body)
            SetupStep.NOTIFICATIONS -> notificationsBody(body)
            SetupStep.TEST_TASK -> testTaskBody(body, needsFirst)
        }
    }

    private fun para(body: LinearLayout, text: CharSequence, muted: Boolean = true, top: Int = 0) {
        body.addView(
            Kit.text(this, text, 15f, 400, if (muted) R.color.pa_muted else R.color.pa_ink).apply {
                setLineSpacing(dpf(this@SetupActivity, 3), 1f)
            },
            Kit.lp().apply { topMargin = dp(this@SetupActivity, top) },
        )
    }

    private fun fieldParams(top: Int = 12) = Kit.lp().apply { topMargin = dp(this@SetupActivity, top) }

    // --- Notice ---

    private fun noticeBody(body: LinearLayout) {
        para(body, getString(R.string.notice_body))
        body.addView(
            Kit.primary(this, getString(R.string.notice_accept)) {
                settings.noticeAccepted = true
                render()
            },
            fieldParams(16),
        )
    }

    // --- Provider ---

    private fun selectedPreset(config: ProviderConfig?): ProviderPreset? {
        val id = presetId
        if (id != null) return if (id == CUSTOM) null else ProviderPresets.byId(id)
        return config?.let { c -> ProviderPresets.all.firstOrNull { it.baseUrl == c.baseUrl } }
    }

    private fun isCustom(config: ProviderConfig?): Boolean =
        presetId == CUSTOM || (presetId == null && config != null && selectedPreset(config) == null)

    private fun providerBody(body: LinearLayout, config: ProviderConfig?) {
        val c = this
        val preset = selectedPreset(config)
        val custom = isCustom(config)

        para(body, "选择你正在用的 AI 服务。密钥会加密存在本机，只发送给该服务。")

        val chips = ProviderPresets.all.map { p ->
            Kit.chip(c, p.label, !custom && preset?.id == p.id).apply {
                setOnClickListener { captureDrafts(); presetId = p.id; imagesDraft = null; render() }
            }
        } + Kit.chip(c, "其他地址", custom).apply {
            setOnClickListener { captureDrafts(); presetId = CUSTOM; render() }
        }
        val grid = Kit.vstack(c, 8)
        chips.chunked(2).forEach { pair ->
            val row = Kit.hstack(c, 8)
            pair.forEach { chip ->
                chip.minimumHeight = dp(c, 44)
                row.addView(chip, Kit.weight())
            }
            if (pair.size == 1) row.addView(View(c), Kit.weight())
            grid.addView(row, Kit.lp())
        }
        body.addView(grid, fieldParams(14))

        var urlField: EditText? = null
        if (custom) {
            urlField = Kit.field(c, "https://llm.example.com/v1").apply {
                inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
                setText(config?.takeIf { presetId == null }?.baseUrl ?: customUrlDraft)
                tag = TAG_URL
            }
            body.addView(urlField, fieldParams())
        }

        val account = preset?.account == true
        val needsKey = preset?.needsKey ?: true
        var keyField: EditText? = null
        var keySaved = false
        if (account) {
            accountBody(body, config)
        } else {
            val field = Kit.field(c, if (needsKey) "API 密钥" else "API 密钥（本地服务无需填写）").apply {
                inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
                importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO
                setText(keyDraft)
                tag = TAG_KEY
            }
            keyField = field
            keySaved = config != null && config.apiKey.isNotEmpty() && !replacingKey &&
                config.authHeader == AUTH_HEADER_API_KEY
            if (keySaved) {
                val saved = Kit.hstack(c).apply { minimumHeight = dp(c, 50) }
                saved.addView(
                    Kit.icon(c, R.drawable.ic_lock, 18, R.color.pa_muted).apply {
                        layoutParams = LinearLayout.LayoutParams(dp(c, 18), dp(c, 18)).apply { marginEnd = dp(c, 10) }
                    },
                )
                saved.addView(Kit.text(c, "密钥已保存", 16f, 500), Kit.weight())
                saved.addView(
                    Kit.text(c, "更换", 16f, 500, R.color.pa_blue).apply {
                        setPadding(dp(c, 10), dp(c, 10), 0, dp(c, 10))
                        Kit.pressable(this, 1f) { captureDrafts(); replacingKey = true; keyDraft = ""; render() }
                    },
                )
                body.addView(saved, fieldParams(8))
            } else {
                body.addView(field, fieldParams())
            }
        }

        providerError?.let { err ->
            body.addView(Kit.text(c, err, 14f, 400, R.color.pa_bad).apply { setLineSpacing(dpf(c, 3), 1f) }, fieldParams(10))
        }

        body.addView(
            Kit.primary(c, "保存") {
                val kind = preset?.kind ?: ProviderKind.OPENAI_COMPAT
                val baseUrl = (urlField?.text?.toString()?.trim()) ?: preset?.baseUrl.orEmpty()
                val typed = keyField?.text?.toString()?.trim().orEmpty()
                val keyText = when {
                    account -> config?.takeIf { it.authHeader == AUTH_HEADER_DSH }?.apiKey.orEmpty()
                    keySaved -> config?.apiKey.orEmpty()
                    else -> typed
                }
                if (account && keyText.isEmpty()) {
                    providerError = "请先用 DeepSeek 账号登录。"
                    render()
                    return@primary
                }
                val images = imagesDraft ?: config?.supportsImages ?: preset?.imagesByDefault ?: false
                val draft = ProviderConfig(
                    kind = kind, baseUrl = baseUrl, apiKey = keyText,
                    model = config?.model ?: modelDraft ?: "",
                    supportsImages = images,
                    effort = preset?.effort,
                    authHeader = if (account) AUTH_HEADER_DSH else AUTH_HEADER_API_KEY,
                )
                val check = draft.copy(model = draft.model.ifBlank { "model" }).validate()
                if (!check.ok) {
                    providerError = check.errors.joinToString(" ")
                    keyDraft = typed
                    if (urlField != null) customUrlDraft = baseUrl
                    render()
                    return@primary
                }
                if (SetupModel.needsHostWarning(draft) && settings.acceptedCustomHost != draft.host) {
                    // Ask for the real host first; the user saves again after accepting.
                    pendingHost = draft.host
                    keyDraft = typed
                    customUrlDraft = baseUrl
                    providerError = getString(R.string.notice_custom_host, draft.host)
                    render()
                    return@primary
                }
                when (val result = repository.save(draft)) {
                    SaveResult.Saved -> {
                        providerError = null
                        keyDraft = ""
                        replacingKey = false
                        expandedOverride = null
                        pendingHost = null
                        checkMessage = null
                    }
                    is SaveResult.Failed -> providerError = result.reason
                }
                render()
            },
            fieldParams(16),
        )

        val host = pendingHost
        if (host != null) {
            body.addView(
                Kit.secondary(c, "我信任 $host") {
                    settings.acceptedCustomHost = host
                    pendingHost = null
                    providerError = null
                    render()
                },
                fieldParams(10),
            )
        }
    }

    /**
     * The DeepSeek account path: no key field at all. Signing in stores an account token through the same
     * encrypted repository, so the model test and the agent run exactly as they do for a key provider.
     */
    private fun accountBody(body: LinearLayout, config: ProviderConfig?) {
        val c = this
        val signedIn = config != null && config.authHeader == AUTH_HEADER_DSH && config.apiKey.isNotEmpty()

        when {
            accountBusy -> {
                val row = Kit.hstack(c, 12).apply { minimumHeight = dp(c, 52) }
                row.addView(ProgressBar(c), LinearLayout.LayoutParams(dp(c, 28), dp(c, 28)))
                row.addView(Kit.text(c, "在浏览器中完成登录，然后回到这里。", 15f, 400, R.color.pa_muted), Kit.weight())
                row.addView(
                    Kit.text(c, "取消", 16f, 500, R.color.pa_blue).apply {
                        setPadding(dp(c, 10), dp(c, 10), 0, dp(c, 10))
                        Kit.pressable(this, 1f) {
                            accountJob?.cancel(); accountJob = null
                            accountPending?.close(); accountPending = null
                            accountBusy = false
                            accountError = null
                            render()
                        }
                    },
                )
                body.addView(row, fieldParams(10))
            }

            signedIn -> {
                val row = Kit.hstack(c).apply { minimumHeight = dp(c, 50) }
                row.addView(
                    Kit.icon(c, R.drawable.ic_lock, 18, R.color.pa_muted).apply {
                        layoutParams = LinearLayout.LayoutParams(dp(c, 18), dp(c, 18)).apply { marginEnd = dp(c, 10) }
                    },
                )
                row.addView(Kit.text(c, "已用 DeepSeek 账号登录", 16f, 500), Kit.weight())
                row.addView(
                    Kit.text(c, "退出登录", 16f, 500, R.color.pa_blue).apply {
                        setPadding(dp(c, 10), dp(c, 10), 0, dp(c, 10))
                        Kit.pressable(this, 1f) {
                            repository.clear()
                            accountError = null
                            providerError = null
                            checkMessage = null
                            render()
                        }
                    },
                )
                body.addView(row, fieldParams(8))
            }

            else -> {
                para(
                    body,
                    "用你在这个手机上用的 DeepSeek 账号登录。令牌会加密存在本机，" +
                        "只发送给 api.deepseek.com。",
                )
                body.addView(
                    Kit.primary(c, "用 DeepSeek 账号登录") { startAccountLogin() },
                    fieldParams(12),
                )
            }
        }

        accountError?.let { err ->
            body.addView(Kit.text(c, err, 14f, 400, R.color.pa_bad).apply { setLineSpacing(dpf(c, 3), 1f) }, fieldParams(10))
        }
    }

    /**
     * A sign-in failure a person can act on. Raw exception text ("Unable to resolve host …") is
     * only useful in logs, so network failures get a plain sentence instead.
     */
    private fun loginError(e: Exception): String = when (e) {
        is UnknownHostException, is ConnectException, is SocketTimeoutException ->
            "连不上 DeepSeek 的登录服务器，检查一下网络再试。"
        is IOException -> "登录过程中网络断了，请再试一次。"
        else -> e.message?.takeIf { msg -> msg.any { it in '\u4e00'..'\u9fff' } }
            ?: "登录没能完成，请再试一次。"
    }

    private fun startAccountLogin() {
        if (accountBusy) return
        accountBusy = true
        accountError = null
        providerError = null
        render()
        accountJob?.cancel()
        accountJob = scope.launch {
            val pending = try {
                withContext(Dispatchers.IO) {
                    DeepSeekAccountLogin.prepare(appVersion(), accountLocale(), deviceInfo())
                }
            } catch (e: Exception) {
                accountBusy = false
                accountError = loginError(e)
                render()
                return@launch
            }
            accountPending = pending
            val opened = try {
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(pending.authorizeUrl)))
                true
            } catch (e: ActivityNotFoundException) {
                false
            }
            if (!opened) {
                pending.close()
                accountPending = null
                accountBusy = false
                accountError = "没有找到能完成登录的浏览器。"
                render()
                return@launch
            }
            val token = try {
                withContext(Dispatchers.IO) { pending.awaitToken() }
            } catch (e: Exception) {
                accountPending = null
                accountBusy = false
                accountError = loginError(e)
                render()
                return@launch
            }
            accountPending = null
            val previous = repository.load()
            val model = previous?.takeIf { it.authHeader == AUTH_HEADER_DSH }?.model
                ?.takeIf { it.isNotBlank() }
                ?: DeepSeekAccountLogin.DEFAULT_MODEL
            val config = ProviderConfig(
                kind = ProviderKind.ANTHROPIC,
                baseUrl = DeepSeekAccountLogin.BASE_URL,
                apiKey = token,
                model = model,
                supportsImages = true,
                contextTokens = DeepSeekAccountLogin.CONTEXT_TOKENS,
                authHeader = AUTH_HEADER_DSH,
            )
            when (val saved = repository.save(config)) {
                SaveResult.Saved -> {
                    presetId = "deepseek-account"
                    accountBusy = false
                    accountError = null
                    checkMessage = null
                    expandedOverride = null
                }
                is SaveResult.Failed -> {
                    accountBusy = false
                    accountError = saved.reason
                }
            }
            render()
        }
    }

    private fun appVersion(): String = try {
        packageManager.getPackageInfo(packageName, 0).versionName.orEmpty().ifEmpty { "0.0.0" }
    } catch (_: Exception) {
        "0.0.0"
    }

    private fun accountLocale(): String = if (java.util.Locale.getDefault().language == "zh") "zh_CN" else "en_US"

    private fun deviceInfo(): DeepSeekAccountLogin.Device {
        val prefs = getSharedPreferences("dsh-account", MODE_PRIVATE)
        val existing = prefs.getString("device_id", null)
        val deviceId = if (existing.isNullOrEmpty()) {
            java.util.UUID.randomUUID().toString().also { prefs.edit().putString("device_id", it).apply() }
        } else {
            existing
        }
        return DeepSeekAccountLogin.Device(deviceId, Build.MODEL ?: "android", "Android ${Build.VERSION.RELEASE}")
    }

    /** Copies what was typed into the drafts, so a redraw does not lose it. */
    private fun captureDrafts() {
        findFields(column).forEach { f ->
            when (f.tag) {
                TAG_URL -> customUrlDraft = f.text.toString()
                TAG_KEY -> keyDraft = f.text.toString()
                TAG_MODEL -> modelDraft = f.text.toString()
            }
        }
    }

    private fun findFields(v: View): List<EditText> = when (v) {
        is EditText -> listOf(v)
        is android.view.ViewGroup -> (0 until v.childCount).flatMap { findFields(v.getChildAt(it)) }
        else -> emptyList()
    }

    // --- Model ---

    private fun modelBody(body: LinearLayout, config: ProviderConfig?, needsFirst: SetupStep?) {
        val c = this
        if (config == null || needsFirst != null) {
            para(body, "请先完成“${titleFor(SetupStep.PROVIDER)}”。")
            return
        }
        para(body, "请完全按服务商的写法填写模型名。我们会发一个小请求确认可用。")
        val modelField = Kit.field(c, "模型名称").apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            filters = arrayOf(InputFilter.LengthFilter(100))
            setText(modelDraft ?: config.model)
            tag = TAG_MODEL
        }
        body.addView(modelField, fieldParams(14))

        val imagesRow = Kit.hstack(c).apply { minimumHeight = dp(c, 52) }
        imagesRow.addView(Kit.text(c, "可读取截图", 16f, 400), Kit.weight())
        imagesRow.addView(Kit.toggle(c, imagesDraft ?: config.supportsImages) { imagesDraft = it })
        body.addView(imagesRow, fieldParams(4))

        checkMessage?.let { msg ->
            body.addView(Kit.text(c, msg, 14f, 400, R.color.pa_ink).apply { setLineSpacing(dpf(c, 3), 1f) }, fieldParams(6))
        }

        if (checking) {
            val running = Kit.hstack(c, 12)
            running.addView(ProgressBar(c), LinearLayout.LayoutParams(dp(c, 28), dp(c, 28)))
            running.addView(Kit.text(c, "测试中…", 15f, 400, R.color.pa_muted), Kit.weight())
            running.addView(
                Kit.text(c, "取消", 16f, 500, R.color.pa_blue).apply {
                    setPadding(dp(c, 10), dp(c, 10), 0, dp(c, 10))
                    Kit.pressable(this, 1f) {
                        checkJob?.cancel(); checkJob = null; checking = false; checkMessage = null; render()
                    }
                },
            )
            body.addView(running, fieldParams(10))
        }

        val test = Kit.primary(c, "测试模型") {
            val model = modelField.text.toString().trim()
            modelDraft = model
            runCheck(config, model, imagesDraft ?: config.supportsImages)
        }
        fun refresh() {
            val on = !checking && modelField.text.toString().isNotBlank()
            test.isEnabled = on
            test.alpha = if (on) 1f else 0.4f
        }
        refresh()
        modelField.addTextChangedListener(object : android.text.TextWatcher {
            override fun afterTextChanged(s: android.text.Editable?) = refresh()
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
        })
        body.addView(test, fieldParams(14))
    }

    private fun runCheck(config: ProviderConfig, model: String, images: Boolean) {
        val updated = config.copy(model = model, supportsImages = images)
        if (!updated.validate().ok) {
            checkMessage = updated.validate().errors.joinToString(" ")
            render()
            return
        }
        when (val saved = repository.save(updated)) {
            is SaveResult.Failed -> {
                checkMessage = saved.reason
                render()
                return
            }
            SaveResult.Saved -> Unit
        }
        checking = true
        checkMessage = null
        render()
        checkJob?.cancel()
        checkJob = scope.launch {
            val result = try {
                val client = Providers.create(updated, HttpCaller(OkHttpTransport()))
                ProviderCheck.run(client)
            } catch (e: IllegalArgumentException) {
                CheckResult.Other(e.message.orEmpty())
            }
            checking = false
            checkMessage = CheckMessages.messageFor(result)
            if (result == CheckResult.Works) repository.markModelCheckPassed()
            render()
        }
    }

    // --- Accessibility ---

    private fun accessibilityBody(body: LinearLayout) {
        val c = this
        para(body, "在无障碍设置中开启 dsh，它就能在任务运行时读取屏幕并替你点击。")
        body.addView(
            Kit.primary(c, "打开无障碍设置") {
                openSettings(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            },
            fieldParams(14),
        )
        body.addView(Kit.text(c, "开关是灰的？", 16f, 600).apply { setPadding(0, dp(c, 22), 0, 0) }, Kit.lp())
        para(body, guidance.forManufacturer(Build.MANUFACTURER).body, top = 6)
        body.addView(
            Kit.secondary(c, "打开应用信息") {
                openSettings(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
            },
            fieldParams(12),
        )
    }

    private fun openSettings(intent: Intent) {
        try {
            startActivity(intent)
        } catch (e: ActivityNotFoundException) {
            // Some builds hide the screen; the guidance text above says where else to look.
        }
    }

    private fun loadGuidance(): GuidanceStore {
        val files = try {
            assets.list(GUIDANCE_DIR).orEmpty().filter { it.endsWith(".md") }.associateWith { name ->
                assets.open("$GUIDANCE_DIR/$name").use { it.readBytes().toString(Charsets.UTF_8) }
            }
        } catch (e: IOException) {
            emptyMap()
        }
        return try {
            GuidanceStore.load(files)
        } catch (e: IllegalArgumentException) {
            GuidanceStore.load(mapOf("generic.md" to "如果开关是灰的，请打开 dsh 的应用信息，点右上角三个点，选择“允许受限设置”。"))
        }
    }

    // --- Notifications ---

    private fun notificationsBody(body: LinearLayout) {
        para(
            body,
            "让 dsh 读取通知，这样不用打开应用就能帮你了解消息并回复。" +
                "它只在你给它任务时读取，也从不保存。银行、支付类应用和银行短信" +
                "会被跳过，验证码也绝不会发送给 AI。",
        )
        body.addView(
            Kit.primary(this, "打开通知使用权") {
                openSettings(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
            },
            fieldParams(14),
        )
    }

    // --- Microphone ---

    private fun microphoneBody(body: LinearLayout) {
        para(body, getString(R.string.notice_mic))
        body.addView(
            Kit.primary(this, "允许使用麦克风") {
                requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), REQUEST_MIC)
            },
            fieldParams(14),
        )
        body.addView(
            Kit.secondary(this, "跳过") {
                settings.microphoneAsked = true
                render()
            },
            fieldParams(10),
        )
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_MIC) {
            settings.microphoneAsked = true
            val granted = grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED
            if (intent.getBooleanExtra(EXTRA_REQUEST_MIC, false)) {
                when {
                    granted -> android.widget.Toast.makeText(this, "麦克风已开启。", android.widget.Toast.LENGTH_SHORT).show()
                    // Answered at once with no dialog: Android has blocked the question, so the only way is the app's permission page.
                    System.currentTimeMillis() - micAskedAt < 600 -> {
                        android.widget.Toast.makeText(this, "请在“权限”中开启麦克风。", android.widget.Toast.LENGTH_LONG).show()
                        openSettings(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
                    }
                }
                finish()
            } else {
                render()
            }
        }
    }

    // --- First task ---

    private fun testTaskBody(body: LinearLayout, needsFirst: SetupStep?) {
        val c = this
        para(body, "跑个小任务，看看一切是否正常。文字可以自己改。")
        val field = Kit.field(c, "要我做点什么？").apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            setSingleLine(false)
            filters = arrayOf(InputFilter.LengthFilter(TaskInputActivity.MAX_TEXT))
            minLines = 2
            gravity = Gravity.TOP or Gravity.START
            setPadding(dp(c, 14), dp(c, 14), dp(c, 14), dp(c, 14))
            setText(testTaskDraft)
        }
        body.addView(field, fieldParams(14))
        if (needsFirst != null) para(body, "请先完成“${titleFor(needsFirst)}”。", top = 10)
        val host = TaskScreenHost.current
        if (needsFirst == null && host == null) para(body, "请在无障碍设置中开启 dsh，才能运行测试。", top = 10)
        val run = Kit.primary(c, "运行首个任务") {
            val text = field.text.toString().trim()
            testTaskDraft = text
            if (text.isEmpty()) {
                field.error = "请先输入任务"
                return@primary
            }
            TaskScreenHost.current?.startSetupTask(text)
        }
        val enabled = needsFirst == null && host != null
        run.isEnabled = enabled
        run.alpha = if (enabled) 1f else 0.4f
        body.addView(run, fieldParams(14))
    }

    companion object {
        const val EXTRA_REQUEST_MIC = "request_mic"
        const val TEST_TASK_TEXT = "看看我的主屏幕，说出上面三个应用的名字"
        private const val CUSTOM = "custom"
        private const val GUIDANCE_DIR = "guidance"
        private const val REQUEST_MIC = 41
        private const val TAG_URL = "url"
        private const val TAG_KEY = "key"
        private const val TAG_MODEL = "model"
    }
}
