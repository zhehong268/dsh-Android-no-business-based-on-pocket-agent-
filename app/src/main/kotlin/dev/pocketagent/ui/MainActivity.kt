package dev.pocketagent.ui

import android.annotation.SuppressLint
import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.ComponentName
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PersistableBundle
import android.provider.Settings
import android.view.View
import android.window.OnBackInvokedDispatcher
import android.view.WindowInsets
import android.widget.FrameLayout
import android.widget.Toast
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import dev.pocketagent.android.LocalAddress
import dev.pocketagent.android.PocketAccessibilityService
import dev.pocketagent.android.PocketNotificationListener
import dev.pocketagent.android.SetupStores
import dev.pocketagent.bridge.McpHttpServer
import dev.pocketagent.bridge.PairingToken
import dev.pocketagent.auth.DeepSeekAccountLogin
import dev.pocketagent.llm.AUTH_HEADER_DSH
import dev.pocketagent.llm.CheckResult
import dev.pocketagent.llm.ProviderConfig
import dev.pocketagent.llm.ProviderKind
import dev.pocketagent.setup.AccessibilityState
import dev.pocketagent.setup.MicState
import dev.pocketagent.setup.SetupFacts
import dev.pocketagent.setup.SetupModel
import dev.pocketagent.setup.SetupStep
import dev.pocketagent.setup.isListenerEnabled
import dev.pocketagent.android.SafFiles
import dev.pocketagent.setup.StepStatus
import dev.pocketagent.ui.kit.Attention
import dev.pocketagent.ui.kit.DefaultShortcuts
import dev.pocketagent.ui.kit.ThemeMode
import dev.pocketagent.ui.kit.HomeState
import dev.pocketagent.ui.kit.Nav
import dev.pocketagent.ui.kit.Screens
import dev.pocketagent.ui.kit.SettingsState
import dev.pocketagent.ui.kit.Shell

/**
 * The app: welcome on first run, then Home, Activity and Settings. Provider, key, model and the accessibility
 * steps still use the older [SetupActivity] until those cards are redrawn in the new style.
 */
class MainActivity : ThemedActivity() {
    private val repository by lazy { SetupStores.repository(this) }
    private val settings by lazy { SetupStores.settings(this) }
    private val history by lazy { SetupStores.history(this) }
    private var balanceJob: Job? = null

