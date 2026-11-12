package dev.chungjungsoo.gptmobile.data.agent

/** A partial usage frame is an observation, not a final bill or a token-breakdown proof. */
internal fun measuredUsageSource(source: String, phase: ProviderUsagePhase = ProviderUsagePhase.FINAL): Boolean =
    phase == ProviderUsagePhase.FINAL && source in setOf("provider_reported", "runtime_reported", "gateway_reported", "mixed_reported")

internal fun usageSourceAtPhase(source: String, phase: ProviderUsagePhase): String =
    if (phase == ProviderUsagePhase.FINAL || source in setOf("estimated", "unavailable") || source.endsWith("_interim") || source.endsWith("_initial")) source else "${source}_${phase.name.lowercase()}"

/** Separate provenance lanes prevent an estimate from becoming a provider-labelled count. */
internal class ProviderUsageCounter {
    data class Observation(val tokens: Long, val source: String, val phase: ProviderUsagePhase, val cumulative: Boolean)
    private val history = ArrayDeque<Observation>()
    var omittedObservations = 0L
        private set
    val observations: List<Observation> get() = history.toList()
    private val lanes = linkedMapOf<Pair<String, ProviderUsagePhase>, Long>()
    var value: Long? = null
        private set
    var source: String = "unavailable"
        private set
    var phase: ProviderUsagePhase = ProviderUsagePhase.INITIAL
        private set

    fun accept(tokens: Int?, cumulative: Boolean, source: String, phase: ProviderUsagePhase, authoritativeReceipt: Boolean = false) {
        require(!authoritativeReceipt || cumulative && phase == ProviderUsagePhase.FINAL)
        if (tokens == null || tokens < 0) return
        val label = usageSourceAtPhase(source, phase)
        val observation = Observation(tokens.toLong(), label, phase, cumulative)
        if (history.lastOrNull() != observation) {
            if (history.size == 512) {
                history.removeFirst()
                omittedObservations++
            }
            history.addLast(observation)
        }
        val key = label to phase
        lanes[key] = if (cumulative) tokens.toLong() else ((lanes[key] ?: 0L) + tokens).coerceAtMost(Int.MAX_VALUE.toLong())
        fun rank(name: String, at: ProviderUsagePhase): Int = when {
            name in setOf("estimated", "unavailable") -> 0
            measuredUsageSource(name, at) -> 3
            name.endsWith("_reported_partial") -> 2
            at == ProviderUsagePhase.INTERIM -> 2
            else -> 1
        }
        if (authoritativeReceipt || value == null || rank(label, phase) >= rank(this.source, this.phase)) {
            // A corrected provider total may be lower than an estimate or an earlier frame.
            value = lanes.getValue(key)
            this.source = label
            this.phase = phase
        }
    }

    fun evidence(): kotlinx.serialization.json.JsonObject = kotlinx.serialization.json.buildJsonObject {
        put("selected", value?.let { kotlinx.serialization.json.JsonPrimitive(it) } ?: kotlinx.serialization.json.JsonNull)
        put("source", kotlinx.serialization.json.JsonPrimitive(source))
        put("final_confirmed", kotlinx.serialization.json.JsonPrimitive(measuredUsageSource(source, phase)))
        put("omitted_observations", kotlinx.serialization.json.JsonPrimitive(omittedObservations))
        put(
            "observations",
            kotlinx.serialization.json.JsonArray(
                observations.map { item ->
                    kotlinx.serialization.json.buildJsonObject {
                        put("tokens", kotlinx.serialization.json.JsonPrimitive(item.tokens))
                        put("source", kotlinx.serialization.json.JsonPrimitive(item.source))
                        put("phase", kotlinx.serialization.json.JsonPrimitive(item.phase.name))
                        put("cumulative", kotlinx.serialization.json.JsonPrimitive(item.cumulative))
                    }
                }
            )
        )
    }
}
