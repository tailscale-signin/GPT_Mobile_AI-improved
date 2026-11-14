package dev.chungjungsoo.gptmobile.data.context

/** Capacity evidence captured before admission can reject; it never changes the checked limits. */
data class CapacityAdmissionObservation(
    val capacity: CapacitySnapshot,
    val routeContextTokens: Long? = null,
    val configuredContextTokens: Long? = null,
    val catalogContextTokens: Long? = null
) {
    init {
        require(listOfNotNull(routeContextTokens, configuredContextTokens, catalogContextTokens).all { it > 0 })
    }
}
