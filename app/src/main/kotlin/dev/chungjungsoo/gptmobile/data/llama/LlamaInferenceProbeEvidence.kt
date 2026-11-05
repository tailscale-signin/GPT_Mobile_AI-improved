package dev.chungjungsoo.gptmobile.data.llama

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull

/** A status/usage envelope is not a completed inference stream, and another model cannot satisfy the probe. */
internal class LlamaInferenceProbeEvidence(private val expectedModel: String) {
    var completed = false
    var invalid = false
        private set
    var hasPayload = false
        private set
    private var generatedTokens = false
    val ready: Boolean get() = completed && !invalid && (hasPayload || generatedTokens)

    fun observe(raw: String) {
        val body = runCatching { Json.parseToJsonElement(raw) as? JsonObject }.getOrNull()
        if (body == null) {
            invalid = true
            return
        }
        val model = (body["model"] as? JsonPrimitive)?.contentOrNull
        if (body["error"]?.let { it != JsonNull } == true || model != null && model != expectedModel) invalid = true
        generatedTokens = generatedTokens || (((body["usage"] as? JsonObject)?.get("completion_tokens") as? JsonPrimitive)?.longOrNull ?: 0) > 0
        (body["choices"] as? JsonArray).orEmpty().forEach { element ->
            val choice = element as? JsonObject ?: return@forEach
            val message = choice["delta"] as? JsonObject ?: choice["message"] as? JsonObject
            hasPayload = hasPayload ||
                listOf("content", "reasoning_content", "reasoning").any { field ->
                    (message?.get(field) as? JsonPrimitive)?.contentOrNull?.isNotBlank() == true
                }
            val toolCalls = message?.get("tool_calls")
            if (toolCalls != null && toolCalls != JsonNull && (toolCalls !is JsonArray || toolCalls.isNotEmpty())) invalid = true
            if (message?.get("function_call")?.let { it != JsonNull } == true) invalid = true
            val finish = (choice["finish_reason"] as? JsonPrimitive)?.contentOrNull
            if (finish != null) completed = true
            if (finish in setOf("tool_calls", "function_call", "error", "content_filter")) invalid = true
        }
    }
}
