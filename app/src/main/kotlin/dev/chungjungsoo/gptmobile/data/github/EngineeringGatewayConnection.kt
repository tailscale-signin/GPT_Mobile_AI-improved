package dev.chungjungsoo.gptmobile.data.github

import dev.chungjungsoo.gptmobile.data.database.entity.PlatformV2
import dev.chungjungsoo.gptmobile.data.network.ProviderRequestConfig
import dev.chungjungsoo.gptmobile.data.repository.SettingRepository

/** Inference connectivity never implies an engineering service on the same port. */
internal suspend fun SettingRepository.engineeringGateway(inferenceProfileUid: String): PlatformV2 {
    val uid = getFeatureSettings().githubProfileOverrides[inferenceProfileUid]?.engineeringGatewayProfileUid
    check(!uid.isNullOrBlank()) { "Engineering gateway unavailable: select its connection in GitHub settings. Your inference connection remains separate." }
    val profile = engineeringGatewayProfile(fetchPlatformV2s(), uid)
    check(profile != null) { "The selected engineering gateway must be an enabled private Llama or Ollama connection." }
    return profile
}

/** Status and execution resolve the same explicit connection; inference is never a fallback. */
internal fun engineeringGatewayProfile(profiles: List<PlatformV2>, gatewayProfileUid: String?): PlatformV2? {
    if (gatewayProfileUid.isNullOrBlank()) return null
    return profiles.singleOrNull { it.uid == gatewayProfileUid }?.takeIf(GitHubAutonomousEngineeringPolicy::supportsSavedJobs)
}

internal fun PlatformV2.engineeringConfig() = ProviderRequestConfig(apiUrl, token, profileUid = uid)
