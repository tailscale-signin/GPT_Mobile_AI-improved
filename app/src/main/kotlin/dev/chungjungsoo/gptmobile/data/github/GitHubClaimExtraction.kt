package dev.chungjungsoo.gptmobile.data.github

import dev.chungjungsoo.gptmobile.data.agent.AgentProviderSession
import dev.chungjungsoo.gptmobile.data.agent.ProviderEvent
import dev.chungjungsoo.gptmobile.data.network.NetworkFailureKind
import dev.chungjungsoo.gptmobile.data.network.networkFailureKind
import dev.chungjungsoo.gptmobile.data.network.providerExceptionDetails
import dev.chungjungsoo.gptmobile.data.network.sanitizedCauseTypes
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
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
    class Failure(val code: String, val providerFailure: ProviderEvent.Failed? = null, val repairAllowed: Boolean = false) : IllegalStateException(code)

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
        var oversized = false
        var unexpectedOperation = false
        var finishReason: String? = null
        var failure: ProviderEvent.Failed? = null
        var repairProhibited = false
        try {
            attempt.awaitingTerminal = true
            attempt.terminalFailure = null
            openVerifier(request.payload).streamRound(emptyList(), emptyList()).collect { event ->
                when (event) {
                    is ProviderEvent.TextDelta -> {
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
            failure?.let { throw Failure(it.errorCode ?: "GITHUB_EXTRACTION_FAILED", it) }
            val provider = providerExceptionDetails(error, partialOutput = response.isNotEmpty())
            val transport = networkFailureKind(error)
            val unknown = provider == null && transport == NetworkFailureKind.UNKNOWN
            val code = provider?.code ?: transport.code.takeUnless { transport == NetworkFailureKind.UNKNOWN } ?: "GITHUB_EXTRACTION_FAILED"
            val original = ProviderEvent.Failed(
                provider?.message ?: "Claim extraction did not complete. Saved evidence remains available; inspect the retained provider failure before resuming.",
                errorCode = if (unknown) "PROVIDER_INVOCATION_FAILED" else code,
                failureDetails = JsonObject(
                    provider?.failureDetails.orEmpty() + buildJsonObject {
                        if (provider?.failureDetails?.get("failed_stage") == null) put("failed_stage", "claim_extraction")
                        put("review_stage", "claim_extraction")
                        put("cause_types", JsonArray(sanitizedCauseTypes(error).map(::JsonPrimitive)))
                        if (unknown) {
                            put("retryable", false)
                            put("request_replay_allowed", false)
                            put("logical_continuation_allowed", false)
                            put("remote_acceptance", "UNKNOWN")
                        }
                    }
                )
            )
            throw Failure(code, original)
        }

        val terminalFailure = failure
        val completedLimit = terminalFailure != null && !repairProhibited
        if (terminalFailure != null && !completedLimit) throw Failure(terminalFailure.errorCode ?: "GITHUB_EXTRACTION_FAILED", terminalFailure)
        if (!complete && !completedLimit) throw Failure("GITHUB_EXTRACTION_FAILED", incompleteProviderFailure())
        if (unexpectedOperation) {
            val observed = terminalFailure ?: ProviderEvent.Failed("Evidence formatting attempted an operation.", errorCode = "TOOL_PROTOCOL_INVALID_FRAME", transportComplete = true)
            throw Failure(observed.errorCode ?: "GITHUB_EXTRACTION_FAILED", observed)
        }
        val outputLimited = oversized || completedLimit || finishReason?.lowercase() in setOf("length", "max_tokens", "max_output_tokens", "token_limit")
        if (oversized) throw Failure("GITHUB_EXTRACTION_OUTPUT_LIMIT", terminalFailure, repairAllowed = true)
        return try {
            withContext(computationDispatcher) {
                val answer = Json.parseToJsonElement(response.toString().trim()) as? JsonObject ?: error("Claim extraction was not structured")
                GitHubClaimBatches.validate(answer, request)
            }.also { attempt.terminalFailure = null }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (invalid: Exception) {
            // A length flag alone does not invalidate a complete, schema-valid
            // response. Truncated output still needs a smaller claim batch.
            throw Failure(if (outputLimited) "GITHUB_EXTRACTION_OUTPUT_LIMIT" else "GITHUB_EXTRACTION_INVALID_RESPONSE", terminalFailure, repairAllowed = true)
        }
    }

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

    private fun completedLimitAllowsRepair(failure: ProviderEvent.Failed): Boolean =
        !outcomeUnknown(failure) &&
            failure.failureDetails?.get("retryable") != JsonPrimitive(false) &&
            failure.failureDetails?.get("request_replay_allowed") != JsonPrimitive(false) &&
            failure.failureDetails?.get("logical_continuation_allowed") != JsonPrimitive(false) &&
            failure.failureDetails?.get("automatic_retry_allowed") != JsonPrimitive(false) &&
            (failure.transportComplete || failure.failureDetails?.get("generation_completed") == JsonPrimitive(true)) &&
            failure.errorCode in setOf("OUTPUT_LIMIT", "COMPLETION_REPAIR_BUDGET_EXHAUSTED", "GITHUB_EXTRACTION_OUTPUT_LIMIT")

    private fun outcomeUnknown(failure: ProviderEvent.Failed?): Boolean = failure != null &&
        (
            failure.failureDetails?.get("requires_reconciliation") == JsonPrimitive(true) ||
                failure.failureDetails?.get("remote_acceptance") == JsonPrimitive("UNKNOWN") ||
                failure.errorCode in setOf("GATEWAY_OUTCOME_UNKNOWN", "TOOL_OUTCOME_UNKNOWN", "TIMEOUT_OUTCOME_UNKNOWN", "DISPATCH_OUTCOME_UNKNOWN", "RECONCILIATION_REQUIRED")
            )

    private const val MAX_OUTPUT_CHARACTERS = 32_768
}
