package dev.chungjungsoo.gptmobile.data.repository

import dev.chungjungsoo.gptmobile.data.database.dao.ChatPlatformModelV2Dao
import dev.chungjungsoo.gptmobile.data.database.dao.PlatformV2Dao
import dev.chungjungsoo.gptmobile.data.database.entity.ChatPlatformModelV2
import dev.chungjungsoo.gptmobile.data.database.entity.PlatformV2
import dev.chungjungsoo.gptmobile.data.datastore.SettingDataSource
import dev.chungjungsoo.gptmobile.data.model.ApiType
import dev.chungjungsoo.gptmobile.data.model.ClientType
import dev.chungjungsoo.gptmobile.data.model.DynamicTheme
import dev.chungjungsoo.gptmobile.data.model.ThemeMode
import dev.chungjungsoo.gptmobile.data.security.SecretVault
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingRepositoryConfigBackupTest {

    @Test
    fun `export and import configuration round trip preserves platforms`() = runBlocking {
        val initialPlatform = PlatformV2(
            id = 1,
            uid = "p1",
            name = "OpenAI Production",
            compatibleType = ClientType.OPENAI,
            apiUrl = "https://api.openai.com/v1/",
            model = "gpt-4o",
            token = "sk-secret-token"
        )
        val platformDao = BackupFakePlatformV2Dao(mutableListOf(initialPlatform))
        val secretVault = BackupFakeSecretVault()
        val settingDataSource = BackupFakeSettingDataSource(
            dynamicTheme = DynamicTheme.OFF,
            themeMode = ThemeMode.DARK
        )
        val repository = SettingRepositoryImpl(
            settingDataSource = settingDataSource,
            platformV2Dao = platformDao,
            chatPlatformModelV2Dao = BackupFakeChatPlatformModelV2Dao(),
            secretVault = secretVault
        )

        val json = repository.exportConfigurationJson()
        assertTrue(json.contains("OpenAI Production"))

        // Clear state
        platformDao.platforms.clear()
        settingDataSource.themeMode = ThemeMode.LIGHT

        // Import
        val result = repository.importConfigurationJson(json)
        assertTrue(result.isSuccess)
        assertEquals(1, result.getOrNull())

        val platforms = repository.fetchPlatformV2s()
        assertEquals(1, platforms.size)
        assertEquals("OpenAI Production", platforms.first().name)
        assertEquals(ThemeMode.DARK, settingDataSource.themeMode)
    }

    @Test
    fun `import skips already existing platforms by matching API type`() = runBlocking {
        val existingPlatform = PlatformV2(
            id = 1,
            uid = "existing-p1",
            name = "Local Ollama",
            compatibleType = ClientType.OLLAMA,
            apiUrl = "http://192.168.1.100:11434",
            model = "llama3"
        )
        val platformDao = BackupFakePlatformV2Dao(mutableListOf(existingPlatform))
        val secretVault = BackupFakeSecretVault()
        val settingDataSource = BackupFakeSettingDataSource()
        val repository = SettingRepositoryImpl(
            settingDataSource = settingDataSource,
            platformV2Dao = platformDao,
            chatPlatformModelV2Dao = BackupFakeChatPlatformModelV2Dao(),
            secretVault = secretVault
        )

        val backupJson = """
            {
              "platforms": [
                {
                  "uid": "remote-p1",
                  "name": "local ollama",
                  "compatibleType": "OLLAMA",
                  "apiUrl": "http://192.168.1.100:11434",
                  "model": "deepseek-r1:8b"
                }
              ]
            }
        """.trimIndent()

        val result = repository.importConfigurationJson(backupJson)
        assertTrue(result.isSuccess)
        assertEquals(1, result.getOrNull())

        val platforms = repository.fetchPlatformV2s()
        assertEquals(1, platforms.size)
        val updated = platforms.single()
        assertEquals("Local Ollama", updated.name) // Name casing retained from existing entry
        assertEquals("http://192.168.1.100:11434", updated.apiUrl)
        assertEquals("deepseek-r1:8b", updated.model)
    }
}

private class BackupFakeSecretVault : SecretVault {
    val values = mutableMapOf<String, ByteArray>()

    override suspend fun put(secretRef: String, secret: ByteArray) {
        values[secretRef] = secret.copyOf()
    }

    override suspend fun read(secretRef: String): ByteArray? = values[secretRef]?.copyOf()

    override suspend fun delete(secretRef: String) {
        values.remove(secretRef)
    }
}

private class BackupFakePlatformV2Dao(
    val platforms: MutableList<PlatformV2> = mutableListOf()
) : PlatformV2Dao {
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
        val index = platforms.indexOfFirst { it.id == platform.id }
        if (index >= 0) platforms[index] = platform
    }

    override suspend fun deleteBindingsByProfileUid(profileUid: String) = Unit

    override suspend fun deletePlatformRow(platform: PlatformV2) {
        platforms.removeAll { it.id == platform.id }
    }
}

private class BackupFakeChatPlatformModelV2Dao : ChatPlatformModelV2Dao {
    override suspend fun getByChatId(chatId: Int): List<ChatPlatformModelV2> = emptyList()
    override suspend fun upsertAll(vararg models: ChatPlatformModelV2) = Unit
    override suspend fun deleteByChatId(chatId: Int) = Unit
    override suspend fun deleteByPlatformUid(platformUid: String) = Unit
}

private class BackupFakeSettingDataSource(
    var dynamicTheme: DynamicTheme? = null,
    var themeMode: ThemeMode? = null
) : SettingDataSource {
    override suspend fun getPreferencesSnapshot(): androidx.datastore.preferences.core.Preferences =
        androidx.datastore.preferences.core.emptyPreferences()

    override suspend fun updateDynamicTheme(theme: DynamicTheme) {
        dynamicTheme = theme
    }

    override suspend fun updateThemeMode(themeMode: ThemeMode) {
        this.themeMode = themeMode
    }

    override suspend fun updateStatus(apiType: ApiType, status: Boolean) = Unit
    override suspend fun updateAPIUrl(apiType: ApiType, url: String) = Unit
    override suspend fun updateToken(apiType: ApiType, token: String) = Unit
    override suspend fun clearToken(apiType: ApiType) = Unit
    override suspend fun updateModel(apiType: ApiType, model: String) = Unit
    override suspend fun updateTemperature(apiType: ApiType, temperature: Float) = Unit
    override suspend fun updateTopP(apiType: ApiType, topP: Float) = Unit
    override suspend fun updateSystemPrompt(apiType: ApiType, prompt: String) = Unit
    override suspend fun getDynamicTheme(): DynamicTheme? = dynamicTheme
    override suspend fun getThemeMode(): ThemeMode? = themeMode
    override suspend fun getStatus(apiType: ApiType): Boolean? = false
    override suspend fun getAPIUrl(apiType: ApiType): String? = null
    override suspend fun getToken(apiType: ApiType): String? = null
    override suspend fun getModel(apiType: ApiType): String? = null
    override suspend fun getTemperature(apiType: ApiType): Float? = null
    override suspend fun getTopP(apiType: ApiType): Float? = null
    override suspend fun getSystemPrompt(apiType: ApiType): String? = null
}
