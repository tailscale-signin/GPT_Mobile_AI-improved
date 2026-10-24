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
            context[dev.chungjungsoo.gptmobile.data.agent.CombinedWriterDispatchGuard]?.let { guard ->
                guard.validateDispatch(requireNotNull(receipt) { "COMBINED_WRITER_WIRE_RECEIPT_UNAVAILABLE" })
            }
            context[dev.chungjungsoo.gptmobile.data.research.ResearchDispatchLifecycle]?.validate()
            val attemptId = UUID.randomUUID().toString()
            val combined = context[dev.chungjungsoo.gptmobile.data.accounting.CombinedInvocationAccounting]
            combined?.reserve(attemptId, receipt)
            try {
                context[Key]?.authorize?.invoke(attemptId)
                context[dev.chungjungsoo.gptmobile.data.research.ResearchDispatchLifecycle]?.dispatch(attemptId, receipt)
                context[PreparationReplayFence]?.dispatched()
                context.ensureActive()
                context[PhysicalAttemptObserver]?.onDispatch?.invoke(attemptId)
            } catch (error: Throwable) {
                kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) { combined?.notDispatched(attemptId) }
                throw error
            }
            return attemptId
        }
    }
}

internal class PhysicalAttemptRejected(val reason: String) : IllegalStateException(reason)

/** Diagnostic identity only; this observer cannot admit or retry a request. */
internal class PhysicalAttemptObserver(val onDispatch: (String) -> Unit) : AbstractCoroutineContextElement(Key) {
    companion object Key : CoroutineContext.Key<PhysicalAttemptObserver>
}
