package dev.melo.gptmobile.improved.data.backup

import android.content.Context
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import dev.melo.gptmobile.improved.data.database.dao.ChatRoomV2Dao
import dev.melo.gptmobile.improved.data.database.dao.LocalModelDao
import dev.melo.gptmobile.improved.data.database.dao.MessageV2Dao
import dev.melo.gptmobile.improved.data.database.dao.PlatformV2Dao
import dev.melo.gptmobile.improved.data.database.entity.ChatRoomV2
import dev.melo.gptmobile.improved.data.database.entity.LocalModel
import dev.melo.gptmobile.improved.data.database.entity.MessageV2
import dev.melo.gptmobile.improved.data.database.entity.PlatformV2
import dev.melo.gptmobile.improved.data.localmodel.LocalModelStatus
import kotlinx.coroutines.flow.first
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import javax.inject.Inject
import javax.inject.Singleton

data class BackupMetadata(
    val version: Int = 1,
    val createdAt: Long = System.currentTimeMillis(),
    val appVersion: String = "1.0",
    val platformCount: Int = 0,
    val chatRoomCount: Int = 0,
    val messageCount: Int = 0,
    val localModelCount: Int = 0
)

data class BackupData(
    val metadata: BackupMetadata,
    val platforms: List<PlatformV2>,
    val chatRooms: List<ChatRoomV2>,
    val messages: List<MessageV2>,
    val localModels: List<LocalModelBackupItem>
)

data class LocalModelBackupItem(
    val catalogEntryId: String,
    val commitHash: String,
    val fileName: String,
    val relativeDirectory: String,
    val totalBytes: Long,
    val status: String
)

sealed class BackupResult {
    data class Success(val filePath: String, val metadata: BackupMetadata) : BackupResult()
    data class Error(val message: String, val cause: Throwable? = null) : BackupResult()
}

sealed class RestoreResult {
    data class Success(val metadata: BackupMetadata) : RestoreResult()
    data class Error(val message: String, val cause: Throwable? = null) : RestoreResult()
}

@Singleton
class UserBackupManager @Inject constructor(
    private val context: Context,
    private val platformDao: PlatformV2Dao,
    private val chatRoomDao: ChatRoomV2Dao,
    private val messageDao: MessageV2Dao,
    private val localModelDao: LocalModelDao,
    private val gson: Gson
) {
    suspend fun createBackup(outputStream: OutputStream): BackupResult {
        return try {
            val platforms = platformDao.getAll().first()
            val chatRooms = chatRoomDao.getAll().first()
            val messages = messageDao.getAll().first()
            val localModels = localModelDao.getAll().first()

            val metadata = BackupMetadata(
                version = 1,
                createdAt = System.currentTimeMillis(),
                appVersion = "1.0",
                platformCount = platforms.size,
                chatRoomCount = chatRooms.size,
                messageCount = messages.size,
                localModelCount = localModels.size
            )

            val backupItemModels = localModels.map {
                LocalModelBackupItem(
                    catalogEntryId = it.catalogEntryId,
                    commitHash = it.commitHash,
                    fileName = it.fileName,
                    relativeDirectory = it.relativeDirectory,
                    totalBytes = it.totalBytes,
                    status = it.status
                )
            }

            val backupData = BackupData(
                metadata = metadata,
                platforms = platforms,
                chatRooms = chatRooms,
                messages = messages,
                localModels = backupItemModels
            )

            ZipOutputStream(outputStream).use { zipOut ->
                zipOut.putNextEntry(ZipEntry("metadata.json"))
                zipOut.write(gson.toJson(metadata).toByteArray())
                zipOut.closeEntry()

                zipOut.putNextEntry(ZipEntry("platforms.json"))
                zipOut.write(gson.toJson(platforms).toByteArray())
                zipOut.closeEntry()

                zipOut.putNextEntry(ZipEntry("chat_rooms.json"))
                zipOut.write(gson.toJson(chatRooms).toByteArray())
                zipOut.closeEntry()

                zipOut.putNextEntry(ZipEntry("messages.json"))
                zipOut.write(gson.toJson(messages).toByteArray())
                zipOut.closeEntry()

                zipOut.putNextEntry(ZipEntry("local_models.json"))
                zipOut.write(gson.toJson(backupItemModels).toByteArray())
                zipOut.closeEntry()
            }

            BackupResult.Success("", metadata)
        } catch (e: Exception) {
            BackupResult.Error("Backup failed: ${e.message}", e)
        }
    }

    suspend fun restoreBackup(inputStream: InputStream): RestoreResult {
        return try {
            var metadata: BackupMetadata? = null
            var platforms: List<PlatformV2> = emptyList()
            var chatRooms: List<ChatRoomV2> = emptyList()
            var messages: List<MessageV2> = emptyList()
            var localModels: List<LocalModelBackupItem> = emptyList()

            ZipInputStream(inputStream).use { zipIn ->
                var entry = zipIn.nextEntry
                while (entry != null) {
                    val content = zipIn.bufferedReader().readText()
                    when (entry.name) {
                        "metadata.json" -> {
                            metadata = gson.fromJson(content, BackupMetadata::class.java)
                        }
                        "platforms.json" -> {
                            val type = object : TypeToken<List<PlatformV2>>() {}.type
                            platforms = gson.fromJson(content, type) ?: emptyList()
                        }
                        "chat_rooms.json" -> {
                            val type = object : TypeToken<List<ChatRoomV2>>() {}.type
                            chatRooms = gson.fromJson(content, type) ?: emptyList()
                        }
                        "messages.json" -> {
                            val type = object : TypeToken<List<MessageV2>>() {}.type
                            messages = gson.fromJson(content, type) ?: emptyList()
                        }
                        "local_models.json" -> {
                            val type = object : TypeToken<List<LocalModelBackupItem>>() {}.type
                            localModels = gson.fromJson(content, type) ?: emptyList()
                        }
                    }
                    zipIn.closeEntry()
                    entry = zipIn.nextEntry
                }
            }

            val meta = metadata ?: return RestoreResult.Error("Missing metadata.json in backup")

            platforms.forEach { platformDao.upsert(it) }
            chatRooms.forEach { chatRoomDao.upsert(it) }
            messages.forEach { messageDao.upsert(it) }
            localModels.forEach { item ->
                localModelDao.upsert(
                    LocalModel(
                        catalogEntryId = item.catalogEntryId,
                        commitHash = item.commitHash,
                        fileName = item.fileName,
                        relativeDirectory = item.relativeDirectory,
                        totalBytes = item.totalBytes,
                        status = item.status
                    )
                )
            }

            RestoreResult.Success(meta)
        } catch (e: Exception) {
            RestoreResult.Error("Restore failed: ${e.message}", e)
        }
    }
}
