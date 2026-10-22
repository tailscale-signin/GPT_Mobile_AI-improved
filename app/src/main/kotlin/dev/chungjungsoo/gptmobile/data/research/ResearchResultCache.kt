package dev.chungjungsoo.gptmobile.data.research

/** Reads never renew observation time; byte weight and entry count both bound retention. */
internal class ResearchResultCache<K, V>(
    private val lifetimeMs: Long,
    private val capacity: Int,
    private val maxWeight: Long = Long.MAX_VALUE,
    private val weigh: (V) -> Int = { 1 },
    private val now: () -> Long = { System.nanoTime() / 1_000_000 }
) {
    init {
        require(lifetimeMs > 0 && capacity > 0 && maxWeight > 0)
    }
    private data class Entry<V>(val observedAt: Long, val value: V, val weight: Int)
    private val entries = linkedMapOf<K, Entry<V>>()
    private var retainedWeight = 0L

    @Synchronized private fun expire() {
        entries.filterValues { now() - it.observedAt !in 0 until lifetimeMs }.keys.toList().forEach(::remove)
    }

    @Synchronized operator fun get(key: K): V? {
        expire()
        return entries[key]?.value
    }

    @Synchronized operator fun set(key: K, value: V) {
        expire()
        remove(key)
        val weight = weigh(value).coerceAtLeast(0)
        if (weight > maxWeight) return
        while (entries.isNotEmpty() && (entries.size >= capacity || retainedWeight + weight > maxWeight)) remove(entries.keys.first())
        entries[key] = Entry(now(), value, weight)
        retainedWeight += weight
    }

    @Synchronized fun remove(key: K): V? = entries.remove(key)?.also { retainedWeight -= it.weight }?.value
    val size: Int @Synchronized get() {
        expire()
        return entries.size
    }
    val keys: Set<K> @Synchronized get() {
        expire()
        return entries.keys.toSet()
    }
    val values: List<V> @Synchronized get() {
        expire()
        return entries.values.map { it.value }
    }
    val weight: Long @Synchronized get() {
        expire()
        return retainedWeight
    }
}
