package dev.pocketagent.device

import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.Test
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class ScreenshotPacerTest {
    @Test fun theFirstScreenshotDoesNotWaitAndTheNextWaitsOnlyTheRemainder()=runTest {
        val pacer=ScreenshotPacer(now={ testScheduler.currentTime })
        pacer.awaitSlot();assertEquals(0,testScheduler.currentTime)
        delay(200);pacer.awaitSlot();assertEquals(350,testScheduler.currentTime)
        delay(400);pacer.awaitSlot();assertEquals(750,testScheduler.currentTime)
    }
    @Test fun cancellationDoesNotReserveAFutureSlot()=runTest {
        val pacer=ScreenshotPacer(now={ testScheduler.currentTime })
        pacer.awaitSlot()
        val waiting=launch { pacer.awaitSlot() }
        advanceTimeBy(100);waiting.cancelAndJoin()
        delay(300);pacer.awaitSlot();assertEquals(400,testScheduler.currentTime)
    }
}
