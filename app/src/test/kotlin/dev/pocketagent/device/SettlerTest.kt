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
    fun aQuietPageReturnsAfter60ms() = runTest {
        val settler = Settler(now = { testScheduler.currentTime })
        settler.settle()
        assertEquals(60, testScheduler.currentTime)
    }

    @Test
    fun anEventAt40msMovesItTo100ms() = runTest {
        val settler = Settler(now = { testScheduler.currentTime })
        launch {
            delay(40)
            settler.onEvent()
        }
        settler.settle()
        assertEquals(100, testScheduler.currentTime)
    }

    @Test
    fun aConstantStreamCannotPushTheWaitPast300ms() = runTest {
        val settler = Settler(now = { testScheduler.currentTime })
        val feeder = launch {
            while (true) {
                delay(20)
                settler.onEvent()
            }
        }
        settler.settle()
        assertEquals(300, testScheduler.currentTime)
        feeder.cancel()
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
