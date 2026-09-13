package dev.chungjungsoo.gptmobile.data.repository

import dev.chungjungsoo.gptmobile.data.database.entity.PlatformV2
import dev.chungjungsoo.gptmobile.data.dto.Platform
import dev.chungjungsoo.gptmobile.data.dto.ThemeSetting
import dev.chungjungsoo.gptmobile.data.model.LocalRuntimeBackend
import kotlinx.coroutines.flow.Flow

interface SettingRepository {
    suspend fun fetchPlatforms(): List<Platform>
    suspend fun fetchPlatformV2s(): List<PlatformV2>
    fun observePlatformV2s(): Flow<List<PlatformV2>>
    fun observePlatformV2ByUid(uid: String): Flow<PlatformV2?>
    suspend fun fetchThemes(): ThemeSetting
    suspend fun getLocalRuntimeBackend(): LocalRuntimeBackend
    suspend fun updateLocalRuntimeBackend(backend: LocalRuntimeBackend)
    suspend fun migrateToPlatformV2()
    suspend fun migrateSecrets(): List<SecretMigrationError>
    suspend fun updatePlatforms(platforms: List<Platform>)
    suspend fun updateThemes(themeSetting: ThemeSetting)

    // PlatformV2 CRUD operations
    suspend fun addPlatformV2(platform: PlatformV2)
    suspend fun updatePlatformV2(platform: PlatformV2)
    suspend fun deletePlatformV2(platform: PlatformV2)
    suspend fun getPlatformV2ById(id: Int): PlatformV2?

    // Backup & Restore
    suspend fun exportConfigurationJson(): String
    suspend fun importConfigurationJson(json: String): Result<Int>
}

data class SecretMigrationError(val source: String, val message: String)
