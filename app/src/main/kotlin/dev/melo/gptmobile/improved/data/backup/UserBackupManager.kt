package dev.melo.gptmobile.improved.data.backup

import android.content.Context
import android.net.Uri
import dev.melo.gptmobile.improved.data.database.ChatDatabaseV2
import dev.melo.gptmobile.improved.data.database.entity.ChatPlatformModelV2
import dev.melo.gptmobile.improved.data.database.entity.ChatRoomV2
import dev.melo.gptmobile.improved.data.database.entity.LocalModel
import dev.melo.gptmobile.improved.data.database.entity.MessageV2
import dev.melo.gptmobile.improved.data.database.entity.PlatformV2
import dev.melo.gptmobile.improved.data.database.entity.ToolConnection
import dev.melo.gptmobile.improved.data.model.PlatformType
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
 * Serializable DTOs representing backup payload data.
 */
@Serializable
data class BackupChatRoomDto(
    val id: Int? = null,
    val title: String? = null,
    val createdAt: Long = 0L,
    val updatedAt: Long = 0L,
    val isFavorite: Boolean = false
)

@Serializable
data class BackupMessageDto(
    val id: Int? = null,
    val chatId: Int = 0,
    val role: Int = 0,
    val content: String = "",
    val createdAt: Long = 0L,
    val platformType: String? = null,
    val isFavorite: Boolean = false,
    val modelName: String? = null
)

@Serializable
data class BackupPlatformDto(
    val platformUid: String = "",
    val name: String = "",
    val platformType: String = "",
    val endpoint: String? = null,
    val token: String? = null,
    val selectedModel: String? = null,
    val customApiName: String? = null,
    val secretRef: String? = null
)

@Serializable
data class BackupChatPlatformModelDto(
    val id: Int? = null,
    val chatId: Int = 0,
    val platformUid: String = "",
    val modelName: String = ""
)

@Serializable
data class BackupToolConnectionDto(
    val id: Long? = null,
    val name: String = "",
    val transportType: String = "",
    val endpointOrCommand: String = "",
    val headersOrArgs: String? = null,
    val env: String? = null,
    val isEnabled: Boolean = true,
    val createdAt: Long = 0L,
    val updatedAt: Long = 0L
)

@Serializable
data class BackupLocalModelDto(
    val modelName: String = "",
    val modelPath: String = "",
    val isDownloaded: Boolean = false,
    val downloadProgress: Float = 0f,
    val totalBytes: Long = 0L,
    val downloadedBytes: Long = 0L
)

