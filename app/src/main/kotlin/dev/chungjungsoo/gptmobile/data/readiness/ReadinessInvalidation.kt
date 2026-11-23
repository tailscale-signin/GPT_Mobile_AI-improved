package dev.chungjungsoo.gptmobile.data.readiness

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Advance synchronously before credential/grant/restore mutation; cleanup can happen later. */
object ReadinessInvalidation {
    private val generation = MutableStateFlow(0L)
    private val catalogueGeneration = MutableStateFlow(0L)
    val changes = generation.asStateFlow()
    val catalogueChanges = catalogueGeneration.asStateFlow()
    val epoch: Long get() = generation.value
    val catalogueEpoch: Long get() = catalogueGeneration.value

    @Volatile var catalogueReason: String = "INITIAL_STATE"
        private set

    @Synchronized
    fun advance(invalidateCatalogues: Boolean = true, reason: String = "AUTHORITY_OR_CONFIGURATION_CHANGED") {
        if (invalidateCatalogues) {
            catalogueReason = reason.takeIf { it in setOf("AUTHORITY_OR_CONFIGURATION_CHANGED", "PROFILE_AUTHORITY_CHANGED", "FEATURE_AUTHORITY_CHANGED") } ?: "AUTHORITY_OR_CONFIGURATION_CHANGED"
            catalogueGeneration.value++
        }
        generation.value++
    }
}
