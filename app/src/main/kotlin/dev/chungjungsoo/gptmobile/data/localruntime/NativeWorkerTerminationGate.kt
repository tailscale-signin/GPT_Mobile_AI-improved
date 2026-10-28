package dev.chungjungsoo.gptmobile.data.localruntime

import java.util.concurrent.atomic.AtomicBoolean

/**
 * Per-process terminal state. Once termination begins, no new native operation may
 * start and no second watchdog may initiate termination again.
 */
internal class NativeWorkerTerminationGate {
    private val terminating = AtomicBoolean(false)

    fun isTerminating(): Boolean = terminating.get()

    /** Returns true only for the first termination requester. */
    fun beginTermination(): Boolean = terminating.compareAndSet(false, true)
}
