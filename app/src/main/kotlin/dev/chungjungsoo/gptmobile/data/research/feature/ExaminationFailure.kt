package dev.chungjungsoo.gptmobile.data.research.feature

import dev.chungjungsoo.gptmobile.data.research.academic.AcademicEvidence
import kotlinx.serialization.Serializable

@Serializable
data class ExaminationFailure(val code: String, val stage: ExaminationStage, val retryable: Boolean, val retryAtMs: Long? = null, val detail: String = "", val sourceUrl: String = "", val redirectChain: List<String> = emptyList())
internal class ExaminationRetrievalException(val evidence: AcademicEvidence) : java.io.IOException(evidence.failureCode ?: evidence.retrievalStatus)

internal fun examinationFailure(error: Exception, attempt: Int, now: Long = System.currentTimeMillis()): ExaminationFailure {
    val evidence = (error as? ExaminationRetrievalException)?.evidence
    val code = evidence?.failureCode ?: when {
        error is dev.chungjungsoo.gptmobile.data.diagnostics.JavaHeapWorkDeferred || error.message.orEmpty().contains("JAVA_HEAP_PRESSURE") -> "JAVA_HEAP_PRESSURE"
        error.javaClass.simpleName == "CombinedBudgetExceeded" || error.message.orEmpty().contains("BUDGET", true) -> "ADMITTED_CAPACITY_EXHAUSTED"
        error.message.orEmpty().contains("permission", true) -> "PERMISSION_REVOKED"
        error is java.net.UnknownHostException || error is java.net.NoRouteToHostException -> "NETWORK_UNAVAILABLE"
        error.message.orEmpty().contains("EXAMINATION_OUTPUT_INVALID") -> "OUTPUT_INVALID"
        else -> evidence?.retrievalStatus?.uppercase() ?: "EXAMINATION_FAILED"
    }
    val stage = when (code) {
        "JAVA_HEAP_PRESSURE", "ADMITTED_CAPACITY_EXHAUSTED" -> ExaminationStage.WAITING_CAPACITY
        "NETWORK_UNAVAILABLE", "TRANSPORT_FAILED", "TIMEOUT", "OFFLINE", "SERVER_TEMPORARY" -> ExaminationStage.WAITING_NETWORK
        "RATE_LIMITED" -> ExaminationStage.WAITING_RATE_LIMIT
        "SOURCE_ACCESS_BLOCKED", "BLOCKED", "REDIRECT_FAILED" -> ExaminationStage.BLOCKED_SOURCE
        "PERMISSION_REVOKED" -> ExaminationStage.PAUSED
        "METADATA_ONLY", "UNAVAILABLE", "SOURCE_UNAVAILABLE" -> ExaminationStage.UNAVAILABLE
        else -> ExaminationStage.FAILED
    }
    val retry = attempt < 5 && (stage in setOf(ExaminationStage.WAITING_NETWORK, ExaminationStage.WAITING_RATE_LIMIT) || code == "JAVA_HEAP_PRESSURE")
    val due = if (retry) evidence?.retryAtMs ?: (now + (15_000L shl attempt.coerceIn(0, 4))) else null
    return ExaminationFailure(code, stage, retry, due, (evidence?.failureReason ?: error.message).orEmpty().take(1000), evidence?.sourceUrl.orEmpty(), evidence?.redirectChain.orEmpty())
}
