package dev.pocketagent.android

import android.accessibilityservice.AccessibilityService
import android.app.KeyguardManager
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.pm.PackageManager
import android.view.accessibility.AccessibilityEvent
import dev.pocketagent.bridge.McpHandler
import dev.pocketagent.bridge.McpHttpServer
import dev.pocketagent.bridge.PairingToken
import dev.pocketagent.bridge.TaskReply
import dev.pocketagent.playbook.RoutineRecorder
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.widget.Toast
import dev.pocketagent.R
import dev.pocketagent.agent.AgentLoop
import dev.pocketagent.agent.AgentRunner
import dev.pocketagent.agent.AgentState
import dev.pocketagent.agent.AgentUi
import dev.pocketagent.agent.BubbleLogic
import dev.pocketagent.agent.ConfirmRequest
import dev.pocketagent.agent.SummaryText
import dev.pocketagent.agent.VoiceUi
import dev.pocketagent.device.DeviceAction
import dev.pocketagent.device.DeviceUnavailable
import dev.pocketagent.device.Screen
import dev.pocketagent.device.ScreenSerializer
import dev.pocketagent.device.Settler
import dev.pocketagent.llm.HttpCaller
import dev.pocketagent.llm.LlmClient
import dev.pocketagent.llm.OkHttpTransport
import dev.pocketagent.llm.Providers
import dev.pocketagent.llm.ModelCapabilities
import dev.pocketagent.agent.ExperienceStore
import dev.pocketagent.agent.ProgressStore
import dev.pocketagent.agent.DirectControlClient
import dev.pocketagent.bridge.PhoneControl
import dev.pocketagent.llm.LlmResult
import dev.pocketagent.llm.Turn
import kotlinx.serialization.json.*
import java.util.Base64
import dev.pocketagent.playbook.PlaybookStore
import dev.pocketagent.safety.BlockedApps
import dev.pocketagent.safety.NotificationFilter
import dev.pocketagent.safety.QuizLabels
import dev.pocketagent.safety.SafetyGate
import dev.pocketagent.setup.ConfigRepository
import dev.pocketagent.setup.HistoryStore
import dev.pocketagent.setup.SetupSettings
import dev.pocketagent.ui.CaptionView
import dev.pocketagent.ui.ConfirmCard
import dev.pocketagent.ui.MainActivity
import dev.pocketagent.ui.OverlayUi
import dev.pocketagent.ui.SetupActivity
import dev.pocketagent.ui.SummaryCard
import dev.pocketagent.ui.TaskInputActivity
import dev.pocketagent.ui.TaskScreenHost
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException

/**
 * Owns the runner, the device and the overlays for the life of the service (S3 spec §2, §4, §6 and §7).
 * Every method is called on the main thread, and every field below is read and written there, except [closed],
 * which the device also reads from its own threads.
 */
