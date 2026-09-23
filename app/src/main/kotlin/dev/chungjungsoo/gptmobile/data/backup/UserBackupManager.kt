package dev.chungjungsoo.gptmobile.data.backup

import android.content.Context
import android.net.Uri
import androidx.room.withTransaction
import dev.chungjungsoo.gptmobile.data.database.ChatDatabaseV2
import dev.chungjungsoo.gptmobile.data.database.entity.ChatPlatformModelV2
import dev.chungjungsoo.gptmobile.data.database.entity.ChatRoomV2
import dev.chungjungsoo.gptmobile.data.database.entity.LocalModel
import dev.chungjungsoo.gptmobile.data.database.entity.MessageV2
import dev.chungjungsoo.gptmobile.data.database.entity.PlatformV2
import dev.chungjungsoo.gptmobile.data.database.entity.ToolConnection
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.InputStream
import java.io.OutputStream

/**
 * Backup payload data structure representing user chats, settings, models, tools, and favorites.
 */
@Serializable
data class UserBackupData(
    val version: Int = BACKUP_VERSION,
    val exportedAt: Long = System.currentTimeMillis(),
    val chatRooms: List<ChatRoomV2> = emptyList(),
    val messages: List<MessageV2> = emptyList(),
    val platforms: List<PlatformV2> = emptyList(),
    val models: List<ChatPlatformModelV2> = emptyList(),
    val toolConnections: List<ToolConnection> = emptyList(),
    val localModels: List<LocalModel> = emptyList(),
    val favoriteGroups: List<String> = emptyList(),
    val messageGroups: Map<Int, String> = emptyMap()
) {
    companion object {
        const val BACKUP_VERSION = 2
    }
}

/**
 * Options for exporting user backups.
 */
data class BackupExportOptions(
    val includeChatHistory: Boolean = true,
    val includePlatforms: Boolean = true,
    val includeTokens: Boolean = false,
    val includeModels: Boolean = true,
    val includeTools: Boolean = true,
    val includeFavorites: Boolean = true,
    val passphrase: String? = null
)

/**
 * Result returned after performing a backup import.
 */
data class BackupImportResult(
    val chatRoomsImported: Int = 0,
    val messagesImported: Int = 0,
    val platformsImported: Int = 0,
    val modelsImported: Int = 0,
    val toolConnectionsImported: Int = 0,
    val localModelsImported: Int = 0
)

/**
 * Manages user-facing export and import of chats, platforms, models, and tools with encryption.
 */
class UserBackupManager(
    private val context: Context,
    private val database: ChatDatabaseV2
) {
    /**
     * Exports backup data with AES-256-GCM encryption to the destination URI.
     */
    suspend fun exportBackup(
        destinationUri: Uri,
        options: BackupExportOptions = BackupExportOptions()
    ): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val backupData = buildBackupData(options)
            context.contentResolver.openOutputStream(destinationUri)?.use { rawOutput ->
                AppBackupCrypto.encryptUserBackup(backupData, rawOutput, options.passphrase)
            } ?: throw IllegalStateException("Could not open output stream for URI: $destinationUri")
        }
    }

    /**
     * Decrypts and imports backup data from the source URI into the database.
     */
    suspend fun importBackup(
        sourceUri: Uri,
        clearExisting: Boolean = false,
        passphrase: String? = null
    ): Result<BackupImportResult> = withContext(Dispatchers.IO) {
        runCatching {
            val backupData = context.contentResolver.openInputStream(sourceUri)?.use { rawInput ->
                AppBackupCrypto.decryptUserBackup(rawInput, passphrase)
            } ?: throw IllegalStateException("Could not open input stream for URI: $sourceUri")

            restoreBackupData(backupData, clearExisting)
        }
    }

    private suspend fun buildBackupData(options: BackupExportOptions): UserBackupData {
        val chatRooms = if (options.includeChatHistory) {
            database.chatRoomDao().getChatRooms()
        } else {
            emptyList()
        }

        val messages = if (options.includeChatHistory) {
            database.messageDao().getMessageList()
        } else {
            emptyList()
        }

        val platforms = if (options.includePlatforms) {
            database.platformDao().getPlatforms().map { platform ->
                if (!options.includeTokens) {
                    platform.copy(token = null, secretRef = null)
                } else {
                    platform
                }
            }
        } else {
            emptyList()
        }

        val models = if (options.includeModels) {
            database.chatPlatformModelDao().getChatPlatformModels()
        } else {
            emptyList()
        }

        val toolConnections = if (options.includeTools) {
            database.toolConnectionDao().getAllConnections()
        } else {
            emptyList()
        }

        val localModels = if (options.includeModels) {
            database.localModelDao().getAll()
        } else {
            emptyList()
        }

        return UserBackupData(
            chatRooms = chatRooms,
            messages = messages,
            platforms = platforms,
            models = models,
            toolConnections = toolConnections,
            localModels = localModels
        )
    }

    private suspend fun restoreBackupData(
        data: UserBackupData,
        clearExisting: Boolean
    ): BackupImportResult = database.withTransaction {
        if (clearExisting) {
            database.clearAllTables()
        }

        data.platforms.forEach { platform ->
            database.platformDao().addPlatform(platform)
        }

        data.models.forEach { model ->
            database.chatPlatformModelDao().upsertChatPlatformModel(model)
        }

        data.localModels.forEach { localModel ->
            database.localModelDao().upsert(localModel)
        }

        data.chatRooms.forEach { room ->
            database.chatRoomDao().addChatRoom(room)
        }

        if (data.messages.isNotEmpty()) {
            database.messageDao().insertMessageList(data.messages)
        }

        data.toolConnections.forEach { connection ->
            database.toolConnectionDao().upsertConnection(connection)
        }

        BackupImportResult(
            chatRoomsImported = data.chatRooms.size,
            messagesImported = data.messages.size,
            platformsImported = data.platforms.size,
            modelsImported = data.models.size,
            toolConnectionsImported = data.toolConnections.size,
            localModelsImported = data.localModels.size
        )
    }
}
