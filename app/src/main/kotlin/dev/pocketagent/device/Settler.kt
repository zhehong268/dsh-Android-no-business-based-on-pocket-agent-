package dev.pocketagent.device

import kotlinx.coroutines.delay

/**
 * Waits for the screen to stop changing. [onEvent] may be called from any thread;
 * [settle] waits for [quietMs] without events. The caller can share its deadline with a read retry.
 */
class Settler(
    private val now: () -> Long,
    private val quietMs: Long = 25,
    private val maxMs: Long = 50,
) {
    @Volatile
    private var lastEvent = now()

    fun onEvent() {
        lastEvent = now()
    }

    fun deadline(): Long = now() + maxMs

    suspend fun settle(deadline: Long = deadline()) {
        while (true) {
            val t = now()
            val quiet = t - lastEvent
            val remaining = deadline - t
            if (quiet >= quietMs || remaining <= 0) return
            delay(minOf(quietMs - quiet, remaining))
        }
    }
}
