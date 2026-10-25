package dev.chungjungsoo.gptmobile.data.localruntime

/** Driver boundaries share a deadline group; successive API callbacks cannot keep it alive forever. */
internal object GgufLoadDeadline {
    const val ABSOLUTE_LOAD_MS = 180_000L
    val policy = RuntimeLoadDeadlinePolicy(
        absoluteMs = ABSOLUTE_LOAD_MS,
        stageBudgetsMs = mapOf(
            "LIBRARY_LOADING" to 15_000L,
            "VALIDATING_TEMPLATE" to 15_000L,
            "PREPARING_TEMPLATE" to 15_000L,
            "BACKEND_INITIALIZATION" to 20_000L,
            "ALLOCATING_CONTEXT" to 60_000L
        )
    )

    fun group(stage: String): String =
        when {
            stage.startsWith(
                "OPENCL_"
            ) ||
                stage.startsWith("REGISTER_") ||
                stage in setOf("INITIALIZING_BACKEND", "PROBING_DEVICE") -> "BACKEND_INITIALIZATION"
            else -> stage
        }

    fun budgetMs(stage: String): Long? = policy.stageBudgetsMs[group(stage)]

    fun expired(
        stage: String,
        startedAtMs: Long,
        nowMs: Long
    ): Boolean = budgetMs(stage)?.let { nowMs - startedAtMs >= it } == true
}

internal class GgufInitializationTimeoutException(
    message: String
) : IllegalStateException(message)
