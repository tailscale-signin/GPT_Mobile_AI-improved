package dev.chungjungsoo.gptmobile.data.context

import dev.chungjungsoo.gptmobile.data.research.researchHash
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

@Serializable
data class PromptBudgetComponent(val name: String, val tokens: Long, val serializedBytes: Long)

/** Counts and hashes only. Attribution is an estimate even when a total is tokenizer-measured. */
@Serializable
data class PromptBudgetReceipt(
    val schemaVersion: Int = 1,
    val originalRequestLineage: String,
    val tokenizerIdentity: String,
    val templateIdentity: String,
    val toolSchemaHash: String,
    val measurement: TokenMeasurementKind,
    val componentAttribution: String = "serialized_utf8_proportions_v1",
    val components: List<PromptBudgetComponent>,
    val promptTokens: Long,
    val safetyTokens: Long,
    val toolResultReserveTokens: Long,
    val attachmentReserveTokens: Long,
    val outputAllowanceTokens: Long?,
    val contextTokens: Long?,
    val deliberateCompactions: List<String> = emptyList()
) {
    val dominantComponent: PromptBudgetComponent? get() = components.maxByOrNull { it.tokens }

    fun explain(): String = buildString {
        append("promptTokens=$promptTokens, safetyTokens=$safetyTokens, toolResultReserveTokens=$toolResultReserveTokens, ")
        append("output=${outputAllowanceTokens ?: "unknown"}, context=${contextTokens ?: "unknown"}")
        dominantComponent?.let { append("; largest input component: ${it.name} (${it.tokens} estimated tokens)") }
    }
}

/** Attribute the final serializer envelope without storing its contents or double-counting schemas. */
internal object PromptBudgets {
    fun capture(
        payload: String,
        measurement: RequestMeasurement,
        outputTokens: Long?,
        contextTokens: Long?,
        templateIdentity: String,
        originalRequestLineage: String? = null,
        toolResultReserveTokens: Long = 0,
        attachmentReserveTokens: Long = 0,
        toolSchema: String? = null,
        embeddedToolSchema: String? = null,
        deliberateCompactions: List<String> = emptyList()
    ): PromptBudgetReceipt {
        require(toolResultReserveTokens >= 0 && attachmentReserveTokens in 0..measurement.promptTokens)
        val root = runCatching { Json.parseToJsonElement(payload) as? JsonObject }.getOrNull()
        val parts = linkedMapOf<String, Long>()
        fun part(name: String, value: JsonElement?) {
            if (value == null) return
            parts[name] = Math.addExact(parts[name] ?: 0, value.toString().toByteArray(Charsets.UTF_8).size.toLong())
        }
        val nativeEnvelope = root?.containsKey("system") == true && root.containsKey("input")
        var originalInput: JsonElement? = null
        if (root != null) {
            val system = root["system"] ?: root["systemInstruction"] ?: root["instructions"]
            val systemText = (system as? JsonPrimitive)?.takeIf { it.isString }?.content
            val embeddedOffset = embeddedToolSchema?.takeIf { it.isNotEmpty() }?.let { systemText?.indexOf(it) } ?: -1
            if (embeddedOffset >= 0 && systemText != null && embeddedToolSchema != null) {
                part("system_instructions", JsonPrimitive(systemText.removeRange(embeddedOffset, embeddedOffset + embeddedToolSchema.length)))
                part("tool_schemas", JsonPrimitive(embeddedToolSchema))
            } else {
                part("system_instructions", system)
            }
            part("tool_schemas", root["tools"])
            part("tool_results", root["nativeToolTranscript"])
            part("response_schema", root["response_format"] ?: root["text"] ?: (root["generationConfig"] as? JsonObject)?.get("responseSchema"))
            val messages = (root["messages"] ?: root["contents"]) as? JsonArray
            if (messages != null) {
                val lastUser = messages.indexOfLast { message -> (message as? JsonObject)?.get("role")?.let { (it as? JsonPrimitive)?.content?.lowercase() } == "user" }
                messages.forEachIndexed { index, message ->
                    val role = ((message as? JsonObject)?.get("role") as? JsonPrimitive)?.content?.lowercase()
                    val name = when {
                        role in setOf("system", "developer") -> "system_instructions"
                        role in setOf("tool", "function") -> "tool_results"
                        !nativeEnvelope && index == lastUser -> "current_request"
                        else -> "retained_history"
                    }
                    part(name, message)
                    if (index == lastUser) originalInput = message
                }
            }
            val input = root["input"] ?: root["prompt"]
            if (input != null) {
                part("current_request", input)
                originalInput = input
            }
        }
        val serializedBytes = payload.toByteArray(Charsets.UTF_8).size.toLong()
        val attributedBytes = parts.values.fold(0L, Math::addExact)
        val denominator = maxOf(1L, serializedBytes, attributedBytes)
        parts["serialization_and_template_estimate"] = (denominator - attributedBytes).coerceAtLeast(0)
        val textTokens = measurement.promptTokens - attachmentReserveTokens
        var assigned = 0L
        val components = parts.entries.mapIndexed { index, (name, bytes) ->
            val tokens = if (index == parts.size - 1) textTokens - assigned else Math.multiplyExact(textTokens, bytes) / denominator
            assigned = Math.addExact(assigned, tokens)
            PromptBudgetComponent(name, tokens, bytes)
        }.filter { it.tokens > 0 || it.serializedBytes > 0 }.toMutableList()
        if (attachmentReserveTokens > 0) components += PromptBudgetComponent("attachment_reserve", attachmentReserveTokens, 0)
        val schema = toolSchema ?: root?.get("tools")?.toString().orEmpty()
        return PromptBudgetReceipt(
            originalRequestLineage = originalRequestLineage ?: researchHash(originalInput?.toString() ?: payload),
            tokenizerIdentity = if (measurement.kind == TokenMeasurementKind.SERVING_TOKENIZER) "serving_tokenizer_identity_unreported" else "utf8_bytes_div_3_v1",
            templateIdentity = templateIdentity,
            toolSchemaHash = researchHash(schema),
            measurement = measurement.kind,
            components = components,
            promptTokens = measurement.promptTokens,
            safetyTokens = measurement.uncertaintyTokens,
            toolResultReserveTokens = toolResultReserveTokens,
            attachmentReserveTokens = attachmentReserveTokens,
            outputAllowanceTokens = outputTokens,
            contextTokens = contextTokens,
            deliberateCompactions = deliberateCompactions
        )
    }
}
