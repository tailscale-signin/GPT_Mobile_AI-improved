package dev.chungjungsoo.gptmobile.data.repository

import dev.chungjungsoo.gptmobile.data.database.dao.ChatPlatformModelV2Dao
import dev.chungjungsoo.gptmobile.data.database.dao.PlatformV2Dao
import dev.chungjungsoo.gptmobile.data.database.entity.ChatPlatformModelV2
import dev.chungjungsoo.gptmobile.data.database.entity.PlatformV2
import dev.chungjungsoo.gptmobile.data.datastore.SettingDataSource
import dev.chungjungsoo.gptmobile.data.model.ApiType
import dev.chungjungsoo.gptmobile.data.model.ClientType
import dev.chungjungsoo.gptmobile.data.model.DynamicTheme
import dev.chungjungsoo.gptmobile.data.model.LocalRuntimeBackend
import dev.chungjungsoo.gptmobile.data.model.ThemeMode
import dev.chungjungsoo.gptmobile.data.security.SecretVault
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingRepositorySecretMigrationTest {

    @Test
    fun `provider key editor reads the shared pool and rejects missing secrets`() = runBlocking {
        val vault = FakeSecretVault()
        val connections = FakeProviderConnectionDao()
        val repository = SettingRepositoryImpl(FakeSettingDataSource(), FakePlatformV2Dao(mutableListOf()), connections, FakeChatPlatformModelV2Dao(), vault)
        val connection = repository.addProviderConnection(dev.chungjungsoo.gptmobile.data.database.entity.ProviderConnection(name = "Provider", compatibleType = ClientType.OPENAI), "key-one\nkey-two")
        assertEquals("key-one\nkey-two", repository.getProviderCredentials(connection.uid))
        vault.delete(connection.secretRef!!)
        assertTrue(runCatching { repository.getProviderCredentials(connection.uid) }.isFailure)
    }

    @Test
    fun `migrateSecrets moves legacy tokens to secret vault and clears legacy storage`() = runBlocking {
        val platformDao = FakePlatformV2Dao(
            mutableListOf(
                platform(id = 1, uid = "p1", token = "legacy-token-1", secretRef = null),
                platform(id = 2, uid = "p2", token = "legacy-token-2", secretRef = "existing-ref")
            )
        )
        val secretVault = FakeSecretVault()
        val settingDataSource = FakeSettingDataSource(
            tokens = mutableMapOf(
                ApiType.OPENAI to "legacy-openai-token",
                ApiType.ANTHROPIC to "legacy-claude-token"
            )
        )
        val repository = SettingRepositoryImpl(
            settingDataSource = settingDataSource,
            platformV2Dao = platformDao,
            providerConnectionDao = FakeProviderConnectionDao(),
            chatPlatformModelV2Dao = FakeChatPlatformModelV2Dao(),
            secretVault = secretVault
        )

        val errors = repository.migrateSecrets()

        assertTrue(errors.isEmpty())

        val p1 = platformDao.getPlatform(1)
        val p2 = platformDao.getPlatform(2)

        assertNull(p1?.token)
        assertTrue(p1?.secretRef?.isNotBlank() == true)
        assertEquals("legacy-token-1", secretVault.read(p1!!.secretRef!!)?.decodeToString())

        assertNull(p2?.token)
        assertEquals("legacy-token-2", secretVault.read("existing-ref")?.decodeToString())
        assertEquals("existing-ref", p2?.secretRef)

        assertNull(settingDataSource.getToken(ApiType.OPENAI))
        assertNull(settingDataSource.getToken(ApiType.ANTHROPIC))
        assertEquals("legacy-openai-token", secretVault.read("legacy_openai")?.decodeToString())
        assertEquals("legacy-claude-token", secretVault.read("legacy_anthropic")?.decodeToString())
    }

    @Test
    fun `migrateSecrets keeps legacy token when database update fails`() = runBlocking {
        val platformDao = FakePlatformV2Dao(
            mutableListOf(
                platform(id = 1, uid = "p1", token = "legacy-token-1", secretRef = null)
            )
        ).apply { failEdits = true }
        val secretVault = FakeSecretVault()
        val settingDataSource = FakeSettingDataSource()
        val repository = SettingRepositoryImpl(
            settingDataSource = settingDataSource,
            platformV2Dao = platformDao,
            providerConnectionDao = FakeProviderConnectionDao(),
            chatPlatformModelV2Dao = FakeChatPlatformModelV2Dao(),
            secretVault = secretVault
        )

        val errors = repository.migrateSecrets()

        assertEquals(1, errors.size)
        assertEquals("profile:p1", errors.first().source)

        val p1 = platformDao.getPlatform(1)
        assertEquals("legacy-token-1", p1?.token)
        assertNull(p1?.secretRef)
        // A failed database edit keeps both the plaintext reference and its verified vault copy for retry.
        assertEquals("legacy-token-1", secretVault.read("room_profile_1")?.decodeToString())
    }

    @Test
    fun `migrateToPlatformV2 is idempotent when profiles already exist`() = runBlocking {
        val platformDao = FakePlatformV2Dao(
            mutableListOf(
                platform(id = 1, uid = "p1", token = "", secretRef = "existing-ref")
            )
        )
        val secretVault = FakeSecretVault()
        val settingDataSource = FakeSettingDataSource()
        val repository = SettingRepositoryImpl(
            settingDataSource = settingDataSource,
            platformV2Dao = platformDao,
            providerConnectionDao = FakeProviderConnectionDao(),
            chatPlatformModelV2Dao = FakeChatPlatformModelV2Dao(),
            secretVault = secretVault
        )

        repository.migrateToPlatformV2()

        assertEquals(1, platformDao.platforms.size)
        assertEquals("p1", platformDao.platforms.first().uid)
    }

    private fun platform(
        id: Int,
        uid: String,
        token: String,
        secretRef: String?
    ): PlatformV2 = PlatformV2(
        id = id,
        uid = uid,
        name = "Test Platform",
        compatibleType = ClientType.OPENAI,
        apiUrl = "https://api.openai.com/v1/",
        model = "gpt-4o",
        token = token,
        secretRef = secretRef
    )
}

