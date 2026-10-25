package dev.chungjungsoo.gptmobile.data.agent

import dev.chungjungsoo.gptmobile.data.database.entity.PlatformV2
import dev.chungjungsoo.gptmobile.data.free.FreeCatalogState
import dev.chungjungsoo.gptmobile.data.model.ClientType
import dev.chungjungsoo.gptmobile.data.model.FreeAiProvider
import dev.chungjungsoo.gptmobile.data.network.FreeAiRequestLimiter

/** Capability preflight; a manual selection is retained but cannot bypass an unenforceable budget. */
internal object CombinedProviderEligibility {
    fun unavailableReason(profile: PlatformV2, request: String = "", writer: Boolean = false, appendContinuations: Boolean = false): String? {
        if (!profile.enabled) return "Profile is disabled."
        if (writer) CombinedWriterPolicy.unavailableReason(profile, request, appendContinuations = appendContinuations)?.let { return it }
        if (profile.compatibleType != ClientType.FREE) return null
        val provider = FreeAiProvider.fromApiUrl(profile.apiUrl) ?: return "Unknown Free route."
        dev.chungjungsoo.gptmobile.data.network.FreeProviderPreflight.check(profile)?.let { return it.message }
        if (provider == FreeAiProvider.HUGGING_FACE || provider == FreeAiProvider.AI_HORDE && profile.model == "horde-image") {
            return "This route does not expose an enforceable text-output bound for Combined mode. Individual chat remains available."
        }
        provider.fixedOutputTokens?.let { fixed ->
            if (profile.maxTokens?.takeIf { it > 0 }?.let { it < fixed } == true) return "${provider.displayName} requires a $fixed-token output allowance; this profile's smaller limit cannot be enforced by the route."
        }
        if (provider.isDemo && (writer || request.isBlank() || CombinedTaskContract.from(request).factual)) return "Demo model: excluded from factual Combined contributions and final writing."
        // Short local spacing is cancellable scheduling, already enforced by the
        // transport. A provider Retry-After is handled by FreeProviderPreflight.
        FreeAiRequestLimiter.shared.cooldownUntilMillis(provider, profile.model)?.takeIf { it - System.currentTimeMillis() > 60_000 }?.let { return "Rate limited until $it; choose another selected provider or retry after the cooldown." }
        try {
            FreeCatalogState.requireGenerationReady(provider, profile.model, dev.chungjungsoo.gptmobile.data.network.freeCredentialIdentity(provider, profile.token))
        } catch (unavailable: dev.chungjungsoo.gptmobile.data.free.FreeGenerationUnavailableException) {
            return unavailable.message
        }
        return null
    }

    fun recommended(profiles: List<PlatformV2>): List<String> = profiles.filter { unavailableReason(it) == null }
        .distinctBy { Triple(it.compatibleType, it.apiUrl, it.model) }
        .sortedWith(compareBy<PlatformV2> { it.compatibleType == ClientType.FREE && FreeAiProvider.fromApiUrl(it.apiUrl) == FreeAiProvider.AI_HORDE }.thenBy { it.uid })
        .take(4).map { it.uid }
}
