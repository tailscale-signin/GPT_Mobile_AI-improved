package dev.chungjungsoo.gptmobile.util

/** Ownership is explicit; replacement, expiry and disposal close each handle once. */
internal class LifecycleResources(private val clock: () -> Long = { System.nanoTime() / 1_000_000 }) : AutoCloseable {
    private data class Resource(val expires: Long, val cleanup: () -> Unit)
    private val resources = mutableMapOf<String, Resource>()
    private var closed = false

    fun acquire(key: String, ttlMs: Long = Long.MAX_VALUE, cleanup: () -> Unit): AutoCloseable {
        require(ttlMs > 0)
        val expiry = if (ttlMs == Long.MAX_VALUE) Long.MAX_VALUE else clock().let { now -> if (now > Long.MAX_VALUE - ttlMs) Long.MAX_VALUE else now + ttlMs }
        val resource = Resource(expiry, cleanup)
        val previous = synchronized(this) {
            check(!closed)
            resources.put(key, resource)
        }
        previous?.cleanup?.invoke()
        return AutoCloseable {
            val removed = synchronized(this) { if (resources[key] === resource) resources.remove(key) else null }
            removed?.cleanup?.invoke()
        }
    }

    fun cleanupExpired() {
        val expired = synchronized(this) {
            resources.filterValues { it.expires <= clock() }.also { keys -> keys.keys.forEach(resources::remove) }.values.toList()
        }
        expired.forEach { runCatching(it.cleanup) }
    }

    override fun close() {
        val pending = synchronized(this) {
            closed = true
            resources.values.toList().also { resources.clear() }
        }
        pending.forEach { runCatching(it.cleanup) }
    }
}
