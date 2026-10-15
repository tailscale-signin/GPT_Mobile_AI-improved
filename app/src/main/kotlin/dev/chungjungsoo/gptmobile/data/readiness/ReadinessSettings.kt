package dev.chungjungsoo.gptmobile.data.readiness

import kotlinx.serialization.Serializable

@Serializable
enum class PreparationStrategy { BALANCED, MINIMAL_RESOURCES }

/** Pilot defaults stay off until physical-device acceptance gates have passed. */
@Serializable
data class ReadinessSettings(
    val version: Int = 1,
    val enabled: Boolean = false,
    val predictMemory: Boolean = false,
    val warmMeteredNetworks: Boolean = false,
    val strategy: PreparationStrategy = PreparationStrategy.BALANCED,
    val lazyCatalogues: Boolean = true,
    val cacheGeneration: Long = 0
)