@Serializable
data class UserBackupData(
    val version: Int = BACKUP_VERSION,
    val exportedAt: Long = System.currentTimeMillis(),
    val chatRooms: List<BackupChatRoomDto> = emptyList(),
    val messages: List<BackupMessageDto> = emptyList(),
    val platforms: List<BackupPlatformDto> = emptyList(),
    val models: List<BackupChatPlatformModelDto> = emptyList(),
    val toolConnections: List<BackupToolConnectionDto> = emptyList(),
    val localModels: List<BackupLocalModelDto> = emptyList()
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
            database.chatRoomDao().getChatRooms().map {
                BackupChatRoomDto(
                    id = it.id,
                    title = it.title,
                    createdAt = it.createdAt,
                    updatedAt = it.updatedAt,
                    isFavorite = it.isFavorite
                )
            }
        } else {
            emptyList()
        }

        val messages = if (options.includeChatHistory) {
            val rooms = database.chatRoomDao().getChatRooms()
            rooms.flatMap { room ->
                val chatId = room.id ?: return@flatMap emptyList<BackupMessageDto>()
                database.messageDao().loadMessages(chatId).map { msg ->
                    BackupMessageDto(
                        id = msg.id,
                        chatId = msg.chatId,
                        role = msg.role,
                        content = msg.content,
                        createdAt = msg.createdAt,
                        platformType = msg.platformType?.name,
                        isFavorite = msg.isFavorite,
                        modelName = msg.modelName
                    )
                }
            }
        } else {
            emptyList()
        }

        val platforms = if (options.includePlatforms) {
            database.platformDao().getPlatforms().map { platform ->
                BackupPlatformDto(
                    platformUid = platform.platformUid,
                    name = platform.name,
                    platformType = platform.platformType.name,
                    endpoint = platform.endpoint,
                    token = if (options.includeTokens) platform.token else null,
                    selectedModel = platform.selectedModel,
                    customApiName = platform.customApiName,
                    secretRef = if (options.includeTokens) platform.secretRef else null
                )
            }
        } else {
            emptyList()
        }

        val models = if (options.includeModels) {
            val rooms = database.chatRoomDao().getChatRooms()
            rooms.flatMap { room ->
                val chatId = room.id ?: return@flatMap emptyList<BackupChatPlatformModelDto>()
                database.chatPlatformModelDao().getByChatId(chatId).map { model ->
                    BackupChatPlatformModelDto(
                        id = model.id,
                        chatId = model.chatId,
                        platformUid = model.platformUid,
                        modelName = model.modelName
                    )
                }
            }
        } else {
            emptyList()
        }

        val toolConnections = if (options.includeTools) {
            database.toolConnectionDao().getAllConnections().map { conn ->
                BackupToolConnectionDto(
                    id = conn.id,
                    name = conn.name,
                    transportType = conn.transportType,
                    endpointOrCommand = conn.endpointOrCommand,
                    headersOrArgs = conn.headersOrArgs,
                    env = conn.env,
                    isEnabled = conn.isEnabled,
                    createdAt = conn.createdAt,
                    updatedAt = conn.updatedAt
                )
            }
        } else {
            emptyList()
        }

        val localModels = if (options.includeModels) {
            database.localModelDao().getAll().map { lm ->
                BackupLocalModelDto(
                    modelName = lm.modelName,
                    modelPath = lm.modelPath,
                    isDownloaded = lm.isDownloaded,
                    downloadProgress = lm.downloadProgress,
                    totalBytes = lm.totalBytes,
                    downloadedBytes = lm.downloadedBytes
                )
            }
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
            (database as androidx.room.RoomDatabase).clearAllTables()
        }

        var platformsCount = 0
        data.platforms.forEach { dto ->
            val platformType = runCatching { PlatformType.valueOf(dto.platformType) }.getOrDefault(PlatformType.CUSTOM)
            val platform = PlatformV2(
                platformUid = dto.platformUid,
                name = dto.name,
                platformType = platformType,
                endpoint = dto.endpoint,
                token = dto.token,
                selectedModel = dto.selectedModel,
                customApiName = dto.customApiName,
                secretRef = dto.secretRef
            )
            database.platformDao().addPlatform(platform)
            platformsCount++
        }

        var modelsCount = 0
        data.models.forEach { dto ->
            val model = ChatPlatformModelV2(
                id = dto.id,
                chatId = dto.chatId,
                platformUid = dto.platformUid,
                modelName = dto.modelName
            )
            database.chatPlatformModelDao().upsertAll(model)
            modelsCount++
        }

        var localModelsCount = 0
        data.localModels.forEach { dto ->
            val localModel = LocalModel(
                modelName = dto.modelName,
                modelPath = dto.modelPath,
                isDownloaded = dto.isDownloaded,
                downloadProgress = dto.downloadProgress,
                totalBytes = dto.totalBytes,
                downloadedBytes = dto.downloadedBytes
            )
            database.localModelDao().upsert(localModel)
            localModelsCount++
        }

        var chatRoomsCount = 0
        data.chatRooms.forEach { dto ->
            val room = ChatRoomV2(
                id = dto.id,
                title = dto.title,
                createdAt = dto.createdAt,
                updatedAt = dto.updatedAt,
                isFavorite = dto.isFavorite
            )
            database.chatRoomDao().addChatRoom(room)
            chatRoomsCount++
        }

        var messagesCount = 0
        if (data.messages.isNotEmpty()) {
            val messageEntities = data.messages.map { dto ->
                val platformType = dto.platformType?.let { runCatching { PlatformType.valueOf(it) }.getOrNull() }
                MessageV2(
                    id = dto.id,
                    chatId = dto.chatId,
                    role = dto.role,
                    content = dto.content,
                    createdAt = dto.createdAt,
                    platformType = platformType,
                    isFavorite = dto.isFavorite,
                    modelName = dto.modelName
                )
            }
            database.messageDao().addMessages(*messageEntities.toTypedArray())
            messagesCount = messageEntities.size
        }

        var toolConnectionsCount = 0
        data.toolConnections.forEach { dto ->
            val conn = ToolConnection(
                id = dto.id,
                name = dto.name,
                transportType = dto.transportType,
                endpointOrCommand = dto.endpointOrCommand,
                headersOrArgs = dto.headersOrArgs,
                env = dto.env,
                isEnabled = dto.isEnabled,
                createdAt = conn.createdAt,
                updatedAt = conn.updatedAt
            )
            database.toolConnectionDao().upsertConnection(conn)
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

    private fun readPayload(inputStream: InputStream): String {
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