private class FakeSecretVault(
    val values: MutableMap<String, ByteArray> = mutableMapOf(),
    private val readOverride: ByteArray? = null
) : SecretVault {
    override suspend fun put(secretRef: String, secret: ByteArray) {
        values[secretRef] = secret.copyOf()
    }

    override suspend fun read(secretRef: String): ByteArray? =
        readOverride?.copyOf() ?: values[secretRef]?.copyOf()

    override suspend fun delete(secretRef: String) {
        values.remove(secretRef)?.fill(0)
    }
}

private class FakePlatformV2Dao(
    val platforms: MutableList<PlatformV2> = mutableListOf()
) : PlatformV2Dao {
    var failEdits = false

    override suspend fun getPlatforms(): List<PlatformV2> = platforms.toList()

    override fun observePlatforms(): Flow<List<PlatformV2>> = flowOf(platforms.toList())

    override suspend fun getPlatform(id: Int): PlatformV2? = platforms.firstOrNull { it.id == id }

    override suspend fun getPlatformByUid(uid: String): PlatformV2? = platforms.firstOrNull { it.uid == uid }

    override fun observePlatformByUid(uid: String): Flow<PlatformV2?> = flowOf(platforms.firstOrNull { it.uid == uid })

    override suspend fun addPlatform(platform: PlatformV2): Long {
        val persisted = if (platform.id == 0) platform.copy(id = (platforms.maxOfOrNull { it.id } ?: 0) + 1) else platform
        platforms += persisted
        return persisted.id.toLong()
    }

    override suspend fun updateFavorite(platformId: Int, isFavorite: Boolean) {
        val index = platforms.indexOfFirst { it.id == platformId }
        if (index >= 0) platforms[index] = platforms[index].copy(isFavorite = isFavorite)
    }

    override suspend fun updateLabels(platformId: Int, labels: String?) {
        val index = platforms.indexOfFirst { it.id == platformId }
        if (index >= 0) platforms[index] = platforms[index].copy(labels = labels)
    }

    override suspend fun editPlatform(platform: PlatformV2) {
        check(!failEdits) { "Database update failed." }
        val index = platforms.indexOfFirst { it.id == platform.id }
        if (index >= 0) platforms[index] = platform
    }

    override suspend fun deleteBindingsByProfileUid(profileUid: String) = Unit

    override suspend fun deletePlatformRow(platform: PlatformV2) {
        platforms.removeAll { it.id == platform.id }
    }
}

