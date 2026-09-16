package dev.chungjungsoo.gptmobile.data.repository

import dev.chungjungsoo.gptmobile.data.localruntime.LocalRuntimeBackend
import kotlinx.coroutines.flow.Flow

interface SettingRepository {
    fun getLocalRuntimeBackend(): Flow<LocalRuntimeBackend>
    suspend fun setLocalRuntimeBackend(backend: LocalRuntimeBackend)
    fun getAutoContinueSettings(): Flow<AutoContinueSettings>
    suspend fun setAutoContinueSettings(settings: AutoContinueSettings)
}