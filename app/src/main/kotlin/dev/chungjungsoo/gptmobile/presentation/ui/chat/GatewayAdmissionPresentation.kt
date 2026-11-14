package dev.chungjungsoo.gptmobile.presentation.ui.chat

import dev.chungjungsoo.gptmobile.data.agent.ProfileFailureRecord

internal data class GatewayAdmissionPresentation(val title: String, val guidance: String, val canRetryAfterRepair: Boolean)

/** A readiness rejection is setup work. Unknown earlier execution must retain its reconciliation path. */
internal fun gatewayAdmissionPresentation(failure: ProfileFailureRecord?): GatewayAdmissionPresentation? {
    val observed = failure ?: return null
    val (title, guidance) = when (observed.category) {
        "VALIDATION_POLICY_REQUIRED" ->
            "Validation policy required" to
                "Configure the validation policy for this repository on the selected gateway computer, then check gateway readiness before trying again."
        "VALIDATION_CHECKS_REQUIRED", "VALIDATION_CHECK_UNSUPPORTED" ->
            "Validation checks need configuration" to
                "Map the required CI checks to supported validation commands on the selected gateway computer, then check gateway readiness."
        "ACCEPTANCE_CONTRACT_REQUIRED", "ACCEPTANCE_PRODUCER_REQUIRED" ->
            "Acceptance requirements need configuration" to
                "Define the repository acceptance requirements and their evidence mapping on the selected gateway computer, then check gateway readiness."
        "SOURCE_REVISION_REQUIRED", "SOURCE_REVISION_CHANGED" ->
            "Source revision needs refreshing" to
                "Refresh the repository revision and review the current source before checking gateway readiness again."
        "VALIDATION_REVISION_UNAVAILABLE" ->
            "Validation source is unavailable" to
                "Make the pinned source revision available in the gateway mirror, then check gateway readiness."
        "VALIDATION_RUNTIME_UNAVAILABLE" ->
            "Validation runtime is unavailable" to
                "Provision the required validation runtime on the selected gateway computer, then check gateway readiness."
        "ENGINEERING_REPOSITORY_ACCESS_REQUIRED" ->
            "Repository access needs attention" to
                "Restore access to this repository on the selected gateway computer, then check gateway readiness."
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
