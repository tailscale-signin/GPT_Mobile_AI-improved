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
import org.junit.Assert.fail
import org.junit.Test

class SettingRepositorySecretMigrationTest {

    @Test
    fun `migrating unmigrated platforms encrypts tokens and updates secret refs`() = runBlocking {
        val dao = FakePlatformV2Dao(
            mutableListOf(
                testPlatform(id = 1, uid = "profile-1", token = "first-secret"),
                testPlatform(id = 2, uid = "profile-2", token = "second-secret")
            )
        )
        val vault = FakeSecretVault()
        val repository = createRepository(dao, vault)

        val errors = repository.migrateSecrets()

        assertTrue(errors.isEmpty())
        assertEquals(listOf("first-secret", "second-secret"), repository.fetchPlatformV2s().map { it.token })
        assertEquals(2, dao.platforms.mapNotNull { it.secretRef }.distinct().size)
    }

    @Test
    fun `explicitly clearing a profile token removes its vault record and reference`() = runBlocking {
        val dao = FakePlatformV2Dao()
        val vault = FakeSecretVault()
        val repository = createRepository(dao, vault)
        repository.addPlatformV2(testPlatform(token = "profile-secret"))

        repository.updatePlatformV2(repository.fetchPlatformV2s().single().copy(token = null))

        assertNull(dao.platforms.single().token)
        assertNull(dao.platforms.single().secretRef)
        assertNull(vault.values["profile_profile-1"])
    }

    @Test
    fun `failed profile clear keeps the existing vault credential`() = runBlocking {
        val dao = FakePlatformV2Dao()
        val vault = FakeSecretVault()
        val repository = createRepository(dao, vault)
        repository.addPlatformV2(testPlatform(token = "profile-secret"))
        dao.failEdits = true

        try {
            repository.updatePlatformV2(repository.fetchPlatformV2s().single().copy(token = null))
            fail("Expected the database update to fail")
        } catch (_: IllegalStateException) {
            // Expected.
        }

        assertEquals("profile-secret", vault.values.getValue("profile_profile-1").decodeToString())
        assertEquals("profile_profile-1", dao.platforms.single().secretRef)
    }

    @Test
    fun `room plaintext migration failure is recoverable and retains plaintext`() = runBlocking {
        val original = testPlatform(token = "keep-me")
        val dao = FakePlatformV2Dao(mutableListOf(original))
        val vault = FakeSecretVault(readOverride = "different".encodeToByteArray())
        val repository = createRepository(dao, vault)

        val errors = repository.migrateSecrets()

        assertEquals(1, errors.size)
        assertEquals("profile:profile-1", errors.single().source)
        assertEquals(original, dao.platforms.single())
    }

    @Test
    fun `legacy datastore migration clears token only after verification and remains readable`() = runBlocking {
        val dataSource = FakeSettingDataSource(mutableMapOf(ApiType.OPENAI to "legacy-datastore-secret"))
        val vault = FakeSecretVault()
        val repository = createRepository(FakePlatformV2Dao(), vault, dataSource)

        val errors = repository.migrateSecrets()

        assertTrue(errors.isEmpty())
        assertNull(dataSource.tokens[ApiType.OPENAI])
        assertEquals("legacy-datastore-secret", repository.fetchPlatforms().first { it.name == ApiType.OPENAI }.token)
    }

    private fun createRepository(
        platformDao: PlatformV2Dao,
        vault: SecretVault,
        dataSource: SettingDataSource = FakeSettingDataSource(),
        chatPlatformModelDao: ChatPlatformModelV2Dao = FakeChatPlatformModelV2Dao()
    ): SettingRepositoryImpl = SettingRepositoryImpl(
        settingDataSource = dataSource,
        platformV2Dao = platformDao,
        chatPlatformModelV2Dao = chatPlatformModelDao,
        secretVault = vault
    )

    private fun testPlatform(
        id: Int = 1,
        uid: String = "profile-1",
        token: String? = null,
        secretRef: String? = null
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
    override suspend fun upsertAll(vararg models: ChatPlatformModelV2) = Unit
    override suspend fun deleteByChatId(chatId: Int) = Unit
    override suspend fun deleteByPlatformUid(platformUid: String) = Unit
}

private class FakeSettingDataSource(
    val tokens: MutableMap<ApiType, String> = mutableMapOf(),
    var localRuntimeBackend: LocalRuntimeBackend = LocalRuntimeBackend.QUALCOMM_QNN
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
}
