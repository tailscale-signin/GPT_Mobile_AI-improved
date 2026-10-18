package dev.chungjungsoo.gptmobile.data.context

import java.util.UUID
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** Inherited by adapters. Planning, queueing and serialization never consume an inference attempt. */
internal class PhysicalAttemptBoundary(private val authorize: suspend (String) -> Unit) : AbstractCoroutineContextElement(Key) {
    companion object Key : CoroutineContext.Key<PhysicalAttemptBoundary> {
        suspend fun dispatch(receipt: RequestAdmissionReceipt? = null): String {
            val context = currentCoroutineContext()
            context.ensureActive()
            context[RequestAuthorizationGuard]?.validate?.invoke()
            context.ensureActive()
            val attemptId = UUID.randomUUID().toString()
            val combined = context[dev.chungjungsoo.gptmobile.data.accounting.CombinedInvocationAccounting]
            combined?.reserve(attemptId, receipt)
            try {
                context[Key]?.authorize?.invoke(attemptId)
                context[PreparationReplayFence]?.dispatched()
                context.ensureActive()
            } catch (error: Throwable) {
                kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) { combined?.notDispatched(attemptId) }
                throw error
            }
            return attemptId
        }
    }
}

internal class PhysicalAttemptRejected(val reason: String) : IllegalStateException(reason)
