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
    val planningInput: PlanningInput? = null
) : IllegalStateException("$code: $message${budget?.let { " ${it.explain()}." }.orEmpty()}${capacityGuidance(code, promptTokens, safetyTokens, capacityTokens, measurement, planningToolResultReserveTokens ?: 0)}") {
    init {
        require(code in CODES)
    }

    data class PlanningInput(val costs: ContextCostBreakdown, val provenance: CapacityProvenance)

    companion object {
        val CODES = setOf("CAPACITY_UNVERIFIED", "INPUT_CAPACITY_EXCEEDED", "OUTPUT_CAPACITY_EXCEEDED", "CONTEXT_CAPACITY_EXCEEDED")
    }
}

private fun capacityGuidance(code: String, promptTokens: Long, safetyTokens: Long, capacityTokens: Long?, measurement: TokenMeasurementKind, toolReserve: Int): String {
    if (code != "CONTEXT_CAPACITY_EXCEEDED" || capacityTokens == null || promptTokens < 0 || safetyTokens < 0) return ""
    val input = runCatching { Math.addExact(Math.addExact(promptTokens, safetyTokens), toolReserve.toLong()) }.getOrNull() ?: return ""
    val measured = if (measurement == TokenMeasurementKind.UTF8_ESTIMATE) "Estimated input plus safety" else "Input plus safety"
    val label = if (toolReserve > 0) "$measured and reserved tool results" else measured
    return if (input >= capacityTokens) {
        " $label: $input tokens; context capacity: $capacityTokens tokens. " +
            "No output headroom remains, so lowering the output limit alone cannot make this input fit. " +
            "Explicitly reduce input, instructions or optional tools, or choose a route with a larger verified context."
    } else {
        " $label: $input tokens; remaining output headroom: ${capacityTokens - input} tokens. " +
            "Explicitly adjust the output allowance or choose a route with a larger verified context."
    }
}
