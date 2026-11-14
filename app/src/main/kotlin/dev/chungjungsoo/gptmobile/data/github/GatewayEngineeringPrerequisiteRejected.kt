package dev.chungjungsoo.gptmobile.data.github

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** A rejected prerequisite retains its repair action without admitting or replaying work. */
class GatewayEngineeringPrerequisiteRejected internal constructor(
    httpStatus: Int,
    val reasonCode: String,
    stage: String = "admission",
    policyState: String? = null,
    blockerCount: Int = 1,
    commandOutcomeUnknown: Boolean = false
) : GatewayEngineeringRequestRejected(
    httpStatus,
    GatewayEngineeringPrerequisites.message(reasonCode, stage),
    commandOutcomeUnknown,
    reasonCode,
    GatewayEngineeringPrerequisites.stage(stage)
) {
    val recoveryAction: String = requireNotNull(GatewayEngineeringPrerequisites.recoveryAction(reasonCode))
    val validationPolicyState: String? = policyState?.takeIf { it in GatewayEngineeringPrerequisites.policyStates }
    val blockerCount: Int = blockerCount.coerceIn(1, 10_000)
    val diagnosticDetails: JsonObject = buildJsonObject {
        put("recovery_action", recoveryAction)
        put("prerequisite_blocker_count", this@GatewayEngineeringPrerequisiteRejected.blockerCount)
        validationPolicyState?.let { put("validation_policy_state", it) }
    }

    override fun withCommandOutcomeUnknown(unknown: Boolean): GatewayEngineeringPrerequisiteRejected =
        GatewayEngineeringPrerequisiteRejected(httpStatus, reasonCode, requireNotNull(failedStage), validationPolicyState, blockerCount, commandOutcomeUnknown || unknown)
}

/** Server prose, paths and commands never become public recovery instructions. */
internal object GatewayEngineeringPrerequisites {
    private val actions = mapOf(
        "VALIDATION_POLICY_REQUIRED" to "CONFIGURE_VALIDATION_POLICY",
        "VALIDATION_CHECKS_REQUIRED" to "CONFIGURE_CI_CHECK_MAPPING",
        "VALIDATION_CHECK_UNSUPPORTED" to "CONFIGURE_CI_CHECK_MAPPING",
        "ACCEPTANCE_CONTRACT_REQUIRED" to "DEFINE_ACCEPTANCE_REQUIREMENTS",
        "ACCEPTANCE_PRODUCER_REQUIRED" to "CONFIGURE_ACCEPTANCE_MAPPING",
        "SOURCE_REVISION_REQUIRED" to "REFRESH_SOURCE_REVISION",
        "SOURCE_REVISION_CHANGED" to "REFRESH_SOURCE_REVISION",
        "VALIDATION_REVISION_UNAVAILABLE" to "PROVISION_PINNED_MIRROR",
        "VALIDATION_RUNTIME_UNAVAILABLE" to "PROVISION_VALIDATION_RUNTIME",
        "ENGINEERING_REPOSITORY_ACCESS_REQUIRED" to "REFRESH_REPOSITORY_ACCESS"
    )
    val codes: Set<String> = actions.keys
    val recoveryActions: Set<String> = actions.values.toSet()
    val stages: Set<String> = setOf("admission", "provider_admission", "repository_admission", "connection_preflight")
    val policyStates: Set<String> = setOf("NOT_CONFIGURED", "MISSING_OR_OVERSIZED", "UNREADABLE", "INVALID_DOCUMENT", "INVALID_ENTRY", "REPOSITORY_UNCONFIGURED", "VALIDATED")

    fun recoveryAction(code: String?): String? = actions[code]

    fun stage(value: String?): String = value?.takeIf { it in stages } ?: "admission"

    fun message(code: String, stage: String): String {
        val guidance = when (code) {
            "VALIDATION_POLICY_REQUIRED" -> "Configure the repository's host-approved validation policy on the selected gateway before trying again. In the desktop gateway, open Settings → Advanced → Repository validation policy, choose and check the approved file, then save and restart."
            "VALIDATION_CHECKS_REQUIRED", "VALIDATION_CHECK_UNSUPPORTED" -> "Configure the repository's supported CI check mappings on the selected gateway before trying again."
            "ACCEPTANCE_CONTRACT_REQUIRED" -> "Define testable acceptance requirements for this request before trying again."
            "ACCEPTANCE_PRODUCER_REQUIRED" -> "Configure approved evidence producers for the repository's acceptance requirements before trying again."
            "SOURCE_REVISION_REQUIRED", "SOURCE_REVISION_CHANGED" -> "Refresh the repository's exact source revision before trying again."
            "VALIDATION_REVISION_UNAVAILABLE" -> "Provision the exact source revision in the gateway's approved repository mirror before trying again."
            "VALIDATION_RUNTIME_UNAVAILABLE" -> "Restore the validation runtime required by the repository's approved policy before trying again."
            "ENGINEERING_REPOSITORY_ACCESS_REQUIRED" -> "Restore the selected gateway's access to this repository before trying again."
            else -> "Review the repository's engineering prerequisites before trying again."
        }
        return "$guidance ($code; ${stage(stage)}; ${requireNotNull(recoveryAction(code))})"
    }

    fun rejected(status: Int, preflight: GatewayEngineeringPreflight): GatewayEngineeringRequestRejected {
        val blocker = preflight.blockers.firstOrNull()
        return if (blocker != null && blocker.reasonCode in codes) {
            GatewayEngineeringPrerequisiteRejected(status, blocker.reasonCode, blocker.failedStage, preflight.validationPolicyState, preflight.blockers.size)
        } else {
            GatewayEngineeringRequestRejected(status, "The gateway has not verified this request's engineering prerequisites. Review gateway readiness and the saved request before trying again.", errorCode = "REQUEST_REJECTED", failedStage = "provider_admission")
        }
    }
}
