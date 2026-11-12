package dev.chungjungsoo.gptmobile.data.context

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** One refresh per route. A slow/absent endpoint cannot block unrelated connections. */
internal class RouteCapabilityCache<T>(private val now: () -> Long, private val positiveTtl: Long = 300_000L, private val negativeTtl: Long = 15_000L, private val valueTtl: ((T) -> Long)? = null, private val maxEntries: Int = 128) {
    init {
        require(maxEntries > 0)
    }
    private data class Cached<T>(val value: T?, val expires: Long, val explicitlyRefreshed: Boolean)
    private class Refresh(val mutex: Mutex = Mutex(), var subscribers: Int = 0)
    private val lock = Any()
    private val entries = linkedMapOf<String, Cached<T>>()
    private val refreshes = mutableMapOf<String, Refresh>()
    private var epoch = 0L

    suspend fun getOrLoad(key: String, forceRefresh: Boolean = false, isCurrent: (T?) -> Boolean = { true }, shareConcurrentRefresh: Boolean = true, load: suspend () -> T?): T? {
        val (refresh, previous) = synchronized(lock) {
            val cached = entries[key]
            if (!forceRefresh && cached != null && cached.expires > now() && isCurrent(cached.value)) return cached.value
            refreshes.getOrPut(key) { Refresh() }.also { it.subscribers++ } to cached
        }
        return try {
            refresh.mutex.withLock {
                val generation = synchronized(lock) {
                    // Only an overlapping explicit refresh can satisfy a queued repair probe.
                    entries[key]?.takeIf {
                        it.expires > now() && isCurrent(it.value) && (!forceRefresh || shareConcurrentRefresh && it.explicitlyRefreshed && it !== previous)
                    }?.let { return@withLock it.value }
                    epoch
                }
                val result = load()
                synchronized(lock) {
                    if (generation != epoch) return@withLock null
                    if (entries.size >= maxEntries && key !in entries) entries.remove(entries.keys.first())
                    val ttl = if (result == null) negativeTtl else valueTtl?.invoke(result) ?: positiveTtl
                    val observedAt = now()
                    entries[key] = Cached(result, if (ttl > 0 && observedAt > Long.MAX_VALUE - ttl) Long.MAX_VALUE else observedAt + ttl, forceRefresh)
                    result
                }
            }
        } finally {
            synchronized(lock) {
                refresh.subscribers--
                if (refresh.subscribers == 0 && refreshes[key] === refresh) refreshes.remove(key)
            }
        }
    }

    fun invalidate() = synchronized(lock) {
        epoch = Math.addExact(epoch, 1)
        entries.clear()
    }
}
