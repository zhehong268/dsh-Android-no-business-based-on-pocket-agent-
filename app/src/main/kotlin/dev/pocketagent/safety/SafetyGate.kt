package dev.pocketagent.safety

import dev.pocketagent.device.DeviceAction
import dev.pocketagent.device.Screen
import dev.pocketagent.device.ScreenNode
import java.util.Locale

sealed interface Verdict {
    data object Allow : Verdict
    data class Confirm(val reason: String) : Verdict
    data class Block(val reason: String) : Verdict
}

/**
 * Decides whether an action may run, needs the user's confirmation, or is refused. Plain rules only: the
 * model proposes, and nothing it writes can change a verdict. The model can raise the risk of an action
 * (the irreversible flag) but never lower it.
 */
class SafetyGate(private val blocked: BlockedApps, private val ownPackage: String) {

    /** Blocks the whole task when the foreground app is on the block list. */
    fun checkApp(screen: Screen): Verdict =
        if (blocked.isBlocked(screen.packageName, screen.appLabel)) {
            Verdict.Block("${screen.appLabel ?: screen.packageName} 在拦截名单里")
        } else {
            Verdict.Allow
        }

    fun check(action: DeviceAction, screen: Screen, irreversible: Boolean): Verdict = when (action) {
        is DeviceAction.LongPress -> checkTap(action.node, screen, irreversible)
        is DeviceAction.DoubleTap -> checkTap(action.node, screen, irreversible)
        is DeviceAction.Drag -> when {
            screen.ownWindowBounds.any { it.contains(action.fromX,action.fromY) || it.contains(action.toX,action.toY) } -> OWN_WINDOW
            else -> Verdict.Confirm("拖动可能移动、删除或提交内容")
        }
        is DeviceAction.Tap -> checkTap(action.node, screen, irreversible)
        is DeviceAction.TapAt -> checkTapAt(action.x, action.y, screen, irreversible)
        is DeviceAction.TypeText -> when {
            action.node.packageName == ownPackage -> OWN_WINDOW
            action.node.isPassword -> Verdict.Block("从不往密码框里输入内容")
            action.submit -> checkSubmit(action.node, screen)
            else -> Verdict.Allow
        }
        is DeviceAction.Scroll -> if (action.node?.packageName == ownPackage) OWN_WINDOW else Verdict.Allow
        is DeviceAction.OpenApp ->
            if (blocked.isBlocked("", action.name)) Verdict.Block("${action.name} 在拦截名单里") else Verdict.Allow
        // Sending a message is never undone, whatever the model says: always ask, unless the app is blocked.
        is DeviceAction.ReplyToNotification ->
            if (blocked.isBlocked(action.packageName, action.appLabel)) {
                Verdict.Block("${action.appLabel ?: action.packageName} 在拦截名单里")
            } else {
                Verdict.Confirm("回复会发出一条消息")
            }
        // Nothing is sent, called or paid by the shortcut itself. The device refuses one that would land in a
        // blocked app, and the loop's checkApp ends the task if a blocked app comes to the front anyway.
        is DeviceAction.OpenShortcut -> Verdict.Allow
        DeviceAction.Back, DeviceAction.Home -> Verdict.Allow
    }

    /**
     * Enter can send a message as well as run a search, and nothing on the screen says which one it will do. So
     * a submit needs the user's approval whenever the field sits near a risky label, a risky button such as Send
     * is visible anywhere, or the screen shows an amount. A plain search screen has none of these.
     */
    private fun checkSubmit(field: ScreenNode, screen: Screen): Verdict {
        riskyLabelNear(field, screen)?.let { return Verdict.Confirm("在「$it」里按回车可能会发出去") }
        screen.all().mapNotNull { it.label() }.firstOrNull { RiskyLabels.isRisky(it) }
            ?.let { return Verdict.Confirm("在这个界面上按回车可能会触发「$it」") }
        if (screen.showsMoney()) return Verdict.Confirm("在显示金额的界面上按了回车")
        return Verdict.Allow
    }

