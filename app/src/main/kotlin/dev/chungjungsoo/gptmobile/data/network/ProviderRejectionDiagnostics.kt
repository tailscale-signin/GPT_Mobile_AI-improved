package dev.chungjungsoo.gptmobile.data.network

import dev.chungjungsoo.gptmobile.data.openrouter.account.OpenRouterIdentity
import java.security.MessageDigest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Closed categories and domain-separated hashes; response prose and rejected values never leave here. */
internal object ProviderRejectionDiagnostics {
    enum class Contract { CHAT_COMPLETIONS, OPENAI_RESPONSES, ANTHROPIC_MESSAGES, GOOGLE_CONTENT, GATEWAY_GENERATION }
    private val parameters = setOf("model", "messages", "max_tokens", "max_completion_tokens", "temperature", "top_p", "stop", "tools", "tool_choice", "stream", "response_format", "reasoning", "thinking", "metadata", "system", "input", "max_output_tokens", "previous_response_id", "instructions")

    fun isOpenRouterChatNotFound(status: Int, endpoint: String?): Boolean =
        status == 404 && OpenRouterIdentity.endpoints.any { endpoint == "$it/chat/completions" }

    fun openRouterNotFoundMessage(fields: JsonObject, providerDetail: String? = null): String = when ((fields["provider_rejection_category"] as? JsonPrimitive)?.content) {
        "MODEL_UNAVAILABLE" -> "OpenRouter could not serve the selected model (HTTP 404). Check the model ID and availability in this profile."
        "PERMISSION" -> "POLICY_RESTRICTED: OpenRouter found no endpoint compatible with this profile’s data policy (HTTP 404). Choose a model or provider compatible with that policy."
        "UNAVAILABLE" -> "OpenRouter found no matching provider endpoint (HTTP 404). Check availability and the routing requirements for this model."
        else -> "OpenRouter returned HTTP 404 for chat generation. Check this profile’s model ID and provider routing." +
            providerDetail?.takeUnless { it == "HTTP 404" || it.isBlank() }?.let { " Provider detail: $it" }.orEmpty()
    }

    fun response(status: Int, body: String, contract: Contract, endpoint: String? = null): JsonObject {
        val root = body.takeIf { it.length <= 256 * 1024 }?.let { runCatching { Json.parseToJsonElement(it) as? JsonObject }.getOrNull() }
        val error = root?.get("error") as? JsonObject ?: root
        // OpenRouter can wrap an upstream error object in metadata.raw. Project only typed fields.
        val routerNotFound = contract == Contract.CHAT_COMPLETIONS && isOpenRouterChatNotFound(status, endpoint)
        val nested = if ((status == 400 || routerNotFound) && contract == Contract.CHAT_COMPLETIONS) {
            when (val raw = (error?.get("metadata") as? JsonObject)?.get("raw")) {
                is JsonObject -> raw
                is JsonPrimitive -> raw.takeIf { it.isString }?.let { runCatching { Json.parseToJsonElement(it.content) as? JsonObject }.getOrNull() }
                else -> null
            }?.let { it["error"] as? JsonObject ?: it }
        } else {
            null
        }
        fun text(key: String): String? = ((nested?.get(key) as? JsonPrimitive)?.takeIf { it.isString } ?: (error?.get(key) as? JsonPrimitive)?.takeIf { it.isString })?.content
        val code = text("code")?.takeIf { it.any(Char::isLetter) }
        val billing = status == 402 ||
            status == 400 &&
            contract == Contract.ANTHROPIC_MESSAGES &&
            text("type") == "invalid_request_error" &&
            text("message")?.startsWith("Your credit balance is too low to access the Anthropic API", ignoreCase = true) == true
        val fields = typed(status, text("type"), text("param") ?: text("parameter"), contract, code)
        val schemaRejected = status == 400 &&
            contract == Contract.CHAT_COMPLETIONS &&
            (
                code in setOf("invalid_json_schema", "invalid_response_format") ||
                    fields["provider_parameter"] == JsonPrimitive("response_format") ||
                    text("message")?.startsWith("Invalid schema for response_format", ignoreCase = true) == true
                )
        val missingModel = status == 404 && (code == "model_not_found" || text("type") == "model_not_found")
        // The router owns this message. An upstream error can separately identify a missing model.
        val routerMessage = (error?.get("message") as? JsonPrimitive)?.takeIf { routerNotFound && it.isString }?.content.orEmpty()
        val policyMismatch = routerMessage.startsWith("No endpoints found matching your data policy", ignoreCase = true)
        val noMatchingEndpoint = routerMessage.startsWith("No endpoints found for ", ignoreCase = true) ||
            routerMessage.startsWith("No endpoints found that support ", ignoreCase = true)
        val configurationRequired = policyMismatch || billing || missingModel || status == 401 || status == 410 || schemaRejected
        if (!configurationRequired && status != 404 && status != 429) return fields
        return JsonObject(
            fields + buildJsonObject {
                when {
                    billing -> put("provider_rejection_category", "BILLING_UNAVAILABLE")
                    policyMismatch -> put("provider_rejection_category", "PERMISSION")
                    noMatchingEndpoint && fields["provider_rejection_category"] == JsonPrimitive("NOT_FOUND") -> put("provider_rejection_category", "UNAVAILABLE")
                }
                if (configurationRequired) {
                    put("retryable", false)
                    put("recovery_action", "RETRY_AFTER_CONFIGURATION_CHANGE")
                }
                // Hash the route identity without credentials, query parameters or fragments.
                val route = endpoint?.let { runCatching { java.net.URI(it) }.getOrNull() }
                if (route != null && route.scheme in setOf("https", "http") && route.host != null) {
                    put("provider_route_hash", hash("provider.route.v1", "${route.scheme}://${route.host}:${route.port}${route.rawPath.orEmpty()}"))
                }
            }
        )
    }

