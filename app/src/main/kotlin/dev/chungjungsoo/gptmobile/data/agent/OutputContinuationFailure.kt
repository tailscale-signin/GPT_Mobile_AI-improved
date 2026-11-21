package dev.chungjungsoo.gptmobile.data.agent

import dev.chungjungsoo.gptmobile.data.diagnostics.IssueReportIncidentCapsule
import dev.chungjungsoo.gptmobile.data.network.NetworkFailureKind
import dev.chungjungsoo.gptmobile.data.network.networkFailureKind
import dev.chungjungsoo.gptmobile.data.network.providerExceptionDetails
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put

/** Preserve the cause of a failed dependent request without authorizing replay of earlier work. */
internal object OutputContinuationFailure {
    enum class Cause { NO_SAVED_TASK, CAPACITY_REJECTED, HTTP_REJECTED, TRANSPORT_FAILURE, OPEN_FAILED }

    fun unavailable(cause: Cause, savedCharacters: Int = 0): ProviderEvent.Failed = ProviderEvent.Failed(
        if (cause == Cause.NO_SAVED_TASK) {
            "No saved task is available to continue. Send the original request to begin the work."
        } else {
            "The continuation could not start. The saved answer and completed tool results remain available."
        },
        errorCode = "OUTPUT_CONTINUATION_UNAVAILABLE",
        taskOutcome = if (savedCharacters > 0) "PARTIAL" else "BLOCKED",
        failureDetails = buildJsonObject {
            put("failed_stage", if (cause == Cause.NO_SAVED_TASK) "planning" else "provider_admission")
            put("continuation_cause", cause.name)
            put("continuation_saved_characters", savedCharacters.coerceAtLeast(0))
            put("partial_output_preserved", savedCharacters > 0)
            put("retryable", false)
            put("request_replay_allowed", false)
            put("logical_continuation_allowed", false)
            put("next_action", if (cause == Cause.NO_SAVED_TASK) "SEND_ORIGINAL_REQUEST" else "INSPECT_CONTINUATION_FAILURE")
            if (cause == Cause.NO_SAVED_TASK) {
                put("failure_origin", "APPLICATION")
                put("dispatched", false)
                put("dispatch_state", "PRECHECK_REJECTED")
                put("remote_acceptance", "NO")
                put("requires_reconciliation", false)
            }
        }
    )

    fun opening(error: Exception, trigger: ContinuationTrigger, savedCharacters: Int, continuationCount: Int, outputCap: Int?): ProviderEvent.Failed {
        val causes = generateSequence<Throwable>(error) { it.cause?.takeUnless { cause -> cause === it } }.take(16).toList()
        causes.filterIsInstance<kotlinx.coroutines.CancellationException>().firstOrNull()?.let { throw it }
        causes.firstOrNull {
            it is dev.chungjungsoo.gptmobile.data.context.DispatchPolicyChangedException ||
                it is dev.chungjungsoo.gptmobile.data.context.ProviderWaitAuthorityChanged ||
                it is SavedEvidenceAccessDenied
        }?.let { throw it }
        causes.filterIsInstance<ProviderRecoveryFailure>().firstOrNull()?.let {
            return annotate(it.event(), savedCharacters, continuationCount, outputCap, trigger)
        }
        val observed = causes.filterIsInstance<Exception>().firstNotNullOfOrNull { providerExceptionDetails(it, partialOutput = savedCharacters > 0) }
        val http = (observed?.failureDetails?.get("http_status") as? JsonPrimitive)?.intOrNull
        val cause = when {
            causes.any { it is dev.chungjungsoo.gptmobile.data.context.RequestCapacityException } -> Cause.CAPACITY_REJECTED
            http != null -> Cause.HTTP_REJECTED
            networkFailureKind(error) != NetworkFailureKind.UNKNOWN -> Cause.TRANSPORT_FAILURE
            else -> Cause.OPEN_FAILED
        }
        val fallback = unavailable(cause, savedCharacters)
        return annotate(
            fallback.copy(
                errorCode = observed?.code ?: fallback.errorCode,
                failureDetails = JsonObject(
                    fallback.failureDetails.orEmpty() + observed?.failureDetails.orEmpty() +
                        IssueReportIncidentCapsule.exceptionFields(error, IssueReportIncidentCapsule.ExceptionCaptureSite.OUTPUT_CONTINUATION_OPEN_CATCH) +
                        buildJsonObject {
                            put("continuation_cause", cause.name)
                            put("retryable", false)
                            put("request_replay_allowed", false)
                            put("logical_continuation_allowed", false)
                        }
                )
            ),
            savedCharacters,
            continuationCount,
            outputCap,
            trigger
        )
    }

    fun annotate(failure: ProviderEvent.Failed, savedCharacters: Int, continuationCount: Int, outputCap: Int?, trigger: ContinuationTrigger? = null): ProviderEvent.Failed {
        val status = (failure.failureDetails?.get("http_status") as? JsonPrimitive)?.intOrNull
        return failure.copy(
            failureDetails = JsonObject(
                failure.failureDetails.orEmpty() + buildJsonObject {
                    put("continuation_saved_characters", savedCharacters.coerceAtLeast(0))
                    put("continuation_attempt_count", continuationCount.coerceAtLeast(0))
                    trigger?.let { put("continuation_trigger", it.name) }
                    outputCap?.takeIf { it > 0 }?.let { put("continuation_output_cap", it) }
                    if (status == 413) {
                        put("continuation_cause", Cause.HTTP_REJECTED.name)
                        put("next_action", "REPLAN_REQUEST_BUDGET")
                        put("retryable", false)
                        put("request_replay_allowed", false)
                        put("logical_continuation_allowed", false)
                    }
                }
            )
        )
    }
}
