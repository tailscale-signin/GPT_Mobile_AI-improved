package dev.chungjungsoo.gptmobile.data.network

import dev.chungjungsoo.gptmobile.data.diagnostics.IssueReportIncidentCapsule
import java.util.Collections
import java.util.IdentityHashMap
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Retain cause types, not exception messages that can contain endpoints, credentials or user input. */
internal fun sanitizedCauseTypes(error: Throwable): List<String> {
    val seen = Collections.newSetFromMap(IdentityHashMap<Throwable, Boolean>())
    return generateSequence(error) { it.cause }.takeWhile(seen::add).take(16)
        .map { it.javaClass.name.take(160) }.filter { it.matches(Regex("[A-Za-z0-9_.$]+")) }.toList()
}

/** HTTP acceptance, generation completion and request replay are independent observations. */
internal fun providerTransportFailureDetails(
    details: JsonObject?,
    error: Throwable,
    timing: ProviderResponseTiming?,
    dispatched: Boolean,
    receivedPayload: Boolean,
    receivedVisibleText: Boolean,
    generationCompleted: Boolean,
    replayAllowed: Boolean,
    requiresReconciliation: Boolean,
    httpAttempt: HttpAttemptEvidence? = null
): JsonObject = JsonObject(
    details.orEmpty() + buildJsonObject {
        val kind = networkFailureKind(error)
        val effectiveDispatch = details?.get("dispatched") ?: details?.get("inference_dispatched") ?: JsonPrimitive(dispatched)
        val attemptTiming = timing.takeUnless { effectiveDispatch == JsonPrimitive(false) }
        put("dispatched", effectiveDispatch)
        attemptTiming?.httpStatus?.let { put("http_status", it) }
        put(
            "remote_acceptance",
            when {
                attemptTiming?.httpStatus in 200..299 -> "YES"
                effectiveDispatch == JsonPrimitive(false) -> "NO"
                attemptTiming?.httpStatus in 400..499 && !requiresReconciliation -> "NO"
                else -> "UNKNOWN"
            }
        )
        attemptTiming?.serverRequestId?.let { put("server_request_id", it) }
        attemptTiming?.generationId?.let { put("generation_id", it) }
        put("generation_completed", generationCompleted)
        // A transport cause cannot authorize replay or continuation that its
        // owning admission check explicitly refused.
        put("request_replay_allowed", !requiresReconciliation && replayAllowed && details?.get("request_replay_allowed") != JsonPrimitive(false))
        put("logical_continuation_allowed", !requiresReconciliation && kind.retryable && details?.get("logical_continuation_allowed") != JsonPrimitive(false))
        put("partial_output_preserved", receivedPayload)
        put("visible_output_preserved", receivedVisibleText)
        // Dispatch/acceptance are boundary observations. They do not turn an
        // arbitrary provider/preparation exception into a network diagnosis.
        if (kind != NetworkFailureKind.UNKNOWN) put("failure_kind", kind.code)
        if (kind == NetworkFailureKind.TLS) {
            put("tls_failure_kind", tlsFailureKind(error).name)
            put("tls_response_started", attemptTiming?.httpStatus != null || receivedPayload)
            httpAttempt?.takeUnless { effectiveDispatch == JsonPrimitive(false) }?.let { attempt ->
                put("tls_last_observed_phase", attempt.phase.name)
                put("tls_request_body_completed", attempt.requestBodyCompleted)
                attempt.connectionReused?.let { put("tls_connection_reused", it) }
                attempt.tlsVersion?.takeIf { it in HttpAttemptEvidenceCollector.TLS_VERSIONS }?.let { put("tls_version", it) }
                attempt.httpProtocol?.takeIf { it in HttpAttemptEvidenceCollector.HTTP_PROTOCOLS }?.let { put("tls_http_protocol", it) }
            }
        }
        if (kind == NetworkFailureKind.UNKNOWN && details?.get("code") == JsonPrimitive("PROVIDER_INVOCATION_FAILED")) {
            val captured = IssueReportIncidentCapsule.exceptionFields(error, IssueReportIncidentCapsule.ExceptionCaptureSite.OPENAI_ADAPTER_STREAM_CATCH)
            captured.forEach { (key, value) -> if (key !in details) put(key, value) }
        }
        put("cause_types", JsonArray(sanitizedCauseTypes(error).map(::JsonPrimitive)))
        if (requiresReconciliation) put("requires_reconciliation", true)
    }
)
