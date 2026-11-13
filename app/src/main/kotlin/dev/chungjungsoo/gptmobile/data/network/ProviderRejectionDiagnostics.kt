package dev.chungjungsoo.gptmobile.data.network

import java.security.MessageDigest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Closed categories and domain-separated hashes; response prose and rejected values never leave here. */
internal object ProviderRejectionDiagnostics {
    enum class Contract { CHAT_COMPLETIONS, ANTHROPIC_MESSAGES, GOOGLE_CONTENT, GATEWAY_GENERATION }
    private val parameters = setOf("model", "messages", "max_tokens", "max_completion_tokens", "temperature", "top_p", "stop", "tools", "tool_choice", "stream", "response_format", "reasoning", "thinking", "metadata", "system")

    fun response(status: Int, body: String, contract: Contract): JsonObject {
        val root = body.takeIf { it.length <= 256 * 1024 }?.let { runCatching { Json.parseToJsonElement(it) as? JsonObject }.getOrNull() }
        val error = root?.get("error") as? JsonObject ?: root
        fun text(key: String): String? = (error?.get(key) as? JsonPrimitive)?.takeIf { it.isString }?.content
        val code = text("code")?.takeIf { it.any(Char::isLetter) }
        return typed(status, text("type"), text("param") ?: text("parameter"), contract, code)
    }

    fun typed(status: Int, errorType: String?, parameter: String? = null, contract: Contract, code: String? = null): JsonObject = buildJsonObject {
        put("payload_contract", contract.name)
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
