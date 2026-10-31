package dev.chungjungsoo.gptmobile.data.agent

/** Version of the local invocation/receipt contract, independent of an upstream API version. */
data class AgentContractVersion(val major: Int = 1, val minor: Int = 0) {
    init {
        require(major > 0 && minor >= 0) { "Contract versions require a positive major and nonnegative minor." }
    }

    // Minor revisions are additive. A different major needs an explicit adapter.
    val supported: Boolean get() = major == 1
    override fun toString(): String = "$major.$minor"
}

/** Shared by direct, budgeted, delegated and provider-owned tool boundaries. */
internal object AgentContractValidation {
    fun admission(definition: AgentToolDefinition, callId: String): AgentToolResult? = when {
        !definition.contractVersion.supported -> ToolResultEnvelope.error(callId, "UNSUPPORTED_TOOL_CONTRACT", "This tool requires a different invocation contract major version. Update its adapter before execution.")
        else -> null
    }

    fun receipt(callId: String, result: AgentToolResult): AgentToolResult = when {
        result.callId != callId -> ToolResultEnvelope.error(callId, "TOOL_RESULT_ID_MISMATCH", "The returned receipt belongs to another call. This action may have executed; reconcile its external state before retrying.", dispatched = true)
        result.errorCode in ToolResultEnvelope.unknownOutcomeCodes && !result.isError -> result.copy(isError = true)
        else -> result
    }

    /** Refuse malformed replay before it crosses a provider boundary; never invent a missing receipt. */
    fun exchangeError(exchanges: List<AgentToolExchange>): String? {
        for (exchange in exchanges) {
            val calls = exchange.calls.map { it.callId }
            val results = exchange.results.map { it.callId }
            if (calls.any(String::isBlank) || calls.distinct().size != calls.size || results.distinct().size != results.size || calls.toSet() != results.toSet()) {
                return "Tool exchange identities are missing, duplicated or unmatched. Restore the original receipts before continuing."
            }
        }
        return null
    }
}
