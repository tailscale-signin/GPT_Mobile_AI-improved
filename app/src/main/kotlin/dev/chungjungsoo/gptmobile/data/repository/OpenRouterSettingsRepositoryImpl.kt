package dev.chungjungsoo.gptmobile.data.repository

import dev.chungjungsoo.gptmobile.data.database.dao.PlatformV2Dao
import dev.chungjungsoo.gptmobile.data.datastore.SettingDataSource
import dev.chungjungsoo.gptmobile.data.model.ClientType
import dev.chungjungsoo.gptmobile.domain.model.OpenRouterSettings
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class OpenRouterSettingsRepositoryImpl @Inject constructor(
    private val settingDataSource: SettingDataSource,
    private val platformV2Dao: PlatformV2Dao
) : OpenRouterSettingsRepository {

    private var inMemorySettings = OpenRouterSettings(apiKey = "")

    override suspend fun loadSettings(): OpenRouterSettings {
        // Find existing OpenRouter platform from DB if available
        val openRouterPlatform = platformV2Dao.getPlatforms().firstOrNull {
            it.compatibleType == ClientType.OPENAI && (
                it.name.contains("OpenRouter", ignoreCase = true) ||
                    it.apiUrl.contains("openrouter", ignoreCase = true)
            )
        }

        val key = openRouterPlatform?.token ?: inMemorySettings.apiKey
        val baseUrl = openRouterPlatform?.apiUrl?.takeIf { it.isNotBlank() } ?: inMemorySettings.baseUrl

        return inMemorySettings.copy(
            apiKey = key,
            baseUrl = baseUrl
        )
    }

    override suspend fun saveSettings(settings: OpenRouterSettings) {
        inMemorySettings = settings
        // Update database if matching platform exists
        val openRouterPlatform = platformV2Dao.getPlatforms().firstOrNull {
            it.compatibleType == ClientType.OPENAI && (
                it.name.contains("OpenRouter", ignoreCase = true) ||
                    it.apiUrl.contains("openrouter", ignoreCase = true)
            )
        }

        if (openRouterPlatform != null) {
            val updated = openRouterPlatform.copy(
                token = settings.apiKey.takeIf { it.isNotBlank() },
                apiUrl = settings.baseUrl
            )
            platformV2Dao.editPlatform(updated)
        }
    }
}
