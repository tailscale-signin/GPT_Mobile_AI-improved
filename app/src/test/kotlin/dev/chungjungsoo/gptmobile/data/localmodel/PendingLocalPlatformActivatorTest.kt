package dev.chungjungsoo.gptmobile.data.localmodel

import dev.chungjungsoo.gptmobile.data.database.entity.PlatformV2
import dev.chungjungsoo.gptmobile.data.database.entity.ProviderConnection
import dev.chungjungsoo.gptmobile.data.dto.Platform
import dev.chungjungsoo.gptmobile.data.dto.ThemeSetting
import dev.chungjungsoo.gptmobile.data.model.ClientType
import dev.chungjungsoo.gptmobile.data.repository.FakeLocalModelRepository
import dev.chungjungsoo.gptmobile.data.repository.SecretMigrationError
import dev.chungjungsoo.gptmobile.data.repository.SettingRepository
import dev.chungjungsoo.gptmobile.presentation.ui.setup.wizardStoredModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class PendingLocalPlatformActivatorTest {

    @Test
    fun `model transitioning to READY enables matching disabled local platform`() = runTest {
        val localModels = FakeLocalModelRepository(
            listOf(wizardStoredModel("pending-model", LocalModelStatus.DOWNLOADING))
        )
        val settings = RecordingPlatformRepository(
            listOf(
                localPlatform("pending-local", "pending-model", enabled = false),
                localPlatform("other-local", "ready-model", enabled = false)
            )
        )
        val activator = PendingLocalPlatformActivator(
            localModelRepository = localModels,
            settingRepository = settings,
            scope = CoroutineScope(UnconfinedTestDispatcher())
        )

        activator.start()
        localModels.setModels(listOf(wizardStoredModel("pending-model", LocalModelStatus.READY)))

        assertEquals(listOf("pending-local"), settings.updatedPlatforms.map { it.name })
        assertTrue(settings.updatedPlatforms.single().enabled)
        assertFalse(settings.platforms.single { it.name == "other-local" }.enabled)
    }

    @Test
    fun `direct ready notification enables matching platforms even when already READY`() = runTest {
        val localModels = FakeLocalModelRepository(
            listOf(wizardStoredModel("ready-model", LocalModelStatus.READY))
        )
        val settings = RecordingPlatformRepository(
            listOf(localPlatform("pending-local", "ready-model", enabled = false))
        )
        val activator = PendingLocalPlatformActivator(
            localModelRepository = localModels,
            settingRepository = settings,
            scope = CoroutineScope(UnconfinedTestDispatcher())
        )

        activator.start()
        assertTrue(settings.updatedPlatforms.isEmpty())

        activator.onModelsBecameReady(setOf("ready-model"))

        assertEquals(listOf("pending-local"), settings.updatedPlatforms.map { it.name })
        assertTrue(settings.updatedPlatforms.single().enabled)
    }

    @Test
    fun `already READY at observation start does not enable user-disabled platforms`() = runTest {
        val localModels = FakeLocalModelRepository(
            listOf(wizardStoredModel("ready-model", LocalModelStatus.READY))
        )
        val settings = RecordingPlatformRepository(
            listOf(localPlatform("manual-off", "ready-model", enabled = false))
        )
        val activator = PendingLocalPlatformActivator(
            localModelRepository = localModels,
            settingRepository = settings,
            scope = CoroutineScope(UnconfinedTestDispatcher())
        )

        activator.start()

        assertTrue(settings.updatedPlatforms.isEmpty())
        assertFalse(settings.platforms.single().enabled)
    }

    private fun localPlatform(
        name: String,
        model: String,
        enabled: Boolean
    ) = PlatformV2(
        name = name,
        compatibleType = ClientType.LITERT_LM,
        enabled = enabled,
        apiUrl = "",
        model = model
    )
}

private class RecordingPlatformRepository(
    initial: List<PlatformV2>
) : SettingRepository {
    var platforms = initial.toMutableList()
    val updatedPlatforms = mutableListOf<PlatformV2>()

    override suspend fun fetchProviderConnections(): List<ProviderConnection> = emptyList()
    override fun observeProviderConnections(): Flow<List<ProviderConnection>> = kotlinx.coroutines.flow.flowOf(emptyList())
    override suspend fun getProviderConnection(uid: String): ProviderConnection? = null
    override suspend fun addProviderConnection(connection: ProviderConnection, credential: String?): ProviderConnection = error("Provider writes are not used by this fixture")
    override suspend fun updateProviderConnection(connection: ProviderConnection, credential: String?): ProviderConnection = error("Provider writes are not used by this fixture")
    override suspend fun deleteProviderConnection(connection: ProviderConnection): Boolean = error("Provider writes are not used by this fixture")

    override suspend fun fetchPlatforms(): List<Platform> = emptyList()

    override suspend fun fetchPlatformV2s(): List<PlatformV2> = platforms.toList()

    override fun observePlatformV2s(): Flow<List<PlatformV2>> = emptyFlow()

    override fun observePlatformV2ByUid(uid: String): Flow<PlatformV2?> = emptyFlow()

    override suspend fun fetchThemes(): ThemeSetting = ThemeSetting()

    override suspend fun getLocalRuntimeBackend() =
        dev.chungjungsoo.gptmobile.data.model.LocalRuntimeBackend.QUALCOMM_QNN

    override suspend fun updateLocalRuntimeBackend(
        backend: dev.chungjungsoo.gptmobile.data.model.LocalRuntimeBackend
    ) = Unit

    override suspend fun getDebugMode(): Boolean = false

    override suspend fun updateDebugMode(enabled: Boolean) = Unit

    override fun observeDebugMode(): Flow<Boolean> = emptyFlow()

    override suspend fun getFavoriteGroups(): List<String> = emptyList()

    override suspend fun saveFavoriteGroups(groups: List<String>) = Unit

    override fun observeFavoriteGroups(): Flow<List<String>> = emptyFlow()

    override suspend fun getFavoriteMessageGroups(): Map<Int, String> = emptyMap()

    override suspend fun saveFavoriteMessageGroups(messageGroups: Map<Int, String>) = Unit

    override fun observeFavoriteMessageGroups(): Flow<Map<Int, String>> = emptyFlow()

    override suspend fun migrateToPlatformV2() = Unit

    override suspend fun migrateSecrets(): List<SecretMigrationError> = emptyList()

    override suspend fun updatePlatforms(platforms: List<Platform>) = Unit

    override suspend fun updateThemes(themeSetting: ThemeSetting) = Unit

    override suspend fun addPlatformV2(platform: PlatformV2) {
        platforms += platform
    }

    override suspend fun updatePlatformV2(platform: PlatformV2) {
        platforms = platforms.map { if (it.uid == platform.uid) platform else it }.toMutableList()
        updatedPlatforms += platform
    }

    override suspend fun deletePlatformV2(platform: PlatformV2) = Unit

    override suspend fun getPlatformV2ById(id: Int): PlatformV2? = null

    override suspend fun exportConfigurationJson(): String = "{}"

    override suspend fun importConfigurationJson(json: String): Result<Int> = Result.success(0)
}
