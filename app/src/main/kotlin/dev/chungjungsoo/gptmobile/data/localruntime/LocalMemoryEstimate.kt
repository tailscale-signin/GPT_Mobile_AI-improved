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
    val budget: LocalMemoryBudget get() = LocalMemoryBudget(requiredBytes, reserveBytes, availableBytes)

    fun rejection(detail: String, stage: String = "load", nativeLoadAttempted: Boolean = false): LocalMemoryAdmissionException {
        val configuredReserveMb = reserveBytes / MIB
        val recommendedFits = budget.fitsWithReserve(DEFAULT_MEMORY_RESERVE_MB * MIB)
        val recovery = if (configuredReserveMb > DEFAULT_MEMORY_RESERVE_MB && recommendedFits) {
            "The configured Memory reserve is $configuredReserveMb MB. The current estimate fits " +
                "with the app's recommended 768 MB default. In Local Models → Settings → Memory, tap " +
                "'Use recommended 768 MB' and retry. This changes the reserve, not the model's context."
        } else {
            "Free device memory, release idle models, or choose a smaller compatible export. " +
                "Fixed-context exports cannot be resized without a compatible export."
        }
        val verification = if (nativeLoadAttempted) {
            "The engine was already loaded, but this request was blocked before generation."
        } else {
            "The native engine was not loaded, so runtime compatibility is still unverified."
        }
        return LocalMemoryAdmissionException(
            "MEMORY_ADMISSION_REJECTED · stage=$stage · requiredBytes=$requiredBytes · reserveBytes=$reserveBytes · " +
                "availableBytes=${availableBytes ?: "unknown"} · totalBudgetBytes=${budget.totalBytes} · " +
                "shortfallBytes=${budget.shortfallBytes ?: "unknown"} · recommendedReserveFits=$recommendedFits · " +
                "contextTokens=$contextTokens · estimate=$method · nativeLoadAttempted=$nativeLoadAttempted. " +
                "$detail $recovery $verification"
        )
    }
}

internal class LocalMemoryAdmissionException(message: String) : IllegalStateException(message)

/** Isolated-process transports may wrap the original preflight exception. */
internal fun Throwable.isLocalMemoryAdmissionFailure(): Boolean =
    generateSequence(this) { it.cause }
        .take(8)
        .any { it is LocalMemoryAdmissionException || "MEMORY_ADMISSION_REJECTED" in it.message.orEmpty() }
