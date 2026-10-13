package dev.chungjungsoo.gptmobile.data.repository

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import dev.chungjungsoo.gptmobile.data.model.ClientType
import dev.chungjungsoo.gptmobile.data.security.SecretVault
import dev.chungjungsoo.gptmobile.domain.model.OpenRouterSettings
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json

@Singleton
class OpenRouterSettingsRepositoryImpl @Inject constructor(
    private val dataStore: DataStore<Preferences>,
    private val settingRepository: SettingRepository,
    private val secretVault: SecretVault
) : OpenRouterSettingsRepository {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }
    private val lock = Mutex()
    private fun key(uid: String) = stringPreferencesKey("openrouter_connection_v2_$uid")
    private fun decode(encoded: String?) = encoded?.let { json.decodeFromString<OpenRouterSettings>(it) } ?: OpenRouterSettings(apiKey = "")
    private suspend fun onlyConnection(): String = settingRepository.fetchProviderConnections()
        .filter { it.compatibleType == ClientType.OPENROUTER }.singleOrNull()?.uid
        ?: error("Select an OpenRouter connection explicitly.")
    override suspend fun loadSettings(): OpenRouterSettings = loadSettings(onlyConnection())
    override suspend fun saveSettings(settings: OpenRouterSettings) = saveSettings(onlyConnection(), settings)

    override suspend fun loadSettings(connectionUid: String): OpenRouterSettings = lock.withLock { load(connectionUid) }
    private suspend fun load(uid: String): OpenRouterSettings {
        val connection = requireNotNull(settingRepository.getProviderConnection(uid)) { "Connection unavailable" }
        require(connection.compatibleType == ClientType.OPENROUTER)
        var preferences = dataStore.data.first()
        if (preferences[key(uid)] == null &&
            preferences[LEGACY] != null &&
            settingRepository.fetchProviderConnections().count { it.compatibleType == ClientType.OPENROUTER } == 1
        ) {
            migrate(uid)
            preferences = dataStore.data.first()
        }
        return normalize(decode(preferences[key(uid)])).copy(
            apiKey = settingRepository.getProviderCredentials(uid).orEmpty(),
            baseUrl = connection.apiUrl
        )
    }
    override suspend fun hasLegacyDefaults(connectionUid: String): Boolean {
        val preferences = dataStore.data.first()
        return preferences[LEGACY] != null && preferences[key(connectionUid)] == null
    }
    override suspend fun migrateLegacyDefaults(connectionUid: String) = lock.withLock {
        require(settingRepository.getProviderConnection(connectionUid)?.compatibleType == ClientType.OPENROUTER)
        migrate(connectionUid)
    }
    private suspend fun migrate(uid: String) {
        dataStore.edit { prefs ->
            if (prefs[key(uid)] == null) {
                val saved = normalize(decode(prefs[LEGACY])).copy(apiKey = "", revision = 1)
                prefs[key(uid)] = json.encodeToString(saved)
            }
            // Keep the old defaults as a rollback snapshot, but never retain a legacy plaintext key.
            prefs[LEGACY]?.let { prefs[LEGACY] = json.encodeToString(decode(it).copy(apiKey = "")) }
        }
    }
    override suspend fun saveSettings(connectionUid: String, settings: OpenRouterSettings) {
        updateSettings(connectionUid, settings.revision) { settings }
    }
    override suspend fun updateSettings(
        connectionUid: String,
        expectedRevision: Long,
        transform: (OpenRouterSettings) -> OpenRouterSettings
    ): OpenRouterSettings = lock.withLock {
        val current = load(connectionUid)
        check(current.revision == expectedRevision) { "Settings changed. Reload before applying." }
        // Credentials have their own explicit validated transaction in the workspace ViewModel.
        val updated = normalize(transform(current)).copy(apiKey = current.apiKey, baseUrl = current.baseUrl, revision = current.revision + 1)
        dataStore.edit { it[key(connectionUid)] = json.encodeToString(updated.copy(apiKey = "")) }
        updated
    }
    private fun normalize(value: OpenRouterSettings) = value.copy(
        batchSize = value.batchSize.coerceIn(1, 100),
        flushTimeoutMs = value.flushTimeoutMs.coerceIn(1000, 30000),
        maxRetries = value.maxRetries.coerceIn(0, 10),
        cacheTtlSeconds = value.cacheTtlSeconds.coerceIn(0, 86400),
        responseCacheTtlSeconds = value.responseCacheTtlSeconds.coerceIn(1, 86400),
        responseCachingEnabled = value.responseCachingEnabled && !value.noRemoteResponseStorage,
        schemaVersion = 2
    )
    private companion object {
        val LEGACY = stringPreferencesKey("openrouter_batch_settings")
    }
}
