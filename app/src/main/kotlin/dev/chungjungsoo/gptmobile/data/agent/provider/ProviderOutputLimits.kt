package dev.chungjungsoo.gptmobile.data.agent.provider

import java.util.concurrent.ConcurrentHashMap

/** Learn only explicit completion ceilings, scoped to endpoint/model/routing, never context guesses. */
internal object ProviderOutputLimits {
    private val ceilings = ConcurrentHashMap<String, Int>()
    private val completionMaximum = Regex("(?i)(?:supports\\s+at\\s+most|maximum\\s+(?:allowed\\s+)?(?:completion\\s+tokens\\s*(?:is|:)?\\s*)?)\\s*([0-9]+)\\s+(?:completion|output)\\s+tokens")

    fun effective(key: String, requested: Int?): Int? {
        val parts = key.split('|', limit = 4)
        val advertised = if (parts.size == 4) dev.chungjungsoo.gptmobile.data.openrouter.OpenRouterCatalogLimits.forRequest(parts[0], parts[1].takeUnless { it == "null" }, parts[2], parts[3])?.output else null
        return listOfNotNull(requested?.takeIf { it > 0 }, ceilings[key], advertised).minOrNull()
    }

    fun learn(key: String, error: String, requested: Int?): Int? {
        if (requested == null || !Regex("(?i)max[_ ](?:completion[_ ])?tokens.*(?:too large|exceed|invalid)").containsMatchIn(error)) return null
        val limit = completionMaximum.find(error)?.groupValues?.get(1)?.toIntOrNull()?.takeIf { it in 1 until requested } ?: return null
        // Bound this metadata cache for long-lived processes with many imported profiles.
        if (ceilings.size >= 256 && !ceilings.containsKey(key)) ceilings.clear()
        ceilings.merge(key, limit, ::minOf)
        return ceilings[key]
    }
}
