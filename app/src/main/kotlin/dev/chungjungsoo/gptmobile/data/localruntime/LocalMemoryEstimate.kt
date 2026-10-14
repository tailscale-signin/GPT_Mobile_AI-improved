package dev.chungjungsoo.gptmobile.data.localruntime

private const val MIB = 1024L * 1024L
private const val DEFAULT_MEMORY_RESERVE_MB = 768L

internal data class LocalMemoryEstimate(
    val requiredBytes: Long,
    val reserveBytes: Long,
    val availableBytes: Long?,
    val contextTokens: Int,
    val method: String = "conservative_unmeasured"
) {
    fun rejection(detail: String): LocalMemoryAdmissionException {
        val configuredReserveMb = reserveBytes / MIB
        val recovery = if (configuredReserveMb > DEFAULT_MEMORY_RESERVE_MB) {
            "The configured Memory reserve is $configuredReserveMb MB, above the app's " +
                "recommended 768 MB default. In Local Models → Settings → Memory, tap " +
                "'Use recommended 768 MB' and retry. If memory remains low, close other " +
                "apps or choose a smaller model."
        } else {
            "Free device memory, release idle models, or choose a smaller compatible export. " +
                "Fixed-context exports cannot be resized without a compatible export."
        }
        return LocalMemoryAdmissionException(
            "MEMORY_ADMISSION_REJECTED · requiredBytes=$requiredBytes · reserveBytes=$reserveBytes · " +
                "availableBytes=${availableBytes ?: "unknown"} · contextTokens=$contextTokens · " +
                "estimate=$method · nativeLoadAttempted=false. $detail $recovery " +
                "The native engine was not loaded, so runtime compatibility is still unverified."
        )
    }
}

internal class LocalMemoryAdmissionException(message: String) : IllegalStateException(message)

/** Isolated-process transports may wrap the original preflight exception. */
internal fun Throwable.isLocalMemoryAdmissionFailure(): Boolean =
    generateSequence(this) { it.cause }
        .take(8)
        .any { it is LocalMemoryAdmissionException || "MEMORY_ADMISSION_REJECTED" in it.message.orEmpty() }
