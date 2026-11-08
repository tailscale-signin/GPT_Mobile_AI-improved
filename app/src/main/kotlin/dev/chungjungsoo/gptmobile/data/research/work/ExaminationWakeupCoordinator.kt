package dev.chungjungsoo.gptmobile.data.research.work

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * A signal follows a durable outbox/configuration write. A worker that has not
 * started yet will observe it, so repeated signals can share that pending work.
 * A running worker may already have passed its last queue read: append a durable
 * successor instead of cancelling it or losing the signal with KEEP.
 *
 * The admission receipt is awaited under the lock. Otherwise a second caller
 * could inspect the old WorkManager state and append another successor.
 */
internal class ExaminationWakeupCoordinator {
    private val admission = Mutex()

    suspend fun enqueue(hasWaitingWork: suspend () -> Boolean, append: suspend () -> Unit): Boolean = admission.withLock {
        if (hasWaitingWork()) return@withLock false
        append()
        true
    }
}
