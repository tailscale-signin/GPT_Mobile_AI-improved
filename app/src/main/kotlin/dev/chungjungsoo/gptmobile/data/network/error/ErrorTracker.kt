package dev.chungjungsoo.gptmobile.data.network.error

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * Tracks error statistics and category distributions for observability,
 * adhering to docs/RESILIENT_CONVERSATIONS_LAYOUT.md observability specifications.
 */
class ErrorTracker {

    private val errorCountsByCategory = ConcurrentHashMap<ErrorCategory, AtomicLong>()
    private val totalErrorCount = AtomicLong(0L)
    private val recoveredCount = AtomicLong(0L)

    fun recordError(error: ClassifiedError) {
        totalErrorCount.incrementAndGet()
        errorCountsByCategory
            .computeIfAbsent(error.category) { AtomicLong(0L) }
            .incrementAndGet()
    }

    fun recordRecovery() {
        recoveredCount.incrementAndGet()
    }

    fun getCountForCategory(category: ErrorCategory): Long {
        return errorCountsByCategory[category]?.get() ?: 0L
    }

    fun getTotalErrors(): Long = totalErrorCount.get()

    fun getTotalRecoveries(): Long = recoveredCount.get()

    fun getRecoveryRate(): Double {
        val total = totalErrorCount.get()
        return if (total == 0L) 1.0 else (recoveredCount.get().toDouble() / total.toDouble()).coerceIn(0.0, 1.0)
    }

    fun clear() {
        errorCountsByCategory.clear()
        totalErrorCount.set(0L)
        recoveredCount.set(0L)
    }
}
