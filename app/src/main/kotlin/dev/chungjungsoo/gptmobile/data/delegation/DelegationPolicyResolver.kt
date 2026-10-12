package dev.chungjungsoo.gptmobile.data.delegation

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
    fun resolve(features: AppFeatureSettings, primaryUid: String, chat: ConversationDelegationSettings?): ModelDelegationSettings {
        val defaults = features.delegationFor(primaryUid).normalized()
        return chat?.applyTo(defaults) ?: defaults
    }

    fun reasons(candidate: PlatformV2, config: ModelDelegationSettings, primary: PlatformV2? = null, reviewer: PlatformV2? = null): List<DelegationBlockReason> = buildList {
        if (!candidate.enabled) add(DelegationBlockReason.DISABLED)
        if (candidate.uid == primary?.uid || sameModel(candidate, primary)) add(DelegationBlockReason.PRIMARY_CONFLICT)
        if (sameModel(candidate, reviewer)) add(DelegationBlockReason.REVIEWER_CONFLICT)
        if (candidate.excludesMemory()) add(DelegationBlockReason.PRIVACY_RESTRICTED)
        if (!config.remoteWorkersAllowed() && !candidate.isPrivateDestination()) add(DelegationBlockReason.CLOUD_DENIED)
        if (primary?.compatibleType == ClientType.LITERT_LM && candidate.compatibleType == ClientType.LITERT_LM) add(DelegationBlockReason.LOCAL_RUNTIME_BUSY)
        if (config.approvedWorkerUids != null && candidate.uid !in config.approvedWorkerUids && candidate.uid != config.targetProfileUid) add(DelegationBlockReason.NOT_APPROVED)
    }

    fun sameModel(first: PlatformV2?, second: PlatformV2?): Boolean = first != null &&
        second != null &&
        (first.uid == second.uid || (first.model.isNotBlank() && first.model.trim().equals(second.model.trim(), true)))

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

    fun preview(config: ModelDelegationSettings, profiles: List<PlatformV2>): String {
        val candidates = ranked(profiles, config)
        val selected = if (config.targetProfileUid.isNotBlank()) candidates.find { it.uid == config.targetProfileUid } else candidates.firstOrNull()
        return buildString {
            appendLine("Configuration preview · no requests made")
            appendLine(
                if (!config.enabled) {
                    "Delegation is off."
                } else if (selected == null) {
                    "Blocked: choose an eligible helper."
                } else {
                    "Helper: ${selected.name} · ${selected.model}"
                }
            )
            appendLine("Data: ${if (config.remoteWorkersAllowed()) "Cloud helpers permitted by global policy" else "On device / private server only"}")
            appendLine("Private-server forwarding and readiness require separate verification.")
            appendLine("Input: ${config.maxInputTokensPerDelegate} requested → ${config.effectiveLocalInputTokens()} token estimate ceiling")
            appendLine("Helper calls: ${config.maxLocalModelCalls} requested → ${config.effectiveLocalModelCalls()} ceiling")
            appendLine("Output: ${config.maxOutputTokens} tokens; provider/profile may lower this.")
            appendLine("Preparation: ${config.preparationTimeoutSeconds}s; retries share the deadline.")
            appendLine("Tools: intersection of parent, profile, conversation and current authorization.")
            if (config.processingOwnership == 0 && config.reviewerProfileUid.isBlank()) appendLine("Blocked: delegate-led preparation requires an independent reviewer.")
            append("Changes apply to the next response. Stop and permission revocation remain live.")
        }
    }
}
