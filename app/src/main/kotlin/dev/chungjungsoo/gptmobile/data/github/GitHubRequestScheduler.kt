package dev.chungjungsoo.gptmobile.data.github

import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Account-wide bounded, cancellable admission and immutable-request joining. */
class GitHubRequestScheduler(private val concurrency: Int = 2, private val maxQueued: Int = 64) {
    private data class Waiter(val priority: Int, val sequence: Long, val ready: CompletableDeferred<Unit>)
    private val lock = Any()
    private val queue = mutableListOf<Waiter>()
    private val inFlight = mutableMapOf<String, CompletableDeferred<Any?>>()
    private var active = 0
    private var sequence = 0L
    private val physical = AtomicLong()
    private val bytes = AtomicLong()
    private val joined = AtomicLong()
    private val cacheHits = AtomicLong()

    suspend fun <T> execute(key: String? = null, priority: Int = 0, block: suspend () -> T): T {
        var owner = true
        val shared = key?.let {
            synchronized(lock) {
                inFlight[it]?.also {
                    owner = false
                    joined.incrementAndGet()
                }
                    ?: CompletableDeferred<Any?>().also { created -> inFlight[it] = created }
            }
        }
        if (!owner) {
            @Suppress("UNCHECKED_CAST")
            return shared!!.await() as T
        }
        try {
            acquire(priority)
            try {
                return block().also { shared?.complete(it) }
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
            if (active < concurrency.coerceAtLeast(1)) {
                active++
                null
            } else {
                check(queue.size < maxQueued) { "GITHUB_WAITING_CAPACITY: request queue is full; retry after current requests complete." }
                Waiter(priority, ++sequence, CompletableDeferred()).also(queue::add)
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
            val next = queue.sortedWith(compareByDescending<Waiter> { it.priority }.thenBy { it.sequence }).firstOrNull()
            if (next == null) {
                active--
            } else {
                queue.remove(next)
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
        }
    }
}
