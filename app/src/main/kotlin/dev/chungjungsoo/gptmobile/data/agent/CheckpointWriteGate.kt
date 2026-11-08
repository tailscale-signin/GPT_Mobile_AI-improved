package dev.chungjungsoo.gptmobile.data.agent

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred

/** Share one progress write across contributors; terminal drains pass queued replaceable updates. */
internal class CheckpointWriteGate {
    private class Waiter(val urgent: () -> Boolean, val ready: CompletableDeferred<Unit> = CompletableDeferred())
    private val lock = Any()
    private val waiting = ArrayDeque<Waiter>()
    private var active = false

    suspend fun <T> withPermit(urgent: () -> Boolean, block: suspend () -> T): T {
        val waiter = synchronized(lock) {
            if (!active) {
                active = true
                null
            } else {
                Waiter(urgent).also(waiting::addLast)
            }
        }
        if (waiter != null) {
            try {
                waiter.ready.await()
            } catch (cancelled: CancellationException) {
                synchronized(lock) {
                    // A grant racing cancellation must hand its permit to the next waiter.
                    if (!waiting.remove(waiter)) releaseLocked()
                }
                throw cancelled
            }
        }
        return try {
            block()
        } finally {
            synchronized(lock) { releaseLocked() }
        }
    }

    private fun releaseLocked() {
        val next = waiting.firstOrNull { it.urgent() } ?: waiting.firstOrNull()
        if (next == null) {
            active = false
        } else {
            waiting.remove(next)
            next.ready.complete(Unit)
        }
    }
}
