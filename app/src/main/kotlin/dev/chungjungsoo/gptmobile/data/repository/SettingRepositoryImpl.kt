package dev.chungjungsoo.gptmobile.data.repository

import dev.chungjungsoo.gptmobile.data.ModelConstants
import dev.chungjungsoo.gptmobile.data.database.dao.ChatPlatformModelV2Dao
import dev.chungjungsoo.gptmobile.data.database.dao.PlatformV2Dao
import dev.chungjungsoo.gptmobile.data.database.entity.PlatformV2
import dev.chungjungsoo.gptmobile.data.datastore.SettingDataSource
import dev.chungjungsoo.gptmobile.data.datastore.SettingDataSourceImpl
import dev.chungjungsoo.gptmobile.data.dto.ConfigBackupDto
import dev.chungjungsoo.gptmobile.data.dto.Platform
import dev.chungjungsoo.gptmobile.data.dto.PlatformBackupDto
import dev.chungjungsoo.gptmobile.data.dto.ThemeBackupDto
import dev.chungjungsoo.gptmobile.data.dto.ThemeSetting
import dev.chungjungsoo.gptmobile.data.model.ApiType
import dev.chungjungsoo.gptmobile.data.model.ClientType
import dev.chungjungsoo.gptmobile.data.model.DynamicTheme
import dev.chungjungsoo.gptmobile.data.model.ThemeMode
import dev.chungjungsoo.gptmobile.data.security.SecretVault
import java.util.concurrent.atomic.AtomicReference
import javax.inject.Inject
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

