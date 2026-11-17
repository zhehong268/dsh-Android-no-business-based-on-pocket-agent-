package dev.pocketagent.agent

/** One suggestion on the task screen: what the user sees ([title], [subtitle]) and the full task it sends ([task]). */
data class QuickTask(val title: String, val subtitle: String, val task: String, val onPhone: OnPhone? = null)

/** A quick task that runs on the phone itself, with no AI. */
enum class OnPhone { LATEST_OTP, RECORD_ROUTINE }

/**
 * The suggestions on the task screen. The title is a short label, but the task is written out in full: a model
 * that is only told "带我去某个地址" has to guess the app and the goal, and small or free models often
 * guess wrong. The user sees the full task in the box and can edit it before sending.
 */
object QuickTasks {
    /** The task box accepts this many characters. */
    const val MAX_TASK_CHARS = 2000

    val all: List<QuickTask> = listOf(
        QuickTask(
            title = "看看有什么新消息",
            subtitle = "把新消息缩成几句话",
            task = "总结我的新消息：如果你有 read_notifications 工具就先调它；没有就打开我的聊天应用，读最新的未读消息。" +
                "不是必须就不要打开别的应用。什么都不要回复。",
        ),
        QuickTask(
            title = "回这条消息",
            subtitle = "先起草，发送前问你",
            task = "用简短、礼貌的话回复这个屏幕上的最新消息，把内容打进回复框，然后按发送。",
        ),
        QuickTask(
            title = "带我去这个地址",
            subtitle = "从消息或地图链接里找",
            task = "在这个屏幕上的消息里找出地址，用 directions 工具开始导航过去。" +
                "只有那一步失败时，才打开 Google 地图，清掉搜索框里的旧内容，再输入地址。",
        ),
        QuickTask(
            title = "这是啥？",
            subtitle = "解释内容，识别诈骗",
            task = "用平常话解释这个屏幕上是什么。如果是消息或推销，说说它像不像骗局、为什么。" +
                "如果是外语就翻译过来。什么都不要改。",
        ),
        QuickTask(
            title = "发微信",
            subtitle = "先问清楚发给谁、发什么",
            task = "用微信给一个人发消息。先 ask_user 问清楚：发给谁（名字或备注）、发什么内容；我回答之后再打开微信。" +
                "在微信里用 tap_text 按名字找联系人（找不到就换关键词，它会自己往下滚），点进聊天，" +
                "点底部输入框，输入内容，最后点「发送」并把 irreversible 设为 true。" +
                "绝对不要点红包、转账、支付，也不要在没得到我确认前按发送。",
        ),        QuickTask(
            title = "答题",
            subtitle = "逐题作答、记答题卡，不自己交卷",
            task = "把屏幕上这份卷子答完。一题一题来：先看清题号和选项，选出答案后重新读屏确认选中了，" +
                "然后把这一题记到答题卡；拿不准的题可以问另一个模型。不会的题先记下题号，最后回头再答。" +
                "全部答完先停下，问我要不要提交，不要自己按提交。",
        ),
        QuickTask(
            title = "最新验证码",
            subtitle = "显示最新的那条验证码",
            task = "",
            onPhone = OnPhone.LATEST_OTP,
        ),
        QuickTask(
            title = "录制我的操作",
            subtitle = "做一次，生成一份流程草稿",
            task = "",
            onPhone = OnPhone.RECORD_ROUTINE,
        ),
    )
}
