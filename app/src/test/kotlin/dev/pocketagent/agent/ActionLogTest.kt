package dev.pocketagent.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ActionLogTest {

    @Test
    fun emptyTrailShowsNothing() {
        val log = ActionLog()
        assertEquals("", log.block())
        assertEquals(0, log.size)
    }

    @Test
    fun trailKeepsTheOrderItHappened() {
        val log = ActionLog()
        log.add("点击「提交」", "成功")
        log.add("看屏幕", "读到一屏")
        assertEquals("- 点击「提交」 → 成功\n- 看屏幕 → 读到一屏", log.block())
    }

    @Test
    fun oldestEntryFallsOffAfterTheLimit() {
        val log = ActionLog(limit = 2)
        log.add("一", "成功")
        log.add("二", "成功")
        log.add("三", "成功")
        assertEquals(2, log.size)
        assertEquals("- 二 → 成功\n- 三 → 成功", log.block())
    }

    @Test
    fun blankLabelsAreIgnored() {
        val log = ActionLog()
        log.add("   ", "成功")
        log.add("", "成功")
        assertEquals(0, log.size)
    }

    @Test
    fun repeatedNeedsTheSameOutcomeTwice() {
        val log = ActionLog()
        log.add("点击「下一步」", "屏幕没变")
        assertFalse(log.repeated("点击「下一步」", "屏幕没变"))
        log.add("点击「下一步」", "屏幕没变")
        assertTrue(log.repeated("点击「下一步」", "屏幕没变"))
        assertFalse(log.repeated("点击「下一步」", "成功"))
        assertFalse(log.repeated("点击「返回」", "屏幕没变"))
    }

    @Test
    fun clearEmptiesTheTrail() {
        val log = ActionLog()
        log.add("一", "成功")
        log.clear()
        assertEquals("", log.block())
    }
}
