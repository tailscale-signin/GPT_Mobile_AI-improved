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
    val budget: PromptBudgetReceipt? = null
) : IllegalStateException("$code: $message${budget?.let { " ${it.explain()}." }.orEmpty()}") {
    init {
        require(code in CODES)
    }

    companion object {
        val CODES = setOf("CAPACITY_UNVERIFIED", "INPUT_CAPACITY_EXCEEDED", "OUTPUT_CAPACITY_EXCEEDED", "CONTEXT_CAPACITY_EXCEEDED")
    }
}
