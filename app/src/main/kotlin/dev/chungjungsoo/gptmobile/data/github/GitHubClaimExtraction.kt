package dev.chungjungsoo.gptmobile.data.github

import dev.chungjungsoo.gptmobile.data.agent.AgentProviderSession
import dev.chungjungsoo.gptmobile.data.agent.ProviderEvent
import dev.chungjungsoo.gptmobile.data.network.NetworkFailureKind
import dev.chungjungsoo.gptmobile.data.network.ProviderLimitDecision
import dev.chungjungsoo.gptmobile.data.network.ProviderRetryPolicy
import dev.chungjungsoo.gptmobile.data.network.networkFailureKind
import dev.chungjungsoo.gptmobile.data.network.providerExceptionDetails
import dev.chungjungsoo.gptmobile.data.network.sanitizedCauseTypes
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Provider attempts finish before a malformed or length-limited claim batch can be retried. */
internal object GitHubClaimExtraction {
    /** Provider error names alone never grant another attempt. */
    class Failure(
        val code: String,
        val providerFailure: ProviderEvent.Failed? = null,
        val repairAllowed: Boolean = false,
        val observations: JsonObject? = null,
        val rejectionRecoveryAllowed: Boolean = false
    ) : IllegalStateException(code)

    /** One short wait belongs to this saved, tool-free batch, never to primary generation. */
    class RejectionRecovery {
        private var used = false

        suspend fun retry(
            failure: Failure,
            now: () -> Long,
            validate: suspend () -> Unit,
            pause: suspend (Long) -> Unit = { delay(it) }
        ): Boolean {
            val decision = rejectedDecision(failure) ?: return false
            val remaining = ProviderRetryPolicy.remainingMillis(decision.retryAt, now())
            if (used || remaining > MAX_REJECTION_WAIT_MS) return false
            used = true
            validate()
            pause(remaining)
            validate()
            return true
        }
    }

    fun rejectedDecision(failure: Failure): ProviderLimitDecision? = failure.providerFailure?.providerLimit?.takeIf { failure.rejectionRecoveryAllowed }

    /** A deadline may interrupt a provider attempt or later local checkpoint/rendering work. */
    class Attempt {
        var awaitingTerminal = false
        var terminalFailure: ProviderEvent.Failed? = null

        fun failureAtDeadline(): ProviderEvent.Failed? = terminalFailure ?: if (awaitingTerminal) incompleteProviderFailure() else null
    }

