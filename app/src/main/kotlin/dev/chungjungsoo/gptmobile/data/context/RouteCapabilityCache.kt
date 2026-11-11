package dev.chungjungsoo.gptmobile.data.context

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** One refresh per route. A slow/absent endpoint cannot block unrelated connections. */
internal class RouteCapabilityCache<T>(private val now: () -> Long, private val positiveTtl: Long = 300_000L, private val negativeTtl: Long = 15_000L, private val valueTtl: ((T) -> Long)? = null) {
    private data class Cached<T>(val value: T?, val expires: Long)
    private class Refresh(val mutex: Mutex = Mutex(), var subscribers: Int = 0)
    private val lock = Any()
    private val entries = linkedMapOf<String, Cached<T>>()
    private val refreshes = mutableMapOf<String, Refresh>()
    private var epoch = 0L

    suspend fun getOrLoad(key: String, forceRefresh: Boolean = false, load: suspend () -> T?): T? {
        val (refresh, previous) = synchronized(lock) {
            val cached = entries[key]
            if (!forceRefresh && cached != null && cached.expires > now()) return cached.value
            refreshes.getOrPut(key) { Refresh() }.also { it.subscribers++ } to cached
        }
        return try {
            refresh.mutex.withLock {
                val generation = synchronized(lock) {
                    entries[key]?.takeIf { it.expires > now() && (!forceRefresh || it !== previous) }?.let { return@withLock it.value }
                    epoch
                }
                val result = load()
                synchronized(lock) {
                    if (generation != epoch) return@withLock null
                    if (entries.size >= 128 && key !in entries) entries.remove(entries.keys.first())
                    entries[key] = Cached(result, now() + if (result == null) negativeTtl else valueTtl?.invoke(result) ?: positiveTtl)
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
