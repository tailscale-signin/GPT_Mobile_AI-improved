package dev.chungjungsoo.gptmobile.data.repository

import dev.chungjungsoo.gptmobile.domain.model.OpenRouterSettings

interface OpenRouterSettingsRepository {
    suspend fun loadSettings(): OpenRouterSettings
    suspend fun saveSettings(settings: OpenRouterSettings)
    suspend fun loadSettings(connectionUid: String): OpenRouterSettings
    suspend fun saveSettings(connectionUid: String, settings: OpenRouterSettings)
    suspend fun updateSettings(connectionUid: String, expectedRevision: Long, transform: (OpenRouterSettings) -> OpenRouterSettings): OpenRouterSettings
    suspend fun hasLegacyDefaults(connectionUid: String): Boolean
    suspend fun migrateLegacyDefaults(connectionUid: String)
}
