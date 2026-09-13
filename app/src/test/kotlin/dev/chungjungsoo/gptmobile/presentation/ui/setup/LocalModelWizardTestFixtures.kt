package dev.chungjungsoo.gptmobile.data.repository

import dev.chungjungsoo.gptmobile.data.database.entity.PlatformV2
import dev.chungjungsoo.gptmobile.data.dto.Platform
import dev.chungjungsoo.gptmobile.data.dto.ThemeSetting
import dev.chungjungsoo.gptmobile.data.model.ClientType
import dev.chungjungsoo.gptmobile.data.model.LocalRuntimeBackend
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

internal class RecordingSettingRepository : SettingRepository {
    val addedPlatforms = mutableListOf<PlatformV2>()
    var localRuntimeBackend: LocalRuntimeBackend = LocalRuntimeBackend.QUALCOMM_QNN

    override suspend fun fetchPlatforms(): List<Platform> = emptyList()

    override suspend fun fetchPlatformV2s(): List<PlatformV2> = emptyList()

    override fun observePlatformV2s(): Flow<List<PlatformV2>> = flowOf(emptyList())

    override fun observePlatformV2ByUid(uid: String): Flow<PlatformV2?> = flowOf(null)

    override suspend fun fetchThemes(): ThemeSetting = ThemeSetting()

    override suspend fun getLocalRuntimeBackend(): LocalRuntimeBackend = localRuntimeBackend

    override suspend fun updateLocalRuntimeBackend(backend: LocalRuntimeBackend) {
        localRuntimeBackend = backend
    }

    override suspend fun migrateToPlatformV2() = Unit

    override suspend fun migrateSecrets(): List<SecretMigrationError> = emptyList()

    override suspend fun updatePlatforms(platforms: List<Platform>) = Unit

    override suspend fun updateThemes(themeSetting: ThemeSetting) = Unit

    override suspend fun addPlatformV2(platform: PlatformV2) {
        addedPlatforms += platform
    }

    override suspend fun updatePlatformV2(platform: PlatformV2) = Unit

    override suspend fun deletePlatformV2(platform: PlatformV2) = Unit

    override suspend fun getPlatformV2ById(id: Int): PlatformV2? = null

    override suspend fun exportConfigurationJson(): String = "{}"

    override suspend fun importConfigurationJson(json: String): Result<Int> = Result.success(0)
}
