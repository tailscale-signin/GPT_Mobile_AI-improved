package dev.chungjungsoo.gptmobile.data.agent

import dev.chungjungsoo.gptmobile.data.agent.tool.GitHubOperationTool
import dev.chungjungsoo.gptmobile.data.agent.tool.ResolvedAgentTool
import dev.chungjungsoo.gptmobile.data.diagnostics.AppLogRecorder

/** Exact requested operations are requirements; the relevance-ranked catalog is not. */
internal fun explicitlyRequiredToolNames(task: String, knownNames: Set<String> = emptySet()): Set<String> {
    if (Regex("(?i)^\\s*(?:please\\s+)?(?:how\\b|what\\b|explain\\b|describe\\b)").containsMatchIn(task)) return emptySet()
    return Regex("(?i)\\b(?:call|invoke|execute|use)\\s+(?:the\\s+)?(tool\\s+)?`?([a-z][a-z0-9_]{2,95})`?")
        .findAll(task).filterNot { match ->
            Regex("(?i)(?:do not|don't|never|avoid|cannot|can't)\\s+$").containsMatchIn(task.substring(maxOf(0, match.range.first - 32), match.range.first))
        }.filter { match ->
            val name = match.groupValues[2]
            match.groupValues[1].isNotBlank() || name in knownNames || GitHubOperationTool.operationName(name) != null
        }.map { it.groupValues[2] }.toSet()
}

internal fun requiredToolSchemas(requiredNames: Set<String>, tools: List<ResolvedAgentTool>): List<AgentToolDefinition> = requiredNames.map { name ->
    val candidates = tools.filter { tool ->
        tool.modelToolName == name || "__" !in name && GitHubOperationTool.operationName(tool.modelToolName) == name
    }
    val callable = candidates.filter { it.tool.definition.modelVisible }
    val reason = when {
        callable.isEmpty() -> "CAPABILITY_UNAVAILABLE"
        callable.size > 1 -> "AMBIGUOUS_CONFIGURATION"
        name.substringBefore("__") in setOf("github_write", "github_commit", "github_stage_patch", "github_release") && callable.single().nativeGitHub && !callable.single().nativeGitHubAuthenticated -> "AUTHORIZATION_REQUIRED"
        else -> null
    }
    if (reason != null) {
        AppLogRecorder.record("ToolCatalog", "REQUIRED_TOOL_BLOCKED · name=$name · reason=$reason · dispatched=false", "W")
        val guidance = when (reason) {
            "AUTHORIZATION_REQUIRED" -> "Connect the selected GitHub account before requesting repository changes."
            "AMBIGUOUS_CONFIGURATION" -> "Select the exact connection-qualified tool name."
            else -> "Enable or reconnect this tool for the selected profile."
        }
        throw IllegalArgumentException("REQUIRED_TOOL_$reason: $name is not ready. $guidance No model or tool request was dispatched.")
    }
    callable.single().tool.definition
}.distinctBy { it.name }
