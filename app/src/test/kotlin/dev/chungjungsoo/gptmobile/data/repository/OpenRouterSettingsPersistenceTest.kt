package dev.chungjungsoo.gptmobile.data.repository

import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import dev.chungjungsoo.gptmobile.data.database.entity.ProviderConnection
import dev.chungjungsoo.gptmobile.data.model.ClientType
import dev.chungjungsoo.gptmobile.data.openrouter.InMemoryRouterPreferences
import dev.chungjungsoo.gptmobile.data.security.SecretVault
import java.lang.reflect.Proxy
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class OpenRouterSettingsPersistenceTest {
    @Test fun connectionOwnershipNeverDependsOnListOrderAndPreferencesContainNoKey() = runBlocking {
        val store = InMemoryRouterPreferences()
        val repository = OpenRouterSettingsRepositoryImpl(store, settings("b", "a"), Vault())
        val original = repository.loadSettings("a")
        assertEquals("fixture-a", original.apiKey)
        val saved = repository.updateSettings("a", original.revision) { it.copy(batchSize = 42, apiKey = "must-not-save") }
        assertEquals("fixture-a", saved.apiKey)
        assertEquals(42, repository.loadSettings("a").batchSize)
        assertEquals(10, repository.loadSettings("b").batchSize)
        assertFalse(store.data.value.asMap().values.any { it.toString().contains("fixture-a") || it.toString().contains("must-not-save") })
    }

    @Test fun ambiguousLegacyEntryRequiresExplicitConnection() = runBlocking {
        val repository = OpenRouterSettingsRepositoryImpl(InMemoryRouterPreferences(), settings("a", "b"), Vault())
        try {
            repository.loadSettings()
            fail()
        } catch (_: IllegalStateException) { }
    }

    @Test fun staleDraftCannotOverwriteAnotherAppliedUpdate() = runBlocking {
        val repository = OpenRouterSettingsRepositoryImpl(InMemoryRouterPreferences(), settings("a"), Vault())
        repository.updateSettings("a", 0) { it.copy(batchSize = 23) }
        try {
            repository.updateSettings("a", 0) { it.copy(batchSize = 3) }
            fail()
        } catch (_: IllegalStateException) { }
        assertEquals(23, repository.loadSettings("a").batchSize)
    }

    @Test fun legacyDefaultsMigrateOnlyWhenOwnershipIsUnambiguous() = runBlocking {
        val store = InMemoryRouterPreferences()
        store.edit { it[stringPreferencesKey("openrouter_batch_settings")] = """{"apiKey":"legacy-plaintext","batchSize":27,"cacheTtlSeconds":0}""" }
        val repository = OpenRouterSettingsRepositoryImpl(store, settings("a", "b"), Vault())
        assertEquals(10, repository.loadSettings("a").batchSize)
        assertTrue(repository.hasLegacyDefaults("a"))
        repository.migrateLegacyDefaults("a")
        assertEquals(27, repository.loadSettings("a").batchSize)
        assertEquals(0, repository.loadSettings("a").cacheTtlSeconds)
        assertEquals("fixture-a", repository.loadSettings("a").apiKey)
        assertEquals(10, repository.loadSettings("b").batchSize)
        assertFalse(store.data.value.asMap().values.any { it.toString().contains("legacy-plaintext") })
    }
    private fun settings(vararg ids: String): SettingRepository {
        val connections = ids.map { ProviderConnection(it, it, ClientType.OPENROUTER, "https://openrouter.ai/api/v1") }
        return Proxy.newProxyInstance(SettingRepository::class.java.classLoader, arrayOf(SettingRepository::class.java)) { _, method, args ->
            when (method.name) {
                "fetchProviderConnections" -> connections
                "getProviderConnection" -> connections.find { it.uid == args[0] }
                "getProviderCredentials" -> "fixture-${args[0]}"
                else -> error("Unexpected repository operation: ${method.name}")
            }
        } as SettingRepository
    }
    private class Vault : SecretVault {
        override suspend fun put(secretRef: String, secret: ByteArray) {
            error("Settings must not write credentials")
        }
        override suspend fun read(secretRef: String): ByteArray? = null
        override suspend fun delete(secretRef: String) = Unit
    }
}
