package dev.chungjungsoo.gptmobile.data.github

import dev.chungjungsoo.gptmobile.data.agent.AgentToolResult
import dev.chungjungsoo.gptmobile.data.agent.ToolResultEnvelope
import dev.chungjungsoo.gptmobile.data.diagnostics.AppLogRecorder
import java.util.UUID
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Invocation-local observations. HTTP acceptance is not body delivery or task completion. */
internal class GitHubDispatchAccounting(
    val forceLiveReads: Boolean = false,
    private val now: () -> Long = { System.nanoTime() / 1_000_000 },
    private val record: (String) -> Unit = { AppLogRecorder.record("GitHub", it) }
) : AbstractCoroutineContextElement(Key) {
    companion object Key : CoroutineContext.Key<GitHubDispatchAccounting> {
        suspend fun started(mutation: Boolean): String? = currentCoroutineContext()[Key]?.start(mutation)
        suspend fun responded(mutation: Boolean, status: Int, requestId: String? = null, serverRequestId: String? = null) =
            currentCoroutineContext()[Key]?.response(mutation, status, requestId, serverRequestId, false)
        suspend fun responded(mutation: Boolean, status: Int, requestId: String?, serverRequestId: String?, expectedAbsence: Boolean) =
            currentCoroutineContext()[Key]?.response(mutation, status, requestId, serverRequestId, expectedAbsence)
        suspend fun cached(observedAt: Long?, immutable: Boolean = false) = currentCoroutineContext()[Key]?.cache(observedAt, immutable)
    }

    private data class Pending(val mutation: Boolean, val startedAt: Long)
    private val invocationId = UUID.randomUUID().toString()
    private val pending = LinkedHashMap<String, Pending>()
    private var sequence = 0L
    private var reads = 0
    private var readResponses = 0
    private var readHttpFailures = 0
    private var expectedAbsenceResponses = 0
    private var observationMismatches = 0
    private var mutations = 0
    private var accepted = 0
    private var rejected = 0
    private var unknown = 0
    private var lastStatus: Int? = null
    private var cachedReads = 0
    private var oldestCacheObservation: Long? = null
    private var oldestImmutableObservation: Long? = null
    private var terminalRecorded = false

    private fun emit(value: String) {
        // Diagnostics must not turn an accepted operation into a transport failure.
        runCatching { record(value) }
    }

    @Synchronized private fun cache(observedAt: Long?, immutable: Boolean) {
        cachedReads++
        if (observedAt == null) return
        if (immutable) {
            oldestImmutableObservation = minOf(oldestImmutableObservation ?: observedAt, observedAt)
        } else {
            oldestCacheObservation = minOf(oldestCacheObservation ?: observedAt, observedAt)
        }
    }

    @Synchronized private fun start(mutation: Boolean): String {
        val requestId = "$invocationId:${++sequence}"
        pending[requestId] = Pending(mutation, now())
        if (mutation) {
            mutations++
            unknown++
        } else {
            reads++
        }
        emit("REQUEST_EXECUTION_STATE · state=DISPATCHED · invocationId=$invocationId · requestId=$requestId · mutation=$mutation")
        return requestId
    }

    @Synchronized private fun response(mutation: Boolean, status: Int, requestId: String?, serverRequestId: String?, expectedAbsence: Boolean) {
        // The optional legacy path matches only an outstanding request. Explicit IDs make
        // concurrent, reordered and duplicated callbacks safe without decrementing twice.
        val id = requestId ?: pending.entries.firstOrNull { it.value.mutation == mutation }?.key
        val request = id?.let(pending::get)
        if (id == null || request == null || request.mutation != mutation || status !in 100..599) {
            observationMismatches++
            return
        }
        pending.remove(id)
        if (mutation || mutations == 0) lastStatus = status
        val outcome = if (mutation) {
            when (status) {
                in 200..299 -> {
                    unknown--
                    accepted++
                    "ACCEPTED"
                }
                in 400..499 -> if (status in setOf(408, 499)) {
                    "UNKNOWN_OUTCOME"
                } else {
                    unknown--
                    rejected++
                    "REJECTED"
                }
                // Server errors and request timeouts do not establish absence of write effects.
                else -> "UNKNOWN_OUTCOME"
            }
        } else {
            readResponses++
            if (expectedAbsence && status == 404) {
                expectedAbsenceResponses++
            } else if (status >= 400) {
                readHttpFailures++
            }
            if (expectedAbsence && status == 404) "EXPECTED_OWNED_REF_ABSENCE" else "READ_RESPONSE"
        }
        val server = serverRequestId?.takeIf { it.matches(Regex("[a-zA-Z0-9:._-]{1,160}")) } ?: "not_recorded"
        emit("REQUEST_EXECUTION_STATE · state=HTTP_RESPONSE_OBSERVED · invocationId=$invocationId · requestId=$id · mutation=$mutation · httpStatus=$status · requestOutcome=$outcome · elapsedMs=${(now() - request.startedAt).coerceAtLeast(0)} · serverRequestId=$server · taskCompletion=NOT_MEASURED")
    }

    @Synchronized fun cancelled() = recordTerminal("CANCELLED")

    private fun recordTerminal(phase: String) {
        if (terminalRecorded) return
        terminalRecorded = true
        pending.forEach { (id, request) ->
            val state = if (request.mutation) "UNKNOWN_OUTCOME" else "NO_RESPONSE_OBSERVED"
            emit("REQUEST_EXECUTION_STATE · state=$state · invocationId=$invocationId · requestId=$id · mutation=${request.mutation} · cause=OPERATION_TERMINATED · elapsedMs=${(now() - request.startedAt).coerceAtLeast(0)}")
        }
        emit("REQUEST_EXECUTION_STATE · state=OPERATION_TERMINAL · invocationId=$invocationId · phase=$phase · readRequests=$reads · readResponses=$readResponses · mutationRequests=$mutations · acceptedMutations=$accepted · rejectedMutations=$rejected · unknownMutations=$unknown · taskCompletion=NOT_MEASURED")
    }

    @Synchronized fun finish(result: AgentToolResult, mutation: Boolean): AgentToolResult {
        val uncertain = unknown > 0 || result.errorCode in ToolResultEnvelope.unknownOutcomeCodes
        val phase = when {
            uncertain -> "OUTCOME_UNKNOWN"
            result.isError && accepted > 0 -> "PARTIAL_ACCEPTED"
            accepted > 0 -> "ACCEPTED"
            rejected > 0 -> "REJECTED"
            result.isError && mutations == 0 && reads > 0 && !mutation -> "READ_FAILED"
            result.isError && mutations == 0 -> "PREFLIGHT_REJECTED"
            reads > 0 -> "OBSERVED"
            else -> "LOCAL_OR_REPLAYED"
        }
        recordTerminal(phase)
        return result.copy(
            errorCode = if (uncertain) "RECONCILIATION_REQUIRED" else result.errorCode,
            dispatched = if (mutation) mutations > 0 || uncertain && result.dispatched else reads > 0 || mutations > 0 || result.dispatched && !result.isError && cachedReads == 0,
            executionReceipt = buildJsonObject {
                put("schema", "github.dispatch.v1")
                put("invocation_id", invocationId)
                put("phase", phase)
                put("live_read_requested", forceLiveReads)
                put("live_read_performed", reads > 0 && readResponses == reads && observationMismatches == 0)
                put(
                    "live_read_status",
                    when {
                        reads > 0 && readResponses == reads && observationMismatches == 0 -> "PERFORMED"
                        reads > 0 -> "RESPONSE_NOT_FULLY_OBSERVED"
                        forceLiveReads -> "NOT_PERFORMED"
                        else -> "NOT_REQUESTED"
                    }
                )
                put("read_requests", reads)
                put("read_response_observations", readResponses)
                put("read_http_failures", readHttpFailures)
                put("expected_absence_responses", expectedAbsenceResponses)
                put("unobserved_read_requests", pending.values.count { !it.mutation })
                put("observation_mismatches", observationMismatches)
                put("cached_read_observations", cachedReads)
                (oldestCacheObservation ?: oldestImmutableObservation?.takeIf { reads == 0 })?.let { put("source_observed_at_ms", it) }
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
