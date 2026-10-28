package dev.chungjungsoo.gptmobile.data.localruntime

internal enum class LocalReadinessFailure {
    INSUFFICIENT_MEMORY,
    PENDING_CLEANUP,
    VERIFICATION_REQUIRED,
    UNSUPPORTED_BACKEND,
    INCOMPATIBLE_MODEL,
    MISSING_RUNTIME,
    INITIALIZATION_TIMEOUT,
    UNKNOWN;

    companion object {
        fun from(error: Throwable): LocalReadinessFailure {
            val causes = generateSequence(error) { it.cause?.takeUnless { next -> next === it } }.take(8).toList()
            val messages = causes.joinToString("\n") { it.message.orEmpty() }.lowercase()
            return when {
                error.isLocalMemoryAdmissionFailure() -> INSUFFICIENT_MEMORY
                "native_artifact_quarantined" in messages -> VERIFICATION_REQUIRED
                "native_session_quarantined" in messages || "cleanup" in messages && "unconfirmed" in messages -> PENDING_CLEANUP
                "unsupported backend" in messages || "device_unavailable" in messages || "backend_unavailable" in messages -> UNSUPPORTED_BACKEND
                "template" in messages || "incompatible" in messages || "unsupported artifact" in messages -> INCOMPATIBLE_MODEL
                causes.any { it is UnsatisfiedLinkError } || "runtime_unavailable" in messages || "missing runtime" in messages || "does not include a compatible" in messages || "separate geniex" in messages -> MISSING_RUNTIME
                "initialization_timeout" in messages || "load_deadline" in messages -> INITIALIZATION_TIMEOUT
                else -> UNKNOWN
            }
        }
    }
}
