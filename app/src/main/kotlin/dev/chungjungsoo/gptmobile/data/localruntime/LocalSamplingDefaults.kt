package dev.chungjungsoo.gptmobile.data.localruntime

import dev.chungjungsoo.gptmobile.data.catalog.CatalogEntry
import dev.chungjungsoo.gptmobile.data.localmodel.SocVariantResolver

data class LocalSamplingDefaults(
    val temperature: Float,
    val topP: Float,
    val topK: Int,
    val maxTokens: Int,
    val accelerator: String
)

/**
 * Resolves sampling and hardware defaults for an on-device catalog entry.
 *
 * If the selected accelerator is NPU, maxTokens is strictly clamped to the SoC variant context window.
 * For GPU/CPU on devices with high RAM capacity (>= 12GB RAM), maxTokens dynamically scales up
 * to 4096 (or 8192 if the catalog entry supports it). On low-memory devices (< 6GB RAM), maxTokens
 * defaults to at most 1024 tokens to safeguard against OOM.
 */
fun localSamplingDefaults(
    entry: CatalogEntry,
    deviceSocModel: String = "",
    deviceRamGb: Long = 8L
): LocalSamplingDefaults {
    val accelerator = LocalAccelerators.defaultFrom(
        supported = entry.supportedAccelerators,
        socToModelFiles = entry.socToModelFiles,
        deviceSocModel = deviceSocModel
    )

    val baseMaxTokens = entry.defaultConfig.maxTokens
    val resolvedMaxTokens = when {
        accelerator == LocalAccelerators.NPU -> {
            val variantContext = SocVariantResolver.resolve(entry, deviceSocModel).contextSize
            if (variantContext > 0) minOf(baseMaxTokens, variantContext) else baseMaxTokens
        }
        deviceRamGb >= 12L -> {
            maxOf(baseMaxTokens, 4096)
        }
        deviceRamGb < 6L -> {
            minOf(baseMaxTokens, 1024)
        }
        else -> baseMaxTokens
    }

    return LocalSamplingDefaults(
        temperature = entry.defaultConfig.temperature,
        topP = entry.defaultConfig.topP,
        topK = entry.defaultConfig.topK,
        maxTokens = resolvedMaxTokens,
        accelerator = accelerator
    )
}
