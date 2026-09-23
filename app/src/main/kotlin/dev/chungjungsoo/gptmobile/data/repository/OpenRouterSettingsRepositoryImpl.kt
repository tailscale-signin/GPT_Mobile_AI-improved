package dev.chungjungsoo.gptmobile.data.repository

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import dev.chungjungsoo.gptmobile.data.database.entity.PlatformV2
import dev.chungjungsoo.gptmobile.data.model.ClientType
import dev.chungjungsoo.gptmobile.data.security.SecretVault
import dev.chungjungsoo.gptmobile.domain.model.OpenRouterSettings
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.first
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

    override suspend fun loadSettings(): OpenRouterSettings {
        val saved = dataStore.data.first()[SETTINGS]?.let { encoded ->
            runCatching { json.decodeFromString<OpenRouterSettings>(encoded) }.getOrNull()
        } ?: OpenRouterSettings(apiKey = "")
        val platform = findPlatform()
        val key = platform?.token ?: secretVault.read(SECRET_REF)?.let { bytes ->
            try {
                bytes.decodeToString()
            } finally {
                bytes.fill(0)
            }
        }.orEmpty()
        return saved.copy(apiKey = key, baseUrl = platform?.apiUrl?.takeIf(String::isNotBlank) ?: saved.baseUrl)
    }

    override suspend fun saveSettings(settings: OpenRouterSettings) {
        val platform = findPlatform()
        if (platform != null) {
            settingRepository.updatePlatformV2(platform.copy(token = settings.apiKey.takeIf(String::isNotBlank), apiUrl = settings.baseUrl))
            secretVault.delete(SECRET_REF)
        } else if (settings.apiKey.isBlank()) {
            secretVault.delete(SECRET_REF)
        } else {
            val bytes = settings.apiKey.encodeToByteArray()
            try {
                secretVault.put(SECRET_REF, bytes)
            } finally {
                bytes.fill(0)
            }
        }
        // The API key stays in the device vault; ordinary preferences are portable.
        dataStore.edit { it[SETTINGS] = json.encodeToString(settings.copy(apiKey = "")) }
    }

    private suspend fun findPlatform(): PlatformV2? = settingRepository.fetchPlatformV2s().firstOrNull {
        it.compatibleType == ClientType.OPENROUTER ||
            (
                it.compatibleType == ClientType.OPENAI &&
                    (it.name.contains("OpenRouter", ignoreCase = true) || it.apiUrl.contains("openrouter", ignoreCase = true))
                )
    }

    private companion object {
        val SETTINGS = stringPreferencesKey("openrouter_batch_settings")
        const val SECRET_REF = "openrouter-settings"
    }
}