class AgentSession(
    private val service: PocketAccessibilityService,
    private val repository: ConfigRepository,
    private val settings: SetupSettings,
    private val history: HistoryStore,
) : TaskScreenHost {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val main = Handler(Looper.getMainLooper())
    private val ownPackage: String = service.packageName

    @Volatile
    private var closed = false

    private val settler = Settler(now = SystemClock::uptimeMillis)
    private val logic = BubbleLogic()
    private val caption = CaptionView(service) { stopRunningTask() }
    private val confirmCard = ConfirmCard(service)
    private val summaryCard = SummaryCard(service)
    private val overlayUi = OverlayUi(confirmCard, caption, ::openAnswerScreen)
    private val keyguard = service.getSystemService(KeyguardManager::class.java)
    private var hiddenForRead = false
    private var hiddenForLock = false
    private val lockPoll = object : Runnable {
        override fun run() {
            if (closed) return
            syncLink()
            val locked = keyguard?.isKeyguardLocked == true
            if (locked != hiddenForLock) {
                hiddenForLock = locked
                applyOverlayVisibility()
            }
            main.postDelayed(this, LOCK_POLL_MS)
        }
    }
    private val device = AccessibilityDevice(service, settler, ::setOverlaysHidden) { closed }

    private val blocked = BlockedApps()
    private val gate = SafetyGate(blocked, ownPackage)
    private val serializer = ScreenSerializer(ownPackage, maxChars = MAX_SCREEN_CHARS, geometry = true)
    private val progress = ProgressStore(PrefsKeyValueStore(service.getSharedPreferences("dsh_progress", android.content.Context.MODE_PRIVATE)))
    private val experiences = ExperienceStore(PrefsKeyValueStore(service.getSharedPreferences("dsh_progress", android.content.Context.MODE_PRIVATE)))
    private val playbooks = loadPlaybooks()

    /** Loaded at each task start; null when there is no valid stored config, so a bubble tap opens setup instead. */
    private fun currentClient(): LlmClient? {
        val config = repository.load() ?: return null
        return try {
            Providers.create(ModelCapabilities.operator(config), HttpCaller(OkHttpTransport()))
        } catch (e: IllegalArgumentException) {
            null
        }
    }

    /**
     * The second opinion: the same provider and key, the site's other model. Only DeepSeek's pair is known by name, so
     * other providers simply do not get the tool — a made-up model name would only fail at task time.
     */
    private fun voterClient(mainModel: String): LlmClient? {
        val config = repository.load() ?: return null
        val other = when (mainModel.lowercase()) {
            "deepseek-flash" -> "deepseek-v4-pro"
            "deepseek-v4-pro" -> "deepseek-flash"
            else -> return null
        }
        return try {
            Providers.create(ModelCapabilities.normalize(config.copy(model = other)), HttpCaller(OkHttpTransport()))
        } catch (e: IllegalArgumentException) {
            null
        }
    }

    /**
     * Answering a paper is the one job where the weaker model is the weak link, so a quiz task on a DeepSeek account
     * runs on the pro model. Anything else keeps whatever the user chose.
     */
    private fun strongerForQuiz(base: LlmClient): LlmClient = base

    /** True for the task started from the setup screen, so a success can mark the test task done. */
    private var setupTaskRunning = false
    private var taskText = ""
    private var taskStartedAt = 0L

    /**
     * The loop only reaches the overlays through this wrapper. Caption and waiting changes are applied on the main
     * thread and only while a task is live, so a caption posted just before a cancel or [close] cannot reappear.
     */
    private val guardedUi = guarded { activeUi }

    /** What a [VoiceUi] wraps: the overlays, with the same guard, since it also shows captions from the loop's thread. */
    private val guardedOverlayUi = guarded { overlayUi }

    /**
     * Delegates to [target]; caption and waiting changes run on the main thread (at once when already there, so a
     * change forwarded from the main thread keeps its order with ones posted from the loop) and only while a task is live.
     */
    private fun guarded(target: () -> AgentUi): AgentUi = object : AgentUi {
        override suspend fun confirm(request: ConfirmRequest): Boolean = target().confirm(request)
        override suspend fun ask(question: String): String? = target().ask(question)
        override fun onCaption(caption: String) = onMainWhileLive { target().onCaption(caption) }
        override fun onPlan(goal: String, next: String) = onMainWhileLive { target().onPlan(goal, next) }
        override fun onWaitingForUser(waiting: Boolean) = onMainWhileLive { target().onWaitingForUser(waiting) }
    }

    private fun onMainWhileLive(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            if (taskIsLive()) block()
        } else {
            main.post { if (taskIsLive()) block() }
        }
    }

    /**
     * The UI of the current task: a [VoiceUi] wrapping [overlayUi] for a spoken task with Talk back on, else [overlayUi].
     * Written on the main thread before the runner starts; the loop reads it from its own thread.
     */
    @Volatile
    private var activeUi: AgentUi = overlayUi

    /** The voice UI of the current or just-finished task, kept after the end so its last line can still be stopped. */
    private var voiceUi: VoiceUi? = null

    /** Created once per service life, the first time Talk back may be needed, so the engine is ready before a task starts. */
    private var speaker: TtsSpeaker? = null
    private var listener: RecognizerListener? = null

    /** True when this task wanted voice but speech was not available; the summary card then says so, once per service run. */
    private var speechMissing = false
    private var speechNoteShown = false

    /** Set just before the runner starts a task; the loop factory reads it once. */
    private var pendingClient: LlmClient? = null

    /** The model that client actually uses, so the second opinion always answers with the other one. */
    private var pendingMainModel: String = ""

    private val runner = AgentRunner(
        scope,
        { ui ->
            AgentLoop(
                checkNotNull(pendingClient), device, gate, playbooks, serializer, ui,
                notifications = AndroidNotifications,
                notificationFilter = NotificationFilter(blocked, ownPackage),
                shortenScreens = settings.shortScreens,
                files = SafFiles(service, SafFiles.store(service)),
                web = HttpWeb(),
                voter = if (pendingClient is DirectControlClient) null else voterClient(pendingMainModel),
                progress = progress,
                experiences = experiences,
                maxSteps = if (pendingClient is DirectControlClient) 60 else 40,
            )
        },
        guardedUi,
    )

    /** Read at bubble tap, handed to the next task, dropped when the task starts or the task screen is dismissed. */
    private var savedScreen: Screen? = null
    private var tapJob: Job? = null

    init {
        TaskScreenHost.current = this
        main.post(lockPoll)
        scope.launch(Dispatchers.Main) { runner.state.collect(::onState) }
    }

    fun onEvent() = settler.onEvent()

    /**
     * The Quick Settings tile: close the notification shade, give the screen a moment to settle so the shade is not
     * what gets read, then do what a bubble tap does.
     */
    fun startFromTile() {
        if (closed || taskIsLive() || recorder != null) return
        val shade = if (Build.VERSION.SDK_INT >= 31) {
            AccessibilityService.GLOBAL_ACTION_DISMISS_NOTIFICATION_SHADE
        } else {
            AccessibilityService.GLOBAL_ACTION_BACK
        }
        service.performGlobalAction(shade)
        main.postDelayed({ startBubbleTap() }, TILE_SETTLE_MS)
    }

    /** Hide the bubble, read the screen without settling, show the bubble, keep the screen, open the task screen. */
    fun startBubbleTap() {
        if (closed || tapJob?.isActive == true) return
        summaryCard.close()
        // The previous task's last spoken line stops with its card.
        if (!taskIsLive()) stopVoice()
        if (currentClient() == null) {
            openSetup()
            return
        }
        logic.taskScreenOpen = true
        // The speech engine starts up in the background while the user speaks the task.
        if (settings.talkBack) createVoiceEngines()
        tapJob = scope.launch {
            val screen = try {
                setOverlaysHidden(true)
                try {
                    device.readScreenNow()
                } finally {
                    withContext(NonCancellable) { setOverlaysHidden(false) }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: DeviceUnavailable) {
                withContext(Dispatchers.Main) { tapFailed(e.reason) }
                return@launch
            } catch (e: RuntimeException) {
                // A binder call failed while reading; nothing was saved.
                withContext(Dispatchers.Main) { tapFailed(null) }
                return@launch
            }
            withContext(Dispatchers.Main) { openTaskScreen(screen) }
        }
    }

    override fun startTask(text: String, spoken: Boolean) {
        logic.taskScreenOpen = false
        val screen = savedScreen
        savedScreen = null
        val task = text.trim()
        val client = currentClient()
        if (closed || remoteInFlight || screen == null || client == null || task.isEmpty() || task.length > TaskInputActivity.MAX_TEXT) {
            dropHandlesIfNoTask()
            return
        }
        begin(task, screen, client, fromSetup = false, spoken = spoken)
    }

    private fun begin(task: String, screen: Screen, client: LlmClient, fromSetup: Boolean, spoken: Boolean = false) {
        // Every start runs on the main thread and the runner refuses a second task, so a live task keeps its UI.
        if (taskIsLive() || recorder != null) return
        val quiz = QuizLabels.looksLikeQuiz(task)
        val stored = repository.load()?.let { ModelCapabilities.operator(it).model.lowercase() }.orEmpty()
        pendingClient = client
        pendingMainModel = stored
        setupTaskRunning = fromSetup
        // A new task silences whatever the previous one was still saying.
        stopVoice()
        speechMissing = false
        val voice = if (spoken && settings.talkBack) voiceFor(task) else null
        voiceUi = voice
        activeUi = voice ?: overlayUi
        if (runner.start(task, screen)) {
            taskText = task
            taskStartedAt = System.currentTimeMillis()
            voice?.start()
        } else {
            setupTaskRunning = false
            stopVoice()
        }
    }

    private fun createVoiceEngines() {
        if (closed) return
        // An engine error leaves a speaker unusable for good; a fresh one lets the next task speak again.
        if (speaker?.failed == true) {
            speaker?.shutdown()
            speaker = null
        }
        if (speaker == null) speaker = TtsSpeaker(service)
        if (listener == null) listener = RecognizerListener(service)
    }

    /** A voice UI for [task], or null (and the note on the summary card) when the speech engine is not ready. */
    private fun voiceFor(task: String): VoiceUi? {
        createVoiceEngines()
        val tts = speaker
        val ears = listener
        if (tts == null || ears == null || !tts.available) {
            speechMissing = true
            return null
        }
        return VoiceUi(guardedOverlayUi, tts, ears, task)
    }

    /** Stops speech and listening of the voice UI, if any, and goes back to the plain overlays. */
    private fun stopVoice() {
        voiceUi?.stop()
        voiceUi = null
        activeUi = overlayUi
    }

    /** The test task of the setup screen: go Home, read the settled screen, run the task there. */
    override fun startSetupTask(text: String) {
        val task = text.trim()
        if (closed || remoteInFlight || task.isEmpty() || task.length > TaskInputActivity.MAX_TEXT || taskIsLive()) return
        val client = currentClient() ?: return
        scope.launch {
            val screen = try {
                // A resumed task must keep the user's current app; a fresh task starts at Home.
                if (progress.load()?.task != task) device.perform(DeviceAction.Home)
                device.readScreen()
            } catch (e: CancellationException) {
                throw e
            } catch (e: DeviceUnavailable) {
                withContext(Dispatchers.Main) { tapFailed(e.reason) }
                return@launch
            } catch (e: RuntimeException) {
                withContext(Dispatchers.Main) { tapFailed(null) }
                return@launch
            }
            withContext(Dispatchers.Main) { if (!closed) begin(task, screen, client, fromSetup = true) }
        }
    }

    override fun dropSavedScreen() {
        logic.taskScreenOpen = false
        savedScreen = null
        dropHandlesIfNoTask()
    }

    override fun completeAnswer(answer: String?) {
        overlayUi.completeAnswer(answer)
    }

    /** Cancels the runner and scope, removes every overlay, drops the screen and node map, clears the holder. */
    fun close() {
        if (closed) return
        // Read before setting closed: taskIsLive() is false once the session is closed.
        val taskWasLive = taskIsLive()
        // A task the system killed must still show up in the history, or the user is left wondering where it went.
        if (taskWasLive) record(false, "任务还没跑完就被系统打断了（可能是系统回收了内存）。", 0)
        directControl?.close("服务已关闭，控制结束。"); directControl = null
        closed = true
        main.removeCallbacks(lockPoll)
        link?.stop()
        link = null
        runner.cancel()
        scope.cancel()
        stopVoice()
        speaker?.shutdown()
        listener?.shutdown()
        speaker = null
        listener = null
        overlayUi.closeAll()
        TaskInputActivity.closeAnswerScreen()
        caption.close()
        confirmCard.close()
        summaryCard.close()
        main.removeCallbacksAndMessages(null)
        logic.taskScreenOpen = false
        savedScreen = null
        device.dropHandles()
        if (TaskScreenHost.current === this) TaskScreenHost.current = null
        if (taskWasLive) {
            // The overlays are gone with the service, so a Toast is the only way left to say so (spec §7).
            Toast.makeText(service.applicationContext, R.string.service_stopped, Toast.LENGTH_LONG).show()
        }
    }

    // --- State ---

    private fun onState(state: AgentState) {
        if (closed) return
        logic.onState(state)
        val live = state is AgentState.Running || state is AgentState.WaitingForUser
        caption.setRunning(live)
        service.subscribeToTaskEvents(live)
        when (state) {
            is AgentState.Finished -> {
                directControl?.close(state.outcome.summary); directControl = null
                if (remoteInFlight) remoteInFlight = false
                if (setupTaskRunning && state.outcome.success) repository.testTaskDone = true
                record(state.outcome.success, SummaryText.forHistory(state.outcome), state.outcome.usage.inputTokens + state.outcome.usage.outputTokens)
                setupTaskRunning = false
                // Not stopped afterwards, so the result is heard in full; the next task, a cancel or close() stops it.
                if (!taskIsLive()) {
                    voiceUi?.end(state.outcome)
                    activeUi = overlayUi
                }
                endTask(SummaryText.title(state), SummaryText.body(state.outcome))
            }
            AgentState.Stopped -> {
                directControl?.close("用户在手机上停止了控制。"); directControl = null
                remoteInFlight = false
                setupTaskRunning = false
                record(false, "被你停止；进度已保留，可从首页继续。", 0)
                if (!taskIsLive()) stopVoice()
                endTask(SummaryText.stoppedTitle(), "")
            }
            else -> Unit
        }
    }

    /** Saves the finished task to Activity (only the task text and how it went), once per task. */
    private fun record(ok: Boolean, summary: String, tokens: Long) {
        if (taskText.isEmpty()) return
        history.add(taskText, ok, summary, taskStartedAt, tokens)
        taskText = ""
        // The task may end while the user is sitting on Home or Activity, so ask the screen to redraw.
        MainActivity.noticeHistoryChanged()
    }

    /** Runs once per ending; skipped when a newer task already started before this state was collected. */
    private fun endTask(title: String, body: String) {
        if (taskIsLive()) return
        overlayUi.closeAll()
        TaskInputActivity.closeAnswerScreen()
        device.dropHandles()
        summaryCard.close()
        Toast.makeText(service, title + "。" + withSpeechNote(body).take(120), Toast.LENGTH_LONG).show()
    }

    private fun stopRunningTask() {
        if (!taskIsLive()) return
        // Cancel both a pending model request and any confirmation/answer wait.
        directControl?.close("用户在手机上停止了控制。")
        directControl = null
        runner.cancel()
        stopVoice()
        overlayUi.closeAll()
    }

    /** Adds the unavailable-speech note to [body] the first time a voice task had to run silently. */
    private fun withSpeechNote(body: String): String {
        if (!speechMissing) return body
        speechMissing = false
        if (speechNoteShown) return body
        speechNoteShown = true
        return if (body.isEmpty()) SPEECH_UNAVAILABLE_NOTE else "$body\n\n$SPEECH_UNAVAILABLE_NOTE"
    }

    private fun taskIsLive(): Boolean {
        if (closed) return false
        val state = runner.state.value
        return state is AgentState.Running || state is AgentState.WaitingForUser
    }

    // --- Computer link ---

    private var link: McpHttpServer? = null
    private var remoteInFlight = false
    @Volatile private var directControl: DirectControlClient? = null
    private val linkHandler = McpHandler(PhoneControl { name,args -> directCall(name,args) }) { task -> runFromComputer(task) }

    /** Starts or stops the listener to match the setting. Cheap enough to call from the poll that already runs. */
    private fun syncLink() {
        if (closed) return
        if (settings.computerLink) {
            if (link != null) return
            if (!PairingToken.looksValid(settings.linkToken)) settings.linkToken = PairingToken.generate()
            val server = McpHttpServer(McpHttpServer.DEFAULT_PORT, { settings.linkToken.orEmpty() }, linkHandler, scope)
            if (server.start()) {
                link = server
            } else {
                // Port taken: say so once and switch the setting off so this does not retry every half second.
                settings.computerLink = false
                Toast.makeText(service.applicationContext, R.string.link_port_busy, Toast.LENGTH_LONG).show()
            }
        } else {
            if (directControl != null) { directControl?.close("电脑连接已关闭。"); directControl=null; runner.cancel(); remoteInFlight=false }
            link?.stop()
            link = null
        }
    }

    /**
     * A task sent from the user's computer. It always asks on the phone first and shows the exact words, then runs
     * like any other task, so the safety gate and the confirmation of anything that sends, calls or pays still apply.
     */
    private suspend fun runFromComputer(task: String): TaskReply = withContext(Dispatchers.Main) {
        if (closed) return@withContext TaskReply.Refused("dsh 正在停止。")
        if (remoteInFlight || taskIsLive() || recorder != null || tapJob?.isActive == true || logic.taskScreenOpen) {
            return@withContext TaskReply.Refused("手机正忙，做不了这件事。")
        }
        if (keyguard?.isKeyguardLocked == true) return@withContext TaskReply.Refused("手机处于锁屏状态。")
        val client = currentClient() ?: return@withContext TaskReply.Refused("手机上还没设置 AI 服务商。")
        remoteInFlight = true
        try {
            summaryCard.close()
            val request = ConfirmRequest("执行电脑发来的任务", task.take(CONFIRM_TASK_CHARS), "dsh")
            val approved = withTimeoutOrNull(CONFIRM_LINK_MS) { overlayUi.confirm(request) } ?: false
            if (!approved) return@withContext TaskReply.Refused("手机主人没有批准这个任务。")
            val screen = try {
                setOverlaysHidden(true)
                try {
                    device.readScreenNow()
                } finally {
                    withContext(NonCancellable) { setOverlaysHidden(false) }
                }
            } catch (e: DeviceUnavailable) {
                return@withContext TaskReply.Refused(e.reason)
            }
            begin(task, screen, client, fromSetup = false)
            if (!taskIsLive()) return@withContext TaskReply.Refused("任务没能启动。")
            val end = runner.state.first { it is AgentState.Finished || it is AgentState.Stopped }
            when (end) {
                is AgentState.Finished -> TaskReply.Done(end.outcome.success, end.outcome.summary)
                else -> TaskReply.Done(false, "任务在手机上被停止了。")
            }
        } finally {
            remoteInFlight = false
        }
    }

    private fun remoteResult(turn: Turn): JsonObject = buildJsonObject {
        putJsonArray("content") {
            val text = listOfNotNull(turn.toolResult?.text,turn.text.takeIf { it.isNotBlank() },turn.systemNote).joinToString("\n\n")
            add(buildJsonObject { put("type","text"); put("text",text) })
            turn.image?.let { png -> add(buildJsonObject { put("type","image"); put("mimeType","image/png"); put("data",Base64.getEncoder().encodeToString(png)) }) }
        }
        put("isError",turn.toolResult?.isError == true)
    }

    private fun remoteError(message: String) = remoteResult(Turn("",dev.pocketagent.llm.ToolResult("control",message,true)))

    private suspend fun directCall(name: String, args: JsonObject): JsonObject {
        if (name == "control_status") return remoteResult(Turn(if (directControl != null) "电脑控制中" else "未建立电脑控制"))
        if (name == "control_stop") return withContext(Dispatchers.Main) {
            if (directControl != null) { directControl?.close("电脑结束了控制。"); runner.cancel(); directControl=null; remoteInFlight=false }
            remoteResult(Turn("电脑控制已结束。"))
        }
        if (name == "control_start") {
            val client=withContext(Dispatchers.Main) {
                if (closed || taskIsLive() || remoteInFlight || recorder != null || tapJob?.isActive == true || logic.taskScreenOpen || keyguard?.isKeyguardLocked == true) return@withContext null
                remoteInFlight=true
                try {
                    val task=(args["task"] as JsonPrimitive).content
                    summaryCard.close()
                    val approved=withTimeoutOrNull(CONFIRM_LINK_MS) { overlayUi.confirm(ConfirmRequest("允许电脑直接操作手机",task.take(CONFIRM_TASK_CHARS),"dsh")) } ?: false
                    if (!approved || closed || keyguard?.isKeyguardLocked == true) { remoteInFlight=false; return@withContext null }
                    val screen=device.readScreen()
                    if (gate.checkApp(screen) is dev.pocketagent.safety.Verdict.Block) { remoteInFlight=false; return@withContext null }
                    val remote=DirectControlClient()
                    directControl=remote
                    begin(task,screen,remote,fromSetup=false)
                    if (!taskIsLive()) { remote.close("未能启动。"); directControl=null; remoteInFlight=false; return@withContext null }
                    remote
                } catch (e: CancellationException) { remoteInFlight=false; throw e }
                catch (_: Exception) { remoteInFlight=false; directControl?.close("启动失败。"); directControl=null; null }
            } ?: return remoteError("手机正忙、已锁屏，或主人未批准控制。")
            return remoteResult(client.ready.await())
        }
        val client=directControl ?: return remoteError("请先 control_start，并在手机上批准本次任务。")
        if (closed || keyguard?.isKeyguardLocked == true) return remoteError("手机已锁定或服务已停止。")
        return remoteResult(client.execute(LlmResult.Call("remote-"+System.nanoTime(),name,args)))
    }

    // --- Record my routine ---

    private var recorder: RoutineRecorder? = null

    override fun startRecording() {
        savedScreen = null
        logic.taskScreenOpen = false
        if (closed || taskIsLive() || recorder != null) return
        summaryCard.close()
        recorder = RoutineRecorder(blocked, ownPackage)
        service.subscribeToRecordingEvents(true)
        logic.setRecording(true)
        Toast.makeText(service.applicationContext, R.string.recording_started, Toast.LENGTH_LONG).show()
    }

    /**
     * Called for every event while recording. A click keeps only its label, never a field's text, and a new screen
     * keeps only its class name, which names the screen but, unlike a window title, never holds a contact or a message.
     */
    fun onRecordingEvent(event: AccessibilityEvent) {
        val active = recorder ?: return
        val pkg = event.packageName?.toString().orEmpty()
        when (event.eventType) {
            AccessibilityEvent.TYPE_VIEW_CLICKED -> {
                val source = event.source
                val label = source?.text?.toString() ?: source?.contentDescription?.toString() ?: event.contentDescription?.toString()
                // No readable source means no label either, so the step is kept as an unlabelled button.
                active.tap(pkg, label, editable = source != null && (source.isEditable || source.isPassword))
            }
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> {
                val name = event.className?.toString()?.substringAfterLast('.').orEmpty()
                if (name.length >= MIN_SCREEN_NAME && !event.className.toString().startsWith("android.")) active.screen(pkg, name)
            }
        }
    }

    private fun stopRecording() {
        val active = recorder ?: return
        recorder = null
        service.subscribeToRecordingEvents(false)
        logic.setRecording(false)
        val pm = service.packageManager
        val draft = active.build(
            appLabel = { pkg -> try { pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString() } catch (e: PackageManager.NameNotFoundException) { pkg } },
            version = { pkg -> try { pm.getPackageInfo(pkg, 0).versionName.orEmpty() } catch (e: PackageManager.NameNotFoundException) { "" } },
        )
        val result = draft.getOrNull()
        if (result == null) {
            summaryCard.show(service.getString(R.string.recording_failed_title), draft.exceptionOrNull()?.message.orEmpty())
            return
        }
        // Private storage only; the user decides whether it is ever shared, after checking it for anything personal.
        val saved = try {
            val dir = File(service.filesDir, RECORDED_DIR).apply { mkdirs() }
            File(dir, "${result.packageName}.md").writeText(result.text)
            true
        } catch (e: IOException) {
            false
        }
        service.getSystemService(ClipboardManager::class.java)?.setPrimaryClip(ClipData.newPlainText("dsh 流程草稿", result.text))
        val note = if (saved) "已存进应用，并已复制。" else "已复制（但没能保存）。"
        summaryCard.show(
            service.getString(R.string.recording_done_title, result.app),
            "${result.steps} 步。$note 分享成流程之前先看一遍有没有私人信息；参考 CONTRIBUTING.md。",
        )
    }

    /** Hides (true) or shows (false) the caption on the main thread; does nothing once closed. */
    private suspend fun setOverlaysHidden(hidden: Boolean) {
        withContext(Dispatchers.Main) {
            if (closed) return@withContext
            hiddenForRead = hidden
            applyOverlayVisibility()
        }
    }

    /** The caption shows only when neither a screen read or gesture nor the lock screen hides it. */
    private fun applyOverlayVisibility() {
        val visible = !hiddenForRead && !hiddenForLock
        caption.setVisible(visible)
    }

    private fun tapFailed(reason: String?) {
        logic.taskScreenOpen = false
        dropHandlesIfNoTask()
        if (closed) return
        summaryCard.show(
            service.getString(R.string.phone_unavailable_title),
            reason ?: service.getString(R.string.read_failed),
        )
    }

    private fun openTaskScreen(screen: Screen) {
        if (closed) return
        savedScreen = screen
        val intent = Intent(service, TaskInputActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            service.startActivity(intent)
        } catch (e: ActivityNotFoundException) {
            dropSavedScreen()
        } catch (e: SecurityException) {
            dropSavedScreen()
        }
    }

    private fun openAnswerScreen(question: String) {
        if (!taskIsLive()) {
            overlayUi.completeAnswer(null)
            return
        }
        // Home asks the question in a dialog when it is the screen in front. From any other app the question gets a
        // task of its own (TaskInputActivity has an empty taskAffinity), so answering it drops the user back into the
        // app they were using instead of into ours.
        if (MainActivity.resumedNow) {
            val intent = Intent(service, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                .putExtra(MainActivity.EXTRA_QUESTION, question)
            try {
                service.startActivity(intent)
            } catch (e: ActivityNotFoundException) {
                overlayUi.completeAnswer(null)
            } catch (e: SecurityException) {
                overlayUi.completeAnswer(null)
            }
            return
        }
        val ask = Intent(service, TaskInputActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            .putExtra(TaskInputActivity.EXTRA_QUESTION, question)
        try {
            service.startActivity(ask)
        } catch (e: ActivityNotFoundException) {
            overlayUi.completeAnswer(null)
        } catch (e: SecurityException) {
            overlayUi.completeAnswer(null)
        }
    }

    private fun dropHandlesIfNoTask() {
        if (!taskIsLive()) device.dropHandles()
    }

    private fun openSetup() {
        try {
            service.startActivity(Intent(service, SetupActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (e: ActivityNotFoundException) {
            // Nothing else to show.
        } catch (e: SecurityException) {
            // Nothing else to show.
        }
    }

    /** The bundled playbooks. Their validity is enforced by the unit tests; a bad file here means none are used. */
    private fun loadPlaybooks(): PlaybookStore {
        val files = try {
            service.assets.list(PLAYBOOK_DIR).orEmpty().filter { it.endsWith(".md") }.associateWith { name ->
                service.assets.open("$PLAYBOOK_DIR/$name").use { it.readBytes().toString(Charsets.UTF_8) }
            }
        } catch (e: IOException) {
            emptyMap()
        }
        return try {
            PlaybookStore.load(files, blocked)
        } catch (e: IllegalArgumentException) {
            PlaybookStore.load(emptyMap(), blocked)
        }
    }

    private companion object {
        const val MAX_SCREEN_CHARS = 12_000
        const val LOCK_POLL_MS = 500L
        const val TILE_SETTLE_MS = 450L
        const val MIN_SCREEN_NAME = 4
        const val CONFIRM_LINK_MS = 60_000L
        const val CONFIRM_TASK_CHARS = 300
        const val RECORDED_DIR = "recorded"
        const val PLAYBOOK_DIR = "playbooks"
        const val SPEECH_UNAVAILABLE_NOTE = "这台手机没有语音能力，dsh 这次没出声。"
    }
}
