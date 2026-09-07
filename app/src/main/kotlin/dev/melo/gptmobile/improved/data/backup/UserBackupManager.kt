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
import dev.melo.gptmobile.improved.data.model.ClientType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
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
    val isFavorite: Boolean = false,
    val activePlatformUid: String = "",
    val systemPrompt: String? = null,
    val temperature: Float? = null,
    val topP: Float? = null,
    val maxTokens: Int? = null,
    val presencePenalty: Float? = null,
    val frequencyPenalty: Float? = null,
    val enabledMcpServers: List<String> = emptyList(),
    val mcpToolExecutionMode: String = "ALWAYS_APPROVE"
)

@Serializable
data class BackupMessageDto(
    val id: Int? = null,
    val chatId: Int = 0,
    val sender: Int = 0,
    val thoughts: String = "",
    val content: String = "",
    val createdAt: Long = 0L,
    val platformType: String? = null,
    val isFavorite: Boolean = false,
    val currentRunId: String? = null,
    val linkedMessageId: Int = 0
)

@Serializable
data class BackupPlatformDto(
    val platformUid: String = "",
    val name: String = "",
    val compatibleType: String = "",
    val apiUrl: String = "",
    val token: String? = null,
    val secretRef: String? = null,
    val model: String = "",
    val enabled: Boolean = false,
    val temperature: Float? = null,
    val topP: Float? = null,
    val topK: Int? = null,
    val maxTokens: Int? = null,
    val accelerator: String? = null,
    val systemPrompt: String? = null,
    val stream: Boolean = true,
    val reasoning: Boolean = false,
    val timeout: Int = 30,
    val harassmentSafetyThreshold: String = "BLOCK_NONE",
    val hateSpeechSafetyThreshold: String = "BLOCK_NONE",
    val sexuallyExplicitSafetyThreshold: String = "BLOCK_NONE",
    val dangerousContentSafetyThreshold: String = "BLOCK_NONE"
)

@Serializable
data class BackupChatPlatformModelDto(
    val id: Int? = null,
    val chatId: Int = 0,
    val platformUid: String = "",
    val modelName: String = "",
    val updatedAt: Long = 0L
)

@Serializable
data class BackupToolConnectionDto(
    val connectionUid: String = "",
    val name: String = "",
    val alias: String = "",
    val type: String = "MCP",
    val serverName: String = "",
    val transportType: String = "",
    val endpointUrl: String? = null,
    val authType: String = "NONE",
    val secretRef: String? = null,
    val oauthClientId: String? = null,
    val allowCleartext: Boolean = false,
    val headers: String? = null,
    val createdAt: Long = 0L,
    val updatedAt: Long = 0L
)

