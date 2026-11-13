package dev.chungjungsoo.gptmobile.data.agent

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** Retain the reconciled setup outcome without replacing earlier uncertain remote work. */
internal fun coordinatorSetupFailureEvent(
    failure: ProfileFailureRecord,
    error: Throwable,
    partialOutput: Boolean
): ProviderEvent.Failed? {
    val bindingFailure = AgentFailureEvidence.exception(error, partialOutput)
    val details = (error as? Exception)?.let { dev.chungjungsoo.gptmobile.data.network.providerExceptionDetails(it, partialOutput) }
    val code = bindingFailure?.errorCode ?: details?.code ?: failure.category
    // A later failed precheck must not replace an earlier uncertain generation outcome.
    if (failure.category in setOf("GATEWAY_OUTCOME_UNKNOWN", "TOOL_OUTCOME_UNKNOWN") && code != failure.category) return null
    return ProviderEvent.Failed(
        message = failure.category,
        errorCode = code,
        transportComplete = bindingFailure?.transportComplete == true,
        failureDetails = JsonObject(
            (bindingFailure?.failureDetails ?: details?.failureDetails).orEmpty() +
                mapOf("failed_stage" to JsonPrimitive(failure.phase), "remote_acceptance" to JsonPrimitive(failure.remoteAcceptance.name)) +
                listOfNotNull(
                    failure.dispatched?.let { "dispatched" to JsonPrimitive(it) },
                    failure.requestReplayAllowed?.let { "request_replay_allowed" to JsonPrimitive(it) }
                ).toMap()
        )
    )
}
