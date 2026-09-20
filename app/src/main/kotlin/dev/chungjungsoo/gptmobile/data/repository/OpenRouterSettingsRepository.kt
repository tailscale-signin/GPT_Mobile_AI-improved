package dev.chungjungsoo.gptmobile.data.repository

import dev.chungjungsoo.gptmobile.domain.model.OpenRouterSettings

interface OpenRouterSettingsRepository {
    suspend fun loadSettings(): OpenRouterSettings
    suspend fun saveSettings(settings: OpenRouterSettings)
}
