package com.example.gptmobileai.ui.components

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * Repository providing persistent state and data flow for [PlatformConfiguration].
 */
interface PlatformSettingsRepository {
    fun getConfiguration(platform: AIPlatform): Flow<PlatformConfiguration>
    suspend fun saveConfiguration(config: PlatformConfiguration)
    suspend fun getAllConfigurations(): List<PlatformConfiguration>
}

class InMemoryPlatformSettingsRepository : PlatformSettingsRepository {
    private val configs = MutableStateFlow<Map<AIPlatform, PlatformConfiguration>>(
        AIPlatform.entries.associateWith { PlatformConfiguration(platform = it) }
    )

    override fun getConfiguration(platform: AIPlatform): Flow<PlatformConfiguration> {
        val flow = MutableStateFlow(configs.value[platform] ?: PlatformConfiguration(platform = platform))
        return flow.asStateFlow()
    }

    override suspend fun saveConfiguration(config: PlatformConfiguration) {
        configs.update { current ->
            current + (config.platform to config)
        }
    }

    override suspend fun getAllConfigurations(): List<PlatformConfiguration> {
        return configs.value.values.toList()
    }
}
