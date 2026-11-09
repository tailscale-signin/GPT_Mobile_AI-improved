package dev.chungjungsoo.gptmobile.data.agent.provider

import dev.chungjungsoo.gptmobile.data.agent.GitHubToolContextBudgetException
import dev.chungjungsoo.gptmobile.data.agent.ProviderEvent
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** A later admission rejection cannot establish that earlier generation or tool work was unsent. */
internal fun localToolContextFailure(error: IllegalArgumentException, generationDispatched: Boolean): ProviderEvent.Failed {
    if (error !is GitHubToolContextBudgetException) {
        return ProviderEvent.Failed(error.message ?: "Local context is too small for this request.")
    }
    val failure = error.providerFailure()
    return failure.copy(
        message = if (generationDispatched) {
            "GITHUB_TOOL_CONTEXT_BUDGET: The full authorized GitHub catalog needs approximately ${error.requiredSchemaTokens} schema tokens, but ${error.availableSchemaTokens} remain. This session already attempted generation; preserve prior output and check tool outcomes before continuing with a larger context."
        } else {
            failure.message
        },
        failureDetails = JsonObject(
            failure.failureDetails.orEmpty() - "dispatched" + buildJsonObject {
                put("generation_dispatched", generationDispatched)
                if (generationDispatched) {
                    put("remote_acceptance", "UNKNOWN")
                    put("dispatch_state", "UNKNOWN")
                } else {
                    put("dispatched", false)
                    put("remote_acceptance", "NO")
                    put("dispatch_state", "PRECHECK_REJECTED")
                }
            }
        )
    )
}
