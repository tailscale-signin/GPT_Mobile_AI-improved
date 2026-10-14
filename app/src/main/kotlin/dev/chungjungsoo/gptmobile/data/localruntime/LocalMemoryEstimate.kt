package dev.chungjungsoo.gptmobile.data.localruntime

internal data class LocalMemoryEstimate(
    val requiredBytes: Long,
    val reserveBytes: Long,
    val availableBytes: Long?,
    val contextTokens: Int,
    val method: String = "conservative_unmeasured"
) {
    fun rejection(detail: String) = LocalMemoryAdmissionException(
        "MEMORY_ADMISSION_REJECTED · requiredBytes=$requiredBytes · reserveBytes=$reserveBytes · availableBytes=${availableBytes ?: "unknown"} · contextTokens=$contextTokens · estimate=$method · nativeLoadAttempted=false. $detail Use a smaller context/profile or release idle models; fixed-context exports need a compatible smaller export."
    )
}

internal class LocalMemoryAdmissionException(message: String) : IllegalStateException(message)
