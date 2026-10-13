package dev.chungjungsoo.gptmobile.data.agent

import kotlinx.serialization.Serializable

@Serializable
data class ProgressDeadlineSettings(
    val firstProgressMillis: Long? = null,
    val meaningfulIdleMillis: Long? = null,
    val queueMillis: Long? = null,
    val loadMillis: Long? = null,
    val roundDeadlineMillis: Long? = null,
    val adaptiveFirstProgress: Boolean = true
) {
    init {
        require(listOfNotNull(firstProgressMillis, meaningfulIdleMillis, queueMillis, loadMillis, roundDeadlineMillis).all { it > 0 })
    }
}

@Serializable
enum class ObservedProgressStage { WAITING_FOR_MODEL, QUEUED, LOADING, PREFILL, REASONING, ANSWER, TOOL }

@Serializable
data class ProgressDeadlineOutcome(
    val deadline: String,
    val stage: ObservedProgressStage,
    val elapsedMillis: Long,
    val lastProgressAgeMillis: Long,
    val visibleOutputObserved: Boolean,
    val completedToolTransitions: Int,
    val savedWorkStatus: String = "PERSISTENCE_UNVERIFIED",
    val cleanup: String = "CANCELLATION_REQUESTED"
)

/** Bounded observations are keyed by the admitted runtime, role, policy and prompt-size bucket. */
internal class AdaptiveProgressHistory {
    private val observations = linkedMapOf<String, ArrayDeque<Long>>()

    @Synchronized
    fun record(key: String, firstProgressMillis: Long) {
        if (firstProgressMillis <= 0) return
        val samples = observations.getOrPut(key) { ArrayDeque() }
        samples.addLast(firstProgressMillis)
        while (samples.size > 8) samples.removeFirst()
        while (observations.size > 128) observations.remove(observations.keys.first())
    }

    @Synchronized
    fun firstProgressMillis(key: String?, fallback: Long, settings: ProgressDeadlineSettings): Long {
        settings.firstProgressMillis?.let { return it }
        if (!settings.adaptiveFirstProgress || key == null) return fallback
        val samples = observations[key] ?: return fallback
        if (samples.size < 3) return fallback
        val observed = samples.max().coerceAtMost(450_000L) * 2
        // Observations may extend the conservative default, never shorten it or create an infinite wait.
        return maxOf(fallback, observed.coerceAtMost(900_000L))
    }

    companion object {
        val shared = AdaptiveProgressHistory()
    }
}
