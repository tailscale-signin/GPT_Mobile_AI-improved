package dev.chungjungsoo.gptmobile.data.research.feature

import dev.chungjungsoo.gptmobile.data.research.academic.AcademicEvidence
import kotlinx.serialization.Serializable

@Serializable
data class ExaminationFailure(val code: String, val stage: ExaminationStage, val retryable: Boolean, val retryAtMs: Long? = null, val detail: String = "", val sourceUrl: String = "", val redirectChain: List<String> = emptyList())
internal class ExaminationRetrievalException(val evidence: AcademicEvidence) : java.io.IOException(evidence.failureCode ?: evidence.retrievalStatus)

internal fun examinationFailure(error: Exception, attempt: Int, now: Long = System.currentTimeMillis()): ExaminationFailure {
    val causes = generateSequence<Throwable>(error) { it.cause?.takeUnless { cause -> cause === it } }.take(16).toList()
    val evidence = causes.filterIsInstance<ExaminationRetrievalException>().firstOrNull()?.evidence
    val delegated = causes.filterIsInstance<dev.chungjungsoo.gptmobile.data.agent.tool.DelegateGenerationException>().firstOrNull()
    val code = evidence?.failureCode ?: when {
        evidence != null && evidence.text.isBlank() && evidence.retrievalStatus in setOf("read", "partially_read", "abstract_only") -> "SOURCE_TEXT_EMPTY"
        causes.any { it.message.orEmpty().startsWith("EXAMINATION_OUTPUT_EMPTY") } -> "OUTPUT_EMPTY"
        causes.any { it.message.orEmpty().startsWith("EXAMINATION_OUTPUT_INVALID") } -> "OUTPUT_INVALID"
        causes.any { it.message.orEmpty().startsWith("SOURCE_TEXT_EMPTY") } -> "SOURCE_TEXT_EMPTY"
        causes.any { it.message.orEmpty().startsWith("EXTRACTION_EMPTY") } -> "NO_GROUNDED_OUTCOME"
        causes.any { it.message.orEmpty().startsWith("GROUNDING_INVALID") } -> "GROUNDING_INVALID"
        causes.any {
            it.message.orEmpty().startsWith(ExaminationOutputBudget.INSUFFICIENT) ||
                it.message.orEmpty().startsWith(ExaminationOutputBudget.CONTEXT_INSUFFICIENT) ||
                it.message.orEmpty().startsWith("REVIEW_PACKET_TOO_LARGE")
        } -> "ADMITTED_CAPACITY_EXHAUSTED"
        causes.any { it.message.orEmpty().startsWith("EXAMINER_OUTPUT_CAPACITY") } -> "EXAMINER_OUTPUT_CAPACITY"
        delegated?.kind == dev.chungjungsoo.gptmobile.data.agent.tool.DelegateFailureKind.TRANSPORT_FAILURE -> "TRANSPORT_FAILED"
        delegated?.kind == dev.chungjungsoo.gptmobile.data.agent.tool.DelegateFailureKind.OUTPUT_LIMIT -> "OUTPUT_LIMIT"
        delegated?.kind == dev.chungjungsoo.gptmobile.data.agent.tool.DelegateFailureKind.AUTH_FAILURE -> "EXAMINER_AUTH_REQUIRED"
        delegated?.kind == dev.chungjungsoo.gptmobile.data.agent.tool.DelegateFailureKind.REASONING_ONLY -> "REASONING_ONLY"
        delegated?.kind == dev.chungjungsoo.gptmobile.data.agent.tool.DelegateFailureKind.EMPTY_OUTPUT -> "OUTPUT_EMPTY"
        causes.any { it is dev.chungjungsoo.gptmobile.data.diagnostics.JavaHeapWorkDeferred || it.message.orEmpty().startsWith("JAVA_HEAP_PRESSURE") } -> "JAVA_HEAP_PRESSURE"
        causes.any {
            it is dev.chungjungsoo.gptmobile.data.agent.CombinedBudgetExceeded ||
                it.message.orEmpty().startsWith("CombinedBudgetExceeded")
        } -> "ADMITTED_CAPACITY_EXHAUSTED"
        error.message.orEmpty().contains("permission", true) -> "PERMISSION_REVOKED"
        causes.any { it is java.net.UnknownHostException || it is java.net.NoRouteToHostException } -> "NETWORK_UNAVAILABLE"
        causes.any { it.message.orEmpty().contains("HTTP 429", true) || it.message.orEmpty().contains("429 Too Many Requests", true) } -> "RATE_LIMITED"
        causes.any { it.message.orEmpty().contains("HTTP 403", true) || it.message.orEmpty().contains("403 Forbidden", true) } -> "SOURCE_ACCESS_BLOCKED"
        causes.any { it.message.orEmpty().contains("HTTP 401", true) || it.message.orEmpty().contains("401 Unauthorized", true) } -> "EXAMINER_AUTH_REQUIRED"
        causes.any { it.message.orEmpty().contains("INVALID_JSON", true) || it.message.orEmpty().contains("JSON_DECODING", true) } -> "OUTPUT_INVALID"
        causes.any { it.message.orEmpty().contains("OUTPUT_LIMIT", true) } -> "OUTPUT_LIMIT"
        causes.any { it.message.orEmpty().contains("EXAMINATION_OUTPUT_EMPTY") } -> "OUTPUT_EMPTY"
        causes.any { it.message.orEmpty().contains("SOURCE_TEXT_EMPTY") } -> "SOURCE_TEXT_EMPTY"
        causes.any { it.message.orEmpty().contains("EXTRACTION_EMPTY") } -> "NO_GROUNDED_OUTCOME"
        causes.any { it.message.orEmpty().contains("GROUNDING_INVALID") } -> "GROUNDING_INVALID"
        causes.any { it.message.orEmpty().contains("EXAMINATION_OUTPUT_INVALID") } -> "OUTPUT_INVALID"
        causes.any { it is java.net.SocketTimeoutException || it is io.ktor.client.plugins.HttpRequestTimeoutException } -> "TIMEOUT"
        error is java.io.IOException -> "TRANSPORT_FAILED"
        else -> evidence?.retrievalStatus?.uppercase() ?: "EXAMINATION_FAILED"
    }
    val stage = when (code) {
        "JAVA_HEAP_PRESSURE", "ADMITTED_CAPACITY_EXHAUSTED", "EXAMINER_OUTPUT_CAPACITY", "OUTPUT_LIMIT" -> ExaminationStage.WAITING_CAPACITY
        "NETWORK_UNAVAILABLE", "TRANSPORT_FAILED", "TIMEOUT", "OFFLINE", "SERVER_TEMPORARY", "DNS_RECOVERY", "DNS_FAILED" -> ExaminationStage.WAITING_NETWORK
        "RATE_LIMITED", "HTTP_429", "TOO_MANY_REQUESTS" -> ExaminationStage.WAITING_RATE_LIMIT
        "SOURCE_ACCESS_BLOCKED", "BLOCKED", "REDIRECT_FAILED", "HTTP_403", "FORBIDDEN" -> ExaminationStage.BLOCKED_SOURCE
        "PERMISSION_REVOKED", "EXAMINER_AUTH_REQUIRED", "HTTP_401", "UNAUTHORIZED", "AUTHENTICATION_REQUIRED" -> ExaminationStage.PAUSED
        "METADATA_ONLY", "UNAVAILABLE", "SOURCE_UNAVAILABLE" -> ExaminationStage.UNAVAILABLE
        else -> ExaminationStage.FAILED
    }
    val retry = attempt < 5 && (stage in setOf(ExaminationStage.WAITING_NETWORK, ExaminationStage.WAITING_RATE_LIMIT) || code == "JAVA_HEAP_PRESSURE")
    // Output-limit recovery requires a new admitted budget or a smaller checkpointed slice.
    // Do not schedule the identical request repeatedly without changing its capacity.
    val due = if (retry) evidence?.retryAtMs ?: (now + (15_000L shl attempt.coerceIn(0, 4))) else null
    return ExaminationFailure(code, stage, retry, due, (evidence?.failureReason ?: error.message).orEmpty().take(1000), evidence?.sourceUrl.orEmpty(), evidence?.redirectChain.orEmpty())
}
