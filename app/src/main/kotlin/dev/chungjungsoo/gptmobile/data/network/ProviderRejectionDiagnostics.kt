package dev.chungjungsoo.gptmobile.data.network

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

    fun response(status: Int, body: String, contract: Contract, endpoint: String? = null): JsonObject {
        val root = body.takeIf { it.length <= 256 * 1024 }?.let { runCatching { Json.parseToJsonElement(it) as? JsonObject }.getOrNull() }
        val error = root?.get("error") as? JsonObject ?: root
        // OpenRouter can wrap an upstream error object in metadata.raw. Project only typed fields.
        val nested = if (status == 400 && contract == Contract.CHAT_COMPLETIONS) {
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
        if (!billing && status != 410 && !schemaRejected) return fields
        return JsonObject(
            fields + buildJsonObject {
                if (billing) put("provider_rejection_category", "BILLING_UNAVAILABLE")
                put("retryable", false)
                put("recovery_action", "RETRY_AFTER_CONFIGURATION_CHANGE")
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
