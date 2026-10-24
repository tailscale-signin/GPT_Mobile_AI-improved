package dev.chungjungsoo.gptmobile.data.localruntime

import java.util.UUID
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/** Resource identity is stage-specific. Credentials belong in a digest, never in this key. */
internal data class ResourceRequest(
    val ownerId: String = UUID.randomUUID().toString(),
    val resourceKeys: Set<String> = emptySet(),
    val exclusiveKeys: Set<String> = emptySet(),
    val generation: Long = 0,
    val benchmark: Boolean = false,
    val isolatedMeasurement: Boolean = benchmark,
    val queueTimeoutMillis: Long? = null,
    val endpointConcurrency: Int = 4
) {
    init {
        require(ownerId.isNotBlank() && generation >= 0 && endpointConcurrency > 0)
        require(exclusiveKeys.all { it in resourceKeys })
        require(queueTimeoutMillis == null || queueTimeoutMillis > 0)
        require(resourceKeys.none { it.isBlank() })
    }
    companion object {
        const val LOCAL_RUNTIME = "local_runtime"
        fun local(ownerId: String, generation: Long = 0) = ResourceRequest(ownerId, setOf(LOCAL_RUNTIME), setOf(LOCAL_RUNTIME), generation)
        fun remote(ownerId: String, endpointKey: String, generation: Long = 0, concurrency: Int = 4) = ResourceRequest(ownerId, setOf("remote:$endpointKey"), generation = generation, endpointConcurrency = concurrency)
    }
}

internal data class ResourceLease(
    val leaseId: String,
    val ownerId: String,
    val generation: Long,
    val resourceKeys: Set<String>,
    val acquiredMonotonicMillis: Long,
    val queueMillis: Long,
    val isolatedMeasurement: Boolean,
    val measuredUnderSharedLoad: Boolean
)

internal sealed interface AdmissionOutcome {
    data class Waiting(val ownerId: String, val resourceKeys: Set<String>, val blockingOwners: List<String>, val benchmarkBlocker: Boolean, val queueMillis: Long) : AdmissionOutcome
    data class Acquired(val lease: ResourceLease) : AdmissionOutcome
    data class Released(val leaseId: String, val generation: Long) : AdmissionOutcome
}

internal class AdmissionExpiredException(val request: ResourceRequest) : IllegalStateException("ADMISSION_EXPIRED: Resource wait reached the accepted task deadline; no provider request was dispatched.")

/**
 * One process-owned resource scheduler. Isolated measurements exclude interfering work only
 * while a trial holds its lease. Interactive waiters have priority at the next trial boundary.
 * Queueing has no implicit provider timeout; only an explicit task deadline may expire it.
 */
internal object InferenceAdmission {
    private val mutex = Mutex()
    private data class Held(val lease: ResourceLease, val request: ResourceRequest, val rootLeaseId: String)
    private data class Waiter(val id: String, val request: ResourceRequest, val rootLeaseId: String?, val sequence: Long)
    private val held = linkedMapOf<String, Held>()
    private val waiting = linkedMapOf<String, Waiter>()
    private var sequence = 0L
    private var changed = CompletableDeferred<Unit>()
    private val availabilityEpoch = MutableStateFlow(0L)
    val availability: StateFlow<Long> get() = availabilityEpoch
    private val waitingState = MutableStateFlow<List<AdmissionOutcome.Waiting>>(emptyList())
    val blockers: StateFlow<List<AdmissionOutcome.Waiting>> get() = waitingState

    @Volatile private var exclusiveBusy = false
    val isExclusiveBusy: Boolean get() = exclusiveBusy
    private fun now() = System.nanoTime() / 1_000_000L
    private class Owner(val rootLeaseId: String, val lease: ResourceLease) : AbstractCoroutineContextElement(Owner) {
        companion object Key : CoroutineContext.Key<Owner>
    }

    private fun signal() {
        exclusiveBusy = held.values.any { it.request.isolatedMeasurement } || waiting.values.any { it.request.isolatedMeasurement }
        availabilityEpoch.value++
        changed.complete(Unit)
        changed = CompletableDeferred()
    }

    private fun conflicts(a: ResourceRequest, b: ResourceRequest): Boolean =
        a.isolatedMeasurement ||
            b.isolatedMeasurement ||
            a.resourceKeys.intersect(b.resourceKeys).any { it in a.exclusiveKeys || it in b.exclusiveKeys }

    private fun blockers(waiter: Waiter): List<Held> = held.values.filter { owner ->
        owner.rootLeaseId != waiter.rootLeaseId &&
            (
                conflicts(waiter.request, owner.request) ||
                    waiter.request.resourceKeys.intersect(owner.request.resourceKeys).any { key ->
                        held.values.count { key in it.request.resourceKeys && it.rootLeaseId != waiter.rootLeaseId } >= minOf(waiter.request.endpointConcurrency, owner.request.endpointConcurrency)
                    }
                )
    }

    private fun earlierConflict(waiter: Waiter): Boolean {
        // A nested acquisition extends the actual held lease; it never globally bypasses
        // admission. Waiting behind an external measurement while owning its prerequisite
        // would deadlock, so only its existing root owner is ignored here.
        if (waiter.rootLeaseId != null) return false
        return waiting.values.any { other ->
            other.id != waiter.id &&
                (if (waiter.request.benchmark != other.request.benchmark) !other.request.benchmark else other.sequence < waiter.sequence) &&
                (conflicts(waiter.request, other.request) || waiter.request.resourceKeys.intersect(other.request.resourceKeys).isNotEmpty())
        }
    }