    suspend fun collect(
        request: GitHubClaimBatches.Request,
        openVerifier: suspend (String) -> AgentProviderSession,
        computationDispatcher: CoroutineDispatcher,
        attempt: Attempt = Attempt()
    ): JsonObject {
        val response = StringBuilder()
        var complete = false
        var receivedProviderOutput = false
        var oversized = false
        var unexpectedOperation = false
        var finishReason: String? = null
        var failure: ProviderEvent.Failed? = null
        var repairProhibited = false
        var rejectionProhibited = false
        fun interrupted(event: ProviderEvent.Failed, allowRejection: Boolean = true, code: String = event.errorCode ?: "GITHUB_EXTRACTION_FAILED") = Failure(
            code,
            event,
            observations = buildJsonObject {
                put("claim_extraction_response_characters", response.length)
                put("claim_extraction_max_claims", request.maxClaims)
                put("claim_extraction_completed", complete)
            },
            rejectionRecoveryAllowed = allowRejection && !receivedProviderOutput && !unexpectedOperation && !rejectionProhibited && rejectedLimit(event) != null
        )
        try {
            attempt.awaitingTerminal = true
            attempt.terminalFailure = null
            openVerifier(request.payload).streamRound(emptyList(), emptyList()).collect { event ->
                when (event) {
                    is ProviderEvent.ThinkingDelta -> if (event.text.isNotEmpty()) receivedProviderOutput = true
                    is ProviderEvent.TextDelta -> {
                        if (event.text.isNotEmpty()) receivedProviderOutput = true
                        if (response.length + event.text.length.toLong() > MAX_OUTPUT_CHARACTERS) oversized = true
                        // Drain a bounded response to its terminal event. Aborting the
                        // stream here would not establish that another attempt is safe.
                        if (!oversized) response.append(event.text)
                    }
                    is ProviderEvent.Completed -> {
                        complete = true
                        attempt.awaitingTerminal = false
                    }
                    is ProviderEvent.Usage -> event.finishReason?.let { finishReason = it }
                    is ProviderEvent.Failed -> {
                        attempt.awaitingTerminal = false
                        // Later frames may tighten safety without taking ownership
                        // from the first failed attempt.
                        repairProhibited = repairProhibited || !completedLimitAllowsRepair(event)
                        rejectionProhibited = rejectionProhibited || rejectedLimit(event) == null
                        // Preserve the first failure. A later limit/Completed frame
                        // cannot replace a 429 or another failed attempt. Explicit
                        // uncertainty may only tighten that original disposition.
                        if (failure == null || !outcomeUnknown(failure) && outcomeUnknown(event)) failure = event
                        attempt.terminalFailure = failure
                    }
                    is ProviderEvent.ToolCall, is ProviderEvent.ToolResult -> unexpectedOperation = true
                    else -> Unit
                }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failed: Failure) {
            throw failed
        } catch (error: Exception) {
            val policy = generateSequence<Throwable>(error) { it.cause?.takeUnless { next -> next === it } }.take(16).firstOrNull {
                it is dev.chungjungsoo.gptmobile.data.context.DispatchPolicyChangedException ||
                    it is dev.chungjungsoo.gptmobile.data.context.ProviderWaitAuthorityChanged ||
                    it is dev.chungjungsoo.gptmobile.data.agent.SavedEvidenceAccessDenied ||
                    it is dev.chungjungsoo.gptmobile.data.agent.ProviderRecoveryFailure
            }
            if (policy != null) throw policy
            failure?.let { throw interrupted(it, allowRejection = false) }
            val provider = providerExceptionDetails(error, partialOutput = receivedProviderOutput)
            val transport = networkFailureKind(error)
            val unknown = provider == null && transport == NetworkFailureKind.UNKNOWN
            val code = provider?.code ?: transport.code.takeUnless { transport == NetworkFailureKind.UNKNOWN } ?: "GITHUB_EXTRACTION_FAILED"
            val original = ProviderEvent.Failed(
                provider?.message ?: "Claim extraction did not complete. Saved evidence remains available; inspect the retained provider failure before resuming.",
                errorCode = if (unknown) "PROVIDER_INVOCATION_FAILED" else code,
                providerLimit = provider?.providerLimit,
                failureDetails = JsonObject(
                    provider?.failureDetails.orEmpty() + buildJsonObject {
                        if (provider?.failureDetails?.get("failed_stage") == null) put("failed_stage", "claim_extraction")
                        put("review_stage", "claim_extraction")
                        put("cause_types", JsonArray(sanitizedCauseTypes(error).map(::JsonPrimitive)))
                        if (receivedProviderOutput) {
                            put("request_replay_allowed", false)
                            put("logical_continuation_allowed", false)
                            put("retryable", false)
                        }
                        if (unknown) {
                            put("retryable", false)
                            put("request_replay_allowed", false)
                            put("logical_continuation_allowed", false)
                            put("remote_acceptance", "UNKNOWN")
                        }
                    }
                )
            )
            attempt.awaitingTerminal = false
            attempt.terminalFailure = original
            throw interrupted(original, code = code)
        }

        val terminalFailure = failure
        val completedLimit = terminalFailure != null && !repairProhibited
        if (terminalFailure != null && !completedLimit) throw interrupted(terminalFailure)
        if (!complete && !completedLimit) throw Failure("GITHUB_EXTRACTION_FAILED", incompleteProviderFailure())
        if (unexpectedOperation) {
            val observed = terminalFailure ?: ProviderEvent.Failed("Evidence formatting attempted an operation.", errorCode = "TOOL_PROTOCOL_INVALID_FRAME", transportComplete = true)
            throw Failure(observed.errorCode ?: "GITHUB_EXTRACTION_FAILED", observed)
        }
        val outputLimited = oversized || completedLimit || finishReason?.lowercase() in setOf("length", "max_tokens", "max_output_tokens", "token_limit")
        if (oversized) throw Failure("GITHUB_EXTRACTION_OUTPUT_LIMIT", terminalFailure, repairAllowed = true)
        var invalidKind = "INVALID_JSON"
        var validationStage: String? = null
        return try {
            withContext(computationDispatcher) {
                val payload = structuredPayload(response.toString())
                val parsed = Json.parseToJsonElement(payload)
                // Some providers/parsers admit bare unquoted tokens as JSON primitives.
                // Do not misclassify private arbitrary text as a valid non-object JSON value.
                if (parsed is JsonPrimitive) {
                    val token = payload.trim()
                    if (!token.startsWith('"') && !STRICT_JSON_SCALAR.matches(token)) {
                        error("Claim extraction received a non-JSON scalar")
                    }
                }
                invalidKind = "NON_OBJECT"
                val answer = parsed as? JsonObject ?: error("Claim extraction was not structured")
                invalidKind = "SCHEMA_REJECTED"
                GitHubClaimBatches.validate(answer, request) { validationStage = it }
            }.also { attempt.terminalFailure = null }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (invalid: Exception) {
            // Only closed classifications and bounded counts leave the verifier.
            // Never include the model's raw output or a parser's exception text.
            // A length flag alone cannot invalidate schema-valid completed output.
            val code = if (outputLimited) "GITHUB_EXTRACTION_OUTPUT_LIMIT" else "GITHUB_EXTRACTION_INVALID_RESPONSE"
            val observed = terminalFailure ?: ProviderEvent.Failed(
                "Claim extraction completed but did not pass local structured-response validation.",
                errorCode = code,
                transportComplete = true,
                failureDetails = buildJsonObject {
                    put("failed_stage", "claim_extraction")
                    put("diagnostic_area", "PROVIDER")
                    put("claim_extraction_failure_kind", invalidKind)
                    validationStage?.let { put("claim_extraction_validation_stage", it) }
                    put("claim_extraction_response_characters", response.length.coerceAtMost(MAX_OUTPUT_CHARACTERS))
                    put("claim_extraction_max_claims", request.maxClaims)
                    put("claim_extraction_completed", complete)
                }
            )
            throw Failure(code, observed, repairAllowed = true)
        }
    }

    /** Accept only a single whole-response JSON fence; all claim contents still require schema validation. */
    private fun structuredPayload(raw: String): String {
        val trimmed = raw.trim()
        val fenced = JSON_FENCE.matchEntire(trimmed) ?: return trimmed
        return fenced.groupValues[1].trim()
    }

    // Only JSON quoted strings, true/false/null, and JSON-number tokens are scalar JSON.
    private val STRICT_JSON_SCALAR = Regex("""(?:true|false|null|-?(?:0|[1-9][0-9]*)(?:[.][0-9]+)?(?:[eE][+-]?[0-9]+)?)""")

    private val JSON_FENCE = Regex("""\A```(?:json)?\r?\n([\s\S]*)\r?\n```\z""", RegexOption.IGNORE_CASE)

    private fun incompleteProviderFailure() = ProviderEvent.Failed(
        "Claim extraction ended without a complete response. Saved evidence remains available.",
        errorCode = "INCOMPLETE_PROVIDER_STREAM",
        transportComplete = false,
        failureDetails = buildJsonObject {
            put("failed_stage", "claim_extraction")
            put("retryable", false)
            put("request_replay_allowed", false)
            put("logical_continuation_allowed", false)
            put("remote_acceptance", "UNKNOWN")
        }
    )

    // A smaller claim batch is a new, read-only extraction over retained evidence.
    // It is not a replay of the original request, but explicit no-retry or
    // reconciliation facts still prohibit automatic repair.
    private fun completedLimitAllowsRepair(failure: ProviderEvent.Failed): Boolean {
        val details = failure.failureDetails
        if (outcomeUnknown(failure) || details?.get("automatic_retry_allowed") == JsonPrimitive(false)) return false

        // A smaller batch is a new, read-only extraction over retained evidence. It never
        // replays the original request or continues its logical invocation. If the provider
        // explicitly denied those operations, require proof that the original generation
        // was accepted/completed before allowing this distinct extraction.
        val explicitNoReplay = listOf("retryable", "request_replay_allowed", "logical_continuation_allowed")
            .any { details?.get(it) == JsonPrimitive(false) }
        val acceptedOrCompleted = details?.get("remote_acceptance") in setOf(JsonPrimitive("YES"), JsonPrimitive("ACCEPTED")) ||
            details?.get("generation_completed") == JsonPrimitive(true)
        if (explicitNoReplay && !acceptedOrCompleted) return false

        return (failure.transportComplete || details?.get("generation_completed") == JsonPrimitive(true)) &&
            failure.errorCode in setOf("OUTPUT_LIMIT", "COMPLETION_REPAIR_BUDGET_EXHAUSTED", "GITHUB_EXTRACTION_OUTPUT_LIMIT")
    }

    private fun rejectedLimit(failure: ProviderEvent.Failed): ProviderLimitDecision? = failure.providerLimit?.takeIf { limit ->
        limit.knownNotAccepted &&
            !outcomeUnknown(failure) &&
            failure.errorCode?.uppercase(java.util.Locale.ROOT).let { it == null || it in setOf("429", "RATE_LIMIT", "RATE_LIMIT_EXCEEDED", "PROVIDER_RATE_LIMITED", "LOCAL_COOLDOWN_ACTIVE", "LOCAL_ALLOWANCE_EXHAUSTED") } &&
            failure.failureDetails?.get("remote_acceptance") !in setOf(JsonPrimitive("YES"), JsonPrimitive("ACCEPTED")) &&
            failure.failureDetails?.get("generation_completed") != JsonPrimitive(true) &&
            failure.failureDetails?.get("job_id") == null &&
            listOf("retryable", "request_replay_allowed", "logical_continuation_allowed", "automatic_retry_allowed").none { failure.failureDetails?.get(it) == JsonPrimitive(false) } &&
            (limit.httpStatus == 429 || failure.errorCode == "429" || limit.category in setOf("RATE_LIMIT", "PROVIDER_RATE_LIMITED", "LOCAL_COOLDOWN_ACTIVE", "LOCAL_ALLOWANCE_EXHAUSTED"))
    }

    private fun outcomeUnknown(failure: ProviderEvent.Failed?): Boolean = failure != null &&
        (
            failure.failureDetails?.get("requires_reconciliation") == JsonPrimitive(true) ||
                failure.failureDetails?.get("remote_acceptance") == JsonPrimitive("UNKNOWN") ||
                failure.errorCode in setOf("GATEWAY_OUTCOME_UNKNOWN", "TOOL_OUTCOME_UNKNOWN", "TIMEOUT_OUTCOME_UNKNOWN", "DISPATCH_OUTCOME_UNKNOWN", "RECONCILIATION_REQUIRED")
            )

    private const val MAX_OUTPUT_CHARACTERS = 32_768
    private const val MAX_REJECTION_WAIT_MS = 10_000L
}
