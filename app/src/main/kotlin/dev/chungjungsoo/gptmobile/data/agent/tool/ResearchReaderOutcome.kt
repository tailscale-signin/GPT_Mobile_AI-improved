package dev.chungjungsoo.gptmobile.data.agent.tool

import dev.chungjungsoo.gptmobile.data.agent.AgentToolResult
import dev.chungjungsoo.gptmobile.data.agent.ToolOutcome

/** A target site's access denial must not disable the provider for other sites. */
internal fun researchReaderOutcome(result: AgentToolResult?, text: String, minimumCharacters: Int = 1): ToolOutcome = when {
    result == null -> ToolOutcome.FAILED
    result.toolCallBudgetExhausted || result.outputBudgetExhausted -> ToolOutcome.BUDGET_EXHAUSTED
    result.isError && researchReaderAuthFailure(result) -> ToolOutcome.AUTH_FAILED
    result.isError && (result.errorCode == "RATE_LIMITED" || Regex("HTTP 429|rate.?limit|too many requests", RegexOption.IGNORE_CASE).containsMatchIn(result.content.researchText())) -> ToolOutcome.RATE_LIMITED
    result.errorCode in setOf("INVALID_RESPONSE", "MALFORMED_RESPONSE", "PARSE_ERROR") -> ToolOutcome.INVALID_RESPONSE
    result.isError && Regex("HTTP 403|denied", RegexOption.IGNORE_CASE).containsMatchIn(result.content.researchText()) -> ToolOutcome.BLOCKED
    result.isError -> ToolOutcome.FAILED
    looksLikeConsentOrScriptShell(text) -> ToolOutcome.BLOCKED
    text.length < minimumCharacters -> ToolOutcome.EMPTY
    else -> ToolOutcome.SUCCESS
}

internal fun researchReaderAuthFailure(result: AgentToolResult): Boolean =
    result.errorCode in setOf("AUTH_REQUIRED", "AUTH_FAILED", "UNAUTHORIZED") ||
        Regex("HTTP 401|unauthorized|authentication required|invalid api key", RegexOption.IGNORE_CASE).containsMatchIn(result.content.researchText())

internal fun researchReaderBillingFailure(result: AgentToolResult?): Boolean = result?.let {
    it.errorCode in setOf("BILLING_FAILED", "PAYMENT_REQUIRED", "INSUFFICIENT_CREDITS") ||
        Regex("HTTP 402|payment required|insufficient (credits|balance)|billing quota", RegexOption.IGNORE_CASE).containsMatchIn(it.content.researchText())
} == true
