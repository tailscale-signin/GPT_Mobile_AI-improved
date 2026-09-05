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
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingRepositoryConfigBackupTest {

    @Test
    fun `exportConfigurationJson and importConfigurationJson roundtrip correctly`() = runBlocking {
        val dao = BackupFakePlatformV2Dao()
        val vault = BackupFakeSecretVault()
        val dataSource = BackupFakeSettingDataSource()
        val repository = SettingRepositoryImpl(
            settingDataSource = dataSource,
            platformV2Dao = dao,
            chatPlatformModelV2Dao = BackupFakeChatPlatformModelV2Dao(),
            secretVault = vault
        )

        // Seed initial platform
        val platform1 = PlatformV2(
            id = 1,
            uid = "p-1",
            name = "OpenAI Production",
            compatibleType = ClientType.OPENAI,
            enabled = true,
            apiUrl = "https://api.openai.com/v1",
            token = "sk-test-token-123",
            model = "gpt-4o",
            temperature = 0.7f,
            topP = 0.9f,
            topK = 50,
            maxTokens = 2048,
            stream = true,
            reasoning = true
        )
        repository.addPlatformV2(platform1)

        val json = repository.exportConfigurationJson()
        assertTrue(json.contains("OpenAI Production"))
        assertTrue(json.contains("gpt-4o"))
        assertTrue(json.contains("sk-test-token-123"))

        // Create clean repository instance with empty dao & vault
        val freshDao = BackupFakePlatformV2Dao()
        val freshVault = BackupFakeSecretVault()
        val freshDataSource = BackupFakeSettingDataSource()
        val freshRepo = SettingRepositoryImpl(
            settingDataSource = freshDataSource,
            platformV2Dao = freshDao,
            chatPlatformModelV2Dao = BackupFakeChatPlatformModelV2Dao(),
            secretVault = freshVault
        )

        val importResult = freshRepo.importConfigurationJson(json)
        assertTrue(importResult.isSuccess)
        assertEquals(1, importResult.getOrThrow())

        val importedPlatforms = freshRepo.fetchPlatformV2s()
        assertEquals(1, importedPlatforms.size)
        val imported = importedPlatforms.single()
        assertEquals("OpenAI Production", imported.name)
        assertEquals(ClientType.OPENAI, imported.compatibleType)
        assertEquals("gpt-4o", imported.model)
        assertEquals("sk-test-token-123", imported.token)
        assertEquals(0.7f, imported.temperature ?: 0f, 0.001f)
        assertEquals(0.9f, imported.topP ?: 0f, 0.001f)
        assertEquals(true, imported.reasoning)
    }

    @Test
    fun `importConfigurationJson updates existing platform matching name case-insensitively`() = runBlocking {
        val dao = BackupFakePlatformV2Dao()
        val vault = BackupFakeSecretVault()
        val dataSource = BackupFakeSettingDataSource()
        val repository = SettingRepositoryImpl(
            settingDataSource = dataSource,
            platformV2Dao = dao,
            chatPlatformModelV2Dao = BackupFakeChatPlatformModelV2Dao(),
            secretVault = vault
        )

        val initialPlatform = PlatformV2(
            id = 1,
            uid = "p-1",
            name = "Local Ollama",
            compatibleType = ClientType.OLLAMA,
            enabled = true,
            apiUrl = "http://10.0.2.2:11434",
            token = null,
            model = "llama3:latest"
        )
        repository.addPlatformV2(initialPlatform)

        val updatedConfigJson = """
            {
                "version": 1,
                "exportedAt": 1700000000000,
                "platforms": [
                    {
                        "name": "local ollama",
                        "compatibleType": 4,
                        "enabled": true,
                        "apiUrl": "http://192.168.1.100:11434",
                        "token": "",
                        "model": "deepseek-r1:8b"
                    }
                ]
            }
        """.trimIndent()

        val importResult = repository.importConfigurationJson(updatedConfigJson)
        assertTrue(importResult.isSuccess)
        assertEquals(1, importResult.getOrThrow())

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

    override suspend fun getPlatform(id: Int): PlatformV2? = platforms.firstOrNull { it.id == id }

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
