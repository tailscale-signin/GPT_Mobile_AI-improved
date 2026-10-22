package dev.chungjungsoo.gptmobile.data.agent

/** A partial usage frame is an observation, not a final bill or a token-breakdown proof. */
internal fun measuredUsageSource(source: String, phase: ProviderUsagePhase = ProviderUsagePhase.FINAL): Boolean =
    phase == ProviderUsagePhase.FINAL && source in setOf("provider_reported", "runtime_reported", "gateway_reported", "mixed_reported")

internal fun usageSourceAtPhase(source: String, phase: ProviderUsagePhase): String =
    if (phase == ProviderUsagePhase.FINAL || source in setOf("estimated", "unavailable") || source.endsWith("_interim") || source.endsWith("_initial")) source else "${source}_${phase.name.lowercase()}"

/** Keeps measured final counts authoritative even when an earlier estimate was higher. */
internal class ProviderUsageCounter {
    var value: Long? = null
        private set
    var source: String = "unavailable"
        private set
    var phase: ProviderUsagePhase = ProviderUsagePhase.INITIAL
        private set

    fun accept(tokens: Int?, cumulative: Boolean, source: String, phase: ProviderUsagePhase) {
        if (tokens == null || tokens < 0) return
        val next = tokens.toLong()
        val old = value
        val wasMeasured = measuredUsageSource(this.source, this.phase)
        val nowMeasured = measuredUsageSource(source, phase)
        if (old == null || cumulative && nowMeasured && !wasMeasured) {
            value = next
            this.source = usageSourceAtPhase(source, phase)
            this.phase = phase
        } else if (cumulative) {
            if (wasMeasured && !nowMeasured) return
            value = maxOf(old, next)
            this.source = usageSourceAtPhase(source, phase)
            this.phase = phase
        } else {
            value = (old + next).coerceAtMost(Int.MAX_VALUE.toLong())
            this.source = when {
                wasMeasured && nowMeasured && this.source == source -> source
                wasMeasured && nowMeasured -> "mixed_reported"
                else -> "estimated"
            }
            this.phase = if (wasMeasured && nowMeasured) ProviderUsagePhase.FINAL else ProviderUsagePhase.INTERIM
        }
    }
}
