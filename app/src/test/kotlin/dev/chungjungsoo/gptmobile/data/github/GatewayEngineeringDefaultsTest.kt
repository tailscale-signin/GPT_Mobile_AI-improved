package dev.chungjungsoo.gptmobile.data.github

import dev.chungjungsoo.gptmobile.data.database.entity.PlatformV2
import dev.chungjungsoo.gptmobile.data.model.AppFeatureSettings
import dev.chungjungsoo.gptmobile.data.model.ClientType
import dev.chungjungsoo.gptmobile.data.repository.SettingRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class GatewayEngineeringDefaultsTest {
    private val profile = PlatformV2(uid = "pc", name = "PC", compatibleType = ClientType.LLAMA, apiUrl = "http://192.168.1.4:8090/v1", model = "selected")
    private val client = mockk<GatewayEngineeringClient>()
    private val settings = mockk<SettingRepository>()
    private var features = AppFeatureSettings()
    private val defaults = GatewayEngineeringDefaults(settings, client)
    private val capability = GatewayEngineeringCapabilities("engineering", 1, enabled = true, gatewayId = "pc", principalId = "account", taskScopeProtocol = 1, taskScopes = listOf("read", "plan", "implement"), conversationSync = true, durableDetach = true)

    init {
        coEvery { settings.getFeatureSettings() } answers { features }
        coEvery { settings.fetchPlatformV2s() } returns listOf(profile)
        coEvery { settings.mutateFeatureSettings(any()) } answers {
            firstArg<(AppFeatureSettings) -> AppFeatureSettings>()(features).also { features = it }
        }
        coEvery { client.capabilities(any(), false) } returns capability
    }

    @Test fun freshAndRestoredProfilesUseVerifiedEngineeringOnceAcrossChats() = runTest {
        for (previous in listOf(null, GitHubChatProfileOverride.newProfile.copy(webSearch = false, engineeringGatewayDefaultApplied = true))) {
            features = AppFeatureSettings(githubProfileOverrides = previous?.let { mapOf(profile.uid to it) }.orEmpty())
            defaults.discover(profile)
            defaults.discover(profile)
            val selected = features.githubProfileOverrides.getValue("pc")
            assertEquals("pc", selected.engineeringGatewayProfileUid)
            assertEquals(previous?.webSearch ?: true, selected.webSearch)
        }
        coVerify(exactly = 2) { client.capabilities(any(), false) }
        coVerify(exactly = 2) { settings.mutateFeatureSettings(any()) }
    }

    @Test fun publicMapOrUnusableJobServiceCannotBecomeTheEngineeringRoute() = runTest {
        for (observed in listOf(capability.copy(enabled = false), capability.copy(durableDetach = false), capability.copy(principalId = "network:shared"))) {
            coEvery { client.capabilities(any(), false) } returns observed
            defaults.discover(profile)
            assertNull(features.githubProfileOverrides["pc"])
        }
        coVerify(exactly = 0) { settings.mutateFeatureSettings(any()) }
    }

    @Test fun disablingGitHubDuringDiscoveryPreservesTheUpdatedPreference() = runTest {
        coEvery { client.capabilities(any(), false) } answers {
            features = AppFeatureSettings(githubProfileOverrides = mapOf("pc" to GitHubChatProfileOverride.newProfile.copy(githubTools = false)))
            capability
        }
        defaults.discover(profile)
        assertNull(features.githubProfileOverrides.getValue("pc").engineeringGatewayProfileUid)
    }

    @Test fun changedEndpointAndDifferentSavedRouteWinDuringDiscovery() = runTest {
        coEvery { settings.fetchPlatformV2s() } returns listOf(profile.copy(apiUrl = "http://10.0.0.2:8090/v1"))
        defaults.discover(profile)
        assertNull(features.githubProfileOverrides["pc"])

        coEvery { settings.fetchPlatformV2s() } returns listOf(profile)
        coEvery { client.capabilities(any(), false) } answers {
            features = AppFeatureSettings(githubProfileOverrides = mapOf("pc" to GitHubChatProfileOverride.newProfile.copy(engineeringGatewayProfileUid = "other")))
            capability
        }
        defaults.discover(profile)
        assertEquals("other", features.githubProfileOverrides["pc"]?.engineeringGatewayProfileUid)
        coVerify(exactly = 0) { settings.mutateFeatureSettings(any()) }
    }
}
