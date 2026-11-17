package dev.pocketagent.agent

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class QuickTasksTest {
    @Test
    fun keepsTheSuggestionsInOrder() {
        assertEquals(
            listOf("看看有什么新消息", "回这条消息", "带我去这个地址", "这是啥？", "发微信", "答题", "最新验证码", "录制我的操作"),
            QuickTasks.all.map { it.title },
        )
    }

    @Test
    fun everyTaskIsMoreSpecificThanItsTitle() {
        QuickTasks.all.filter { it.onPhone == null }.forEach {
            assertTrue(it.task.length > it.title.length + 20, "${it.title}: task is too vague")
            assertFalse(it.task.equals(it.title, ignoreCase = true), it.title)
        }
    }

    @Test
    fun addressTaskNamesTheAppAndTheGoal() {
        val task = QuickTasks.all.first { it.title == "带我去这个地址" }.task
        assertTrue("Google 地图" in task, task)
        assertTrue("directions" in task.lowercase(), task)
    }

    @Test
    fun addressTaskTellsTheAgentToClearAnOldSearch() {
        val task = QuickTasks.all.first { it.title == "带我去这个地址" }.task
        assertTrue("清掉搜索框" in task, task)
    }

    @Test
    fun tasksFitTheInputBoxAndAreSingleLinePlainText() {
        QuickTasks.all.forEach {
            assertTrue(it.task.length <= QuickTasks.MAX_TASK_CHARS, it.title)
            assertTrue(it.task.none { c -> c.isISOControl() }, it.title)
        }
    }

    @Test
    fun replyTaskDoesNotPromiseToSendWithoutAsking() {
        val task = QuickTasks.all.first { it.title == "回这条消息" }.task.lowercase()
        assertFalse("不问" in task)
        assertFalse("无需询问" in task)
    }

    @Test
    fun onlyTheOnPhoneTilesHaveNoTaskText() {
        val onPhone = QuickTasks.all.filter { it.onPhone != null }
        assertEquals(listOf("最新验证码", "录制我的操作"), onPhone.map { it.title })
        assertEquals(listOf(OnPhone.LATEST_OTP, OnPhone.RECORD_ROUTINE), onPhone.map { it.onPhone })
        assertTrue(onPhone.all { it.task.isEmpty() })
        assertTrue(QuickTasks.all.filter { it.onPhone == null }.all { it.task.isNotBlank() })
    }

    @Test
    fun readThisScreenOutIsGone() {
        assertTrue(QuickTasks.all.none { it.title == "读出这个屏幕" })
    }

    @Test
    fun catchMeUpUsesNotificationsAndNeverReplies() {
        val t = QuickTasks.all.first { it.title == "看看有什么新消息" }
        assertTrue("read_notifications" in t.task)
        assertTrue("不要回复" in t.task)
        // Without notification access the tool is not offered, so the task must not depend on it.
        assertTrue("如果你有 read_notifications 工具" in t.task, t.task)
        assertTrue("没有就打开我的聊天应用" in t.task, t.task)
        assertEquals("把新消息缩成几句话", t.subtitle)
    }

    @Test
    fun whatsThisChecksForScamsAndChangesNothing() {
        val t = QuickTasks.all.first { it.title == "这是啥？" }
        assertTrue("骗局" in t.task)
        assertTrue("什么都不要改" in t.task)
        assertEquals("解释内容，识别诈骗", t.subtitle)
    }
}
