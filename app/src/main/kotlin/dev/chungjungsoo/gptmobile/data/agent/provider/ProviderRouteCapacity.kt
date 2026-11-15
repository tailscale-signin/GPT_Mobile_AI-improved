package dev.chungjungsoo.gptmobile.data.agent.provider

import dev.chungjungsoo.gptmobile.data.context.CapacityProvenance
import dev.chungjungsoo.gptmobile.data.context.ReasoningCapabilityObservation
import dev.chungjungsoo.gptmobile.data.context.TokenBudgetSettings
import dev.chungjungsoo.gptmobile.data.database.entity.PlatformV2
import dev.chungjungsoo.gptmobile.data.free.FreeCatalogState
import dev.chungjungsoo.gptmobile.data.model.ClientType
import dev.chungjungsoo.gptmobile.data.model.FreeAiProvider

/** One route decision shared by planning and serialization; presets are not server ceilings. */
internal object ProviderRouteCapacity {
    data class PrimaryContextDecision(
        val tokens: Int,
        val configuredTokens: Int?,
        val verifiedRouteTokens: Int?,
        val catalogTokens: Int?,
        val provenance: CapacityProvenance
    )

    fun key(profile: PlatformV2) = "${profile.compatibleType}|${profile.apiUrl}|${profile.model}|${profile.openRouterRouting.orEmpty()}"
    fun ceiling(profile: PlatformV2): Int? {
        val free = FreeAiProvider.fromApiUrl(profile.apiUrl).takeIf { profile.compatibleType == ClientType.FREE }
        return listOfNotNull(ProviderOutputLimits.ceiling(key(profile)), free?.enforcedOutputCeiling, free?.let { FreeCatalogState.model(it, profile.model)?.outputTokens }).minOrNull()
    }
    fun output(profile: PlatformV2, requested: Int?): Int = listOfNotNull(
        ProviderOutputLimits.effective(key(profile), requested),
        profile.maxTokens?.takeIf { it > 0 },
        ceiling(profile)
    ).minOrNull() ?: 2048
    fun context(profile: PlatformV2): Int? = if (profile.compatibleType == ClientType.FREE) {
        FreeAiProvider.fromApiUrl(profile.apiUrl)?.let { FreeCatalogState.model(it, profile.model)?.contextTokens }
    } else {
        dev.chungjungsoo.gptmobile.data.openrouter.OpenRouterCatalogLimits.forRequest(profile.compatibleType.name, profile.apiUrl, profile.model, profile.openRouterRouting.orEmpty())?.context
    }

    /** The caller obtains readiness through check(profile, mode), which validates freshness and route epochs. */
    fun primaryContext(profile: PlatformV2, settings: TokenBudgetSettings, readiness: ReasoningCapabilityObservation?, githubMode: Boolean): Int =
        primaryContextDecision(profile, settings, readiness, githubMode).tokens

    /** Return the very observations consumed by this decision, without a second metadata lookup. */
    fun primaryContextDecision(profile: PlatformV2, settings: TokenBudgetSettings, readiness: ReasoningCapabilityObservation?, githubMode: Boolean): PrimaryContextDecision {
        val verified = readiness?.takeIf { profile.compatibleType == ClientType.LLAMA && it.routeVerified && it.profileUid == profile.uid && it.model == profile.model }
            ?.verifiedContextTokens?.takeIf { it > 0 }
        val catalog = context(profile)
        val route = listOfNotNull(catalog, verified).minOrNull()
        val configured = minOf(settings.contextTokens, settings.profileContextCeilings[profile.uid] ?: Int.MAX_VALUE)
        val fallback = if (githubMode && profile.compatibleType == ClientType.LLAMA) 32_768 else Int.MAX_VALUE
        val tokens = minOf(configured, route ?: fallback)
        return PrimaryContextDecision(
            tokens = tokens,
            configuredTokens = configured.takeIf { it in 1 until Int.MAX_VALUE },
            verifiedRouteTokens = verified,
            catalogTokens = catalog,
            provenance = when {
                tokens == verified -> CapacityProvenance.VERIFIED_ROUTE
                tokens != Int.MAX_VALUE && (tokens == configured || tokens == catalog) -> CapacityProvenance.DECLARED
                route == null && tokens == fallback && tokens != Int.MAX_VALUE -> CapacityProvenance.CONSERVATIVE_POLICY
                else -> CapacityProvenance.UNKNOWN
            }
        )
    }
}
