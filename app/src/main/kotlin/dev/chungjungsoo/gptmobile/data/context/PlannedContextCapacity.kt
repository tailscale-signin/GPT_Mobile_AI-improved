package dev.chungjungsoo.gptmobile.data.context

/** The ceiling used to select this exact prompt travels with it into every execution stage. */
data class PlannedContextCapacity(val tokens: Int, val provenance: CapacityProvenance) {
    init {
        require(tokens > 0)
    }

    /** A smaller current route observation remains authoritative; a larger one cannot widen this plan. */
    fun retainProvenance(snapshot: CapacitySnapshot, configuredCeiling: Int?): CapacitySnapshot =
        if (configuredCeiling == tokens && snapshot.contextTokens == tokens.toLong()) snapshot.copy(provenance = provenance) else snapshot

    fun applyTo(settings: TokenBudgetSettings): TokenBudgetSettings {
        val configured = settings.normalized()
        require(configured.contextTokens >= tokens) {
            "CONTEXT_PLAN_CAPACITY_CHANGED: The prepared prompt must be replanned against the reduced context ceiling before dispatch."
        }
        return configured.copy(contextTokens = tokens)
    }
}
