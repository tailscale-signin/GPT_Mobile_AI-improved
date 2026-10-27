package dev.chungjungsoo.gptmobile.data.provider

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull

/**
 * Provider usage is telemetry, not a prerequisite for accepting a valid completion.
 *
 * Some OpenAI-compatible endpoints omit usage or return null/incomplete counters.
 * Consumers must validate choices/content independently from this optional metadata.
 */
internal object CompletionUsageNormalizer {
    data class Usage(
        val promptTokens: Int?,
        val completionTokens: Int?,
        val totalTokens: Int?,
    )

    fun parse(response: JsonObject): Usage? {
        val raw = response["usage"] as? JsonObject ?: return null
        fun nonNegative(name: String): Int? =
            (raw[name] as? JsonPrimitive)?.intOrNull?.takeIf { it >= 0 }

        val prompt = nonNegative("prompt_tokens")
        val completion = nonNegative("completion_tokens")
        val total = nonNegative("total_tokens")
        if (prompt == null && completion == null && total == null) return null
        return Usage(prompt, completion, total)
    }
}
