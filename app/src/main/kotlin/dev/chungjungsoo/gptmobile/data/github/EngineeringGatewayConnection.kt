package dev.chungjungsoo.gptmobile.data.github

import dev.chungjungsoo.gptmobile.data.database.entity.PlatformV2
import dev.chungjungsoo.gptmobile.data.network.ProviderRequestConfig
import dev.chungjungsoo.gptmobile.data.repository.SettingRepository

/** Inference connectivity never implies an engineering service on the same port. */
internal suspend fun SettingRepository.engineeringGateway(inferenceProfileUid: String): PlatformV2 {
    val uid = getFeatureSettings().githubProfileOverrides[inferenceProfileUid]?.engineeringGatewayProfileUid
    check(!uid.isNullOrBlank()) { "Engineering gateway unavailable: select its connection in GitHub settings. Your inference connection remains separate." }
    val profile = fetchPlatformV2s().singleOrNull { it.uid == uid }
    check(GitHubAutonomousEngineeringPolicy.supportsSavedJobs(profile)) { "The selected engineering gateway must be an enabled private Llama or Ollama connection." }
    return requireNotNull(profile)
}

internal fun PlatformV2.engineeringConfig() = ProviderRequestConfig(apiUrl, token, profileUid = uid)
