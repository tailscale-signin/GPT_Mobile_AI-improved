package dev.chungjungsoo.gptmobile.data.agent.tool

import dev.chungjungsoo.gptmobile.data.diagnostics.AppLogRecorder

internal fun toolExclusionCategory(reason: String): String = when {
    listOf("AUTH", "CONSENT", "PERMISSION").any { it in reason } -> "AUTHORIZATION"
    listOf("BUDGET", "ALLOWANCE", "CONTEXT").any { it in reason } -> "BUDGET"
    listOf("UNAVAILABLE", "UNSUPPORTED", "CAPABILITY", "PROVIDER_SELECTED").any { it in reason } -> "CAPABILITY"
    else -> "CONFIGURATION"
}

/** Report policy omissions separately from schema/context omissions and network failures. */
internal fun List<ResolvedAgentTool>.filterForToolPolicy(reason: String, predicate: (ResolvedAgentTool) -> Boolean): List<ResolvedAgentTool> = filter { tool ->
    predicate(tool).also { included ->
        if (!included) AppLogRecorder.record("ToolCatalog", "TOOL_OMITTED · stage=policy · name=${tool.modelToolName} · reason=$reason · category=${toolExclusionCategory(reason)}")
    }
}
