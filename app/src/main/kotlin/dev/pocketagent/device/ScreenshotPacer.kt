package dev.pocketagent.device

import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** AOSP rate-limits display screenshots to 333 ms. Wait only for the remaining interval. */
class ScreenshotPacer(private val now:()->Long,private val intervalMs:Long=350) {
    private val mutex=Mutex()
    private var attemptedAt:Long?=null
    suspend fun awaitSlot()=mutex.withLock {
        val wait=attemptedAt?.let { (intervalMs-(now()-it)).coerceAtLeast(0) } ?: 0
        if(wait>0)delay(wait)
        attemptedAt=now()
    }
}
