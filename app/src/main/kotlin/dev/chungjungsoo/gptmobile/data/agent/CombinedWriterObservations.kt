package dev.chungjungsoo.gptmobile.data.agent

import java.util.concurrent.ConcurrentHashMap

/** Observations expire and are scoped to actual endpoint/model/routing, never a provider-wide promise. */
internal object CombinedWriterObservations {
    data class Observation(val reasoningDisabled: String, val reasoningOnlyAttempts: Int, val updatedAt: Long)
    private val observed = ConcurrentHashMap<String, Observation>()
    fun get(key: String): Observation? = observed[key]?.takeIf { System.currentTimeMillis() - it.updatedAt < 24 * 60 * 60_000L }
    fun record(key: String, reasoning: Boolean, visiblePassages: Int) {
        if (observed.size >= 256 && !observed.containsKey(key)) observed.clear()
        val previous = get(key)
        observed[key] = Observation(
            if (reasoning) {
                "NOT_HONORED"
            } else if (visiblePassages > 0) {
                "OBSERVED_NO_REASONING"
            } else {
                "UNKNOWN"
            },
            if (reasoning && visiblePassages == 0) (previous?.reasoningOnlyAttempts ?: 0) + 1 else 0,
            System.currentTimeMillis()
        )
    }
    fun key(profile: dev.chungjungsoo.gptmobile.data.database.entity.PlatformV2): String = "${profile.compatibleType}|${profile.apiUrl}|${profile.model}|${profile.openRouterRouting.orEmpty()}"
}