    private fun checkTapAt(x: Int, y: Int, screen: Screen, irreversible: Boolean): Verdict {
        if (screen.ownWindowBounds.any { it.contains(x, y) }) return OWN_WINDOW
        if (screen.all().none { it.label() != null }) {
            return Verdict.Confirm("这个界面没有可读的文字标签，没法核对这次点击")
        }
        // Judge every element under the point, not only one of them: overlapping windows decide which element
        // really receives the touch, and a screen's tree order does not tell us that.
        screen.all().filter { it.bounds.contains(x, y) }.firstNotNullOfOrNull { riskyOnPath(it, screen) }
            ?.let { return Verdict.Confirm("「$it」看起来会付款、发送或删除") }
        val target = screen.deepestAt(x, y)
        if (target != null) {
            val verdict = checkTap(target, screen, irreversible)
            val blindTap = target.label() == null && screen.all().any { RiskyLabels.isRisky(it.label()) }
            return if (verdict == Verdict.Allow && blindTap) {
                Verdict.Confirm("界面上有高风险按钮时，点了一个没有标签的元素")
            } else {
                verdict
            }
        }
        return when {
            irreversible -> Verdict.Confirm("助手把这一步标记为不可撤销")
            screen.all().any { RiskyLabels.isRisky(it.label()) } -> Verdict.Confirm("界面上有高风险按钮，而这次点击不在已知元素上")
            else -> Verdict.Allow
        }
    }

    private fun checkTap(node: ScreenNode, screen: Screen, irreversible: Boolean): Verdict {
        if (node.packageName == ownPackage) return OWN_WINDOW
        if (irreversible) return Verdict.Confirm("助手把这一步标记为不可撤销")
        riskyLabelNear(node, screen)?.let { return Verdict.Confirm("「$it」看起来会付款、发送或删除") }
        if ((sequenceOf(node) + descendants(node)).any { isGenericAffirmative(it) } && screen.showsMoney()) {
            return Verdict.Confirm("在显示金额的界面上点了通用的确认按钮")
        }
        return Verdict.Allow
    }

    /** The risky label on the node, any descendant, or the nearest labelled ancestor, or null if none is risky. */
    private fun riskyLabelNear(node: ScreenNode, screen: Screen): String? {
        val inside = sequenceOf(node) + descendants(node)
        inside.mapNotNull { it.label() }.firstOrNull { RiskyLabels.isRisky(it) }?.let { return it }
        var ancestor = screen.parentOf(node)
        while (ancestor != null) {
            val label = ancestor.label()
            if (label != null) return label.takeIf { RiskyLabels.isRisky(it) }
            ancestor = screen.parentOf(ancestor)
        }
        return null
    }

    /** The risky label on the node itself or on its nearest labelled ancestor, or null. */
    private fun riskyOnPath(node: ScreenNode, screen: Screen): String? {
        node.label()?.takeIf { RiskyLabels.isRisky(it) }?.let { return it }
        var ancestor = screen.parentOf(node)
        while (ancestor != null) {
            val label = ancestor.label()
            if (label != null) return label.takeIf { RiskyLabels.isRisky(it) }
            ancestor = screen.parentOf(ancestor)
        }
        return null
    }

    private fun descendants(node: ScreenNode): Sequence<ScreenNode> =
        node.children.asSequence().flatMap { sequenceOf(it) + descendants(it) }

    private fun isGenericAffirmative(node: ScreenNode): Boolean =
        node.label()?.let { matchForm(it).lowercase(Locale.ROOT) } in AFFIRMATIVES

    private fun Screen.showsMoney(): Boolean = all().mapNotNull { it.label() }.any { label ->
        matchForm(label).any { it in CURRENCY_SYMBOLS } || MONEY_WORDS.find(label) || MONEY_WORDS_ZH.find(label)
    }

    private companion object {
        val OWN_WINDOW = Verdict.Block("dsh 自己的界面不在操作范围内")
        val AFFIRMATIVES = setOf(
            "ok", "yes", "continue", "proceed", "done", "submit",
            "确定", "确认", "继续", "提交", "完成", "好的", "同意", "下一步", "去支付", "立即购买", "立即支付",
        )
        const val CURRENCY_SYMBOLS = "₹\$€£¥￥"
        val MONEY_WORDS = wordStartPattern(listOf("total", "order", "pay", "amount"))
        val MONEY_WORDS_ZH = wordStartPattern(listOf("合计", "总额", "应付", "实付", "金额", "订单", "支付", "付款", "小计"))
    }
}
