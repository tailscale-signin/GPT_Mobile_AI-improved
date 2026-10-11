package dev.chungjungsoo.gptmobile.data.localruntime

import kotlinx.serialization.Serializable

@Serializable
data class LocalLoadAttempt(
    val backend: String,
    val stage: String,
    val exceptionType: String,
    val message: String,
    val skipped: Boolean = false
) {
    companion object {
        fun failed(backend: String, stage: String, error: Throwable) = LocalLoadAttempt(
            backend,
            stage,
            error.javaClass.simpleName,
            error.message ?: "No native error detail"
        )
    }
}

/** The first real failure is primary; capability exclusions never overwrite it. */
class LocalLoadFailure(val attempts: List<LocalLoadAttempt>, cause: Throwable? = null) :
    IllegalStateException(
        attempts.joinToString("; ") { "${it.backend.uppercase()} ${it.stage}${if (it.skipped) " (skipped)" else ""}: ${it.message}" },
        cause
    )

internal fun compatibleFallbacks(requested: String, declared: Set<String>?): List<String> {
    val first = LocalAccelerators.normalize(requested)
    val ordered = when (first) {
        LocalAccelerators.NPU -> listOf("npu", "gpu", "cpu")
        LocalAccelerators.GPU -> listOf("gpu", "cpu")
        else -> listOf("cpu")
    }
    // Unknown metadata cannot establish that another compiled backend is compatible.
    return if (declared.isNullOrEmpty()) listOf(first) else ordered.filter { it in declared }
}
