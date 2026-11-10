package dev.chungjungsoo.gptmobile.presentation.ui.chat

import dev.chungjungsoo.gptmobile.data.agent.ProfileFailureRecord

internal data class GatewayAdmissionPresentation(val title: String, val guidance: String, val canRetryAfterRepair: Boolean)

/** A readiness rejection is setup work. Unknown earlier execution must retain its reconciliation path. */
internal fun gatewayAdmissionPresentation(failure: ProfileFailureRecord?): GatewayAdmissionPresentation? {
    val observed = failure ?: return null
    val (title, guidance) = when (observed.category) {
        "GATEWAY_ENGINEERING_GITHUB_REQUIRED" ->
            "Gateway GitHub setup required" to
                "On the selected gateway computer, enable its GitHub connection and apply the settings. In GitHub chat Settings, use Check gateway readiness before trying again."
        "GATEWAY_ENGINEERING_STORAGE_DISABLED", "GATEWAY_ENGINEERING_STORAGE_UNAVAILABLE" ->
            "Gateway storage needs attention" to
                "Restore durable storage on the selected gateway computer, then check gateway readiness in GitHub chat Settings."
        "GATEWAY_ENGINEERING_WORKER_UNAVAILABLE" ->
            "Gateway worker needs attention" to
                "Start the engineering worker on the selected gateway computer, then check gateway readiness in GitHub chat Settings."
        "GATEWAY_ENGINEERING_DRAINING", "GATEWAY_ENGINEERING_UNAVAILABLE" ->
            "Gateway is not ready" to
                "Check the selected gateway computer and its engineering status. You can check gateway readiness in GitHub chat Settings after it is ready."
        else -> return null
    }
    return GatewayAdmissionPresentation(
        title,
        guidance,
        canRetryAfterRepair = observed.isUnsentSetup
    )
}
