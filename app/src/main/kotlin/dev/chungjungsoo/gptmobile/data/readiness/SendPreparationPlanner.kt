package dev.chungjungsoo.gptmobile.data.readiness

import java.util.Locale

internal enum class PreparationKind { DIRECT, CONTEXTUAL, REQUIRED_WORK }
internal data class PreparationPlan(val kind: PreparationKind, val optionalBudgetMillis: Long) {
    fun remainingMillis(startedNanos: Long, nowNanos: Long = System.nanoTime()): Long =
        (optionalBudgetMillis - ((nowNanos - startedNanos) / 1_000_000).coerceAtLeast(0)).coerceAtLeast(0)
}

/** Whole accepted message only. Readiness never overrides a required workflow or recovery. */
internal object SendPreparationPlanner {
    fun classify(text: String, hasAttachments: Boolean = false, hasHistory: Boolean = false, requiredWorkflow: Boolean = false, recovery: Boolean = false, synthesis: Boolean = false): PreparationPlan {
        if (requiredWorkflow || hasAttachments || recovery || synthesis) return PreparationPlan(PreparationKind.REQUIRED_WORK, 0)
        val normalized = text.replace(Regex("[\\p{Z}\\s]+"), " ").trim().trim('!', '.', '?').trim().lowercase(Locale.ROOT)
        val direct = !hasHistory && normalized in setOf("hi", "hello", "hey", "good morning", "good afternoon", "good evening")
        return PreparationPlan(if (direct) PreparationKind.DIRECT else PreparationKind.CONTEXTUAL, if (direct) 0 else 250)
    }
}
