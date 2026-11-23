package dev.chungjungsoo.gptmobile.data.github

import dev.chungjungsoo.gptmobile.data.agent.AgentToolResult
import dev.chungjungsoo.gptmobile.data.agent.ToolResultContent
import dev.chungjungsoo.gptmobile.data.agent.ToolResultEnvelope
import dev.chungjungsoo.gptmobile.data.diagnostics.AppLogRecorder
import java.util.UUID
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Invocation-local observations. HTTP acceptance is not body delivery or task completion. */
internal class GitHubDispatchAccounting(
    val forceLiveReads: Boolean = false,
    private val now: () -> Long = { System.nanoTime() / 1_000_000 },
    private val record: (String) -> Unit = { AppLogRecorder.record("GitHub", it) },
    private val maxReadRequests: Int? = null,
    val requireReadInventory: Boolean = false
) : AbstractCoroutineContextElement(Key) {
    init {
        require(maxReadRequests == null || maxReadRequests in 1..128)
    }
    companion object Key : CoroutineContext.Key<GitHubDispatchAccounting> {
        suspend fun started(mutation: Boolean, path: String? = null): String? {
            val context = currentCoroutineContext()
            return context[Key]?.start(mutation, path, context[GitHubReadRetry.Scope])
        }
        suspend fun responded(mutation: Boolean, status: Int, requestId: String? = null, serverRequestId: String? = null) =
            currentCoroutineContext()[Key]?.response(mutation, status, requestId, serverRequestId, false)
        suspend fun responded(mutation: Boolean, status: Int, requestId: String?, serverRequestId: String?, expectedAbsence: Boolean) =
            currentCoroutineContext()[Key]?.response(mutation, status, requestId, serverRequestId, expectedAbsence)
        suspend fun locallyRejected(requestId: String?) = currentCoroutineContext()[Key]?.localRejection(requestId)
        suspend fun cached(observedAt: Long?, immutable: Boolean = false) = currentCoroutineContext()[Key]?.cache(observedAt, immutable)
        suspend fun freshness(path: String, reason: GitHubReadFreshness, observedAt: Long? = null) =
            currentCoroutineContext()[Key]?.recordFreshness(path, reason, observedAt)
    }

    private data class Pending(
        val mutation: Boolean,
        val startedAt: Long,
        val endpoint: String,
        val targetDigest: String?,
        val readRetry: GitHubReadRetry.Scope?,
        val readAttempt: Int?,
        val requestDigest: String?
    )
    private val failures = mutableListOf<kotlinx.serialization.json.JsonObject>()
    private var omittedFailures = 0
    private val invocationId = UUID.randomUUID().toString()
    private val pending = LinkedHashMap<String, Pending>()
    private var sequence = 0L
    private var reads = 0
    private var localRequestRejections = 0
    private var readResponses = 0
    private var recoveredReadRequests = 0
    private val observedRetryTargets = mutableMapOf<GitHubReadRetry.Scope, MutableMap<Int, MutableSet<String>>>()
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
    private val freshnessObservations = linkedMapOf<String, kotlinx.serialization.json.JsonObject>()
    private var omittedFreshnessObservations = 0

    @Synchronized private fun recordFreshness(path: String, reason: GitHubReadFreshness, observedAt: Long?) {
        val target = GitHubEvidenceBook.digest(path)
        val key = "$target:${reason.name}"
        if (key in freshnessObservations) return
        if (freshnessObservations.size >= 32) {
            omittedFreshnessObservations++
            return
        }
        freshnessObservations[key] = buildJsonObject {
            put("target_sha256", target)
            put("reason", reason.name)
            observedAt?.takeIf { it > 0 }?.let { put("previous_observed_at_ms", it) }
        }
    }

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

    @Synchronized private fun start(mutation: Boolean, path: String?, readRetry: GitHubReadRetry.Scope?): String {
        if (!mutation && maxReadRequests != null && reads >= maxReadRequests) throw GitHubReadRequestBudgetExceeded()
        val requestId = "$invocationId:${++sequence}"
        val route = path?.substringBefore('?').orEmpty()
        val endpoint = when {
            "/protection" in route -> "BRANCH_PROTECTION"
            "/rules" in route -> "RULES"
            "/actions/" in route -> "ACTIONS"
            "/releases" in route -> "RELEASES"
            "/pulls" in route -> "PULL_REQUESTS"
            "/issues" in route -> "ISSUES"
            "/git/ref" in route -> "GIT_REFS"
            "/commits" in route -> "COMMITS"
            "/contents" in route -> "CONTENTS"
            "/branches" in route -> "BRANCHES"
            route.startsWith("/repos/") -> "REPOSITORY"
            else -> "OTHER"
        }
        pending[requestId] = Pending(
            mutation,
            now(),
            endpoint,
            route.takeIf { it.isNotBlank() }?.let(GitHubEvidenceBook::digest),
            readRetry.takeUnless { mutation },
            readRetry?.attempt.takeUnless { mutation },
            path?.takeIf { it.isNotBlank() }?.let(GitHubEvidenceBook::digest)
        )
        if (mutation) {
            mutations++
            unknown++
        } else {
            reads++
        }
        emit("REQUEST_EXECUTION_STATE · state=ATTEMPT_STARTED · invocationId=$invocationId · requestId=$requestId · mutation=$mutation")
        return requestId
    }

    /** Settle only this app-owned pre-send rejection; earlier requests retain their own uncertainty. */
    @Synchronized private fun localRejection(requestId: String?) {
        val request = requestId?.let(pending::remove) ?: return
        if (request.mutation) {
            mutations--
            unknown--
        } else {
            reads--
        }
        localRequestRejections++
        emit("REQUEST_EXECUTION_STATE · state=NOT_DISPATCHED · invocationId=$invocationId · requestId=$requestId · mutation=${request.mutation} · cause=LOCAL_POLICY_REJECTION")
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
            val qualifiedResponse = status in 200..299 || status == 304 || expectedAbsence && status == 404
            if (qualifiedResponse && request.readRetry != null && request.readAttempt != null && request.requestDigest != null) {
                observedRetryTargets.getOrPut(request.readRetry) { mutableMapOf() }
                    .getOrPut(request.readAttempt) { mutableSetOf() }.add(request.requestDigest)
            }
            if (expectedAbsence && status == 404) {
                expectedAbsenceResponses++
            } else if (status >= 400) {
                readHttpFailures++
            }
            if (expectedAbsence && status == 404) "EXPECTED_OWNED_REF_ABSENCE" else "READ_RESPONSE"
        }
        if (status >= 400 && !(expectedAbsence && status == 404)) {
            if (failures.size < 32) {
                failures += buildJsonObject {
                    put("request_id", id)
                    put("endpoint_class", request.endpoint)
                    request.targetDigest?.let { put("target_sha256", it) }
                    put("http_status", status)
                    put("mutation", mutation)
                    put("elapsed_ms", (now() - request.startedAt).coerceAtLeast(0))
                    put(
                        "cause",
                        when (status) {
                            401 -> "AUTHENTICATION"
                            403 -> "ACCESS_OR_RATE_POLICY"
                            404 -> "NOT_FOUND_OR_HIDDEN"
                            408, 504 -> "DEADLINE"
                            429 -> "RATE_LIMIT"
                            in 500..599 -> "SERVICE_UNAVAILABLE"
                            else -> "HTTP_ERROR"
                        }
                    )
                    put("requirement_scope", "CALLER_NOT_RECORDED")
                }
            } else {
                omittedFailures++
            }
        }
        val server = serverRequestId?.takeIf { it.matches(Regex("[a-zA-Z0-9:._-]{1,160}")) } ?: "not_recorded"
        emit("REQUEST_EXECUTION_STATE · state=HTTP_RESPONSE_OBSERVED · invocationId=$invocationId · requestId=$id · mutation=$mutation · httpStatus=$status · requestOutcome=$outcome · elapsedMs=${(now() - request.startedAt).coerceAtLeast(0)} · serverRequestId=$server · taskCompletion=NOT_MEASURED")
    }

    @Synchronized fun cancelled() = recordTerminal("CANCELLED")

    /** A completed retry can replace only its own unanswered GET of the exact path and query. */
    @Synchronized internal fun completeReadRetry(scope: GitHubReadRetry.Scope, recovered: Boolean) {
        // Earlier responses, including HTTP failures, do not certify the final retry's target.
        val observed = observedRetryTargets.remove(scope)?.get(scope.attempt).orEmpty()
        if (!recovered || observed.isEmpty()) return
        val attempts = pending.filterValues { request ->
            !request.mutation &&
                request.readRetry === scope &&
                request.readAttempt != null &&
                request.readAttempt < scope.attempt &&
                request.requestDigest in observed
        }
        attempts.forEach { (id, request) ->
            pending.remove(id)
            recoveredReadRequests++
            emit("REQUEST_EXECUTION_STATE · state=READ_RETRY_RECOVERED · invocationId=$invocationId · requestId=$id · mutation=false · elapsedMs=${(now() - request.startedAt).coerceAtLeast(0)} · responseForOriginalAttempt=NOT_OBSERVED")
        }
    }

    @Synchronized fun mutationMayHaveTakenEffect(): Boolean = accepted > 0 || unknown > 0

    private fun recordTerminal(phase: String) {
        if (terminalRecorded) return
        terminalRecorded = true
        pending.forEach { (id, request) ->
            val state = if (request.mutation) "UNKNOWN_OUTCOME" else "NO_RESPONSE_OBSERVED"
            emit("REQUEST_EXECUTION_STATE · state=$state · invocationId=$invocationId · requestId=$id · mutation=${request.mutation} · cause=OPERATION_TERMINATED · elapsedMs=${(now() - request.startedAt).coerceAtLeast(0)}")
        }
        emit("REQUEST_EXECUTION_STATE · state=OPERATION_TERMINAL · invocationId=$invocationId · phase=$phase · readRequests=$reads · readResponses=$readResponses · mutationRequests=$mutations · acceptedMutations=$accepted · rejectedMutations=$rejected · unknownMutations=$unknown · taskCompletion=NOT_MEASURED")
    }

    @Synchronized fun finish(candidate: AgentToolResult, mutation: Boolean): AgentToolResult {
        val liveObserved = reads > 0 && readResponses + recoveredReadRequests == reads && observationMismatches == 0
        val result = if (forceLiveReads && !mutation && !candidate.isError && !liveObserved) {
            candidate.copy(
                content = ToolResultContent.Text("A fresh GitHub response was not verified for this request. Run the exact read again with verify_live=true."),
                isError = true,
                dispatched = reads > 0,
                errorCode = "GITHUB_LIVE_OBSERVATION_REQUIRED"
            )
        } else {
            candidate
        }
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
                put("live_read_performed", liveObserved)
                put(
                    "live_read_status",
                    when {
                        liveObserved -> "PERFORMED"
                        reads > 0 -> "RESPONSE_NOT_FULLY_OBSERVED"
                        forceLiveReads -> "NOT_PERFORMED"
                        else -> "NOT_REQUESTED"
                    }
                )
                put("read_requests", reads)
                put("local_request_rejections", localRequestRejections)
                put("read_response_observations", readResponses)
                put("recovered_read_requests", recoveredReadRequests)
                put("read_http_failures", readHttpFailures)
                put("failed_requests", kotlinx.serialization.json.JsonArray(failures))
                put("omitted_failed_requests", omittedFailures)
                put("parent_result_successful", !result.isError)
                put("expected_absence_responses", expectedAbsenceResponses)
                put("unobserved_read_requests", pending.values.count { !it.mutation })
                put("observation_mismatches", observationMismatches)
                put("cached_read_observations", cachedReads)
                if (freshnessObservations.isNotEmpty()) put("read_freshness", kotlinx.serialization.json.JsonArray(freshnessObservations.values.toList()))
                if (omittedFreshnessObservations > 0) put("omitted_read_freshness", omittedFreshnessObservations)
                put(
                    "read_source",
                    when {
                        liveObserved && cachedReads > 0 -> "MIXED"
                        liveObserved -> "LIVE"
                        cachedReads > 0 -> "RETAINED"
                        else -> "UNVERIFIED"
                    }
                )
                (oldestCacheObservation ?: oldestImmutableObservation?.takeIf { reads == 0 })?.let { put("source_observed_at_ms", it) }
                put("mutation_requests", mutations)
                put("accepted_mutations", accepted)
                put("rejected_mutations", rejected)
                put("unknown_mutations", unknown)
                put("requires_reconciliation", uncertain || result.isError && accepted > 0)
                // A redirect or metadata response cannot identify the HTTP status of an unanswered transport read.
                if (result.errorCode != "GITHUB_TRANSIENT_NETWORK" || pending.values.none { !it.mutation }) {
                    lastStatus?.let { put("http_status", it) }
                }
                result.errorCode?.let { put("error_code", it) }
                if (phase == "READ_FAILED" && lastStatus == 422 && result.errorCode == "GITHUB_VALIDATION_FAILED") {
                    for (key in dev.chungjungsoo.gptmobile.data.agent.tool.GitHubToolErrorDiagnostics.validationKeys) {
                        result.executionReceipt?.get(key)?.let { put(key, it) }
                    }
                }
                issueContinuationFields(result, phase)?.forEach { (key, value) -> put(key, value) }
                result.executionReceipt?.get("retry_not_before_ms")?.let { put("retry_not_before_ms", it) }
                for (key in listOf(
                    "github_transport_kind", "github_transport_wait_ms", "cause_types", "exception_fingerprint",
                    "github_cooldown_resource", "github_cooldown_basis", "github_cooldown_delay_ms", "github_cooldown_deadline_ms",
                    "github_cooldown_observed_at_ms", "github_cooldown_remaining_requests", "retry_after_ms"
                )) {
                    result.executionReceipt?.get(key)?.let { put(key, it) }
                }
            }
        )
    }

    private fun issueContinuationFields(result: AgentToolResult, phase: String): JsonObject? {
        if (phase != "PREFLIGHT_REJECTED" || result.dispatched || result.errorCode != "INVALID_ARGUMENTS") return null
        val receipt = result.executionReceipt ?: return null
        val stateName = (receipt["issue_continuation_state"] as? JsonPrimitive)?.takeIf { it.isString }?.content
        val state = GitHubInspection.InvalidIssueContinuation.State.entries.firstOrNull { it.name == stateName } ?: return null
        val missing = state == GitHubInspection.InvalidIssueContinuation.State.MISSING_AFTER
        val fields = when (state) {
            GitHubInspection.InvalidIssueContinuation.State.FILTER_MISMATCH -> listOf("after", "owner", "repo", "state", "per_page", "sort", "direction")
            GitHubInspection.InvalidIssueContinuation.State.PAGE_MISMATCH, GitHubInspection.InvalidIssueContinuation.State.INITIAL_PAGE_WITH_AFTER -> listOf("after", "page")
            else -> listOf("after")
        }
        // Copy only the complete typed rejection shape. Its diagnostic fields
        // cannot replace measured dispatch, HTTP, mutation or reconciliation facts.
        val expected = buildJsonObject {
            put("issue_continuation_rejected", true)
            put("issue_continuation_state", state.name)
            put("reason_code", "TOOL_ARGUMENT_CONSTRAINT_REJECTED")
            put("missing_field_count", if (missing) 1 else 0)
            put("invalid_field_count", if (missing) 0 else 1)
            put(if (missing) "missing_fields" else "invalid_fields", JsonArray(listOf(JsonPrimitive("after"))))
            put("invalid_field_classes", JsonArray(listOf(JsonPrimitive(if (missing) "MISSING_REQUIRED" else "COMPOSITE_CONSTRAINT"))))
            put("validation_field_names", JsonArray(fields.map(::JsonPrimitive)))
        }
        return expected.takeIf {
            expected.all { (key, value) -> receipt[key] == value } &&
                receipt[if (missing) "invalid_fields" else "missing_fields"] == null
        }
    }
}

/** Local admission failure, deliberately outside the retryable I/O hierarchy. */
internal class GitHubReadRequestBudgetExceeded : IllegalStateException("GitHub read request budget exhausted before dispatch")