@Serializable
data class BackupLocalModelDto(
    val id: String = "",
    val displayName: String = "",
    val modelName: String = "",
    val filePath: String = "",
    val isDownloaded: Boolean = false,
    val downloadProgress: Float = 0f,
    val createdAt: Long = 0L
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
            database.chatRoomDao().getAll().first().map {
                BackupChatRoomDto(
                    id = it.chatId,
                    title = it.title,
                    createdAt = it.createdAt,
                    isFavorite = it.pinned,
                    activePlatformUid = it.activePlatformUid,
                    systemPrompt = it.systemPrompt,
                    temperature = it.temperature,
                    topP = it.topP,
                    maxTokens = it.maxTokens,
                    presencePenalty = it.presencePenalty,
                    frequencyPenalty = it.frequencyPenalty,
                    enabledMcpServers = it.enabledMcpServers,
                    mcpToolExecutionMode = it.mcpToolExecutionMode
                )
            }
        } else {
            emptyList()
        }

        val messages = if (options.includeChatHistory) {
            val rooms = database.chatRoomDao().getAll().first()
            rooms.flatMap { room ->
                database.messageDao().getMessagesDirect(room.chatId).map { msg ->
                    BackupMessageDto(
                        id = msg.id,
                        chatId = msg.chatId,
                        sender = msg.sender,
                        thoughts = msg.thoughts,
                        content = msg.content,
                        createdAt = msg.createdAt,
                        platformType = msg.platformType,
                        isFavorite = msg.isFavorite,
                        currentRunId = msg.currentRunId,
                        linkedMessageId = msg.linkedMessageId
                    )
                }
            }
        } else {
            emptyList()
        }

        val platforms = if (options.includePlatforms) {
            database.platformDao().getAllDirect().map { platform ->
                BackupPlatformDto(
                    platformUid = platform.uid,
                    name = platform.name,
                    compatibleType = platform.compatibleType.name,
                    apiUrl = platform.apiUrl,
                    token = if (options.includeTokens) platform.token else null,
                    secretRef = if (options.includeTokens) platform.secretRef else null,
                    model = platform.model,
                    enabled = platform.enabled,
                    temperature = platform.temperature,
                    topP = platform.topP,
                    topK = platform.topK,
                    maxTokens = platform.maxTokens,
                    accelerator = platform.accelerator,
                    systemPrompt = platform.systemPrompt,
                    stream = platform.stream,
                    reasoning = platform.reasoning,
                    timeout = platform.timeout,
                    harassmentSafetyThreshold = platform.harassmentSafetyThreshold,
                    hateSpeechSafetyThreshold = platform.hateSpeechSafetyThreshold,
                    sexuallyExplicitSafetyThreshold = platform.sexuallyExplicitSafetyThreshold,
                    dangerousContentSafetyThreshold = platform.dangerousContentSafetyThreshold
                )
            }
        } else {
            emptyList()
        }

        val models = if (options.includeModels) {
            val rooms = database.chatRoomDao().getAll().first()
            rooms.flatMap { room ->
                database.chatPlatformModelDao().getModelsByChatId(room.chatId).map { model ->
                    BackupChatPlatformModelDto(
                        id = model.id,
                        chatId = model.chatId,
                        platformUid = model.platformUid,
                        modelName = model.modelName,
                        updatedAt = model.updatedAt
                    )
                }
            }
        } else {
            emptyList()
        }

        val toolConnections = if (options.includeTools) {
            database.toolConnectionDao().listConnections().map { conn ->
                BackupToolConnectionDto(
                    connectionUid = conn.connectionUid,
                    name = conn.name,
                    alias = conn.alias,
                    type = conn.type,
                    serverName = conn.serverName,
                    transportType = conn.transportType,
                    endpointUrl = conn.endpointUrl,
                    authType = conn.authType,
                    secretRef = conn.secretRef,
                    oauthClientId = conn.oauthClientId,
                    allowCleartext = conn.allowCleartext,
                    headers = conn.headers,
                    createdAt = conn.createdAt,
                    updatedAt = conn.updatedAt
                )
            }
        } else {
            emptyList()
        }

        val localModels = if (options.includeModels) {
            database.localModelDao().getAllModels().first().map { lm ->
                BackupLocalModelDto(
                    id = lm.id,
                    displayName = lm.displayName,
                    modelName = lm.modelName,
                    filePath = lm.filePath,
                    isDownloaded = lm.isDownloaded,
                    downloadProgress = lm.downloadProgress,
                    createdAt = lm.createdAt
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
            val compatibleType = runCatching { ClientType.valueOf(dto.compatibleType) }.getOrDefault(ClientType.CUSTOM)
            val platform = PlatformV2(
                uid = dto.platformUid,
                name = dto.name,
                compatibleType = compatibleType,
                apiUrl = dto.apiUrl,
                token = dto.token,
                secretRef = dto.secretRef,
                model = dto.model,
                enabled = dto.enabled,
                temperature = dto.temperature,
                topP = dto.topP,
                topK = dto.topK,
                maxTokens = dto.maxTokens,
                accelerator = dto.accelerator,
                systemPrompt = dto.systemPrompt,
                stream = dto.stream,
                reasoning = dto.reasoning,
                timeout = dto.timeout,
                harassmentSafetyThreshold = dto.harassmentSafetyThreshold,
                hateSpeechSafetyThreshold = dto.hateSpeechSafetyThreshold,
                sexuallyExplicitSafetyThreshold = dto.sexuallyExplicitSafetyThreshold,
                dangerousContentSafetyThreshold = dto.dangerousContentSafetyThreshold
            )
            database.platformDao().upsert(platform)
            platformsCount++
        }

        var chatRoomsCount = 0
        data.chatRooms.forEach { dto ->
            val room = ChatRoomV2(
                chatId = dto.id ?: 0,
                title = dto.title ?: "Chat",
                activePlatformUid = dto.activePlatformUid,
                createdAt = dto.createdAt,
                pinned = dto.isFavorite,
                systemPrompt = dto.systemPrompt,
                temperature = dto.temperature,
                topP = dto.topP,
                maxTokens = dto.maxTokens,
                presencePenalty = dto.presencePenalty,
                frequencyPenalty = dto.frequencyPenalty,
                enabledMcpServers = dto.enabledMcpServers,
                mcpToolExecutionMode = dto.mcpToolExecutionMode
            )
            database.chatRoomDao().upsert(room)
            chatRoomsCount++
        }

        var modelsCount = 0
        val modelEntities = data.models.map { dto ->
            ChatPlatformModelV2(
                id = dto.id ?: 0,
                chatId = dto.chatId,
                platformUid = dto.platformUid,
                modelName = dto.modelName,
                updatedAt = dto.updatedAt
            )
        }
        if (modelEntities.isNotEmpty()) {
            database.chatPlatformModelDao().upsertAll(modelEntities)
            modelsCount = modelEntities.size
        }

        var localModelsCount = 0
        data.localModels.forEach { dto ->
            val localModel = LocalModel(
                id = dto.id,
                displayName = dto.displayName,
                modelName = dto.modelName,
                filePath = dto.filePath,
                isDownloaded = dto.isDownloaded,
                downloadProgress = dto.downloadProgress,
                createdAt = dto.createdAt
            )
            database.localModelDao().insertModel(localModel)
            localModelsCount++
        }

        var messagesCount = 0
        data.messages.forEach { dto ->
            val message = MessageV2(
                id = dto.id ?: 0,
                chatId = dto.chatId,
                sender = dto.sender,
                thoughts = dto.thoughts,
                content = dto.content,
                createdAt = dto.createdAt,
                platformType = dto.platformType,
                isFavorite = dto.isFavorite,
                currentRunId = dto.currentRunId,
                linkedMessageId = dto.linkedMessageId
            )
            database.messageDao().upsert(message)
            messagesCount++
        }

        var toolConnectionsCount = 0
        data.toolConnections.forEach { dto ->
            val conn = ToolConnection(
                connectionUid = dto.connectionUid,
                name = dto.name,
                alias = dto.alias,
                type = dto.type,
                serverName = dto.serverName,
                transportType = dto.transportType,
                endpointUrl = dto.endpointUrl,
                authType = dto.authType,
                secretRef = dto.secretRef,
                oauthClientId = dto.oauthClientId,
                allowCleartext = dto.allowCleartext,
                headers = dto.headers,
                createdAt = dto.createdAt,
                updatedAt = dto.updatedAt
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
