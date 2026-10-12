package dev.chungjungsoo.gptmobile.data.agent.provider

import java.util.concurrent.ConcurrentHashMap

/** Learn only explicit completion ceilings, scoped to endpoint/model/routing, never context guesses. */
internal object ProviderOutputLimits {
    private data class Ceiling(val tokens: Int, val learnedAtMs: Long)
    private val ceilings = ConcurrentHashMap<String, Ceiling>()
    private const val TTL_MS = 30 * 60_000L
    private fun clock() = System.nanoTime() / 1_000_000
    private val completionMaximum = Regex("(?i)(?:supports\\s+at\\s+most|maximum\\s+(?:allowed\\s+)?(?:completion\\s+tokens\\s*(?:is|:)?\\s*)?)\\s*([0-9]+)\\s+(?:completion|output)\\s+tokens")

    fun effective(key: String, requested: Int?, nowMs: Long = clock()): Int? {
        val parts = key.split('|', limit = 4)
        val advertised = if (parts.size == 4) dev.chungjungsoo.gptmobile.data.openrouter.OpenRouterCatalogLimits.forRequest(parts[0], parts[1].takeUnless { it == "null" }, parts[2], parts[3])?.output else null
        return listOfNotNull(requested?.takeIf { it > 0 }, ceilings[key]?.takeIf { nowMs - it.learnedAtMs in 0 until TTL_MS }?.tokens, advertised).minOrNull()
    }

    fun learn(key: String, error: String, requested: Int?, nowMs: Long = clock()): Int? {
        if (requested == null || !Regex("(?i)max[_ ](?:completion[_ ])?tokens.*(?:too large|exceed|invalid)").containsMatchIn(error)) return null
        val limit = completionMaximum.find(error)?.groupValues?.get(1)?.toIntOrNull()?.takeIf { it in 1 until requested } ?: return null
        // Bound this metadata cache for long-lived processes with many imported profiles.
        if (ceilings.size >= 256 && !ceilings.containsKey(key)) ceilings.clear()
        ceilings.compute(key) { _, previous ->
            Ceiling(minOf(limit, previous?.takeIf { nowMs - it.learnedAtMs in 0 until TTL_MS }?.tokens ?: limit), nowMs)
        }
        return ceilings[key]?.tokens
    }
}
