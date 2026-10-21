package dev.chungjungsoo.gptmobile.data.research.academic

import dev.chungjungsoo.gptmobile.data.research.researchHash
import java.io.IOException

internal class AcademicHttpFailure(val status: Int, val retryAtMs: Long? = null, val sourceUrl: String = "") : IOException("Academic HTTP $status")
internal class AcademicRedirectFailure(val chain: List<String>) : IOException("Academic redirect loop or limit reached")
internal class AcademicNetworkRecovery(cause: Throwable? = null) : IOException("DNS_RECOVERY: network recovery pending", cause)

/** Access outcomes never make a scientific reliability judgment. Cancellation is handled by callers. */
internal fun academicFailureEvidence(paper: AcademicPaper, error: Exception): AcademicEvidence? {
    val causes = generateSequence<Throwable>(error) { it.cause?.takeUnless { cause -> cause === it } }.take(16).toList()
    val http = causes.filterIsInstance<AcademicHttpFailure>().firstOrNull()
    val redirect = causes.filterIsInstance<AcademicRedirectFailure>().firstOrNull()
    val code = when {
        causes.any { it is dev.chungjungsoo.gptmobile.data.diagnostics.JavaHeapWorkDeferred } -> "JAVA_HEAP_PRESSURE"
        causes.any { it is AcademicNetworkRecovery || it is java.net.UnknownHostException || it is java.net.NoRouteToHostException } -> "NETWORK_UNAVAILABLE"
        causes.any { it is java.net.SocketTimeoutException || it is io.ktor.client.plugins.HttpRequestTimeoutException } -> "TIMEOUT"
        redirect != null -> "REDIRECT_FAILED"
        http?.status == 429 -> "RATE_LIMITED"
        http?.status in setOf(401, 403) -> "SOURCE_ACCESS_BLOCKED"
        http?.status in 500..599 -> "SERVER_TEMPORARY"
        http?.status in setOf(404, 405, 410) -> "SOURCE_UNAVAILABLE"
        error.message == "Academic provider disabled" -> "PROVIDER_DISABLED"
        error is IOException -> "TRANSPORT_FAILED"
        else -> return null
    }
    val status = when (code) {
        "JAVA_HEAP_PRESSURE" -> "waiting_capacity"
        "NETWORK_UNAVAILABLE", "TRANSPORT_FAILED" -> "offline"
        "RATE_LIMITED" -> "rate_limited"
        "SOURCE_ACCESS_BLOCKED" -> "blocked"
        "REDIRECT_FAILED" -> "redirect_failed"
        "SERVER_TEMPORARY" -> "server_temporary"
        "TIMEOUT" -> "timeout"
        else -> "unavailable"
    }
    return AcademicEvidence(paper.paperId, paper.versionId, http?.sourceUrl?.ifBlank { paper.canonicalUrl } ?: paper.canonicalUrl, System.currentTimeMillis(), researchHash(""), "", status, failureCode = code, failureReason = error.message.orEmpty().take(500), retryAtMs = http?.retryAtMs, redirectChain = redirect?.chain.orEmpty().take(8))
}
