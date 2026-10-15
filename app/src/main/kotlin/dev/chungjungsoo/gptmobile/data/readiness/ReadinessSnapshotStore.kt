package dev.chungjungsoo.gptmobile.data.readiness

import java.util.concurrent.atomic.AtomicLong

/** Immutable bounded metadata only; never owns clients, permissions, draft text or tool outputs. */
internal class ReadinessSnapshotStore<K, V>(private val maxEntries: Int = 16, private val maxBytes: Int = 4 * 1024 * 1024, private val clock: () -> Long = { System.nanoTime() / 1_000_000 }) {
    data class Snapshot<V>(val value: V, val bytes: Int, val observedAt: Long, val epoch: Long)
    private val generation = AtomicLong()

    @Volatile private var entries: Map<K, Snapshot<V>> = emptyMap()
    val epoch: Long get() = generation.get()
    fun peek(key: K, hardAgeMillis: Long = 3_600_000): Snapshot<V>? = entries[key]?.takeIf { it.epoch == epoch && clock() - it.observedAt in 0..hardAgeMillis }

    @Synchronized
    fun publish(key: K, value: V, bytes: Int, expectedEpoch: Long): Boolean {
        if (expectedEpoch != epoch || bytes !in 0..maxBytes) return false
        val updated = LinkedHashMap(entries).apply {
            remove(key)
            put(key, Snapshot(value, bytes, clock(), expectedEpoch))
        }
        while (updated.size > maxEntries || updated.values.sumOf { it.bytes.toLong() } > maxBytes) updated.remove(updated.keys.first())
        entries = updated.toMap()
        return true
    }

    @Synchronized
    fun invalidate() {
        generation.incrementAndGet()
        entries = emptyMap()
    }
}
