package dev.chungjungsoo.gptmobile.presentation.ui.home

import dev.chungjungsoo.gptmobile.data.database.entity.PlatformV2
import dev.chungjungsoo.gptmobile.data.model.ClientType
import dev.chungjungsoo.gptmobile.data.network.FreeProviderPreflight
import dev.chungjungsoo.gptmobile.data.network.gateway.GatewayAvailability
import dev.chungjungsoo.gptmobile.data.network.gateway.GatewayAvailabilityStatus
import dev.chungjungsoo.gptmobile.data.pricing.PriceFreshness
import dev.chungjungsoo.gptmobile.data.pricing.decimalRate
import java.math.BigDecimal

internal enum class ChatRouteChoice(val label: String) { AUTO("Auto"), FAST("Fast"), THINK("Think"), RESEARCH("Research") }

private data class Candidate(val profile: PlatformV2, val latency: Long?, val score: Double?, val price: BigDecimal?)

internal fun recommendedChatProfile(
    choice: ChatRouteChoice,
    eligible: List<PlatformV2>,
    insights: Map<String, ProfileInsight>,
    gateways: Map<String, GatewayAvailability>,
    researchAllowed: (PlatformV2) -> Boolean
): PlatformV2? {
    val candidates = eligible.mapNotNull { profile ->
        if (!profile.enabled || profile.model.isBlank() || FreeProviderPreflight.check(profile) != null) return@mapNotNull null
        val gateway = gateways[profile.uid]
        if (gateway != null && !gateway.isOnline && gateway.status !in setOf(GatewayAvailabilityStatus.NOT_GATEWAY, GatewayAvailabilityStatus.CHECKING)) return@mapNotNull null
        if (choice == ChatRouteChoice.RESEARCH && !researchAllowed(profile)) return@mapNotNull null
        val insight = insights[profile.uid]?.takeIf { it.matches(profile) }
        val times = (insight?.localRun?.samples?.mapNotNull { it.firstTextMs } ?: insight?.score?.submission?.trials?.mapNotNull { it.firstTextMs }).orEmpty().filter { it > 0 }.sorted()
        val price = insight?.price?.takeIf {
            it.currency == "USD" && it.valid() && it.freshness(insight.now) == PriceFreshness.FRESH && !it.conditionalPricing && !it.routingReference
        }
        val rate = if (profile.compatibleType == ClientType.LITERT_LM) {
            BigDecimal.ZERO
        } else {
            price?.let {
                decimalRate(it.inputPerMillion)?.add(decimalRate(it.outputPerMillion))
            }
        }
        Candidate(profile, times.getOrNull(times.size / 2), insight?.score?.score, rate)
    }
    val fallback = compareByDescending<Candidate> { it.profile.isFavorite }.thenBy { it.profile.name.lowercase() }.thenBy { it.profile.uid }
    val latency = compareBy<Candidate> { it.latency ?: Long.MAX_VALUE }
    val quality = compareByDescending<Candidate> { it.score ?: -1.0 }
    val cost = compareBy<Candidate> { it.price == null }.thenBy { it.price }
    val order = when (choice) {
        ChatRouteChoice.AUTO -> quality.then(cost).then(latency)
        ChatRouteChoice.FAST -> latency.then(cost).then(quality)
        ChatRouteChoice.THINK -> compareByDescending<Candidate> { it.profile.reasoning }.then(quality).then(cost)
        ChatRouteChoice.RESEARCH -> quality.then(latency).then(cost)
    }
    return candidates.minWithOrNull(order.then(fallback))?.profile
}
