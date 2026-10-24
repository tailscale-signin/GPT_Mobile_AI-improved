package dev.chungjungsoo.gptmobile.data.localruntime

/** Driver qualification is bounded separately from productive model loading. */
internal object GgufLoadDeadline {
    fun budgetMs(stage: String): Long? = when (stage) {
        "LIBRARY_LOADING", "VALIDATING_TEMPLATE", "PREPARING_TEMPLATE" -> 15_000L
        "INITIALIZING_BACKEND", "PROBING_DEVICE" -> 20_000L
        "ALLOCATING_CONTEXT" -> 60_000L
        else -> null
    }

    fun expired(stage: String, startedAtMs: Long, nowMs: Long): Boolean =
        budgetMs(stage)?.let { nowMs - startedAtMs >= it } == true
}

internal class GgufInitializationTimeoutException(message: String) : IllegalStateException(message)
