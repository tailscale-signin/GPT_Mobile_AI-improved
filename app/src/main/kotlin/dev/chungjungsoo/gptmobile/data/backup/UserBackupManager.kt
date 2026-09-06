package dev.chungjungsoo.gptmobile.data.backup

import android.content.Context
import dev.chungjungsoo.gptmobile.data.database.ChatDatabaseV2
import dev.chungjungsoo.gptmobile.data.database.entity.ChatPlatformModelV2
import dev.chungjungsoo.gptmobile.data.database.entity.ChatRoomV2
import dev.chungjungsoo.gptmobile.data.database.entity.LocalModel
import dev.chungjungsoo.gptmobile.data.database.entity.MessageV2
import dev.chungjungsoo.gptmobile.data.database.entity.PlatformV2
import dev.chungjungsoo.gptmobile.data.database.entity.ToolConnection
import java.io.InputStream
import java.io.OutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Serializable
data class BackupData(
    val version: Int = 1,
    val platforms: List<PlatformV2> = emptyList(),
    val chatRooms: List<ChatRoomV2> = emptyList(),
    val messages: List<MessageV2> = emptyList(),
    val chatPlatformModels: List<ChatPlatformModelV2> = emptyList(),
    val toolConnections: List<ToolConnection> = emptyList(),
    val localModels: List<LocalModel> = emptyList()
)

class UserBackupManager(
    private val context: Context,
    private val database: ChatDatabaseV2
) {

    private val json = Json {
        prettyPrint = true
        ignoreUnknownKeys = true
    }

    suspend fun createBackup(outputStream: OutputStream): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val platforms = database.platformDao().getPlatforms().first()
            val chatRooms = database.chatRoomDao().getChatRooms().first()
            val messages = chatRooms.flatMap { room ->
                database.messageDao().loadMessages(room.id).first()
            }
            val chatPlatformModels = chatRooms.flatMap { room ->
                database.chatPlatformModelDao().getByChatId(room.id)
            }
            val toolConnections = database.toolConnectionDao().listConnections()
            val localModels = database.localModelDao().getAll()

            val backupData = BackupData(
                version = 1,
                platforms = platforms,
                chatRooms = chatRooms,
                messages = messages,
                chatPlatformModels = chatPlatformModels,
                toolConnections = toolConnections,
                localModels = localModels
            )

            val serialized = json.encodeToString(backupData)
            outputStream.bufferedWriter().use { it.write(serialized) }
        }
    }

    suspend fun restoreBackup(inputStream: InputStream): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val serialized = inputStream.bufferedReader().use { it.readText() }
            val backupData = json.decodeFromString<BackupData>(serialized)

            database.clearAllTables()

            backupData.platforms.forEach { database.platformDao().upsertPlatform(it) }
            backupData.chatRooms.forEach { database.chatRoomDao().insertChatRoom(it) }
            backupData.messages.forEach { database.messageDao().insertMessage(it) }
            if (backupData.chatPlatformModels.isNotEmpty()) {
                database.chatPlatformModelDao().upsertAll(*backupData.chatPlatformModels.toTypedArray())
            }
            backupData.toolConnections.forEach { database.toolConnectionDao().upsertConnection(it) }
            backupData.localModels.forEach { database.localModelDao().upsert(it) }
        }
    }
}
