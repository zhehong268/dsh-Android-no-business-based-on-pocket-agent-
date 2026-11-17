package dev.pocketagent.playbook

import dev.pocketagent.safety.BlockedApps

/**
 * Turns the taps a person makes into a playbook draft. It keeps only what a playbook needs: the app, the titles of
 * the screens that opened, and the labels of the buttons that were tapped.
 *
 * What it never keeps: anything typed, anything from a password or text field, labels that look personal (an
 * email address, a long number), and anything from a blocked app or from dsh itself. The result goes
 * through the same checks as a bundled playbook, so a draft that could not ship is refused, not saved.
 */
class RoutineRecorder(private val blocked: BlockedApps, private val ownPackage: String) {
    private sealed interface Step {
        val packageName: String

        data class Tap(override val packageName: String, val label: String?) : Step
        data class Screen(override val packageName: String, val title: String) : Step
    }

    private val steps = ArrayList<Step>()

    val stepCount: Int get() = steps.size

    /** A tap on a button or row. [editable] is true for text fields and password fields, which are never recorded. */
    fun tap(packageName: String, label: String?, editable: Boolean) {
        if (editable || !wanted(packageName)) return
        add(Step.Tap(packageName, clean(label)))
    }

    /** A new screen or dialog opened, with its title when it has one. */
    fun screen(packageName: String, title: String?) {
        if (!wanted(packageName)) return
        val clean = clean(title) ?: return
        add(Step.Screen(packageName, clean))
    }

    /**
     * The draft for the app the person spent the most taps in, or a failure that says why there is none.
     * [appLabel] and [version] describe that app.
     */
    fun build(appLabel: (String) -> String, version: (String) -> String): Result<Draft> {
        val taps = steps.filterIsInstance<Step.Tap>()
        if (taps.isEmpty()) return Result.failure(IllegalStateException("没有记录到任何点击。"))
        val packageName = taps.groupingBy { it.packageName }.eachCount().maxByOrNull { it.value }!!.key
        val mine = steps.filter { it.packageName == packageName }
        val app = clean(appLabel(packageName)) ?: packageName
        val versionName = clean(version(packageName)) ?: "unknown"
        var keep = mine.size.coerceAtMost(MAX_STEPS)
        while (keep > 0) {
            val text = render(app, packageName, versionName, mine.take(keep), cut = keep < mine.size)
            val parsed = PlaybookStore.parse("$packageName.md", text, blocked)
            if (parsed.isSuccess) return Result.success(Draft(packageName, app, text, keep, mine.size))
            // Too long is the only failure a shorter draft can fix; anything else will not improve by cutting.
            if (!parsed.exceptionOrNull()?.message.orEmpty().contains("bytes")) return Result.failure(parsed.exceptionOrNull()!!)
            keep--
        }
        return Result.failure(IllegalStateException("录制太长了，没法保存。"))
    }

    /** A playbook draft: the file text, which app it is for, and how many of the recorded steps fit. */
    data class Draft(val packageName: String, val app: String, val text: String, val steps: Int, val recorded: Int)

    private fun render(app: String, packageName: String, version: String, list: List<Step>, cut: Boolean): String {
        val body = StringBuilder("这是一个人实际做了一遍录下来的流程。当成参考，不要当脚本。\n\n")
        list.forEachIndexed { index, step ->
            val line = when (step) {
                is Step.Tap -> if (step.label != null) "点「${step.label}」。" else "点一个没有标签的按钮。"
                is Step.Screen -> "打开「${step.title}」这个界面。"
            }
            body.append(index + 1).append(". ").append(line).append('\n')
        }
        if (cut) body.append("（为了简短，后面的步骤省略了。）\n")
        return "---\napp: $app\npackage: $packageName\ntested_version: $version\n---\n\n$body"
    }

    private fun wanted(packageName: String): Boolean =
        packageName.isNotEmpty() && packageName != ownPackage && packageName !in IGNORED && !blocked.isBlocked(packageName, null)

    /** Skips a step that repeats the one before it: a double tap, or a screen event that fires twice. */
    private fun add(step: Step) {
        if (steps.lastOrNull() == step) return
        steps.add(step)
    }

    /** One short line with no quotes or colons, or null when there is nothing worth keeping or it could be personal. */
    private fun clean(raw: String?): String? {
        val text = raw.orEmpty().filter { !it.isISOControl() || it == ' ' }
            .replace('"', ' ').replace('\'', ' ').replace(':', ' ')
            .replace("screen_data", "").replace("app_tips", "")
            .replace(WHITESPACE, " ").trim()
        if (text.isEmpty() || text.length > MAX_LABEL) return null
        if ('@' in text || LONG_NUMBER.containsMatchIn(text.replace(" ", ""))) return null
        return text
    }

    private companion object {
        const val MAX_STEPS = 25
        const val MAX_LABEL = 40
        val WHITESPACE = Regex("\\s+")
        val LONG_NUMBER = Regex("\\d{5,}")

        /** The notification shade is not part of an app's routine. */
        val IGNORED = setOf("com.android.systemui")
    }
}
