package dev.pocketagent.ui

import org.junit.Test
import java.io.File
import kotlin.test.assertTrue

class NoticeTextTest {
    private val xml = File("src/main/res/values/strings.xml").readText()

    private fun string(name: String): String {
        val match = Regex("<string name=\"$name\">(.*?)</string>", RegexOption.DOT_MATCHES_ALL).find(xml)
        return requireNotNull(match) { "missing string $name" }.groupValues[1]
    }

    @Test
    fun noticeCoversTheFourPoints() {
        val body = string("notice_body").lowercase()
        // The same four promises, in Chinese: only your provider gets the text, nothing is stored, risky actions
        // wait for a yes, and a long press stops the task.
        for (word in listOf("只会直接发给", "不发往别处", "不会留下任何记录", "会先问你", "在任务页点「停止」")) {
            assertTrue(word in body, "notice_body should contain \"$word\"")
        }
        for (name in listOf("notice_title", "notice_accept")) assertTrue(string(name).isNotBlank())
    }

    @Test
    fun customHostNoticeTakesTheHost() {
        assertTrue("%1\$s" in string("notice_custom_host"))
    }

    @Test
    fun micNoticeSaysTheRecogniserMayUseTheCloud() {
        val mic = string("notice_mic").lowercase()
        assertTrue("cloud" in mic || "online" in mic || "云端" in mic || "联网" in mic, mic)
    }
}
