package dev.chungjungsoo.gptmobile.data.localruntime

private const val MIB = 1024L * 1024L
private const val DEFAULT_MEMORY_RESERVE_MB = 768L

internal data class LocalMemoryEstimate(
    val requiredBytes: Long,
    val reserveBytes: Long,
    val availableBytes: Long?,
    val contextTokens: Int,
    val method: String = "conservative_unmeasured",
    val components: Map<String, Long> = emptyMap(),
    val requestedContextTokens: Int = contextTokens,
    val planIdentity: String? = null,
    val systemMemory: LocalSystemMemorySnapshot? = null
) {
    val budget: LocalMemoryBudget get() = LocalMemoryBudget(requiredBytes, reserveBytes, availableBytes)

    fun receipt(stage: String, accepted: Boolean, existingEngineResident: Boolean = false) = LocalAdmissionReceipt(
        stage = stage,
        accepted = accepted,
        requiredBytes = requiredBytes,
        reserveBytes = reserveBytes,
        availableBytes = availableBytes,
        shortfallBytes = budget.shortfallBytes,
        contextTokens = contextTokens,
        method = method,
        existingEngineResident = existingEngineResident,
        components = components,
        requestedContextTokens = requestedContextTokens,
        planIdentity = planIdentity,
        systemMemory = systemMemory
    )

    fun rejection(detail: String, stage: String = "load", nativeLoadAttempted: Boolean = false): LocalMemoryAdmissionException {
        val configuredReserveMb = reserveBytes / MIB
        val recommendedFits = budget.fitsWithReserve(DEFAULT_MEMORY_RESERVE_MB * MIB)
        val recovery = if (configuredReserveMb > DEFAULT_MEMORY_RESERVE_MB && recommendedFits) {
            "The configured Memory reserve is $configuredReserveMb MB. The current estimate fits " +
                "with the app's recommended 768 MB default. In Local Models → Settings → Memory, tap " +
                "'Use recommended 768 MB' and retry. This changes the reserve, not the model's context."
        } else {
            "Free device memory, release idle models, or choose a smaller compatible export. " +
                "Use Auto context for a resizable GGUF model. Fixed-context exports require a compatible smaller export."
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
                "$detail $recovery $verification",
            receipt(stage, accepted = false, existingEngineResident = nativeLoadAttempted)
        )
    }
}

internal class LocalMemoryAdmissionException(message: String, val receipt: LocalAdmissionReceipt? = null) : IllegalStateException(message)

/** Isolated-process transports may wrap the original preflight exception. */
internal fun Throwable.isLocalMemoryAdmissionFailure(): Boolean =
    generateSequence(this) { it.cause }
        .take(8)
        .any { it is LocalMemoryAdmissionException || "MEMORY_ADMISSION_REJECTED" in it.message.orEmpty() }
