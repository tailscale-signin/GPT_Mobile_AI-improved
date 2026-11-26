package dev.chungjungsoo.gptmobile.data.github

import dev.chungjungsoo.gptmobile.data.database.entity.PlatformV2
import dev.chungjungsoo.gptmobile.data.model.AppFeatureSettings
import dev.chungjungsoo.gptmobile.data.network.ProviderRequestConfig
import dev.chungjungsoo.gptmobile.data.repository.SettingRepository
import javax.inject.Inject
import kotlinx.coroutines.CancellationException

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

internal fun PlatformV2.engineeringConfig() = ProviderRequestConfig(apiUrl, token, profileUid = uid, gatewayOwnerControl = true)

/** Preconditions are derived from a verified identity, never from a selected label. */
internal fun ProviderRequestConfig.withGatewayIdentity(identity: GatewayScopeIdentity): ProviderRequestConfig {
    requireGatewayIdentity(identity.gatewayId, identity.principalId)
    val names = setOf("x-gateway-installation-id", "x-gateway-principal-id")
    return copy(extraHeaders = extraHeaders.filterKeys { it.lowercase(java.util.Locale.ROOT) !in names } + mapOf("X-Gateway-Installation-ID" to identity.gatewayId, "X-Gateway-Principal-ID" to identity.principalId))
}

/** Select the verified local engineering service once for every GitHub conversation using this profile. */
class GatewayEngineeringDefaults @Inject constructor(
    private val settings: SettingRepository,
    private val client: GatewayEngineeringClient
) {
    suspend fun discover(profile: PlatformV2) {
        if (!GitHubAutonomousEngineeringPolicy.supportsSavedJobs(profile) || profile.disableAllTools || profile.disableRemoteTools) return
        val saved = settings.getFeatureSettings().githubProfileOverrides[profile.uid]
        if (saved?.engineeringGatewayProfileUid != null || saved?.githubTools == false) return
        val capability = try {
            client.capabilities(profile.engineeringConfig())
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            // A direct inference endpoint can still serve phone-owned GitHub work.
            return
        }
        if (capability.enabled && capability.supportsDurableChat && !capability.gatewayId.isNullOrBlank() && capability.principalId != "network:shared") {
            settings.selectVerifiedEngineeringGateway(profile)
        }
    }
}

/** The proof belongs to this exact saved connection. A concurrent explicit route wins. */
internal suspend fun SettingRepository.selectVerifiedEngineeringGateway(profile: PlatformV2) {
    if (getFeatureSettings().githubProfileOverrides[profile.uid]?.engineeringGatewayProfileUid != null) return
    if (fetchPlatformV2s().singleOrNull { it.uid == profile.uid } != profile) return
    mutateFeatureSettings { it.withVerifiedEngineeringGateway(profile.uid) }
}

internal fun AppFeatureSettings.withVerifiedEngineeringGateway(uid: String): AppFeatureSettings {
    val saved = githubProfileOverrides[uid] ?: GitHubChatProfileOverride.newProfile
    if (saved.engineeringGatewayProfileUid != null) return this
    return copy(githubProfileOverrides = githubProfileOverrides + (uid to saved.copy(engineeringGatewayProfileUid = uid, engineeringGatewayDefaultApplied = true)))
}
