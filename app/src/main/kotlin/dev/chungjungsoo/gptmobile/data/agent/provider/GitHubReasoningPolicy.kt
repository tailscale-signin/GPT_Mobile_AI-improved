package dev.chungjungsoo.gptmobile.data.agent.provider

import dev.chungjungsoo.gptmobile.data.agent.AgentToolDefinition
import dev.chungjungsoo.gptmobile.data.agent.AgentToolExchange
import dev.chungjungsoo.gptmobile.data.agent.ToolResultContent
import dev.chungjungsoo.gptmobile.data.context.ReasoningMode
import dev.chungjungsoo.gptmobile.data.github.githubOperationFamily
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** Request-scoped effort; stored profile settings and provider output ceilings are unchanged. */
internal object GitHubReasoningPolicy {
    fun requestedOpenAiEffort(model: String, constraints: RequestConstraints): String {
        val ceiling = openAiEffort(model)
        val effort = constraints.gitHubEffort
        return when {
            ceiling != null && effort == dev.chungjungsoo.gptmobile.data.github.GitHubReasoningEffort.MAXIMUM -> ceiling
            effort != null && supportsOpenAiMechanicalEffort(model) -> effort.wire
            effort != null && ceiling != null -> ceiling
            constraints.maximizeReasoning -> ceiling ?: "high"
            else -> "medium"
        }
    }

    fun supportsOpenAiMechanicalEffort(model: String): Boolean {
        val name = model.lowercase().substringAfterLast('/')
        // High-effort support alone does not establish a low-effort control on
        // specialized Pro or deep-research variants.
        return openAiEffort(model) != null && "-pro" !in name && "-deep-research" !in name
    }

    /** Lower effort only while native source recovery still has an exact unread local page. */
    fun mechanicalContinuation(constraints: RequestConstraints, tools: List<AgentToolDefinition>, exchanges: List<AgentToolExchange>): Boolean {
        if (!constraints.maximizeReasoning ||
            !constraints.allowReasoning ||
            !constraints.allowTools ||
            tools.isEmpty() ||
            constraints.reasoningModeOverride !in setOf(null, ReasoningMode.AUTO) ||
            (constraints.requestRole ?: "primary") !in setOf("primary", "github-output-continuation")
        ) {
            return false
        }
        val last = exchanges.lastOrNull() ?: return false
        if (last.calls.isEmpty() || last.calls.size != last.results.size) return false
        return last.calls.all { call ->
            if (call.name.githubOperationFamily() !in setOf("github_read", "github_search", "github_context") || call.arguments.keys != setOf("view_cursor")) return@all false
            if (tools.none { it.modelVisible && it.name == call.name }) return@all false
            val current = (call.arguments["view_cursor"] as? JsonPrimitive)?.takeIf { it.isString }?.content?.split(':') ?: return@all false
            val result = last.results.singleOrNull { it.callId == call.callId }?.takeUnless { it.isError || it.executionReceipt?.get("requires_reconciliation") == JsonPrimitive(true) } ?: return@all false
            val page = (result.content as? ToolResultContent.Json)?.value as? JsonObject ?: return@all false
            if (page["local_page_complete"] != JsonPrimitive(false)) return@all false
            val recipe = page["continuation_call"] as? JsonObject ?: return@all false
            val args = recipe["arguments"] as? JsonObject ?: return@all false
            val next = (args["view_cursor"] as? JsonPrimitive)?.takeIf { it.isString }?.content?.split(':') ?: return@all false
            recipe["tool"] == JsonPrimitive(call.name) &&
                args.keys == setOf("view_cursor") &&
                current.size == 3 &&
                next.size == 3 &&
                current[0] == next[0] &&
                current[1].toIntOrNull()?.let { offset -> next[1].toIntOrNull()?.let { it > offset } } == true
        }
    }

    fun gatewayHeaders(existing: Map<String, String>, engineering: Boolean, outputTokens: Int?): Map<String, String> {
        if (!engineering) return existing
        val controls = setOf("x-gateway-performance-profile", "x-gateway-reasoning-effort", "x-gateway-intermediate-max-tokens")
        return existing.filterKeys { it.lowercase() !in controls } + buildMap {
            put("X-Gateway-Performance-Profile", "quality")
            put("X-Gateway-Reasoning-Effort", "high")
            outputTokens?.takeIf { it > 0 }?.let { put("X-Gateway-Intermediate-Max-Tokens", it.toString()) }
        }
    }

    fun openAiEffort(model: String): String? {
        val name = model.lowercase().substringAfterLast('/')
        return when {
            "-chat" in name -> null
            Regex("^gpt-5\\.[2-9](?:[.-]|$)").containsMatchIn(name) || name.startsWith("gpt-5.1-codex-max") -> "xhigh"
            name.startsWith("gpt-5") || Regex("^o[134](?:-|$)").containsMatchIn(name) || name.startsWith("gpt-oss") -> "high"
            else -> null
        }
    }

    fun outputBudget(contextTokens: Int, configured: Int, profile: Int?): Int =
        if (configured > 0) configured else minOf(16_384, (contextTokens / 3).coerceAtLeast(1), profile?.takeIf { it > 0 } ?: Int.MAX_VALUE)

    fun automaticVisibleDelivery(constraints: RequestConstraints): Boolean =
        constraints.allowReasoning &&
            constraints.reasoningModeOverride in setOf(null, ReasoningMode.AUTO) &&
            constraints.gitHubIntelligenceLevel !in setOf(dev.chungjungsoo.gptmobile.data.github.GitHubIntelligenceLevel.DEEP_ENGINEERING, dev.chungjungsoo.gptmobile.data.github.GitHubIntelligenceLevel.MAXIMUM_INTELLIGENCE) &&
            constraints.gitHubEffort != dev.chungjungsoo.gptmobile.data.github.GitHubReasoningEffort.MAXIMUM &&
            constraints.requestRole in setOf(dev.chungjungsoo.gptmobile.data.github.GitHubStageBudget.VISIBLE_OUTPUT_REPAIR_ROLE, "github-final-summary")

    /** Reserve the most room for visible text that this route explicitly supports. */
    fun visibleOutputEffort(constraints: RequestConstraints, advertised: dev.chungjungsoo.gptmobile.data.openrouter.OpenRouterCatalogLimits.Limits?): String? {
        if (!automaticVisibleDelivery(constraints) || advertised?.reasoningControlAdvertised != true) return null
        if (advertised.reasoningMandatory == false && "none" in advertised.reasoningEfforts) return "none"
        return listOf("minimal", "low").firstOrNull { it in advertised.reasoningEfforts }
    }

    fun manualThinkingBudget(outputTokens: Int, role: String? = null): Int {
        val visibleReserve = maxOf(
            minOf(2048, outputTokens / 2),
            dev.chungjungsoo.gptmobile.data.github.GitHubStageBudget.visibleOutputReserve(outputTokens, role)
        )
        return (outputTokens - visibleReserve).coerceAtLeast(1024)
    }
}
