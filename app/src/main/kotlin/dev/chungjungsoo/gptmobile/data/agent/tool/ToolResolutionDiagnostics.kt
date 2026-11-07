package dev.chungjungsoo.gptmobile.data.agent.tool

import dev.chungjungsoo.gptmobile.data.agent.ToolCatalogDiagnostics
import dev.chungjungsoo.gptmobile.data.diagnostics.AppLogRecorder

internal fun toolExclusionCategory(reason: String): String = when {
    listOf("AUTH", "CONSENT", "PERMISSION").any { it in reason } -> "AUTHORIZATION"
    listOf("BUDGET", "ALLOWANCE", "CONTEXT").any { it in reason } -> "BUDGET"
    listOf("UNAVAILABLE", "UNSUPPORTED", "CAPABILITY", "PROVIDER_SELECTED").any { it in reason } -> "CAPABILITY"
    else -> "CONFIGURATION"
}

/** Report policy omissions separately from schema/context omissions and network failures. */
internal fun List<ResolvedAgentTool>.filterForToolPolicy(reason: String, predicate: (ResolvedAgentTool) -> Boolean): List<ResolvedAgentTool> {
    val omissions = linkedMapOf<String, String>()
    val category = toolExclusionCategory(reason)
    val filtered = filter { tool ->
        predicate(tool).also { included ->
            if (!included) {
                omissions[tool.modelToolName] = reason
                // Actual authority denials and alias errors keep their individual
                // warning evidence. Ordinary deferral/configuration is summarized.
                if (category == "AUTHORIZATION" || reason == "AMBIGUOUS_MODEL_ALIAS") {
                    AppLogRecorder.record("ToolCatalog", "TOOL_POLICY_BLOCKED · stage=policy · name=${tool.modelToolName} · reason=$reason · category=$category · dispatched=false", "W")
                }
            }
        }
    }
    ToolCatalogDiagnostics.recordOmissions("policy:$reason", omissions, inventoryNames = map { it.modelToolName })
    return filtered
}
