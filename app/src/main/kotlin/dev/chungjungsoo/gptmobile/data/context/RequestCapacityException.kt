package dev.chungjungsoo.gptmobile.data.context

/** A local request budget rejection, before the prepared request can be dispatched. */
internal class RequestCapacityException(
    val code: String,
    message: String,
    val promptTokens: Long,
    val safetyTokens: Long,
    val outputTokens: Long?,
    val capacityTokens: Long?,
    val measurement: TokenMeasurementKind,
    val budget: PromptBudgetReceipt? = null,
    val capacityObservation: CapacityAdmissionObservation? = null,
    val planningToolResultReserveTokens: Int? = null,
    val planningInput: PlanningInput? = null,
    val planningObservation: PlanningObservation? = null
) : IllegalStateException("$code: $message${budget?.let { " ${it.explain()}." }.orEmpty()}${capacityGuidance(code, promptTokens, safetyTokens, capacityTokens, measurement, planningToolResultReserveTokens ?: 0, planningInput?.provenance ?: capacityObservation?.capacity?.provenance)}") {
    private val explanation = message
    init {
        require(code in CODES)
    }

    data class PlanningInput(val costs: ContextCostBreakdown, val provenance: CapacityProvenance)

    /** Immutable observations from the owning planner; hashes never contain model or profile text. */
    data class PlanningObservation(
        val profileHash: String?,
        val modelHash: String?,
        val configuredOutputTokens: Int?,
        val capacity: dev.chungjungsoo.gptmobile.data.agent.provider.ProviderRouteCapacity.PrimaryContextDecision,
        val planningContextTokens: Int
    )

    fun withPlanningObservation(observation: PlanningObservation): RequestCapacityException {
        if (planningObservation != null || planningInput == null || planningInput.costs.total.toLong() != promptTokens) return this
        if (observation.planningContextTokens !in 1..observation.capacity.tokens) return this
        val direct = capacityTokens == observation.planningContextTokens.toLong()
        val conservative = planningInput.provenance == CapacityProvenance.CONSERVATIVE_POLICY &&
            capacityTokens == 4096L &&
            observation.planningContextTokens == Int.MAX_VALUE &&
            observation.capacity.tokens == Int.MAX_VALUE
        if (!direct && !conservative) return this
        return RequestCapacityException(
            code, explanation, promptTokens, safetyTokens, outputTokens, capacityTokens, measurement, budget,
            capacityObservation, planningToolResultReserveTokens, planningInput, observation
        ).also {
            it.initCause(this)
            it.stackTrace = stackTrace
        }
    }

    companion object {
        val CODES = setOf("CAPACITY_UNVERIFIED", "INPUT_CAPACITY_EXCEEDED", "OUTPUT_CAPACITY_EXCEEDED", "CONTEXT_CAPACITY_EXCEEDED")
    }
}

private fun capacityGuidance(code: String, promptTokens: Long, safetyTokens: Long, capacityTokens: Long?, measurement: TokenMeasurementKind, toolReserve: Int, provenance: CapacityProvenance?): String {
    if (code != "CONTEXT_CAPACITY_EXCEEDED" || capacityTokens == null || promptTokens < 0 || safetyTokens < 0) return ""
    val input = runCatching { Math.addExact(Math.addExact(promptTokens, safetyTokens), toolReserve.toLong()) }.getOrNull() ?: return ""
    val measured = if (measurement == TokenMeasurementKind.UTF8_ESTIMATE) "Estimated input plus safety" else "Input plus safety"
    val label = if (toolReserve > 0) "$measured and reserved tool results" else measured
    val capacityLabel = if (provenance == CapacityProvenance.CONSERVATIVE_POLICY) "conservative planning allowance" else "context capacity"
    val unverified = if (provenance == CapacityProvenance.CONSERVATIVE_POLICY) " The selected model's context capacity has not been verified; the planning allowance is not a measured model limit." else ""
    return if (input >= capacityTokens) {
        " $label: $input tokens; $capacityLabel: $capacityTokens tokens." + unverified + " " +
            "No output headroom remains, so lowering the output limit alone cannot make this input fit. " +
            "Explicitly reduce input, instructions or optional tools, or choose a route with a larger verified context."
    } else {
        " $label: $input tokens; remaining output headroom: ${capacityTokens - input} tokens." + unverified + " " +
            "Explicitly adjust the output allowance or choose a route with a larger verified context."
    }
}
