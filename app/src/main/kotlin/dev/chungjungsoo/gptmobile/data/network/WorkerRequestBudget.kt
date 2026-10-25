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
    private val clock: () -> Long = { System.nanoTime() / 1_000_000 },
    private val durableReservation: () -> Boolean = { true },
    private val queuedAtMillis: Long? = null
) : AbstractCoroutineContextElement(Key) {
    companion object Key : CoroutineContext.Key<WorkerRequestBudget>
    private val startedAt = clock()
    private val deadline = Math.addExact(startedAt, runtimeMillis.also { require(it >= 0) })
    private val providerAttempts = AtomicInteger()

    fun remainingMillis(): Long = (deadline - clock()).coerceAtLeast(0)

    fun preflight(): dev.chungjungsoo.gptmobile.data.context.RequestDeadlineReceipt {
        val now = clock()
        val receipt = dev.chungjungsoo.gptmobile.data.context.RequestDeadlineReceipt(
            queueAgeMillis = queuedAtMillis?.let { (startedAt - it).coerceAtLeast(0) },
            elapsedMillis = (now - startedAt).coerceAtLeast(0),
            remainingMillis = (deadline - now).coerceAtLeast(0),
            requestLimit = requestLimit,
            requestsReserved = physicalRequests.get()
        )
        if (receipt.remainingMillis < 1_000) {
            AppLogRecorder.record("Delegation", "REQUEST_PREFLIGHT_REJECTED · reason=deadline · elapsedMs=${receipt.elapsedMillis} · remainingMs=${receipt.remainingMillis} · queueAgeMs=${receipt.queueAgeMillis ?: "unknown"} · dispatched=false", "W")
            throw IOException("DELEGATE_DEADLINE_EXHAUSTED: ${receipt.remainingMillis} ms remain after ${receipt.elapsedMillis} ms; no request was sent")
        }
        if (receipt.requestsReserved >= requestLimit) throw IOException("DELEGATE_PHYSICAL_REQUEST_LIMIT: $requestLimit")
        return receipt
    }

    fun acquireRequest() {
        preflight()
        while (true) {
            val count = physicalRequests.get()
            if (count >= requestLimit) throw IOException("DELEGATE_PHYSICAL_REQUEST_LIMIT: $requestLimit")
            if (physicalRequests.compareAndSet(count, count + 1)) {
                val reserved = try {
                    durableReservation()
                } catch (failure: Throwable) {
                    physicalRequests.decrementAndGet()
                    throw failure
                }
                if (!reserved) {
                    physicalRequests.decrementAndGet()
                    throw IOException("DELEGATE_PHYSICAL_REQUEST_LIMIT: original work allowance exhausted")
                }
                AppLogRecorder.record("Delegation", "HTTP_DISPATCH · providerAttempt=${providerAttempts.incrementAndGet()} · physicalRequest=${count + 1}/$requestLimit · remainingDeadlineMs=${remainingMillis()}")
                return
            }
        }
    }
}
