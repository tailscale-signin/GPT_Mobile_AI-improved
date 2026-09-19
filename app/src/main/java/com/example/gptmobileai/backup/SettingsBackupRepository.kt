package com.example.gptmobileai.backup

import android.content.Context
import dev.chungjungsoo.gptmobile.data.repository.ChatRepository
import dev.chungjungsoo.gptmobile.data.repository.SettingRepository
import java.io.File

data class BackupOption(
    val title: String,
    val description: String
)

interface SettingsBackupRepository {
    suspend fun createBackup(targetFile: File, passphrase: String): Result<BackupMetadata>
    suspend fun restoreBackup(backupFile: File, passphrase: String): Result<Boolean>
    fun getBackupOptions(): List<BackupOption>
}

class SettingsBackupRepositoryImpl(
    private val context: Context,
    private val backupManager: BackupManager,
    private val settingRepository: SettingRepository
) : SettingsBackupRepository {

    override suspend fun createBackup(targetFile: File, passphrase: String): Result<BackupMetadata> {
        return try {
            val metadata = backupManager.createEncryptedBackup(targetFile, passphrase)
            Result.success(metadata)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun restoreBackup(backupFile: File, passphrase: String): Result<Boolean> {
        return try {
            val res = backupManager.restoreAllFromBackup(backupFile, passphrase)
            Result.success(res.success)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override fun getBackupOptions(): List<BackupOption> = listOf(
        BackupOption("Backup All Data", "Consolidated backup of conversations, credentials, and settings"),
        BackupOption("Restore All Data", "Atomic restore from encrypted backup file with automatic rollback")
    )
}