    fun typed(status: Int, errorType: String?, parameter: String? = null, contract: Contract, code: String? = null): JsonObject = buildJsonObject {
        put("payload_contract", contract.name)
        if (code == "tool_use_failed" || errorType == "tool_use_failed") put("provider_tool_rejection", "TOOL_USE_FAILED")
        put(
            "provider_rejection_category",
            when (status) {
                400, 422 -> "INVALID_REQUEST"
                401 -> "AUTHENTICATION"
                403 -> "PERMISSION"
                404 -> if (listOf(errorType, code).any { it in setOf("model_not_found", "model_unavailable") }) "MODEL_UNAVAILABLE" else "NOT_FOUND"
                410 -> "GONE"
                413 -> "PAYLOAD_SIZE"
                429 -> "RATE_LIMIT"
                in 500..599 -> "UNAVAILABLE"
                else -> when (errorType) {
                    "invalid_request_error" -> "INVALID_REQUEST"
                    "authentication_error" -> "AUTHENTICATION"
                    "permission_error" -> "PERMISSION"
                    "not_found_error" -> "NOT_FOUND"
                    "rate_limit_error" -> "RATE_LIMIT"
                    "request_too_large" -> "PAYLOAD_SIZE"
                    "overloaded_error", "api_error" -> "UNAVAILABLE"
                    "model_not_found", "model_unavailable" -> "MODEL_UNAVAILABLE"
                    else -> "UNKNOWN"
                }
            }
        )
        (errorType ?: code)?.takeIf { it.isNotBlank() && it.length <= 128 }?.let { put("provider_error_type_hash", hash("provider.error-type.v1", it)) }
        parameter?.takeIf { it.isNotBlank() && it.length <= 256 }?.let {
            put("provider_parameter_path_hash", hash("provider.parameter-path.v1", it))
            val root = it.removePrefix("$.").substringBefore('.').substringBefore('[')
            if (root in parameters) put("provider_parameter", root)
        }
    }

    private fun hash(domain: String, value: String): String = MessageDigest.getInstance("SHA-256")
        .digest("$domain\u0000$value".toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it.toInt() and 0xff) }
}
