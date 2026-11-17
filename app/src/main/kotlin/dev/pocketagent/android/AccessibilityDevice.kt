package dev.pocketagent.android

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.accessibilityservice.GestureDescription
import android.app.KeyguardManager
import android.app.SearchManager
import android.content.ComponentName
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.net.Uri
import android.provider.AlarmClock
import android.os.Build
import android.os.Bundle
import android.view.Display
import android.view.WindowInsets
import android.view.WindowManager
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityNodeInfo.AccessibilityAction
import dev.pocketagent.device.ActionResult
import dev.pocketagent.device.AppMatcher
import dev.pocketagent.device.AppResolution
import dev.pocketagent.device.Bounds
import dev.pocketagent.device.DeviceAction
import dev.pocketagent.device.DevicePort
import dev.pocketagent.device.Mark
import dev.pocketagent.device.MarkLegend
import dev.pocketagent.device.Shot
import dev.pocketagent.device.DeviceUnavailable
import dev.pocketagent.device.Direction
import dev.pocketagent.device.GesturePlanner
import dev.pocketagent.device.GridLines
import dev.pocketagent.device.LauncherApp
import dev.pocketagent.device.LiveNodeState
import dev.pocketagent.device.Point
import dev.pocketagent.device.Revalidator
import dev.pocketagent.device.Screen
import dev.pocketagent.device.ScreenNode
import dev.pocketagent.device.Shortcut
import dev.pocketagent.device.Settler
import dev.pocketagent.device.SwipePath
import dev.pocketagent.device.TypedText
import dev.pocketagent.device.TextEntry
import dev.pocketagent.device.TextEditor
import dev.pocketagent.device.FontReference
import dev.pocketagent.safety.BlockedApps
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.asExecutor
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.resume
import kotlin.math.roundToInt

/**
 * [DevicePort] on top of the accessibility service (spec §4 and §5). It only moves data between the platform and
 * the pure units; every decision is theirs: [Revalidator] before each action, [GesturePlanner] for gesture
 * coordinates, [AppMatcher] (with [BlockedApps]) for open_app.
 */
