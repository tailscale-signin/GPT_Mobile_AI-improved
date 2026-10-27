package dev.chungjungsoo.gptmobile.data.provider

import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull

/**
 * Usage is optional telemetry on OpenAI-compatible completions.
 * Never infer that a response body is invalid solely from missing usage.
 * This helper does not validate choices, finish reasons, or message content.
 */
internal object CompletionUsageNormalizer {
    private val objects = setOf("cost_details", "prompt_tokens_details", "completion_tokens_details")

    /** Normalize only optional telemetry; malformed choices and tool calls still fail decoding. */
    fun normalize(response: JsonObject): JsonObject {
        if ("usage" !in response) return response
        val raw = response["usage"] as? JsonObject
        val usage = raw?.let {
            val parsed = parse(response)
            JsonObject(
                raw.filter { (name, value) ->
                    when (name) {
                        "prompt_tokens" -> parsed?.promptTokens != null
                        "completion_tokens" -> parsed?.completionTokens != null
                        "total_tokens" -> parsed?.totalTokens != null
                        in objects -> value is JsonObject || value == JsonNull
                        "cost" -> value is JsonPrimitive
                        else -> true
                    }
                }
            )
        } ?: JsonNull
        return JsonObject(response + ("usage" to usage))
    }

    data class Usage(
        val promptTokens: Int?,
        val completionTokens: Int?,
        val totalTokens: Int?
    )

    fun parse(response: JsonObject): Usage? {
        val raw = response["usage"] as? JsonObject ?: return null

        fun counter(name: String): Int? {
            val primitive = raw[name] as? JsonPrimitive ?: return null
            if (primitive.isString) return null
            return primitive.intOrNull?.takeIf { it >= 0 }
        }

        val prompt = counter("prompt_tokens")
        val completion = counter("completion_tokens")
        val total = counter("total_tokens")
        if (prompt == null && completion == null && total == null) return null
        return Usage(prompt, completion, total)
    }
}
