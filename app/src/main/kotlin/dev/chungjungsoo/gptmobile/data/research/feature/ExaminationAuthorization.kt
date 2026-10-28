package dev.chungjungsoo.gptmobile.data.research.feature

import dev.chungjungsoo.gptmobile.data.database.entity.PlatformV2
import dev.chungjungsoo.gptmobile.data.model.AppFeatureSettings
import dev.chungjungsoo.gptmobile.data.model.ChatMcpToolConfig
import dev.chungjungsoo.gptmobile.data.model.ToolPluginId
import dev.chungjungsoo.gptmobile.data.model.delegationFor
import dev.chungjungsoo.gptmobile.data.research.researchHash

/** Named permission facts, separate from formatting, output capacity and display preferences. */
internal fun examinationAuthorization(features: AppFeatureSettings, profile: PlatformV2, tools: ChatMcpToolConfig, chatId: Int): Map<String, String> {
    val options = features.delegationFor(profile.uid).deepResearch
    val selection = features.conversationResearch[chatId] ?: tools.research
    val toolNames = listOf("deep_research", "deep_research_get_paper", "deep_research_read_paper", "research_examine_article", "read_url")
    return sortedMapOf(
        "research_enabled" to features.researchFeature.enabled.toString(),
        "profile_enabled" to profile.enabled.toString(),
        "network_tools" to (!profile.disableAllTools && !profile.disableRemoteTools).toString(),
        "research_plugin" to features.isToolPluginEnabledForProfile(profile.uid, ToolPluginId.DEEP_RESEARCH).toString(),
        "papers_consent" to (options.enabled && options.academicSources).toString(),
        "destination" to researchHash("${profile.uid}|${profile.compatibleType}|${profile.apiUrl}|${profile.model}|${profile.token}|${profile.secretRef}|${profile.providerConnectionUid}"),
        "tool_grants" to toolNames.joinToString { "$it=${tools.isToolEnabled(it)}" },
        "source_domains" to "${options.includeDomains.trim()}|${options.excludeDomains.trim()}",
        "source_scope" to (selection?.scope ?: options.contentScope).name,
        "source_providers" to selection?.enabledProviders.orEmpty().sorted().joinToString(),
        "source_gateway" to selection?.gatewayConnectionUid.orEmpty(),
        "reviewer" to "${features.researchFeature.reviewerProfileUid}|${features.delegationFor(profile.uid).reviewerProfileUid}|${features.delegationFor(profile.uid).reviewerEnabled}|${features.delegationFor(profile.uid).reviewMode}",
        "remote_connections" to (selection?.gatewayConnectionUid == null || features.remoteMcpConnections).toString()
    )
}

internal fun examinationAuthorizationChange(accepted: Map<String, String>, current: Map<String, String>): String? =
    (accepted.keys + current.keys).sorted().firstOrNull { accepted[it] != current[it] }

internal fun blocksUnchangedExaminationRetry(failure: ExaminationFailure?): Boolean =
    failure != null && !failure.retryable && failure.code in setOf("OUTPUT_INVALID", "OUTPUT_EMPTY", "REASONING_ONLY", "SOURCE_TEXT_EMPTY", "NO_GROUNDED_OUTCOME", "GROUNDING_INVALID", "ADMITTED_CAPACITY_EXHAUSTED", "EXAMINER_OUTPUT_CAPACITY")
