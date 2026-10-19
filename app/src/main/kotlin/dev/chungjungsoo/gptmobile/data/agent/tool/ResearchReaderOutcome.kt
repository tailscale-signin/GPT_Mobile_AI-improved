package dev.chungjungsoo.gptmobile.data.agent.tool

import dev.chungjungsoo.gptmobile.data.agent.AgentToolResult
import dev.chungjungsoo.gptmobile.data.agent.ToolOutcome

internal enum class ResearchAccessDisposition { READABLE, CHALLENGE, AUTH_REQUIRED, PREVIEW, EMPTY }

internal fun classifyResearchAccess(text: String): ResearchAccessDisposition {
    val normalized = text.lowercase()
    return when {
        text.isBlank() -> ResearchAccessDisposition.EMPTY
        looksLikeConsentOrScriptShell(text) -> ResearchAccessDisposition.CHALLENGE
        text.length < 2000 && listOf("sign in to access", "log in to access", "authentication required", "please sign in to continue").any(normalized::contains) -> ResearchAccessDisposition.AUTH_REQUIRED
        text.length < 12000 && listOf("preview of subscription content", "purchase this article", "access through your institution", "this is a preview", "abstract only").any(normalized::contains) -> ResearchAccessDisposition.PREVIEW
        else -> ResearchAccessDisposition.READABLE
    }
}

/** A target site's access denial must not disable the provider for other sites. */
internal fun researchReaderOutcome(result: AgentToolResult?, text: String, minimumCharacters: Int = 1): ToolOutcome = when {
    result == null -> ToolOutcome.FAILED
    result.toolCallBudgetExhausted || result.outputBudgetExhausted -> ToolOutcome.BUDGET_EXHAUSTED
    result.isError && researchReaderAuthFailure(result) -> ToolOutcome.AUTH_FAILED
    result.isError && (result.errorCode == "RATE_LIMITED" || Regex("HTTP 429|rate.?limit|too many requests", RegexOption.IGNORE_CASE).containsMatchIn(result.content.researchText())) -> ToolOutcome.RATE_LIMITED
    result.errorCode in setOf("INVALID_RESPONSE", "MALFORMED_RESPONSE", "PARSE_ERROR") -> ToolOutcome.INVALID_RESPONSE
    result.isError && Regex("HTTP 403|denied", RegexOption.IGNORE_CASE).containsMatchIn(result.content.researchText()) -> ToolOutcome.BLOCKED
    result.isError -> ToolOutcome.FAILED
    classifyResearchAccess(text) == ResearchAccessDisposition.AUTH_REQUIRED -> ToolOutcome.AUTH_FAILED
    classifyResearchAccess(text) == ResearchAccessDisposition.CHALLENGE -> ToolOutcome.BLOCKED
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
