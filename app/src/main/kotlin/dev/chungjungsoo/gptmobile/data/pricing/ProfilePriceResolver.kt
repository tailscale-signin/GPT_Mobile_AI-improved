package dev.chungjungsoo.gptmobile.data.pricing

import dev.chungjungsoo.gptmobile.data.database.entity.PlatformV2

/** Account rates apply only to the endpoint, connection, model and routing they were reviewed for. */
internal fun resolveProfilePrice(profile: PlatformV2, overrides: List<ManualPrice>, entries: List<ModelPrice>, now: Long): ModelPrice? {
    val provider = billingProvider(profile)
    val override = overrides.lastOrNull {
        it.contextKey == priceContextKey(profile) &&
            it.price.model == profile.model &&
            it.price.provider == (provider ?: "CUSTOM") &&
            it.price.source == PriceSource.ACCOUNT_OVERRIDE &&
            it.price.valid() &&
            it.price.freshness(now) != PriceFreshness.EXPIRED
    }
    if (override != null) return override.price
    if (provider == null || profile.batchMode || profile.model.endsWith(":batch")) return null
    return entries.firstOrNull { it.provider == provider && it.model == profile.model && it.source != PriceSource.ACCOUNT_OVERRIDE && it.valid() && it.freshness(now) != PriceFreshness.EXPIRED }
}