class SettingRepositoryImpl @Inject constructor(
    private val settingDataSource: SettingDataSource,
    private val platformV2Dao: PlatformV2Dao,
    private val chatPlatformModelV2Dao: ChatPlatformModelV2Dao,
    private val secretVault: SecretVault
) : SettingRepository {

    private val jsonSerializer = Json {
        ignoreUnknownKeys = true
        prettyPrint = true
        encodeDefaults = true
    }

    // High-performance thread-safe in-memory cache for resolved PlatformV2 list
    private val platformV2Cache = AtomicReference<List<PlatformV2>?>(null)

    private fun invalidatePlatformCache() {
        platformV2Cache.set(null)
    }

    override suspend fun fetchPlatforms(): List<Platform> {
        val pref = settingDataSource.getPreferencesSnapshot()
        val dsImpl = settingDataSource as? SettingDataSourceImpl

        return ApiType.entries.map { apiType ->
            val status = if (dsImpl != null) {
                pref[dsImpl.apiStatusMap[apiType]!!]
            } else {
                settingDataSource.getStatus(apiType)
            }

            val rawUrl = if (dsImpl != null) {
                pref[dsImpl.apiUrlMap[apiType]!!]
            } else {
                settingDataSource.getAPIUrl(apiType)
            }

            val apiUrl = when (apiType) {
                ApiType.OPENAI -> rawUrl ?: ModelConstants.OPENAI_API_URL
                ApiType.ANTHROPIC -> rawUrl ?: ModelConstants.ANTHROPIC_API_URL
                ApiType.GOOGLE -> rawUrl ?: ModelConstants.GOOGLE_API_URL
                ApiType.GROQ -> rawUrl ?: ModelConstants.GROQ_API_URL
                ApiType.OLLAMA -> rawUrl ?: ""
            }

            val token = resolveLegacyToken(apiType)

            val model = if (dsImpl != null) {
                pref[dsImpl.apiModelMap[apiType]!!]
            } else {
                settingDataSource.getModel(apiType)
            }

            val temperature = if (dsImpl != null) {
                pref[dsImpl.apiTemperatureMap[apiType]!!]
            } else {
                settingDataSource.getTemperature(apiType)
            }

            val topP = if (dsImpl != null) {
                pref[dsImpl.apiTopPMap[apiType]!!]
            } else {
                settingDataSource.getTopP(apiType)
            }

            val rawPrompt = if (dsImpl != null) {
                pref[dsImpl.apiSystemPromptMap[apiType]!!]
            } else {
                settingDataSource.getSystemPrompt(apiType)
            }

            val systemPrompt = when (apiType) {
                ApiType.OPENAI -> rawPrompt ?: ModelConstants.OPENAI_PROMPT
                else -> rawPrompt ?: ModelConstants.DEFAULT_PROMPT
            }

            Platform(
                name = apiType,
                enabled = status == true,
                apiUrl = apiUrl,
                token = token,
                model = model,
                temperature = temperature,
                topP = topP,
                systemPrompt = systemPrompt
            )
        }
    }

    override suspend fun fetchPlatformV2s(): List<PlatformV2> {
        val cached = platformV2Cache.get()
        if (cached != null) return cached

        val resolved = platformV2Dao.getPlatforms().map { platform ->
            resolvePlatformToken(platform)
        }
        platformV2Cache.set(resolved)
        return resolved
    }

    override suspend fun fetchThemes(): ThemeSetting = ThemeSetting(
        dynamicTheme = settingDataSource.getDynamicTheme() ?: DynamicTheme.OFF,
        themeMode = settingDataSource.getThemeMode() ?: ThemeMode.SYSTEM
    )

    override suspend fun migrateToPlatformV2() {
        val leftOverPlatformV2s = fetchPlatformV2s()
        leftOverPlatformV2s.forEach { deletePlatformV2(it) }

        val platforms = fetchPlatforms()

        platforms.forEach { platform ->
            addPlatformV2(
                PlatformV2(
                    name = when (platform.name) {
                        ApiType.OPENAI -> "OpenAI"
                        ApiType.ANTHROPIC -> "Anthropic"
                        ApiType.GOOGLE -> "Google"
                        ApiType.GROQ -> "Groq"
                        ApiType.OLLAMA -> "Ollama"
                    },
                    compatibleType = when (platform.name) {
                        ApiType.OPENAI -> ClientType.OPENAI
                        ApiType.ANTHROPIC -> ClientType.ANTHROPIC
                        ApiType.GOOGLE -> ClientType.GOOGLE
                        ApiType.GROQ -> ClientType.GROQ
                        ApiType.OLLAMA -> ClientType.OLLAMA
                    },
                    enabled = platform.enabled,
                    apiUrl = ModelConstants.normalizeLegacyAPIUrl(platform.apiUrl),
                    token = platform.token,
                    model = platform.model ?: "",
                    temperature = platform.temperature,
                    topP = platform.topP,
                    systemPrompt = platform.systemPrompt,
                    stream = true,
                    reasoning = false
                )
            )
        }
        invalidatePlatformCache()
    }

    override suspend fun migrateSecrets(): List<SecretMigrationError> = buildList {
        platformV2Dao.getPlatforms().forEach { platform ->
            val plaintext = platform.token ?: return@forEach
            val source = "profile:${platform.uid}"
            try {
                val secretRef = platform.secretRef ?: migratedProfileSecretRef(platform)
                storeVerified(secretRef, plaintext)
                platformV2Dao.editPlatform(platform.copy(token = null, secretRef = secretRef))
            } catch (error: Exception) {
                add(SecretMigrationError(source, error.message ?: "Credential migration failed."))
            }
        }

        ApiType.entries.forEach { apiType ->
            val plaintext = settingDataSource.getToken(apiType) ?: return@forEach
            val source = "legacy:${apiType.name}"
            try {
                storeVerified(legacySecretRef(apiType), plaintext)
                settingDataSource.clearToken(apiType)
            } catch (error: Exception) {
                add(SecretMigrationError(source, error.message ?: "Credential migration failed."))
            }
        }
        invalidatePlatformCache()
    }

    override suspend fun updatePlatforms(platforms: List<Platform>) {
        platforms.forEach { platform ->
            settingDataSource.updateStatus(platform.name, platform.enabled)
            settingDataSource.updateAPIUrl(platform.name, platform.apiUrl)

            platform.token?.let { token ->
                if (token.isBlank()) {
                    secretVault.delete(legacySecretRef(platform.name))
                    settingDataSource.clearToken(platform.name)
                } else {
                    storeVerified(legacySecretRef(platform.name), token)
                    settingDataSource.clearToken(platform.name)
                }
            }
            platform.model?.let { settingDataSource.updateModel(platform.name, it) }
            platform.temperature?.let { settingDataSource.updateTemperature(platform.name, it) }
            platform.topP?.let { settingDataSource.updateTopP(platform.name, it) }
            platform.systemPrompt?.let { settingDataSource.updateSystemPrompt(platform.name, it.trim()) }
        }
    }

    override suspend fun updateThemes(themeSetting: ThemeSetting) {
        settingDataSource.updateDynamicTheme(themeSetting.dynamicTheme)
        settingDataSource.updateThemeMode(themeSetting.themeMode)
    }

    override suspend fun addPlatformV2(platform: PlatformV2) {
        platformV2Dao.addPlatform(securePlatform(platform))
        invalidatePlatformCache()
    }

    override suspend fun updatePlatformV2(platform: PlatformV2) {
        val previousSecretRef = platform.secretRef
            ?: platform.id.takeIf { it > 0 }?.let { platformV2Dao.getPlatform(it)?.secretRef }
        val securedPlatform = securePlatform(platform)
        platformV2Dao.editPlatform(securedPlatform)
        if (previousSecretRef != securedPlatform.secretRef) {
            previousSecretRef?.let { secretVault.delete(it) }
        }
        invalidatePlatformCache()
    }

    override suspend fun deletePlatformV2(platform: PlatformV2) {
        val secretRef = platform.secretRef
            ?: platform.id.takeIf { it > 0 }?.let { platformV2Dao.getPlatform(it)?.secretRef }
        chatPlatformModelV2Dao.deleteByPlatformUid(platform.uid)
        platformV2Dao.deletePlatform(platform)
        secretRef?.let { secretVault.delete(it) }
        invalidatePlatformCache()
    }

    override suspend fun getPlatformV2ById(id: Int): PlatformV2? {
        val cached = platformV2Cache.get()
        if (cached != null) {
            val hit = cached.firstOrNull { it.id == id }
            if (hit != null) return hit
        }
        return platformV2Dao.getPlatform(id)?.let { platform ->
            resolvePlatformToken(platform)
        }
    }

    override suspend fun exportConfigurationJson(): String {
        val currentPlatforms = fetchPlatformV2s()
        val currentThemes = fetchThemes()

        val backup = ConfigBackupDto(
            version = 1,
            exportedAt = System.currentTimeMillis(),
            theme = ThemeBackupDto(
                dynamicTheme = currentThemes.dynamicTheme == DynamicTheme.ON,
                themeMode = currentThemes.themeMode.ordinal
            ),
            platforms = currentPlatforms.map { p ->
                PlatformBackupDto(
                    name = p.name,
                    compatibleType = p.compatibleType.ordinal,
                    enabled = p.enabled,
                    apiUrl = p.apiUrl,
                    token = p.token ?: "",
                    model = p.model,
                    temperature = p.temperature,
                    topP = p.topP,
                    topK = p.topK,
                    maxTokens = p.maxTokens,
                    accelerator = p.accelerator,
                    systemPrompt = p.systemPrompt,
                    stream = p.stream,
                    reasoning = p.reasoning,
                    timeout = p.timeout,
                    harassmentSafetyThreshold = p.harassmentSafetyThreshold,
                    hateSpeechSafetyThreshold = p.hateSpeechSafetyThreshold,
                    sexuallyExplicitSafetyThreshold = p.sexuallyExplicitSafetyThreshold,
                    dangerousContentSafetyThreshold = p.dangerousContentSafetyThreshold
                )
            }
        )

        return jsonSerializer.encodeToString(backup)
    }

    override suspend fun importConfigurationJson(json: String): Result<Int> = runCatching {
        val backup = jsonSerializer.decodeFromString<ConfigBackupDto>(json)

        backup.theme?.let { themeDto ->
            val dynamicTheme = if (themeDto.dynamicTheme) DynamicTheme.ON else DynamicTheme.OFF
            val themeMode = ThemeMode.getByValue(themeDto.themeMode) ?: ThemeMode.SYSTEM
            updateThemes(ThemeSetting(dynamicTheme = dynamicTheme, themeMode = themeMode))
        }

        var importedCount = 0
        val existingPlatforms = platformV2Dao.getPlatforms()

        backup.platforms.forEach { pDto ->
            val clientType = ClientType.entries.getOrNull(pDto.compatibleType) ?: ClientType.OPENAI
            val existing = existingPlatforms.firstOrNull { it.name.equals(pDto.name, ignoreCase = true) }

            if (existing != null) {
                val updated = existing.copy(
                    compatibleType = clientType,
                    enabled = pDto.enabled,
                    apiUrl = pDto.apiUrl,
                    token = pDto.token.ifBlank { null },
                    model = pDto.model,
                    temperature = pDto.temperature,
                    topP = pDto.topP,
                    topK = pDto.topK,
                    maxTokens = pDto.maxTokens,
                    accelerator = pDto.accelerator,
                    systemPrompt = pDto.systemPrompt,
                    stream = pDto.stream,
                    reasoning = pDto.reasoning,
                    timeout = pDto.timeout,
                    harassmentSafetyThreshold = pDto.harassmentSafetyThreshold,
                    hateSpeechSafetyThreshold = pDto.hateSpeechSafetyThreshold,
                    sexuallyExplicitSafetyThreshold = pDto.sexuallyExplicitSafetyThreshold,
                    dangerousContentSafetyThreshold = pDto.dangerousContentSafetyThreshold
                )
                updatePlatformV2(updated)
            } else {
                val newPlatform = PlatformV2(
                    name = pDto.name,
                    compatibleType = clientType,
                    enabled = pDto.enabled,
                    apiUrl = pDto.apiUrl,
                    token = pDto.token.ifBlank { null },
                    model = pDto.model,
                    temperature = pDto.temperature,
                    topP = pDto.topP,
                    topK = pDto.topK,
                    maxTokens = pDto.maxTokens,
                    accelerator = pDto.accelerator,
                    systemPrompt = pDto.systemPrompt,
                    stream = pDto.stream,
                    reasoning = pDto.reasoning,
                    timeout = pDto.timeout,
                    harassmentSafetyThreshold = pDto.harassmentSafetyThreshold,
                    hateSpeechSafetyThreshold = pDto.hateSpeechSafetyThreshold,
                    sexuallyExplicitSafetyThreshold = pDto.sexuallyExplicitSafetyThreshold,
                    dangerousContentSafetyThreshold = pDto.dangerousContentSafetyThreshold
                )
                addPlatformV2(newPlatform)
            }
            importedCount++
        }

        invalidatePlatformCache()
        importedCount
    }

    private suspend fun securePlatform(platform: PlatformV2): PlatformV2 {
        val secret = platform.token
        if (secret == null) {
            return platform.copy(token = null, secretRef = null)
        }

        val secretRef = platform.secretRef ?: profileSecretRef(platform.uid)
        storeVerified(secretRef, secret)
        return platform.copy(token = null, secretRef = secretRef)
    }

    private suspend fun resolvePlatformToken(platform: PlatformV2): PlatformV2 {
        if (platform.token != null) return platform
        val secretRef = platform.secretRef ?: return platform
        return platform.copy(token = readSecret(secretRef))
    }

    private suspend fun resolveLegacyToken(apiType: ApiType): String? = settingDataSource.getToken(apiType)
        ?: readSecret(legacySecretRef(apiType))

    private suspend fun readSecret(secretRef: String): String? {
        val bytes = secretVault.read(secretRef) ?: return null
        return try {
            bytes.decodeToString()
        } finally {
            bytes.fill(0)
        }
    }

    private suspend fun storeVerified(secretRef: String, secret: String) {
        val bytes = secret.encodeToByteArray()
        try {
            secretVault.put(secretRef, bytes)
            val verified = secretVault.read(secretRef)
            try {
                check(verified != null && verified.contentEquals(bytes)) { "Credential verification failed." }
            } finally {
                verified?.fill(0)
            }
        } finally {
            bytes.fill(0)
        }
    }

    private fun profileSecretRef(uid: String): String = "profile_$uid"

    private fun migratedProfileSecretRef(platform: PlatformV2): String = platform.id.takeIf { it > 0 }?.let { "room_profile_$it" } ?: profileSecretRef(platform.uid)

    private fun legacySecretRef(apiType: ApiType): String = "legacy_${apiType.name.lowercase()}"
}
