package dev.chungjungsoo.gptmobile.data.network.error

/** Deterministic request/template failures remain terminal even when legacy servers wrap them in HTTP 500. */
internal data class ProviderContractFailure(val code: String, val phase: String, val message: String) {
    companion object {
        private val template = ProviderContractFailure(
            "BACKEND_TEMPLATE_INVALID",
            "backend_template",
            "The selected model rejected the conversation format before generation. Update the gateway/template compatibility and resume using the saved tool results. Repeating the unchanged request will not help."
        )
        private val failures = listOf(
            template,
            ProviderContractFailure("INVALID_TOOL_HISTORY", "tool_continuation", "The gateway rejected the tool conversation history. Preserve each original call ID and its terminal result before resuming."),
            ProviderContractFailure("UNSUPPORTED_INSTRUCTION_CONTENT", "provider_admission", "The gateway cannot represent the instruction content for this model. Correct the request format before resuming."),
            ProviderContractFailure("INVALID_TOOL_OWNERSHIP", "tool_precheck", "The gateway could not verify the executor for this tool batch. Review tool ownership before resuming; no tools may be replayed."),
            ProviderContractFailure("GATEWAY_NEW_STAGE_BOUNDARY_REQUIRED", "gateway_recovery", "The original task and actual continuation request must both remain in context. Restore the saved conversation and continue its unfinished work; no new request was dispatched."),
            ProviderContractFailure("GATEWAY_JOB_IN_PROGRESS", "gateway_recovery", "The saved gateway job is still active. Read its result before continuing; no additional generation was dispatched."),
            ProviderContractFailure("GATEWAY_JOB_UNAVAILABLE", "gateway_recovery", "The saved gateway job is unavailable on this endpoint. Restore access to that exact job before resuming; a replacement request was not dispatched."),
            ProviderContractFailure("GATEWAY_JOB_FAILED", "gateway_recovery", "The saved gateway job failed. Inspect its retained cause and results before starting unfinished work; the request must not be replayed."),
            ProviderContractFailure("CLIENT_TOOL_RESULT_IDENTITY_MISMATCH", "tool_continuation", "A saved tool result does not match its original call. Restore the exact assistant call and terminal result before resuming; no completed operation may be replayed."),
            ProviderContractFailure("COMPLETION_REPAIR_BUDGET_UNAVAILABLE", "provider_generation", "The gateway could not establish a finite repair allowance. Review the output limit before resuming; no additional generation was sent."),
            ProviderContractFailure("CLIENT_TOOL_RESULTS_REQUIRED", "tool_continuation", "The gateway is waiting for the exact pending tool results. Resume the saved job with those results; completed tools must not be run again."),
            ProviderContractFailure("TERMINAL_JOB_NOT_RESUMABLE", "gateway_recovery", "This gateway job has already ended. Read its saved result before starting any unfinished work; the request must not be replayed."),
            ProviderContractFailure("MISSING_REQUIRED_TOOL_CALL", "provider_generation", "The model did not return the required tool call. Review the selected model's tool support before resuming."),
            ProviderContractFailure("COMPLETION_REPAIR_BUDGET_EXHAUSTED", "provider_generation", "The bounded answer repair exhausted its allowance. Saved output and tool results are retained."),
            ProviderContractFailure("COMPLETION_REPAIR_USAGE_UNAVAILABLE", "provider_generation", "The gateway could not measure repair usage and stopped before another attempt. Review the saved result before resuming."),
            ProviderContractFailure("CONTEXT_BUDGET_EXCEEDED", "provider_admission", "The request exceeds the selected route's context allowance. Reduce the prepared context before resuming with saved evidence."),
            ProviderContractFailure("INVALID_V14_REQUEST", "provider_admission", "The gateway rejected the request format. Update the client and gateway compatibility before retrying."),
            ProviderContractFailure("MIXED_TOOL_BATCH", "tool_continuation", "The gateway rejected an incompatible tool batch. Preserve completed tool results and correct the continuation format."),
            ProviderContractFailure("EMPTY_COMPLETION", "delivery", "The provider returned no usable answer. Review generation settings and the saved result before resuming."),
            ProviderContractFailure("UNAUTHORIZED_TOOL_CALL", "tool_precheck", "The model requested a tool outside the permitted tool set. Review the saved tool request before resuming."),
            ProviderContractFailure("INVALID_TOOL_ARGUMENTS", "tool_precheck", "The model returned invalid tool arguments. Correct the request before resuming; completed actions remain recorded.")
        )
        val codes: Set<String> = failures.map { it.code }.toSet()

        fun find(vararg details: String?): ProviderContractFailure? {
            val text = details.filterNotNull().joinToString(" ").lowercase(java.util.Locale.ROOT)
            // Canonical template evidence outranks incidental HTTP status or retry prose.
            if ("backend_template_invalid" in text ||
                "jinja exception" in text ||
                "system message must be at the beginning" in text ||
                "system message must be at the start" in text ||
                "conversation roles must alternate" in text ||
                "roles must alternate user/assistant" in text
            ) {
                return template
            }
            return failures.firstOrNull { it.code.lowercase(java.util.Locale.ROOT) in text }
                ?: failures.firstOrNull { it.code == "MISSING_REQUIRED_TOOL_CALL" && "backend omitted the required tool call" in text }
        }
    }
}
