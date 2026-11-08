package dev.chungjungsoo.gptmobile.data.network

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
    requiresReconciliation: Boolean
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
        put("request_replay_allowed", replayAllowed)
        put("logical_continuation_allowed", !requiresReconciliation && kind.retryable)
        put("partial_output_preserved", receivedPayload)
        put("visible_output_preserved", receivedVisibleText)
        put("failure_kind", kind.code)
        put("cause_types", JsonArray(sanitizedCauseTypes(error).map(::JsonPrimitive)))
        if (requiresReconciliation) put("requires_reconciliation", true)
    }
)
