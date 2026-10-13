package dev.chungjungsoo.gptmobile.data.context

import java.util.UUID
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** Inherited by adapters. Planning, queueing and serialization never consume an inference attempt. */
internal class PhysicalAttemptBoundary(private val authorize: suspend (String) -> Unit) : AbstractCoroutineContextElement(Key) {
    companion object Key : CoroutineContext.Key<PhysicalAttemptBoundary> {
        suspend fun dispatch(): String {
            val context = currentCoroutineContext()
            context.ensureActive()
            val attemptId = UUID.randomUUID().toString()
            context[Key]?.authorize?.invoke(attemptId)
            context.ensureActive()
            return attemptId
        }
    }
}

internal class PhysicalAttemptRejected(val reason: String) : IllegalStateException(reason)
