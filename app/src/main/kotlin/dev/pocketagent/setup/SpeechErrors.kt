package dev.pocketagent.setup

/** Short messages for the codes of `SpeechRecognizer.ERROR_*`. Pure, so it is tested on the JVM. */
object SpeechErrors {
    fun messageFor(errorCode: Int): String = when (errorCode) {
        6, 7 -> "没有听到任何内容。请重试，或改用输入。"
        1, 2, 4, 11 -> "语音识别器当前需要网络连接。请改用输入。"
        8 -> "语音识别器正忙。请稍后再试。"
        9 -> "麦克风权限未开启。请在设置界面中开启。"
        12, 13 -> "你的手机语言暂不支持语音。请改用输入。"
        else -> "无法听写。请改用输入。"
    }
}
