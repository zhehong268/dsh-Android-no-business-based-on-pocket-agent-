package dev.pocketagent.device

import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SettlerTest {
    @Test
    fun aQuietPageReturnsAfter40ms() = runTest {
        val settler = Settler(now = { testScheduler.currentTime })
        settler.settle()
        assertEquals(40, testScheduler.currentTime)
    }

    @Test
    fun anEventAt30msMovesItTo70ms() = runTest {
        val settler = Settler(now = { testScheduler.currentTime })
        launch {
            delay(30)
            settler.onEvent()
        }
        settler.settle()
        assertEquals(70, testScheduler.currentTime)
    }

    @Test
    fun aConstantStreamCannotPushTheWaitPast100ms() = runTest {
        val settler = Settler(now = { testScheduler.currentTime })
        val feeder = launch {
            while (true) {
                delay(20)
                settler.onEvent()
            }
        }
        settler.settle()
        assertEquals(100, testScheduler.currentTime)
        feeder.cancel()
    }

    @Test
    fun timeSpentBeforeSettlingCountsAgainstTheSameBudget() = runTest {
        val settler = Settler(now = { testScheduler.currentTime })
        val deadline = settler.deadline()
        delay(80)
        settler.onEvent()
        settler.settle(deadline)
        assertEquals(100, testScheduler.currentTime)
    }

    @Test
    fun cancellationStopsTheWait() = runTest {
        val settler = Settler(now = { testScheduler.currentTime })
        val job = launch { settler.settle() }
        advanceTimeBy(20)
        runCurrent()
        job.cancel()
        runCurrent()
        assertTrue(job.isCancelled)
        assertEquals(20, testScheduler.currentTime)
    }

    @Test
    fun eventsBeforeTheCallDoNotDelay() = runTest {
        val settler = Settler(now = { testScheduler.currentTime })
        settler.onEvent()
        delay(1_000)
        settler.settle()
        assertEquals(1_000, testScheduler.currentTime)
    }
}
