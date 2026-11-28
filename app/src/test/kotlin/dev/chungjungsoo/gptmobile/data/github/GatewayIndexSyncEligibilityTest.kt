package dev.chungjungsoo.gptmobile.data.github

import dev.chungjungsoo.gptmobile.data.database.entity.PlatformV2
import dev.chungjungsoo.gptmobile.data.model.AppFeatureSettings
import dev.chungjungsoo.gptmobile.data.model.ClientType
import dev.chungjungsoo.gptmobile.data.model.ProfileBehaviorSettings
import dev.chungjungsoo.gptmobile.data.model.ToolPluginId
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GatewayIndexSyncEligibilityTest {
    private val context = GitHubConversationContext("github", "owner", "repo", "main", "a".repeat(40), "cloud-ai")
    private val ai = PlatformV2(uid = "cloud-ai", name = "Selected AI", compatibleType = ClientType.OPENAI, apiUrl = "https://provider.example/v1", model = "selected")
    private val host = PlatformV2(uid = "host", name = "Selected PC", compatibleType = ClientType.LLAMA, apiUrl = "http://127.0.0.1:8090/v1", model = "unused")
    private val preferences = GitHubChatProfileOverride(engineeringGatewayProfileUid = host.uid)
    private val features = AppFeatureSettings(githubBlobCache = true, githubProfileOverrides = mapOf(ai.uid to preferences))

    private fun configured(settings: AppFeatureSettings = features, profiles: List<PlatformV2> = listOf(ai, host)) =
        GatewayIndexSyncEligibility.configured(context, profiles, settings)

    @Test fun configuredCloudAiUsesItsExplicitPrivateGateway() {
        assertTrue(configured())
        assertFalse(configured(features.copy(githubProfileOverrides = emptyMap())))
        assertFalse(configured(features.copy(githubProfileOverrides = mapOf(ai.uid to preferences.copy(engineeringGatewayProfileUid = ai.uid)))))
    }

    @Test fun missingDisabledOrAmbiguousSelectionsDoNotConsumeBackgroundSlots() {
        assertFalse(configured(profiles = listOf(host)))
        assertFalse(configured(profiles = listOf(ai.copy(enabled = false), host)))
        assertFalse(configured(profiles = listOf(ai, ai, host)))
        assertFalse(configured(profiles = listOf(ai)))
        assertFalse(configured(profiles = listOf(ai, host.copy(enabled = false))))
        assertFalse(configured(profiles = listOf(ai, host, host)))
    }

    @Test fun explicitToolAndPluginExclusionsRemainEffective() {
        assertFalse(configured(profiles = listOf(ai.copy(disableAllTools = true), host)))
        assertFalse(configured(profiles = listOf(ai.copy(disableRemoteTools = true), host)))
        assertFalse(configured(features.copy(githubProfileOverrides = mapOf(ai.uid to preferences.copy(githubTools = false)))))
        for (id in listOf(ToolPluginId.GITHUB, ToolPluginId.connection(context.connectionUid))) {
            assertFalse(configured(features.copy(toolPluginStates = mapOf(id to false))))
            assertFalse(configured(features.copy(profileBehavior = mapOf(ai.uid to ProfileBehaviorSettings(toolPluginStates = mapOf(id to false))))))
        }
    }

    @Test fun sessionOnlyMemoryAndDisabledPersistenceNeverScheduleMirrors() {
        assertFalse(configured(features.copy(githubBlobCache = false)))
        assertFalse(configured(features.copy(githubProfileOverrides = mapOf(ai.uid to preferences.copy(engineeringMemory = GitHubEngineeringMemoryMode.SESSION_ONLY)))))
    }

    @Test fun restoredConfigurationIsEligibleOnTheNextObservationWithoutNegativeCaching() {
        val disabled = features.copy(githubProfileOverrides = mapOf(ai.uid to preferences.copy(engineeringGatewayProfileUid = null)))
        assertFalse(configured(disabled))
        assertTrue(configured(features))
        assertFalse(configured(disabled))
    }
}
