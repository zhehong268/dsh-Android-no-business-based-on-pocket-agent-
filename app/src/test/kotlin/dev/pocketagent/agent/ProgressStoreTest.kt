package dev.pocketagent.agent

import org.junit.Test
import kotlin.test.*

class ProgressStoreTest {
    @Test fun startingAnotherTaskDoesNotDestroyTheFailedTask() {
        val values=MemoryValues();val store=ProgressStore(values)
        store.save(TaskProgress("任务一",completed="已找到联系人",next="填写草稿"))
        store.save(TaskProgress("任务二",completed="已打开页面"))
        val restarted=ProgressStore(values)
        assertEquals("已找到联系人",restarted.load("任务一")!!.completed)
        restarted.clear("任务二")
        assertEquals("任务一",restarted.load()!!.task)
    }
    @Test fun onlyTwelveIncompleteTasksAreKeptAndUpdatesDoNotDuplicate() {
        val store=ProgressStore(MemoryValues())
        repeat(13) { store.save(TaskProgress("任务$it")) }
        assertNull(store.load("任务0"));assertNotNull(store.load("任务1"))
        store.save(TaskProgress("任务1",completed="新进度"));assertEquals("新进度",store.load("任务1")!!.completed)
    }
    @Test fun onlyExplicitShortContinuationRequestsSelectTheSavedTask() {
        assertTrue(ProgressStore.isContinuation("继续任务！"))
        assertFalse(ProgressStore.isContinuation("继续对话并发一条消息"))
        assertFalse(ProgressStore.isContinuation("请继续分析这份资料"))
    }
}
