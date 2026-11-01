package dev.chungjungsoo.gptmobile.data.github

import dev.chungjungsoo.gptmobile.data.agent.AgentToolResult
import dev.chungjungsoo.gptmobile.data.agent.ToolResultEnvelope
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Per invocation, including compound operations. A read preflight is never a sent mutation. */
internal class GitHubDispatchAccounting : AbstractCoroutineContextElement(Key) {
    companion object Key : CoroutineContext.Key<GitHubDispatchAccounting> {
        suspend fun started(mutation: Boolean) = currentCoroutineContext()[Key]?.start(mutation)
        suspend fun responded(mutation: Boolean, status: Int) = currentCoroutineContext()[Key]?.response(mutation, status)
        suspend fun cached(observedAt: Long) = currentCoroutineContext()[Key]?.cache(observedAt)
    }

    private var reads = 0
    private var mutations = 0
    private var accepted = 0
    private var rejected = 0
    private var unknown = 0
    private var lastStatus: Int? = null
    private var cachedReads = 0
    private var oldestCacheObservation: Long? = null

    @Synchronized private fun cache(observedAt: Long) {
        cachedReads++
        oldestCacheObservation = minOf(oldestCacheObservation ?: observedAt, observedAt)
    }

    @Synchronized private fun start(mutation: Boolean) {
        dev.chungjungsoo.gptmobile.data.diagnostics.AppLogRecorder.record("GitHub", "REQUEST_EXECUTION_STATE · state=DISPATCHED · mutation=$mutation")
        if (mutation) {
            mutations++
            unknown++ // Stays unknown if transmission or reading the response is interrupted.
        } else {
            reads++
        }
    }

    @Synchronized private fun response(mutation: Boolean, status: Int) {
        if (mutation || mutations == 0) lastStatus = status
        if (!mutation) return
        when (status) {
            in 200..299 -> {
                unknown--
                accepted++
            }
            in 400..499 -> {
                unknown--
                rejected++
            }
            // A server error does not prove that a write had no effect.
        }
    }

    @Synchronized fun finish(result: AgentToolResult, mutation: Boolean): AgentToolResult {
        val uncertain = unknown > 0 || result.errorCode in ToolResultEnvelope.unknownOutcomeCodes
        val phase = when {
            uncertain -> "OUTCOME_UNKNOWN"
            result.isError && accepted > 0 -> "PARTIAL_ACCEPTED"
            accepted > 0 -> "ACCEPTED"
            rejected > 0 -> "REJECTED"
            result.isError && mutations == 0 -> "PREFLIGHT_REJECTED"
            reads > 0 -> "OBSERVED"
            else -> "LOCAL_OR_REPLAYED"
        }
        return result.copy(
            errorCode = if (uncertain) "RECONCILIATION_REQUIRED" else result.errorCode,
            dispatched = if (mutation) mutations > 0 || uncertain && result.dispatched else reads > 0 || mutations > 0 || result.dispatched && !result.isError && cachedReads == 0,
            executionReceipt = buildJsonObject {
                put("schema", "github.dispatch.v1")
                put("phase", phase)
                put("read_requests", reads)
                put("cached_read_observations", cachedReads)
                oldestCacheObservation?.let { put("source_observed_at_ms", it) }
                put("mutation_requests", mutations)
                put("accepted_mutations", accepted)
                put("rejected_mutations", rejected)
                put("unknown_mutations", unknown)
                put("requires_reconciliation", uncertain || result.isError && accepted > 0)
                lastStatus?.let { put("http_status", it) }
                result.errorCode?.let { put("error_code", it) }
            }
        )
    }
}
