package dev.chungjungsoo.gptmobile.data.research

/** Reading a cached result never renews the provider's observation time. */
internal class ResearchResultCache<K, V>(
    private val lifetimeMs: Long,
    private val capacity: Int,
    private val now: () -> Long = { System.nanoTime() / 1_000_000 }
) {
    init {
        require(lifetimeMs > 0 && capacity > 0)
    }
    private data class Entry<V>(val observedAt: Long, val value: V)
    private val entries = linkedMapOf<K, Entry<V>>()

    @Synchronized operator fun get(key: K): V? {
        val entry = entries[key] ?: return null
        if (now() - entry.observedAt !in 0 until lifetimeMs) {
            entries.remove(key)
            return null
        }
        return entry.value
    }

    @Synchronized operator fun set(key: K, value: V) {
        entries.remove(key)
        if (entries.size >= capacity) entries.remove(entries.keys.first())
        entries[key] = Entry(now(), value)
    }
}
