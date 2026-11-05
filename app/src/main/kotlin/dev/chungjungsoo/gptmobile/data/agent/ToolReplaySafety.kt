package dev.chungjungsoo.gptmobile.data.agent

import dev.chungjungsoo.gptmobile.data.permissions.GitHubAuthorizationBoundary
import dev.chungjungsoo.gptmobile.data.research.ResearchTimeouts
import kotlinx.serialization.json.JsonObject

/** Local resolver approval is separate from semantic capabilities advertised by a remote tool. */
interface ApprovedReadOnlyAgentTool {
    val approvedReadOnly: Boolean
}

internal object ToolReplaySafety {
    fun isReplayableRead(name: String, arguments: JsonObject, tool: AgentTool? = null): Boolean =
        name == ProgressiveToolCatalog.NAME ||
            GitHubAuthorizationBoundary.isReplayableRead(name, arguments) ||
            ResearchTimeouts.isAcademicRead(name) ||
            (tool as? ApprovedReadOnlyAgentTool)?.approvedReadOnly == true
}
