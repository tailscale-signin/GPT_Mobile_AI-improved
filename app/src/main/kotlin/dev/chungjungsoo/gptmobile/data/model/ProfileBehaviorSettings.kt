package dev.chungjungsoo.gptmobile.data.model

import kotlinx.serialization.Serializable

/** Optional capabilities are explicit for each profile, including restored legacy profiles. */
@Serializable
data class ProfileBehaviorSettings(
    val localAssistMode: dev.chungjungsoo.gptmobile.data.assist.AssistMode? = null,
    val delegationEnabled: Boolean = false,
    val crawlersEnabled: Boolean = false,
    val crawlerToolIds: Set<String> = emptySet(),
    val maxCrawlPages: Int = 5,
    val toolPluginStates: Map<String, Boolean> = emptyMap(),
    val researchDepthOverride: Int? = null,
    val researchEnabledOverride: Boolean? = null,
    val academicSourcesAllowed: Boolean? = null
) {
    fun normalized() = copy(maxCrawlPages = maxCrawlPages.coerceIn(1, 20))
}

fun AppFeatureSettings.delegationFor(profileUid: String): ModelDelegationSettings =
    delegation.copy(
        enabled = delegation.enabled && profileBehavior[profileUid]?.delegationEnabled == true,
        deepResearch = delegation.deepResearch.copy(
            enabled = profileBehavior[profileUid]?.researchEnabledOverride ?: delegation.deepResearch.enabled,
            academicSources = profileBehavior[profileUid]?.academicSourcesAllowed ?: delegation.deepResearch.academicSources
        )
    ).let { value -> profileBehavior[profileUid]?.researchDepthOverride?.let(value::withStrategy) ?: value }
