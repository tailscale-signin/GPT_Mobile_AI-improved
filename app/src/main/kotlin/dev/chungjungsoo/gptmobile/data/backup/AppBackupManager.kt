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
import dev.chungjungsoo.gptmobile.data.database.entity.PlatformV2
import dev.chungjungsoo.gptmobile.data.dto.ThemeBackupDto
import dev.chungjungsoo.gptmobile.data.dto.ThemeSetting
import dev.chungjungsoo.gptmobile.data.model.DynamicTheme
import dev.chungjungsoo.gptmobile.data.model.ThemeMode
import dev.chungjungsoo.gptmobile.data.repository.SettingRepository
import dev.chungjungsoo.gptmobile.data.security.SecretVault
import java.io.InputStream
import java.io.OutputStream
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class BackupRestoreResult(
    val success: Boolean,
    val message: String,
    val count: Int = 0
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

    suspend fun exportConfiguration(uri: Uri): BackupRestoreResult = withContext(Dispatchers.IO) {
        runCatching {
            val platforms = settingRepository.fetchPlatformV2s()
            val theme = settingRepository.fetchThemes()
            val toolConnections = toolConnectionDao.listConnections()
            val toolBindings = platforms.flatMap { p ->
                toolConnectionDao.listBindingsByProfile(p.uid)
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

            val payload = ConfigBackupPayload(
                version = 1,
                exportedAt = System.currentTimeMillis(),
                theme = ThemeBackupDto(
                    dynamicTheme = theme.dynamicTheme == DynamicTheme.ON,
                    themeMode = theme.themeMode.ordinal
                ),
                platforms = platforms,
                toolConnections = connectionsWithCreds,
                agentToolBindings = toolBindings
            )

            context.contentResolver.openOutputStream(uri)?.use { outStream ->
                AppBackupCrypto.encryptConfig(payload, outStream)
            } ?: throw IllegalStateException("Could not open destination file for writing.")

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

    suspend fun restoreConfiguration(uri: Uri): BackupRestoreResult = withContext(Dispatchers.IO) {
        runCatching {
            val payload = context.contentResolver.openInputStream(uri)?.use { inStream ->
                AppBackupCrypto.decryptConfig(inStream)
            } ?: throw IllegalStateException("Could not read backup file.")

            payload.theme?.let { themeDto ->
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

    suspend fun exportDatabase(uri: Uri): BackupRestoreResult = withContext(Dispatchers.IO) {
        runCatching {
            val chatRooms = chatRoomV2Dao.getChatRooms()
            val allMessages = chatRooms.flatMap { room ->
                messageV2Dao.loadMessages(room.id)
            }
            val allModels = chatRooms.flatMap { room ->
                chatPlatformModelV2Dao.getByChatId(room.id)
            }

            val payload = DatabaseBackupPayload(
                version = 1,
                exportedAt = System.currentTimeMillis(),
                chatRooms = chatRooms,
                messages = allMessages,
                chatPlatformModels = allModels
            )

            context.contentResolver.openOutputStream(uri)?.use { outStream ->
                AppBackupCrypto.encryptDatabase(payload, outStream)
            } ?: throw IllegalStateException("Could not open destination file for writing.")

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

    suspend fun restoreDatabase(uri: Uri): BackupRestoreResult = withContext(Dispatchers.IO) {
        runCatching {
            val payload = context.contentResolver.openInputStream(uri)?.use { inStream ->
                AppBackupCrypto.decryptDatabase(inStream)
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
}
