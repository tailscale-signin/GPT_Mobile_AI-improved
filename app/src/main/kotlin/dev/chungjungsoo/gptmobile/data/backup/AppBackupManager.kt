package dev.chungjungsoo.gptmobile.data.backup

import android.content.Context
import android.net.Uri
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.chungjungsoo.gptmobile.data.database.ChatDatabaseV2
import dev.chungjungsoo.gptmobile.data.database.dao.ChatPlatformModelV2Dao
import dev.chungjungsoo.gptmobile.data.database.dao.ChatRoomV2Dao
import dev.chungjungsoo.gptmobile.data.database.dao.MessageV2Dao
import dev.chungjungsoo.gptmobile.data.database.dao.PlatformV2Dao
import dev.chungjungsoo.gptmobile.data.database.dao.ToolConnectionDao
import dev.chungjungsoo.gptmobile.data.dto.ThemeBackupDto
import dev.chungjungsoo.gptmobile.data.dto.ThemeSetting
import dev.chungjungsoo.gptmobile.data.model.DynamicTheme
import dev.chungjungsoo.gptmobile.data.model.LocalRuntimeBackend
import dev.chungjungsoo.gptmobile.data.model.ThemeMode
import dev.chungjungsoo.gptmobile.data.repository.SettingRepository
import dev.chungjungsoo.gptmobile.data.security.SecretVault
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

data class BackupRestoreResult(
    val success: Boolean,
    val message: String,
    val count: Int = 0
)

data class BackupStatus(
    val lastBackupEpochMs: Long? = null,
    val backupCount: Int = 0
)

