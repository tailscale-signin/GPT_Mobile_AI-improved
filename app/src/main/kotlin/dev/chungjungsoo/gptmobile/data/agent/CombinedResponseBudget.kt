package dev.chungjungsoo.gptmobile.data.agent

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** Continuation is capacity for unfinished requested work; it is never a required number of calls. */
internal object CombinedResponseBudget {
    fun continuationLimit(envelope: String, outputCap: Int?, wordGoal: Int?, combined: CombinedExecutionContext? = null): Int {
        val json = runCatching { Json.parseToJsonElement(envelope) as? JsonObject }.getOrNull()
        val contributions = (json?.get("contributions") as? JsonArray).orEmpty()
        val sharedClaims = (json?.get("claim_inventory") as? JsonArray).orEmpty()
        val sourceWords = if (sharedClaims.isNotEmpty()) {
            sharedClaims.sumOf { ((it as? JsonObject)?.get("text") as? JsonPrimitive)?.content?.let(CombinedWordCounter::count)?.toLong() ?: 0L }
        } else {
            contributions.sumOf { ((it as? JsonObject)?.get("response") as? JsonPrimitive)?.content?.let(CombinedWordCounter::count)?.toLong() ?: 0L }
        }
        val request = ((json?.get("original_request")) as? JsonPrimitive)?.content.orEmpty()
        val policy = runCatching { json?.get("answer_policy")?.let { Json.decodeFromJsonElement(CombinedAnswerPolicy.serializer(), it) } }.getOrNull() ?: CombinedAnswerPolicy.from(request)
        val requestedLength = policy.maximumWords ?: policy.targetWords ?: policy.minimumWords ?: wordGoal
        if (combined?.plan?.renewable == true && requestedLength == null) return Int.MAX_VALUE
        val estimatedOutput = requestedLength?.toDouble()?.times(2.2)
            ?: combined?.plan?.finalOutputTokens?.toDouble()
            ?: ((json?.get("merge_selection_policy") as? JsonObject)?.get("default_visible_output_tokens") as? JsonPrimitive)?.content?.toDoubleOrNull()
            ?: sourceWords.toDouble() * 2.2
        val segment = outputCap?.takeIf { it > 0 } ?: 16_384
        // The shared turn ledger, provider admission and no-progress rules gate each actual continuation.
        // A fixed sixteen-segment application cap must not truncate an explicit large request.
        return (kotlin.math.ceil(estimatedOutput / (segment * 0.85)) - 1).coerceIn(1.0, Int.MAX_VALUE.toDouble()).toInt()
    }
}

internal fun isRecoverableStreamFailure(message: String): Boolean {
    val text = message.lowercase()
    if (listOf("queue_", "free allowance", "rate limit", "429", "401", "403", "max_tokens", "context", "certificate", "handshake", "connection refused").any { it in text }) return false
    return listOf("temporarily overloaded", "overloaded", "503", "502", "504", "connection reset", "reset by peer", "socket closed", "read error:", "timed out", "timeout", "unexpected end", "premature", "stream ended").any { it in text }
}
