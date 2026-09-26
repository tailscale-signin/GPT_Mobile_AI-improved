package dev.chungjungsoo.gptmobile.data.network

import dev.chungjungsoo.gptmobile.data.agent.ToolDefinitionsRejectedException

data class ProviderRequestConfig(
    val apiUrl: String,
    val token: String?,
    val anthropicBetaFeatures: Set<String> = emptySet(),
    val extraHeaders: Map<String, String> = emptyMap()
) {
    /**
     * Resolves an API endpoint path against the base apiUrl, normalizing any
     * leading/trailing slashes or whitespace so that custom endpoints (e.g. self-hosted
     * llama.cpp, Ollama, or custom OpenAI-compatible proxies) do not produce invalid
     * double slashes or malformed URLs.
     */
    fun buildEndpoint(subPath: String): String {
        val sanitizedBase = apiUrl.trim().trimEnd('/')
        val sanitizedSubPath = subPath.trim().trimStart('/')
        return if (sanitizedSubPath.isEmpty()) sanitizedBase else "$sanitizedBase/$sanitizedSubPath"
    }
}

internal fun throwIfToolDefinitionsRejected(
    statusCode: Int,
    hasTools: Boolean,
    errorBody: String
) {
    val normalizedError = errorBody.lowercase()
    val explicitlyUnsupported = listOf(
        "does not support tools",
        "doesn't support tools",
        "tools are not supported",
        "tools not supported",
        "tool use is not supported",
        "tool calling is not supported",
        "unsupported parameter: 'tools'",
        "unsupported parameter: \"tools\"",
        "'tools' is not supported",
        "\"tools\" is not supported",
        "unknown field: tools",
        "unrecognized field \"tools\"",
        "unknown name \"function_declarations\"",
        "function calling is not supported"
    ).any(normalizedError::contains)
    val noOpenRouterToolEndpoints = statusCode == 404 &&
        normalizedError.contains("no endpoints found that support tool use")
    if (hasTools && ((statusCode in setOf(400, 404, 422) && explicitlyUnsupported) || noOpenRouterToolEndpoints)) {
        throw ToolDefinitionsRejectedException("HTTP $statusCode rejected tool definitions")
    }
}

/** Keep authentication failures attributable to the AI connection, including after tool rounds. */
internal fun ProviderRequestConfig.readableProviderError(message: String, code: String?): String {
    if (code != "401" && code != "403") return message
    val host = runCatching { java.net.URI(apiUrl.trim()).host }.getOrNull()
    val provider = if (host.equals("openrouter.ai", ignoreCase = true)) "OpenRouter" else "The AI provider"
    return if (code == "401") {
        "$provider could not authenticate the request (HTTP 401). Check the API key on the platform connection used by this AI profile in Settings → Platforms. " +
            "If this key worked recently, retry later or check the provider's service status."
    } else {
        "$provider denied access (HTTP 403). Check the API key permissions and account restrictions for this AI profile's platform connection."
    }
}
