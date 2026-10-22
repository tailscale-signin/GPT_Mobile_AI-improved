package dev.chungjungsoo.gptmobile.data.agent.tool

import dev.chungjungsoo.gptmobile.data.diagnostics.AppLogRecorder

/** Report policy omissions separately from schema/context omissions and network failures. */
internal fun List<ResolvedAgentTool>.filterForToolPolicy(reason: String, predicate: (ResolvedAgentTool) -> Boolean): List<ResolvedAgentTool> = filter { tool ->
    predicate(tool).also { included ->
        if (!included) AppLogRecorder.record("ToolCatalog", "TOOL_OMITTED · stage=authorization · name=${tool.modelToolName} · reason=$reason")
    }
}
