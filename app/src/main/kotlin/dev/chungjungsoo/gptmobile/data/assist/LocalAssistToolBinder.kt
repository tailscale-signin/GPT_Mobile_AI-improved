package dev.chungjungsoo.gptmobile.data.assist

import dev.chungjungsoo.gptmobile.data.agent.tool.ResolvedAgentTool
import dev.chungjungsoo.gptmobile.data.agent.tool.selectionId
import dev.chungjungsoo.gptmobile.data.database.entity.PlatformV2

/** Inputs are pre-admitted read-only tools and profiles. This component cannot grant access. */
internal object LocalAssistToolBinder {
    suspend fun bind(
        tools: List<ResolvedAgentTool>,
        candidates: List<PlatformV2>,
        enabled: Boolean,
        policy: LocalAssistPolicy,
        goal: String,
        turnId: String,
        eligible: suspend (PlatformV2) -> Boolean,
        profileAllowed: suspend (PlatformV2) -> Boolean,
        generate: suspend (PlatformV2, String, Int) -> String,
        notice: suspend (String) -> Unit,
        stillEnabled: suspend () -> Boolean
    ): List<ResolvedAgentTool> {
        if (!enabled) return tools
        val target = candidates.firstOrNull { it.uid == policy.profileUid || policy.profileUid == null && eligible(it) } ?: return tools
        val assist = AssistCoordinator(goal, { profileAllowed(target) && eligible(target) }, { task -> generate(target, task, if (policy.summarizeRetrievedText || policy.proposeQueries) 1024 else 128) }, notice, policy.validated(), { stillEnabled() && profileAllowed(target) }, turnId)
        return tools.map { tool -> if (tool.shareableReadOnly) tool.copy(tool = assist.bind(tool.tool, tool.selectionId() + target.uid + dev.chungjungsoo.gptmobile.data.benchmark.benchmarkConfigKey(target))) else tool }
    }
}
