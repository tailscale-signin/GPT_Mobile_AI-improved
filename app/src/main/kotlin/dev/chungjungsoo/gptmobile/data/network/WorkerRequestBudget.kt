package dev.chungjungsoo.gptmobile.data.network

import dev.chungjungsoo.gptmobile.data.diagnostics.AppLogRecorder
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

/** Propagates one worker deadline and a turn-wide request ceiling through repairs. */
internal class WorkerRequestBudget(
    runtimeMillis: Long,
    private val physicalRequests: AtomicInteger,
    private val requestLimit: Int = 48,
    private val clock: () -> Long = { System.nanoTime() / 1_000_000 }
) : AbstractCoroutineContextElement(Key) {
    companion object Key : CoroutineContext.Key<WorkerRequestBudget>
    private val deadline = clock() + runtimeMillis
    private val providerAttempts = AtomicInteger()

    fun remainingMillis(): Long = (deadline - clock()).coerceAtLeast(0)

    fun acquireRequest() {
        if (remainingMillis() < 1_000) throw IOException("DELEGATE_DEADLINE_EXHAUSTED")
        while (true) {
            val count = physicalRequests.get()
            if (count >= requestLimit) throw IOException("DELEGATE_PHYSICAL_REQUEST_LIMIT: $requestLimit")
            if (physicalRequests.compareAndSet(count, count + 1)) {
                AppLogRecorder.record("Delegation", "HTTP_DISPATCH · providerAttempt=${providerAttempts.incrementAndGet()} · physicalRequest=${count + 1}/$requestLimit · remainingDeadlineMs=${remainingMillis()}")
                return
            }
        }
    }
}
