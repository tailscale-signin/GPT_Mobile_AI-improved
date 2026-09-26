package dev.chungjungsoo.gptmobile.data.repository

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import dev.chungjungsoo.gptmobile.data.database.dao.PlatformV2Dao
import dev.chungjungsoo.gptmobile.data.database.entity.PlatformV2
import dev.chungjungsoo.gptmobile.data.database.entity.ProviderConnection
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

    @Test
    fun sharedConnectionKeyAndUrlReachAllLinkedProfilesAfterSaveAndReload() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            val store = PreferenceDataStoreFactory.create(scope = scope) { File(temp.root, "shared.preferences_pb") }
            val platformDao = mockk<PlatformV2Dao>()
            val profiles = listOf("first", "second").mapIndexed { index, uid ->
                PlatformV2(id = index + 1, uid = uid, name = uid, compatibleType = ClientType.OPENROUTER, providerConnectionUid = "shared")
            }
            coEvery { platformDao.getPlatforms() } returns profiles
            val connections = FakeProviderConnectionDao(
                listOf(
                    ProviderConnection("shared", "Router", ClientType.OPENROUTER, "https://old.example/v1", "shared-key"),
                    ProviderConnection("other", "Other", ClientType.OPENROUTER, "https://other.example/v1", "other-key")
                )
            )
            val vault = MemoryVault().apply {
                put("shared-key", "old-token".encodeToByteArray())
                put("other-key", "unrelated-token".encodeToByteArray())
            }
            val settings = SettingRepositoryImpl(mockk(), platformDao, connections, mockk(), vault)
            val repository = OpenRouterSettingsRepositoryImpl(store, settings, vault)
            assertEquals("old-token", repository.loadSettings().apiKey)
            repository.saveSettings(OpenRouterSettings(apiKey = "  replacement-token  ", baseUrl = " https://openrouter.ai/api/v1 "))
            assertEquals(listOf("replacement-token", "replacement-token"), settings.fetchPlatformV2s().map { it.token })
            assertEquals(listOf("https://openrouter.ai/api/v1", "https://openrouter.ai/api/v1"), settings.fetchPlatformV2s().map { it.apiUrl })
            assertEquals("replacement-token", OpenRouterSettingsRepositoryImpl(store, settings, vault).loadSettings().apiKey)
            assertEquals("unrelated-token", vault.read("other-key")?.decodeToString())
            assertFalse(store.data.first().asMap().values.any { it.toString().contains("replacement-token") })
            coVerify(exactly = 0) { platformDao.editPlatform(any()) }

            repository.saveSettings(OpenRouterSettings(apiKey = ""))
            assertEquals(listOf(null, null), settings.fetchPlatformV2s().map { it.token })
            vault.put("openrouter-settings", "stale-standalone-key".encodeToByteArray())
            assertEquals("", OpenRouterSettingsRepositoryImpl(store, settings, vault).loadSettings().apiKey)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun clearingALegacyKeyUsesAnExplicitEmptyCredential() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            val store = PreferenceDataStoreFactory.create(scope = scope) { File(temp.root, "legacy.preferences_pb") }
            val settings = mockk<SettingRepository>(relaxed = true)
            coEvery { settings.fetchPlatformV2s() } returns listOf(PlatformV2(id = 1, name = "Router", compatibleType = ClientType.OPENROUTER, token = "old", secretRef = "profile"))
            OpenRouterSettingsRepositoryImpl(store, settings, MemoryVault()).saveSettings(OpenRouterSettings(apiKey = ""))
            coVerify { settings.updatePlatformV2(match { it.token == "" }) }
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
