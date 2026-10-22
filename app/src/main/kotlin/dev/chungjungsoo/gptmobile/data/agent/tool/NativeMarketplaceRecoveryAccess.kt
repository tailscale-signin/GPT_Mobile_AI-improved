package dev.chungjungsoo.gptmobile.data.agent.tool

import dev.chungjungsoo.gptmobile.data.agent.sameRecoveryDestination
import dev.chungjungsoo.gptmobile.data.catalog.GitHubMarketplacePackage
import dev.chungjungsoo.gptmobile.data.database.entity.PlatformV2
import dev.chungjungsoo.gptmobile.data.marketplace.NativeMarketplaceRegistry
import dev.chungjungsoo.gptmobile.data.marketplace.NativePluginInstallation
import dev.chungjungsoo.gptmobile.data.model.ToolPluginId
import dev.chungjungsoo.gptmobile.data.model.ToolServiceCatalog
import dev.chungjungsoo.gptmobile.data.repository.SettingRepository

/** Native installations have registry grants, not MCP binding rows. Usage counters are not grants. */
internal fun nativeMarketplaceRecoveryAccess(
    profileUid: String,
    acceptedProfile: PlatformV2?,
    entry: GitHubMarketplacePackage,
    operation: String,
    accepted: NativePluginInstallation,
    registry: NativeMarketplaceRegistry,
    settings: SettingRepository
): suspend () -> Boolean = {
    val profile = settings.fetchPlatformV2s().singleOrNull { it.uid == profileUid }
    val features = settings.getFeatureSettings()
    val current = registry.load()[entry.id]
    profile != null &&
        acceptedProfile != null &&
        sameRecoveryDestination(acceptedProfile, profile) &&
        profile.enabled &&
        !profile.disableAllTools &&
        !profile.disableRemoteTools &&
        listOf(entry.id, ToolServiceCatalog.forPackage(entry).id, ToolPluginId.nativeOperation(entry.id, operation)).all {
            features.isToolPluginEnabledForProfile(profileUid, it)
        } &&
        current != null &&
        current.enabled &&
        current.ready(entry) &&
        operation !in current.disabledOperations &&
        current.endpoint == accepted.endpoint &&
        current.credentialRef == accepted.credentialRef &&
        current.endpointConfig(operation) == accepted.endpointConfig(operation)
}
