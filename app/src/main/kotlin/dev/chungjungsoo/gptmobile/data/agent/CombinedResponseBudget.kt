package dev.chungjungsoo.gptmobile.data.agent

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** Size recovery from the complete contributor envelope without imposing a shorter answer. */
internal object CombinedResponseBudget {
    fun continuationLimit(envelope: String, outputCap: Int?, wordGoal: Int?): Int {
        val contributions = runCatching { (Json.parseToJsonElement(envelope) as? JsonObject)?.get("contributions") as? JsonArray }.getOrNull().orEmpty()
        val sourceWords = contributions.sumOf { ((it as? JsonObject)?.get("response") as? JsonPrimitive)?.content?.let(LongResponsePolicy::countWords)?.toLong() ?: 0L }
        val request = runCatching { ((Json.parseToJsonElement(envelope) as? JsonObject)?.get("original_request") as? JsonPrimitive)?.content }.getOrNull().orEmpty()
        val policy = CombinedAnswerPolicy.from(request)
        val requestedLength = policy.maximumWords ?: policy.targetWords ?: wordGoal
        val estimatedOutput = (requestedLength?.toLong() ?: sourceWords) * 2.2
        val segment = outputCap?.takeIf { it > 0 } ?: 16_384
        return (kotlin.math.ceil(estimatedOutput / (segment * 0.85)).toInt() - 1).coerceIn(1, 16)
    }
}

internal fun isRecoverableStreamFailure(message: String): Boolean {
    val text = message.lowercase()
    if (listOf("free allowance", "rate limit", "429", "401", "403", "max_tokens", "context", "certificate", "handshake", "connection refused").any { it in text }) return false
    return listOf("temporarily overloaded", "overloaded", "503", "502", "504", "connection reset", "reset by peer", "socket closed", "read error:", "timed out", "timeout", "unexpected end", "premature", "stream ended").any { it in text }
}
