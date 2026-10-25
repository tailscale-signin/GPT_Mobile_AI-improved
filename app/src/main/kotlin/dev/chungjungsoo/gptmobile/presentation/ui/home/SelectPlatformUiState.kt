package dev.chungjungsoo.gptmobile.presentation.ui.home

import dev.chungjungsoo.gptmobile.data.database.entity.PlatformV2
import dev.chungjungsoo.gptmobile.data.model.ClientType
import dev.chungjungsoo.gptmobile.data.pricing.PriceFreshness
import dev.chungjungsoo.gptmobile.data.pricing.PriceSource
import dev.chungjungsoo.gptmobile.data.pricing.formatRate
import dev.chungjungsoo.gptmobile.data.pricing.priceContextKey

internal fun selectedProfileUids(profiles: List<PlatformV2>, selected: List<Boolean>): Set<String> =
    profiles.mapIndexedNotNull { index, profile -> profile.uid.takeIf { profile.enabled && dev.chungjungsoo.gptmobile.data.network.FreeProviderPreflight.check(profile)?.category != dev.chungjungsoo.gptmobile.data.network.FreeFailureCategory.PROVIDER_CLIENT_RESTRICTED && selected.getOrElse(index) { false } } }.toSet()

internal fun confirmedProfileUids(profiles: List<PlatformV2>, selected: Set<String>): List<String> {
    val requested = profiles.filter { it.enabled && it.uid in selected }
    if (requested.any { dev.chungjungsoo.gptmobile.data.network.FreeProviderPreflight.check(it) != null }) return emptyList()
    return requested.map { it.uid }
}

internal fun ProfileInsight.matches(profile: PlatformV2): Boolean =
    this.profile.uid == profile.uid && priceContextKey(this.profile) == priceContextKey(profile)

internal enum class InsightAvailability { HIDDEN, LOADING, MISSING, ON_DEVICE, AVAILABLE }
internal data class CompactProfileInsights(
    val scoreState: InsightAvailability,
    val score: Double?,
    val community: Boolean,
    val priceState: InsightAvailability,
    val rate: String?,
    val qualified: Boolean
)

internal fun ProfileInsight.compact(): CompactProfileInsights {
    val local = profile.compatibleType == ClientType.LITERT_LM
    val validPrice = price?.takeIf { it.valid() && it.freshness(now) != PriceFreshness.EXPIRED }
    return CompactProfileInsights(
        when {
            !settings.newChatScores -> InsightAvailability.HIDDEN
            !scoresLoaded -> InsightAvailability.LOADING
            local -> InsightAvailability.ON_DEVICE
            score == null -> InsightAvailability.MISSING
            else -> InsightAvailability.AVAILABLE
        },
        score?.score?.takeUnless { local },
        community,
        when {
            !settings.newChatPrices -> InsightAvailability.HIDDEN
            local -> InsightAvailability.ON_DEVICE
            !pricesLoaded -> InsightAvailability.LOADING
            validPrice == null -> InsightAvailability.MISSING
            else -> InsightAvailability.AVAILABLE
        },
        validPrice?.let { "${it.currency} ${formatRate(it.inputPerMillion)} / ${formatRate(it.outputPerMillion)}" },
        validPrice?.let { it.routingReference || it.conditionalPricing || it.source == PriceSource.ACCOUNT_OVERRIDE || it.freshness(now) == PriceFreshness.STALE } == true
    )
}
