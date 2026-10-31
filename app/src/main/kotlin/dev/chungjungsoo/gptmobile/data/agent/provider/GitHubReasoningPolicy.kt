package dev.chungjungsoo.gptmobile.data.agent.provider

/** Request-scoped effort; stored profile settings and provider output ceilings are unchanged. */
internal object GitHubReasoningPolicy {
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

    fun manualThinkingBudget(outputTokens: Int): Int = (outputTokens - minOf(2048, outputTokens / 2)).coerceAtLeast(1024)
}
