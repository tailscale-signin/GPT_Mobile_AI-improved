package dev.chungjungsoo.gptmobile.data.repository

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import dev.chungjungsoo.gptmobile.data.database.entity.PlatformV2
import dev.chungjungsoo.gptmobile.data.model.ClientType
import dev.chungjungsoo.gptmobile.data.security.SecretVault
import dev.chungjungsoo.gptmobile.domain.model.OpenRouterSettings
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class OpenRouterSettingsPersistenceTest {
    @get:Rule val temp = TemporaryFolder()

    @Test
    fun settingsSurviveRepositoryRecreationWithoutPlaintextApiKey() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            val store = PreferenceDataStoreFactory.create(scope = scope) { File(temp.root, "settings.preferences_pb") }
            val settings = mockk<SettingRepository>()
            coEvery { settings.fetchPlatformV2s() } returns emptyList()
            val vault = MemoryVault()
            val saved = OpenRouterSettings(apiKey = "test-secret", batchingEnabled = false, batchSize = 4, flushTimeoutMs = 1234, maxRetries = 0, cacheEnabled = false)
            OpenRouterSettingsRepositoryImpl(store, settings, vault).saveSettings(saved)
            assertEquals(saved, OpenRouterSettingsRepositoryImpl(store, settings, vault).loadSettings())
            assertFalse(store.data.first().asMap().values.any { it.toString().contains(saved.apiKey) })
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun nativeOpenRouterPlatformUsesResolvedVaultTokenAndSecureRepositoryUpdates() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            val store = PreferenceDataStoreFactory.create(scope = scope) { File(temp.root, "settings.preferences_pb") }
            val settings = mockk<SettingRepository>(relaxed = true)
            val platform = PlatformV2(id = 7, name = "Router", compatibleType = ClientType.OPENROUTER, token = "vault-token", secretRef = "profile", apiUrl = "https://openrouter.ai/api/v1")
            coEvery { settings.fetchPlatformV2s() } returns listOf(platform)
            val repository = OpenRouterSettingsRepositoryImpl(store, settings, MemoryVault())
            assertEquals("vault-token", repository.loadSettings().apiKey)
            repository.saveSettings(OpenRouterSettings(apiKey = "new-token"))
            coVerify { settings.updatePlatformV2(match { it.id == 7 && it.token == "new-token" && it.secretRef == "profile" }) }
        } finally {
            scope.cancel()
        }
    }

    private class MemoryVault : SecretVault {
        val values = mutableMapOf<String, ByteArray>()
        override suspend fun put(secretRef: String, secret: ByteArray) {
            values[secretRef] = secret.copyOf()
        }
        override suspend fun read(secretRef: String) = values[secretRef]?.copyOf()
        override suspend fun delete(secretRef: String) {
            values.remove(secretRef)
        }
    }
}
