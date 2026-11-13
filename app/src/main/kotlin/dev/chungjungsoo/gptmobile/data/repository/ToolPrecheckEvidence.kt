package dev.chungjungsoo.gptmobile.data.repository

import dev.chungjungsoo.gptmobile.data.agent.AgentToolResult
import kotlinx.serialization.json.JsonPrimitive

/** Public precheck evidence comes from a closed local code, never tool-result prose or arguments. */
internal object ToolPrecheckEvidence {
    private val reasons = setOf(
        "BUDGET_EXHAUSTED", "TOOL_BATCH_OVERSIZE", "DUPLICATE_INVOCATION", "REPEATED_FAILURE",
        "BUDGET_CHECKPOINT_UNAVAILABLE", "UNSUPPORTED_TOOL_CONTRACT", "UNSUPPORTED_OPERATION",
        "UNKNOWN_TOOL", "SAVED_VIEW_WRONG_OPERATION", "SAVED_VIEW_CURSOR_ALTERED"
    )

    fun reasonCode(result: AgentToolResult): String? = result.errorCode?.takeIf {
        result.isError &&
            !result.dispatched &&
            it in reasons &&
            listOf("requires_reconciliation", "requiresReconciliation").none { field -> result.executionReceipt?.get(field) == JsonPrimitive(true) }
    }
}