    suspend fun <T> admit(request: ResourceRequest, onState: suspend (AdmissionOutcome) -> Unit = {}, block: suspend (ResourceLease) -> T): T {
        val inherited = coroutineContext[Owner]
        if (inherited != null && !request.benchmark && request.resourceKeys.all { it in inherited.lease.resourceKeys }) return block(inherited.lease)
        check(inherited == null || !request.benchmark) { "Cannot start a benchmark inside active inference." }
        val id = UUID.randomUUID().toString()
        val started = now()
        val waiter = mutex.withLock {
            Waiter(id, request, inherited?.rootLeaseId, sequence++).also {
                waiting[id] = it
                signal()
            }
        }
        var lease: ResourceLease? = null
        suspend fun acquire(): ResourceLease {
            var lastBlocker: Pair<List<String>, Boolean>? = null
            while (true) {
                var observation: AdmissionOutcome.Waiting? = null
                val pause = mutex.withLock {
                    val owners = blockers(waiter)
                    if (owners.isEmpty() && !earlierConflict(waiter)) {
                        val acquired = ResourceLease(id, request.ownerId, request.generation, (inherited?.lease?.resourceKeys.orEmpty() + request.resourceKeys), now(), (now() - started).coerceAtLeast(0), request.isolatedMeasurement, held.values.any { it.rootLeaseId != inherited?.rootLeaseId })
                        held[id] = Held(acquired, request, inherited?.rootLeaseId ?: id)
                        waiting.remove(id)
                        waitingState.value = waitingState.value.filterNot { it.ownerId == request.ownerId }
                        lease = acquired
                        signal()
                        null
                    } else {
                        val ownerNames = owners.map { it.lease.ownerId }.distinct()
                        val benchmark = owners.any { it.request.benchmark } || waiting.values.any { it.id != id && it.request.isolatedMeasurement && it.sequence < waiter.sequence }
                        val key = ownerNames to benchmark
                        if (lastBlocker != key) {
                            observation = AdmissionOutcome.Waiting(request.ownerId, request.resourceKeys, ownerNames, benchmark, (now() - started).coerceAtLeast(0))
                            waitingState.value = waitingState.value.filterNot { it.ownerId == request.ownerId } + requireNotNull(observation)
                            lastBlocker = key
                        }
                        changed
                    }
                }
                observation?.let { onState(it) }
                if (pause == null) return checkNotNull(lease)
                pause.await()
            }
        }
        try {
            val acquired = request.queueTimeoutMillis?.let { timeout -> withTimeoutOrNull(timeout) { acquire() } ?: throw AdmissionExpiredException(request) } ?: acquire()
            onState(AdmissionOutcome.Acquired(acquired))
            return withContext(Owner(inherited?.rootLeaseId ?: acquired.leaseId, acquired)) { block(acquired) }
        } finally {
            withContext(NonCancellable) {
                mutex.withLock {
                    waiting.remove(id)
                    waitingState.value = waitingState.value.filterNot { it.ownerId == request.ownerId }
                    // Identity and generation fence prevent stale cleanup releasing a successor.
                    val current = held[id]
                    if (current?.lease?.generation == request.generation && current.lease.ownerId == request.ownerId) held.remove(id)
                    signal()
                }
                lease?.let { onState(AdmissionOutcome.Released(it.leaseId, it.generation)) }
            }
        }
    }

    suspend fun <T> shared(request: ResourceRequest = ResourceRequest(), onState: suspend (AdmissionOutcome) -> Unit = {}, block: suspend () -> T): T = admit(request, onState) { block() }

    suspend fun <T> tryShared(block: suspend () -> T): T? {
        val inherited = coroutineContext[Owner]
        if (inherited != null) return block()
        val request = ResourceRequest()
        val id = UUID.randomUUID().toString()
        val lease = mutex.withLock {
            if (held.values.any { it.request.isolatedMeasurement } || waiting.values.any { it.request.isolatedMeasurement }) return null
            ResourceLease(id, request.ownerId, 0, emptySet(), now(), 0, false, held.isNotEmpty()).also {
                held[id] = Held(it, request, id)
                signal()
            }
        }
        return try {
            withContext(Owner(id, lease)) { block() }
        } finally {
            withContext(NonCancellable) {
                mutex.withLock {
                    held.remove(id)
                    signal()
                }
            }
        }
    }

    /** Read-only provenance for the lease already owned by this coroutine. */
    suspend fun currentLease(): ResourceLease? = coroutineContext[Owner]?.lease

    suspend fun <T> benchmark(block: suspend () -> T): T = benchmark(onWaiting = {}, block = block)
    suspend fun <T> benchmark(onWaiting: suspend () -> Unit, block: suspend () -> T): T = admit(ResourceRequest(benchmark = true), { if (it is AdmissionOutcome.Waiting) onWaiting() }) { block() }
    fun <T> sharedFlow(upstream: Flow<T>, request: ResourceRequest = ResourceRequest(), onState: suspend (AdmissionOutcome) -> Unit = {}): Flow<T> = channelFlow { shared(request, onState) { upstream.collect { send(it) } } }
}
