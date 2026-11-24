package dev.chungjungsoo.gptmobile.data.github

import dev.chungjungsoo.gptmobile.data.context.ConfiguredDispatchAuthority
import dev.chungjungsoo.gptmobile.data.database.entity.PlatformV2
import dev.chungjungsoo.gptmobile.data.delegation.DelegationAdmissionResolver
import dev.chungjungsoo.gptmobile.data.delegation.DelegationFallback
import dev.chungjungsoo.gptmobile.data.delegation.DelegationPolicyResolver
import dev.chungjungsoo.gptmobile.data.delegation.DelegationTaskPlan
import dev.chungjungsoo.gptmobile.data.model.AppFeatureSettings
import dev.chungjungsoo.gptmobile.data.model.ConversationDelegationSettings
import dev.chungjungsoo.gptmobile.data.model.ModelDelegationSettings
import dev.chungjungsoo.gptmobile.data.model.ToolPluginId
import dev.chungjungsoo.gptmobile.data.model.delegationFor
import dev.chungjungsoo.gptmobile.data.model.excludesMemory

/** Named roles work on evidence supplied by the primary. Only the primary owns repository operations. */
internal data class GitHubEngineeringTeam(val worker: PlatformV2, val reviewer: PlatformV2?, val consent: String) {
    fun restrict(policy: ModelDelegationSettings): ModelDelegationSettings = policy.copy(
        approvedWorkerUids = listOf(worker.uid),
        targetProfileUid = worker.uid,
        reviewerProfileUid = reviewer?.uid.orEmpty(),
        reviewerEnabled = reviewer != null,
        fallbackToAnotherProfile = false,
        fallbackPolicy = DelegationFallback.NONE,
        processingOwnership = policy.processingOwnership.coerceAtLeast(1),
        automaticResearch = false,
        researchEnabled = false,
        taskPlan = DelegationTaskPlan(),
        templateRole = null
    )

    companion object {
        fun policy(features: AppFeatureSettings, context: GitHubConversationContext, chatId: Int?, requested: ConversationDelegationSettings? = null): ModelDelegationSettings {
            val saved = features.conversationDelegation[chatId]
            val current = DelegationPolicyResolver.resolve(features, context.mainProfileUid, saved)
            if (requested == null || requested == saved) return current
            val scoped = requested.applyTo(current)
            return if (saved?.targetProfileUid.orEmpty().isNotBlank() && scoped.targetProfileUid != current.targetProfileUid) scoped.copy(enabled = false) else scoped
        }

        fun configured(context: GitHubConversationContext, profiles: List<PlatformV2>, features: AppFeatureSettings, policy: ModelDelegationSettings = features.delegationFor(context.mainProfileUid)): GitHubEngineeringTeam? {
            val uid = context.mainProfileUid
            val options = features.githubProfileOverrides[uid] ?: return null
            if (options.intelligenceLevel != GitHubIntelligenceLevel.MAXIMUM_INTELLIGENCE ||
                !options.githubTools ||
                !features.isToolPluginEnabledForProfile(uid, ToolPluginId.MODEL_DELEGATION)
            ) {
                return null
            }
            val primary = profiles.singleOrNull { it.uid == uid && it.enabled && !it.excludesMemory() } ?: return null
            val approved = policy.normalized()
            if (!approved.enabled || approved.processingOwnership >= 100) return null
            val roles = DelegationAdmissionResolver.resolve(approved, profiles, primary)
            val worker = roles.worker ?: return null
            if (roles.blocked || approved.reviewerEnabled && roles.reviewer == null) return null
            val binding = listOf(GitHubChatDecisions.workspaceKey(context), ConfiguredDispatchAuthority.profileRevision(primary), ConfiguredDispatchAuthority.profileRevision(worker), roles.reviewer?.let(ConfiguredDispatchAuthority::profileRevision).orEmpty())
            return GitHubEngineeringTeam(worker, roles.reviewer, GitHubRepositoryStateStore.digest(binding.joinToString("|")))
        }

        fun admitted(context: GitHubConversationContext, profiles: List<PlatformV2>, features: AppFeatureSettings, policy: ModelDelegationSettings): GitHubEngineeringTeam? =
            configured(context, profiles, features, policy)?.takeIf { it.consent == features.githubProfileOverrides[context.mainProfileUid]?.engineeringTeamConsent }
    }
}
