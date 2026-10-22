package dev.chungjungsoo.gptmobile.data.delegation

import dev.chungjungsoo.gptmobile.data.agent.CombinedExecutionContext
import dev.chungjungsoo.gptmobile.data.database.entity.PlatformV2
import dev.chungjungsoo.gptmobile.data.model.AppFeatureSettings
import dev.chungjungsoo.gptmobile.data.model.ClientType
import dev.chungjungsoo.gptmobile.data.model.ConversationDelegationSettings
import dev.chungjungsoo.gptmobile.data.model.ModelDelegationSettings
import dev.chungjungsoo.gptmobile.data.model.delegationFor
import dev.chungjungsoo.gptmobile.data.model.excludesMemory
import dev.chungjungsoo.gptmobile.data.model.isPrivateDestination
import kotlinx.serialization.Serializable

@Serializable
enum class ScopedPermission { INHERIT, ALLOW, DENY }

@Serializable
enum class WorkerSelectionMode { INHERIT, AUTOMATIC, FIXED }

@Serializable
enum class DelegationFallback { NONE, ASK, APPROVED_ORDER }

enum class DelegationBlockReason {
    DISABLED,
    PRIMARY_CONFLICT,
    REVIEWER_CONFLICT,
    PRIVACY_RESTRICTED,
    CLOUD_DENIED,
    LOCAL_RUNTIME_BUSY,
    NOT_APPROVED,
    MISSING_PROFILE
}

/** Metadata inspection only: no network, native load, or billable request. */
object DelegationPolicyResolver {
    fun resolve(features: AppFeatureSettings, primaryUid: String, chat: ConversationDelegationSettings?, combined: CombinedExecutionContext? = null): ModelDelegationSettings {
        val defaults = features.delegationFor(primaryUid).normalized()
        return suppressForCombined(chat?.applyTo(defaults) ?: defaults, combined)
    }

    /** Apply after normalization/research selection: normalization can enable automatic research. */
    fun suppressForCombined(config: ModelDelegationSettings, combined: CombinedExecutionContext?): ModelDelegationSettings =
        if (combined == null) config else config.copy(enabled = false, reviewerEnabled = false, automaticResearch = false, processingOwnership = 100)

    fun reasons(candidate: PlatformV2, config: ModelDelegationSettings, primary: PlatformV2? = null, reviewer: PlatformV2? = null): List<DelegationBlockReason> = buildList {
        if (!candidate.enabled) add(DelegationBlockReason.DISABLED)
        if (candidate.uid == primary?.uid || sameModel(candidate, primary)) add(DelegationBlockReason.PRIMARY_CONFLICT)
        if (sameModel(candidate, reviewer)) add(DelegationBlockReason.REVIEWER_CONFLICT)
        if (candidate.excludesMemory()) add(DelegationBlockReason.PRIVACY_RESTRICTED)
        if (!config.remoteWorkersAllowed() && !candidate.isPrivateDestination()) add(DelegationBlockReason.CLOUD_DENIED)
        if (primary?.compatibleType == ClientType.LITERT_LM && candidate.compatibleType == ClientType.LITERT_LM) add(DelegationBlockReason.LOCAL_RUNTIME_BUSY)
        if (config.approvedWorkerUids != null && candidate.uid !in config.approvedWorkerUids && candidate.uid != config.targetProfileUid) add(DelegationBlockReason.NOT_APPROVED)
    }

    /** Exact duplicate deployments share a reserved role even when their model alias is unresolved. */
    fun sameModel(first: PlatformV2?, second: PlatformV2?): Boolean = first != null &&
        second != null &&
        (
            CanonicalModelIdentity.independence(first, second) == ModelIndependence.SAME ||
                CanonicalModelIdentity.resolve(first).deployment == CanonicalModelIdentity.resolve(second).deployment
            )

    fun ranked(profiles: List<PlatformV2>, config: ModelDelegationSettings, primary: PlatformV2? = null, reviewer: PlatformV2? = null): List<PlatformV2> =
        profiles.filter { reasons(it, config, primary, reviewer).isEmpty() }.sortedWith(
            compareBy<PlatformV2> { config.approvedWorkerUids?.indexOf(it.uid)?.takeIf { index -> index >= 0 } ?: Int.MAX_VALUE }
                .thenBy {
                    if (it.compatibleType == ClientType.LITERT_LM) {
                        0
                    } else if (it.isPrivateDestination()) {
                        1
                    } else {
                        2
                    }
                }
                .thenBy { it.uid }
        )

    fun preview(config: ModelDelegationSettings, profiles: List<PlatformV2>, primary: PlatformV2? = null): String {
        val policy = config.normalized()
        val admission = primary?.let { DelegationAdmissionResolver.resolve(policy, profiles, it) }
        val candidates = ranked(profiles, policy, primary)
        val selected = if (primary != null) {
            admission?.worker
        } else if (policy.targetProfileUid.isNotBlank()) {
            candidates.find { it.uid == policy.targetProfileUid }
        } else {
            candidates.firstOrNull()
        }
        return buildString {
            appendLine("Configuration preview · no requests made")
            if (primary == null) {
                appendLine("Primary not selected here; role conflicts and independence must be checked for the actual chat.")
            } else {
                appendLine(admission?.explanation)
            }
            appendLine(
                if (!policy.enabled) {
                    "Delegation is off."
                } else if (selected == null) {
                    "Blocked: choose an eligible helper."
                } else {
                    "Helper: ${selected.name} · ${selected.model}"
                }
            )
            appendLine("Data: ${if (policy.remoteWorkersAllowed()) "Cloud helpers permitted by the resolved policy" else "On device / private server only"}")
            appendLine("Private-server forwarding and readiness require separate verification.")
            appendLine("Input: ${policy.maxInputTokensPerDelegate} requested → ${policy.effectiveLocalInputTokens()} token estimate ceiling")
            appendLine("Logical research operations: ${policy.maxCallsPerTurn} requested → ${policy.effectiveResearchCalls()} ceiling")
            appendLine("Physical model sessions: ${policy.maxLocalModelCalls} requested → ${policy.effectiveLocalModelCalls()} ceiling")
            val research = dev.chungjungsoo.gptmobile.data.model.effectiveResearchPlan(policy.deepResearch)
            appendLine("Research depth: ${policy.deepResearch.effort}; effective source goal: ${research.sourceGoal}; legacy delegation depth: ${policy.strategy}. Source collection does not imply question coverage.")
            appendLine("Output: ${policy.maxOutputTokens} tokens; provider/profile may lower this.")
            appendLine("Preparation: ${policy.preparationTimeoutSeconds}s; retries share the deadline.")
            appendLine("Tools: intersection of parent, profile, conversation and current authorization.")
            if (policy.reviewMode == DelegationReviewMode.REQUIRED && policy.reviewerProfileUid.isBlank()) appendLine("Required independent review pauses preparation until an eligible reviewer is selected.")
            append("Changes apply to the next response. Stop and permission revocation remain live.")
        }
    }
}
