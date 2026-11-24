package dev.chungjungsoo.gptmobile.data.github

import dev.chungjungsoo.gptmobile.data.context.ConfiguredDispatchAuthority
import dev.chungjungsoo.gptmobile.data.database.entity.PlatformV2
import dev.chungjungsoo.gptmobile.data.delegation.DelegationFallback
import dev.chungjungsoo.gptmobile.data.delegation.DelegationPolicyResolver
import dev.chungjungsoo.gptmobile.data.model.ClientType
import dev.chungjungsoo.gptmobile.data.model.ModelDelegationSettings
import dev.chungjungsoo.gptmobile.data.model.excludesMemory
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** Binds separate advice and read-selection grants to the named profiles and workspace. */
internal object GitHubRecoveryConsult {
    const val ROLE = "github-recovery-consult"

    fun consent(context: GitHubConversationContext, primary: PlatformV2, helper: PlatformV2): String =
        GitHubRepositoryStateStore.digest(listOf(GitHubChatDecisions.workspaceKey(context), ConfiguredDispatchAuthority.profileRevision(primary), ConfiguredDispatchAuthority.profileRevision(helper)).joinToString("|"))

    fun unresolvedCriterion(report: JsonObject?, acceptedRequest: String): String? {
        val row = GitHubAcceptanceWork.outstanding(report).firstOrNull { !GitHubAcceptanceWork.deferred(it) } ?: return null
        val criterion = (row["criterion"] ?: row["requirement"]) as? JsonPrimitive
        return criterion?.content?.takeIf { it.isNotBlank() && it in acceptedRequest }?.take(240)
            ?: GitHubAcceptanceWork.step(row)?.action
    }

    fun target(
        preferences: GitHubChatProfileOverride,
        context: GitHubConversationContext,
        primary: PlatformV2,
        profiles: List<PlatformV2>,
        policy: ModelDelegationSettings
    ): PlatformV2? = preferences.recoveryConsultConsent?.let { candidates(preferences, context, primary, profiles, policy)[it] }

    fun candidates(
        preferences: GitHubChatProfileOverride,
        context: GitHubConversationContext,
        primary: PlatformV2,
        profiles: List<PlatformV2>,
        policy: ModelDelegationSettings,
        execution: Boolean = false
    ): Map<String, PlatformV2> {
        if (!preferences.githubTools || preferences.intelligenceLevel != GitHubIntelligenceLevel.MAXIMUM_INTELLIGENCE || primary.uid != context.mainProfileUid || !policy.enabled || policy.processingOwnership >= 100) return emptyMap()
        if (policy.fallbackPolicy in setOf(DelegationFallback.NONE, DelegationFallback.ASK) || policy.fallbackPolicy == null && !policy.fallbackToAnotherProfile) return emptyMap()
        return profiles.filter { helper ->
            (helper.compatibleType == ClientType.LITERT_LM || execution && policy.remoteWorkersAllowed()) &&
                (!execution || !helper.disableAllTools && !helper.disableRemoteTools && !helper.excludesMemory() && !primary.excludesMemory() && helper.model.isNotBlank() && !helper.model.endsWith(":batch")) &&
                helper.uid != primary.uid &&
                (policy.selectionMode != dev.chungjungsoo.gptmobile.data.delegation.WorkerSelectionMode.FIXED || helper.uid == policy.targetProfileUid) &&
                (helper.uid in policy.approvedWorkerUids.orEmpty() || helper.uid == policy.targetProfileUid) &&
                DelegationPolicyResolver.reasons(helper, policy, primary).isEmpty()
        }.groupBy {
            val key = consent(context, primary, it)
            if (execution) GitHubRepositoryStateStore.digest("read-recovery-v1|$key") else key
        }.mapNotNull { (key, matches) -> matches.singleOrNull()?.let { key to it } }.toMap()
    }
}