@Singleton
class AppBackupManager @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val database: ChatDatabaseV2,
    private val platformV2Dao: PlatformV2Dao,
    private val toolConnectionDao: ToolConnectionDao,
    private val chatPlatformModelV2Dao: ChatPlatformModelV2Dao,
    private val chatRoomV2Dao: ChatRoomV2Dao,
    private val messageV2Dao: MessageV2Dao,
    private val settingRepository: SettingRepository,
    private val secretVault: SecretVault
) {
    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true }

    /**
     * Exports favorites and their custom groups as an encrypted backup file.
     */
    suspend fun exportFavorites(uri: Uri, passphrase: String? = null): BackupRestoreResult = withContext(Dispatchers.IO) {
        runCatching {
            val allMessages = messageV2Dao.getMessageList()
            val favoriteMessages = allMessages.filter { it.isFavorite }
            val favoriteIds = favoriteMessages.map { it.id }
            val favoriteGroups = settingRepository.getFavoriteGroups()
            val messageGroups = settingRepository.getFavoriteMessageGroups()

            val favoriteItemDtos = favoriteMessages.map { msg ->
                FavoriteItemBackupDto(
                    messageId = msg.id,
                    chatId = msg.chatId,
                    content = msg.content,
                    platformType = msg.platformType,
                    isFavorite = msg.isFavorite,
                    createdAt = msg.createdAt,
                    group = messageGroups[msg.id]
                )
            }

            val payload = FavoritesBackupPayload(
                version = 1,
                exportedAt = System.currentTimeMillis(),
                favoriteIds = favoriteIds,
                favoriteGroups = favoriteGroups,
                messageGroups = messageGroups,
                favoriteMessages = favoriteItemDtos
            )

            context.contentResolver.openOutputStream(uri)?.use { outStream ->
                AppBackupCrypto.encryptFavorites(payload, outStream, passphrase)
            } ?: throw IllegalStateException("Could not open destination file for writing.")

            recordBackupMetadata()

            BackupRestoreResult(
                success = true,
                message = "Encrypted favorites exported successfully.",
                count = favoriteIds.size
            )
        }.getOrElse { error ->
            BackupRestoreResult(
                success = false,
                message = error.localizedMessage ?: "Failed to export favorites."
            )
        }
    }

    /**
     * Imports favorites from an encrypted backup file, restoring favorite flags and group mappings.
     */
    suspend fun importFavorites(uri: Uri, passphrase: String? = null): BackupRestoreResult = withContext(Dispatchers.IO) {
        runCatching {
            val payload = context.contentResolver.openInputStream(uri)?.use { inStream ->
                AppBackupCrypto.decryptFavorites(inStream, passphrase)
            } ?: throw IllegalStateException("Could not read favorites file.")

            // Restore custom groups
            if (payload.favoriteGroups.isNotEmpty()) {
                val existingGroups = settingRepository.getFavoriteGroups().toSet()
                val mergedGroups = (existingGroups + payload.favoriteGroups).toList()
                settingRepository.saveFavoriteGroups(mergedGroups)
            }

            // Restore message groups
            if (payload.messageGroups.isNotEmpty()) {
                val existingMap = settingRepository.getFavoriteMessageGroups().toMutableMap()
                existingMap.putAll(payload.messageGroups)
                settingRepository.saveFavoriteMessageGroups(existingMap)
            }

            var count = 0
            payload.favoriteIds.forEach { id ->
                if (id > 0) {
                    messageV2Dao.updateFavorite(id, true)
                    count++
                }
            }

            BackupRestoreResult(
                success = true,
                message = "Favorites restored successfully ($count favorite(s) marked).",
                count = count
            )
        }.getOrElse { error ->
            BackupRestoreResult(
                success = false,
                message = error.localizedMessage ?: "Failed to import favorites."
            )
        }
    }

    /**
     * Exports full advanced settings including AI Platforms, MCP Tool connections & bindings,
     * UI preferences, runtime backend, and favorite group taxonomy.
     */
    suspend fun exportConfiguration(
        uri: Uri,
        passphrase: String? = null,
        options: GranularBackupOptions = GranularBackupOptions()
    ): BackupRestoreResult = withContext(Dispatchers.IO) {
        runCatching {
            val platforms = if (options.includePlatforms) settingRepository.fetchPlatformV2s() else emptyList()
            val theme = settingRepository.fetchThemes()
            val toolConnections = if (options.includeTools) toolConnectionDao.listConnections() else emptyList()
            val toolBindings = if (options.includeTools) {
                platforms.flatMap { p -> toolConnectionDao.listBindingsByProfile(p.uid) }
            } else {
                emptyList()
            }

            val connectionsWithCreds = toolConnections.map { conn ->
                val cred = conn.secretRef?.let { ref ->
                    secretVault.read(ref)?.let { bytes ->
                        try {
                            bytes.decodeToString()
                        } finally {
                            bytes.fill(0)
                        }
                    }
                }
                ToolConnectionWithCredential(connection = conn, credentialPlaintext = cred)
            }

            val uiPreferences = if (options.includeUiPreferences) {
                UiPreferencesBackupDto(
                    themeMode = theme.themeMode.ordinal,
                    dynamicTheme = theme.dynamicTheme == DynamicTheme.ON,
                    debugMode = settingRepository.getDebugMode(),
                    localRuntimeBackend = settingRepository.getLocalRuntimeBackend().name
                )
            } else {
                null
            }

            val favoriteGroups = if (options.includeFavorites) settingRepository.getFavoriteGroups() else emptyList()
            val messageGroups = if (options.includeFavorites) settingRepository.getFavoriteMessageGroups() else emptyMap()

            val payload = ConfigBackupPayload(
                version = 2,
                exportedAt = System.currentTimeMillis(),
                theme = ThemeBackupDto(
                    dynamicTheme = theme.dynamicTheme == DynamicTheme.ON,
                    themeMode = theme.themeMode.ordinal
                ),
                platforms = platforms,
                toolConnections = connectionsWithCreds,
                agentToolBindings = toolBindings,
                favoriteGroups = favoriteGroups,
                messageGroups = messageGroups,
                uiPreferences = uiPreferences
            )

            context.contentResolver.openOutputStream(uri)?.use { outStream ->
                AppBackupCrypto.encryptConfig(payload, outStream, passphrase)
            } ?: throw IllegalStateException("Could not open destination file for writing.")

            recordBackupMetadata()

            BackupRestoreResult(
                success = true,
                message = "Configuration exported successfully.",
                count = platforms.size
            )
        }.getOrElse { error ->
            BackupRestoreResult(
                success = false,
                message = error.localizedMessage ?: "Failed to export configuration."
            )
        }
    }

    /**
     * Restores configuration including AI Platforms, MCP tools, UI preferences, and custom groups.
     */
    suspend fun restoreConfiguration(uri: Uri, passphrase: String? = null): BackupRestoreResult = withContext(Dispatchers.IO) {
        runCatching {
            val payload = context.contentResolver.openInputStream(uri)?.use { inStream ->
                AppBackupCrypto.decryptConfig(inStream, passphrase)
            } ?: throw IllegalStateException("Could not read backup file.")

            // Restore Theme & UI Preferences
            payload.uiPreferences?.let { uiPrefs ->
                val dynamicTheme = if (uiPrefs.dynamicTheme) DynamicTheme.ON else DynamicTheme.OFF
                val themeMode = ThemeMode.getByValue(uiPrefs.themeMode) ?: ThemeMode.SYSTEM
                settingRepository.updateThemes(ThemeSetting(dynamicTheme = dynamicTheme, themeMode = themeMode))
                settingRepository.updateDebugMode(uiPrefs.debugMode)
                settingRepository.updateLocalRuntimeBackend(LocalRuntimeBackend.fromString(uiPrefs.localRuntimeBackend))
            } ?: payload.theme?.let { themeDto ->
                val dynamicTheme = if (themeDto.dynamicTheme) DynamicTheme.ON else DynamicTheme.OFF
                val themeMode = ThemeMode.getByValue(themeDto.themeMode) ?: ThemeMode.SYSTEM
                settingRepository.updateThemes(ThemeSetting(dynamicTheme = dynamicTheme, themeMode = themeMode))
            }

            // Restore Tool Connections
            payload.toolConnections.forEach { item ->
                toolConnectionDao.upsertConnection(item.connection)
                if (!item.credentialPlaintext.isNullOrBlank()) {
                    val secretRef = item.connection.secretRef ?: "connection_${item.connection.connectionUid}"
                    val bytes = item.credentialPlaintext.encodeToByteArray()
                    try {
                        secretVault.put(secretRef, bytes)
                    } finally {
                        bytes.fill(0)
                    }
                }
            }

            // Restore Tool Bindings
            payload.agentToolBindings.forEach { binding ->
                toolConnectionDao.insertBinding(binding)
            }

            // Restore Platforms
            val existingPlatforms = platformV2Dao.getPlatforms()
            payload.platforms.forEach { platform ->
                val existing = existingPlatforms.firstOrNull { it.uid == platform.uid }
                    ?: existingPlatforms.firstOrNull { it.name.equals(platform.name, ignoreCase = true) }

                val resolvedPlatform = if (existing != null) {
                    platform.copy(id = existing.id)
                } else {
                    platform.copy(id = 0)
                }

                if (resolvedPlatform.id > 0) {
                    settingRepository.updatePlatformV2(resolvedPlatform)
                } else {
                    settingRepository.addPlatformV2(resolvedPlatform)
                }
            }

            // Restore Favorite Groups
            if (payload.favoriteGroups.isNotEmpty()) {
                val existing = settingRepository.getFavoriteGroups().toSet()
                settingRepository.saveFavoriteGroups((existing + payload.favoriteGroups).toList())
            }
            if (payload.messageGroups.isNotEmpty()) {
                val existing = settingRepository.getFavoriteMessageGroups().toMutableMap()
                existing.putAll(payload.messageGroups)
                settingRepository.saveFavoriteMessageGroups(existing)
            }

            BackupRestoreResult(
                success = true,
                message = "Configuration restored successfully.",
                count = payload.platforms.size
            )
        }.getOrElse { error ->
            BackupRestoreResult(
                success = false,
                message = error.localizedMessage ?: "Failed to restore configuration."
            )
        }
    }

    /**
     * Exports full database (conversations + messages + models + favorite groups).
     */
    suspend fun exportDatabase(uri: Uri, passphrase: String? = null): BackupRestoreResult = withContext(Dispatchers.IO) {
        runCatching {
            val chatRooms = chatRoomV2Dao.getChatRooms()
            val allMessages = chatRooms.flatMap { room ->
                messageV2Dao.loadMessages(room.id)
            }
            val allModels = chatRooms.flatMap { room ->
                chatPlatformModelV2Dao.getByChatId(room.id)
            }
            val favoriteGroups = settingRepository.getFavoriteGroups()
            val messageGroups = settingRepository.getFavoriteMessageGroups()

            val payload = DatabaseBackupPayload(
                version = 2,
                exportedAt = System.currentTimeMillis(),
                chatRooms = chatRooms,
                messages = allMessages,
                chatPlatformModels = allModels,
                favoriteGroups = favoriteGroups,
                messageGroups = messageGroups
            )

            context.contentResolver.openOutputStream(uri)?.use { outStream ->
                AppBackupCrypto.encryptDatabase(payload, outStream, passphrase)
            } ?: throw IllegalStateException("Could not open destination file for writing.")

            recordBackupMetadata()

            BackupRestoreResult(
                success = true,
                message = "Database exported successfully.",
                count = chatRooms.size
            )
        }.getOrElse { error ->
            BackupRestoreResult(
                success = false,
                message = error.localizedMessage ?: "Failed to export database."
            )
        }
    }

    /**
     * Restores full database, replacing existing chats and restoring favorite groups.
     */
    suspend fun restoreDatabase(uri: Uri, passphrase: String? = null): BackupRestoreResult = withContext(Dispatchers.IO) {
        runCatching {
            val payload = context.contentResolver.openInputStream(uri)?.use { inStream ->
                AppBackupCrypto.decryptDatabase(inStream, passphrase)
            } ?: throw IllegalStateException("Could not read database backup.")

            // Overwrite and replace database cleanly
            val existingChats = chatRoomV2Dao.getChatRooms()
            if (existingChats.isNotEmpty()) {
                chatRoomV2Dao.deleteChatRooms(*existingChats.toTypedArray())
            }

            payload.chatRooms.forEach { room ->
                chatRoomV2Dao.addChatRoom(room)
            }

            if (payload.messages.isNotEmpty()) {
                messageV2Dao.addMessages(*payload.messages.toTypedArray())
            }

            if (payload.chatPlatformModels.isNotEmpty()) {
                chatPlatformModelV2Dao.upsertAll(*payload.chatPlatformModels.toTypedArray())
            }

            if (payload.favoriteGroups.isNotEmpty()) {
                settingRepository.saveFavoriteGroups(payload.favoriteGroups)
            }
            if (payload.messageGroups.isNotEmpty()) {
                settingRepository.saveFavoriteMessageGroups(payload.messageGroups)
            }

            BackupRestoreResult(
                success = true,
                message = "Database restored successfully.",
                count = payload.chatRooms.size
            )
        }.getOrElse { error ->
            BackupRestoreResult(
                success = false,
                message = error.localizedMessage ?: "Failed to restore database."
            )
        }
    }

    /**
     * Returns the latest backup status (timestamp and count).
     */
    fun getBackupStatus(): BackupStatus {
        val prefs = context.getSharedPreferences(PREFS_BACKUP_METADATA, Context.MODE_PRIVATE)
        val lastEpoch = prefs.getLong(KEY_LAST_BACKUP_TIME, 0L)
        val count = prefs.getInt(KEY_BACKUP_COUNT, 0)
        return BackupStatus(
            lastBackupEpochMs = if (lastEpoch > 0L) lastEpoch else null,
            backupCount = count
        )
    }

    private fun recordBackupMetadata() {
        val prefs = context.getSharedPreferences(PREFS_BACKUP_METADATA, Context.MODE_PRIVATE)
        val count = prefs.getInt(KEY_BACKUP_COUNT, 0) + 1
        prefs.edit()
            .putLong(KEY_LAST_BACKUP_TIME, System.currentTimeMillis())
            .putInt(KEY_BACKUP_COUNT, count)
            .apply()
    }

    companion object {
        private const val PREFS_BACKUP_METADATA = "app_backup_metadata"
        private const val KEY_LAST_BACKUP_TIME = "last_backup_epoch_ms"
        private const val KEY_BACKUP_COUNT = "backup_count"
    }
}
