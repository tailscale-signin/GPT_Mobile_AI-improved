package dev.chungjungsoo.gptmobile.data.github

import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Account-wide bounded, cancellable admission and immutable-request joining. */
class GitHubRequestScheduler(
    private val concurrency: Int = 2,
    private val maxQueued: Int = 64,
    private val maxJoined: Int = 64,
    private val maxPriorityBypasses: Int = 8
) {
    init {
        require(concurrency > 0 && maxQueued >= 0 && maxJoined >= 0 && maxPriorityBypasses >= 0)
    }

    /** This particular HTTP request never entered its dispatch block. Earlier receipts still apply. */
    class Capacity(val sharedRead: Boolean) :
        IllegalStateException(
            if (sharedRead) {
                "GITHUB_WAITING_CAPACITY: shared-read waiters are full; consume the existing result before requesting it again."
            } else {
                "GITHUB_WAITING_CAPACITY: request queue is full; retry after current requests complete."
            }
        )
    private val circuit = GitHubRequestCircuit()
    private data class Waiter(val priority: Int, val sequence: Long, val ready: CompletableDeferred<Unit>, var bypasses: Int = 0)
    private val lock = Any()
    private val queue = mutableListOf<Waiter>()
    private val inFlight = mutableMapOf<String, CompletableDeferred<Any?>>()
    private var active = 0
    private var waitingJoins = 0
    private var rejected = 0L
    private var peakQueued = 0
    private var sequence = 0L
    private val physical = AtomicLong()
    private val bytes = AtomicLong()
    private val joined = AtomicLong()
    private val cacheHits = AtomicLong()

    suspend fun <T> execute(key: String? = null, priority: Int = 0, block: suspend () -> T): T {
        currentCoroutineContext().ensureActive()
        var owner = true
        val shared = key?.let {
            synchronized(lock) {
                inFlight[it]?.also {
                    if (waitingJoins >= maxJoined) {
                        rejected++
                        throw Capacity(sharedRead = true)
                    }
                    waitingJoins++
                    owner = false
                    joined.incrementAndGet()
                }
                    ?: CompletableDeferred<Any?>().also { created -> inFlight[it] = created }
            }
        }
        if (!owner) {
            try {
                @Suppress("UNCHECKED_CAST")
                return shared!!.await() as T
            } finally {
                synchronized(lock) { waitingJoins-- }
            }
        }
        try {
            acquire(priority)
            try {
                currentCoroutineContext().ensureActive()
                val permit = circuit.admit()
                try {
                    return block().also {
                        circuit.succeeded(permit)
                        shared?.complete(it)
                    }
                } catch (failure: Throwable) {
                    circuit.failed(permit, failure)
                    throw failure
                }
            } finally {
                release()
            }
        } catch (failure: Throwable) {
            shared?.completeExceptionally(failure)
            throw failure
        } finally {
            if (key != null) synchronized(lock) { if (inFlight[key] === shared) inFlight.remove(key) }
        }
    }

    private suspend fun acquire(priority: Int) {
        val waiter = synchronized(lock) {
            if (active < concurrency) {
                active++
                null
            } else {
                if (queue.size >= maxQueued) {
                    rejected++
                    throw Capacity(sharedRead = false)
                }
                Waiter(priority, ++sequence, CompletableDeferred()).also {
                    queue += it
                    peakQueued = maxOf(peakQueued, queue.size)
                }
            }
        } ?: return
        try {
            waiter.ready.await()
        } catch (cancelled: CancellationException) {
            val granted = synchronized(lock) { !queue.remove(waiter) }
            if (granted) release()
            throw cancelled
        }
    }

    private fun release() {
        synchronized(lock) {
            // Keep visible requests responsive without starving admitted background work.
            val next = queue.firstOrNull { it.bypasses >= maxPriorityBypasses }
                ?: queue.minWithOrNull(compareByDescending<Waiter> { it.priority }.thenBy { it.sequence })
            if (next == null) {
                active--
            } else {
                queue.remove(next)
                queue.forEach { if (it.bypasses < maxPriorityBypasses) it.bypasses++ }
                next.ready.complete(Unit)
            }
        }
    }

    fun recordRequest() {
        physical.incrementAndGet()
    }
    fun recordBytes(count: Long) {
        if (count > 0) bytes.addAndGet(count)
    }
    fun recordCacheHit() {
        cacheHits.incrementAndGet()
    }
    fun metrics() = synchronized(lock) {
        buildJsonObject {
            put("physical_requests", physical.get())
            put("downloaded_bytes", bytes.get())
            put("singleflight_joins", joined.get())
            put("cache_hits", cacheHits.get())
            put("active_requests", active)
            put("queued_requests", queue.size)
            put("waiting_shared_reads", waitingJoins)
            put("queue_capacity", maxQueued)
            put("shared_waiter_capacity", maxJoined)
            put("peak_queued_requests", peakQueued)
            put("capacity_rejections", rejected)
            put("circuit", circuit.metrics())
        }
    }
}