    private val balanceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private val files by lazy { SafFiles(this, SafFiles.store(this)) }
    private lateinit var content: FrameLayout
    private lateinit var shell: Shell
    private var editingShortcuts = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.setDecorFitsSystemWindows(false)
        val host = FrameLayout(this)
        content = FrameLayout(this)
        host.addView(content, FrameLayout.LayoutParams(-1, -1))
        host.setOnApplyWindowInsetsListener { _, insets ->
            val bars = insets.getInsets(WindowInsets.Type.systemBars())
            (content.layoutParams as FrameLayout.LayoutParams).apply { topMargin = bars.top; bottomMargin = bars.bottom }
            content.requestLayout()
            insets
        }
        setContentView(host)
        devSeed()
        shell = Shell(this, content, ::parentOf, ::build)
        val restored = savedInstanceState?.getString("screen")
        // A restored screen (after a theme change or rotation) comes back without animating and at the same scroll position.
        shell.reset(restored ?: if (settings.noticeAccepted) "home" else "welcome", settle = restored == null)
        savedInstanceState?.getInt("scrollY")?.let { shell.scrollTo(it) }
        askIfWaiting(intent)
        // Android 13 and later send the back gesture here and never call onBackPressed.
        if (Build.VERSION.SDK_INT >= 33) {
            onBackInvokedDispatcher.registerOnBackInvokedCallback(OnBackInvokedDispatcher.PRIORITY_DEFAULT) { goBack() }
        }
    }

    /**
     * A task started from the tile may outlive this screen, so the question arrives as a new intent instead of a
     * dialog that was already open. Asking here keeps the user inside the app: the old answer screen was a task of
     * its own, so closing it dropped them back into whatever app they were in.
     */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        askIfWaiting(intent)
    }

    private fun askIfWaiting(source: Intent?) {
        val question = source?.getStringExtra(EXTRA_QUESTION)?.takeIf { it.isNotBlank() } ?: return
        source.removeExtra(EXTRA_QUESTION)
        if (isFinishing || isDestroyed) return
        AnswerDialog.show(this, question) { answer -> TaskScreenHost.current?.completeAnswer(answer) }
    }

    /** Re-renders the open screen so a task that just finished shows up in Recent and Activity. */
    internal fun refreshIfResumed() {
        if (!resumedNow) return
        if (::shell.isInitialized && shell.current in setOf("home", "settings", "activity")) shell.refresh()
    }

    override fun onResume() {
        super.onResume()
        resumedNow = true
        live = this
        if (::shell.isInitialized && shell.current in setOf("home", "settings", "activity")) shell.refresh()
        refreshBalance(force = false)
    }

    /**
     * Asks the platform how much money is left. Cached for five minutes so switching tabs does not hammer the
     * API; taps in settings pass force. Never blocks the UI thread and stays silent when there is no account.
     */
    private fun refreshBalance(force: Boolean) {
        val config = repository.load() ?: return
        if (config.authHeader != dev.pocketagent.llm.AUTH_HEADER_DSH) return
        val age = System.currentTimeMillis() - settings.balanceAt
        if (!force && settings.balanceText != null && age < BALANCE_FRESH_MS) return
        if (balanceJob?.isActive == true) return
        balanceJob = balanceScope.launch {
            val result = withContext(Dispatchers.IO) { dev.pocketagent.android.BalanceApi.fetch(config.apiKey) }
            if (result is dev.pocketagent.android.BalanceApi.Result.Ok) {
                settings.balanceText = result.total
                settings.balanceAt = System.currentTimeMillis()
                if (::shell.isInitialized) shell.refresh()
            } else if (force) {
                val reason = (result as dev.pocketagent.android.BalanceApi.Result.Failed).reason
                Toast.makeText(this@MainActivity, reason, Toast.LENGTH_SHORT).show()
            }
        }
    }

    /** Cycles the warning line 关闭 → ¥5 → ¥10 → ¥20 → ¥50 → 关闭. */
    private fun cycleBalanceAlert() {
        val steps = listOf(0, 5, 10, 20, 50)
        val next = steps[(steps.indexOf(settings.balanceAlert).takeIf { it >= 0 }?.plus(1) ?: 2) % steps.size]
        settings.balanceAlert = next
        if (::shell.isInitialized) shell.refresh()
    }

    override fun onDestroy() {
        balanceScope.cancel()
        if (live === this) live = null
        super.onDestroy()
    }

    override fun onPause() {
        resumedNow = false
        super.onPause()
    }

    /**
     * Debug builds only. [DeepSeekAccountLogin] needs a real password typed in a browser, so local
     * testing seeds an already-issued account token instead:
     * `adb shell am start -n dev.pocketagent/.ui.MainActivity -e dev_token <token> [-e dev_model <model>]`.
     * Release builds ignore the extras.
     */
    private fun devSeed() {
        if (applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE == 0) return
        val token = intent.getStringExtra("dev_token")?.takeIf { it.isNotBlank() } ?: return
        val model = intent.getStringExtra("dev_model")?.takeIf { it.isNotBlank() }
            ?: DeepSeekAccountLogin.DEFAULT_MODEL
        repository.save(
            ProviderConfig(
                kind = ProviderKind.ANTHROPIC,
                baseUrl = DeepSeekAccountLogin.BASE_URL,
                apiKey = token,
                model = model,
                supportsImages = true,
                contextTokens = DeepSeekAccountLogin.CONTEXT_TOKENS,
                authHeader = AUTH_HEADER_DSH,
            ),
        )
        Toast.makeText(this, "开发令牌已保存", Toast.LENGTH_SHORT).show()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        shell.current?.let { outState.putString("screen", it) }
        outState.putInt("scrollY", shell.scrollY())
    }

    /** Back: one pushed screen at a time, then Settings and Activity go to Home, then the app closes. */
    private fun goBack() {
        if (!shell.pop()) finish()
    }

    /** Only reached below Android 13; later versions use the callback registered in onCreate. */
    @SuppressLint("GestureBackNavigation") // Android 13+ goes through the callback registered in onCreate; this serves older phones.
    @Deprecated("Android 13 and later use the registered back callback")
    override fun onBackPressed() = goBack()

    /** The screen a screen belongs under, for Back when nothing is stacked beneath it. */
    private fun parentOf(name: String): String? = when {
        name == "activity" || name == "settings" -> "home"
        name == "blocked" || name == "licences" -> "settings"
        name.startsWith("detail") -> "activity"
        name == "notice" -> "welcome"
        else -> null
    }

    // --- screens ---

    private fun build(name: String, nav: Nav): View = when {
        name == "welcome" -> Screens.welcome(this, nav)
        name == "notice" -> Screens.notice(this, nav, onAccept = {
            settings.noticeAccepted = true
            shell.reset("home", Shell.Transition.FADE)
        })
        name == "activity" -> Screens.activity(this, nav, rows = HistoryFormat.rows(history.all()))
        name == "settings" -> Screens.settings(this, settingsState(), nav)
        name == "blocked" -> Screens.blockedApps(this, nav)
        name == "licences" -> Screens.licences(this, nav)
        name.startsWith("detail:") -> detailScreen(name.removePrefix("detail:").toLongOrNull(), nav)
        else -> Screens.home(this, homeState(), nav)
    }

    private fun detailScreen(id: Long?, nav: Nav): View {
        val entry = id?.let { history.find(it) } ?: return Screens.activity(this, nav, rows = HistoryFormat.rows(history.all()))
        return Screens.activityDetail(
            this, nav,
            HistoryFormat.detail(entry, System.currentTimeMillis(),
                onRun = { runTask(entry.task) },
                onDelete = {
                    history.remove(entry.id)
                    shell.reset("activity", Shell.Transition.FADE)
                },
            ),
        )
    }

    private fun runTask(text: String) {
        val host = TaskScreenHost.current
        if (host == null) {
            Toast.makeText(this, "请先在无障碍设置中开启 dsh。", Toast.LENGTH_LONG).show()
        } else {
            host.startSetupTask(text)
        }
    }

    private fun openUrl(url: String) {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(this, "没有浏览器能打开 $url", Toast.LENGTH_LONG).show()
        }
    }

    private fun accessibilityOn() = AccessibilityState.isEnabled(
        Settings.Secure.getString(contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES),
        packageName,
        PocketAccessibilityService::class.java.name,
    )

    private fun notificationsOn(): Boolean = isListenerEnabled(
        Settings.Secure.getString(contentResolver, "enabled_notification_listeners"),
        ComponentName(this, PocketNotificationListener::class.java).flattenToString(),
    )

    private fun micOn() = checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    private fun facts(): SetupFacts {
        val config = repository.load()
        return SetupFacts(
            noticeAccepted = settings.noticeAccepted,
            acceptedCustomHost = settings.acceptedCustomHost,
            config = config,
            modelCheck = if (repository.modelCheckPassed && config != null) CheckResult.Works else null,
            accessibilityOn = accessibilityOn(),
            microphone = if (micOn()) MicState.GRANTED else if (settings.microphoneAsked) MicState.DENIED else MicState.NOT_ASKED,
            testTaskDone = repository.testTaskDone,
            notificationsOn = notificationsOn(),
        )
    }

    /** Settings > Microphone and the Home mic button: ask for the permission, or open the app's permission page if it is already on. */
    private fun requestMic() {
        if (micOn()) {
            openSettingsPage(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
        } else {
            startActivity(Intent(this, SetupActivity::class.java).putExtra(SetupActivity.EXTRA_REQUEST_MIC, true))
        }
    }

    private fun openSettingsPage(intent: Intent) {
        try {
            startActivity(intent)
        } catch (e: ActivityNotFoundException) {
            openSetup()
        }
    }

    private fun openSetup() = startActivity(Intent(this, SetupActivity::class.java))

    private fun openAccessibility() {
        try {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        } catch (e: ActivityNotFoundException) {
            openSetup()
        }
    }

    private fun homeState(): HomeState {
        val eval = SetupModel.evaluate(facts())
        val current = eval.steps.firstOrNull { it.status == StepStatus.CURRENT }?.step
        val attention = when {
            eval.isReady && dev.pocketagent.agent.ProgressStore(dev.pocketagent.android.PrefsKeyValueStore(getSharedPreferences("dsh_progress", MODE_PRIVATE))).load() != null -> {
                val pending = dev.pocketagent.agent.ProgressStore(dev.pocketagent.android.PrefsKeyValueStore(getSharedPreferences("dsh_progress", MODE_PRIVATE))).load()!!
                Attention("有未完成任务", pending.goal.ifBlank { pending.task }.take(150), "继续任务") { runTask(pending.task) }
            }
            eval.isReady -> null
            current == SetupStep.ACCESSIBILITY && eval.of(SetupStep.MODEL_CHECK).status == StepStatus.DONE ->
                Attention("无障碍未开启", "开启之前，dsh 无法点击或读取屏幕。", "去开启") { openAccessibility() }
            else -> Attention("完成设置", "添加 AI 服务商、密钥和模型，再开启无障碍。", "继续设置") { openSetup() }
        }
        return HomeState(
            statusDetail = "模型正常 · 无障碍已开启",
            attention = attention,
            recent = HistoryFormat.recent(history.all()),
            usage = history.all().let { all ->
                val line = HistoryFormat.todayLine(all)
                val total = settings.balanceText
                val warn = settings.balanceAlert
                val amount = total?.let { dev.pocketagent.android.BalanceApi.amount(it) }
                val money = total?.let { dev.pocketagent.android.BalanceApi.show(it, "CNY") }
                when {
                    money == null -> line
                    warn > 0 && amount != null && amount < warn -> "$line　·　余额偏低：$money（低于 ¥$warn）"
                    else -> "$line　·　余额 $money"
                }
            },
            shortcuts = settings.shortcuts ?: DefaultShortcuts,
            editingShortcuts = editingShortcuts,
            onEditShortcuts = { editingShortcuts = !editingShortcuts; shell.refresh() },
            onRemoveShortcut = { gone ->
                settings.shortcuts = (settings.shortcuts ?: DefaultShortcuts).filter { it != gone }
                shell.refresh()
            },
            onAddShortcut = {
                ShortcutDialog.show(this) { text ->
                    settings.shortcuts = ((settings.shortcuts ?: DefaultShortcuts) + text).distinct()
                    shell.refresh()
                }
            },
            onRun = { text -> runTask(text) },
            micGranted = { micOn() },
            onNeedMic = { requestMic() },
        )
    }

    /** The full address of the computer link, for pasting. It holds no secret: the pairing code is separate. */
    private fun copyLinkAddress() {
        val address = LocalAddress.find()
        if (address == null) {
            Toast.makeText(this, "请先让手机连上 Wi-Fi。", Toast.LENGTH_SHORT).show()
            return
        }
        val url = "http://$address:${McpHttpServer.DEFAULT_PORT}/mcp"
        getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("dsh 地址", url))
        Toast.makeText(this, "地址已复制：$url", Toast.LENGTH_SHORT).show()
    }

    /** The one-line command that connects Claude Code to this phone. It holds the pairing token, so it is marked sensitive. */
    private fun copyLinkCommand() {
        val address = LocalAddress.find()
        val token = settings.linkToken
        if (address == null || token == null) {
            Toast.makeText(this, "请先让手机连上 Wi-Fi。", Toast.LENGTH_SHORT).show()
            return
        }
        val command = "claude mcp add --transport http pocket-agent http://$address:${McpHttpServer.DEFAULT_PORT}/mcp " +
            "--header \"Authorization: Bearer $token\""
        val clip = ClipData.newPlainText("dsh 连接命令", command)
        clip.description.extras = PersistableBundle().apply { putBoolean(ClipDescription.EXTRA_IS_SENSITIVE, true) }
        getSystemService(ClipboardManager::class.java).setPrimaryClip(clip)
        Toast.makeText(this, "已复制。请粘贴到电脑的终端里。", Toast.LENGTH_LONG).show()
    }

    /** Lets the user grant a folder. The system picker is the only way in: no broad storage permission is asked for. */
    private fun pickFolder() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE)
            .addFlags(
                Intent.FLAG_GRANT_READ_URI_PERMISSION or
                    Intent.FLAG_GRANT_WRITE_URI_PERMISSION or
                    Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION,
            )
        try {
            @Suppress("DEPRECATION")
            startActivityForResult(intent, PICK_FOLDER)
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(this, "这台手机没有可以选文件夹的应用。", Toast.LENGTH_LONG).show()
        }
    }

    @Deprecated("The folder picker is the one flow that still returns through onActivityResult.")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != PICK_FOLDER || resultCode != RESULT_OK) return
        val uri = data?.data ?: return
        if (files.add(uri)) {
            Toast.makeText(this, "已允许 dsh 读写这个文件夹。", Toast.LENGTH_SHORT).show()
            shell.refresh()
        } else {
            Toast.makeText(this, "没能取得这个文件夹的长期权限，请再选一次。", Toast.LENGTH_LONG).show()
        }
    }

    private fun settingsState(): SettingsState {
        val config = repository.load()
        val version = try {
            "版本 " + packageManager.getPackageInfo(packageName, 0).versionName
        } catch (e: PackageManager.NameNotFoundException) {
            "dsh"
        }
        return SettingsState(
            version = version,
            provider = config?.host?.removePrefix("api.")?.removePrefix("www.") ?: "未设置",
            keySaved = !config?.apiKey.isNullOrEmpty(),
            accessibilityOn = accessibilityOn(),
            micOn = micOn(),
            talkBackOn = settings.talkBack,
            onTalkBack = { settings.talkBack = it },
            linkOn = settings.computerLink,
            onLink = {
                settings.computerLink = it
                if (it && !PairingToken.looksValid(settings.linkToken)) settings.linkToken = PairingToken.generate()
                shell.refresh()
            },
            linkAddress = if (settings.computerLink) LocalAddress.find()?.let { "$it:${McpHttpServer.DEFAULT_PORT}" } else null,
            onCopyLink = { copyLinkCommand() },
            onCopyAddress = { copyLinkAddress() },
            onResetLink = {
                SheetDialogs.confirm(this, "重置配对码？", "已连接的电脑会失效，直到你复制新的连接命令。", "重置") {
                    settings.linkToken = PairingToken.generate()
                    Toast.makeText(this, "已生成新的配对码。", Toast.LENGTH_SHORT).show()
                }
            },
            shortScreensOn = settings.shortScreens,
            onShortScreens = { settings.shortScreens = it },
            onProvider = { openSetup() },
            onAccessibility = { openAccessibility() },
            onMic = { requestMic() },
            fileFolders = files.folders().map { it.name },
            onAddFolder = { pickFolder() },
            onRemoveFolder = { name ->
                files.folders().firstOrNull { it.name == name }?.let { folder ->
                    files.remove(folder.uri)
                    shell.refresh()
                }
            },
            showHistory = true,
            historyOn = history.enabled,
            onHistory = { history.enabled = it },
            onClearHistory = {
                SheetDialogs.confirm(this, "清除历史记录？", "这会删除本机的任务列表，且无法恢复。", "清除") {
                    history.clear()
                    shell.refresh()
                }
            },
            balance = settings.balanceText?.let { dev.pocketagent.android.BalanceApi.show(it, "CNY") },
            balanceAlert = settings.balanceAlert,
            onRefreshBalance = { refreshBalance(force = true) },
            onBalanceAlert = { cycleBalanceAlert() },
            onRiskyInfo = {
                SheetDialogs.info(
                    this, "危险操作前先询问",
                    "发送、付款、发布或删除之前，dsh 都会先征求你的同意。这是安全规则，无法关闭。",
                )
            },
            theme = ThemeMode.current(this),
            onTheme = { mode ->
                if (mode != ThemeMode.current(this)) {
                    ThemeMode.set(this, mode)
                    recreate()
                }
            },
        )
    }

    companion object {
        private const val BALANCE_FRESH_MS = 5 * 60 * 1000L
        const val REPO = "https://github.com/ManojDevarajulu/PocketAgent"

        /**
         * Whether Home is the screen in front. A question asked while it is can be answered in a dialog; one asked
         * while another app is in front opens its own task, so answering it returns to that app, not to us.
         */
        @Volatile
        var resumedNow: Boolean = false

        /** The visible instance, so a task that ends while we are open can refresh the list underneath it. */
        @Volatile
        private var live: MainActivity? = null

        /** Called after a task is saved: the Activity tab is correct without waiting for a resume. */
        fun noticeHistoryChanged() {
            val activity = live ?: return
            activity.runOnUiThread { activity.refreshIfResumed() }
        }

        /** A question the agent is waiting on. The session brings Home forward and this screen asks it. */
        const val EXTRA_QUESTION = "dev.pocketagent.extra.QUESTION"

        private const val PICK_FOLDER = 4701
    }
}
