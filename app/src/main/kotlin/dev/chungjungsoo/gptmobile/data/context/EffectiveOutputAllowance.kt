package dev.chungjungsoo.gptmobile.data.context

/** A zero/absent app limit is a policy, not a valid physical generation allowance. */
internal object EffectiveOutputAllowance {
    const val DEFAULT_TOKENS = 4096L

    data class Decision(val tokens: Long, val source: String)

    fun resolve(requested: Long?, providerCeiling: Long?, contextCeiling: Long?, measurement: RequestMeasurement): Decision {
        val explicit = requested?.takeIf { it > 0 }
        var tokens = explicit ?: DEFAULT_TOKENS
        var source = if (explicit == null) "adaptive_default" else "explicit_request"
        providerCeiling?.takeIf { it > 0 }?.let {
            if (it < tokens) {
                tokens = it
                source += "+provider_ceiling"
            }
        }
        contextCeiling?.takeIf { it > 0 }?.let {
            val prompt = Math.addExact(measurement.promptTokens, measurement.uncertaintyTokens)
            check(prompt < it) { "CONTEXT_CAPACITY_EXCEEDED: no output headroom remains; input was retained" }
            val headroom = it - prompt
            if (headroom < tokens) {
                tokens = headroom
                source += "+context_headroom"
            }
        }
        return Decision(tokens, source)
    }
}
