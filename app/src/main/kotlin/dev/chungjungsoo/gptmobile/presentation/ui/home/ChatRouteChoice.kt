package dev.chungjungsoo.gptmobile.presentation.ui.home

import dev.chungjungsoo.gptmobile.data.agent.provider.ProviderRouteCapacity
import dev.chungjungsoo.gptmobile.data.benchmark.BenchmarkOutcome
import dev.chungjungsoo.gptmobile.data.database.entity.PlatformV2
import dev.chungjungsoo.gptmobile.data.model.ClientType
import dev.chungjungsoo.gptmobile.data.network.FreeProviderPreflight
import dev.chungjungsoo.gptmobile.data.network.gateway.GatewayAvailability
import dev.chungjungsoo.gptmobile.data.network.gateway.GatewayAvailabilityStatus
import dev.chungjungsoo.gptmobile.data.pricing.PriceFreshness
import dev.chungjungsoo.gptmobile.data.pricing.decimalRate
import java.math.BigDecimal

internal enum class ChatRouteChoice(val label: String) { AUTO("Auto"), FAST("Fast"), THINK("Think"), RESEARCH("Research") }

private data class Candidate(val profile: PlatformV2, val latency: Long?, val score: Double?, val price: BigDecimal?, val completionRate: Double?, val catalogContext: Int?, val demonstratedInput: Long)

internal fun recommendedChatProfile(
    choice: ChatRouteChoice,
    eligible: List<PlatformV2>,
    insights: Map<String, ProfileInsight>,
    gateways: Map<String, GatewayAvailability>,
    requiresResearch: Boolean = choice == ChatRouteChoice.RESEARCH,
    researchAllowed: (PlatformV2) -> Boolean
): PlatformV2? {
    val candidates = eligible.mapNotNull { profile ->
        if (!profile.enabled || profile.model.isBlank() || FreeProviderPreflight.check(profile) != null) return@mapNotNull null
        val gateway = gateways[profile.uid]
        if (gateway != null && !gateway.isOnline && gateway.status != GatewayAvailabilityStatus.NOT_GATEWAY) return@mapNotNull null
        if (requiresResearch && !researchAllowed(profile)) return@mapNotNull null
        val insight = insights[profile.uid]?.takeIf { it.matches(profile) }
        val samples = insight?.localRun?.samples
        val trials = insight?.score?.submission?.trials
        val passed = BenchmarkOutcome.PASSED
        val times = (samples?.filter { it.outcome == passed }?.mapNotNull { it.firstTextMs } ?: trials?.filter { it.outcome == passed }?.mapNotNull { it.firstTextMs }).orEmpty().filter { it > 0 }.sorted()
        val outcomes = (samples?.map { it.outcome } ?: trials?.map { it.outcome }).orEmpty()
            .filter { it !in setOf(BenchmarkOutcome.SKIPPED, BenchmarkOutcome.CANCELED, BenchmarkOutcome.UNSUPPORTED) }
        val rate = outcomes.takeIf { it.isNotEmpty() }?.let { it.count { outcome -> outcome == passed }.toDouble() / it.size }
        val input = samples?.filter { it.outcome == passed && !it.estimatedTokens }?.maxOfOrNull { it.inputTokens.toLong() }
            ?: trials?.filter { it.outcome == passed && !it.estimatedTokens }?.maxOfOrNull { it.inputTokens }
        val price = insight?.price?.takeIf {
            it.currency == "USD" && it.valid() && it.freshness(insight.now) == PriceFreshness.FRESH && !it.conditionalPricing && !it.routingReference
        }
        val tokenPrice = if (profile.compatibleType == ClientType.LITERT_LM) {
            BigDecimal.ZERO
        } else {
            price?.let {
                decimalRate(it.inputPerMillion)?.add(decimalRate(it.outputPerMillion))
            }
        }
        Candidate(profile, times.getOrNull(times.size / 2), insight?.score?.score, tokenPrice, rate, ProviderRouteCapacity.context(profile), input ?: 0L)
    }
    val fallback = compareByDescending<Candidate> { it.profile.isFavorite }.thenBy { it.profile.name.lowercase() }.thenBy { it.profile.uid }
    val latency = compareBy<Candidate> { it.latency ?: Long.MAX_VALUE }
    val quality = compareByDescending<Candidate> { it.score ?: -1.0 }
    val cost = compareBy<Candidate> { it.price == null }.thenBy { it.price }
    val reliability = compareByDescending<Candidate> { it.completionRate ?: 0.5 }
    val inputRoom = compareByDescending<Candidate> { it.catalogContext ?: 0 }.thenByDescending { it.demonstratedInput }
    val order = when (choice) {
        ChatRouteChoice.AUTO -> reliability.then(quality).then(cost).then(latency)
        ChatRouteChoice.FAST -> latency.then(reliability).then(cost).then(quality)
        ChatRouteChoice.THINK -> compareByDescending<Candidate> { it.profile.reasoning }.then(reliability).then(quality).then(inputRoom).then(cost)
        ChatRouteChoice.RESEARCH -> reliability.then(quality).then(inputRoom).then(latency).then(cost)
    }
    return candidates.minWithOrNull(order.then(fallback))?.profile
}
