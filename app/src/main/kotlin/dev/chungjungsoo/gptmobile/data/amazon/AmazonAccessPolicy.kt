package dev.chungjungsoo.gptmobile.data.amazon

import dev.chungjungsoo.gptmobile.data.database.entity.PlatformV2
import dev.chungjungsoo.gptmobile.data.model.AppFeatureSettings
import dev.chungjungsoo.gptmobile.data.model.ToolPluginId
import dev.chungjungsoo.gptmobile.data.repository.SettingRepository
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOf

@Singleton
class AmazonAccessPolicy @Inject constructor(private val settings: SettingRepository) {
    /** Product media is shared by both Amazon plugins, with the owning profile's live permissions. */
    suspend fun mediaAllowed(owner: String?): Boolean {
        val profile = settings.fetchPlatformV2s().firstOrNull { it.uid == owner }
        val features = settings.getFeatureSettings()
        return permitsMedia(features, profile)
    }

    fun mediaChanges(owner: String?) = if (owner == null) {
        flowOf(false)
    } else {
        combine(settings.observeFeatureSettings(), settings.observePlatformV2ByUid(owner)) { features, profile ->
            permitsMedia(features, profile)
        }.distinctUntilChanged()
    }

    suspend fun allowed(owner: String, network: Boolean): Boolean = permits(
        settings.getFeatureSettings(),
        settings.fetchPlatformV2s().firstOrNull { it.uid == owner },
        network
    )

    fun changes(owner: String, network: Boolean) = combine(settings.observeFeatureSettings(), settings.observePlatformV2ByUid(owner)) { features, profile ->
        permits(features, profile, network)
    }.distinctUntilChanged()

    companion object {
        fun permitsMedia(features: AppFeatureSettings, profile: PlatformV2?): Boolean =
            profile?.enabled == true &&
                !profile.disableAllTools &&
                !profile.disableRemoteTools &&
                (features.isToolPluginEnabledForProfile(profile.uid, ToolPluginId.AMAZON_FREE) || features.isToolPluginEnabledForProfile(profile.uid, ToolPluginId.AMAZON_SEARCH))

        fun permits(features: AppFeatureSettings, profile: PlatformV2?, network: Boolean): Boolean =
            profile?.enabled == true &&
                !profile.disableAllTools &&
                (if (network) !profile.disableRemoteTools else !profile.disableLocalTools) &&
                features.isToolPluginEnabledForProfile(profile.uid, ToolPluginId.AMAZON_FREE)
    }
}
