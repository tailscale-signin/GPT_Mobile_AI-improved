package dev.chungjungsoo.gptmobile.data.delegation

import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext
import kotlinx.serialization.Serializable

@Serializable
enum class CancellationOrigin { USER_WHOLE_RUN, USER_STOP_RESEARCH_AND_SUMMARIZE, USER_PRIMARY_ONLY, PERMISSION_REVOKED, PARENT_FAILURE, OPERATION_DEADLINE, PROCESS_INTERRUPTION, SUPERSEDED_GENERATION, CHAT_DELETED, UNKNOWN }

@Serializable
data class DelegationCancellation(val origin: CancellationOrigin, val rootWorkId: String, val generationId: String, val eventId: String = java.util.UUID.randomUUID().toString(), val timeMillis: Long = System.currentTimeMillis(), val resumable: Boolean = origin != CancellationOrigin.CHAT_DELETED)

class DelegationCancellationContext(val rootWorkId: String, val generationId: String) : AbstractCoroutineContextElement(Key) {
    companion object Key : CoroutineContext.Key<DelegationCancellationContext>
    private val value = AtomicReference<DelegationCancellation?>(null)
    val record: DelegationCancellation? get() = value.get()
    fun mark(origin: CancellationOrigin) {
        value.compareAndSet(null, DelegationCancellation(origin, rootWorkId, generationId))
    }
}
