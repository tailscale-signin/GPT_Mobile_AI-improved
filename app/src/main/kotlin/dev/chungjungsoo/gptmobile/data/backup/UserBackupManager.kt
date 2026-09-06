package dev.chungjungsoo.gptmobile.data.backup

import android.content.Context
import android.net.Uri
import dev.chungjungsoo.gptmobile.data.database.ChatDatabaseV2
import dev.chungjungsoo.gptmobile.data.database.entity.AgentRun
import dev.chungjungsoo.gptmobile.data.database.entity.AgentToolBinding
import dev.chungjungsoo.gptmobile.data.database.entity.ChatPlatformModelV2
import dev.chungjungsoo.gptmobile.data.database.entity.ChatRoomV2
import dev.chungjungsoo.gptmobile.data.database.entity.LocalModel
import dev.chungjungsoo.gptmobile.data.database.entity.MessageV2
import dev.chungjungsoo.gptmobile.data.database.entity.PlatformV2
import dev.chungjungsoo.gptmobile.data.database.entity.ToolConnection
import dev.chungjungsoo.gptmobile.data.database.entity.ToolEvent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.InputStream
import java.io.OutputStream
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

/**
 * Backup payload data structure representing user chats, settings, models, and tools.
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
    val localModels: List<LocalModel> = emptyList()
) {
    companion object {
        const val BACKUP_VERSION = 1
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
    val compress: Boolean = true
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
 * Manages user-facing export and import of chats, platforms, models, and tools.
 */
class UserBackupManager(
    private val context: Context,
    private val database: ChatDatabaseV2
) {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        prettyPrint = false
    }

    /**
     * Exports backup data to the destination URI.
     */
    suspend fun exportBackup(
        destinationUri: Uri,
        options: BackupExportOptions = BackupExportOptions()
    ): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val backupData = buildBackupData(options)
            val jsonString = json.encodeToString(backupData)

            context.contentResolver.openOutputStream(destinationUri)?.use { rawOutput ->
                writePayload(rawOutput, jsonString, options.compress)
            } ?: throw IllegalStateException("Could not open output stream for URI: $destinationUri")
        }
    }

    /**
     * Imports backup data from the source URI into the database.
     */
    suspend fun importBackup(
        sourceUri: Uri,
        clearExisting: Boolean = false
    ): Result<BackupImportResult> = withContext(Dispatchers.IO) {
        runCatching {
            val jsonString = context.contentResolver.openInputStream(sourceUri)?.use { rawInput ->
                readPayload(rawInput)
            } ?: throw IllegalStateException("Could not open input stream for URI: $sourceUri")

            val backupData = json.decodeFromString<UserBackupData>(jsonString)
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
    ): BackupImportResult {
        if (clearExisting) {
            database.clearAllTables()
        }

        var platformsCount = 0
        data.platforms.forEach { platform ->
            database.platformDao().addPlatform(platform)
            platformsCount++
        }

        var modelsCount = 0
        data.models.forEach { model ->
            database.chatPlatformModelDao().upsertChatPlatformModel(model)
            modelsCount++
        }

        var localModelsCount = 0
        data.localModels.forEach { localModel ->
            database.localModelDao().upsert(localModel)
            localModelsCount++
        }

        var chatRoomsCount = 0
        data.chatRooms.forEach { room ->
            database.chatRoomDao().addChatRoom(room)
            chatRoomsCount++
        }

        var messagesCount = 0
        if (data.messages.isNotEmpty()) {
            database.messageDao().insertMessageList(data.messages)
            messagesCount = data.messages.size
        }

        var toolConnectionsCount = 0
        data.toolConnections.forEach { connection ->
            database.toolConnectionDao().upsertConnection(connection)
            toolConnectionsCount++
        }

        return BackupImportResult(
            chatRoomsImported = chatRoomsCount,
            messagesImported = messagesCount,
            platformsImported = platformsCount,
            modelsImported = modelsCount,
            toolConnectionsImported = toolConnectionsCount,
            localModelsImported = localModelsCount
        )
    }

    private fun writePayload(outputStream: OutputStream, content: String, compress: Boolean) {
        if (compress) {
            GZIPOutputStream(outputStream).bufferedWriter(Charsets.UTF_8).use { writer ->
                writer.write(content)
            }
        } else {
            outputStream.bufferedWriter(Charsets.UTF_8).use { writer ->
                writer.write(content)
            }
        }
    }

    private fun readPayload(inputStream: java.io.InputStream): String {
        val bufferedInput = inputStream.buffered()
        bufferedInput.mark(2)
        val header = ByteArray(2)
        val read = bufferedInput.read(header)
        bufferedInput.reset()

        val isGzip = read == 2 && (header[0] == 0x1f.toByte()) && (header[1] == 0x8b.toByte())
        val effectiveInput: InputStream = if (isGzip) {
            GZIPInputStream(bufferedInput)
        } else {
            bufferedInput
        }

        return effectiveInput.bufferedReader(Charsets.UTF_8).use { it.readText() }
    }
}
