package dev.chungjungsoo.gptmobile.data.agent

import dev.chungjungsoo.gptmobile.data.agent.tool.GitHubOperationTool
import dev.chungjungsoo.gptmobile.data.agent.tool.ResolvedAgentTool
import dev.chungjungsoo.gptmobile.data.diagnostics.AppLogRecorder

/** Exact requested operations are requirements; the relevance-ranked catalog is not. */
internal fun explicitlyRequiredToolNames(task: String, knownNames: Set<String> = emptySet()): Set<String> {
    if (Regex("(?i)^\\s*(?:please\\s+)?(?:how\\b|what\\b|explain\\b|describe\\b)").containsMatchIn(task)) return emptySet()
    val requested = Regex("(?i)\\b(?:call|invoke|execute|use)\\s+(?:the\\s+)?(tool\\s+)?`?([a-z][a-z0-9_]{2,95})`?")
        .findAll(task).filterNot { match ->
            Regex("(?i)(?:do not|don't|never|avoid|cannot|can't)\\s+$").containsMatchIn(task.substring(maxOf(0, match.range.first - 32), match.range.first))
        }.filter { match ->
            val name = match.groupValues[2]
            match.groupValues[1].isNotBlank() || name in knownNames || GitHubOperationTool.operationName(name) != null
        }.map { it.groupValues[2] }.toMutableSet()
    // A qualified tool identifier is explicit operational intent even without a
    // prose verb. Underscores must not erase that requirement in word matching.
    Regex("(?i)(?<![a-z0-9_])([a-z][a-z0-9_]{1,63}__[a-z0-9_-]{1,64})(?![a-z0-9_])").findAll(task).filterNot { match ->
        Regex("(?i)(?:do not|don't|never|avoid|cannot|can't)(?:\\s+(?:call|invoke|execute|use)(?:\\s+the)?(?:\\s+tool)?)?\\s+`?$")
            .containsMatchIn(task.substring(maxOf(0, match.range.first - 64), match.range.first))
    }.map { it.groupValues[1] }.filter { it in knownNames || GitHubOperationTool.operationName(it) != null }.forEach(requested::add)
    return requested
}

internal fun requiredToolSchemas(requiredNames: Set<String>, tools: List<ResolvedAgentTool>): List<AgentToolDefinition> = requiredNames.map { name ->
    val candidates = tools.filter { tool ->
        tool.modelToolName == name || "__" !in name && GitHubOperationTool.operationName(tool.modelToolName) == name
    }
    val callable = candidates.filter { it.tool.definition.modelVisible }
    val acquisitionCode = tools.firstNotNullOfOrNull { it.progressiveCatalog?.acquisitionReadinessCode() }
    val reason = when {
        callable.isEmpty() && acquisitionCode != null -> acquisitionCode
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
            "ACQUISITION_PENDING" -> "The authorized catalog has not finished loading; resume catalog acquisition before deciding this tool is unavailable."
            "ACQUISITION_TIMED_OUT" -> "Catalog acquisition timed out before availability was established. Retry the required catalog lookup."
            "ACQUISITION_FAILED" -> "Catalog acquisition failed before availability was established. Inspect its retained failure before retrying."
            else -> "This tool is absent from the selected profile's authorized catalog. Check its tool selection and connection setup."
        }
        throw RequiredToolReadinessFailure(reason, name, guidance)
    }
    callable.single().tool.definition
}.distinctBy { it.name }

/** Admission metadata remains typed through repository and provider error adapters. */
internal class RequiredToolReadinessFailure(val reasonCode: String, val toolName: String, guidance: String) : IllegalArgumentException("REQUIRED_TOOL_$reasonCode: $toolName is not ready. $guidance No model or tool request was dispatched.") {
    val errorCode: String get() = "REQUIRED_TOOL_$reasonCode"
    val failureDetails: kotlinx.serialization.json.JsonObject get() = kotlinx.serialization.json.buildJsonObject {
        put("error_code", kotlinx.serialization.json.JsonPrimitive(errorCode))
        put("failed_stage", kotlinx.serialization.json.JsonPrimitive("tool_readiness"))
        put("tool", kotlinx.serialization.json.JsonPrimitive(toolName))
        put("dispatched", kotlinx.serialization.json.JsonPrimitive(false))
        put("retryable", kotlinx.serialization.json.JsonPrimitive(reasonCode == "ACQUISITION_TIMED_OUT"))
        put("disposition", kotlinx.serialization.json.JsonPrimitive(if (reasonCode.startsWith("ACQUISITION_")) "READINESS_INCOMPLETE" else "BLOCKED"))
    }
}
