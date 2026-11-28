package dev.chungjungsoo.gptmobile.data.github

import dev.chungjungsoo.gptmobile.data.database.entity.PlatformV2
import dev.chungjungsoo.gptmobile.data.model.AppFeatureSettings
import dev.chungjungsoo.gptmobile.data.model.ToolPluginId

/** Local scheduling hint only; the graph repository rechecks live authority before every read. */
internal object GatewayIndexSyncEligibility {
    fun configured(context: GitHubConversationContext, profiles: List<PlatformV2>, original: AppFeatureSettings): Boolean {
        if (!original.githubBlobCache) return false
        val selected = profiles.singleOrNull { it.uid == context.mainProfileUid && it.enabled } ?: return false
        if (selected.disableAllTools || selected.disableRemoteTools) return false
        val preferences = original.githubProfileOverrides[selected.uid] ?: GitHubChatProfileOverride.newProfile
        if (!preferences.githubTools || preferences.engineeringMemory == GitHubEngineeringMemoryMode.SESSION_ONLY) return false
        if (!original.isToolPluginEnabledForProfile(selected.uid, ToolPluginId.GITHUB) ||
            !original.isToolPluginEnabledForProfile(selected.uid, ToolPluginId.connection(context.connectionUid))
        ) {
            return false
        }
        return engineeringGatewayProfile(profiles, preferences.engineeringGatewayProfileUid) != null
    }
}
