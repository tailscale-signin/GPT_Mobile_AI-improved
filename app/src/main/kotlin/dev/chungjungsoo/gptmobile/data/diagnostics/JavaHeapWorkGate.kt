package dev.chungjungsoo.gptmobile.data.diagnostics

import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext

class JavaHeapWorkDeferred(message: String = "Java heap capacity is temporarily unavailable; retained work can resume later.") : IOException(message)

/** Bounds simultaneous parsing/export allocations independently from native-model RAM. */
object JavaHeapWorkGate {
    private const val MIB = 1024L * 1024
    private val runtime = Runtime.getRuntime()
    private val permits = Semaphore(if (runtime.maxMemory() <= 384 * MIB) 1 else 2)
    private val sequence = AtomicLong()
    private val operations = ConcurrentHashMap<Long, String>()

    val activeOperations: String get() = operations.values.sorted().joinToString(",")
    val headroomBytes: Long get() = (runtime.maxMemory() - (runtime.totalMemory() - runtime.freeMemory())).coerceAtLeast(0)

    suspend fun <T> run(operation: String, block: suspend () -> T): T {
        // A reader can invoke an extractor without acquiring the same permit twice.
        if (currentCoroutineContext()[Permit] != null) return block()
        return permits.withPermit {
            currentCoroutineContext().ensureActive()
            val required = maxOf(16 * MIB, runtime.maxMemory() / 8)
            if (headroomBytes < required) throw JavaHeapWorkDeferred()
            val id = sequence.incrementAndGet()
            operations[id] = operation.take(96)
            try {
                withContext(Permit()) { block() }
            } finally {
                operations.remove(id)
            }
        }
    }

    private class Permit : AbstractCoroutineContextElement(Key) {
        companion object Key : CoroutineContext.Key<Permit>
    }
}
