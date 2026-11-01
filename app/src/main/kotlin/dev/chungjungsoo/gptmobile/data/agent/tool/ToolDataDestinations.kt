package dev.chungjungsoo.gptmobile.data.agent.tool

import dev.chungjungsoo.gptmobile.data.agent.AgentToolCapability
import dev.chungjungsoo.gptmobile.data.database.entity.PlatformV2
import dev.chungjungsoo.gptmobile.data.permissions.PersonalDataContext
import kotlinx.coroutines.currentCoroutineContext

/** External data transfer follows the resolved implementation, never its display name. */
internal fun ResolvedAgentTool.externalServiceId(): String? {
    val control = realToolName in setOf("delegate_to_model", "read_recovery_context", "discover_tools")
    val external = !control &&
        (
            connectionUid != null ||
                nativeGitHub ||
                nativeMarketplace ||
                tool.definition.capabilities.any { it in setOf(AgentToolCapability.WEB_SEARCH, AgentToolCapability.PAGE_READER) } ||
                realToolName in setOf("web_search", "read_url", "news", "deep_research_search_papers", "deep_research_get_paper", "deep_research_read_paper", "deep_research_citations")
            )
    return if (external) connectionUid?.let { "$it:$realToolName" } ?: realToolName else null
}

/** Recheck after engineering memory hydration, using the same destinations as execution. */
internal suspend fun validateGitHubToolDestinations(profile: PlatformV2, tools: List<ResolvedAgentTool>) {
    val context = currentCoroutineContext()[PersonalDataContext] ?: return
    tools.filter { it.isGitHubTool() }.forEach { tool ->
        tool.externalServiceId()?.let { context.validateExternalTool(profile, it) }
    }
}
