package dev.chungjungsoo.gptmobile.research.reliability

/** Provider transport success is not equivalent to a usable visible response. */
@kotlinx.serialization.Serializable
enum class ProviderOutcome {
    SUCCESS_VISIBLE,
    REASONING_ONLY,
    INVALID_PAYLOAD,
    INVALID_REQUEST,
    UPSTREAM_FAILED,
    RATE_LIMITED,
    ALLOWANCE_EXHAUSTED,
    INTERRUPTED
}

data class ProviderObservation(
    val httpStatus: Int?,
    val visibleText: String?,
    val reasoningTokens: Int = 0,
    val outputTokens: Int = 0,
    val retryAfterMillis: Long? = null,
    val payloadValid: Boolean = true,
    val interrupted: Boolean = false
)

object ProviderOutcomeClassifier {
    fun classify(value: ProviderObservation): ProviderOutcome = when {
        value.interrupted -> ProviderOutcome.INTERRUPTED
        value.httpStatus == 429 -> ProviderOutcome.RATE_LIMITED
        value.httpStatus == 402 -> ProviderOutcome.ALLOWANCE_EXHAUSTED
        value.httpStatus == 400 || value.httpStatus == 422 -> ProviderOutcome.INVALID_REQUEST
        value.httpStatus == null || value.httpStatus !in 200..299 -> ProviderOutcome.UPSTREAM_FAILED
        !value.payloadValid -> ProviderOutcome.INVALID_PAYLOAD
        else -> classifyPayload(value.visibleText, value.reasoningTokens.toLong(), value.outputTokens.toLong(), value.payloadValid)
    }

    /** Called only after the transport adapter proves completion; schema acceptance stays separate. */
    fun classifyPayload(visibleText: String?, reasoningTokens: Long = 0, outputTokens: Long = 0, payloadValid: Boolean = true): ProviderOutcome = when {
        !visibleText.isNullOrBlank() && !payloadValid -> ProviderOutcome.INVALID_PAYLOAD
        !visibleText.isNullOrBlank() -> ProviderOutcome.SUCCESS_VISIBLE
        reasoningTokens > 0 || outputTokens > 0 -> ProviderOutcome.REASONING_ONLY
        else -> ProviderOutcome.INVALID_PAYLOAD
    }
}

enum class ResearchStageStatus {
    PENDING,
    RUNNING,
    WAITING_PROVIDER_LIMIT,
    RETRYABLE_FAILED,
    TERMINAL_FAILED,
    COMPLETED,
    SKIPPED_WITH_REASON
}

data class ResearchStageCheckpoint(
    val runId: String,
    val stageId: String,
    val status: ResearchStageStatus,
    val attempt: Int = 0,
    val providerId: String? = null,
    val retryAtEpochMillis: Long? = null
) {
    init {
        require(runId.isNotBlank() && stageId.isNotBlank())
        require(attempt >= 0)
        require(status == ResearchStageStatus.WAITING_PROVIDER_LIMIT || retryAtEpochMillis == null)
    }

    fun canResume(nowEpochMillis: Long): Boolean = when (status) {
        ResearchStageStatus.PENDING, ResearchStageStatus.RETRYABLE_FAILED -> true
        ResearchStageStatus.WAITING_PROVIDER_LIMIT ->
            retryAtEpochMillis != null && nowEpochMillis >= retryAtEpochMillis
        else -> false
    }
}

enum class EvidenceStatus { DISCOVERED, FETCHED, READABLE, EXTRACTED, VALIDATED, ACCEPTED, EXCLUDED }

data class ResearchEvidence(
    val sourceId: String,
    val sourceUrl: String,
    val status: EvidenceStatus,
    val citationLocator: String? = null,
    val contentHash: String? = null,
    val exclusionReason: String? = null
) {
    init {
        require(sourceId.isNotBlank())
        require(sourceUrl.startsWith("https://") || sourceUrl.startsWith("http://"))
        require(status != EvidenceStatus.EXCLUDED || !exclusionReason.isNullOrBlank())
    }
}

object ResearchReportGate {
    fun canPublish(report: String, citedSourceIds: Set<String>, evidence: Collection<ResearchEvidence>): Boolean {
        if (report.isBlank() || citedSourceIds.isEmpty()) return false
        val accepted = evidence.filter { it.status == EvidenceStatus.ACCEPTED }.map { it.sourceId }.toSet()
        return citedSourceIds.all { it in accepted }
    }

    fun shouldOfferPartialReport(evidence: Collection<ResearchEvidence>): Boolean =
        evidence.any { it.status == EvidenceStatus.ACCEPTED }
}