class AccessibilityDevice(
    private val service: AccessibilityService,
    private val settler: Settler,
    /** Hides (true) or shows (false) the bubble and caption on the main thread. */
    private val hideOverlays: suspend (hidden: Boolean) -> Unit,
    private val isClosed: () -> Boolean,
) : DevicePort {
    private val ownPackage: String = service.packageName
    private val reader = ScreenReader(service)
    private val environment = VisualEnvironment(service)
    private val revalidator = Revalidator(ownPackage, tolerancePx = BOUNDS_TOLERANCE_PX)
    private val blockedApps = BlockedApps()
    private val appMatcher = AppMatcher(blockedApps)
    private val keyguard: KeyguardManager? = service.getSystemService(KeyguardManager::class.java)
    private val observationCounter = AtomicInteger(0)

    /** The latest observation and its live nodes. Replaced by every read, cleared by [dropHandles]. */
    @Volatile
    private var observation: ScreenReader.Reading? = null

    override suspend fun readScreen(): Screen {
        ensureUsable()
        settler.settle()
        ensureUsable()
        return withOverlaysHidden { readAndKeep() }
    }

    override suspend fun readUiSource():Screen {
        ensureUsable()
        withContext(Dispatchers.Main) {
            val info=service.serviceInfo
            if(info!=null && info.flags and AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS==0) {
                info.flags=info.flags or AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS
                service.serviceInfo=info
            }
        }
        return readScreen()
    }

    /**
     * Reads without settling, for the bubble tap: the screen is already still. The caller hides and shows the
     * bubble around this call; our own windows are left out of the observation either way.
     */
    suspend fun readScreenNow(): Screen {
        ensureUsable()
        return readAndKeep()
    }

    /** Forgets the live node map, so no later action can use a handle from an old task. */
    fun dropHandles() {
        observation = null
    }

    override suspend fun perform(action: DeviceAction): ActionResult {
        ensureUsable()
        return withOverlaysHidden {
            delay(60) // Wait for the panel surface and input region to disappear.
            withContext(Dispatchers.Default) {
                when (action) {
                    is DeviceAction.LongPress -> pressNode(action.node, false)
                    is DeviceAction.DoubleTap -> pressNode(action.node, true)
                    is DeviceAction.Drag -> drag(action)
                    is DeviceAction.Tap -> tap(action.node)
                    is DeviceAction.TapAt -> tapAt(action.x, action.y)
                    is DeviceAction.TypeText -> typeText(action.node, action.text, action.submit, action.mode)
                    is DeviceAction.Scroll -> scroll(action.direction, action.node)
                    DeviceAction.Back -> global(AccessibilityService.GLOBAL_ACTION_BACK, "没能返回")
                    DeviceAction.Home -> global(AccessibilityService.GLOBAL_ACTION_HOME, "没能回到桌面")
                    is DeviceAction.OpenApp -> openApp(action.name)
                    is DeviceAction.OpenShortcut -> openShortcut(action.shortcut)
                    // Replies go through NotificationPort, never the screen.
                    is DeviceAction.ReplyToNotification -> ActionResult.Failed("这不是屏幕操作")
                }
            }
        }.also { settler.onEvent() }
    }
    override suspend fun screenshot(): ByteArray? = shot()?.png

    /**
     * The same picture with numbered boxes over the tappable things, so a model that can see images does not have to
     * guess where a button is. Coordinates in [Shot.marks] are real pixels, matching what a tap takes.
     */
    override suspend fun shot(): Shot? = captureShot(null)
    override suspend fun zoom(bounds: Bounds): Shot? = captureShot(bounds)
    override suspend fun fontReference(text:String):FontReference? = withContext(Dispatchers.Default) {
        ensureUsable()
        val screen=observation?.screen ?: return@withContext null
        if(reader.currentNow().packageName!=screen.packageName || blockedApps.isBlocked(screen.packageName,null))return@withContext null
        environment.fontReference(screen.packageName,text)
    }

    private suspend fun captureShot(region: Bounds?): Shot? {
        ensureUsable()
        val expected = observation?.screen ?: return null
        val current = reader.current().packageName
        if (current.isEmpty() || current != expected.packageName || blockedApps.isBlocked(current,null)) return null
        val marks = marksFor(expected).filter { mark -> expected.all().none { it.isPassword && it.bounds.contains(mark.x,mark.y) } }
        return withOverlaysHidden {
            // Visibility updates reach SurfaceFlinger on a later frame; do not capture our own caption.
            delay(80)
            captureBitmap()?.let { bitmap ->
            val sensitive = expected.all().filter { it.isPassword }.map { it.bounds }.toList()
            if (sensitive.isNotEmpty()) {
                val canvas=Canvas(bitmap)
                val paint=Paint().apply { color=android.graphics.Color.BLACK }
                sensitive.forEach { canvas.drawRect(it.left.toFloat(),it.top.toFloat(),it.right.toFloat(),it.bottom.toFloat(),paint) }
            }
            val hash = contentHash(bitmap,expected.root.bounds)
            val visualContext = environment.background(bitmap,expected)
            if (region != null) {
                val root=expected.root.bounds
                if (region.left < maxOf(0,root.left) || region.top < maxOf(0,root.top) || region.right > minOf(bitmap.width,root.right) || region.bottom > minOf(bitmap.height,root.bottom) || region.right<=region.left || region.bottom<=region.top) {
                    bitmap.recycle(); return@let null
                }
                val cropped=Bitmap.createBitmap(bitmap,region.left,region.top,region.right-region.left,region.bottom-region.top)
                if(cropped !== bitmap) bitmap.recycle()
                val visible=marks.filter { region.contains(it.x,it.y) }
                encode(cropped,visible,region.left,region.top)?.let { Shot(it,visible,hash,visualContext) }
            } else encode(bitmap,marks)?.let { png -> Shot(png,marks,hash,visualContext) }
        } }
    }

    /** Sample app content before grid overlays; exclude system bars and hash no persisted pixels. */
    private fun contentHash(bitmap: Bitmap, bounds: Bounds): String {
        val left=bounds.left.coerceIn(0,bitmap.width-1)
        val right=bounds.right.coerceIn(left+1,bitmap.width)
        val top=maxOf(bounds.top,48).coerceIn(0,bitmap.height-1)
        val bottom=minOf(bounds.bottom,bitmap.height-32).coerceIn(top+1,bitmap.height)
        val digest=java.security.MessageDigest.getInstance("SHA-256")
        for (y in top until bottom step 8) for (x in left until right step 8) {
            val pixel=bitmap.getPixel(x,y)
            digest.update((pixel shr 16).toByte());digest.update((pixel shr 8).toByte());digest.update(pixel.toByte())
        }
        return digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) }
    }

    /** Tappable or described nodes, in reading order, with their centres in real screen pixels. */
    private fun marksFor(screen: Screen?): List<Mark> {
        if (screen == null) return emptyList()
        return screen.all()
            .filter { !it.isPassword && it.packageName != service.packageName && (it.clickable || !it.label().isNullOrBlank()) }
            .filter { it.bounds.right - it.bounds.left > 8 && it.bounds.bottom - it.bounds.top > 8 }
            .distinctBy { it.bounds }
            .sortedWith(compareBy({ if (it.editable) 0 else if (it.clickable) 1 else 2 }, { it.bounds.top }, { it.bounds.left }))
            .take(MarkLegend.MAX_MARKS)
            .toList()
            .mapIndexed { i, node ->
                val label = node.label()?.take(24) ?: node.role.take(24)
                Mark(
                    index = i + 1,
                    label = label.ifBlank { "位置" },
                    x = (node.bounds.left + node.bounds.right) / 2,
                    y = (node.bounds.top + node.bounds.bottom) / 2,
                )
            }
    }

    private suspend fun captureBitmap(): Bitmap? {
        return when (val first = capture()) {
            is Capture.Taken -> first.bitmap
            Capture.RateLimited -> {
                delay(SCREENSHOT_RETRY_MS)
                ensureUsable()
                (capture() as? Capture.Taken)?.bitmap
            }
            Capture.Refused -> null
        }
    }

    // --- Reading ---

    private suspend fun readAndKeep(): Screen {
        val reading = reader.read(observationCounter.getAndIncrement())
        // A read that finishes after the session closed must not keep live nodes behind dropHandles().
        if (isClosed()) throw DeviceUnavailable("dsh 的无障碍服务已关闭")
        observation = reading
        return reading.screen
    }

    private fun ensureUsable() {
        if (keyguard?.isKeyguardLocked == true) throw DeviceUnavailable("手机锁屏了")
        if (isClosed()) throw DeviceUnavailable("dsh 的无障碍服务已关闭")
    }

    private suspend fun <T> withOverlaysHidden(block: suspend () -> T): T {
        hideOverlays(true)
        try {
            return block()
        } finally {
            withContext(NonCancellable) { hideOverlays(false) }
        }
    }

    // --- Re-validation (spec §5, T3, rule 13) ---

    /**
     * The live node behind [node] when it still matches the observation, or null. The node must belong to the
     * latest observation, refresh, and pass [Revalidator.nodeStillMatches] against the window chosen right now.
     */
    private suspend fun validatedNode(node: ScreenNode): AccessibilityNodeInfo? {
        val current = observation ?: return null
        // An unknown app (empty package) never passes, matching the coordinate actions.
        if (current.screen.packageName.isEmpty()) return null
        if (current.screen.find(node.id) != node) return null
        val live = current.handles[node.id] ?: return null
        val activeWindowId = reader.current().id
        val state = if (live.refresh() && live.isEnabled) {
            LiveNodeState(
                label = labelOf(live),
                bounds = boundsOf(live),
                visibleToUser = live.isVisibleToUser,
                packageName = live.packageName?.toString().orEmpty(),
                inActiveWindow = activeWindowId != null && live.windowId == activeWindowId,
            )
        } else {
            null
        }
        return live.takeIf { revalidator.nodeStillMatches(node, state) }
    }

    /**
     * A coordinate tap is checked against a fresh reading of the chosen window: the deepest node at the point
     * must have the same label as before ([Revalidator.pointStillMatches]). Two stricter checks than the spec:
     * the point must lie on an observed node, and the app in front must be the same one, because two unlabelled
     * points in different apps would otherwise look alike.
     */
    private suspend fun pointStillMatches(x: Int, y: Int): Boolean {
        val current = observation ?: return false
        // An unknown foreground app ("" package, no readable root) escaped the block-list check: never tap in it.
        if (current.screen.packageName.isEmpty()) return false
        // A point outside every observed node is outside the window the model saw (another split-screen half,
        // a system bar). Revalidator would pass it when the live point is unlabelled too; refuse it here.
        if (current.screen.deepestAt(x, y) == null) return false
        val fresh = reader.read(current.screen.observation).screen
        if (fresh.packageName.isEmpty() || fresh.packageName != current.screen.packageName) return false
        return revalidator.pointStillMatches(current.screen, x, y, fresh)
    }

    // --- Actions ---

    private suspend fun tap(node: ScreenNode): ActionResult {
        val live = validatedNode(node) ?: return SCREEN_CHANGED
        // One physical touch. Some WebViews accept ACTION_CLICK without dispatching a DOM click;
        // falling back afterwards can instead send twice if the first click was merely delayed.
        val bounds = boundsOf(live)
        val point = planner().tapPoint(bounds)
        // The planner falls back to the middle of the screen when the node lies wholly in the edge area;
        // tapping there would hit something else, so refuse instead.
        if (!bounds.contains(point.x, point.y)) return ActionResult.Failed("这里点不动")
        return tapGesture(point)
    }

    private suspend fun tapAt(x: Int, y: Int): ActionResult {
        if (!pointStillMatches(x, y)) return SCREEN_CHANGED
        return tapGesture(Point(x, y))
    }

    private suspend fun typeText(node: ScreenNode, text: String, submit: Boolean, mode: String = "replace"): ActionResult {
        val live = validatedNode(node) ?: return SCREEN_CHANGED
        if (node.isPassword || live.isPassword || TextEntry.isPasswordType(live.inputType)) return ActionResult.Failed("从不往密码框里输入内容")
        val clipboard=service.getSystemService(ClipboardManager::class.java)
        val previous=try { clipboard?.primaryClip } catch(_:RuntimeException) { null }
        var clipLabel:String?=null
        val editor=object:TextEditor {
            override fun value():String? { live.refresh();return if(live.isShowingHintText)"" else live.text?.toString().orEmpty() }
            override fun sameTarget():Boolean {
                if(!live.refresh() || !live.isEnabled || live.isPassword || TextEntry.isPasswordType(live.inputType))return false
                val window=reader.currentNow()
                return window.id==live.windowId && window.packageName==node.packageName && live.packageName?.toString()==node.packageName
            }
            override fun focus()=live.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
            override fun selectAll(length:Int)=live.isFocused && live.performAction(AccessibilityNodeInfo.ACTION_SET_SELECTION,Bundle().apply {
                putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT,0);putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT,length)
            })
            override fun paste(text:String):Boolean {
                if(!live.isFocused || clipboard==null)return false
                val label="dsh-input-"+java.util.UUID.randomUUID().toString()
                val clip=ClipData.newPlainText(label,text)
                clip.description.extras=android.os.PersistableBundle().apply { putBoolean("android.content.extra.IS_SENSITIVE",true) }
                var dispatched=false
                return try {
                    clipboard.setPrimaryClip(clip);clipLabel=label;live.refresh()
                    if(live.actionList.none { it.id==AccessibilityNodeInfo.ACTION_PASTE })false
                    else { dispatched=true;live.performAction(AccessibilityNodeInfo.ACTION_PASTE);true }
                } catch(_:RuntimeException) { dispatched }
            }
            override fun setText(text:String):Boolean {
                if(live.actionList.none { it.id==AccessibilityNodeInfo.ACTION_SET_TEXT })return false
                live.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT,Bundle().apply {
                    putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,text)
                })
                return true
            }
            override fun commit(text:String,previousLength:Int):Boolean {
                if(Build.VERSION.SDK_INT<33 || !live.isFocused || !sameTarget())return false
                val method=service.inputMethod ?: return false
                val info=method.currentInputEditorInfo ?: return false
                if(info.packageName!=node.packageName || TextEntry.isPasswordType(info.inputType))return false
                val input=method.currentInputConnection ?: return false
                input.setSelection(0,previousLength)
                input.commitText(text,1,null)
                return true
            }
        }
        var verified=false
        try {
            val result=TextEntry.enter(editor,text,mode) { delay(80) }
            verified=result is ActionResult.Ok
            return if(result is ActionResult.Ok && submit)pressEnter(live) else result
        } finally {
            // An unverified late paste must not consume the user's previous clipboard contents.
            // Restore only after verified input, and never overwrite a user copy made during the action.
            if(verified && clipLabel!=null)try {
                if(clipboard?.primaryClipDescription?.label?.toString()==clipLabel) {
                    if(previous!=null)clipboard.setPrimaryClip(previous) else clipboard.clearPrimaryClip()
                }
            } catch(_:RuntimeException) { }
        }
    }

    /** The keyboard's Enter or Search key, sent to the field itself, so no gesture lands anywhere else. */
    private fun pressEnter(field: AccessibilityNodeInfo): ActionResult {
        val enter = AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.id
        return if (field.actionList.any { it.id == enter } && field.performAction(enter)) {
            ActionResult.Ok
        } else {
            ActionResult.Failed("文字已经填进去了，但这里没有回车键；改点搜索按钮")
        }
    }

    private suspend fun scroll(direction: Direction, node: ScreenNode?): ActionResult {
        val current = observation ?: return SCREEN_CHANGED
        val target = node ?: largestScrollable(current.screen)
        if (target == null) {
            // Nothing scrollable was seen: swipe across the middle of the screen, but only in the same, known app.
            // An unknown app ("" package) escaped the block-list check, so it is never acted in.
            if (current.screen.packageName.isEmpty()) return SCREEN_CHANGED
            if (reader.current().packageName != current.screen.packageName) return SCREEN_CHANGED
            return swipeGesture(planner().swipe(direction, null))
        }
        val live = validatedNode(target) ?: return SCREEN_CHANGED
        val scrollAction = scrollActionFor(direction)
        if (live.actionList.any { it.id == scrollAction.id } && live.performAction(scrollAction.id)) {
            return ActionResult.Ok
        }
        val bounds = boundsOf(live)
        val path = planner().swipe(direction, bounds)
        // As with taps: a swipe the planner moved out of the node would scroll something else.
        if (!bounds.contains(path.from.x, path.from.y) || !bounds.contains(path.to.x, path.to.y)) {
            return ActionResult.Failed("这里划不动")
        }
        return swipeGesture(path)
    }

    private fun global(action: Int, failure: String): ActionResult =
        if (service.performGlobalAction(action)) ActionResult.Ok else ActionResult.Failed(failure)

    /** Rule 15: launcher apps of this user only, matched by [AppMatcher], which also refuses blocked packages. */
    private fun openApp(name: String): ActionResult {
        val pm = service.packageManager
        val launcherIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val resolved: List<ResolveInfo> = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            pm.queryIntentActivities(launcherIntent, PackageManager.ResolveInfoFlags.of(0))
        } else {
            @Suppress("DEPRECATION")
            pm.queryIntentActivities(launcherIntent, 0)
        }
        val components = LinkedHashMap<LauncherApp, ComponentName>()
        for (info in resolved) {
            val activity = info.activityInfo ?: continue
            if (activity.packageName == ownPackage) continue
            val app = LauncherApp(info.loadLabel(pm).toString(), activity.packageName)
            components.putIfAbsent(app, ComponentName(activity.packageName, activity.name))
        }
        return when (val resolution = appMatcher.resolve(name, components.keys.toList())) {
            is AppResolution.Refused -> ActionResult.Failed(resolution.reason)
            is AppResolution.Launch -> {
                val component = components.getValue(resolution.app)
                val intent = Intent(Intent.ACTION_MAIN)
                    .addCategory(Intent.CATEGORY_LAUNCHER)
                    .setComponent(component)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                try {
                    service.startActivity(intent)
                    ActionResult.Ok
                } catch (e: RuntimeException) {
                    ActionResult.Failed("打不开 ${resolution.app.label}")
                }
            }
        }
    }

    /**
     * Each shortcut is tried as a list of intents, most specific first: directions prefer Google Maps' own
     * navigation link and fall back to any maps app. The app that would receive the intent is looked up first,
     * and a blocked app (or dsh itself) is refused before anything opens.
     */
    private fun openShortcut(shortcut: Shortcut): ActionResult {
        val (candidates, what) = when (shortcut) {
            is Shortcut.Directions -> listOf(
                Intent(Intent.ACTION_VIEW, Uri.parse("google.navigation:q=" + Uri.encode(shortcut.destination))),
                Intent(Intent.ACTION_VIEW, Uri.parse("geo:0,0?q=" + Uri.encode(shortcut.destination))),
            ) to "显示路线"
            is Shortcut.Dial -> listOf(Intent(Intent.ACTION_DIAL, Uri.fromParts("tel", shortcut.number, null))) to "打电话"
            is Shortcut.TextMessage -> listOf(
                Intent(Intent.ACTION_SENDTO, Uri.fromParts("smsto", shortcut.number, null)).putExtra("sms_body", shortcut.body),
            ) to "发短信"
            is Shortcut.WebSearch -> listOf(
                Intent(Intent.ACTION_WEB_SEARCH).putExtra(SearchManager.QUERY, shortcut.query),
            ) to "搜网页"
            is Shortcut.SetAlarm -> listOf(
                Intent(AlarmClock.ACTION_SET_ALARM)
                    .putExtra(AlarmClock.EXTRA_HOUR, shortcut.hour)
                    .putExtra(AlarmClock.EXTRA_MINUTES, shortcut.minute)
                    .putExtra(AlarmClock.EXTRA_SKIP_UI, true),
            ) to "设闹钟"
            is Shortcut.SetTimer -> listOf(
                Intent(AlarmClock.ACTION_SET_TIMER)
                    .putExtra(AlarmClock.EXTRA_LENGTH, shortcut.seconds)
                    .putExtra(AlarmClock.EXTRA_SKIP_UI, true),
            ) to "开计时器"
            is Shortcut.OpenUrl -> listOf(
                Intent(Intent.ACTION_VIEW, Uri.parse(shortcut.url)).addCategory(Intent.CATEGORY_BROWSABLE),
            ) to "打开网页链接"
            is Shortcut.AddEvent -> {
                val begin = shortcut.start.atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()
                listOf(
                    Intent(Intent.ACTION_INSERT, android.provider.CalendarContract.Events.CONTENT_URI)
                        .putExtra(android.provider.CalendarContract.Events.TITLE, shortcut.title)
                        .putExtra(android.provider.CalendarContract.EXTRA_EVENT_BEGIN_TIME, begin)
                        .putExtra(android.provider.CalendarContract.EXTRA_EVENT_END_TIME, begin + shortcut.minutes * 60_000L),
                ) to "加日历事件"
            }
        }
        val pm = service.packageManager
        for (intent in candidates) {
            val target = resolvedActivity(intent) ?: continue
            val label = target.loadLabel(pm).toString()
            val pkg = target.activityInfo?.packageName.orEmpty()
            if (pkg == ownPackage || blockedApps.isBlocked(pkg, label)) return ActionResult.Failed("$label 在拦截名单里")
            return try {
                service.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                ActionResult.Ok
            } catch (e: RuntimeException) {
                ActionResult.Failed("没能$what")
            }
        }
        return ActionResult.Failed("这台手机上没有应用能$what")
    }

    private fun resolvedActivity(intent: Intent): ResolveInfo? {
        val pm = service.packageManager
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            pm.resolveActivity(intent, PackageManager.ResolveInfoFlags.of(PackageManager.MATCH_DEFAULT_ONLY.toLong()))
        } else {
            @Suppress("DEPRECATION")
            pm.resolveActivity(intent, PackageManager.MATCH_DEFAULT_ONLY)
        }
    }

    // --- Gestures ---

    private suspend fun pressNode(node: ScreenNode, twice: Boolean): ActionResult {
        val live = validatedNode(node) ?: return SCREEN_CHANGED
        if (!live.isEnabled || live.isPassword) return ActionResult.Failed("目标不可用")
        if (!twice && live.performAction(AccessibilityNodeInfo.ACTION_LONG_CLICK)) return ActionResult.Ok
        val point = planner().tapPoint(node.bounds)
        if (!node.bounds.contains(point.x,point.y)) return SCREEN_CHANGED
        if (!twice) return gesture(Path().apply { moveTo(point.x.toFloat(),point.y.toFloat()) }, 650)
        val path = Path().apply { moveTo(point.x.toFloat(),point.y.toFloat()) }
        val description = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path,0,50))
            .addStroke(GestureDescription.StrokeDescription(path,120,50)).build()
        return dispatch(description)
    }

    private suspend fun drag(action: DeviceAction.Drag): ActionResult {
        if (!pointStillMatches(action.fromX,action.fromY) || !pointStillMatches(action.toX,action.toY)) return SCREEN_CHANGED
        val bounds = observation?.screen?.root?.bounds ?: return SCREEN_CHANGED
        val inset = 24
        if (listOf(action.fromX,action.toX).any { it < bounds.left+inset || it >= bounds.right-inset } ||
            listOf(action.fromY,action.toY).any { it < bounds.top+inset || it >= bounds.bottom-inset }) return SCREEN_CHANGED
        return gesture(Path().apply { moveTo(action.fromX.toFloat(),action.fromY.toFloat()); lineTo(action.toX.toFloat(),action.toY.toFloat()) },action.durationMs.toLong())
    }

    private suspend fun tapGesture(point: Point): ActionResult {
        val path = Path().apply { moveTo(point.x.toFloat(), point.y.toFloat()) }
        return gesture(path, TAP_MS)
    }

    private suspend fun swipeGesture(swipe: SwipePath): ActionResult {
        val path = Path().apply {
            moveTo(swipe.from.x.toFloat(), swipe.from.y.toFloat())
            lineTo(swipe.to.x.toFloat(), swipe.to.y.toFloat())
        }
        return gesture(path, SWIPE_MS)
    }

    /** One stroke, with our overlays hidden until the platform reports the gesture finished or cancelled. */
    private suspend fun gesture(path: Path, durationMs: Long): ActionResult {
        val description = try {
            GestureDescription.Builder()
                .addStroke(GestureDescription.StrokeDescription(path, 0, durationMs))
                .build()
        } catch (e: IllegalArgumentException) {
            return ActionResult.Failed("手势没能发出去")
        }
        return dispatch(description)
    }

    private suspend fun dispatch(description: GestureDescription): ActionResult = suspendCancellableCoroutine { cont ->
        val callback = object : AccessibilityService.GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription?) {
                if (cont.isActive) cont.resume(ActionResult.Ok)
            }
            override fun onCancelled(gestureDescription: GestureDescription?) {
                if (cont.isActive) cont.resume(GESTURE_INTERRUPTED)
            }
        }
        if (!service.dispatchGesture(description, callback, null) && cont.isActive) {
            cont.resume(ActionResult.Failed("手势没能发出去"))
        }
    }

    // --- Screenshots ---

    private sealed interface Capture {
        /** [bitmap] is a software copy, or null when the copy failed. */
        class Taken(val bitmap: Bitmap?) : Capture
        data object RateLimited : Capture
        /** A secure window, or any other refusal: the caller returns null. */
        data object Refused : Capture
    }

    private suspend fun capture(): Capture = suspendCancellableCoroutine { cont ->
        service.takeScreenshot(
            Display.DEFAULT_DISPLAY,
            Dispatchers.Default.asExecutor(),
            object : AccessibilityService.TakeScreenshotCallback {
                override fun onSuccess(screenshot: AccessibilityService.ScreenshotResult) {
                    val buffer = screenshot.hardwareBuffer
                    var hardware: Bitmap? = null
                    // A throw here would reach the uncaught handler and leave the caller suspended forever,
                    // so any failure to wrap or copy the buffer is treated like a refused screenshot.
                    val result: Capture = try {
                        hardware = Bitmap.wrapHardwareBuffer(buffer, screenshot.colorSpace)
                        Capture.Taken(hardware?.copy(Bitmap.Config.ARGB_8888, true))
                    } catch (e: RuntimeException) {
                        Capture.Refused
                    } finally {
                        hardware?.recycle()
                        buffer.close()
                    }
                    if (cont.isActive) cont.resume(result) else (result as? Capture.Taken)?.bitmap?.recycle()
                }

                override fun onFailure(errorCode: Int) {
                    val result = if (errorCode == AccessibilityService.ERROR_TAKE_SCREENSHOT_INTERVAL_TIME_SHORT) {
                        Capture.RateLimited
                    } else {
                        Capture.Refused
                    }
                    if (cont.isActive) cont.resume(result)
                }
            },
        )
    }

    /** Scales so the long side is at most [SCREENSHOT_LONG_SIDE_PX], then encodes as PNG. */
    private suspend fun encode(bitmap: Bitmap, marks: List<Mark> = emptyList(), originX: Int = 0, originY: Int = 0): ByteArray? = withContext(Dispatchers.Default) {
        try {
            val longSide = maxOf(bitmap.width, bitmap.height)
            val scaled = if (longSide > SCREENSHOT_LONG_SIDE_PX) {
                val scale = SCREENSHOT_LONG_SIDE_PX.toDouble() / longSide
                Bitmap.createScaledBitmap(
                    bitmap,
                    (bitmap.width * scale).roundToInt().coerceAtLeast(1),
                    (bitmap.height * scale).roundToInt().coerceAtLeast(1),
                    true,
                )
            } else {
                bitmap
            }
            val gridded = withGrid(scaled, realWidth = bitmap.width, realHeight = bitmap.height, marks = marks, originX=originX, originY=originY)
            val out = ByteArrayOutputStream()
            val ok = gridded.compress(Bitmap.CompressFormat.PNG, 100, out)
            if (gridded !== scaled) gridded.recycle()
            if (scaled !== bitmap) scaled.recycle()
            if (ok) out.toByteArray() else null
        } finally {
            bitmap.recycle()
        }
    }

    /**
     * A copy of [picture] with a faint grid whose labels are real screen pixels. The picture is scaled down but taps
     * use real pixels, so the model can read a position straight off a line instead of converting between the two.
     * Falls back to the plain picture if a copy cannot be made.
     */
    private fun withGrid(picture: Bitmap, realWidth: Int, realHeight: Int, marks: List<Mark> = emptyList(), originX: Int = 0, originY: Int = 0): Bitmap {
        val canvasBitmap = picture.copy(Bitmap.Config.ARGB_8888, true) ?: return picture
        val canvas = Canvas(canvasBitmap)
        val sx = picture.width.toFloat() / realWidth
        val sy = picture.height.toFloat() / realHeight
        val density = (picture.width / 540f).coerceAtLeast(1f)
        val line = Paint().apply { color = 0x66FF2D55; strokeWidth = density }
        val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFFFFFF.toInt(); textSize = 11f * density }
        val backing = Paint().apply { color = 0xCCC2185B.toInt() }
        fun label(s: String, x: Float, y: Float) {
            val w = text.measureText(s)
            canvas.drawRect(x - 1f, y - text.textSize, x + w + 1f, y + 3f * density, backing)
            canvas.drawText(s, x, y, text)
        }
        val step = GridLines.stepFor(maxOf(realWidth, realHeight))
        for (x in GridLines.marks(realWidth, step)) {
            val px = x * sx
            canvas.drawLine(px, 0f, px, picture.height.toFloat(), line)
            label((x+originX).toString(), px + 2f, text.textSize + 2f)
        }
        for (y in GridLines.marks(realHeight, step)) {
            val py = y * sy
            canvas.drawLine(0f, py, picture.width.toFloat(), py, line)
            label((y+originY).toString(), 2f, py - 2f)
        }
        // Numbered boxes last so they sit on top of the grid. The number is what the legend refers to; the box is
        // drawn in the same colour family as the grid so a busy screenshot stays readable.
        if (marks.isNotEmpty()) {
            val box = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE
                color = 0xCC1B8A5A.toInt()
                strokeWidth = 2f * density
            }
            val badge = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF1B8A5A.toInt() }
            val number = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = 0xFFFFFFFF.toInt()
                textSize = 12f * density
                textAlign = Paint.Align.CENTER
            }
            val radius = 9f * density
            for (mark in marks) {
                val cx = (mark.x-originX) * sx
                val cy = (mark.y-originY) * sy
                canvas.drawCircle(cx, cy, radius, badge)
                canvas.drawCircle(cx, cy, radius, box)
                canvas.drawText(mark.index.toString(), cx, cy + number.textSize / 3f, number)
            }
        }
        return canvasBitmap
    }

    // --- Helpers ---

    /** A planner for the current display, so rotation and split screen are always taken into account. */
    private fun planner(): GesturePlanner {
        val metrics = service.getSystemService(WindowManager::class.java).currentWindowMetrics
        val screen = metrics.bounds
        val gestures = metrics.windowInsets.getInsetsIgnoringVisibility(WindowInsets.Type.systemGestures())
        val minimum = (MIN_EDGE_DP * service.resources.displayMetrics.density).roundToInt()
        // GesturePlanner keeps one inset on every side; cap it so the safe area never turns inside out.
        val inset = maxOf(gestures.left, gestures.top, gestures.right, gestures.bottom, minimum)
            .coerceAtMost(minOf(screen.width(), screen.height()) / 4)
        return GesturePlanner(screen.toBounds(), inset)
    }

    private fun largestScrollable(screen: Screen): ScreenNode? = screen.all()
        .filter { it.scrollable }
        .maxByOrNull { (it.bounds.right - it.bounds.left).toLong() * (it.bounds.bottom - it.bounds.top).toLong() }

    private fun scrollActionFor(direction: Direction): AccessibilityAction = when (direction) {
        Direction.UP -> AccessibilityAction.ACTION_SCROLL_UP
        Direction.DOWN -> AccessibilityAction.ACTION_SCROLL_DOWN
        Direction.LEFT -> AccessibilityAction.ACTION_SCROLL_LEFT
        Direction.RIGHT -> AccessibilityAction.ACTION_SCROLL_RIGHT
    }

    private fun boundsOf(node: AccessibilityNodeInfo): Bounds = Rect().also { node.getBoundsInScreen(it) }.toBounds()

    /** The live label with exactly [ScreenNode.label] semantics, by building a throwaway node. */
    private fun labelOf(node: AccessibilityNodeInfo): String? = ScreenNode(
        id = "",
        role = "",
        text = node.text?.toString()?.takeUnless { node.isShowingHintText },
        description = node.contentDescription?.toString(),
        hint = node.hintText?.toString(),
        packageName = "",
        bounds = Bounds(0, 0, 0, 0),
    ).label()

    private companion object {
        const val BOUNDS_TOLERANCE_PX = 4
        const val TAP_MS = 50L

        /** Time an app gets to apply typed text before the field is read back. */
        const val TYPE_SETTLE_MS = 150L
        const val SWIPE_MS = 300L
        const val MIN_EDGE_DP = 24
        const val MAX_ANCESTORS = 60
        const val SCREENSHOT_LONG_SIDE_PX = 1_280
        const val SCREENSHOT_RETRY_MS = 1_000L
        val SCREEN_CHANGED = ActionResult.Failed("界面变了")
        val GESTURE_INTERRUPTED = ActionResult.Failed("手势被打断了", mayHaveEffect = true)
    }
}
