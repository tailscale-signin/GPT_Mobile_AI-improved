package dev.chungjungsoo.gptmobile.data.readiness

import kotlinx.serialization.Serializable

@Serializable
enum class PreparationStrategy { BALANCED, MINIMAL_RESOURCES }

/** Bounded metadata readiness is enabled on new installs; existing persisted settings stay authoritative. */
@Serializable
data class ReadinessSettings(
    val version: Int = 1,
    val enabled: Boolean = true,
    val predictMemory: Boolean = false,
    val warmMeteredNetworks: Boolean = false,
    val strategy: PreparationStrategy = PreparationStrategy.BALANCED,
    val lazyCatalogues: Boolean = true,
    val cacheGeneration: Long = 0
)
