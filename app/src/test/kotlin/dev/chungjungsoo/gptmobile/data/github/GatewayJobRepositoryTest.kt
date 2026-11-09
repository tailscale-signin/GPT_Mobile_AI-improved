package dev.chungjungsoo.gptmobile.data.github

import dev.chungjungsoo.gptmobile.data.database.ChatDatabaseV2
import dev.chungjungsoo.gptmobile.data.database.entity.PlatformV2
import dev.chungjungsoo.gptmobile.data.model.AppFeatureSettings
import dev.chungjungsoo.gptmobile.data.model.ClientType
import dev.chungjungsoo.gptmobile.data.repository.SettingRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GatewayJobRepositoryTest {
    private val model = PlatformV2(uid = "model", name = "Inference", compatibleType = ClientType.LLAMA, apiUrl = "http://127.0.0.1:28880/v1", model = "selected", enabled = true)
    private val gateway = model.copy(uid = "gateway", name = "Saved work", apiUrl = "http://127.0.0.1:29443/v1")
    private val settings = mockk<SettingRepository>()
    private val client = mockk<GatewayJobClient>()
    private val scopes = mockk<GatewayVerifiedScopeStore>()
    private val database = mockk<ChatDatabaseV2>()
    private val repository = GatewayJobRepository(database, settings, client, mockk(), scopes)

    private fun configure(features: AppFeatureSettings) {
        coEvery { settings.fetchPlatformV2s() } returns listOf(model, gateway)
        coEvery { settings.getFeatureSettings() } returns features
        every { settings.observePlatformV2s() } returns flowOf(listOf(model, gateway))
        every { settings.observeFeatureSettings() } returns flowOf(features)
        every { scopes.changes } returns kotlinx.coroutines.flow.MutableStateFlow(0L)
        coEvery { scopes.configurationRevision(any()) } returns "saved-revision"
    }

    @Test fun absentGatewayBindingNeverFallsBackToTheInferenceAddress() = runTest {
        configure(AppFeatureSettings())
        assertFalse(repository.configured(model.uid))
        assertNull(repository.observeConfiguration(model.uid).first())
        val failure = runCatching { repository.refresh(model.uid) }.exceptionOrNull()
        assertTrue(failure is IllegalStateException)
        assertTrue(failure?.message.orEmpty().contains("Select the engineering gateway"))
        coVerify(exactly = 0) { client.verifySavedWorkContract(any(), any(), any()) }
        coVerify(exactly = 0) { client.catalog(any(), any()) }
    }

    @Test fun explicitGatewayBindingUsesItsAddressAndUnsupportedPreflightPreventsCatalogReads() = runTest {
        configure(AppFeatureSettings(githubProfileOverrides = mapOf(model.uid to GitHubChatProfileOverride(engineeringGatewayProfileUid = gateway.uid))))
        coEvery { client.verifySavedWorkContract(any(), any(), any()) } throws GatewayHistoryUnsupported(GatewayAdmissionSurface.CAPABILITIES)
        val route = repository.route(model.uid)
        assertEquals(gateway.uid, route.profileUid)
        assertEquals(gateway.apiUrl, route.config.apiUrl)
        assertTrue(repository.configured(model.uid))
        assertTrue(runCatching { repository.refresh(model.uid) }.exceptionOrNull() is GatewayHistoryUnsupported)
        coVerify(exactly = 1) { client.verifySavedWorkContract(match { it.apiUrl == gateway.apiUrl }, null, false) }
        coVerify(exactly = 0) { client.catalog(any(), any()) }
    }
}
