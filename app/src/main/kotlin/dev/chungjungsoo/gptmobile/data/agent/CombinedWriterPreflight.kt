package dev.chungjungsoo.gptmobile.data.agent

import dev.chungjungsoo.gptmobile.data.agent.provider.ProviderRouteCapacity
import dev.chungjungsoo.gptmobile.data.context.ContextBudgetService
import dev.chungjungsoo.gptmobile.data.context.TokenBudgetSettings
import dev.chungjungsoo.gptmobile.data.database.entity.PlatformV2
import dev.chungjungsoo.gptmobile.data.model.ClientType

/** Local readiness before contributors spend resources; wire admission still rechecks the real corpus. */
internal object CombinedWriterPreflight {
    suspend fun qualifyControls(
        profile: PlatformV2,
        requestedMode: dev.chungjungsoo.gptmobile.data.context.ReasoningMode,
        capabilities: dev.chungjungsoo.gptmobile.data.context.ReasoningCapabilityRepository?
    ): dev.chungjungsoo.gptmobile.data.context.ReasoningCapabilityObservation? {
        if (profile.compatibleType != ClientType.LLAMA) return null
        val observation = checkNotNull(capabilities) { "COMBINED_WRITER_PREFLIGHT: Reasoning capability checks are unavailable; no contributions were started." }
            .check(profile, requestedMode)
        if (!observation.permitsRequest && observation.status == dev.chungjungsoo.gptmobile.data.context.ReasoningCapabilityStatus.UNSUPPORTED) {
            throw dev.chungjungsoo.gptmobile.data.context.ReasoningAdmissionException(requestedMode)
        }
        if (!observation.permitsRequest || !observation.routeVerified) throw dev.chungjungsoo.gptmobile.data.context.ReasoningCapabilityUnavailableException(observation.safeReasonCode?.takeIf { it.startsWith("REASONING_CAPABILITY_") } ?: "REASONING_CAPABILITY_UNVERIFIED")
        return observation
    }

    fun validate(profile: PlatformV2, requested: CombinedRequestedPolicy, limits: TokenBudgetSettings) {
        require(profile.enabled && profile.uid in requested.participantUids) { "COMBINED_WRITER_PREFLIGHT: Primary is disabled or no longer a participant." }
        CombinedProviderEligibility.unavailableReason(profile, requested.userPrompt, writer = true, appendContinuations = requested.appendContinuations)?.let { error("COMBINED_WRITER_PREFLIGHT: $it") }
        CombinedWriterPolicy.unavailableReason(profile, requested.userPrompt, appendContinuations = requested.appendContinuations)?.let { error("COMBINED_WRITER_PREFLIGHT: $it") }
        val credentialRequired = profile.compatibleType in setOf(ClientType.OPENAI, ClientType.ANTHROPIC, ClientType.GOOGLE, ClientType.GROQ, ClientType.NVIDIA, ClientType.OPENROUTER)
        require(!credentialRequired || !profile.token.isNullOrBlank()) { "COMBINED_WRITER_PREFLIGHT: Primary credentials are missing. Reconnect this profile before collecting contributions." }
        val required = CombinedWriterPolicy.requiredOutput(requested.userPrompt, profile, requested.appendContinuations)
        val output = minOf(ProviderRouteCapacity.output(profile, required), limits.outputTokens.takeIf { it > 0 } ?: Int.MAX_VALUE)
        require(output >= required) { "COMBINED_WRITER_PREFLIGHT: The primary requires $required output tokens; the accepted allowance is $output." }
        val context = listOfNotNull(ProviderRouteCapacity.context(profile), limits.contextTokens.takeIf { it < Int.MAX_VALUE }, limits.profileContextCeilings[profile.uid]).minOrNull()
        val minimumInput = ContextBudgetService.estimate(requested.userPrompt + profile.systemPrompt.orEmpty()).toLong() + 256
        require(context == null || minimumInput + required < context) { "COMBINED_WRITER_PREFLIGHT: The primary cannot fit even the original request and a useful answer. Increase context or change the primary." }
    }
}
