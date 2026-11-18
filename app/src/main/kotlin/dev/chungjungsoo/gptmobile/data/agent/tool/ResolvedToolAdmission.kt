package dev.chungjungsoo.gptmobile.data.agent.tool

import kotlinx.serialization.json.JsonObject

/** Capture the exact call once; approval rechecks invoke the same live predicate. */
internal fun ResolvedAgentTool.executionAdmission(arguments: JsonObject): suspend () -> Boolean = {
    canExecuteArguments?.invoke(arguments) ?: (canReuseResult?.invoke() != false)
}

/** Profile, binding and personal-data restrictions constrain every authority path. */
internal fun ResolvedAgentTool.withAdmissionGate(gate: suspend () -> Boolean): ResolvedAgentTool {
    val previousReuse = canReuseResult
    val previousExecution = canExecuteArguments
    return copy(
        canReuseResult = { previousReuse?.invoke() != false && gate() },
        canExecuteArguments = previousExecution?.let { previous ->
            { arguments -> previous(arguments) && gate() }
        }
    )
}