private class FakeChatPlatformModelV2Dao : ChatPlatformModelV2Dao {
    override suspend fun getByChatId(chatId: Int): List<ChatPlatformModelV2> = emptyList()
    override suspend fun getChatPlatformModels(): List<ChatPlatformModelV2> = emptyList()
    override suspend fun upsertAll(vararg models: ChatPlatformModelV2) = Unit
    override suspend fun upsertChatPlatformModel(model: ChatPlatformModelV2) = Unit
    override suspend fun deleteByChatId(chatId: Int) = Unit
    override suspend fun deleteByPlatformUid(platformUid: String) = Unit
}

private class FakeSettingDataSource(
    val tokens: MutableMap<ApiType, String> = mutableMapOf(),
    var localRuntimeBackend: LocalRuntimeBackend = LocalRuntimeBackend.QUALCOMM_QNN,
    var debugMode: Boolean = false,
    var favoriteGroups: List<String> = emptyList(),
    var favoriteMessageGroups: Map<Int, String> = emptyMap()
) : SettingDataSource {
    override suspend fun getPreferencesSnapshot(): androidx.datastore.preferences.core.Preferences =
        androidx.datastore.preferences.core.emptyPreferences()

    override suspend fun updateDynamicTheme(theme: DynamicTheme) = Unit
    override suspend fun updateThemeMode(themeMode: ThemeMode) = Unit
    override suspend fun updateStatus(apiType: ApiType, status: Boolean) = Unit
    override suspend fun updateAPIUrl(apiType: ApiType, url: String) = Unit
    override suspend fun updateToken(apiType: ApiType, token: String) {
        tokens[apiType] = token
    }

    override suspend fun clearToken(apiType: ApiType) {
        tokens.remove(apiType)
    }

    override suspend fun updateModel(apiType: ApiType, model: String) = Unit
    override suspend fun updateTemperature(apiType: ApiType, temperature: Float) = Unit
    override suspend fun updateTopP(apiType: ApiType, topP: Float) = Unit
    override suspend fun updateSystemPrompt(apiType: ApiType, prompt: String) = Unit
    override suspend fun getDynamicTheme(): DynamicTheme? = null
    override suspend fun getThemeMode(): ThemeMode? = null
    override suspend fun getStatus(apiType: ApiType): Boolean? = false
    override suspend fun getAPIUrl(apiType: ApiType): String? = null
    override suspend fun getToken(apiType: ApiType): String? = tokens[apiType]
    override suspend fun getModel(apiType: ApiType): String? = null
    override suspend fun getTemperature(apiType: ApiType): Float? = null
    override suspend fun getTopP(apiType: ApiType): Float? = null
    override suspend fun getSystemPrompt(apiType: ApiType): String? = null
    override suspend fun getLocalRuntimeBackend(): LocalRuntimeBackend = localRuntimeBackend
    override suspend fun updateLocalRuntimeBackend(backend: LocalRuntimeBackend) {
        localRuntimeBackend = backend
    }
    override suspend fun updateDebugMode(enabled: Boolean) {
        debugMode = enabled
    }
    override suspend fun getDebugMode(): Boolean = debugMode
    override fun observeDebugMode(): Flow<Boolean> = flowOf(debugMode)

    override suspend fun getFavoriteGroups(): List<String> = favoriteGroups
    override suspend fun saveFavoriteGroups(groups: List<String>) {
        favoriteGroups = groups
    }
    override fun observeFavoriteGroups(): Flow<List<String>> = flowOf(favoriteGroups)

    override suspend fun getFavoriteMessageGroups(): Map<Int, String> = favoriteMessageGroups
    override suspend fun saveFavoriteMessageGroups(messageGroups: Map<Int, String>) {
        favoriteMessageGroups = messageGroups
    }
    override fun observeFavoriteMessageGroups(): Flow<Map<Int, String>> = flowOf(favoriteMessageGroups)
}
