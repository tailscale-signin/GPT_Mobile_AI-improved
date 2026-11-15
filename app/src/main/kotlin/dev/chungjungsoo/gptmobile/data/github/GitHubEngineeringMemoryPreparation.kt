package dev.chungjungsoo.gptmobile.data.github

import dev.chungjungsoo.gptmobile.data.agent.tool.ResolvedAgentTool

/** Optional history requires a selected native source; a local decision tool grants no memory access. */
internal object GitHubEngineeringMemoryPreparation {
    fun eligible(context: GitHubConversationContext, tools: List<ResolvedAgentTool>): Boolean =
        tools.any { it.nativeGitHub && it.connectionUid != null && it.connectionUid == context.connectionUid }

    suspend fun prepare(
        context: GitHubConversationContext,
        tools: List<ResolvedAgentTool>,
        load: suspend () -> String,
        unavailable: suspend (GitHubEngineeringMemoryAccessRejected) -> Unit
    ): String {
        if (!eligible(context, tools)) return ""
        return try {
            load()
        } catch (rejected: GitHubEngineeringMemoryAccessRejected) {
            unavailable(rejected)
            ""
        }
    }
}
