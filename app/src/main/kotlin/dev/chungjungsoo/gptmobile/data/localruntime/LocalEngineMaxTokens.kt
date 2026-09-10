package dev.chungjungsoo.gptmobile.data.localruntime

import dev.chungjungsoo.gptmobile.data.catalog.CatalogEntry
import dev.chungjungsoo.gptmobile.data.localmodel.SocVariantResolver

/**
 * NPU SOC variants ship with a fixed KV cache context (e.g. ekv1280 = 1280 tokens).
 * Clamp here so a profile's maxTokens cannot overflow that context window.
 *
 * For GPU/CPU on devices with high RAM capacity (>= 12GB RAM), context headroom can safely
 * scale up to 8192 tokens when requested, while low-memory devices clamp to 1024.
 */
fun resolvedEngineMaxTokens(
    requestedMaxTokens: Int,
    accelerator: String,
    entry: CatalogEntry?,
    deviceSocModel: String,
    deviceRamGb: Long = 8L
): Int {
    val normalizedAccelerator = LocalAccelerators.normalize(accelerator)
    if (normalizedAccelerator == LocalAccelerators.NPU && entry != null) {
        val contextSize = SocVariantResolver.resolve(entry, deviceSocModel).contextSize
        if (contextSize > 0) {
            return minOf(requestedMaxTokens, contextSize)
        }
    }

    // High RAM (>= 12GB / 16GB) device tier: allow expanded long-context up to 8192 tokens
    if (deviceRamGb >= 12L && requestedMaxTokens > 0) {
        return minOf(requestedMaxTokens, MAX_HIGH_RAM_CONTEXT_TOKENS)
    }

    return requestedMaxTokens
}

const val MAX_HIGH_RAM_CONTEXT_TOKENS: Int = 8192
